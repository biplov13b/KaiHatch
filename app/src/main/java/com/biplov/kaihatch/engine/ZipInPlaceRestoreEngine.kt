package com.biplov.kaihatch.engine

import android.content.Context
import android.net.Uri
import android.system.Os
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.RestorationCheckpointState
import com.biplov.kaihatch.model.RestorationPhase
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Safe, crash-consistent in-place ZIP/ZIP64 Central Directory & EOCD restoration engine.
 *
 * Reconstructs a valid standalone ZIP/ZIP64 archive directly inside the original archive file
 * for all remaining unextracted entries `[0 .. newCentralDirOffset)` without creating a second
 * ZIP, copying payloads, recompressing entries, or overwriting intact entry data.
 */
object ZipInPlaceRestoreEngine {

    private const val LFH_SIG = 0x04034b50
    private const val CD_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val DATA_DESCRIPTOR_SIG = 0x08074b50
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val MIN_SAFETY_MARGIN_BYTES = 64 * 1024L

    sealed class RestoreOutcome {
        data class RestoredInPlace(
            val finalArchiveBytes: Long,
            val remainingEntryCount: Int,
            val isZip64: Boolean,
            val noRewriteNeeded: Boolean = false
        ) : RestoreOutcome()

        data class Failed(
            val reason: String,
            val archiveModified: Boolean = false
        ) : RestoreOutcome()
    }

    private data class ValidatedRemainingEntry(
        val info: ZipEntryInfo,
        val lfhVersionNeeded: Int,
        val lfhGpBitFlag: Int,
        val lfhMethod: Int,
        val lfhDosTime: Int,
        val lfhDosDate: Int,
        val lfhNameBytes: ByteArray,
        val payloadOffset: Long,
        val payloadEnd: Long,
        val needsZip64: Boolean
    ) {
        val cdExtraLen: Int get() = if (needsZip64) 28 else 0
        val cdRecordByteLength: Int get() = 46 + lfhNameBytes.size + cdExtraLen
    }

    /**
     * Restores the remaining unextracted entries of an interrupted or stopped Hatch Mode ZIP/ZIP64
     * archive directly on [channel] in-place.
     *
     * Guarantees:
     * - If no archive bytes were reclaimed (`bytesReclaimedSoFar == 0L && physicalSize == originalArchiveBytes`)
     *   and no prior restoration checkpoint exists, cleans up recovery state without rewriting the ZIP.
     * - Never touches any bytes in `[0 .. newCentralDirOffset)`.
     * - Writes a durable [RestorationCheckpointState] before modifying `[newCentralDirOffset .. EOF)`
     *   so an interrupted restoration can be safely retried idempotently.
     * - Flushes (`fsync`) and validates the restored ZIP with [ArchiveVerifierEngine] and [ZipParser]
     *   before deleting recovery metadata.
     */
    fun restoreRemainingZipOnChannel(
        channel: FileChannel,
        fd: FileDescriptor?,
        stateFilePath: String,
        archivePath: String,
        sourceUriString: String?,
        archiveName: String,
        targetDir: File?,
        originalArchiveBytes: Long,
        bytesReclaimedSoFar: Long,
        allEntries: List<ZipEntryInfo>,
        completedPathsInput: Set<String>,
        verifiedEntryCrcsInput: Map<String, Long>,
        context: Context? = null,
        targetTreeUri: Uri? = null,
        targetFolderDocUri: Uri? = null,
        faultInjector: TruncationFaultInjector = TruncationFaultInjector.NoOp,
        simulatedAvailableDiskBytes: Long? = null,
        onLog: ((String) -> Unit)? = null,
        deferJournalDeletion: Boolean = false
    ): RestoreOutcome {
        val lowerName = archiveName.lowercase()
        when {
            lowerName.endsWith(".7z") ->
                return RestoreOutcome.Failed("7Z solid archives do not support in-place ZIP restoration.")
            lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") ->
                return RestoreOutcome.Failed("TAR archives do not use a ZIP Central Directory.")
            lowerName.endsWith(".rar") ->
                return RestoreOutcome.Failed("RAR archives do not support in-place ZIP restoration.")
            lowerName.matches(Regex(".*\\.(zip\\.\\d{3}|z\\d{2})$")) ->
                return RestoreOutcome.Failed("Multi-part split archives cannot be rewritten as a single-volume ZIP.")
        }

        if (allEntries.any { it.compressionMethod == 100 }) {
            return RestoreOutcome.Failed("TAR block entries cannot be restored as a ZIP archive.")
        }

        val pendingCkpt = ResumeJournalManager.loadRestorationCheckpoint(stateFilePath)
        val initialPhysicalSize = try {
            channel.size()
        } catch (e: Throwable) {
            return RestoreOutcome.Failed("Cannot read archive channel size (${e.message}).")
        }

        if (initialPhysicalSize <= 0L) {
            return RestoreOutcome.Failed("Archive is empty on disk (0 bytes); cannot restore ZIP.")
        }

        // Fast-path: If no archive bytes were reclaimed and the original ZIP structure is still intact,
        // stop normally without rewriting the ZIP.
        if (bytesReclaimedSoFar == 0L &&
            initialPhysicalSize == originalArchiveBytes &&
            pendingCkpt == null
        ) {
            val structuralError = ArchiveVerifierEngine.validateZipEocdAndZip64Structure(
                channel = channel,
                fileLen = initialPhysicalSize
            )
            if (structuralError == null) {
                onLog?.invoke("[RESTORE] No archive bytes were reclaimed; original ZIP is intact (${StorageHelper.formatBytes(initialPhysicalSize)}).")
                if (!deferJournalDeletion) {
                    ResumeJournalManager.deleteState(stateFilePath)
                }
                return RestoreOutcome.RestoredInPlace(
                    finalArchiveBytes = initialPhysicalSize,
                    remainingEntryCount = allEntries.size,
                    isZip64 = allEntries.any { it.isZip64 },
                    noRewriteNeeded = true
                )
            }
        }

        val completedPaths = LinkedHashSet(completedPathsInput)
        val verifiedCrcs = LinkedHashMap(verifiedEntryCrcsInput)
        val isDirectPosixTarget = targetDir != null &&
            StorageHelper.isDirectPosixDestination(context, targetDir, targetTreeUri)
        val safFolderDocUri = if (!isDirectPosixTarget && context != null && targetDir != null && targetTreeUri != null) {
            StorageHelper.verifySafDestinationAccessible(
                context = context,
                treeUri = targetTreeUri,
                targetFolderDocUri = targetFolderDocUri,
                subfolderName = targetDir.name
            )
        } else null

        // Check active TailSwapGuardState and DeflateTailSwapCheckpoint before modifying anything
        val guard = ResumeJournalManager.loadTailSwapGuard(stateFilePath)
        if (guard == null && ResumeJournalManager.hasCorruptedTailSwapGuard(stateFilePath)) {
            return RestoreOutcome.Failed(
                "Cannot restore ZIP: tail-swap recovery guard is corrupted and no valid backup exists."
            )
        }
        if (ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(stateFilePath)) {
            return RestoreOutcome.Failed(
                "Cannot restore ZIP: deflate tail-swap transaction checkpoint is corrupted and no valid backup exists."
            )
        }
        if (guard != null) {
            val destFile = if (isDirectPosixTarget && targetDir != null) File(targetDir, guard.entryPath) else null
            val expectedCrc = if (guard.expectedEntryCrc32 != 0L) guard.expectedEntryCrc32 else verifiedCrcs[guard.entryPath]
            val alreadyVerified = when {
                destFile != null ->
                    ResumeJournalManager.verifyOutputFileIntegrity(destFile, guard.uncompressedSize, expectedCrc)
                context != null && targetTreeUri != null && safFolderDocUri != null ->
                    StorageHelper.verifySafOutputFileIntegrity(
                        context = context,
                        treeUri = targetTreeUri,
                        rootFolderDocUri = safFolderDocUri,
                        relativePath = guard.entryPath,
                        expectedSize = guard.uncompressedSize,
                        expectedCrc32 = expectedCrc
                    )
                else -> false
            }
            if (alreadyVerified) {
                completedPaths.add(guard.entryPath)
                ResumeJournalManager.clearTailSwapGuard(stateFilePath)
            } else {
                val expectedEnd = guard.payloadOffset + guard.compressedSize
                if (initialPhysicalSize < expectedEnd || guard.overwriteStarted) {
                    return RestoreOutcome.Failed(
                        "Cannot restore ZIP: entry '${guard.entryName}' was interrupted mid-tail-swap relocation and its compressed payload was modified."
                    )
                }
                // Guard was written before any pwrite or truncate occurred; delete partial output file and clear guard
                if (destFile != null && destFile.exists()) {
                    destFile.delete()
                } else if (context != null && targetTreeUri != null && safFolderDocUri != null) {
                    StorageHelper.findSafRelativeDocumentUri(context, targetTreeUri, safFolderDocUri, guard.entryPath)
                        ?.let { StorageHelper.deleteSafDocument(context, it) }
                }
                ResumeJournalManager.clearTailSwapGuard(stateFilePath)
                ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateFilePath)
            }
        }

