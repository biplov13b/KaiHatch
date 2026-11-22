package com.biplov.kaihatch.data

import android.content.Context
import com.biplov.kaihatch.engine.CheckpointableInflateEngine
import com.biplov.kaihatch.model.RestorationCheckpointState
import com.biplov.kaihatch.model.RestorationPhase
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.ZipEntryInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

/**
 * Crash-consistent resume journal manager for KaiHatch in-place extraction.
 *
 * Guarantees:
 * 1. Unlimited Entry Table Support (>5,000+ entries): Persists the initial descending entry index
 *    once to a streaming binary file (`<stateFilePath>.index.bin`) protected by a trailing CRC32
 *    checksum and `FileDescriptor.sync()` (`fsync`) before Central Directory truncation.
 * 2. Fast Atomic Checkpoints: Each truncation checkpoint writes `.tmp`, flushes, calls `fd.sync()`,
 *    and atomically renames to the target state file without re-serializing tens of thousands of
 *    unchanged `ZipEntryInfo` records on every window.
 * 3. Trustworthy Output Verification: Stores verified CRC32 digests for completed entries and
 *    verifies output file content via full-stream CRC32 (`verifyOutputFileIntegrity`) rather than
 *    relying solely on file existence or file length.
 */
object ResumeJournalManager {

    private const val INDEX_MAGIC = 0x4B484958 // "KHIX"
    private const val INDEX_VERSION = 1
    private const val CKPT_MAGIC = 0x4B484350 // "KHCP"
    private const val CKPT_VERSION = 2
    private const val RESTORE_CKPT_MAGIC = 0x4B485253 // "KHRS"
    private const val RESTORE_CKPT_VERSION = 1
    private const val INLINE_JSON_ENTRY_LIMIT = 256

