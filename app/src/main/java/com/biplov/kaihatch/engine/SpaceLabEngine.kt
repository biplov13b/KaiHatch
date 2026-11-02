package com.biplov.kaihatch.engine

import android.content.Context
import android.net.Uri
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ArchiveSummary
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
        val standardPeak = fileSizeBytes + uncompressedSizeBytes

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
                val firstSlicePeak = archiveBytesRemaining + extractedSoFar + activeChunk
                val lastSlicePeak = candidateTargetAfterTruncate + extractedSoFar + entryUncomp +
                        activeChunk.coerceAtMost(entryArchiveSpan)
                maxOf(firstSlicePeak, lastSlicePeak)
            } else {
                // Small/medium entry: extracted in full before its archive span is truncated
                archiveBytesRemaining + extractedSoFar + entryUncomp
            }

            if (stepPeak > peakDiskBytes) {
                peakDiskBytes = stepPeak
            }

            extractedSoFar += entryUncomp
            archiveBytesRemaining = candidateTargetAfterTruncate
            val afterTruncateDisk = archiveBytesRemaining + extractedSoFar
            if (afterTruncateDisk > peakDiskBytes) {
                peakDiskBytes = afterTruncateDisk
            }
        }

        val minPhysicalFloor = maxOf(fileSizeBytes, uncompressedSizeBytes)
        return peakDiskBytes.coerceIn(minPhysicalFloor, standardPeak)
    }

    fun computeArchiveSummary(file: File, deviceFreeBytes: Long): ArchiveSummary {
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

        val standardPeakRequired = fileSizeBytes + uncompressedSizeBytes
        val hatchPeakRequired = computeHatchTimelinePeakBytes(
            fileSizeBytes = fileSizeBytes,
            uncompressedSizeBytes = uncompressedSizeBytes,
            entries = entries,
            format = format
        )
        val spaceSaved = (standardPeakRequired - hatchPeakRequired).coerceAtLeast(0L)

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
            deviceFreeSpace = deviceFreeBytes
        )
    }

    fun computeSafUriSummary(context: Context, uri: Uri, deviceFreeBytes: Long): ArchiveSummary? {
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

            val standardPeakRequired = fileSizeBytes + uncompressedSizeBytes
            val hatchPeakRequired = computeHatchTimelinePeakBytes(
                fileSizeBytes = fileSizeBytes,
                uncompressedSizeBytes = uncompressedSizeBytes,
                entries = entries,
                format = format
            )
            val spaceSaved = (standardPeakRequired - hatchPeakRequired).coerceAtLeast(0L)

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
                deviceFreeSpace = deviceFreeBytes
            )
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            null
        }
    }
}