        // Sort full index ascending by localHeaderOffset so we know exact adjacent boundaries
        val allAscending = allEntries.sortedBy { it.localHeaderOffset }
        if (allAscending.isEmpty()) {
            return RestoreOutcome.Failed("Entry index is empty; cannot reconstruct Central Directory.")
        }

        var currentPhysicalSize = initialPhysicalSize

        // Reconcile any unrecorded completed entry or partially truncated large STORED entry at the tail
        val tailCandidates = allAscending.filter { !completedPaths.contains(it.path) }.sortedByDescending { it.localHeaderOffset }
        for (candidate in tailCandidates) {
            if (candidate.isDirectory) continue
            val lfhEndMin = candidate.localHeaderOffset + 30L + candidate.path.toByteArray(Charsets.UTF_8).size
            val minPayloadEnd = lfhEndMin + candidate.compressedSize
            val destFile = if (isDirectPosixTarget && targetDir != null) File(targetDir, candidate.path) else null
            val expectedCrc = if (candidate.crc32 != 0L) candidate.crc32 else verifiedCrcs[candidate.path]

            val outputVerified = when {
                destFile != null ->
                    ResumeJournalManager.verifyOutputFileIntegrity(destFile, candidate.uncompressedSize, expectedCrc)
                context != null && targetTreeUri != null && safFolderDocUri != null ->
                    StorageHelper.verifySafOutputFileIntegrity(
                        context = context,
                        treeUri = targetTreeUri,
                        rootFolderDocUri = safFolderDocUri,
                        relativePath = candidate.path,
                        expectedSize = candidate.uncompressedSize,
                        expectedCrc32 = expectedCrc
                    )
                else -> false
            }

            if (outputVerified && currentPhysicalSize < minPayloadEnd) {
                // Entry was already extracted and verified before crash, and its archive payload was truncated
                completedPaths.add(candidate.path)
                continue
            }

            // Check if this is an interrupted large STORED entry with intact tail slices on POSIX disk
            val isLargeStored = candidate.compressionMethod == 0 && candidate.uncompressedSize >= 4L * 1024L * 1024L
            if (!outputVerified &&
                isLargeStored &&
                destFile != null &&
                destFile.exists() &&
                destFile.length() == candidate.uncompressedSize &&
                currentPhysicalSize > candidate.localHeaderOffset + 30L &&
                currentPhysicalSize < minPayloadEnd
            ) {
                val recovered = tryCompleteInterruptedStoredEntry(
                    channel = channel,
                    fd = fd,
                    entry = candidate,
                    destFile = destFile,
                    currentPhysicalSize = currentPhysicalSize
                )
                if (recovered != null) {
                    currentPhysicalSize = recovered
                    completedPaths.add(candidate.path)
                    verifiedCrcs[candidate.path] = candidate.crc32
                    onLog?.invoke("[RESTORE] Completed in-flight STORED slices for ${candidate.name} before finalizing ZIP.")
                    continue
                } else {
                    return RestoreOutcome.Failed(
                        "Cannot restore ZIP: interrupted large STORED entry '${candidate.name}' was partially truncated and its output slices failed CRC32 verification."
                    )
                }
            }
            break
        }