    private fun getJournalsDir(context: Context): File {
        val dir = File(context.filesDir, "hatch_journals")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getStateFileForArchive(context: Context, archiveIdentifier: String): File {
        val rawSafeId = archiveIdentifier.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeId = if (rawSafeId.length > 100) {
            val hashHex = java.lang.Long.toHexString(
                CRC32().apply { update(archiveIdentifier.toByteArray(Charsets.UTF_8)) }.value
            )
            "${rawSafeId.takeLast(72)}_$hashHex"
        } else {
            rawSafeId
        }
        return File(getJournalsDir(context), "$safeId.hatchstate.json")
    }

    internal enum class PersistenceFaultStage {
        AFTER_TMP_PARTIAL_WRITE,
        AFTER_TMP_SYNC_BEFORE_REPLACE,
        AFTER_PRIMARY_REPLACE_BEFORE_BACKUP,
        AFTER_BACKUP_PARTIAL_WRITE
    }

    @Volatile
    internal var persistenceFaultHook: ((stage: PersistenceFaultStage, targetFile: File, stagingFile: File?) -> Unit)? = null

    private fun getIndexFile(stateFilePath: String): File = File("$stateFilePath.index.bin")
    private fun getRestorationCheckpointFile(stateFilePath: String): File = File("$stateFilePath.restore.ckpt")
    private fun getBinaryCheckpointFile(stateFilePath: String): File = File("$stateFilePath.ckpt.bin")
    private fun getBackupFile(file: File): File = File("${file.absolutePath}.bak")
    private fun getTempFile(file: File): File = File("${file.absolutePath}.tmp")
    private fun getBackupTempFile(file: File): File = File("${file.absolutePath}.bak.tmp")

    private fun syncParentDirectory(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                java.nio.channels.FileChannel.open(
                    dir.toPath(),
                    java.nio.file.StandardOpenOption.READ
                ).use { ch ->
                    ch.force(true)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Atomically replaces [targetFile] with [tmpFile] without ever deleting [targetFile] first.
     * Uses `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` on API 26+, POSIX `Os.rename` on
     * API 21+, and direct `renameTo` fallback — never `target.delete() + renameTo()`.
     */
    private fun atomicReplaceFile(tmpFile: File, targetFile: File, syncDir: Boolean = true): Boolean {
        if (!tmpFile.exists()) return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                if (syncDir) syncParentDirectory(targetFile.parentFile)
                return true
            } catch (_: Throwable) {
                try {
                    java.nio.file.Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    if (syncDir) syncParentDirectory(targetFile.parentFile)
                    return true
                } catch (_: Throwable) {
                }
            }
        }
        try {
            android.system.Os.rename(tmpFile.absolutePath, targetFile.absolutePath)
            if (syncDir) syncParentDirectory(targetFile.parentFile)
            return true
        } catch (_: Throwable) {
        }
        if (tmpFile.renameTo(targetFile)) {
            if (syncDir) syncParentDirectory(targetFile.parentFile)
            return true
        }
        return false
    }

    private fun mirrorCommittedFileToBackup(targetFile: File, syncBackup: Boolean) {
        val bakFile = getBackupFile(targetFile)
        val bakTmpFile = getBackupTempFile(targetFile)
        FileInputStream(targetFile).use { fis ->
            FileOutputStream(bakTmpFile).use { fos ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val r = fis.read(buf)
                    if (r <= 0) break
                    fos.write(buf, 0, r)
                }
                fos.flush()
                persistenceFaultHook?.invoke(PersistenceFaultStage.AFTER_BACKUP_PARTIAL_WRITE, targetFile, bakTmpFile)
                if (syncBackup) {
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        atomicReplaceFile(bakTmpFile, bakFile, syncDir = false)
    }

    /**
     * Stages a checkpoint into `<target>.tmp`, flushes and `fd.sync()`s it, atomically replaces
     * `<target>` without deleting the previous committed file, and mirrors the committed file into
     * `<target>.bak` as a validated B-slot copy.
     */
    private fun writeAtomicFileWithBackup(
        targetFile: File,
        syncDir: Boolean = true,
        syncBackup: Boolean = true,
        writeToFos: (fos: FileOutputStream, beforeFooterSyncHook: () -> Unit) -> Unit
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val tmpFile = getTempFile(targetFile)
        FileOutputStream(tmpFile).use { fos ->
            writeToFos(fos) {
                persistenceFaultHook?.invoke(PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE, targetFile, tmpFile)
            }
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Throwable) {
            }
        }
        persistenceFaultHook?.invoke(PersistenceFaultStage.AFTER_TMP_SYNC_BEFORE_REPLACE, targetFile, tmpFile)
        if (!atomicReplaceFile(tmpFile, targetFile, syncDir = syncDir)) {
            return false
        }
        persistenceFaultHook?.invoke(PersistenceFaultStage.AFTER_PRIMARY_REPLACE_BEFORE_BACKUP, targetFile, null)
        try {
            mirrorCommittedFileToBackup(targetFile, syncBackup = syncBackup)
        } catch (t: Throwable) {
            // Primary targetFile is already atomically committed and synced; propagate only if test hook requested interruption
            if (persistenceFaultHook != null) throw t
        }
        return true
    }

    /**
     * Writes the complete descending entry table once to `<stateFilePath>.index.bin` using
     * streaming binary I/O + CRC32 footer + `fd.sync()` + crash-safe atomic replace + `.bak` mirror.
     * Supports 50,000+ APK/ZIP entries with minimal heap allocation.
     */
    fun saveInitialIndex(stateFilePath: String, orderedEntries: List<ZipEntryInfo>): Boolean {
        return try {
            val indexFile = getIndexFile(stateFilePath)
            writeAtomicFileWithBackup(indexFile) { fos, beforeFooterSyncHook ->
                val crc = CRC32()
                val buffered = BufferedOutputStream(fos, 64 * 1024)
                val cos = CheckedOutputStream(buffered, crc)
                val dos = DataOutputStream(cos)

                dos.writeInt(INDEX_MAGIC)
                dos.writeInt(INDEX_VERSION)
                dos.writeInt(orderedEntries.size)

                for (entry in orderedEntries) {
                    dos.writeUTF(entry.path)
                    dos.writeUTF(entry.name)
                    var flags = 0
                    if (entry.isDirectory) flags = flags or 1
                    if (entry.isEncrypted) flags = flags or 2
                    if (entry.isZip64) flags = flags or 4
                    dos.writeInt(flags)
                    dos.writeLong(entry.compressedSize)
                    dos.writeLong(entry.uncompressedSize)
                    dos.writeLong(entry.crc32)
                    dos.writeInt(entry.compressionMethod)
                    dos.writeLong(entry.localHeaderOffset)
                    dos.writeLong(entry.lastModified)
                }
                dos.flush()
                buffered.flush()
                beforeFooterSyncHook()

                // Write 8-byte CRC32 footer directly (unhashed by CheckedOutputStream)
                val footerOut = DataOutputStream(fos)
                footerOut.writeLong(crc.value)
                footerOut.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun readInitialIndexFile(indexFile: File): List<ZipEntryInfo>? {
        if (!indexFile.exists() || indexFile.length() < 20L) return null
        return try {
            val payloadLen = indexFile.length() - 8L
            val crc = CRC32()
            FileInputStream(indexFile).use { fis ->
                val buffered = BufferedInputStream(fis, 64 * 1024)
                val cis = CheckedInputStream(LimitedInputStream(buffered, payloadLen), crc)
                val dis = DataInputStream(cis)

                val magic = dis.readInt()
                val version = dis.readInt()
                if (magic != INDEX_MAGIC || version != INDEX_VERSION) return null

                val count = dis.readInt()
                if (count < 0 || count > 2_000_000) return null

                val list = ArrayList<ZipEntryInfo>(count.coerceAtMost(65536))
                for (i in 0 until count) {
                    val path = dis.readUTF()
                    val name = dis.readUTF()
                    val flags = dis.readInt()
                    val isDirectory = (flags and 1) != 0
                    val isEncrypted = (flags and 2) != 0
                    val isZip64 = (flags and 4) != 0
                    val compressedSize = dis.readLong()
                    val uncompressedSize = dis.readLong()
                    val crc32Val = dis.readLong()
                    val compressionMethod = dis.readInt()
                    val localHeaderOffset = dis.readLong()
                    val lastModified = dis.readLong()
                    list.add(
                        ZipEntryInfo(
                            path = path,
                            name = name,
                            isDirectory = isDirectory,
                            compressedSize = compressedSize,
                            uncompressedSize = uncompressedSize,
                            crc32 = crc32Val,
                            compressionMethod = compressionMethod,
                            localHeaderOffset = localHeaderOffset,
                            lastModified = lastModified,
                            isEncrypted = isEncrypted,
                            isZip64 = isZip64
                        )
                    )
                }

                // Drain any remaining bytes in payload before reading footer
                while (cis.read() != -1) {
                    // no-op
                }

                val footerDis = DataInputStream(buffered)
                val expectedCrc = footerDis.readLong()
                if (crc.value != expectedCrc) {
                    return null
                }
                list
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Reads and CRC-verifies the streaming binary entry table `<stateFilePath>.index.bin`,
     * falling back to validated `.bak` or `.tmp` copies if the primary file is missing or corrupted.
     * Returns `null` if no CRC-valid copy exists.
     */
    fun loadInitialIndex(stateFilePath: String): List<ZipEntryInfo>? {
        val indexFile = getIndexFile(stateFilePath)
        readInitialIndexFile(indexFile)?.let { return it }
        readInitialIndexFile(getBackupFile(indexFile))?.let { return it }
        return readInitialIndexFile(getTempFile(indexFile))
    }

    /**
     * Durably commits [state] to disk with `fd.sync()`, CRC32 checksums, crash-safe atomic replace,
     * and validated `.bak` backup copies. Automatically creates `<stateFilePath>.index.bin` if it
     * does not yet exist.
     */
    fun saveState(state: ResumeJournalState): Boolean {
        return try {
            val targetFile = File(state.stateFilePath)
            targetFile.parentFile?.mkdirs()

            val indexFile = getIndexFile(state.stateFilePath)
            if (!indexFile.exists() && !getBackupFile(indexFile).exists() && state.remainingEntries.isNotEmpty()) {
                saveInitialIndex(state.stateFilePath, state.remainingEntries)
            }

            // 1. Write binary checkpoint `<stateFilePath>.ckpt.bin` with CRC32 + fd.sync() + atomic replace + .bak
            val ckptOk = saveBinaryCheckpoint(state)

            // 2. Write checksummed `.hatchstate.json` with fd.sync() + atomic replace + .bak
            val jsonText = buildJsonStateText(state, includeInlineEntries = state.remainingEntries.size <= INLINE_JSON_ENTRY_LIMIT)
            val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
            val jsonOk = writeAtomicFileWithBackup(
                targetFile = targetFile,
                syncDir = false,
                syncBackup = false
            ) { fos, beforeFooterSyncHook ->
                val splitPoint = (jsonBytes.size / 2).coerceAtLeast(1)
                fos.write(jsonBytes, 0, splitPoint)
                fos.flush()
                beforeFooterSyncHook()
                if (splitPoint < jsonBytes.size) {
                    fos.write(jsonBytes, splitPoint, jsonBytes.size - splitPoint)
                }
            }
            jsonOk && ckptOk && (getBinaryCheckpointFile(state.stateFilePath).exists() || getBackupFile(getBinaryCheckpointFile(state.stateFilePath)).exists())
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun hasValidJournalOnDisk(stateFilePath: String, requireIndex: Boolean = true): Boolean {
        if (stateFilePath.isEmpty()) return false
        val stateFile = File(stateFilePath)
        val ckptFile = getBinaryCheckpointFile(stateFilePath)
        val indexFile = getIndexFile(stateFilePath)
        val hasCheckpoint = (stateFile.exists() && stateFile.length() > 0L) ||
                (getBackupFile(stateFile).exists() && getBackupFile(stateFile).length() > 0L) ||
                (ckptFile.exists() && ckptFile.length() > 0L) ||
                (getBackupFile(ckptFile).exists() && getBackupFile(ckptFile).length() > 0L)
        if (!hasCheckpoint) return false
        val hasIndex = (indexFile.exists() && indexFile.length() > 0L) ||
                (getBackupFile(indexFile).exists() && getBackupFile(indexFile).length() > 0L)
        if (requireIndex && !hasIndex) return false
        return true
    }

    private fun saveBinaryCheckpoint(state: ResumeJournalState): Boolean {
        val ckptFile = getBinaryCheckpointFile(state.stateFilePath)
        return writeAtomicFileWithBackup(
            targetFile = ckptFile,
            syncDir = false,
            syncBackup = false
        ) { fos, beforeFooterSyncHook ->
            val crc = CRC32()
            val buffered = BufferedOutputStream(fos, 32 * 1024)
            val cos = CheckedOutputStream(buffered, crc)
            val dos = DataOutputStream(cos)

            dos.writeInt(CKPT_MAGIC)
            dos.writeInt(CKPT_VERSION)
            dos.writeUTF(state.stateFilePath)
            dos.writeUTF(state.archivePath)
            dos.writeUTF(state.sourceUriString ?: "")
            dos.writeUTF(state.archiveName)
            dos.writeUTF(state.targetDirPath)
            dos.writeLong(state.originalArchiveBytes)
            dos.writeLong(state.currentArchiveBytes)
            dos.writeLong(state.bytesReclaimedSoFar)
            dos.writeInt(state.totalEntries)
            dos.writeLong(state.timestamp)
            dos.writeUTF(state.password ?: "")

            dos.writeInt(state.completedPaths.size)
            for (p in state.completedPaths) {
                dos.writeUTF(p)
            }

            dos.writeInt(state.verifiedEntryCrcs.size)
            for ((k, v) in state.verifiedEntryCrcs) {
                dos.writeUTF(k)
                dos.writeLong(v)
            }

            // If external index does not exist, embed remainingEntries in the checkpoint
            val hasExternalIndex = getIndexFile(state.stateFilePath).exists() ||
                    getBackupFile(getIndexFile(state.stateFilePath)).exists()
            dos.writeBoolean(hasExternalIndex)
            if (!hasExternalIndex) {
                dos.writeInt(state.remainingEntries.size)
                for (entry in state.remainingEntries) {
                    dos.writeUTF(entry.path)
                    dos.writeUTF(entry.name)
                    var flags = 0
                    if (entry.isDirectory) flags = flags or 1
                    if (entry.isEncrypted) flags = flags or 2
                    if (entry.isZip64) flags = flags or 4
                    dos.writeInt(flags)
                    dos.writeLong(entry.compressedSize)
                    dos.writeLong(entry.uncompressedSize)
                    dos.writeLong(entry.crc32)
                    dos.writeInt(entry.compressionMethod)
                    dos.writeLong(entry.localHeaderOffset)
                    dos.writeLong(entry.lastModified)
                }
            }
            dos.writeUTF(state.targetTreeUriString ?: "")
            dos.writeUTF(state.targetFolderDocUriString ?: "")
            dos.flush()
            buffered.flush()
            beforeFooterSyncHook()

            val footerOut = DataOutputStream(fos)
            footerOut.writeLong(crc.value)
            footerOut.flush()
        }
    }

    private fun readBinaryCheckpointFile(canonicalStateFile: File, ckptFile: File): ResumeJournalState? {
        if (!ckptFile.exists() || ckptFile.length() < 24L) return null
        return try {
            val payloadLen = ckptFile.length() - 8L
            val crc = CRC32()
            FileInputStream(ckptFile).use { fis ->
                val buffered = BufferedInputStream(fis, 32 * 1024)
                val cis = CheckedInputStream(LimitedInputStream(buffered, payloadLen), crc)
                val dis = DataInputStream(cis)

                val magic = dis.readInt()
                val version = dis.readInt()
                if (magic != CKPT_MAGIC || (version != 1 && version != CKPT_VERSION)) return null

                val savedStatePath = dis.readUTF()
                val archivePath = dis.readUTF()
                val sourceUriString = dis.readUTF().ifEmpty { null }
                val archiveName = dis.readUTF()
                val targetDirPath = dis.readUTF()
                val originalArchiveBytes = dis.readLong()
                val currentArchiveBytes = dis.readLong()
                val bytesReclaimedSoFar = dis.readLong()
                val totalEntries = dis.readInt()
                val timestamp = dis.readLong()
                val password = dis.readUTF().ifEmpty { null }

                val completedCount = dis.readInt()
                if (completedCount < 0 || completedCount > 2_000_000) return null
                val completedSet = LinkedHashSet<String>(completedCount.coerceAtLeast(16))
                for (i in 0 until completedCount) {
                    completedSet.add(dis.readUTF())
                }

                val crcCount = dis.readInt()
                if (crcCount < 0 || crcCount > 2_000_000) return null
                val verifiedCrcs = LinkedHashMap<String, Long>(crcCount.coerceAtLeast(16))
                for (i in 0 until crcCount) {
                    val k = dis.readUTF()
                    val v = dis.readLong()
                    verifiedCrcs[k] = v
                }

                val hasExternalIndex = dis.readBoolean()
                val inlineRemaining = mutableListOf<ZipEntryInfo>()
                if (!hasExternalIndex) {
                    val remCount = dis.readInt()
                    if (remCount < 0 || remCount > 2_000_000) return null
                    for (i in 0 until remCount) {
                        val path = dis.readUTF()
                        val name = dis.readUTF()
                        val flags = dis.readInt()
                        val isDirectory = (flags and 1) != 0
                        val isEncrypted = (flags and 2) != 0
                        val isZip64 = (flags and 4) != 0
                        val compressedSize = dis.readLong()
                        val uncompressedSize = dis.readLong()
                        val crc32Val = dis.readLong()
                        val compressionMethod = dis.readInt()
                        val localHeaderOffset = dis.readLong()
                        val lastModified = dis.readLong()
                        if (!completedSet.contains(path)) {
                            inlineRemaining.add(
                                ZipEntryInfo(
                                    path = path,
                                    name = name,
                                    isDirectory = isDirectory,
                                    compressedSize = compressedSize,
                                    uncompressedSize = uncompressedSize,
                                    crc32 = crc32Val,
                                    compressionMethod = compressionMethod,
                                    localHeaderOffset = localHeaderOffset,
                                    lastModified = lastModified,
                                    isEncrypted = isEncrypted,
                                    isZip64 = isZip64
                                )
                            )
                        }
                    }
                }

                val targetTreeUriString = if (version >= 2) dis.readUTF().ifEmpty { null } else null
                val targetFolderDocUriString = if (version >= 2) dis.readUTF().ifEmpty { null } else null

                while (cis.read() != -1) {
                    // drain
                }

                val footerDis = DataInputStream(buffered)
                val expectedCrc = footerDis.readLong()
                if (crc.value != expectedCrc) return null

                val remainingEntries = if (hasExternalIndex) {
                    val fullIndex = loadInitialIndex(canonicalStateFile.absolutePath) ?: loadInitialIndex(savedStatePath) ?: return null
                    fullIndex.filter { !completedSet.contains(it.path) }
                } else {
                    inlineRemaining
                }

                ResumeJournalState(
                    stateFilePath = canonicalStateFile.absolutePath,
                    archivePath = archivePath,
                    sourceUriString = sourceUriString,
                    archiveName = archiveName,
                    targetDirPath = targetDirPath,
                    originalArchiveBytes = originalArchiveBytes,
                    currentArchiveBytes = currentArchiveBytes,
                    bytesReclaimedSoFar = bytesReclaimedSoFar,
                    totalEntries = totalEntries,
                    completedPaths = completedSet,
                    remainingEntries = remainingEntries,
                    password = password,
                    timestamp = timestamp,
                    verifiedEntryCrcs = verifiedCrcs,
                    targetTreeUriString = targetTreeUriString,
                    targetFolderDocUriString = targetFolderDocUriString,
                    hasPendingRestoration = hasPendingRestorationCheckpoint(canonicalStateFile.absolutePath)
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadBinaryCheckpoint(stateFile: File): ResumeJournalState? {
        val ckptFile = getBinaryCheckpointFile(stateFile.absolutePath)
        readBinaryCheckpointFile(stateFile, ckptFile)?.let { return it }
        readBinaryCheckpointFile(stateFile, getBackupFile(ckptFile))?.let { return it }
        return readBinaryCheckpointFile(stateFile, getTempFile(ckptFile))
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun buildJsonStateText(state: ResumeJournalState, includeInlineEntries: Boolean): String {
        val sb = StringBuilder(4096)
        sb.append('{')
        sb.append("\"stateFilePath\":").append(escapeJson(state.stateFilePath)).append(',')
        sb.append("\"archivePath\":").append(escapeJson(state.archivePath)).append(',')
        sb.append("\"sourceUriString\":").append(escapeJson(state.sourceUriString ?: "")).append(',')
        sb.append("\"archiveName\":").append(escapeJson(state.archiveName)).append(',')
        sb.append("\"targetDirPath\":").append(escapeJson(state.targetDirPath)).append(',')
        sb.append("\"targetTreeUriString\":").append(escapeJson(state.targetTreeUriString ?: "")).append(',')
        sb.append("\"targetFolderDocUriString\":").append(escapeJson(state.targetFolderDocUriString ?: "")).append(',')
        sb.append("\"originalArchiveBytes\":").append(state.originalArchiveBytes).append(',')
        sb.append("\"currentArchiveBytes\":").append(state.currentArchiveBytes).append(',')
        sb.append("\"bytesReclaimedSoFar\":").append(state.bytesReclaimedSoFar).append(',')
        sb.append("\"totalEntries\":").append(state.totalEntries).append(',')
        sb.append("\"timestamp\":").append(state.timestamp).append(',')
        sb.append("\"hasExternalIndex\":").append(getIndexFile(state.stateFilePath).exists()).append(',')

        sb.append("\"completedPaths\":[")
        var first = true
        for (path in state.completedPaths) {
            if (!first) sb.append(',')
            first = false
            sb.append(escapeJson(path))
        }
        sb.append("],")

        sb.append("\"verifiedEntryCrcs\":{")
        first = true
        for ((k, v) in state.verifiedEntryCrcs) {
            if (!first) sb.append(',')
            first = false
            sb.append(escapeJson(k)).append(':').append(v)
        }
        sb.append("},")

        sb.append("\"remainingEntries\":[")
        if (includeInlineEntries) {
            first = true
            for (entry in state.remainingEntries) {
                if (!first) sb.append(',')
                first = false
                sb.append('{')
                sb.append("\"path\":").append(escapeJson(entry.path)).append(',')
                sb.append("\"name\":").append(escapeJson(entry.name)).append(',')
                sb.append("\"isDirectory\":").append(entry.isDirectory).append(',')
                sb.append("\"compressedSize\":").append(entry.compressedSize).append(',')
                sb.append("\"uncompressedSize\":").append(entry.uncompressedSize).append(',')
                sb.append("\"crc32\":").append(entry.crc32).append(',')
                sb.append("\"compressionMethod\":").append(entry.compressionMethod).append(',')
                sb.append("\"localHeaderOffset\":").append(entry.localHeaderOffset).append(',')
                sb.append("\"lastModified\":").append(entry.lastModified).append(',')
                sb.append("\"isEncrypted\":").append(entry.isEncrypted).append(',')
                sb.append("\"isZip64\":").append(entry.isZip64)
                sb.append('}')
            }
        }
        sb.append("]")
        val payloadPrefix = sb.toString()
        val jsonCrc = CRC32().apply { update(payloadPrefix.toByteArray(Charsets.UTF_8)) }.value
        sb.append(",\"payloadCrc32\":").append(jsonCrc).append('}')
        return sb.toString()
    }

    private const val TAILSWAP_GUARD_MAGIC = 0x4B485447 // "KHTG"
    private const val TAILSWAP_GUARD_VERSION = 2

    data class TailSwapGuardState(
        val entryPath: String,
        val entryName: String,
        val payloadOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val expectedEntryCrc32: Long,
        val chunk0Len: Int,
        val chunk0OriginalCrc32: Long,
        val decompressedChunk0Bytes: Long,
        val overwriteStarted: Boolean = false
    )

    sealed class ResumeEligibility {
        object Resumable : ResumeEligibility()
        data class Unrecoverable(val reason: String) : ResumeEligibility()
    }

    private fun getTailSwapGuardFile(stateFilePath: String): File = File("$stateFilePath.tailswap.guard")

    fun saveTailSwapGuard(stateFilePath: String, guard: TailSwapGuardState): Boolean {
        return try {
            val guardFile = getTailSwapGuardFile(stateFilePath)
            writeAtomicFileWithBackup(guardFile) { fos, beforeFooterSyncHook ->
                val crc = CRC32()
                val buffered = BufferedOutputStream(fos, 4096)
                val cos = CheckedOutputStream(buffered, crc)
                val dos = DataOutputStream(cos)
                dos.writeInt(TAILSWAP_GUARD_MAGIC)
                dos.writeInt(TAILSWAP_GUARD_VERSION)
                dos.writeUTF(guard.entryPath)
                dos.writeUTF(guard.entryName)
                dos.writeLong(guard.payloadOffset)
                dos.writeLong(guard.compressedSize)
                dos.writeLong(guard.uncompressedSize)
                dos.writeLong(guard.expectedEntryCrc32)
                dos.writeInt(guard.chunk0Len)
                dos.writeLong(guard.chunk0OriginalCrc32)
                dos.writeLong(guard.decompressedChunk0Bytes)
                dos.writeBoolean(guard.overwriteStarted)
                dos.flush()
                buffered.flush()
                beforeFooterSyncHook()

                val footerOut = DataOutputStream(fos)
                footerOut.writeLong(crc.value)
                footerOut.flush()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun readTailSwapGuardFile(guardFile: File): TailSwapGuardState? {
        if (!guardFile.exists() || guardFile.length() < 40L) return null
        return try {
            val payloadLen = guardFile.length() - 8L
            val crc = CRC32()
            FileInputStream(guardFile).use { fis ->
                val buffered = BufferedInputStream(fis, 4096)
                val cis = CheckedInputStream(LimitedInputStream(buffered, payloadLen), crc)
                val dis = DataInputStream(cis)
                val magic = dis.readInt()
                val version = dis.readInt()
                if (magic != TAILSWAP_GUARD_MAGIC || (version != 1 && version != TAILSWAP_GUARD_VERSION)) return null
                val entryPath = dis.readUTF()
                val entryName = dis.readUTF()
                val payloadOffset = dis.readLong()
                val compressedSize = dis.readLong()
                val uncompressedSize = dis.readLong()
                val expectedEntryCrc32 = dis.readLong()
                val chunk0Len = dis.readInt()
                val chunk0OriginalCrc32 = dis.readLong()
                val decompressedChunk0Bytes = dis.readLong()
                val overwriteStarted = if (version >= 2) dis.readBoolean() else false
                while (cis.read() != -1) {
                    // drain
                }
                val footerDis = DataInputStream(buffered)
                val expectedFooterCrc = footerDis.readLong()
                if (crc.value != expectedFooterCrc) return null
                TailSwapGuardState(
                    entryPath = entryPath,
                    entryName = entryName,
                    payloadOffset = payloadOffset,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    expectedEntryCrc32 = expectedEntryCrc32,
                    chunk0Len = chunk0Len,
                    chunk0OriginalCrc32 = chunk0OriginalCrc32,
                    decompressedChunk0Bytes = decompressedChunk0Bytes,
                    overwriteStarted = overwriteStarted
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Loads and CRC-verifies the active [TailSwapGuardState] from primary, `.bak`, or `.tmp`.
     * Enforces fail-closed monotonic preference: if any CRC-valid copy has `overwriteStarted == true`,
     * it strictly takes precedence over any older copy with `overwriteStarted == false`.
     */
    fun loadTailSwapGuard(stateFilePath: String): TailSwapGuardState? {
        val guardFile = getTailSwapGuardFile(stateFilePath)
        val candidates = listOfNotNull(
            readTailSwapGuardFile(guardFile),
            readTailSwapGuardFile(getBackupFile(guardFile)),
            readTailSwapGuardFile(getTempFile(guardFile))
        )
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.overwriteStarted } ?: candidates.first()
    }

    /**
     * Returns `true` when a committed tail-swap guard artifact exists on disk (primary or `.bak`)
     * but NO valid CRC-verified copy can be loaded. Callers must fail closed (`Unrecoverable`)
     * rather than treating a corrupted guard as absent.
     */
    fun hasCorruptedTailSwapGuard(stateFilePath: String): Boolean {
        if (loadTailSwapGuard(stateFilePath) != null) return false
        val guardFile = getTailSwapGuardFile(stateFilePath)
        return guardFile.exists() || getBackupFile(guardFile).exists()
    }

    fun clearTailSwapGuard(stateFilePath: String) {
        try {
            val guardFile = getTailSwapGuardFile(stateFilePath)
            val targets = listOf(
                guardFile,
                getBackupFile(guardFile),
                getTempFile(guardFile),
                getBackupTempFile(guardFile)
            )
            for (f in targets) {
                if (f.exists()) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    internal const val DEFLATE_TAILSWAP_CKPT_MAGIC = 0x4B485453 // "KHTS"
    internal const val DEFLATE_TAILSWAP_CKPT_VERSION_V1 = 1
    internal const val DEFLATE_TAILSWAP_CKPT_VERSION = 2
    private const val MAX_TAILSWAP_CHUNKS = 1_000_000
    private const val MAX_EMBEDDED_INFLATE_CKPT_BYTES = 256 * 1024

    /**
     * Durable Part 2C transaction phase for each non-final chunk in the large DEFLATE tail-swap loop.
     */
    enum class DeflateTailSwapTxPhase(val code: Int) {
        /**
         * Chunk `c` decoded and output flushed/fsynced; relocation intent (including CRC32 of the
         * intact tail chunk) persisted BEFORE destructive `pwrite`.
         */
        PREPARED(0),

        /**
         * `pwrite` + `fsync` complete (or no `pwrite` needed for middle/second-half truncation);
         * updated relocation map and truncation target persisted BEFORE destructive `ftruncate`.
         */
        RELOCATED_TRUNCATE_PENDING(1),

        /**
         * `ftruncate` + `fsync` complete; completed chunk progress durably committed.
         */
        COMMITTED(2);

        companion object {
            fun fromCode(code: Int): DeflateTailSwapTxPhase? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * Tail-swap archive mutation operation associated with a [DeflateTailSwapCheckpoint] transaction.
     */
    enum class DeflateTailSwapOp(val code: Int) {
        NONE(0),
        FIRST_HALF_RELOCATE(1),
        MIDDLE_TRUNCATE(2),
        SECOND_HALF_TRUNCATE(3);

        companion object {
            fun fromCode(code: Int): DeflateTailSwapOp? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * Self-consistent, checksummed checkpoint capturing `CheckpointableInflateEngine` decoder state
     * (compressed position, 32 KB window, Huffman tables, bit accumulator, pending tail bytes,
     * output offset, running CRC32), the tail-swap relocation map (`relocatedSlot`, `relocatedLen`),
     * and the Part 2C transactional phase/intent (`txPhase`, `pendingOp`, `pendingRelocateCrc32`,
     * `pendingTruncateTarget`) in a single atomic file.
     */
    data class DeflateTailSwapCheckpoint(
        val entryPath: String,
        val entryName: String,
        val payloadOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val expectedEntryCrc32: Long,
        val chunkStep: Int,
        val totalChunks: Int,
        val lastCompletedChunkIndex: Int,
        val relocationCommittedForChunk: Boolean,
        val canSwapInPlace: Boolean,
        val hasMutatedArchive: Boolean,
        val currentArchiveBytes: Long,
        val compressedBytesConsumed: Long,
        val outputBytesWritten: Long,
        val runningCrc32: Long,
        val relocatedSlot: IntArray,
        val relocatedLen: IntArray,
        val inflateCheckpointBytes: ByteArray,
        val txPhase: DeflateTailSwapTxPhase = if (relocationCommittedForChunk) {
            DeflateTailSwapTxPhase.COMMITTED
        } else {
            DeflateTailSwapTxPhase.PREPARED
        },
        val pendingOp: DeflateTailSwapOp = DeflateTailSwapOp.NONE,
        val pendingSlotIndex: Int = -1,
        val pendingTailIndex: Int = -1,
        val pendingRelocateLen: Int = 0,
        val pendingRelocateCrc32: Long = 0L,
        val pendingTruncateTarget: Long = -1L,
        val formatVersion: Int = DEFLATE_TAILSWAP_CKPT_VERSION
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeflateTailSwapCheckpoint) return false
            return entryPath == other.entryPath &&
                entryName == other.entryName &&
                payloadOffset == other.payloadOffset &&
                compressedSize == other.compressedSize &&
                uncompressedSize == other.uncompressedSize &&
                expectedEntryCrc32 == other.expectedEntryCrc32 &&
                chunkStep == other.chunkStep &&
                totalChunks == other.totalChunks &&
                lastCompletedChunkIndex == other.lastCompletedChunkIndex &&
                relocationCommittedForChunk == other.relocationCommittedForChunk &&
                canSwapInPlace == other.canSwapInPlace &&
                hasMutatedArchive == other.hasMutatedArchive &&
                currentArchiveBytes == other.currentArchiveBytes &&
                compressedBytesConsumed == other.compressedBytesConsumed &&
                outputBytesWritten == other.outputBytesWritten &&
                runningCrc32 == other.runningCrc32 &&
                relocatedSlot.contentEquals(other.relocatedSlot) &&
                relocatedLen.contentEquals(other.relocatedLen) &&
                inflateCheckpointBytes.contentEquals(other.inflateCheckpointBytes) &&
                txPhase == other.txPhase &&
                pendingOp == other.pendingOp &&
                pendingSlotIndex == other.pendingSlotIndex &&
                pendingTailIndex == other.pendingTailIndex &&
                pendingRelocateLen == other.pendingRelocateLen &&
                pendingRelocateCrc32 == other.pendingRelocateCrc32 &&
                pendingTruncateTarget == other.pendingTruncateTarget &&
                formatVersion == other.formatVersion
        }

        override fun hashCode(): Int {
            var result = entryPath.hashCode()
            result = 31 * result + entryName.hashCode()
            result = 31 * result + payloadOffset.hashCode()
            result = 31 * result + compressedSize.hashCode()
            result = 31 * result + uncompressedSize.hashCode()
            result = 31 * result + expectedEntryCrc32.hashCode()
            result = 31 * result + chunkStep
            result = 31 * result + totalChunks
            result = 31 * result + lastCompletedChunkIndex
            result = 31 * result + relocationCommittedForChunk.hashCode()
            result = 31 * result + canSwapInPlace.hashCode()
            result = 31 * result + hasMutatedArchive.hashCode()
            result = 31 * result + currentArchiveBytes.hashCode()
            result = 31 * result + compressedBytesConsumed.hashCode()
            result = 31 * result + outputBytesWritten.hashCode()
            result = 31 * result + runningCrc32.hashCode()
            result = 31 * result + relocatedSlot.contentHashCode()
            result = 31 * result + relocatedLen.contentHashCode()
            result = 31 * result + inflateCheckpointBytes.contentHashCode()
            result = 31 * result + txPhase.hashCode()
            result = 31 * result + pendingOp.hashCode()
            result = 31 * result + pendingSlotIndex
            result = 31 * result + pendingTailIndex
            result = 31 * result + pendingRelocateLen
            result = 31 * result + pendingRelocateCrc32.hashCode()
            result = 31 * result + pendingTruncateTarget.hashCode()
            result = 31 * result + formatVersion
            return result
        }
    }

    private fun isValidDeflateTailSwapTxInvariants(
        txPhase: DeflateTailSwapTxPhase,
        pendingOp: DeflateTailSwapOp,
        pendingSlotIndex: Int,
        pendingTailIndex: Int,
        pendingRelocateLen: Int,
        pendingRelocateCrc32: Long,
        pendingTruncateTarget: Long,
        relocationCommittedForChunk: Boolean,
        payloadOffset: Long,
        chunkStep: Int,
        totalChunks: Int,
        relocatedSlot: IntArray,
        relocatedLen: IntArray
    ): Boolean {
        if (pendingRelocateCrc32 !in 0L..0xFFFFFFFFL) return false
        if (pendingOp == DeflateTailSwapOp.NONE) {
            return true
        }
        if (txPhase == DeflateTailSwapTxPhase.COMMITTED || relocationCommittedForChunk) {
            return false
        }
        when (pendingOp) {
            DeflateTailSwapOp.NONE -> return true
            DeflateTailSwapOp.FIRST_HALF_RELOCATE -> {
                if (pendingSlotIndex !in 0 until totalChunks) return false
                if (pendingTailIndex !in 0 until totalChunks) return false
                if (pendingSlotIndex >= pendingTailIndex) return false
                if (pendingRelocateLen !in 1..chunkStep) return false
                if (pendingTruncateTarget < payloadOffset) return false
                if (txPhase == DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING) {
                    if (relocatedSlot[pendingTailIndex] != pendingSlotIndex) return false
                    if (relocatedLen[pendingTailIndex] != pendingRelocateLen) return false
                }
                return true
            }
            DeflateTailSwapOp.MIDDLE_TRUNCATE -> {
                if (txPhase != DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING) return false
                if (pendingTruncateTarget < payloadOffset) return false
                return true
            }
            DeflateTailSwapOp.SECOND_HALF_TRUNCATE -> {
                if (txPhase != DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING) return false
                if (pendingSlotIndex !in 1 until totalChunks) return false
                if (pendingTruncateTarget < payloadOffset) return false
                return true
            }
        }
    }

    internal fun getDeflateTailSwapCheckpointFile(stateFilePath: String): File =
        File("$stateFilePath.tailswap.ckpt")

    fun saveDeflateTailSwapCheckpoint(
        stateFilePath: String,
        checkpoint: DeflateTailSwapCheckpoint
    ): Boolean {
        if (checkpoint.chunkStep <= 0 ||
            checkpoint.totalChunks !in 1..MAX_TAILSWAP_CHUNKS ||
            checkpoint.relocatedSlot.size != checkpoint.totalChunks ||
            checkpoint.relocatedLen.size != checkpoint.totalChunks ||
            checkpoint.lastCompletedChunkIndex !in -1 until checkpoint.totalChunks ||
            checkpoint.inflateCheckpointBytes.isEmpty() ||
            checkpoint.inflateCheckpointBytes.size > MAX_EMBEDDED_INFLATE_CKPT_BYTES ||
            !isValidDeflateTailSwapTxInvariants(
                txPhase = checkpoint.txPhase,
                pendingOp = checkpoint.pendingOp,
                pendingSlotIndex = checkpoint.pendingSlotIndex,
                pendingTailIndex = checkpoint.pendingTailIndex,
                pendingRelocateLen = checkpoint.pendingRelocateLen,
                pendingRelocateCrc32 = checkpoint.pendingRelocateCrc32,
                pendingTruncateTarget = checkpoint.pendingTruncateTarget,
                relocationCommittedForChunk = checkpoint.relocationCommittedForChunk,
                payloadOffset = checkpoint.payloadOffset,
                chunkStep = checkpoint.chunkStep,
                totalChunks = checkpoint.totalChunks,
                relocatedSlot = checkpoint.relocatedSlot,
                relocatedLen = checkpoint.relocatedLen
            )
        ) {
            return false
        }
        return try {
            val ckptFile = getDeflateTailSwapCheckpointFile(stateFilePath)
            writeAtomicFileWithBackup(ckptFile) { fos, beforeFooterSyncHook ->
                val crc = CRC32()
                val buffered = BufferedOutputStream(fos, 40 * 1024)
                val cos = CheckedOutputStream(buffered, crc)
                val dos = DataOutputStream(cos)

                dos.writeInt(DEFLATE_TAILSWAP_CKPT_MAGIC)
                dos.writeInt(DEFLATE_TAILSWAP_CKPT_VERSION)
                dos.writeUTF(checkpoint.entryPath)
                dos.writeUTF(checkpoint.entryName)
                dos.writeLong(checkpoint.payloadOffset)
                dos.writeLong(checkpoint.compressedSize)
                dos.writeLong(checkpoint.uncompressedSize)
                dos.writeLong(checkpoint.expectedEntryCrc32)
                dos.writeInt(checkpoint.chunkStep)
                dos.writeInt(checkpoint.totalChunks)
                dos.writeInt(checkpoint.lastCompletedChunkIndex)
                dos.writeBoolean(checkpoint.relocationCommittedForChunk)
                dos.writeBoolean(checkpoint.canSwapInPlace)
                dos.writeBoolean(checkpoint.hasMutatedArchive)
                dos.writeLong(checkpoint.currentArchiveBytes)
                dos.writeLong(checkpoint.compressedBytesConsumed)
                dos.writeLong(checkpoint.outputBytesWritten)
                dos.writeLong(checkpoint.runningCrc32)

                // Part 2C v2 transaction phase & intent metadata
                dos.writeInt(checkpoint.txPhase.code)
                dos.writeInt(checkpoint.pendingOp.code)
                dos.writeInt(checkpoint.pendingSlotIndex)
                dos.writeInt(checkpoint.pendingTailIndex)
                dos.writeInt(checkpoint.pendingRelocateLen)
                dos.writeLong(checkpoint.pendingRelocateCrc32)
                dos.writeLong(checkpoint.pendingTruncateTarget)

                for (i in 0 until checkpoint.totalChunks) {
                    dos.writeInt(checkpoint.relocatedSlot[i])
                    dos.writeInt(checkpoint.relocatedLen[i])
                }

                dos.writeInt(checkpoint.inflateCheckpointBytes.size)
                dos.write(checkpoint.inflateCheckpointBytes)
                dos.flush()
                buffered.flush()
                beforeFooterSyncHook()

                val footerOut = DataOutputStream(fos)
                footerOut.writeLong(crc.value)
                footerOut.flush()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun readDeflateTailSwapCheckpointFile(ckptFile: File): DeflateTailSwapCheckpoint? {
        if (!ckptFile.exists() || ckptFile.length() < 96L) return null
        return try {
            val payloadLen = ckptFile.length() - 8L
            val crc = CRC32()
            FileInputStream(ckptFile).use { fis ->
                val buffered = BufferedInputStream(fis, 40 * 1024)
                val cis = CheckedInputStream(LimitedInputStream(buffered, payloadLen), crc)
                val dis = DataInputStream(cis)

                val magic = dis.readInt()
                val version = dis.readInt()
                if (magic != DEFLATE_TAILSWAP_CKPT_MAGIC ||
                    (version != DEFLATE_TAILSWAP_CKPT_VERSION_V1 && version != DEFLATE_TAILSWAP_CKPT_VERSION)
                ) {
                    return null
                }

                val entryPath = dis.readUTF()
                val entryName = dis.readUTF()
                val payloadOffset = dis.readLong()
                val compressedSize = dis.readLong()
                val uncompressedSize = dis.readLong()
                val expectedEntryCrc32 = dis.readLong()
                val chunkStep = dis.readInt()
                val totalChunks = dis.readInt()
                val lastCompletedChunkIndex = dis.readInt()
                val relocationCommittedForChunk = dis.readBoolean()
                val canSwapInPlace = dis.readBoolean()
                val hasMutatedArchive = dis.readBoolean()
                val currentArchiveBytes = dis.readLong()
                val compressedBytesConsumed = dis.readLong()
                val outputBytesWritten = dis.readLong()
                val runningCrc32 = dis.readLong()

                val txPhase: DeflateTailSwapTxPhase
                val pendingOp: DeflateTailSwapOp
                val pendingSlotIndex: Int
                val pendingTailIndex: Int
                val pendingRelocateLen: Int
                val pendingRelocateCrc32: Long
                val pendingTruncateTarget: Long

                if (version >= DEFLATE_TAILSWAP_CKPT_VERSION) {
                    txPhase = DeflateTailSwapTxPhase.fromCode(dis.readInt()) ?: return null
                    pendingOp = DeflateTailSwapOp.fromCode(dis.readInt()) ?: return null
                    pendingSlotIndex = dis.readInt()
                    pendingTailIndex = dis.readInt()
                    pendingRelocateLen = dis.readInt()
                    pendingRelocateCrc32 = dis.readLong()
                    pendingTruncateTarget = dis.readLong()
                } else {
                    txPhase = if (relocationCommittedForChunk) {
                        DeflateTailSwapTxPhase.COMMITTED
                    } else {
                        DeflateTailSwapTxPhase.PREPARED
                    }
                    pendingOp = DeflateTailSwapOp.NONE
                    pendingSlotIndex = -1
                    pendingTailIndex = -1
                    pendingRelocateLen = 0
                    pendingRelocateCrc32 = 0L
                    pendingTruncateTarget = -1L
                }

                if (payloadOffset < 0L || compressedSize <= 0L || uncompressedSize < 0L || currentArchiveBytes < 0L) {
                    return null
                }
                if (chunkStep <= 0 || totalChunks !in 1..MAX_TAILSWAP_CHUNKS) return null
                if (lastCompletedChunkIndex !in -1 until totalChunks) return null
                if (compressedBytesConsumed < 0L || compressedBytesConsumed > compressedSize) return null
                if (outputBytesWritten < 0L) return null
                if (runningCrc32 !in 0L..0xFFFFFFFFL) return null

                val relocatedSlot = IntArray(totalChunks)
                val relocatedLen = IntArray(totalChunks)
                for (i in 0 until totalChunks) {
                    val slot = dis.readInt()
                    val len = dis.readInt()
                    if (slot !in -1 until totalChunks) return null
                    if (len < 0 || len > chunkStep) return null
                    if (slot == -1 && len != 0) return null
                    relocatedSlot[i] = slot
                    relocatedLen[i] = len
                }

                if (!isValidDeflateTailSwapTxInvariants(
                        txPhase = txPhase,
                        pendingOp = pendingOp,
                        pendingSlotIndex = pendingSlotIndex,
                        pendingTailIndex = pendingTailIndex,
                        pendingRelocateLen = pendingRelocateLen,
                        pendingRelocateCrc32 = pendingRelocateCrc32,
                        pendingTruncateTarget = pendingTruncateTarget,
                        relocationCommittedForChunk = relocationCommittedForChunk,
                        payloadOffset = payloadOffset,
                        chunkStep = chunkStep,
                        totalChunks = totalChunks,
                        relocatedSlot = relocatedSlot,
                        relocatedLen = relocatedLen
                    )
                ) {
                    return null
                }

                val inflateLen = dis.readInt()
                if (inflateLen <= 0 || inflateLen > MAX_EMBEDDED_INFLATE_CKPT_BYTES || inflateLen.toLong() > payloadLen) {
                    return null
                }
                val inflateBytes = ByteArray(inflateLen)
                dis.readFully(inflateBytes)

                while (cis.read() != -1) {
                    // drain
                }

                val footerDis = DataInputStream(buffered)
                val expectedFooterCrc = footerDis.readLong()
                if (crc.value != expectedFooterCrc) return null

                // Validate embedded CheckpointableInflateEngine checkpoint CRC32, Huffman invariants,
                // and exact cross-field consistency with outer progress counters.
                val validator = CheckpointableInflateEngine()
                validator.restoreFromCheckpointBytes(inflateBytes)
                if (validator.compressedBytesConsumed != compressedBytesConsumed ||
                    validator.outputBytesWritten != outputBytesWritten ||
                    validator.currentCrc32 != runningCrc32
                ) {
                    return null
                }

                DeflateTailSwapCheckpoint(
                    entryPath = entryPath,
                    entryName = entryName,
                    payloadOffset = payloadOffset,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    expectedEntryCrc32 = expectedEntryCrc32,
                    chunkStep = chunkStep,
                    totalChunks = totalChunks,
                    lastCompletedChunkIndex = lastCompletedChunkIndex,
                    relocationCommittedForChunk = relocationCommittedForChunk,
                    canSwapInPlace = canSwapInPlace,
                    hasMutatedArchive = hasMutatedArchive,
                    currentArchiveBytes = currentArchiveBytes,
                    compressedBytesConsumed = compressedBytesConsumed,
                    outputBytesWritten = outputBytesWritten,
                    runningCrc32 = runningCrc32,
                    relocatedSlot = relocatedSlot,
                    relocatedLen = relocatedLen,
                    inflateCheckpointBytes = inflateBytes,
                    txPhase = txPhase,
                    pendingOp = pendingOp,
                    pendingSlotIndex = pendingSlotIndex,
                    pendingTailIndex = pendingTailIndex,
                    pendingRelocateLen = pendingRelocateLen,
                    pendingRelocateCrc32 = pendingRelocateCrc32,
                    pendingTruncateTarget = pendingTruncateTarget,
                    formatVersion = version
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Loads and CRC-verifies the active [DeflateTailSwapCheckpoint] from primary, `.bak`, or `.tmp`.
     */
    fun loadDeflateTailSwapCheckpoint(stateFilePath: String): DeflateTailSwapCheckpoint? {
        val ckptFile = getDeflateTailSwapCheckpointFile(stateFilePath)
        readDeflateTailSwapCheckpointFile(ckptFile)?.let { return it }
        readDeflateTailSwapCheckpointFile(getBackupFile(ckptFile))?.let { return it }
        return readDeflateTailSwapCheckpointFile(getTempFile(ckptFile))
    }

    /**
     * Returns `true` when a committed deflate tail-swap checkpoint artifact exists on disk
     * (primary or `.bak`) but no valid CRC-verified copy can be loaded.
     */
    fun hasCorruptedDeflateTailSwapCheckpoint(stateFilePath: String): Boolean {
        if (loadDeflateTailSwapCheckpoint(stateFilePath) != null) return false
        val ckptFile = getDeflateTailSwapCheckpointFile(stateFilePath)
        return ckptFile.exists() || getBackupFile(ckptFile).exists()
    }

    fun clearDeflateTailSwapCheckpoint(stateFilePath: String) {
        try {
            val ckptFile = getDeflateTailSwapCheckpointFile(stateFilePath)
            val targets = listOf(
                ckptFile,
                getBackupFile(ckptFile),
                getTempFile(ckptFile),
                getBackupTempFile(ckptFile)
            )
            for (f in targets) {
                if (f.exists()) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Combines two IEEE 802.3 CRC-32 values `crc1 = CRC32(A)` and `crc2 = CRC32(B)` where `len2 = |B|`
     * into `CRC32(A || B)` in O(log(len2)) time using GF(2) polynomial matrix squaring (zlib `crc32_combine`).
     * Used to compute exact forward-order CRC32 for large STORED entries extracted in reverse slices.
     */
    fun crc32Combine(crc1: Long, crc2: Long, len2: Long): Long {
        if (len2 <= 0L) return crc1 and 0xFFFFFFFFL
        var c1 = crc1 and 0xFFFFFFFFL
        val c2 = crc2 and 0xFFFFFFFFL
        var n = len2

        fun gf2MatrixTimes(mat: LongArray, vec: Long): Long {
            var v = vec
            var sum = 0L
            var idx = 0
            while (v != 0L) {
                if ((v and 1L) != 0L) {
                    sum = sum xor mat[idx]
                }
                v = v ushr 1
                idx++
            }
            return sum
        }

        fun gf2MatrixSquare(square: LongArray, mat: LongArray) {
            for (i in 0 until 32) {
                square[i] = gf2MatrixTimes(mat, mat[i])
            }
        }

        val even = LongArray(32)
        val odd = LongArray(32)

        // Operator for 1 zero bit (reflected IEEE 802.3 polynomial 0xEDB88320)
        odd[0] = 0xEDB88320L
        var row = 1L
        for (i in 1 until 32) {
            odd[i] = row
            row = row shl 1
        }

        // Operator for 2 zero bits in `even`, then 4 zero bits in `odd`
        gf2MatrixSquare(even, odd)
        gf2MatrixSquare(odd, even)

        while (true) {
            // Square `odd` into `even` -> first iteration puts 8 zero bits (1 zero byte) in `even`
            gf2MatrixSquare(even, odd)
            if ((n and 1L) != 0L) {
                c1 = gf2MatrixTimes(even, c1)
            }
            n = n ushr 1
            if (n == 0L) break

            gf2MatrixSquare(odd, even)
            if ((n and 1L) != 0L) {
                c1 = gf2MatrixTimes(odd, c1)
            }
            n = n ushr 1
            if (n == 0L) break
        }

        return (c1 xor c2) and 0xFFFFFFFFL
    }

    /**
     * Evaluates whether [state] represents an interrupted archive that can be safely resumed without
     * data loss, or whether compressed/source data has already been destructively overwritten or truncated.
     */
    fun assessResumeEligibility(
        state: ResumeJournalState,
        context: Context? = null
    ): ResumeEligibility {
        val archiveFile = if (state.archivePath.isNotEmpty()) File(state.archivePath) else null
        if (archiveFile != null && !archiveFile.exists()) {
            return ResumeEligibility.Unrecoverable("Source archive file no longer exists on disk.")
        }

        var safSlot0Bytes: ByteArray? = null
        val physLen: Long = when {
            archiveFile != null -> archiveFile.length()
            !state.sourceUriString.isNullOrEmpty() && context != null -> {
                val uri = try {
                    android.net.Uri.parse(state.sourceUriString)
                } catch (_: Throwable) {
                    return ResumeEligibility.Unrecoverable("Invalid source SAF URI in resume journal.")
                }
                val pfd = try {
                    context.contentResolver.openFileDescriptor(uri, "rw")
                } catch (t: Throwable) {
                    val reason = t.localizedMessage ?: t.javaClass.simpleName
                    return ResumeEligibility.Unrecoverable(
                        "Writable access to source SAF archive is unavailable ($reason)."
                    )
                } ?: return ResumeEligibility.Unrecoverable(
                    "Source SAF archive descriptor could not be opened in writable ('rw') mode."
                )

                val guardForSaf = loadTailSwapGuard(state.stateFilePath)
                var resolvedSize = -1L
                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        val probe = StorageHelper.probeTransactableFileDescriptor(
                            fd = descriptor.fileDescriptor,
                            channel = ch,
                            requireWriteAndTruncate = true
                        )
                        if (!probe.isSupported) {
                            return ResumeEligibility.Unrecoverable(
                                "Source SAF archive descriptor does not support safe transactional I/O (${probe.reason}); requires a verified seekable read-write SAF provider (supporting ftruncate and fsync) or direct-POSIX access."
                            )
                        }
                        resolvedSize = probe.sizeBytes
                        if (resolvedSize < 0L) {
                            return ResumeEligibility.Unrecoverable(
                                "Source SAF archive no longer exists on disk (size=$resolvedSize)."
                            )
                        }
                        if (guardForSaf != null &&
                            guardForSaf.chunk0Len > 0 &&
                            resolvedSize >= guardForSaf.payloadOffset + guardForSaf.chunk0Len
                        ) {
                            val buf = ByteArray(guardForSaf.chunk0Len)
                            val bb = java.nio.ByteBuffer.wrap(buf)
                            var pos = guardForSaf.payloadOffset
                            while (bb.hasRemaining()) {
                                val r = try {
                                    ch.read(bb, pos)
                                } catch (_: Throwable) {
                                    synchronized(ch) {
                                        ch.position(pos)
                                        ch.read(bb)
                                    }
                                }
                                if (r <= 0) break
                                pos += r
                            }
                            if (bb.position() == guardForSaf.chunk0Len) {
                                safSlot0Bytes = buf
                            }
                        }
                    }
                }
                resolvedSize
            }
            else -> state.currentArchiveBytes
        }

        val targetDir = File(state.targetDirPath)
        val resumeTreeUri = state.targetTreeUriString?.takeIf { it.isNotEmpty() }?.let {
            runCatching { android.net.Uri.parse(it) }.getOrNull()
        }
        val resumeFolderDocUri = state.targetFolderDocUriString?.takeIf { it.isNotEmpty() }?.let {
            runCatching { android.net.Uri.parse(it) }.getOrNull()
        }
        val isDirectPosixTarget = StorageHelper.isDirectPosixDestination(context, targetDir, resumeTreeUri)
        val safFolderDocUri = if (!isDirectPosixTarget && context != null && resumeTreeUri != null) {
            StorageHelper.verifySafDestinationAccessible(
                context = context,
                treeUri = resumeTreeUri,
                targetFolderDocUri = resumeFolderDocUri,
                subfolderName = targetDir.name
            )
        } else null

        val fullIndex = loadInitialIndex(state.stateFilePath)
        val indexByPath = fullIndex?.associateBy { it.path } ?: emptyMap()
        val remainingByPath = state.remainingEntries.associateBy { it.path }

        var verifiedDeflateRecoveryEntryPath: String? = null
        var effectiveRemainingArchiveLimit = physLen

        fun evaluateDeflateTxRecovery(
            guardForCheck: TailSwapGuardState?,
            expectedEntryPath: String? = null
        ): DeflateTailSwapTxEligibility = verifyDeflateTailSwapTransactionEligibility(
            state = state,
            context = context,
            archiveFile = archiveFile,
            physLen = physLen,
            isDirectPosixTarget = isDirectPosixTarget,
            targetDir = targetDir,
            resumeTreeUri = resumeTreeUri,
            safFolderDocUri = safFolderDocUri,
            guard = guardForCheck,
            indexByPath = indexByPath,
            remainingByPath = remainingByPath,
            expectedEntryPath = expectedEntryPath
        )

        // 1. Check active TailSwapGuardState and DeflateTailSwapCheckpoint for large DEFLATE entry
        val guard = loadTailSwapGuard(state.stateFilePath)
        if (guard == null && hasCorruptedTailSwapGuard(state.stateFilePath)) {
            return ResumeEligibility.Unrecoverable(
                "Tail-swap recovery guard is corrupted and no valid backup exists; refusing to resume to prevent silent data corruption."
            )
        }
        if (hasCorruptedDeflateTailSwapCheckpoint(state.stateFilePath)) {
            return ResumeEligibility.Unrecoverable(
                "Deflate tail-swap transaction checkpoint is corrupted and no valid backup exists; refusing to resume to prevent silent data corruption."
            )
        }
        if (guard != null) {
            val destFile = if (isDirectPosixTarget) File(targetDir, guard.entryPath) else null
            val expectedCrc = if (guard.expectedEntryCrc32 != 0L) guard.expectedEntryCrc32 else state.verifiedEntryCrcs[guard.entryPath]
            val alreadyVerified = when {
                destFile != null -> verifyOutputFileIntegrity(destFile, guard.uncompressedSize, expectedCrc)
                context != null && resumeTreeUri != null && safFolderDocUri != null ->
                    StorageHelper.verifySafOutputFileIntegrity(
                        context = context,
                        treeUri = resumeTreeUri,
                        rootFolderDocUri = safFolderDocUri,
                        relativePath = guard.entryPath,
                        expectedSize = guard.uncompressedSize,
                        expectedCrc32 = expectedCrc
                    )
                else -> false
            }
            if (!alreadyVerified) {
                val expectedEnd = guard.payloadOffset + guard.compressedSize
                val legacyFailureReason: String? = when {
                    physLen < expectedEnd ->
                        "compressed data was already overwritten and truncated ($physLen < $expectedEnd) for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                    archiveFile != null && guard.chunk0Len > 0 -> {
                        val slot0Crc = try {
                            java.io.RandomAccessFile(archiveFile, "r").use { raf ->
                                val buf = ByteArray(guard.chunk0Len)
                                raf.seek(guard.payloadOffset)
                                var rTotal = 0
                                while (rTotal < guard.chunk0Len) {
                                    val r = raf.read(buf, rTotal, guard.chunk0Len - rTotal)
                                    if (r <= 0) break
                                    rTotal += r
                                }
                                if (rTotal == guard.chunk0Len) {
                                    val c = CRC32()
                                    c.update(buf, 0, rTotal)
                                    c.value
                                } else -1L
                            }
                        } catch (_: Throwable) {
                            -1L
                        }
                        if (slot0Crc != guard.chunk0OriginalCrc32 || guard.overwriteStarted) {
                            "compressed head slot 0 was already overwritten via pwrite (expected CRC 0x${String.format("%08X", guard.chunk0OriginalCrc32)}, got 0x${String.format("%08X", slot0Crc)}, overwriteStarted=${guard.overwriteStarted}) for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                        } else null
                    }
                    !state.sourceUriString.isNullOrEmpty() && context != null && guard.chunk0Len > 0 -> {
                        val slot0Bytes = safSlot0Bytes
                        val slot0Crc = if (slot0Bytes != null && slot0Bytes.size == guard.chunk0Len) {
                            val c = CRC32()
                            c.update(slot0Bytes, 0, slot0Bytes.size)
                            c.value
                        } else {
                            -1L
                        }
                        if (slot0Crc != guard.chunk0OriginalCrc32 || guard.overwriteStarted) {
                            "compressed head slot 0 was already overwritten via pwrite (expected CRC 0x${String.format("%08X", guard.chunk0OriginalCrc32)}, got 0x${String.format("%08X", slot0Crc)}, overwriteStarted=${guard.overwriteStarted}) for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                        } else null
                    }
                    guard.overwriteStarted ->
                        "compressed head slot 0 was already overwritten via pwrite for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                    else -> null
                }

                if (legacyFailureReason != null) {
                    when (val txEligibility = evaluateDeflateTxRecovery(guard, guard.entryPath)) {
                        is DeflateTailSwapTxEligibility.Eligible -> {
                            verifiedDeflateRecoveryEntryPath = txEligibility.entryPath
                            effectiveRemainingArchiveLimit = minOf(physLen, txEligibility.postRecoveryArchiveLen)
                        }
                        is DeflateTailSwapTxEligibility.Ineligible -> {
                            return ResumeEligibility.Unrecoverable(txEligibility.reason)
                        }
                        DeflateTailSwapTxEligibility.NoActiveCheckpoint -> {
                            return ResumeEligibility.Unrecoverable(legacyFailureReason)
                        }
                    }
                }
            }
        }

        // 2. Check all completedPaths: if an output file is corrupted/missing on disk AND its source region
        // in the archive was already truncated, the archive cannot be safely resumed.
        if (isDirectPosixTarget) {
            for (compPath in state.completedPaths) {
                val meta = indexByPath[compPath] ?: continue
                if (meta.isDirectory) continue
                val destFile = File(targetDir, meta.path)
                val expectedCrc = if (meta.crc32 != 0L) meta.crc32 else state.verifiedEntryCrcs[compPath]
                if (!verifyOutputFileIntegrity(destFile, meta.uncompressedSize, expectedCrc)) {
                    val approxEnd = meta.localHeaderOffset + 30L + meta.path.length + meta.compressedSize
                    if (approxEnd > effectiveRemainingArchiveLimit) {
                        return ResumeEligibility.Unrecoverable(
                            "Completed entry '${meta.name}' failed output CRC32 verification and its source archive region was already truncated."
                        )
                    }
                }
            }
        } else if (context != null && resumeTreeUri != null && safFolderDocUri != null) {
            for (compPath in state.completedPaths) {
                val meta = indexByPath[compPath] ?: continue
                if (meta.isDirectory) continue
                val expectedCrc = if (meta.crc32 != 0L) meta.crc32 else state.verifiedEntryCrcs[compPath]
                val isSafIntact = StorageHelper.verifySafOutputFileIntegrity(
                    context = context,
                    treeUri = resumeTreeUri,
                    rootFolderDocUri = safFolderDocUri,
                    relativePath = meta.path,
                    expectedSize = meta.uncompressedSize,
                    expectedCrc32 = expectedCrc
                )
                if (!isSafIntact) {
                    val approxEnd = meta.localHeaderOffset + 30L + meta.path.length + meta.compressedSize
                    if (approxEnd > effectiveRemainingArchiveLimit) {
                        return ResumeEligibility.Unrecoverable(
                            "Completed entry '${meta.name}' failed SAF output CRC32 verification and its source archive region was already truncated."
                        )
                    }
                }
            }
        }

        // 3. Check remainingEntries: if any uncompleted entry is not already verified on disk and its source
        // region in the archive has been truncated (beyond recoverable STORED slice boundary or verified
        // v2 transactional DEFLATE tail-swap checkpoint), it is unrecoverable.
        val remaining = if (fullIndex != null) {
            fullIndex.filter { !state.completedPaths.contains(it.path) }
        } else {
            state.remainingEntries
        }
        for (entry in remaining) {
            if (entry.isDirectory) continue
            if (entry.path == verifiedDeflateRecoveryEntryPath) continue

            val destFile = if (isDirectPosixTarget) File(targetDir, entry.path) else null
            val expectedCrc = if (entry.crc32 != 0L) entry.crc32 else state.verifiedEntryCrcs[entry.path]
            if (destFile != null && verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedCrc)) continue
            if (destFile == null && context != null && resumeTreeUri != null && safFolderDocUri != null &&
                StorageHelper.verifySafOutputFileIntegrity(
                    context = context,
                    treeUri = resumeTreeUri,
                    rootFolderDocUri = safFolderDocUri,
                    relativePath = entry.path,
                    expectedSize = entry.uncompressedSize,
                    expectedCrc32 = expectedCrc
                )
            ) {
                continue
            }

            val minPayloadStart = entry.localHeaderOffset + 30L + entry.path.length
            val minPayloadEnd = minPayloadStart + entry.compressedSize
            val isLargeStored = (entry.compressionMethod == 0 || entry.compressionMethod == 100) &&
                    entry.uncompressedSize >= 4L * 1024L * 1024L
            if (isLargeStored && destFile != null) {
                if (minPayloadEnd > effectiveRemainingArchiveLimit && (!destFile.exists() || destFile.length() != entry.uncompressedSize)) {
                    return ResumeEligibility.Unrecoverable(
                        "Interrupted large STORED entry '${entry.name}' was partially truncated ($effectiveRemainingArchiveLimit < $minPayloadEnd) and its previously extracted tail slices are missing on disk."
                    )
                }
            } else if (minPayloadEnd > effectiveRemainingArchiveLimit) {
                if (verifiedDeflateRecoveryEntryPath == null && entry.compressionMethod == 8) {
                    when (val txEligibility = evaluateDeflateTxRecovery(guard, entry.path)) {
                        is DeflateTailSwapTxEligibility.Eligible -> {
                            verifiedDeflateRecoveryEntryPath = txEligibility.entryPath
                            effectiveRemainingArchiveLimit = minOf(effectiveRemainingArchiveLimit, txEligibility.postRecoveryArchiveLen)
                            continue
                        }
                        is DeflateTailSwapTxEligibility.Ineligible -> {
                            return ResumeEligibility.Unrecoverable(txEligibility.reason)
                        }
                        DeflateTailSwapTxEligibility.NoActiveCheckpoint -> {
                            // fall through to standard truncated error
                        }
                    }
                }
                return ResumeEligibility.Unrecoverable(
                    "Source archive region for '${entry.name}' was already truncated ($effectiveRemainingArchiveLimit < $minPayloadEnd) before entry completion."
                )
            }
        }

        // 4. Fail closed if the source archive was already truncated to 0 bytes (for example,
        //    when final source sync/close failed after truncate(0)). Step 2 above already
        //    verified every completed file via full-stream CRC32.
        if (physLen <= 0L) {
            val hasVerifiedDestinationAccess =
                isDirectPosixTarget || (context != null && resumeTreeUri != null && safFolderDocUri != null)
            val allRecordedCompleted =
                state.totalEntries > 0 && state.completedPaths.size >= state.totalEntries && remaining.isEmpty()
            return if (allRecordedCompleted && hasVerifiedDestinationAccess) {
                ResumeEligibility.Unrecoverable(
                    "All ${state.completedPaths.size} extracted files are already verified on disk, but the source archive was truncated to 0 bytes before final source sync/close completed. Nothing remains to resume; your extracted files remain intact and you can dismiss this notice."
                )
            } else if (remaining.isEmpty() && hasVerifiedDestinationAccess) {
                ResumeEligibility.Unrecoverable(
                    "Source archive was already truncated to 0 bytes with no remaining entries to resume (final source sync/close failed); verified extracted files remain intact on disk."
                )
            } else {
                ResumeEligibility.Unrecoverable(
                    "Source archive was already truncated to 0 bytes and cannot be resumed."
                )
            }
        }

        return ResumeEligibility.Resumable
    }

    private sealed class DeflateTailSwapTxEligibility {
        data class Eligible(
            val entryPath: String,
            val postRecoveryArchiveLen: Long
        ) : DeflateTailSwapTxEligibility()
        data class Ineligible(val reason: String) : DeflateTailSwapTxEligibility()
        data object NoActiveCheckpoint : DeflateTailSwapTxEligibility()
    }

    /**
     * Strictly validates every invariant required to safely resume or restore an interrupted large
     * DEFLATE tail-swap transaction across either verified direct-POSIX storage or verified
     * seekable read-write SAF `ParcelFileDescriptor` handles (supporting `pwrite`, `ftruncate`,
     * and durable `fsync`). Fails closed on any mismatch, missing capability, or failed `fsync`.
     */
    private fun verifyDeflateTailSwapTransactionEligibility(
        state: ResumeJournalState,
        context: Context?,
        archiveFile: File?,
        physLen: Long,
        isDirectPosixTarget: Boolean,
        targetDir: File,
        resumeTreeUri: android.net.Uri?,
        safFolderDocUri: android.net.Uri?,
        guard: TailSwapGuardState?,
        indexByPath: Map<String, ZipEntryInfo>,
        remainingByPath: Map<String, ZipEntryInfo>,
        expectedEntryPath: String?
    ): DeflateTailSwapTxEligibility {
        if (hasCorruptedTailSwapGuard(state.stateFilePath)) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Tail-swap recovery guard is corrupted and no valid backup exists."
            )
        }
        if (hasCorruptedDeflateTailSwapCheckpoint(state.stateFilePath)) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap transaction checkpoint is corrupted and no valid backup exists."
            )
        }

        val ckpt = loadDeflateTailSwapCheckpoint(state.stateFilePath)
            ?: return DeflateTailSwapTxEligibility.NoActiveCheckpoint

        if (ckpt.formatVersion < DEFLATE_TAILSWAP_CKPT_VERSION) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Legacy v${ckpt.formatVersion} deflate tail-swap checkpoint for '${ckpt.entryName}' lacks transactional phase metadata and cannot be safely resumed."
            )
        }

        // Verify source storage: either direct-POSIX File OR verified seekable read-write SAF URI.
        val isSafSource = !state.sourceUriString.isNullOrEmpty()
        val safSourceUri: android.net.Uri? = if (isSafSource) {
            if (context == null) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Transactional deflate tail-swap recovery for a SAF-backed source requires an Android Context and a verified seekable read-write SAF provider (or direct-POSIX access)."
                )
            }
            try {
                android.net.Uri.parse(state.sourceUriString)
            } catch (_: Throwable) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Source SAF URI in resume journal is malformed; requires a verified seekable read-write SAF provider or direct-POSIX access."
                )
            }
        } else {
            if (archiveFile == null) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Transactional deflate tail-swap recovery requires a verified direct-POSIX source archive or seekable read-write SAF source."
                )
            }
            if (!archiveFile.exists() || !archiveFile.isFile || !archiveFile.canRead() || !archiveFile.canWrite()) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Source archive '${archiveFile.name}' is not readable and writable for direct-POSIX transactional recovery."
                )
            }
            val directSyncOk = try {
                java.io.RandomAccessFile(archiveFile, "rw").use { raf ->
                    StorageHelper.syncFileDescriptorStrict(raf.fd, raf.channel)
                }
            } catch (_: Throwable) {
                false
            }
            if (!directSyncOk) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Source archive '${archiveFile.name}' failed durable fsync verification; refusing transactional recovery."
                )
            }
            null
        }

        if (expectedEntryPath != null && ckpt.entryPath != expectedEntryPath) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint entry '${ckpt.entryPath}' does not match interrupted entry '$expectedEntryPath'."
            )
        }

        if (!ckpt.canSwapInPlace ||
            ckpt.chunkStep <= 0 ||
            ckpt.totalChunks <= 1 ||
            ckpt.lastCompletedChunkIndex !in 0 until (ckpt.totalChunks - 1)
        ) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint for '${ckpt.entryName}' has invalid chunk layout or progress bounds."
            )
        }
        val expectedTotalChunks = ((ckpt.compressedSize + ckpt.chunkStep.toLong() - 1L) / ckpt.chunkStep.toLong())
            .toInt()
            .coerceAtLeast(1)
        if (expectedTotalChunks != ckpt.totalChunks) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint chunk count (${ckpt.totalChunks}) does not match expected ($expectedTotalChunks) for '${ckpt.entryName}'."
            )
        }

        if (guard != null) {
            if (guard.entryPath != ckpt.entryPath ||
                guard.payloadOffset != ckpt.payloadOffset ||
                guard.compressedSize != ckpt.compressedSize ||
                guard.uncompressedSize != ckpt.uncompressedSize ||
                (guard.expectedEntryCrc32 != 0L && ckpt.expectedEntryCrc32 != 0L && guard.expectedEntryCrc32 != ckpt.expectedEntryCrc32)
            ) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Deflate tail-swap checkpoint metadata does not match active TailSwapGuardState for '${ckpt.entryName}'."
                )
            }
        }

        val entryMeta = indexByPath[ckpt.entryPath] ?: remainingByPath[ckpt.entryPath]
            ?: return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint entry '${ckpt.entryName}' is missing from the archive index."
            )
        if (entryMeta.isDirectory ||
            entryMeta.isEncrypted ||
            entryMeta.compressionMethod != 8 ||
            entryMeta.compressedSize != ckpt.compressedSize ||
            entryMeta.uncompressedSize != ckpt.uncompressedSize ||
            (entryMeta.crc32 != 0L && entryMeta.crc32 != ckpt.expectedEntryCrc32)
        ) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint metadata does not match indexed entry layout for '${ckpt.entryName}'."
            )
        }
        val minPayloadStart = entryMeta.localHeaderOffset + 30L + entryMeta.path.length
        if (ckpt.payloadOffset < minPayloadStart) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint payloadOffset (${ckpt.payloadOffset}) precedes entry header end ($minPayloadStart) for '${ckpt.entryName}'."
            )
        }

        if (ckpt.outputBytesWritten < 0L || ckpt.outputBytesWritten > ckpt.uncompressedSize) {
            return DeflateTailSwapTxEligibility.Ineligible(
                "Deflate tail-swap checkpoint output offset (${ckpt.outputBytesWritten}) is out of bounds for '${ckpt.entryName}'."
            )
        }

        // Verify partial output file on EITHER direct-POSIX destination OR verified seekable read-write SAF destination.
        if (isDirectPosixTarget) {
            if (!targetDir.exists() || !targetDir.isDirectory || !targetDir.canWrite()) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Transactional deflate tail-swap recovery requires a writable direct-POSIX destination folder or a verified seekable read-write SAF destination."
                )
            }
            val canonicalTarget = runCatching { targetDir.canonicalFile }.getOrNull()
                ?: return DeflateTailSwapTxEligibility.Ineligible("Target directory could not be resolved canonically.")
            val destFile = runCatching { File(targetDir, ckpt.entryPath).canonicalFile }.getOrNull()
                ?: return DeflateTailSwapTxEligibility.Ineligible("Partial output path '${ckpt.entryPath}' could not be resolved.")
            if (destFile != canonicalTarget && !destFile.path.startsWith(canonicalTarget.path + File.separator)) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Deflate tail-swap checkpoint entry path '${ckpt.entryPath}' escapes target directory."
                )
            }
            if (!destFile.exists() || !destFile.isFile || !destFile.canRead() || !destFile.canWrite()) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Partial DEFLATE output file for '${ckpt.entryName}' is missing or not readable/writable on disk."
                )
            }
            if (destFile.length() < ckpt.outputBytesWritten) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Partial DEFLATE output file for '${ckpt.entryName}' is shorter than checkpoint offset (${destFile.length()} < ${ckpt.outputBytesWritten})."
                )
            }
            val outSyncOk = try {
                java.io.RandomAccessFile(destFile, "rw").use { raf ->
                    StorageHelper.syncFileDescriptorStrict(raf.fd, raf.channel)
                }
            } catch (_: Throwable) {
                false
            }
            if (!outSyncOk) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Partial DEFLATE output file for '${ckpt.entryName}' failed durable fsync verification."
                )
            }
            val actualPrefixCrc = computeFilePrefixCrc32(destFile, ckpt.outputBytesWritten)
            if (actualPrefixCrc != ckpt.runningCrc32) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Partial DEFLATE output prefix CRC32 mismatch for '${ckpt.entryName}' (expected 0x${String.format("%08X", ckpt.runningCrc32)}, got 0x${String.format("%08X", actualPrefixCrc)})."
                )
            }
        } else {
            if (context == null || resumeTreeUri == null || safFolderDocUri == null) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    "Transactional deflate tail-swap recovery requires a writable direct-POSIX destination folder or an accessible seekable read-write SAF destination folder."
                )
            }
            val safOutVerify = StorageHelper.verifySafPartialOutputForRecovery(
                context = context,
                treeUri = resumeTreeUri,
                rootFolderDocUri = safFolderDocUri,
                relativePath = ckpt.entryPath,
                expectedOffset = ckpt.outputBytesWritten,
                expectedPrefixCrc32 = ckpt.runningCrc32
            )
            if (!safOutVerify.isValid) {
                return DeflateTailSwapTxEligibility.Ineligible(
                    safOutVerify.failureReason
                        ?: "Partial SAF output verification failed for '${ckpt.entryName}'; requires a seekable read-write SAF provider or direct-POSIX access."
                )
            }
        }

        fun expectedChunkLen(cIdx: Int): Int {
            val start = cIdx.toLong() * ckpt.chunkStep.toLong()
            val rem = (ckpt.compressedSize - start).coerceAtLeast(0L)
            return rem.coerceAtMost(ckpt.chunkStep.toLong()).toInt()
        }

        fun validateRemainingChunksWithin(
            completedChunk: Int,
            archiveLen: Long,
            relocSlot: IntArray,
            relocLen: IntArray
        ): Boolean {
            for (remChunk in (completedChunk + 1) until ckpt.totalChunks) {
                val slot = relocSlot[remChunk]
                val readPos = if (slot >= 0) {
                    ckpt.payloadOffset + slot.toLong() * ckpt.chunkStep.toLong()
                } else {
                    ckpt.payloadOffset + remChunk.toLong() * ckpt.chunkStep.toLong()
                }
                val readLen = if (slot >= 0 && relocLen[remChunk] > 0) {
                    relocLen[remChunk]
                } else {
                    expectedChunkLen(remChunk)
                }
                if (readLen <= 0 || readPos < ckpt.payloadOffset || readPos + readLen.toLong() > archiveLen) {
                    return false
                }
            }
            return true
        }

        val cDone = ckpt.lastCompletedChunkIndex

        fun verifyPhaseInvariants(readRegionCrc32: (Long, Int) -> Long): DeflateTailSwapTxEligibility {
            return when (ckpt.txPhase) {
                DeflateTailSwapTxPhase.PREPARED -> {
                    val expectedTailIdx = ckpt.totalChunks - 1 - cDone
                    val expectedTailPos = ckpt.payloadOffset + expectedTailIdx.toLong() * ckpt.chunkStep.toLong()
                    if (ckpt.pendingOp != DeflateTailSwapOp.FIRST_HALF_RELOCATE ||
                        ckpt.pendingSlotIndex != cDone ||
                        ckpt.pendingTailIndex != expectedTailIdx ||
                        ckpt.pendingSlotIndex >= ckpt.pendingTailIndex ||
                        ckpt.pendingTruncateTarget != expectedTailPos ||
                        ckpt.pendingRelocateLen != expectedChunkLen(expectedTailIdx)
                    ) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Inconsistent PREPARED transaction metadata for '${ckpt.entryName}' chunk $cDone."
                        )
                    }
                    if (physLen < expectedTailPos + ckpt.pendingRelocateLen.toLong()) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Source archive was truncated below intact tail chunk ($physLen < ${expectedTailPos + ckpt.pendingRelocateLen.toLong()}) in PREPARED phase."
                        )
                    }
                    val tailCrc = readRegionCrc32(expectedTailPos, ckpt.pendingRelocateLen)
                    if (tailCrc != ckpt.pendingRelocateCrc32) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Tail chunk CRC32 mismatch in PREPARED phase for '${ckpt.entryName}' (expected 0x${String.format("%08X", ckpt.pendingRelocateCrc32)}, got 0x${String.format("%08X", tailCrc)})."
                        )
                    }
                    val simSlot = ckpt.relocatedSlot.copyOf()
                    val simLen = ckpt.relocatedLen.copyOf()
                    simSlot[expectedTailIdx] = cDone
                    simLen[expectedTailIdx] = ckpt.pendingRelocateLen
                    if (!validateRemainingChunksWithin(cDone, expectedTailPos, simSlot, simLen)) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Remaining compressed chunks do not fit within post-replay archive bounds ($expectedTailPos) for '${ckpt.entryName}'."
                        )
                    }
                    DeflateTailSwapTxEligibility.Eligible(ckpt.entryPath, minOf(physLen, expectedTailPos))
                }

                DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING -> {
                    if (ckpt.pendingOp == DeflateTailSwapOp.NONE) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "RELOCATED_TRUNCATE_PENDING checkpoint for '${ckpt.entryName}' has pendingOp=NONE."
                        )
                    }
                    if (physLen < ckpt.pendingTruncateTarget) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Source archive ($physLen) is already smaller than pendingTruncateTarget (${ckpt.pendingTruncateTarget}) for '${ckpt.entryName}'."
                        )
                    }
                    when (ckpt.pendingOp) {
                        DeflateTailSwapOp.FIRST_HALF_RELOCATE -> {
                            val expectedTailIdx = ckpt.totalChunks - 1 - cDone
                            val expectedTailPos = ckpt.payloadOffset + expectedTailIdx.toLong() * ckpt.chunkStep.toLong()
                            if (ckpt.pendingSlotIndex != cDone ||
                                ckpt.pendingTailIndex != expectedTailIdx ||
                                ckpt.pendingSlotIndex >= ckpt.pendingTailIndex ||
                                ckpt.pendingTruncateTarget != expectedTailPos ||
                                ckpt.relocatedSlot[expectedTailIdx] != ckpt.pendingSlotIndex ||
                                ckpt.relocatedLen[expectedTailIdx] != ckpt.pendingRelocateLen
                            ) {
                                return DeflateTailSwapTxEligibility.Ineligible(
                                    "Inconsistent FIRST_HALF_RELOCATE slot bounds in RELOCATED_TRUNCATE_PENDING for '${ckpt.entryName}'."
                                )
                            }
                            val slotFilePos = ckpt.payloadOffset + ckpt.pendingSlotIndex.toLong() * ckpt.chunkStep.toLong()
                            if (slotFilePos + ckpt.pendingRelocateLen.toLong() > physLen) {
                                return DeflateTailSwapTxEligibility.Ineligible(
                                    "Relocated slot ${ckpt.pendingSlotIndex} exceeds archive size ($physLen) for '${ckpt.entryName}'."
                                )
                            }
                            val slotCrc = readRegionCrc32(slotFilePos, ckpt.pendingRelocateLen)
                            if (slotCrc != ckpt.pendingRelocateCrc32) {
                                return DeflateTailSwapTxEligibility.Ineligible(
                                    "Relocated slot ${ckpt.pendingSlotIndex} CRC32 mismatch in RELOCATED_TRUNCATE_PENDING for '${ckpt.entryName}' (expected 0x${String.format("%08X", ckpt.pendingRelocateCrc32)}, got 0x${String.format("%08X", slotCrc)})."
                                )
                            }
                        }
                        DeflateTailSwapOp.MIDDLE_TRUNCATE -> {
                            val expectedMidPos = ckpt.payloadOffset + cDone.toLong() * ckpt.chunkStep.toLong()
                            if (ckpt.pendingTruncateTarget != expectedMidPos) {
                                return DeflateTailSwapTxEligibility.Ineligible(
                                    "Inconsistent MIDDLE_TRUNCATE target (${ckpt.pendingTruncateTarget} != $expectedMidPos) for '${ckpt.entryName}'."
                                )
                            }
                        }
                        DeflateTailSwapOp.SECOND_HALF_TRUNCATE -> {
                            val expectedSlotPos = ckpt.payloadOffset + ckpt.pendingSlotIndex.toLong() * ckpt.chunkStep.toLong()
                            if (ckpt.pendingSlotIndex !in 1 until ckpt.totalChunks ||
                                ckpt.pendingTruncateTarget != expectedSlotPos
                            ) {
                                return DeflateTailSwapTxEligibility.Ineligible(
                                    "Inconsistent SECOND_HALF_TRUNCATE target for '${ckpt.entryName}'."
                                )
                            }
                        }
                        DeflateTailSwapOp.NONE -> {
                            return DeflateTailSwapTxEligibility.Ineligible(
                                "RELOCATED_TRUNCATE_PENDING checkpoint for '${ckpt.entryName}' has pendingOp=NONE."
                            )
                        }
                    }
                    if (!validateRemainingChunksWithin(cDone, ckpt.pendingTruncateTarget, ckpt.relocatedSlot, ckpt.relocatedLen)) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Remaining compressed chunks do not fit within pendingTruncateTarget (${ckpt.pendingTruncateTarget}) for '${ckpt.entryName}'."
                        )
                    }
                    DeflateTailSwapTxEligibility.Eligible(ckpt.entryPath, minOf(physLen, ckpt.pendingTruncateTarget))
                }

                DeflateTailSwapTxPhase.COMMITTED -> {
                    if (!ckpt.relocationCommittedForChunk || ckpt.pendingOp != DeflateTailSwapOp.NONE) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Inconsistent COMMITTED checkpoint state for '${ckpt.entryName}' chunk $cDone."
                        )
                    }
                    if (!validateRemainingChunksWithin(cDone, physLen, ckpt.relocatedSlot, ckpt.relocatedLen)) {
                        return DeflateTailSwapTxEligibility.Ineligible(
                            "Remaining compressed chunks do not fit within archive size ($physLen) for COMMITTED chunk $cDone of '${ckpt.entryName}'."
                        )
                    }
                    DeflateTailSwapTxEligibility.Eligible(ckpt.entryPath, physLen)
                }
            }
        }

        return try {
            if (isSafSource && safSourceUri != null && context != null) {
                val pfd = try {
                    context.contentResolver.openFileDescriptor(safSourceUri, "rw")
                } catch (t: Throwable) {
                    return DeflateTailSwapTxEligibility.Ineligible(
                        "Source SAF provider denied read-write ('rw') access (${t.localizedMessage ?: t.javaClass.simpleName}); transactional deflate tail-swap recovery requires a verified seekable read-write SAF provider or direct-POSIX access."
                    )
                } ?: return DeflateTailSwapTxEligibility.Ineligible(
                    "Source SAF provider returned a null read-write descriptor; transactional deflate tail-swap recovery requires a verified seekable read-write SAF provider or direct-POSIX access."
                )

                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        val probe = StorageHelper.probeTransactableFileDescriptor(
                            fd = descriptor.fileDescriptor,
                            channel = ch,
                            requireWriteAndTruncate = true
                        )
                        if (!probe.isSupported) {
                            return@use DeflateTailSwapTxEligibility.Ineligible(
                                "Source SAF descriptor failed transactional capability check (${probe.reason}); requires a verified seekable read-write SAF provider or direct-POSIX access."
                            )
                        }
                        verifyPhaseInvariants { offset, len ->
                            if (offset < 0L || len <= 0 || offset + len.toLong() > physLen) return@verifyPhaseInvariants -1L
                            val buf = ByteArray(minOf(len, 64 * 1024))
                            val crc = CRC32()
                            var remaining = len
                            var curPos = offset
                            while (remaining > 0) {
                                val toRead = minOf(remaining, buf.size)
                                val bb = java.nio.ByteBuffer.wrap(buf, 0, toRead)
                                var rTotal = 0
                                while (bb.hasRemaining()) {
                                    val r = try {
                                        ch.read(bb, curPos)
                                    } catch (_: Throwable) {
                                        try {
                                            android.system.Os.pread(descriptor.fileDescriptor, buf, rTotal, toRead - rTotal, curPos).also { n ->
                                                if (n > 0) bb.position(bb.position() + n)
                                            }
                                        } catch (_: Throwable) {
                                            -1
                                        }
                                    }
                                    if (r <= 0) return@verifyPhaseInvariants -1L
                                    curPos += r
                                    rTotal += r
                                }
                                crc.update(buf, 0, rTotal)
                                remaining -= rTotal
                            }
                            crc.value
                        }
                    }
                }
            } else {
                java.io.RandomAccessFile(archiveFile!!, "r").use { raf ->
                    verifyPhaseInvariants { offset, len ->
                        if (offset < 0L || len <= 0 || offset + len.toLong() > physLen) return@verifyPhaseInvariants -1L
                        val buf = ByteArray(minOf(len, 64 * 1024))
                        val crc = CRC32()
                        var remaining = len
                        raf.seek(offset)
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buf.size)
                            val r = raf.read(buf, 0, toRead)
                            if (r <= 0) return@verifyPhaseInvariants -1L
                            crc.update(buf, 0, r)
                            remaining -= r
                        }
                        crc.value
                    }
                }
            }
        } catch (t: Throwable) {
            DeflateTailSwapTxEligibility.Ineligible(
                "Failed to verify source archive bounds for '${ckpt.entryName}': ${t.localizedMessage ?: t.javaClass.simpleName}"
            )
        }
    }

    fun isStateSafelyResumable(
        state: ResumeJournalState,
        context: Context? = null
    ): Boolean {
        return assessResumeEligibility(state, context) is ResumeEligibility.Resumable
    }

    sealed class RestoreEligibility {
        data class Restorable(
            val remainingCount: Int,
            val noBytesReclaimed: Boolean,
            val hasPendingRestoreCheckpoint: Boolean = false,
            val pendingDeflateTailSwapEntryPath: String? = null
        ) : RestoreEligibility()
        data class UnsupportedFormat(val reason: String) : RestoreEligibility()
        data class Unrecoverable(val reason: String) : RestoreEligibility()
    }

    fun hasPendingRestorationCheckpoint(stateFilePath: String): Boolean {
        return loadRestorationCheckpoint(stateFilePath) != null
    }

    fun saveRestorationCheckpoint(state: RestorationCheckpointState): Boolean {
        val ckptFile = getRestorationCheckpointFile(state.stateFilePath)
        return try {
            writeAtomicFileWithBackup(ckptFile) { fos, beforeFooterSyncHook ->
                val crc = CRC32()
                val buffered = BufferedOutputStream(fos, 8 * 1024)
                val cos = CheckedOutputStream(buffered, crc)
                val dos = DataOutputStream(cos)

                dos.writeInt(RESTORE_CKPT_MAGIC)
                dos.writeInt(RESTORE_CKPT_VERSION)
                dos.writeUTF(state.stateFilePath)
                dos.writeUTF(state.archivePath)
                dos.writeUTF(state.sourceUriString.orEmpty())
                dos.writeUTF(state.archiveName)
                dos.writeLong(state.newCentralDirOffset)
                dos.writeLong(state.expectedFinalArchiveSize)
                dos.writeInt(state.remainingEntryCount)
                dos.writeInt(state.phase.code)
                dos.writeLong(state.timestamp)
                dos.flush()
                buffered.flush()
                beforeFooterSyncHook()

                val footerDos = DataOutputStream(fos)
                footerDos.writeLong(crc.value)
                footerDos.flush()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    private fun readRestorationCheckpointFile(file: File): RestorationCheckpointState? {
        if (!file.exists() || file.length() < 24L) return null
        return try {
            val payloadLen = file.length() - 8L
            val crc = CRC32()
            FileInputStream(file).use { fis ->
                val buffered = BufferedInputStream(fis, 8 * 1024)
                val cis = CheckedInputStream(LimitedInputStream(buffered, payloadLen), crc)
                val dis = DataInputStream(cis)

                val magic = dis.readInt()
                val version = dis.readInt()
                if (magic != RESTORE_CKPT_MAGIC || version != RESTORE_CKPT_VERSION) return null

                val savedStatePath = dis.readUTF()
                val archivePath = dis.readUTF()
                val sourceUriString = dis.readUTF().ifEmpty { null }
                val archiveName = dis.readUTF()
                val newCentralDirOffset = dis.readLong()
                val expectedFinalArchiveSize = dis.readLong()
                val remainingEntryCount = dis.readInt()
                val phaseCode = dis.readInt()
                val timestamp = dis.readLong()

                while (cis.read() != -1) {
                    // drain
                }

                val footerDis = DataInputStream(buffered)
                val expectedCrc = footerDis.readLong()
                if (crc.value != expectedCrc) return null

                RestorationCheckpointState(
                    stateFilePath = savedStatePath,
                    archivePath = archivePath,
                    sourceUriString = sourceUriString,
                    archiveName = archiveName,
                    newCentralDirOffset = newCentralDirOffset,
                    expectedFinalArchiveSize = expectedFinalArchiveSize,
                    remainingEntryCount = remainingEntryCount,
                    phase = RestorationPhase.fromCode(phaseCode),
                    timestamp = timestamp
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun loadRestorationCheckpoint(stateFilePath: String): RestorationCheckpointState? {
        val ckptFile = getRestorationCheckpointFile(stateFilePath)
        readRestorationCheckpointFile(ckptFile)?.let { return it }
        readRestorationCheckpointFile(getBackupFile(ckptFile))?.let { return it }
        return readRestorationCheckpointFile(getTempFile(ckptFile))
    }

    fun clearRestorationCheckpoint(stateFilePath: String) {
        try {
            val ckpt = getRestorationCheckpointFile(stateFilePath)
            val targets = listOf(
                ckpt,
                getBackupFile(ckpt),
                getTempFile(ckpt),
                getBackupTempFile(ckpt)
            )
            for (f in targets) {
                if (f.exists()) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Evaluates whether an interrupted Hatch Mode archive can be safely restored in-place as a valid
     * standalone ZIP/ZIP64 archive containing all remaining unextracted entries.
     */
    fun assessRestoreEligibility(
        state: ResumeJournalState,
        context: Context? = null
    ): RestoreEligibility {
        val lowerName = state.archiveName.lowercase()
        when {
            lowerName.endsWith(".7z") ->
                return RestoreEligibility.UnsupportedFormat("7Z solid archives do not support in-place ZIP Central Directory restoration.")
            lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") ->
                return RestoreEligibility.UnsupportedFormat("TAR archives do not use a ZIP Central Directory; use Resume to finish extraction.")
            lowerName.endsWith(".rar") ->
                return RestoreEligibility.UnsupportedFormat("RAR archives do not support in-place ZIP restoration.")
            lowerName.matches(Regex(".*\\.(zip\\.\\d{3}|z\\d{2})$")) ->
                return RestoreEligibility.UnsupportedFormat("Multi-part split archives cannot be rewritten as a single-volume ZIP.")
        }

        val fullIndex = loadInitialIndex(state.stateFilePath)
        val allEntries = fullIndex ?: state.remainingEntries
        if (allEntries.any { it.compressionMethod == 100 }) {
            return RestoreEligibility.UnsupportedFormat("TAR block entries cannot be restored as a ZIP archive.")
        }

        val archiveFile = state.archivePath.takeIf { it.isNotEmpty() }?.let { File(it) }
        val pendingRestoreCkpt = loadRestorationCheckpoint(state.stateFilePath)

        val physLen: Long = when {
            archiveFile != null -> {
                if (!archiveFile.exists()) {
                    return RestoreEligibility.Unrecoverable("Source archive file is missing on disk.")
                }
                if (!archiveFile.canWrite()) {
                    return RestoreEligibility.Unrecoverable("Source archive file is read-only.")
                }
                archiveFile.length()
            }
            !state.sourceUriString.isNullOrEmpty() && context != null -> {
                val uri = try {
                    android.net.Uri.parse(state.sourceUriString)
                } catch (_: Throwable) {
                    return RestoreEligibility.Unrecoverable("Source SAF URI is malformed.")
                }
                val pfd = try {
                    context.contentResolver.openFileDescriptor(uri, "rw")
                } catch (t: Throwable) {
                    return RestoreEligibility.Unrecoverable(
                        "Cannot open source SAF URI in read-write mode (${t.javaClass.simpleName})."
                    )
                } ?: return RestoreEligibility.Unrecoverable(
                    "DocumentProvider returned null read-write descriptor for source SAF URI."
                )
                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        val probe = StorageHelper.probeTransactableFileDescriptor(
                            fd = descriptor.fileDescriptor,
                            channel = ch,
                            requireWriteAndTruncate = true
                        )
                        if (!probe.isSupported) {
                            return RestoreEligibility.Unrecoverable(
                                "Source SAF archive descriptor does not support safe transactional I/O (${probe.reason}); requires a verified seekable read-write SAF provider (supporting ftruncate and fsync) or direct-POSIX access."
                            )
                        }
                        val resolvedSize = probe.sizeBytes
                        if (resolvedSize <= 0L) {
                            return RestoreEligibility.Unrecoverable("Source SAF archive is empty or unreadable.")
                        }
                        resolvedSize
                    }
                }
            }
            else -> state.currentArchiveBytes
        }

        val targetDir = File(state.targetDirPath)
        val resumeTreeUri = state.targetTreeUriString?.takeIf { it.isNotEmpty() }?.let {
            runCatching { android.net.Uri.parse(it) }.getOrNull()
        }
        val resumeFolderDocUri = state.targetFolderDocUriString?.takeIf { it.isNotEmpty() }?.let {
            runCatching { android.net.Uri.parse(it) }.getOrNull()
        }
        val isDirectPosixTarget = StorageHelper.isDirectPosixDestination(context, targetDir, resumeTreeUri)
        val safFolderDocUri = if (!isDirectPosixTarget && context != null && resumeTreeUri != null) {
            StorageHelper.verifySafDestinationAccessible(
                context = context,
                treeUri = resumeTreeUri,
                targetFolderDocUri = resumeFolderDocUri,
                subfolderName = targetDir.name
            )
        } else null

        val indexByPath = fullIndex?.associateBy { it.path } ?: emptyMap()
        val remainingByPath = state.remainingEntries.associateBy { it.path }

        var verifiedDeflateRecoveryEntryPath: String? = null
        var effectiveRemainingArchiveLimit = physLen

        fun evaluateDeflateTxRecoveryForRestore(
            guardForCheck: TailSwapGuardState?,
            expectedEntryPath: String? = null
        ): DeflateTailSwapTxEligibility = verifyDeflateTailSwapTransactionEligibility(
            state = state,
            context = context,
            archiveFile = archiveFile,
            physLen = physLen,
            isDirectPosixTarget = isDirectPosixTarget,
            targetDir = targetDir,
            resumeTreeUri = resumeTreeUri,
            safFolderDocUri = safFolderDocUri,
            guard = guardForCheck,
            indexByPath = indexByPath,
            remainingByPath = remainingByPath,
            expectedEntryPath = expectedEntryPath
        )

        // Check if a large DEFLATE entry had its head slot overwritten mid-stream without completing
        val guard = loadTailSwapGuard(state.stateFilePath)
        if (guard == null && hasCorruptedTailSwapGuard(state.stateFilePath)) {
            return RestoreEligibility.Unrecoverable(
                "Tail-swap recovery guard is corrupted and no valid backup exists; refusing to restore archive."
            )
        }
        if (hasCorruptedDeflateTailSwapCheckpoint(state.stateFilePath)) {
            return RestoreEligibility.Unrecoverable(
                "Deflate tail-swap transaction checkpoint is corrupted and no valid backup exists; refusing to restore archive."
            )
        }
        if (guard != null) {
            val destFile = if (isDirectPosixTarget) File(targetDir, guard.entryPath) else null
            val expectedCrc = if (guard.expectedEntryCrc32 != 0L) guard.expectedEntryCrc32 else state.verifiedEntryCrcs[guard.entryPath]
            val alreadyVerified = when {
                destFile != null -> verifyOutputFileIntegrity(destFile, guard.uncompressedSize, expectedCrc)
                context != null && resumeTreeUri != null && safFolderDocUri != null ->
                    StorageHelper.verifySafOutputFileIntegrity(
                        context = context,
                        treeUri = resumeTreeUri,
                        rootFolderDocUri = safFolderDocUri,
                        relativePath = guard.entryPath,
                        expectedSize = guard.uncompressedSize,
                        expectedCrc32 = expectedCrc
                    )
                else -> false
            }
            if (!alreadyVerified) {
                val expectedEnd = guard.payloadOffset + guard.compressedSize
                if (physLen < expectedEnd || guard.overwriteStarted) {
                    when (val txEligibility = evaluateDeflateTxRecoveryForRestore(guard, guard.entryPath)) {
                        is DeflateTailSwapTxEligibility.Eligible -> {
                            verifiedDeflateRecoveryEntryPath = txEligibility.entryPath
                            effectiveRemainingArchiveLimit = minOf(physLen, txEligibility.postRecoveryArchiveLen)
                        }
                        is DeflateTailSwapTxEligibility.Ineligible -> {
                            return RestoreEligibility.Unrecoverable(txEligibility.reason)
                        }
                        DeflateTailSwapTxEligibility.NoActiveCheckpoint -> {
                            return RestoreEligibility.Unrecoverable(
                                "Entry '${guard.entryName}' was interrupted during in-place DEFLATE tail-swap relocation; neither its output file nor its compressed stream is intact."
                            )
                        }
                    }
                }
            }
        }

        val remaining = if (fullIndex != null) {
            fullIndex.filter { !state.completedPaths.contains(it.path) }
        } else {
            state.remainingEntries
        }

        if (remaining.isEmpty() && pendingRestoreCkpt == null) {
            return RestoreEligibility.Unrecoverable("No remaining unextracted entries to restore.")
        }

        var restorableRemainingCount = 0
        val minBoundary = pendingRestoreCkpt?.newCentralDirOffset ?: run {
            var maxReq = 0L
            for (entry in remaining) {
                if (entry.path == verifiedDeflateRecoveryEntryPath) {
                    // This single interrupted DEFLATED entry will be finished & verified first,
                    // then excluded from the rebuilt remaining ZIP.
                    continue
                }
                if (entry.isDirectory) {
                    val dirHeaderEnd = entry.localHeaderOffset + 30L + entry.path.toByteArray(Charsets.UTF_8).size
                    if (dirHeaderEnd > effectiveRemainingArchiveLimit) {
                        return RestoreEligibility.Unrecoverable(
                            "Remaining directory entry '${entry.name}' header end ($dirHeaderEnd) exceeds physical archive size ($effectiveRemainingArchiveLimit)."
                        )
                    }
                    if (dirHeaderEnd > maxReq) maxReq = dirHeaderEnd
                    restorableRemainingCount++
                    continue
                }
                val destFile = if (isDirectPosixTarget) File(targetDir, entry.path) else null
                val expectedCrc = if (entry.crc32 != 0L) entry.crc32 else state.verifiedEntryCrcs[entry.path]
                val outputVerified = when {
                    destFile != null -> verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedCrc)
                    context != null && resumeTreeUri != null && safFolderDocUri != null ->
                        StorageHelper.verifySafOutputFileIntegrity(
                            context = context,
                            treeUri = resumeTreeUri,
                            rootFolderDocUri = safFolderDocUri,
                            relativePath = entry.path,
                            expectedSize = entry.uncompressedSize,
                            expectedCrc32 = expectedCrc
                        )
                    else -> false
                }
                val minPayloadEnd = entry.localHeaderOffset + 30L + entry.path.toByteArray(Charsets.UTF_8).size + entry.compressedSize
                if (outputVerified) {
                    if (minPayloadEnd <= effectiveRemainingArchiveLimit) {
                        if (minPayloadEnd > maxReq) maxReq = minPayloadEnd
                        restorableRemainingCount++
                    }
                    continue
                }
                val isLargeStored = entry.compressionMethod == 0 && entry.uncompressedSize >= 4L * 1024L * 1024L
                if (isLargeStored && destFile != null && destFile.exists() && destFile.length() == entry.uncompressedSize) {
                    // Recoverable large STORED entry if tail slices are on disk and head slices remain in archive
                    if (effectiveRemainingArchiveLimit < entry.localHeaderOffset + 30L + entry.path.toByteArray(Charsets.UTF_8).size) {
                        return RestoreEligibility.Unrecoverable(
                            "Remaining entry '${entry.name}' local header at ${entry.localHeaderOffset} exceeds physical archive size ($effectiveRemainingArchiveLimit)."
                        )
                    }
                    // Note: large STORED tail entry is completed in-place by ZipInPlaceRestoreEngine when truncated;
                    // only count it as remaining in the rebuilt ZIP if its full payload is still intact.
                    if (minPayloadEnd <= effectiveRemainingArchiveLimit) {
                        if (minPayloadEnd > maxReq) maxReq = minPayloadEnd
                        restorableRemainingCount++
                    }
                } else if (minPayloadEnd > effectiveRemainingArchiveLimit) {
                    if (verifiedDeflateRecoveryEntryPath == null && entry.compressionMethod == 8) {
                        when (val txEligibility = evaluateDeflateTxRecoveryForRestore(guard, entry.path)) {
                            is DeflateTailSwapTxEligibility.Eligible -> {
                                verifiedDeflateRecoveryEntryPath = txEligibility.entryPath
                                effectiveRemainingArchiveLimit = minOf(effectiveRemainingArchiveLimit, txEligibility.postRecoveryArchiveLen)
                                continue
                            }
                            is DeflateTailSwapTxEligibility.Ineligible -> {
                                return RestoreEligibility.Unrecoverable(txEligibility.reason)
                            }
                            DeflateTailSwapTxEligibility.NoActiveCheckpoint -> {
                                // fall through to standard truncated error
                            }
                        }
                    }
                    return RestoreEligibility.Unrecoverable(
                        "Remaining entry '${entry.name}' payload end ($minPayloadEnd) exceeds physical archive size ($effectiveRemainingArchiveLimit)."
                    )
                } else {
                    if (minPayloadEnd > maxReq) maxReq = minPayloadEnd
                    restorableRemainingCount++
                }
            }
            maxReq
        }

        if (pendingRestoreCkpt == null && restorableRemainingCount == 0) {
            return if (verifiedDeflateRecoveryEntryPath != null) {
                RestoreEligibility.Unrecoverable(
                    "Interrupted entry '$verifiedDeflateRecoveryEntryPath' is the final remaining entry in the archive; use Resume to finish extracting it."
                )
            } else {
                RestoreEligibility.Unrecoverable("No remaining unextracted entries to restore.")
            }
        }

        if (effectiveRemainingArchiveLimit < minBoundary) {
            return RestoreEligibility.Unrecoverable(
                "Physical archive size ($effectiveRemainingArchiveLimit) is smaller than required remaining entry boundary ($minBoundary)."
            )
        }

        val noBytesReclaimed = state.bytesReclaimedSoFar == 0L &&
            physLen == state.originalArchiveBytes &&
            pendingRestoreCkpt == null &&
            verifiedDeflateRecoveryEntryPath == null

        return RestoreEligibility.Restorable(
            remainingCount = if (pendingRestoreCkpt != null) {
                restorableRemainingCount.coerceAtLeast(pendingRestoreCkpt.remainingEntryCount)
            } else {
                restorableRemainingCount
            },
            noBytesReclaimed = noBytesReclaimed,
            hasPendingRestoreCheckpoint = pendingRestoreCkpt != null,
            pendingDeflateTailSwapEntryPath = verifiedDeflateRecoveryEntryPath
        )
    }

    fun deleteState(stateFilePath: String) {
        try {
            val baseFiles = listOf(
                File(stateFilePath),
                getIndexFile(stateFilePath),
                getBinaryCheckpointFile(stateFilePath),
                getTailSwapGuardFile(stateFilePath),
                getDeflateTailSwapCheckpointFile(stateFilePath),
                getRestorationCheckpointFile(stateFilePath)
            )
            for (base in baseFiles) {
                for (f in listOf(base, getBackupFile(base), getTempFile(base), getBackupTempFile(base))) {
                    if (f.exists()) f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun loadPendingStates(context: Context): List<ResumeJournalState> {
        val dir = getJournalsDir(context)
        val rawFiles = dir.listFiles() ?: return emptyList()
        val canonicalStateFiles = LinkedHashSet<File>()
        for (f in rawFiles) {
            val name = f.name
            val canonicalName = when {
                name.endsWith(".hatchstate.json") -> name
                name.endsWith(".hatchstate.json.bak") -> name.removeSuffix(".bak")
                name.endsWith(".hatchstate.json.tmp") -> name.removeSuffix(".tmp")
                name.endsWith(".hatchstate.json.ckpt.bin") -> name.removeSuffix(".ckpt.bin")
                name.endsWith(".hatchstate.json.ckpt.bin.bak") -> name.removeSuffix(".ckpt.bin.bak")
                else -> null
            }
            if (canonicalName != null) {
                canonicalStateFiles.add(File(dir, canonicalName))
            }
        }
        val results = mutableListOf<ResumeJournalState>()

        for (file in canonicalStateFiles) {
            try {
                val parsed = parseStateFile(file)
                if (parsed != null && (parsed.remainingEntries.isNotEmpty() || parsed.completedPaths.isNotEmpty() || parsed.hasPendingRestoration)) {
                    val localExists = parsed.archivePath.isNotEmpty() &&
                            File(parsed.archivePath).exists()
                    val hasSafUri = !parsed.sourceUriString.isNullOrEmpty()
                    if (localExists || hasSafUri) {
                        results.add(parsed)
                    } else {
                        deleteState(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return results.sortedByDescending { it.timestamp }
    }

    private fun parseJsonStateFile(jsonFile: File, canonicalStateFilePath: String): ResumeJournalState? {
        if (!jsonFile.exists() || jsonFile.length() <= 0L) return null
        return try {
            val rawText = jsonFile.readText(Charsets.UTF_8)
            val crcMarker = ",\"payloadCrc32\":"
            val markerIdx = rawText.lastIndexOf(crcMarker)
            if (markerIdx > 0) {
                val trimmedEnd = rawText.trimEnd()
                if (!trimmedEnd.endsWith("}")) return null
                val prefix = rawText.substring(0, markerIdx)
                val crcToken = trimmedEnd.substring(markerIdx + crcMarker.length, trimmedEnd.length - 1).trim()
                val expectedCrc = crcToken.toLongOrNull() ?: return null
                val actualCrc = CRC32().apply { update(prefix.toByteArray(Charsets.UTF_8)) }.value
                if (actualCrc != expectedCrc) return null
            }

            val root = JSONObject(rawText)
            val completedSet = mutableSetOf<String>()
            val completedArr = root.optJSONArray("completedPaths") ?: JSONArray()
            for (i in 0 until completedArr.length()) {
                completedSet.add(completedArr.getString(i))
            }

            val verifiedCrcs = mutableMapOf<String, Long>()
            val crcObj = root.optJSONObject("verifiedEntryCrcs")
            if (crcObj != null) {
                val keys = crcObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    verifiedCrcs[k] = crcObj.optLong(k, 0L)
                }
            }

            val externalIndex = loadInitialIndex(canonicalStateFilePath)
            val remainingList = if (externalIndex != null) {
                externalIndex.filter { !completedSet.contains(it.path) }
            } else {
                val list = mutableListOf<ZipEntryInfo>()
                val remainingArr = root.optJSONArray("remainingEntries") ?: JSONArray()
                for (i in 0 until remainingArr.length()) {
                    val obj = remainingArr.getJSONObject(i)
                    val path = obj.getString("path")
                    if (!completedSet.contains(path)) {
                        list.add(
                            ZipEntryInfo(
                                path = path,
                                name = obj.getString("name"),
                                isDirectory = obj.getBoolean("isDirectory"),
                                compressedSize = obj.getLong("compressedSize"),
                                uncompressedSize = obj.getLong("uncompressedSize"),
                                crc32 = obj.getLong("crc32"),
                                compressionMethod = obj.getInt("compressionMethod"),
                                localHeaderOffset = obj.getLong("localHeaderOffset"),
                                lastModified = obj.optLong("lastModified", System.currentTimeMillis()),
                                isEncrypted = obj.optBoolean("isEncrypted", false),
                                isZip64 = obj.optBoolean("isZip64", false)
                            )
                        )
                    }
                }
                list
            }

            val uriStr = root.optString("sourceUriString", "").ifEmpty { null }
            val targetTreeUriStr = root.optString("targetTreeUriString", "").ifEmpty { null }
            val targetFolderDocUriStr = root.optString("targetFolderDocUriString", "").ifEmpty { null }
            ResumeJournalState(
                stateFilePath = canonicalStateFilePath,
                archivePath = root.optString("archivePath", ""),
                sourceUriString = uriStr,
                archiveName = root.optString("archiveName", "Archive.zip"),
                targetDirPath = root.optString("targetDirPath", ""),
                originalArchiveBytes = root.optLong("originalArchiveBytes", 0L),
                currentArchiveBytes = root.optLong("currentArchiveBytes", 0L),
                bytesReclaimedSoFar = root.optLong("bytesReclaimedSoFar", 0L),
                totalEntries = root.optInt("totalEntries", remainingList.size + completedSet.size),
                completedPaths = completedSet,
                remainingEntries = remainingList,
                timestamp = root.optLong("timestamp", System.currentTimeMillis()),
                verifiedEntryCrcs = verifiedCrcs,
                targetTreeUriString = targetTreeUriStr,
                targetFolderDocUriString = targetFolderDocUriStr,
                hasPendingRestoration = hasPendingRestorationCheckpoint(canonicalStateFilePath)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun parseStateFile(file: File): ResumeJournalState? {
        // First attempt CRC-verified binary checkpoint (`<stateFilePath>.ckpt.bin`, `.bak`, or `.tmp`)
        loadBinaryCheckpoint(file)?.let { return it }

        // Fallback to parsing `.hatchstate.json` (primary, `.bak`, or `.tmp`, with legacy compatibility)
        parseJsonStateFile(file, file.absolutePath)?.let { return it }
        parseJsonStateFile(getBackupFile(file), file.absolutePath)?.let { return it }
        return parseJsonStateFile(getTempFile(file), file.absolutePath)
    }

    /**
     * Computes the full-stream CRC32 digest of [file], or `-1L` if the file cannot be read.
     */
    fun computeFileCrc32(file: File): Long {
        if (!file.exists() || !file.isFile) return -1L
        return try {
            val crc = CRC32()
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = fis.read(buf)
                    if (r <= 0) break
                    crc.update(buf, 0, r)
                }
            }
            crc.value
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Computes the CRC32 digest of the exact prefix `[0, prefixLength)` of [file],
     * or `-1L` if [file] does not exist, is shorter than [prefixLength], or cannot be read.
     */
    fun computeFilePrefixCrc32(file: File, prefixLength: Long): Long {
        if (prefixLength < 0L || !file.exists() || !file.isFile || file.length() < prefixLength) return -1L
        if (prefixLength == 0L) return 0L
        return try {
            val crc = CRC32()
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                var remaining = prefixLength
                while (remaining > 0L) {
                    val toRead = remaining.coerceAtMost(buf.size.toLong()).toInt()
                    val r = fis.read(buf, 0, toRead)
                    if (r <= 0) return -1L
                    crc.update(buf, 0, r)
                    remaining -= r.toLong()
                }
            }
            crc.value
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Trustworthy verification of a completed output file on disk.
     * Never relies solely on file existence or file length:
     * - Verifies exact uncompressed byte length (`file.length() == expectedSize`).
     * - Streams the entire file on disk to compute its actual `CRC32` and compares against
     *   [expectedCrc32] (from the archive header or from `verifiedEntryCrcs` recorded in the journal).
     */
    fun verifyOutputFileIntegrity(
        file: File,
        expectedSize: Long,
        expectedCrc32: Long?
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (expectedSize < 0L || file.length() != expectedSize) return false
        if (expectedSize == 0L) {
            return expectedCrc32 == null || expectedCrc32 == 0L
        }
        val actualCrc = computeFileCrc32(file)
        if (actualCrc < 0L) return false
        if (expectedCrc32 != null && expectedCrc32 != 0L) {
            return actualCrc == expectedCrc32
        }
        // If expectedCrc32 is 0L (e.g. legacy TAR without recorded CRC), require non-zero CRC
        return actualCrc != 0L
    }

    private class LimitedInputStream(
        private val delegate: java.io.InputStream,
        private var remaining: Long
    ) : java.io.InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val r = delegate.read()
            if (r != -1) remaining--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = len.toLong().coerceAtMost(remaining).toInt()
            val r = delegate.read(b, off, toRead)
            if (r > 0) remaining -= r
            return r
        }
    }
}
