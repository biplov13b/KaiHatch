package com.example.data

import android.content.Context
import com.example.model.ResumeJournalState
import com.example.model.ZipEntryInfo
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
    private const val CKPT_VERSION = 1
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

    private fun getIndexFile(stateFilePath: String): File = File("$stateFilePath.index.bin")
    private fun getBinaryCheckpointFile(stateFilePath: String): File = File("$stateFilePath.ckpt.bin")

    /**
     * Writes the complete descending entry table once to `<stateFilePath>.index.bin` using
     * streaming binary I/O + CRC32 footer + `fd.sync()` + atomic rename.
     * Supports 50,000+ APK/ZIP entries with minimal heap allocation.
     */
    fun saveInitialIndex(stateFilePath: String, orderedEntries: List<ZipEntryInfo>): Boolean {
        return try {
            val indexFile = getIndexFile(stateFilePath)
            indexFile.parentFile?.mkdirs()
            val tmpFile = File(indexFile.parentFile, "${indexFile.name}.tmp")
            val crc = CRC32()

            FileOutputStream(tmpFile).use { fos ->
                val cos = CheckedOutputStream(BufferedOutputStream(fos, 64 * 1024), crc)
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

                // Write 8-byte CRC32 footer directly (unhashed by CheckedOutputStream)
                val footerOut = DataOutputStream(fos)
                footerOut.writeLong(crc.value)
                footerOut.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }

            if (indexFile.exists()) indexFile.delete()
            tmpFile.renameTo(indexFile)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Reads and CRC-verifies the streaming binary entry table `<stateFilePath>.index.bin`.
     * Returns `null` if the file is missing or its CRC32 footer indicates a torn write.
     */
    fun loadInitialIndex(stateFilePath: String): List<ZipEntryInfo>? {
        val indexFile = getIndexFile(stateFilePath)
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
     * Durably commits [state] to disk with `fd.sync()` and atomic `.tmp` rename.
     * Automatically creates `<stateFilePath>.index.bin` if it does not yet exist.
     */
    fun saveState(state: ResumeJournalState) {
        try {
            val targetFile = File(state.stateFilePath)
            targetFile.parentFile?.mkdirs()

            val indexFile = getIndexFile(state.stateFilePath)
            if (!indexFile.exists() && state.remainingEntries.isNotEmpty()) {
                saveInitialIndex(state.stateFilePath, state.remainingEntries)
            }

            // 1. Write binary checkpoint `<stateFilePath>.ckpt.bin` with CRC32 + fd.sync()
            saveBinaryCheckpoint(state)

            // 2. Write `.hatchstate.json` with pure-Kotlin JSON builder + fd.sync() + atomic rename
            val jsonText = buildJsonStateText(state, includeInlineEntries = state.remainingEntries.size <= INLINE_JSON_ENTRY_LIMIT)
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonText.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveBinaryCheckpoint(state: ResumeJournalState) {
        val ckptFile = getBinaryCheckpointFile(state.stateFilePath)
        val tmpFile = File(ckptFile.parentFile, "${ckptFile.name}.tmp")
        val crc = CRC32()
        FileOutputStream(tmpFile).use { fos ->
            val cos = CheckedOutputStream(BufferedOutputStream(fos, 32 * 1024), crc)
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
            val hasExternalIndex = getIndexFile(state.stateFilePath).exists()
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
            dos.flush()

            val footerOut = DataOutputStream(fos)
            footerOut.writeLong(crc.value)
            footerOut.flush()
            try {
                fos.fd.sync()
            } catch (_: Throwable) {
            }
        }
        if (ckptFile.exists()) ckptFile.delete()
        tmpFile.renameTo(ckptFile)
    }

    private fun loadBinaryCheckpoint(stateFile: File): ResumeJournalState? {
        val ckptFile = getBinaryCheckpointFile(stateFile.absolutePath)
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
                if (magic != CKPT_MAGIC || version != CKPT_VERSION) return null

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
                val completedSet = LinkedHashSet<String>(completedCount.coerceAtLeast(16))
                for (i in 0 until completedCount) {
                    completedSet.add(dis.readUTF())
                }

                val crcCount = dis.readInt()
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

                while (cis.read() != -1) {
                    // drain
                }

                val footerDis = DataInputStream(buffered)
                val expectedCrc = footerDis.readLong()
                if (crc.value != expectedCrc) return null

                val remainingEntries = if (hasExternalIndex) {
                    val fullIndex = loadInitialIndex(stateFile.absolutePath) ?: loadInitialIndex(savedStatePath) ?: return null
                    fullIndex.filter { !completedSet.contains(it.path) }
                } else {
                    inlineRemaining
                }

                ResumeJournalState(
                    stateFilePath = stateFile.absolutePath,
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
                    verifiedEntryCrcs = verifiedCrcs
                )
            }
        } catch (_: Throwable) {
            null
        }
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
        sb.append("]}")
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

    fun saveTailSwapGuard(stateFilePath: String, guard: TailSwapGuardState) {
        try {
            val guardFile = getTailSwapGuardFile(stateFilePath)
            guardFile.parentFile?.mkdirs()
            val tmpFile = File(guardFile.parentFile, "${guardFile.name}.tmp")
            val crc = CRC32()
            FileOutputStream(tmpFile).use { fos ->
                val cos = CheckedOutputStream(BufferedOutputStream(fos, 4096), crc)
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

                val footerOut = DataOutputStream(fos)
                footerOut.writeLong(crc.value)
                footerOut.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }
            if (guardFile.exists()) guardFile.delete()
            tmpFile.renameTo(guardFile)
        } catch (_: Throwable) {
        }
    }

    fun loadTailSwapGuard(stateFilePath: String): TailSwapGuardState? {
        val guardFile = getTailSwapGuardFile(stateFilePath)
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

    fun clearTailSwapGuard(stateFilePath: String) {
        try {
            val guardFile = getTailSwapGuardFile(stateFilePath)
            val tmpFile = File(guardFile.parentFile, "${guardFile.name}.tmp")
            if (guardFile.exists()) guardFile.delete()
            if (tmpFile.exists()) tmpFile.delete()
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
        if (archiveFile != null && (!archiveFile.exists() || archiveFile.length() <= 0L)) {
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
                        resolvedSize = try {
                            ch.size().takeIf { it >= 0L } ?: descriptor.statSize
                        } catch (_: Throwable) {
                            try {
                                android.system.Os.fstat(descriptor.fileDescriptor).st_size
                            } catch (_: Throwable) {
                                descriptor.statSize
                            }
                        }
                        if (resolvedSize <= 0L) {
                            return ResumeEligibility.Unrecoverable(
                                "Source SAF archive no longer exists or is empty on disk (size=$resolvedSize)."
                            )
                        }
                        try {
                            android.system.Os.ftruncate(descriptor.fileDescriptor, resolvedSize)
                            FileOutputStream(descriptor.fileDescriptor).channel.truncate(resolvedSize)
                        } catch (t: Throwable) {
                            return ResumeEligibility.Unrecoverable(
                                "Source SAF archive descriptor does not support in-place truncation (${t.javaClass.simpleName})."
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

        // 1. Check active TailSwapGuardState for large DEFLATE entry
        val guard = loadTailSwapGuard(state.stateFilePath)
        if (guard != null) {
            val destFile = File(targetDir, guard.entryPath)
            val expectedCrc = if (guard.expectedEntryCrc32 != 0L) guard.expectedEntryCrc32 else state.verifiedEntryCrcs[guard.entryPath]
            val alreadyVerified = verifyOutputFileIntegrity(destFile, guard.uncompressedSize, expectedCrc)
            if (!alreadyVerified) {
                val expectedEnd = guard.payloadOffset + guard.compressedSize
                if (physLen < expectedEnd) {
                    return ResumeEligibility.Unrecoverable(
                        "compressed data was already overwritten and truncated ($physLen < $expectedEnd) for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                    )
                }
                if (archiveFile != null && guard.chunk0Len > 0) {
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
                        return ResumeEligibility.Unrecoverable(
                            "compressed head slot 0 was already overwritten via pwrite (expected CRC 0x${String.format("%08X", guard.chunk0OriginalCrc32)}, got 0x${String.format("%08X", slot0Crc)}, overwriteStarted=${guard.overwriteStarted}) for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                        )
                    }
                } else if (!state.sourceUriString.isNullOrEmpty() && context != null && guard.chunk0Len > 0) {
                    val slot0Bytes = safSlot0Bytes
                    val slot0Crc = if (slot0Bytes != null && slot0Bytes.size == guard.chunk0Len) {
                        val c = CRC32()
                        c.update(slot0Bytes, 0, slot0Bytes.size)
                        c.value
                    } else {
                        -1L
                    }
                    if (slot0Crc != guard.chunk0OriginalCrc32 || guard.overwriteStarted) {
                        return ResumeEligibility.Unrecoverable(
                            "compressed head slot 0 was already overwritten via pwrite (expected CRC 0x${String.format("%08X", guard.chunk0OriginalCrc32)}, got 0x${String.format("%08X", slot0Crc)}, overwriteStarted=${guard.overwriteStarted}) for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                        )
                    }
                } else if (guard.overwriteStarted) {
                    return ResumeEligibility.Unrecoverable(
                        "compressed head slot 0 was already overwritten via pwrite for '${guard.entryName}' before entry completion, and Java Inflater cannot resume mid-stream."
                    )
                }
            }
        }

        // 2. Check all completedPaths: if an output file is corrupted/missing on disk AND its source region
        // in the archive was already truncated, the archive cannot be safely resumed.
        val fullIndex = loadInitialIndex(state.stateFilePath)
        val indexByPath = fullIndex?.associateBy { it.path } ?: emptyMap()
        for (compPath in state.completedPaths) {
            val meta = indexByPath[compPath] ?: continue
            if (meta.isDirectory) continue
            val destFile = File(targetDir, meta.path)
            val expectedCrc = if (meta.crc32 != 0L) meta.crc32 else state.verifiedEntryCrcs[compPath]
            if (!verifyOutputFileIntegrity(destFile, meta.uncompressedSize, expectedCrc)) {
                val approxEnd = meta.localHeaderOffset + 30L + meta.path.length + meta.compressedSize
                if (approxEnd > physLen) {
                    return ResumeEligibility.Unrecoverable(
                        "Completed entry '${meta.name}' failed output CRC32 verification and its source archive region was already truncated."
                    )
                }
            }
        }

        // 3. Check remainingEntries: if any uncompleted entry is not already verified on disk and its source
        // region in the archive has been truncated (beyond recoverable STORED slice boundary), it is unrecoverable.
        val remaining = if (fullIndex != null) {
            fullIndex.filter { !state.completedPaths.contains(it.path) }
        } else {
            state.remainingEntries
        }
        for (entry in remaining) {
            if (entry.isDirectory) continue
            val destFile = File(targetDir, entry.path)
            val expectedCrc = if (entry.crc32 != 0L) entry.crc32 else state.verifiedEntryCrcs[entry.path]
            if (verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedCrc)) continue

            val minPayloadStart = entry.localHeaderOffset + 30L + entry.path.length
            val minPayloadEnd = minPayloadStart + entry.compressedSize
            val isLargeStored = (entry.compressionMethod == 0 || entry.compressionMethod == 100) &&
                    entry.uncompressedSize >= 4L * 1024L * 1024L
            if (isLargeStored) {
                if (minPayloadEnd > physLen && (!destFile.exists() || destFile.length() != entry.uncompressedSize)) {
                    return ResumeEligibility.Unrecoverable(
                        "Interrupted large STORED entry '${entry.name}' was partially truncated ($physLen < $minPayloadEnd) and its previously extracted tail slices are missing on disk."
                    )
                }
            } else if (minPayloadEnd > physLen) {
                return ResumeEligibility.Unrecoverable(
                    "Source archive region for '${entry.name}' was already truncated ($physLen < $minPayloadEnd) before entry completion."
                )
            }
        }

        return ResumeEligibility.Resumable
    }

    fun isStateSafelyResumable(
        state: ResumeJournalState,
        context: Context? = null
    ): Boolean {
        return assessResumeEligibility(state, context) is ResumeEligibility.Resumable
    }

    fun deleteState(stateFilePath: String) {
        try {
            val targets = listOf(
                File(stateFilePath),
                File("$stateFilePath.tmp"),
                getIndexFile(stateFilePath),
                File("${getIndexFile(stateFilePath).absolutePath}.tmp"),
                getBinaryCheckpointFile(stateFilePath),
                File("${getBinaryCheckpointFile(stateFilePath).absolutePath}.tmp"),
                getTailSwapGuardFile(stateFilePath),
                File("${getTailSwapGuardFile(stateFilePath).absolutePath}.tmp")
            )
            for (f in targets) {
                if (f.exists()) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun loadPendingStates(context: Context): List<ResumeJournalState> {
        val dir = getJournalsDir(context)
        val files = dir.listFiles { f -> f.name.endsWith(".hatchstate.json") } ?: return emptyList()
        val results = mutableListOf<ResumeJournalState>()

        for (file in files) {
            try {
                val parsed = parseStateFile(file)
                if (parsed != null && (parsed.remainingEntries.isNotEmpty() || parsed.completedPaths.isNotEmpty())) {
                    val localExists = parsed.archivePath.isNotEmpty() &&
                            File(parsed.archivePath).exists() &&
                            File(parsed.archivePath).length() > 0L
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

    fun parseStateFile(file: File): ResumeJournalState? {
        if (!file.exists()) return null

        // First attempt CRC-verified binary checkpoint (`<stateFilePath>.ckpt.bin` + `.index.bin`)
        loadBinaryCheckpoint(file)?.let { return it }

        // Fallback to parsing `.hatchstate.json` directly (legacy compatibility)
        return try {
            val root = JSONObject(file.readText())
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

            val externalIndex = loadInitialIndex(file.absolutePath)
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
            ResumeJournalState(
                stateFilePath = file.absolutePath,
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
                verifiedEntryCrcs = verifiedCrcs
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