        // Filter remaining entries in ascending order of localHeaderOffset
        val remainingIndices = mutableListOf<Int>()
        for (i in allAscending.indices) {
            if (!completedPaths.contains(allAscending[i].path)) {
                remainingIndices.add(i)
            }
        }

        if (remainingIndices.isEmpty()) {
            return RestoreOutcome.Failed("All archive entries have already been extracted; no remaining entries to restore.")
        }

        // Verify that remaining entries form a contiguous prefix of allAscending (or strictly ascending non-overlapping prefix)
        // and remove any partial/unverified output files for remaining entries so destination has no corrupt files
        for (idx in remainingIndices) {
            val remEntry = allAscending[idx]
            if (!remEntry.isDirectory) {
                val destFile = if (isDirectPosixTarget && targetDir != null) File(targetDir, remEntry.path) else null
                val expectedCrc = if (remEntry.crc32 != 0L) remEntry.crc32 else verifiedCrcs[remEntry.path]
                if (destFile != null && destFile.exists()) {
                    if (!ResumeJournalManager.verifyOutputFileIntegrity(destFile, remEntry.uncompressedSize, expectedCrc)) {
                        destFile.delete()
                    }
                } else if (destFile == null && context != null && targetTreeUri != null && safFolderDocUri != null) {
                    val safDocUri = StorageHelper.findSafRelativeDocumentUri(context, targetTreeUri, safFolderDocUri, remEntry.path)
                    if (safDocUri != null &&
                        !StorageHelper.verifySafOutputFileIntegrity(
                            context = context,
                            treeUri = targetTreeUri,
                            rootFolderDocUri = safFolderDocUri,
                            relativePath = remEntry.path,
                            expectedSize = remEntry.uncompressedSize,
                            expectedCrc32 = expectedCrc
                        )
                    ) {
                        StorageHelper.deleteSafDocument(context, safDocUri)
                    }
                }
            }
        }

        // Validate every remaining entry's Local File Header & payload bounds in [0 .. currentPhysicalSize)
        val lfhHeaderBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        val validatedEntries = ArrayList<ValidatedRemainingEntry>(remainingIndices.size)
        var prevEndOffset = 0L

        for (pos in remainingIndices.indices) {
            val origIdx = remainingIndices[pos]
            val entry = allAscending[origIdx]
            if (entry.localHeaderOffset < prevEndOffset) {
                return RestoreOutcome.Failed(
                    "Invalid overlapping entry offset for '${entry.path}' (${entry.localHeaderOffset} < $prevEndOffset)."
                )
            }
            if (entry.localHeaderOffset + 30L > currentPhysicalSize) {
                return RestoreOutcome.Failed(
                    "Remaining entry '${entry.path}' Local File Header at ${entry.localHeaderOffset} exceeds physical archive size ($currentPhysicalSize)."
                )
            }

            lfhHeaderBuf.clear()
            if (!readFullyAt(channel, lfhHeaderBuf, entry.localHeaderOffset)) {
                return RestoreOutcome.Failed("Failed to read Local File Header for '${entry.path}' at ${entry.localHeaderOffset}.")
            }
            lfhHeaderBuf.flip()

            val sig = lfhHeaderBuf.int
            if (sig != LFH_SIG) {
                return RestoreOutcome.Failed(
                    "Corrupted Local File Header signature (0x${Integer.toHexString(sig)}) for '${entry.path}' at offset ${entry.localHeaderOffset}."
                )
            }
            val versionNeeded = lfhHeaderBuf.short.toInt() and 0xFFFF
            val gpBitFlag = lfhHeaderBuf.short.toInt() and 0xFFFF
            val method = lfhHeaderBuf.short.toInt() and 0xFFFF
            val dosTime = lfhHeaderBuf.short.toInt() and 0xFFFF
            val dosDate = lfhHeaderBuf.short.toInt() and 0xFFFF
            lfhHeaderBuf.position(26)
            val lfhNameLen = lfhHeaderBuf.short.toInt() and 0xFFFF
            val lfhExtraLen = lfhHeaderBuf.short.toInt() and 0xFFFF

            val nameOffset = entry.localHeaderOffset + 30L
            val payloadOffset = nameOffset + lfhNameLen + lfhExtraLen
            val payloadEnd = payloadOffset + entry.compressedSize

            if (payloadEnd > currentPhysicalSize) {
                return RestoreOutcome.Failed(
                    "Remaining entry '${entry.path}' payload end ($payloadEnd) exceeds physical archive size ($currentPhysicalSize)."
                )
            }

            val lfhNameBytes = ByteArray(lfhNameLen)
            if (lfhNameLen > 0) {
                val nameBb = ByteBuffer.wrap(lfhNameBytes)
                if (!readFullyAt(channel, nameBb, nameOffset)) {
                    return RestoreOutcome.Failed("Failed to read filename bytes for '${entry.path}'.")
                }
            }

            val needsZip64 = entry.isZip64 ||
                entry.uncompressedSize >= 0xFFFFFFFFL ||
                entry.compressedSize >= 0xFFFFFFFFL ||
                entry.localHeaderOffset >= 0xFFFFFFFFL

            validatedEntries.add(
                ValidatedRemainingEntry(
                    info = entry,
                    lfhVersionNeeded = versionNeeded,
                    lfhGpBitFlag = gpBitFlag,
                    lfhMethod = method,
                    lfhDosTime = dosTime,
                    lfhDosDate = dosDate,
                    lfhNameBytes = lfhNameBytes,
                    payloadOffset = payloadOffset,
                    payloadEnd = payloadEnd,
                    needsZip64 = needsZip64
                )
            )
            prevEndOffset = payloadEnd
        }

