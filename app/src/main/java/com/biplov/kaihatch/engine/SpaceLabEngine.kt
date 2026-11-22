package com.biplov.kaihatch.engine

import android.content.Context
import android.net.Uri
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ArchiveSummary
import com.biplov.kaihatch.model.PlanModeVerdict
import com.biplov.kaihatch.model.PreExtractionStoragePlan
import com.biplov.kaihatch.model.StoragePlannerRecommendation
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SpaceLabEngine {

    /**
     * 16 MB sliding window used by [CommonsCompressBufferStrategy] and [KaiHatchEngine]
     * when streaming and truncating large archive entries (>16 MB) in-place.
     */
    const val SUB_ENTRY_TRUNCATE_CHUNK_BYTES = CommonsCompressBufferStrategy.SUB_ENTRY_WINDOW_BYTES

    /**
     * 4 MB threshold matching [KaiHatchEngine] for sub-entry progressive chunk truncation.
     */
    const val SUB_ENTRY_THRESHOLD_BYTES = 4L * 1024L * 1024L

    const val MIN_SAFETY_RESERVE_BYTES = 16L * 1024L * 1024L
    const val MAX_SAFETY_RESERVE_BYTES = 64L * 1024L * 1024L

    internal fun safeAdd(a: Long, b: Long): Long {
        val left = a.coerceAtLeast(0L)
        val right = b.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }

    fun computeDefaultSafetyReserveBytes(extractedSizeBytes: Long): Long {
        if (extractedSizeBytes <= 0L) return MIN_SAFETY_RESERVE_BYTES
        val fivePercent = extractedSizeBytes / 20L
        return fivePercent.coerceIn(MIN_SAFETY_RESERVE_BYTES, MAX_SAFETY_RESERVE_BYTES)
    }

    suspend fun generateTestArchive(
        outputDir: File,
        sizeMb: Int
    ): File = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val archiveFile = File(outputDir, "kaihatch_sample_${sizeMb}mb.zip")
        if (archiveFile.exists()) {
            archiveFile.delete()
        }

        val zipOut = ZipOutputStream(
            CommonsCompressBufferStrategy.wrapBufferedOutput(
                FileOutputStream(archiveFile),
                sizeMb.toLong() * 1024L * 1024L
            )
        )
        zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)

        val random = Random(42)

        try {
            // 1. Text & Config logs
            val logEntry = ZipEntry("system/crash_dump.log")
            zipOut.putNextEntry(logEntry)
            val logLines = StringBuilder()
            val textRepeat = sizeMb * 250
            for (i in 0 until textRepeat) {
                logLines.append("2026-10-24 11:42:${i % 60} [INFO] [KaiHatchEngine] Task $i completed frame processing in 14ms. CRC check valid. Disk write synced.\n")
            }
            val logBytes = logLines.toString().toByteArray(Charsets.UTF_8)
            zipOut.write(logBytes)
            zipOut.closeEntry()

            // 2. High-res Texture / Raw RGB
            val textureEntry = ZipEntry("assets/textures/albedo_map.raw")
            zipOut.putNextEntry(textureEntry)
            val textureChunk = ByteArray(64 * 1024)
            for (i in textureChunk.indices) {
                textureChunk[i] = ((i % 256) xor 0x5A).toByte()
            }
            val textureChunksCount = (sizeMb * 6).coerceAtLeast(4)
            for (c in 0 until textureChunksCount) {
                zipOut.write(textureChunk)
            }
            zipOut.closeEntry()

            // 3. Audio PCM waveforms
            val audioEntry = ZipEntry("assets/audio/synth_ambient.wav")
            zipOut.putNextEntry(audioEntry)
            val audioBuf = ByteArray(32 * 1024)
            for (i in audioBuf.indices) {
                audioBuf[i] = (Math.sin(i.toDouble() / 10.0) * 120.0).toInt().toByte()
            }
            val audioChunksCount = (sizeMb * 4).coerceAtLeast(3)
            for (c in 0 until audioChunksCount) {
                zipOut.write(audioBuf)
            }
            zipOut.closeEntry()

            // 4. JSON metadata database
            val jsonEntry = ZipEntry("data/metadata_index.json")
            zipOut.putNextEntry(jsonEntry)
            val jsonContent = buildString {
                append("{\n  \"package\": \"KaiHatch Test Bundle\",\n  \"sizeMb\": $sizeMb,\n  \"records\": [\n")
                val recordCount = sizeMb * 300
                for (r in 0 until recordCount) {
                    append("    {\"id\": $r, \"uuid\": \"kai-${1000 + r}\", \"active\": ${r % 2 == 0}, \"entropy\": ${random.nextDouble()}}")
                    if (r < recordCount - 1) append(",")
                    append("\n")
                }
                append("  ]\n}")
            }
            zipOut.write(jsonContent.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 5. Binary firmware / chunk
            val binEntry = ZipEntry("bin/payload_firmware.bin")
            zipOut.putNextEntry(binEntry)
            val binBuf = ByteArray(48 * 1024)
            random.nextBytes(binBuf)
            val binChunks = (sizeMb * 3).coerceAtLeast(2)
            for (b in 0 until binChunks) {
                zipOut.write(binBuf)
            }
            zipOut.closeEntry()

        } finally {
            zipOut.finish()
            zipOut.close()
        }

        archiveFile
    }

    /**
     * Computes accurate Peak Disk Telemetry by simulating the reverse-offset truncation timeline
     * from the end of the archive to offset 0, incorporating [CommonsCompressBufferStrategy]'s
     * 16 MB sliding window (`SUB_ENTRY_TRUNCATE_CHUNK_BYTES`) for large entries (both `STORED`
     * and `DEFLATED` / `LZMA2` streams in 2GB+ APKs and 6GB+ .7z archives).
     */
    fun computeHatchTimelinePeakBytes(
        fileSizeBytes: Long,
        uncompressedSizeBytes: Long,
        entries: List<ZipEntryInfo>,
        format: ArchiveFormat
    ): Long {
        if (entries.isEmpty()) return fileSizeBytes
        val standardPeak = safeAdd(fileSizeBytes, uncompressedSizeBytes)

        // Pure sequential GZIP streams cannot be reverse-truncated in-place
        if (format == ArchiveFormat.TAR_GZ) {
            return standardPeak
        }

        val reverseEntries = entries
            .filter { !it.isDirectory }
            .sortedByDescending { it.localHeaderOffset }

        if (reverseEntries.isEmpty()) return fileSizeBytes

        val totalRawWeight = reverseEntries.sumOf {
            val base = if (it.compressedSize > 0L) it.compressedSize else it.uncompressedSize
            (base.coerceAtLeast(1L) + 30L + it.path.length.toLong())
        }.coerceAtLeast(1L)

        // Check if entries have distinct, strictly monotonic offsets within the physical file
        val hasValidPhysicalOffsets = reverseEntries.size == 1 ||
                (reverseEntries.first().localHeaderOffset in 1L until fileSizeBytes &&
                        reverseEntries.first().localHeaderOffset > reverseEntries.last().localHeaderOffset)

        var archiveBytesRemaining = fileSizeBytes
        var extractedSoFar = 0L
        var peakDiskBytes = maxOf(fileSizeBytes, uncompressedSizeBytes)

        for ((idx, entry) in reverseEntries.withIndex()) {
            val entryWeight = (if (entry.compressedSize > 0L) entry.compressedSize else entry.uncompressedSize)
                .coerceAtLeast(1L) + 30L + entry.path.length.toLong()
            val proportionalFootprint = ((fileSizeBytes.toDouble() * entryWeight.toDouble()) / totalRawWeight.toDouble())
                .toLong()
                .coerceIn(0L, archiveBytesRemaining)

            val candidateTargetAfterTruncate = if (idx == reverseEntries.lastIndex) {
                0L
            } else if (hasValidPhysicalOffsets && entry.localHeaderOffset in 0L..archiveBytesRemaining) {
                minOf(entry.localHeaderOffset, (archiveBytesRemaining - proportionalFootprint).coerceAtLeast(0L))
            } else {
                (archiveBytesRemaining - proportionalFootprint).coerceAtLeast(0L)
            }

            val entryArchiveSpan = (archiveBytesRemaining - candidateTargetAfterTruncate).coerceAtLeast(0L)
            val entryUncomp = entry.uncompressedSize.coerceAtLeast(0L)

            val isChunkStreamedLargeEntry = !entry.isEncrypted &&
                    entryUncomp > SUB_ENTRY_TRUNCATE_CHUNK_BYTES

            val stepPeak = if (isChunkStreamedLargeEntry) {
                // During sub-entry progressive truncation (8 MB / 16 MB slices), as each slice of
                // `entryUncomp` is written to disk, the corresponding slice of `entryArchiveSpan` is
                // immediately ftruncated from the archive tail. Thus peak occurs either at the very first
                // slice or at the very last slice of the entry, plus at most 1 active chunk window.
                val activeChunk = SUB_ENTRY_TRUNCATE_CHUNK_BYTES.coerceAtMost(entryUncomp)
                val firstSlicePeak = safeAdd(safeAdd(archiveBytesRemaining, extractedSoFar), activeChunk)
                val lastSlicePeak = safeAdd(
                    safeAdd(candidateTargetAfterTruncate, extractedSoFar),
                    safeAdd(entryUncomp, activeChunk.coerceAtMost(entryArchiveSpan))
                )
                maxOf(firstSlicePeak, lastSlicePeak)
            } else {
                // Small/medium entry: extracted in full before its archive span is truncated
                safeAdd(safeAdd(archiveBytesRemaining, extractedSoFar), entryUncomp)
            }

            if (stepPeak > peakDiskBytes) {
                peakDiskBytes = stepPeak
            }

            extractedSoFar = safeAdd(extractedSoFar, entryUncomp)
            archiveBytesRemaining = candidateTargetAfterTruncate
            val afterTruncateDisk = safeAdd(archiveBytesRemaining, extractedSoFar)
            if (afterTruncateDisk > peakDiskBytes) {
                peakDiskBytes = afterTruncateDisk
            }
        }

        val minPhysicalFloor = maxOf(fileSizeBytes, uncompressedSizeBytes)
        return peakDiskBytes.coerceIn(minPhysicalFloor, standardPeak)
    }

    /**
     * Computes a comprehensive, lightweight [PreExtractionStoragePlan] for both Standard Mode and
     * Hatch Mode before extraction begins.
     *
     * Key guarantees:
     * - Distinguishes **Peak Additional Storage** (`peakTotalFootprint - archiveSizeBytes`) from
     *   total footprint so the existing archive size is never double-counted against free space.
     * - Models `STORED` vs. `DEFLATED` ZIPs accurately:
     *   * Large `STORED` entries on direct POSIX destinations use sub-entry reverse slice truncation
     *     (`1..8 MB` slice working window).
     *   * Large `STORED` entries on non-POSIX SAF destinations retain the source archive until the
     *     full entry is written and verified (`KaiHatchEngine` Phase 2 safety rule).
     *   * Large `DEFLATE` entries account for decompress-first tail-swap (`entryUncomp` + `chunk0`
     *     temporary working file `1..32 MB`) before entry truncation.
     * - Never guarantees sufficient space when SAF storage capacity is unknown (`isDestinationCapacityReliable == false`
     *   or `destinationFreeBytes == null`).
     * - Avoids sorting/allocating intermediate lists when entries are already in ascending offset order
     *   (typical for 10,000+ entry ZIP/ZIP64 Central Directories).
     */
    fun computePreExtractionStoragePlan(
        archiveSizeBytes: Long,
        entries: List<ZipEntryInfo>,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        destinationFreeBytes: Long? = null,
        isDestinationCapacityReliable: Boolean = destinationFreeBytes != null && destinationFreeBytes > 0L,
        isDirectPosixDestination: Boolean = true,
        selectedEntryPaths: Set<String>? = null,
        hasEncryptedEntries: Boolean = false,
        safetyReserveBytes: Long? = null
    ): PreExtractionStoragePlan {
        val safeArchiveSize = archiveSizeBytes.coerceAtLeast(0L)

        // Single-pass inspection of file entries to avoid intermediate list allocations on large ZIP64 archives
        var totalFileEntryCount = 0
        var selectedFileEntryCount = 0
        var extractedSizeBytes = 0L
        var hasCorruptOrNegativeMetadata = safeArchiveSize <= 0L
        var anyEncrypted = hasEncryptedEntries

        val hasSelectionFilter = !selectedEntryPaths.isNullOrEmpty()
        for (i in entries.indices) {
            val e = entries[i]
            if (e.isDirectory) continue
            totalFileEntryCount++
            if (hasSelectionFilter && e.path !in selectedEntryPaths!!) continue
            selectedFileEntryCount++
            if (e.isEncrypted) anyEncrypted = true
            if (e.uncompressedSize < 0L || e.compressedSize < 0L || e.localHeaderOffset < 0L) {
                hasCorruptOrNegativeMetadata = true
            }
            if (format != ArchiveFormat.SEVEN_Z && safeArchiveSize > 0L && e.localHeaderOffset >= safeArchiveSize) {
                hasCorruptOrNegativeMetadata = true
            }
            extractedSizeBytes = safeAdd(extractedSizeBytes, e.uncompressedSize.coerceAtLeast(0L))
        }

        val isPartialSelection = hasSelectionFilter && selectedFileEntryCount < totalFileEntryCount
        val effectiveReserve = (safetyReserveBytes ?: computeDefaultSafetyReserveBytes(extractedSizeBytes)).coerceAtLeast(0L)

        val standardTempWorkingBytes = if (extractedSizeBytes <= 0L) {
            0L
        } else {
            (extractedSizeBytes / 64L).coerceIn(256L * 1024L, 8L * 1024L * 1024L)
        }
        val standardPeakAdditionalBytes = safeAdd(extractedSizeBytes, standardTempWorkingBytes)
        val standardRequiredWithReserveBytes = safeAdd(standardPeakAdditionalBytes, effectiveReserve)
        val standardTotalPeakBytes = safeAdd(safeArchiveSize, standardPeakAdditionalBytes)

        // Build target file list (only filtering if directories or partial selection exist)
        val targetFiles: List<ZipEntryInfo> = if (!hasSelectionFilter && totalFileEntryCount == entries.size) {
            entries
        } else {
            val filtered = ArrayList<ZipEntryInfo>(selectedFileEntryCount.coerceAtLeast(0))
            for (i in entries.indices) {
                val e = entries[i]
                if (!e.isDirectory && (!hasSelectionFilter || e.path in selectedEntryPaths!!)) {
                    filtered.add(e)
                }
            }
            filtered
        }

        // Check if targetFiles is already sorted ascending by localHeaderOffset (O(N), zero allocation)
        var isAscendingOffsets = true
        var isStrictlyIncreasingOffsets = targetFiles.size <= 1
        if (targetFiles.size > 1) {
            var firstOff = targetFiles.first().localHeaderOffset
            var lastOff = targetFiles.last().localHeaderOffset
            for (i in 1 until targetFiles.size) {
                val prev = targetFiles[i - 1].localHeaderOffset
                val curr = targetFiles[i].localHeaderOffset
                if (curr < prev) {
                    isAscendingOffsets = false
                    break
                }
            }
            if (isAscendingOffsets) {
                isStrictlyIncreasingOffsets = lastOff in 1L until safeArchiveSize && lastOff > firstOff
            }
        }

        val orderedAscOrDesc: List<ZipEntryInfo>
        val readBackwardsFromAsc: Boolean
        if (isAscendingOffsets) {
            orderedAscOrDesc = targetFiles
            readBackwardsFromAsc = true
        } else {
            orderedAscOrDesc = targetFiles.sortedByDescending { it.localHeaderOffset }
            readBackwardsFromAsc = false
            if (orderedAscOrDesc.size > 1) {
                val highest = orderedAscOrDesc.first().localHeaderOffset
                val lowest = orderedAscOrDesc.last().localHeaderOffset
                isStrictlyIncreasingOffsets = highest in 1L until safeArchiveSize && highest > lowest
            }
        }

        val isSolidSevenZ = format == ArchiveFormat.SEVEN_Z && targetFiles.size > 1 && !isStrictlyIncreasingOffsets
        val isHatchModeSupported = when {
            targetFiles.isEmpty() -> false
            format == ArchiveFormat.TAR_GZ -> false
            format == ArchiveFormat.RAR -> false
            isPartialSelection -> false
            format == ArchiveFormat.SEVEN_Z && anyEncrypted -> false
            isSolidSevenZ -> false
            else -> true
        }

        val isEstimateReliable = targetFiles.isNotEmpty() &&
            extractedSizeBytes > 0L &&
            !hasCorruptOrNegativeMetadata &&
            (format != ArchiveFormat.SEVEN_Z || isStrictlyIncreasingOffsets || targetFiles.size == 1)

        var hatchTempWorkingBytes = standardTempWorkingBytes
        var hatchTotalPeakBytes = standardTotalPeakBytes
        var hatchPeakAdditionalBytes = standardPeakAdditionalBytes

        if (isHatchModeSupported && targetFiles.isNotEmpty()) {
            val journalOverheadBytes = (targetFiles.size.toLong() * 128L)
                .coerceIn(64L * 1024L, 16L * 1024L * 1024L)

            var totalRawWeight = 0L
            for (i in targetFiles.indices) {
                val it = targetFiles[i]
                val base = if (it.compressedSize > 0L) it.compressedSize else it.uncompressedSize
                totalRawWeight = safeAdd(totalRawWeight, base.coerceAtLeast(1L) + 30L + it.path.length.toLong())
            }
            totalRawWeight = totalRawWeight.coerceAtLeast(1L)

            var archiveBytesRemaining = safeArchiveSize
            var extractedSoFar = 0L
            var peakDiskBytes = maxOf(safeArchiveSize, extractedSizeBytes)
            var maxTempWorking = journalOverheadBytes

            val count = orderedAscOrDesc.size
            for (stepIdx in 0 until count) {
                val entry = if (readBackwardsFromAsc) {
                    orderedAscOrDesc[count - 1 - stepIdx]
                } else {
                    orderedAscOrDesc[stepIdx]
                }
                val isLastRemainingEntry = (stepIdx == count - 1)
                val entryWeight = (if (entry.compressedSize > 0L) entry.compressedSize else entry.uncompressedSize)
                    .coerceAtLeast(1L) + 30L + entry.path.length.toLong()
                val proportionalFootprint = ((safeArchiveSize.toDouble() * entryWeight.toDouble()) / totalRawWeight.toDouble())
                    .toLong()
                    .coerceIn(0L, archiveBytesRemaining)

                val candidateTargetAfterTruncate = if (isLastRemainingEntry) {
                    0L
                } else if (isStrictlyIncreasingOffsets && entry.localHeaderOffset in 0L..archiveBytesRemaining) {
                    minOf(entry.localHeaderOffset, (archiveBytesRemaining - proportionalFootprint).coerceAtLeast(0L))
                } else {
                    (archiveBytesRemaining - proportionalFootprint).coerceAtLeast(0L)
                }

                val entryArchiveSpan = (archiveBytesRemaining - candidateTargetAfterTruncate).coerceAtLeast(0L)
                val entryUncomp = entry.uncompressedSize.coerceAtLeast(0L)
                val entryComp = entry.compressedSize.coerceAtLeast(0L)
                val isLargeEntry = !entry.isEncrypted &&
                    (entryUncomp >= SUB_ENTRY_THRESHOLD_BYTES || entryComp >= SUB_ENTRY_THRESHOLD_BYTES)
                val isStoredMethod = (entry.compressionMethod == 0 || entry.compressionMethod == 100) &&
                    format != ArchiveFormat.SEVEN_Z
                val isDeflateMethod = entry.compressionMethod == 8 &&
                    format != ArchiveFormat.SEVEN_Z

                val stepPeak: Long
                val entryWorkingBytes: Long

                if (isLargeEntry && isStoredMethod) {
                    if (isDirectPosixDestination) {
                        // Direct POSIX STORED: sub-entry reverse slice truncation (1..8 MB window)
                        val sliceStep = (entryUncomp / 8L).coerceIn(1024L * 1024L, 8L * 1024L * 1024L)
                        entryWorkingBytes = safeAdd(sliceStep, journalOverheadBytes)
                        val firstSlicePeak = safeAdd(safeAdd(archiveBytesRemaining, extractedSoFar), entryWorkingBytes)
                        val lastSlicePeak = safeAdd(
                            safeAdd(candidateTargetAfterTruncate, extractedSoFar),
                            safeAdd(entryUncomp, safeAdd(sliceStep.coerceAtMost(entryArchiveSpan), journalOverheadBytes))
                        )
                        stepPeak = maxOf(firstSlicePeak, lastSlicePeak)
                    } else {
                        // SAF STORED: intermediate slice truncation is disabled; full entry is written before truncation
                        val safStreamBuf = 2L * 1024L * 1024L
                        entryWorkingBytes = safeAdd(safStreamBuf, journalOverheadBytes)
                        stepPeak = safeAdd(
                            safeAdd(archiveBytesRemaining, extractedSoFar),
                            safeAdd(entryUncomp, entryWorkingBytes)
                        )
                    }
                } else if (isLargeEntry && isDeflateMethod) {
                    // Large DEFLATE: Decompress-First Tail-Swap writes chunks 1..N + chunk0 temp file (1..32 MB)
                    // before truncating the entry's compressed span from the source archive.
                    val chunk0Bytes = (entryUncomp / 8L).coerceIn(1024L * 1024L, 32L * 1024L * 1024L)
                    entryWorkingBytes = safeAdd(chunk0Bytes, journalOverheadBytes)
                    stepPeak = if (isDirectPosixDestination && isLastRemainingEntry) {
                        // Final entry at offset 0 on POSIX holds chunks 1..N in destFile and chunk0 in temp
                        // (total = entryUncomp + journalOverheadBytes) before truncating archive to chunk0Bytes.
                        safeAdd(
                            safeAdd(archiveBytesRemaining, extractedSoFar),
                            safeAdd(entryUncomp, journalOverheadBytes)
                        )
                    } else {
                        safeAdd(
                            safeAdd(archiveBytesRemaining, extractedSoFar),
                            safeAdd(entryUncomp, entryWorkingBytes)
                        )
                    }
                } else {
                    // Small entry (< 4 MB), encrypted entry, or 7z entry: extracted in full before truncation
                    val streamBuf = (entryUncomp / 16L).coerceIn(256L * 1024L, 4L * 1024L * 1024L)
                    entryWorkingBytes = safeAdd(streamBuf, journalOverheadBytes)
                    stepPeak = safeAdd(
                        safeAdd(archiveBytesRemaining, extractedSoFar),
                        safeAdd(entryUncomp, entryWorkingBytes)
                    )
                }

                if (entryWorkingBytes > maxTempWorking) {
                    maxTempWorking = entryWorkingBytes
                }
                if (stepPeak > peakDiskBytes) {
                    peakDiskBytes = stepPeak
                }

                extractedSoFar = safeAdd(extractedSoFar, entryUncomp)
                archiveBytesRemaining = candidateTargetAfterTruncate
                val afterTruncateDisk = safeAdd(archiveBytesRemaining, extractedSoFar)
                if (afterTruncateDisk > peakDiskBytes) {
                    peakDiskBytes = afterTruncateDisk
                }
            }

            hatchTempWorkingBytes = maxTempWorking
            val minPhysicalFloor = maxOf(safeArchiveSize, extractedSizeBytes)
            hatchTotalPeakBytes = peakDiskBytes.coerceIn(minPhysicalFloor, standardTotalPeakBytes)
            val rawHatchAdditional = (hatchTotalPeakBytes - safeArchiveSize).coerceAtLeast(0L)
            hatchPeakAdditionalBytes = maxOf(rawHatchAdditional, hatchTempWorkingBytes)
                .coerceAtMost(standardPeakAdditionalBytes)
            hatchTotalPeakBytes = safeAdd(safeArchiveSize, hatchPeakAdditionalBytes)
                .coerceAtMost(standardTotalPeakBytes)
        }

        val hatchRequiredWithReserveBytes = safeAdd(hatchPeakAdditionalBytes, effectiveReserve)
        val spaceSavedWithHatchBytes = if (isHatchModeSupported) {
            (standardTotalPeakBytes - hatchTotalPeakBytes).coerceAtLeast(0L)
        } else {
            0L
        }

        val hasReliableFreeSpace = isDestinationCapacityReliable &&
            destinationFreeBytes != null &&
            destinationFreeBytes > 0L

        val standardVerdict: PlanModeVerdict
        val hatchVerdict: PlanModeVerdict
        val recommendation: StoragePlannerRecommendation
        val canGuaranteeSufficientSpace: Boolean

        if (!hasReliableFreeSpace) {
            standardVerdict = PlanModeVerdict.CAPACITY_UNKNOWN
            hatchVerdict = if (isHatchModeSupported) {
                PlanModeVerdict.CAPACITY_UNKNOWN
            } else {
                PlanModeVerdict.UNSUPPORTED_MODE
            }
            recommendation = StoragePlannerRecommendation.CAPACITY_UNKNOWN_PROCEED_WITH_CAUTION
            canGuaranteeSufficientSpace = false
        } else {
            val free = destinationFreeBytes!!
            val standardFits = standardRequiredWithReserveBytes <= free
            val hatchFits = isHatchModeSupported && (hatchRequiredWithReserveBytes <= free)

            standardVerdict = if (standardFits) {
                PlanModeVerdict.FITS_SAFELY
            } else {
                PlanModeVerdict.INSUFFICIENT_SPACE
            }
            hatchVerdict = when {
                !isHatchModeSupported -> PlanModeVerdict.UNSUPPORTED_MODE
                hatchFits -> PlanModeVerdict.FITS_SAFELY
                else -> PlanModeVerdict.INSUFFICIENT_SPACE
            }
            recommendation = when {
                standardFits -> StoragePlannerRecommendation.BOTH_MODES_FIT
                hatchFits -> StoragePlannerRecommendation.HATCH_MODE_RECOMMENDED
                else -> StoragePlannerRecommendation.INSUFFICIENT_FOR_BOTH
            }
            canGuaranteeSufficientSpace = isEstimateReliable && (standardFits || hatchFits)
        }

        val statusReason = when {
            !hasReliableFreeSpace ->
                "SAF destination volume free space cannot be verified; sufficient space cannot be guaranteed."
            !isEstimateReliable && targetFiles.isEmpty() ->
                "Archive contains no extractable file entries."
            !isEstimateReliable ->
                "Archive header offsets or sizes are incomplete; storage estimate is approximate."
            !isHatchModeSupported && format == ArchiveFormat.TAR_GZ ->
                "Sequential .tar.gz streams require Standard Extraction."
            !isHatchModeSupported && isSolidSevenZ ->
                "Solid .7z blocks share a single dictionary stream and require Standard Extraction."
            !isHatchModeSupported && isPartialSelection ->
                "Selective file extraction preserves the source archive using Standard Extraction."
            !isDirectPosixDestination && isHatchModeSupported ->
                "SAF destination retains full entry bytes until each file is verified."
            else -> null
        }

        return PreExtractionStoragePlan(
            archiveSizeBytes = safeArchiveSize,
            extractedSizeBytes = extractedSizeBytes,
            standardTempWorkingBytes = standardTempWorkingBytes,
            hatchTempWorkingBytes = hatchTempWorkingBytes,
            safetyReserveBytes = effectiveReserve,
            standardPeakAdditionalBytes = standardPeakAdditionalBytes,
            hatchPeakAdditionalBytes = hatchPeakAdditionalBytes,
            standardRequiredWithReserveBytes = standardRequiredWithReserveBytes,
            hatchRequiredWithReserveBytes = hatchRequiredWithReserveBytes,
            standardTotalPeakBytes = standardTotalPeakBytes,
            hatchTotalPeakBytes = hatchTotalPeakBytes,
            spaceSavedWithHatchBytes = spaceSavedWithHatchBytes,
            destinationFreeBytes = if (hasReliableFreeSpace) destinationFreeBytes else null,
            isDestinationCapacityReliable = hasReliableFreeSpace,
            isDirectPosixDestination = isDirectPosixDestination,
            isHatchModeSupported = isHatchModeSupported,
            isEstimateReliable = isEstimateReliable,
            canGuaranteeSufficientSpace = canGuaranteeSufficientSpace,
            standardVerdict = standardVerdict,
            hatchVerdict = hatchVerdict,
            recommendation = recommendation,
            statusReason = statusReason
        )
    }

    fun computeArchiveSummary(
        file: File,
        deviceFreeBytes: Long,
        isDestinationCapacityReliable: Boolean = deviceFreeBytes > 0L,
        isDirectPosixDestination: Boolean = true
    ): ArchiveSummary {
        val fileSizeBytes = file.length().coerceAtLeast(0L)
        val format = try {
            RandomAccessFile(file, "r").use { raf ->
                ZipParser.detectFormat(file.name, raf.channel)
            }
        } catch (_: Throwable) {
            ZipParser.detectFormat(file.name)
        }

        val entries = try {
            ZipParser.parseArchive(file)
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            emptyList()
        }

        val rawUncompressed = entries.sumOf { it.uncompressedSize.coerceAtLeast(0L) }
        val uncompressedSizeBytes = if (rawUncompressed > 0L) {
            rawUncompressed
        } else {
            fileSizeBytes
        }
        val isZip64 = entries.any { it.isZip64 } || fileSizeBytes >= 0xFFFFFFFFL || uncompressedSizeBytes >= 0xFFFFFFFFL
        val hasEncrypted = entries.any { it.isEncrypted } || (format == ArchiveFormat.SEVEN_Z && try {
            RandomAccessFile(file, "r").use { raf -> ZipParser.detectSevenZHeaderEncryption(raf.channel) }
        } catch (_: Throwable) { false })

        val standardPeakRequired = safeAdd(fileSizeBytes, uncompressedSizeBytes)
        val hatchPeakRequired = computeHatchTimelinePeakBytes(
            fileSizeBytes = fileSizeBytes,
            uncompressedSizeBytes = uncompressedSizeBytes,
            entries = entries,
            format = format
        )
        val spaceSaved = (standardPeakRequired - hatchPeakRequired).coerceAtLeast(0L)
        val storagePlan = computePreExtractionStoragePlan(
            archiveSizeBytes = fileSizeBytes,
            entries = entries,
            format = format,
            destinationFreeBytes = if (isDestinationCapacityReliable && deviceFreeBytes > 0L) deviceFreeBytes else null,
            isDestinationCapacityReliable = isDestinationCapacityReliable && deviceFreeBytes > 0L,
            isDirectPosixDestination = isDirectPosixDestination,
            hasEncryptedEntries = hasEncrypted
        )

        return ArchiveSummary(
            filePath = file.absolutePath,
            sourceUriString = null,
            fileName = file.name,
            format = format,
            isZip64 = isZip64,
            hasEncryptedEntries = hasEncrypted,
            isZeroCopyDirectUri = false,
            fileSizeBytes = fileSizeBytes,
            uncompressedSizeBytes = uncompressedSizeBytes,
            entryCount = entries.size,
            entries = entries,
            standardPeakSpaceRequired = standardPeakRequired,
            hatchPeakSpaceRequired = hatchPeakRequired,
            spaceSavedWithHatch = spaceSaved,
            deviceFreeSpace = if (isDestinationCapacityReliable) deviceFreeBytes else 0L,
            storagePlan = storagePlan
        )
    }

    fun computeSafUriSummary(
        context: Context,
        uri: Uri,
        deviceFreeBytes: Long,
        isDestinationCapacityReliable: Boolean = deviceFreeBytes > 0L,
        isDirectPosixDestination: Boolean = true
    ): ArchiveSummary? {
        return try {
            val fileName = StorageHelper.queryFileName(context, uri) ?: "External_Archive.zip"
            var fileSizeBytes = StorageHelper.queryUriSize(context, uri)
            var format = ZipParser.detectFormat(fileName)

            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (fileSizeBytes <= 0L && pfd.statSize > 0L) {
                    fileSizeBytes = pfd.statSize
                }
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    if (fileSizeBytes <= 0L) {
                        fileSizeBytes = try { ch.size().coerceAtLeast(0L) } catch (_: Throwable) { 0L }
                    }
                    format = ZipParser.detectFormat(fileName, ch)
                }
            }

            val entries = ZipParser.parseSafUri(context, uri, fileName)
            if (entries.isEmpty()) return null

            val rawUncompressed = entries.sumOf { it.uncompressedSize.coerceAtLeast(0L) }
            val uncompressedSizeBytes = if (rawUncompressed > 0L) rawUncompressed else fileSizeBytes
            val isZip64 = entries.any { it.isZip64 } || fileSizeBytes >= 0xFFFFFFFFL || uncompressedSizeBytes >= 0xFFFFFFFFL
            val hasEncrypted = entries.any { it.isEncrypted } || (format == ArchiveFormat.SEVEN_Z && try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        ZipParser.detectSevenZHeaderEncryption(fis.channel)
                    }
                } ?: false
            } catch (_: Throwable) { false })

            val standardPeakRequired = safeAdd(fileSizeBytes, uncompressedSizeBytes)
            val hatchPeakRequired = computeHatchTimelinePeakBytes(
                fileSizeBytes = fileSizeBytes,
                uncompressedSizeBytes = uncompressedSizeBytes,
                entries = entries,
                format = format
            )
            val spaceSaved = (standardPeakRequired - hatchPeakRequired).coerceAtLeast(0L)
            val storagePlan = computePreExtractionStoragePlan(
                archiveSizeBytes = fileSizeBytes,
                entries = entries,
                format = format,
                destinationFreeBytes = if (isDestinationCapacityReliable && deviceFreeBytes > 0L) deviceFreeBytes else null,
                isDestinationCapacityReliable = isDestinationCapacityReliable && deviceFreeBytes > 0L,
                isDirectPosixDestination = isDirectPosixDestination,
                hasEncryptedEntries = hasEncrypted
            )

            ArchiveSummary(
                filePath = "",
                sourceUriString = uri.toString(),
                fileName = fileName,
                format = format,
                isZip64 = isZip64,
                hasEncryptedEntries = hasEncrypted,
                isZeroCopyDirectUri = true,
                fileSizeBytes = fileSizeBytes,
                uncompressedSizeBytes = uncompressedSizeBytes,
                entryCount = entries.size,
                entries = entries,
                standardPeakSpaceRequired = standardPeakRequired,
                hatchPeakSpaceRequired = hatchPeakRequired,
                spaceSavedWithHatch = spaceSaved,
                deviceFreeSpace = if (isDestinationCapacityReliable) deviceFreeBytes else 0L,
                storagePlan = storagePlan
            )
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            null
        }
    }
}