        // Determine exact newCentralDirOffset right after the last remaining entry (including any Data Descriptor)
        val lastValidated = validatedEntries.last()
        val lastOrigIdx = remainingIndices.last()
        val newCentralDirOffset: Long = run {
            val nextGapBoundary = if (lastOrigIdx + 1 < allAscending.size) {
                val nextOrigHeaderOffset = allAscending[lastOrigIdx + 1].localHeaderOffset
                val gap = nextOrigHeaderOffset - lastValidated.payloadEnd
                if (gap in 0L..24L && nextOrigHeaderOffset <= currentPhysicalSize) nextOrigHeaderOffset else null
            } else null

            when {
                pendingCkpt != null && pendingCkpt.newCentralDirOffset >= lastValidated.payloadEnd &&
                    pendingCkpt.newCentralDirOffset <= currentPhysicalSize -> {
                    pendingCkpt.newCentralDirOffset
                }
                nextGapBoundary != null -> nextGapBoundary
                (lastValidated.lfhGpBitFlag and 0x0008) != 0 -> {
                    detectDataDescriptorEnd(
                        channel = channel,
                        payloadEnd = lastValidated.payloadEnd,
                        physicalSize = currentPhysicalSize,
                        isZip64Entry = lastValidated.needsZip64
                    )
                }
                else -> lastValidated.payloadEnd
            }
        }

        // Compute exact Central Directory and EOCD/ZIP64 byte lengths
        var centralDirByteSize = 0L
        var anyEntryNeedsZip64 = false
        for (ve in validatedEntries) {
            centralDirByteSize += ve.cdRecordByteLength.toLong()
            if (ve.needsZip64) anyEntryNeedsZip64 = true
        }

        val archiveNeedsZip64 = anyEntryNeedsZip64 ||
            validatedEntries.size >= 0xFFFF ||
            centralDirByteSize >= 0xFFFFFFFFL ||
            newCentralDirOffset >= 0xFFFFFFFFL

        val trailerByteSize = if (archiveNeedsZip64) (56L + 20L + 22L) else 22L
        val expectedFinalArchiveSize = newCentralDirOffset + centralDirByteSize + trailerByteSize

        // Verify sufficient storage if the archive needs to grow beyond currentPhysicalSize
        if (expectedFinalArchiveSize > currentPhysicalSize) {
            val additionalRequired = expectedFinalArchiveSize - currentPhysicalSize
            val archiveFile = archivePath.takeIf { it.isNotEmpty() }?.let { File(it) }
            val freeBytes: Long? = simulatedAvailableDiskBytes
                ?: archiveFile?.parentFile?.usableSpace?.takeIf { it > 0L }
            if (freeBytes != null && freeBytes < additionalRequired + MIN_SAFETY_MARGIN_BYTES) {
                return RestoreOutcome.Failed(
                    "Insufficient storage to write Central Directory metadata (need ${StorageHelper.formatBytes(additionalRequired + MIN_SAFETY_MARGIN_BYTES)}, available ${StorageHelper.formatBytes(freeBytes)})."
                )
            }
        }

        // Save durable restoration checkpoint BEFORE modifying a single byte in the archive
        val ckptSaved = ResumeJournalManager.saveRestorationCheckpoint(
            RestorationCheckpointState(
                stateFilePath = stateFilePath,
                archivePath = archivePath,
                sourceUriString = sourceUriString,
                archiveName = archiveName,
                newCentralDirOffset = newCentralDirOffset,
                expectedFinalArchiveSize = expectedFinalArchiveSize,
                remainingEntryCount = validatedEntries.size,
                phase = RestorationPhase.WRITING_CENTRAL_DIRECTORY
            )
        )
        if (!ckptSaved) {
            return RestoreOutcome.Failed("Failed to persist durable restoration checkpoint before modifying archive.")
        }

        faultInjector.onPoint(
            DestructivePoint.BEFORE_RESTORE_CD_WRITE,
            currentPhysicalSize,
            "newCdOffset=$newCentralDirOffset,entries=${validatedEntries.size}"
        )

        // Stream Central Directory headers + optional ZIP64 EOCD + ZIP64 Locator + EOCD at newCentralDirOffset
        val writeBuf = ByteBuffer.allocate(STREAM_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var writeCursor = newCentralDirOffset
        val midEntryTriggerIndex = (validatedEntries.size / 2).coerceAtLeast(1)

        try {
            for (i in validatedEntries.indices) {
                val ve = validatedEntries[i]
                val recLen = ve.cdRecordByteLength
                if (writeBuf.remaining() < recLen) {
                    writeBuf.flip()
                    writeCursor += writeBufferFullyAt(channel, fd, writeBuf, writeCursor)
                    writeBuf.clear()
                }
                putCentralDirectoryRecord(writeBuf, ve)

                if (i + 1 == midEntryTriggerIndex) {
                    writeBuf.flip()
                    writeCursor += writeBufferFullyAt(channel, fd, writeBuf, writeCursor)
                    writeBuf.clear()
                    faultInjector.onPoint(
                        DestructivePoint.MID_RESTORE_CD_WRITE,
                        writeCursor,
                        "writtenEntries=${i + 1}/${validatedEntries.size}"
                    )
                }
            }

            if (writeBuf.remaining() < trailerByteSize.toInt()) {
                writeBuf.flip()
                writeCursor += writeBufferFullyAt(channel, fd, writeBuf, writeCursor)
                writeBuf.clear()
            }

            val zip64EocdOffset = newCentralDirOffset + centralDirByteSize
            if (archiveNeedsZip64) {
                // 56-byte ZIP64 EOCD Record
                writeBuf.putInt(ZIP64_EOCD_SIG)
                writeBuf.putLong(44L) // Size of remaining record
                writeBuf.putShort(45.toShort()) // Version made by
                writeBuf.putShort(45.toShort()) // Version needed
                writeBuf.putInt(0) // Number of this disk
                writeBuf.putInt(0) // Disk with start of CD
                writeBuf.putLong(validatedEntries.size.toLong())
                writeBuf.putLong(validatedEntries.size.toLong())
                writeBuf.putLong(centralDirByteSize)
                writeBuf.putLong(newCentralDirOffset)

                // 20-byte ZIP64 EOCD Locator
                writeBuf.putInt(ZIP64_LOCATOR_SIG)
                writeBuf.putInt(0) // Disk with start of ZIP64 EOCD
                writeBuf.putLong(zip64EocdOffset)
                writeBuf.putInt(1) // Total number of disks
            }

            // 22-byte standard EOCD Record
            val eocdEntryCount = if (validatedEntries.size >= 0xFFFF) 0xFFFF else validatedEntries.size
            val eocdCdSize = if (centralDirByteSize >= 0xFFFFFFFFL) -1 else centralDirByteSize.toInt()
            val eocdCdOffset = if (newCentralDirOffset >= 0xFFFFFFFFL) -1 else newCentralDirOffset.toInt()

            writeBuf.putInt(EOCD_SIG)
            writeBuf.putShort(0.toShort()) // Disk number
            writeBuf.putShort(0.toShort()) // Disk with CD
            writeBuf.putShort(eocdEntryCount.toShort())
            writeBuf.putShort(eocdEntryCount.toShort())
            writeBuf.putInt(eocdCdSize)
            writeBuf.putInt(eocdCdOffset)
            writeBuf.putShort(0.toShort()) // Comment length

            writeBuf.flip()
            writeCursor += writeBufferFullyAt(channel, fd, writeBuf, writeCursor)
            writeBuf.clear()

            if (writeCursor != expectedFinalArchiveSize) {
                return RestoreOutcome.Failed(
                    "Central Directory write cursor mismatch ($writeCursor != $expectedFinalArchiveSize).",
                    archiveModified = true
                )
            }

            // Truncate any leftover bytes beyond expectedFinalArchiveSize
            truncateChannelToSize(channel, fd, expectedFinalArchiveSize)

            faultInjector.onPoint(
                DestructivePoint.AFTER_RESTORE_CD_WRITE_BEFORE_SYNC,
                expectedFinalArchiveSize,
                "finalSize=$expectedFinalArchiveSize"
            )

            // Flush & sync descriptor before verification
            syncChannelAndDescriptor(channel, fd)

            ResumeJournalManager.saveRestorationCheckpoint(
                RestorationCheckpointState(
                    stateFilePath = stateFilePath,
                    archivePath = archivePath,
                    sourceUriString = sourceUriString,
                    archiveName = archiveName,
                    newCentralDirOffset = newCentralDirOffset,
                    expectedFinalArchiveSize = expectedFinalArchiveSize,
                    remainingEntryCount = validatedEntries.size,
                    phase = RestorationPhase.VERIFYING_RESTORED_ARCHIVE
                )
            )

            // Validate restored ZIP structure and parsed Central Directory entries
            val actualFinalSize = channel.size()
            if (actualFinalSize != expectedFinalArchiveSize) {
                return RestoreOutcome.Failed(
                    "Restored archive size mismatch after truncate (expected $expectedFinalArchiveSize, got $actualFinalSize).",
                    archiveModified = true
                )
            }

            val structuralErr = ArchiveVerifierEngine.validateZipEocdAndZip64Structure(
                channel = channel,
                fileLen = actualFinalSize
            )
            if (structuralErr != null) {
                return RestoreOutcome.Failed(
                    "Restored ZIP failed structural EOCD/ZIP64 validation: $structuralErr",
                    archiveModified = true
                )
            }

            val parsedRestored = ZipParser.parseZipChannel(channel)
            if (parsedRestored.size != validatedEntries.size) {
                return RestoreOutcome.Failed(
                    "Restored ZIP entry count mismatch (expected ${validatedEntries.size}, parsed ${parsedRestored.size}).",
                    archiveModified = true
                )
            }

            for (i in validatedEntries.indices) {
                val expected = validatedEntries[i].info
                val actual = parsedRestored[i]
                if (actual.localHeaderOffset != expected.localHeaderOffset ||
                    actual.compressedSize != expected.compressedSize ||
                    actual.uncompressedSize != expected.uncompressedSize ||
                    actual.crc32 != expected.crc32
                ) {
                    return RestoreOutcome.Failed(
                        "Restored Central Directory metadata mismatch for '${expected.path}'.",
                        archiveModified = true
                    )
                }
            }

            faultInjector.onPoint(
                DestructivePoint.AFTER_RESTORE_VERIFY_BEFORE_CLEANUP,
                actualFinalSize,
                "verifiedEntries=${parsedRestored.size}"
            )

            // Only after full verification succeeds (and descriptor closes cleanly when deferred)
            // do we delete recovery metadata
            if (!deferJournalDeletion) {
                ResumeJournalManager.deleteState(stateFilePath)
            }
            onLog?.invoke(
                "[RESTORE] Restored valid ${if (archiveNeedsZip64) "ZIP64" else "ZIP"} archive in-place (${validatedEntries.size} remaining entries, ${StorageHelper.formatBytes(actualFinalSize)})."
            )

            return RestoreOutcome.RestoredInPlace(
                finalArchiveBytes = actualFinalSize,
                remainingEntryCount = validatedEntries.size,
                isZip64 = archiveNeedsZip64,
                noRewriteNeeded = false
            )
        } catch (fault: SimulatedFaultException) {
            throw fault
        } catch (t: Throwable) {
            return RestoreOutcome.Failed(
                reason = "Failed to finalize remaining archive: ${t.message ?: t.javaClass.simpleName}",
                archiveModified = true
            )
        }
    }

    /**
     * Public Flow entry point for post-crash **Restore Remaining ZIP** (or retrying an interrupted
     * restoration) from a saved [ResumeJournalState].
     */
    fun restoreInterruptedZipArchive(
        context: Context,
        resumeState: ResumeJournalState,
        faultInjector: TruncationFaultInjector = TruncationFaultInjector.NoOp,
        simulatedAvailableDiskBytes: Long? = null
    ): Flow<ExtractionProgress> = flow {
        val startTime = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        val completedCount = resumeState.completedPaths.size
        val targetDir = resumeState.targetDirPath.takeIf { it.isNotEmpty() }?.let { File(it) }
        val resumeTreeUri = resumeState.targetTreeUriString?.takeIf { it.isNotEmpty() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        val resumeFolderDocUri = resumeState.targetFolderDocUriString?.takeIf { it.isNotEmpty() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }

        logs.add("[RESTORE] Validating recovery journal and remaining entry boundaries for ${resumeState.archiveName}…")
        emit(
            ExtractionProgress(
                archiveName = resumeState.archiveName,
                isHatchMode = true,
                isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
                isZip64 = resumeState.remainingEntries.any { it.isZip64 },
                status = ExtractionStatus.FINALIZING,
                totalEntries = resumeState.totalEntries,
                completedEntries = completedCount,
                currentFileName = "Finalizing remaining archive…",
                originalArchiveBytes = resumeState.originalArchiveBytes,
                currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                bytesReclaimed = resumeState.bytesReclaimedSoFar,
                journalLogs = logs.toList(),
                outputDirectory = resumeState.targetDirPath,
                startTime = startTime,
                elapsedTimeMs = 0L,
                isPauseSupported = false
            )
        )

        val fullIndex = ResumeJournalManager.loadInitialIndex(resumeState.stateFilePath)
        val allEntries = fullIndex ?: resumeState.remainingEntries

        val archiveFile = resumeState.archivePath.takeIf { it.isNotEmpty() }?.let { File(it) }
        val outcome: RestoreOutcome = when {
            archiveFile != null -> {
                if (!archiveFile.exists()) {
                    RestoreOutcome.Failed("Source archive file is missing on disk: ${archiveFile.absolutePath}")
                } else if (!archiveFile.canWrite()) {
                    RestoreOutcome.Failed("Source archive file is read-only: ${archiveFile.name}")
                } else {
                    try {
                        RandomAccessFile(archiveFile, "rw").use { raf ->
                            restoreRemainingZipOnChannel(
                                channel = raf.channel,
                                fd = raf.fd,
                                stateFilePath = resumeState.stateFilePath,
                                archivePath = resumeState.archivePath,
                                sourceUriString = resumeState.sourceUriString,
                                archiveName = resumeState.archiveName,
                                targetDir = targetDir,
                                originalArchiveBytes = resumeState.originalArchiveBytes,
                                bytesReclaimedSoFar = resumeState.bytesReclaimedSoFar,
                                allEntries = allEntries,
                                completedPathsInput = resumeState.completedPaths,
                                verifiedEntryCrcsInput = resumeState.verifiedEntryCrcs,
                                context = context,
                                targetTreeUri = resumeTreeUri,
                                targetFolderDocUri = resumeFolderDocUri,
                                faultInjector = faultInjector,
                                simulatedAvailableDiskBytes = simulatedAvailableDiskBytes,
                                onLog = { logs.add(it) },
                                deferJournalDeletion = true
                            )
                        }
                    } catch (fault: SimulatedFaultException) {
                        throw fault
                    } catch (e: Throwable) {
                        RestoreOutcome.Failed("Cannot open archive for restoration: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            !resumeState.sourceUriString.isNullOrEmpty() -> {
                val uri = try {
                    Uri.parse(resumeState.sourceUriString)
                } catch (_: Throwable) {
                    null
                }
                if (uri == null) {
                    RestoreOutcome.Failed("Source SAF URI is malformed.")
                } else {
                    val pfd = try {
                        context.contentResolver.openFileDescriptor(uri, "rw")
                    } catch (e: Throwable) {
                        null
                    }
                    if (pfd == null) {
                        RestoreOutcome.Failed("Cannot open SAF archive descriptor in read-write mode.")
                    } else {
                        try {
                            pfd.use { descriptor ->
                                FileInputStream(descriptor.fileDescriptor).use { fis ->
                                    restoreRemainingZipOnChannel(
                                        channel = fis.channel,
                                        fd = descriptor.fileDescriptor,
                                        stateFilePath = resumeState.stateFilePath,
                                        archivePath = "",
                                        sourceUriString = resumeState.sourceUriString,
                                        archiveName = resumeState.archiveName,
                                        targetDir = targetDir,
                                        originalArchiveBytes = resumeState.originalArchiveBytes,
                                        bytesReclaimedSoFar = resumeState.bytesReclaimedSoFar,
                                        allEntries = allEntries,
                                        completedPathsInput = resumeState.completedPaths,
                                        verifiedEntryCrcsInput = resumeState.verifiedEntryCrcs,
                                        context = context,
                                        targetTreeUri = resumeTreeUri,
                                        targetFolderDocUri = resumeFolderDocUri,
                                        faultInjector = faultInjector,
                                        simulatedAvailableDiskBytes = simulatedAvailableDiskBytes,
                                        onLog = { logs.add(it) },
                                        deferJournalDeletion = true
                                    )
                                }
                            }
                        } catch (fault: SimulatedFaultException) {
                            throw fault
                        } catch (e: Throwable) {
                            RestoreOutcome.Failed("SAF archive restoration failed: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                }
            }
            else -> RestoreOutcome.Failed("No valid local path or SAF URI recorded in recovery journal.")
        }

        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
        when (outcome) {
            is RestoreOutcome.RestoredInPlace -> {
                // Descriptor has closed cleanly without IOException/EIO; safe to remove journal now.
                ResumeJournalManager.deleteState(resumeState.stateFilePath)
                val reclaimed = (resumeState.originalArchiveBytes - outcome.finalArchiveBytes).coerceAtLeast(0L)
                emit(
                    ExtractionProgress(
                        archiveName = resumeState.archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
                        isZip64 = outcome.isZip64,
                        status = ExtractionStatus.CANCELLED,
                        totalEntries = resumeState.totalEntries,
                        completedEntries = (resumeState.totalEntries - outcome.remainingEntryCount).coerceAtLeast(0),
                        currentFileName = "Restored remaining archive (${outcome.remainingEntryCount} entries intact)",
                        originalArchiveBytes = resumeState.originalArchiveBytes,
                        currentArchiveBytesOnDisk = outcome.finalArchiveBytes,
                        bytesReclaimed = reclaimed,
                        journalLogs = logs.toList(),
                        outputDirectory = resumeState.targetDirPath,
                        startTime = startTime,
                        elapsedTimeMs = elapsed,
                        isPauseSupported = false
                    )
                )
            }
            is RestoreOutcome.Failed -> {
                logs.add("[ERROR] ${outcome.reason}")
                emit(
                    ExtractionProgress(
                        archiveName = resumeState.archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
                        status = ExtractionStatus.ERROR,
                        totalEntries = resumeState.totalEntries,
                        completedEntries = completedCount,
                        currentFileName = "Restoration failed",
                        originalArchiveBytes = resumeState.originalArchiveBytes,
                        currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                        bytesReclaimed = resumeState.bytesReclaimedSoFar,
                        journalLogs = logs.toList(),
                        errorMessage = outcome.reason,
                        outputDirectory = resumeState.targetDirPath,
                        startTime = startTime,
                        elapsedTimeMs = elapsed,
                        isPauseSupported = false
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun putCentralDirectoryRecord(buf: ByteBuffer, ve: ValidatedRemainingEntry) {
        val entry = ve.info
        val versionMadeBy = if (ve.needsZip64) 45 else 20
        val versionNeeded = if (ve.needsZip64) 45 else ve.lfhVersionNeeded.coerceAtLeast(20)
        // Preserve original UTF-8 / encryption GP flags; clear bit 3 (Data Descriptor) in CD since sizes & CRC are populated
        val gpFlags = (ve.lfhGpBitFlag and 0xFFF7)
        val compSizeField = if (ve.needsZip64) -1 else entry.compressedSize.toInt()
        val uncompSizeField = if (ve.needsZip64) -1 else entry.uncompressedSize.toInt()
        val offsetField = if (ve.needsZip64) -1 else entry.localHeaderOffset.toInt()
        val externalAttr = if (entry.isDirectory) 0x10 else 0

        buf.putInt(CD_SIG)
        buf.putShort(versionMadeBy.toShort())
        buf.putShort(versionNeeded.toShort())
        buf.putShort(gpFlags.toShort())
        buf.putShort(entry.compressionMethod.toShort())
        buf.putShort(ve.lfhDosTime.toShort())
        buf.putShort(ve.lfhDosDate.toShort())
        buf.putInt(entry.crc32.toInt())
        buf.putInt(compSizeField)
        buf.putInt(uncompSizeField)
        buf.putShort(ve.lfhNameBytes.size.toShort())
        buf.putShort(ve.cdExtraLen.toShort())
        buf.putShort(0.toShort()) // file comment length
        buf.putShort(0.toShort()) // disk number start
        buf.putShort(0.toShort()) // internal file attributes
        buf.putInt(externalAttr)
        buf.putInt(offsetField)
        buf.put(ve.lfhNameBytes)

        if (ve.needsZip64) {
            buf.putShort(0x0001.toShort()) // ZIP64 Extra Field Header ID
            buf.putShort(24.toShort())     // Data Size (3 * 8 bytes)
            buf.putLong(entry.uncompressedSize)
            buf.putLong(entry.compressedSize)
            buf.putLong(entry.localHeaderOffset)
        }
    }

    private fun detectDataDescriptorEnd(
        channel: FileChannel,
        payloadEnd: Long,
        physicalSize: Long,
        isZip64Entry: Boolean
    ): Long {
        if (payloadEnd + 12L > physicalSize) return payloadEnd
        val probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        if (!readFullyAt(channel, probe, payloadEnd)) return payloadEnd
        probe.flip()
        val sig = probe.int
        return if (sig == DATA_DESCRIPTOR_SIG) {
            val ddLen = if (isZip64Entry) 24L else 16L
            if (payloadEnd + ddLen <= physicalSize) payloadEnd + ddLen else payloadEnd
        } else {
            val ddLen = if (isZip64Entry) 20L else 12L
            if (payloadEnd + ddLen <= physicalSize) payloadEnd + ddLen else payloadEnd
        }
    }

    private fun tryCompleteInterruptedStoredEntry(
        channel: FileChannel,
        fd: FileDescriptor?,
        entry: ZipEntryInfo,
        destFile: File,
        currentPhysicalSize: Long
    ): Long? {
        return try {
            val lfhBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            if (!readFullyAt(channel, lfhBuf, entry.localHeaderOffset)) return null
            lfhBuf.flip()
            if (lfhBuf.int != LFH_SIG) return null
            lfhBuf.position(26)
            val nameLen = lfhBuf.short.toInt() and 0xFFFF
            val extraLen = lfhBuf.short.toInt() and 0xFFFF
            val payloadOffset = entry.localHeaderOffset + 30L + nameLen + extraLen
            val remainingSliceEnd = currentPhysicalSize - payloadOffset
            if (remainingSliceEnd <= 0L || remainingSliceEnd > entry.uncompressedSize) return null

            RandomAccessFile(destFile, "rw").use { outRaf ->
                val outCh = outRaf.channel
                val chunkBuf = ByteBuffer.allocate(256 * 1024)
                var sliceEnd = remainingSliceEnd
                while (sliceEnd > 0L) {
                    val step = minOf(4L * 1024L * 1024L, sliceEnd)
                    val sliceStart = sliceEnd - step
                    var pos = sliceStart
                    while (pos < sliceEnd) {
                        val toCopy = minOf(chunkBuf.capacity().toLong(), sliceEnd - pos).toInt()
                        chunkBuf.clear()
                        chunkBuf.limit(toCopy)
                        if (!readFullyAt(channel, chunkBuf, payloadOffset + pos)) return null
                        chunkBuf.flip()
                        writeBufferFullyAt(outCh, outRaf.fd, chunkBuf, pos)
                        pos += toCopy
                    }
                    outCh.force(false)
                    truncateChannelToSize(channel, fd, payloadOffset + sliceStart)
                    sliceEnd = sliceStart
                }
                outCh.force(true)
                try {
                    outRaf.fd.sync()
                } catch (_: Throwable) {
                }
            }

            if (!ResumeJournalManager.verifyOutputFileIntegrity(destFile, entry.uncompressedSize, entry.crc32)) {
                return null
            }

            truncateChannelToSize(channel, fd, entry.localHeaderOffset)
            syncChannelAndDescriptor(channel, fd)
            entry.localHeaderOffset
        } catch (_: Throwable) {
            null
        }
    }

    private fun readFullyAt(channel: FileChannel, dst: ByteBuffer, offset: Long): Boolean {
        var pos = offset
        while (dst.hasRemaining()) {
            val r = try {
                channel.read(dst, pos)
            } catch (_: Throwable) {
                synchronized(channel) {
                    channel.position(pos)
                    channel.read(dst)
                }
            }
            if (r <= 0) return false
            pos += r
        }
        return true
    }

    private fun writeBufferFullyAt(
        channel: FileChannel,
        fd: FileDescriptor?,
        src: ByteBuffer,
        offset: Long
    ): Int {
        val totalBytes = src.remaining()
        if (totalBytes <= 0) return 0

        // Fast path: direct writable FileChannel (RandomAccessFile or RW channel)
        val startPos = src.position()
        try {
            var pos = offset
            while (src.hasRemaining()) {
                val w = channel.write(src, pos)
                if (w <= 0) break
                pos += w
            }
            if (!src.hasRemaining()) return totalBytes
        } catch (_: Throwable) {
            src.position(startPos)
        }

        // Fallback for read-only FileInputStream channels backed by an 'rw' ParcelFileDescriptor
        if (fd != null) {
            val bytes = ByteArray(totalBytes)
            src.position(startPos)
            src.get(bytes)

            try {
                var rem = bytes.size
                var curOff = 0
                var curPos = offset
                while (rem > 0) {
                    val w = Os.pwrite(fd, bytes, curOff, rem, curPos)
                    if (w <= 0) break
                    curOff += w
                    rem -= w
                    curPos += w
                }
                if (rem == 0) return totalBytes
            } catch (_: Throwable) {
            }

            try {
                val outCh = FileOutputStream(fd).channel
                val bb = ByteBuffer.wrap(bytes)
                var pos = offset
                while (bb.hasRemaining()) {
                    val w = outCh.write(bb, pos)
                    if (w <= 0) break
                    pos += w
                }
                if (!bb.hasRemaining()) return totalBytes
            } catch (_: Throwable) {
            }
        }

        throw IOException("Failed to write $totalBytes bytes to archive channel at offset $offset")
    }

    private fun truncateChannelToSize(channel: FileChannel, fd: FileDescriptor?, targetSize: Long) {
        val safeTarget = targetSize.coerceAtLeast(0L)
        try {
            channel.truncate(safeTarget)
            return
        } catch (_: Throwable) {
        }
        if (fd != null) {
            var truncated = false
            try {
                Os.ftruncate(fd, safeTarget)
                truncated = true
            } catch (_: Throwable) {
            }
            if (!truncated || try { channel.size() > safeTarget } catch (_: Throwable) { false }) {
                try {
                    FileOutputStream(fd).channel.truncate(safeTarget)
                    truncated = true
                } catch (_: Throwable) {
                }
            }
            if (truncated) return
        }
        throw IOException("Failed to truncate archive channel to $safeTarget bytes")
    }

    private fun syncChannelAndDescriptor(channel: FileChannel, fd: FileDescriptor?) {
        if (!StorageHelper.syncFileDescriptorStrict(fd, channel)) {
            throw IOException("Failed to durably fsync archive descriptor during in-place ZIP restoration")
        }
    }
}
