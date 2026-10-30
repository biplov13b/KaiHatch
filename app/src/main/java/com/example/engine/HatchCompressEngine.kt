package com.example.engine

import com.example.model.ArchiveFormat
import com.example.model.CompressionProgress
import com.example.model.CompressionSettings
import com.example.model.CompressionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.coroutineContext

object HatchCompressEngine {

    private const val SMALL_FILE_IN_MEMORY_LIMIT = 2L * 1024L * 1024L // 2 MB
    private const val LARGE_FILE_CHUNK_BYTES = 1024 * 1024            // 1 MB parallel deflate slice

    fun compressFiles(
        itemsToCompress: List<File>,
        targetArchiveFile: File,
        settings: CompressionSettings
    ): Flow<CompressionProgress> = channelFlow {
        val startTime = System.currentTimeMillis()
        var lastSpeedCheck = startTime
        var lastEmitTime = 0L
        var bytesSinceCheck = 0L
        var currentSpeed = 0L

        send(
            CompressionProgress(
                status = CompressionStatus.PREPARING,
                isEncrypted = settings.password.isNotEmpty(),
                startTime = startTime
            )
        )

        val fileList = mutableListOf<Pair<File, String>>()
        for (item in itemsToCompress) {
            if (item.isDirectory) {
                collectFilesRecursively(item, item.name, fileList)
            } else {
                fileList.add(Pair(item, item.name))
            }
        }

        if (fileList.isEmpty()) {
            send(
                CompressionProgress(
                    status = CompressionStatus.ERROR,
                    errorMessage = "No files selected to compress"
                )
            )
            return@channelFlow
        }

        val totalInputBytes = fileList.sumOf { it.first.length() }
        var processedBytes = 0L
        targetArchiveFile.parentFile?.mkdirs()

        try {
            when (settings.format) {
                ArchiveFormat.SEVEN_Z -> {
                    if (targetArchiveFile.exists()) {
                        targetArchiveFile.delete()
                    }
                    SevenZOutputFile(targetArchiveFile).use { sevenZOut ->
                        val method = when {
                            settings.compressionLevel <= 0 -> SevenZMethod.COPY
                            settings.compressionLevel == 1 -> SevenZMethod.DEFLATE
                            else -> SevenZMethod.LZMA2
                        }
                        sevenZOut.setContentMethods(listOf(SevenZMethodConfiguration(method)))
                        val ioBuf = ByteArray(64 * 1024)
                        var idx = 0
                        for ((file, relPath) in fileList) {
                            if (!coroutineContext.isActive) break
                            idx++
                            val normalizedPath = relPath.replace('\\', '/').trimStart('/')
                            val entry = sevenZOut.createArchiveEntry(file, normalizedPath)
                            sevenZOut.putArchiveEntry(entry)
                            FileInputStream(file).use { fis ->
                                var read: Int
                                while (fis.read(ioBuf).also { read = it } != -1) {
                                    if (!coroutineContext.isActive) break
                                    sevenZOut.write(ioBuf, 0, read)
                                    val delta = read.toLong()
                                    processedBytes += delta
                                    bytesSinceCheck += delta
                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedCheck >= 300L) {
                                        currentSpeed = (bytesSinceCheck * 1000L) / (now - lastSpeedCheck).coerceAtLeast(1L)
                                        bytesSinceCheck = 0L
                                        lastSpeedCheck = now
                                    }
                                    if (now - lastEmitTime >= 160L) {
                                        lastEmitTime = now
                                        trySend(
                                            CompressionProgress(
                                                status = CompressionStatus.COMPRESSING,
                                                totalFiles = fileList.size,
                                                currentFileIndex = idx,
                                                currentFileName = file.name,
                                                totalInputBytes = totalInputBytes,
                                                processedInputBytes = processedBytes,
                                                currentOutputBytes = targetArchiveFile.length(),
                                                bytesPerSecond = currentSpeed,
                                                startTime = startTime,
                                                elapsedTimeMs = now - startTime
                                            )
                                        )
                                    }
                                }
                            }
                            sevenZOut.closeArchiveEntry()
                        }
                        sevenZOut.finish()
                    }
                }

                ArchiveFormat.TAR -> {
                    FileOutputStream(targetArchiveFile).use { fos ->
                        BufferedOutputStream(fos, 256 * 1024).use { bos ->
                            var idx = 0
                            for ((file, relPath) in fileList) {
                                if (!coroutineContext.isActive) break
                                idx++
                                writeTarEntry(file, relPath, bos) { delta ->
                                    processedBytes += delta
                                    bytesSinceCheck += delta
                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedCheck >= 300L) {
                                        currentSpeed = (bytesSinceCheck * 1000L) / (now - lastSpeedCheck).coerceAtLeast(1L)
                                        bytesSinceCheck = 0L
                                        lastSpeedCheck = now
                                    }
                                    if (now - lastEmitTime >= 160L) {
                                        lastEmitTime = now
                                        trySend(
                                            CompressionProgress(
                                                status = CompressionStatus.COMPRESSING,
                                                totalFiles = fileList.size,
                                                currentFileIndex = idx,
                                                currentFileName = file.name,
                                                totalInputBytes = totalInputBytes,
                                                processedInputBytes = processedBytes,
                                                currentOutputBytes = targetArchiveFile.length(),
                                                bytesPerSecond = currentSpeed,
                                                startTime = startTime,
                                                elapsedTimeMs = now - startTime
                                            )
                                        )
                                    }
                                }
                            }
                            // Two 512-byte zero blocks mark EOF in TAR
                            bos.write(ByteArray(1024))
                            bos.flush()
                        }
                    }
                }

                ArchiveFormat.TAR_GZ -> {
                    FileOutputStream(targetArchiveFile).use { fos ->
                        GZIPOutputStream(BufferedOutputStream(fos, 256 * 1024)).use { gzos ->
                            var idx = 0
                            for ((file, relPath) in fileList) {
                                if (!coroutineContext.isActive) break
                                idx++
                                writeTarEntry(file, relPath, gzos) { delta ->
                                    processedBytes += delta
                                    bytesSinceCheck += delta
                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedCheck >= 300L) {
                                        currentSpeed = (bytesSinceCheck * 1000L) / (now - lastSpeedCheck).coerceAtLeast(1L)
                                        bytesSinceCheck = 0L
                                        lastSpeedCheck = now
                                    }
                                    if (now - lastEmitTime >= 160L) {
                                        lastEmitTime = now
                                        trySend(
                                            CompressionProgress(
                                                status = CompressionStatus.COMPRESSING,
                                                totalFiles = fileList.size,
                                                currentFileIndex = idx,
                                                currentFileName = file.name,
                                                totalInputBytes = totalInputBytes,
                                                processedInputBytes = processedBytes,
                                                currentOutputBytes = targetArchiveFile.length(),
                                                bytesPerSecond = currentSpeed,
                                                startTime = startTime,
                                                elapsedTimeMs = now - startTime
                                            )
                                        )
                                    }
                                }
                            }
                            gzos.write(ByteArray(1024))
                            gzos.finish()
                        }
                    }
                }

                else -> {
                    // Multi-Threaded Parallel ZIP / RAR / APK / ZipCrypto Engine
                    writeParallelZip(
                        fileList = fileList,
                        targetArchiveFile = targetArchiveFile,
                        password = settings.password,
                        compressionLevel = settings.compressionLevel,
                        comment = settings.comment,
                        totalInputBytes = totalInputBytes,
                        startTime = startTime,
                        onProgress = { progress ->
                            processedBytes = progress.processedInputBytes
                            trySend(progress)
                        }
                    )
                }
            }

            if (!coroutineContext.isActive) {
                targetArchiveFile.delete()
                send(
                    CompressionProgress(
                        status = CompressionStatus.CANCELLED,
                        totalFiles = fileList.size,
                        totalInputBytes = totalInputBytes,
                        processedInputBytes = processedBytes
                    )
                )
                return@channelFlow
            }

            // Optional Multi-Part Volume Splitting (.001, .002, ...)
            var volumesCreated = 1
            if (settings.splitVolumeSizeMb > 0) {
                val maxVolBytes = settings.splitVolumeSizeMb * 1024L * 1024L
                if (targetArchiveFile.length() > maxVolBytes) {
                    volumesCreated = splitArchiveIntoVolumes(targetArchiveFile, maxVolBytes)
                }
            }

            send(
                CompressionProgress(
                    status = CompressionStatus.COMPLETED,
                    totalFiles = fileList.size,
                    currentFileIndex = fileList.size,
                    currentFileName = "Complete",
                    totalInputBytes = totalInputBytes,
                    processedInputBytes = totalInputBytes,
                    currentOutputBytes = targetArchiveFile.length(),
                    bytesPerSecond = 0L,
                    outputFilePath = targetArchiveFile.absolutePath,
                    splitVolumesCreated = volumesCreated,
                    isEncrypted = settings.password.isNotEmpty(),
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime
                )
            )
        } catch (e: Exception) {
            send(
                CompressionProgress(
                    status = CompressionStatus.ERROR,
                    errorMessage = "Compression failed: ${e.message}"
                )
            )
        }
    }.conflate().flowOn(Dispatchers.IO)

    private data class CentralRecord(
        val nameBytes: ByteArray,
        val generalFlag: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long
    )

    private data class PreparedSmallEntry(
        val fileIndex: Int,
        val file: File,
        val nameBytes: ByteArray,
        val generalFlag: Int,
        val method: Int,
        val crc32: Long,
        val uncompressedSize: Long,
        val payloadBytes: ByteArray
    )

    private data class DeflatedChunk(
        val rawBytes: ByteArray,
        val rawLen: Int,
        val compressedBytes: ByteArray
    )

    /**
     * Multi-Threaded Parallel ZIP / ZipCrypto Compressor:
     * - Compresses batches of small/medium files (<= 2 MB) concurrently across [maxWorkers] threads.
     * - Compresses large files (> 2 MB) using parallel 1 MB chunked Deflate (`Deflater.SYNC_FLUSH`
     *   for non-final chunks + `finish()` on the final chunk) across [maxWorkers] threads.
     */
    private suspend fun writeParallelZip(
        fileList: List<Pair<File, String>>,
        targetArchiveFile: File,
        password: String,
        compressionLevel: Int,
        comment: String,
        totalInputBytes: Long,
        startTime: Long,
        onProgress: (CompressionProgress) -> Unit
    ) {
        val isEncrypted = password.isNotEmpty()
        val generalFlag = (if (isEncrypted) 0x0001 else 0) or 0x0800 // Bit 11 = UTF-8 filenames
        val preCompressedExtensions = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v",
            "jpg", "jpeg", "png", "webp", "gif", "heic", "avif",
            "mp3", "m4a", "aac", "ogg", "flac", "opus",
            "zip", "7z", "rar", "gz", "tgz", "bz2", "xz", "zst", "apk"
        )
        fun resolveEntryMethod(fileName: String): Int {
            if (compressionLevel == 0) return 0
            if (!isEncrypted) {
                val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in preCompressedExtensions) return 0
            }
            return 8
        }

        val maxWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val workerDispatcher = Dispatchers.IO.limitedParallelism(maxWorkers)

        val centralRecords = ArrayList<CentralRecord>(fileList.size)
        val atomicProcessedBytes = AtomicLong(0L)
        val atomicBytesSinceSpeed = AtomicLong(0L)
        val atomicLastSpeedTime = AtomicLong(startTime)
        val atomicCurrentSpeed = AtomicLong(0L)
        val atomicLastEmitTime = AtomicLong(0L)

        fun reportDelta(deltaBytes: Long, fileIdx: Int, fileName: String, outBytesNow: Long, force: Boolean = false) {
            val totalDone = atomicProcessedBytes.addAndGet(deltaBytes)
            atomicBytesSinceSpeed.addAndGet(deltaBytes)
            val now = System.currentTimeMillis()
            val prevSpeedTime = atomicLastSpeedTime.get()
            if (now - prevSpeedTime >= 300L && atomicLastSpeedTime.compareAndSet(prevSpeedTime, now)) {
                val recent = atomicBytesSinceSpeed.getAndSet(0L)
                val spd = (recent * 1000L) / (now - prevSpeedTime).coerceAtLeast(1L)
                atomicCurrentSpeed.set(spd)
            }
            val prevEmit = atomicLastEmitTime.get()
            if (force || (now - prevEmit >= 160L && atomicLastEmitTime.compareAndSet(prevEmit, now))) {
                if (force) atomicLastEmitTime.set(now)
                onProgress(
                    CompressionProgress(
                        status = CompressionStatus.COMPRESSING,
                        totalFiles = fileList.size,
                        currentFileIndex = fileIdx,
                        currentFileName = fileName,
                        totalInputBytes = totalInputBytes,
                        processedInputBytes = totalDone,
                        currentOutputBytes = outBytesNow,
                        bytesPerSecond = atomicCurrentSpeed.get(),
                        isEncrypted = isEncrypted,
                        startTime = startTime,
                        elapsedTimeMs = now - startTime
                    )
                )
            }
        }

        FileOutputStream(targetArchiveFile).use { fos ->
            val outChannel = fos.channel
            var cursor = 0

            while (cursor < fileList.size && coroutineContext.isActive) {
                val (firstFile, firstPath) = fileList[cursor]

                // Path A: Small/Medium files (<= 2 MB) -> Batch up to maxWorkers files in parallel!
                if (firstFile.length() <= SMALL_FILE_IN_MEMORY_LIMIT) {
                    val batch = mutableListOf<Triple<Int, File, String>>()
                    var batchBytes = 0L
                    while (cursor < fileList.size && batch.size < maxWorkers) {
                        val (f, p) = fileList[cursor]
                        val len = f.length()
                        if (len > SMALL_FILE_IN_MEMORY_LIMIT) break
                        if (batch.isNotEmpty() && batchBytes + len > 8L * 1024L * 1024L) break
                        batch.add(Triple(cursor + 1, f, p))
                        batchBytes += len
                        cursor++
                    }

                    val preparedBatch: List<PreparedSmallEntry> = coroutineScope {
                        batch.map { (fileIdx, f, relPath) ->
                            async(workerDispatcher) {
                                val rawBytes = f.readBytes()
                                val uncompSize = rawBytes.size.toLong()
                                val crc = CRC32().apply { update(rawBytes) }.value
                                val nameBytes = relPath.toByteArray(Charsets.UTF_8)
                                val entryMethod = resolveEntryMethod(f.name)

                                val compressedRaw = if (entryMethod == 0) {
                                    rawBytes
                                } else {
                                    val deflater = Deflater(compressionLevel, true)
                                    try {
                                        deflater.setInput(rawBytes)
                                        deflater.finish()
                                        val baos = ByteArrayOutputStream((rawBytes.size / 2).coerceAtLeast(64))
                                        val scratch = CommonsCompressBufferStrategy.borrowInflateBuffer(uncompSize)
                                        while (!deflater.finished()) {
                                            val d = deflater.deflate(scratch)
                                            if (d > 0) {
                                                baos.write(scratch, 0, d)
                                            } else {
                                                break
                                            }
                                        }
                                        baos.toByteArray()
                                    } finally {
                                        deflater.end()
                                    }
                                }

                                val finalPayload = if (isEncrypted) {
                                    val cipher = ZipCryptoCipher(password)
                                    val encHeader = cipher.generateEncryptedHeader(crc)
                                    val combined = ByteArray(12 + compressedRaw.size)
                                    System.arraycopy(encHeader, 0, combined, 0, 12)
                                    System.arraycopy(compressedRaw, 0, combined, 12, compressedRaw.size)
                                    cipher.encryptBytesInPlace(combined, 12, compressedRaw.size)
                                    combined
                                } else {
                                    compressedRaw
                                }

                                reportDelta(
                                    deltaBytes = uncompSize,
                                    fileIdx = fileIdx,
                                    fileName = f.name,
                                    outBytesNow = outChannel.position()
                                )

                                PreparedSmallEntry(
                                    fileIndex = fileIdx,
                                    file = f,
                                    nameBytes = nameBytes,
                                    generalFlag = generalFlag,
                                    method = entryMethod,
                                    crc32 = crc,
                                    uncompressedSize = uncompSize,
                                    payloadBytes = finalPayload
                                )
                            }
                        }.awaitAll()
                    }

                    // Write all prepared entries in deterministic order to the output FileChannel
                    for (entry in preparedBatch) {
                        val localOffset = outChannel.position()
                        val compSize = entry.payloadBytes.size.toLong()
                        val lfh = ByteBuffer.allocate(30 + entry.nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                        lfh.putInt(0x04034b50)
                        lfh.putShort(20)
                        lfh.putShort(entry.generalFlag.toShort())
                        lfh.putShort(entry.method.toShort())
                        lfh.putShort(0)
                        lfh.putShort(0x21)
                        lfh.putInt(entry.crc32.toInt())
                        lfh.putInt(compSize.toInt())
                        lfh.putInt(entry.uncompressedSize.toInt())
                        lfh.putShort(entry.nameBytes.size.toShort())
                        lfh.putShort(0)
                        lfh.put(entry.nameBytes)
                        lfh.flip()
                        outChannel.write(lfh)
                        outChannel.write(ByteBuffer.wrap(entry.payloadBytes))

                        centralRecords.add(
                            CentralRecord(
                                nameBytes = entry.nameBytes,
                                generalFlag = entry.generalFlag,
                                method = entry.method,
                                crc32 = entry.crc32,
                                compressedSize = compSize,
                                uncompressedSize = entry.uncompressedSize,
                                localOffset = localOffset
                            )
                        )
                    }
                    continue
                }

                // Path B: Large File (> 2 MB) -> Parallel 1 MB Chunked Deflate (SYNC_FLUSH) or STORED
                val fileIdx = cursor + 1
                cursor++
                val localOffset = outChannel.position()
                val nameBytes = firstPath.toByteArray(Charsets.UTF_8)
                val uncompressedSize = firstFile.length()
                val entryMethod = resolveEntryMethod(firstFile.name)

                // If encrypted, ZipCrypto header requires the full CRC32 upfront
                val precomputedCrc = if (isEncrypted) computeFileCrc32(firstFile) else 0L
                val rollingCrc = CRC32()

                val lfh = ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                lfh.putInt(0x04034b50)
                lfh.putShort(20)
                lfh.putShort(generalFlag.toShort())
                lfh.putShort(entryMethod.toShort())
                lfh.putShort(0)
                lfh.putShort(0x21)
                lfh.putInt(precomputedCrc.toInt()) // backpatched at localOffset + 14 if unencrypted
                lfh.putInt(0)                      // backpatched at localOffset + 18
                lfh.putInt(uncompressedSize.toInt())
                lfh.putShort(nameBytes.size.toShort())
                lfh.putShort(0)
                lfh.put(nameBytes)
                lfh.flip()
                outChannel.write(lfh)

                val payloadStartOffset = outChannel.position()
                val cipher = if (isEncrypted) {
                    ZipCryptoCipher(password).also { c ->
                        val encHeader = c.generateEncryptedHeader(precomputedCrc)
                        outChannel.write(ByteBuffer.wrap(encHeader))
                    }
                } else null

                val totalChunks = ((uncompressedSize + LARGE_FILE_CHUNK_BYTES - 1L) / LARGE_FILE_CHUNK_BYTES).toInt().coerceAtLeast(1)
                RandomAccessFile(firstFile, "r").use { inRaf ->
                    val inChannel = inRaf.channel
                    var chunkCursor = 0
                    while (chunkCursor < totalChunks && coroutineContext.isActive) {
                        val windowSize = minOf(maxWorkers, totalChunks - chunkCursor)
                        val chunkIndices = (chunkCursor until (chunkCursor + windowSize)).toList()
                        chunkCursor += windowSize

                        val deflatedWindow: List<DeflatedChunk> = coroutineScope {
                            chunkIndices.map { cIdx ->
                                async(workerDispatcher) {
                                    val chunkOffset = cIdx.toLong() * LARGE_FILE_CHUNK_BYTES
                                    val chunkLen = (uncompressedSize - chunkOffset)
                                        .coerceAtMost(LARGE_FILE_CHUNK_BYTES.toLong())
                                        .toInt()
                                    val rawBuf = ByteArray(chunkLen)
                                    val bb = ByteBuffer.wrap(rawBuf)
                                    var pos = chunkOffset
                                    while (bb.hasRemaining()) {
                                        val r = inChannel.read(bb, pos)
                                        if (r <= 0) break
                                        pos += r
                                    }
                                    val actualRead = bb.position()
                                    val isLastChunk = (cIdx == totalChunks - 1)

                                    val compBytes = if (entryMethod == 0) {
                                        if (actualRead == rawBuf.size) rawBuf else rawBuf.copyOf(actualRead)
                                    } else {
                                        val deflater = Deflater(compressionLevel, true)
                                        try {
                                            deflater.setInput(rawBuf, 0, actualRead)
                                            val baos = ByteArrayOutputStream((actualRead / 2).coerceAtLeast(256))
                                            val outScratch = CommonsCompressBufferStrategy.borrowInflateBuffer(actualRead.toLong())
                                            if (isLastChunk) {
                                                deflater.finish()
                                                while (!deflater.finished()) {
                                                    val d = deflater.deflate(outScratch)
                                                    if (d > 0) baos.write(outScratch, 0, d) else break
                                                }
                                            } else {
                                                while (true) {
                                                    val d = deflater.deflate(outScratch, 0, outScratch.size, Deflater.SYNC_FLUSH)
                                                    if (d > 0) {
                                                        baos.write(outScratch, 0, d)
                                                    } else {
                                                        break
                                                    }
                                                }
                                            }
                                            baos.toByteArray()
                                        } finally {
                                            deflater.end()
                                        }
                                    }

                                    DeflatedChunk(
                                        rawBytes = rawBuf,
                                        rawLen = actualRead,
                                        compressedBytes = compBytes
                                    )
                                }
                            }.awaitAll()
                        }

                        for (chunk in deflatedWindow) {
                            if (!isEncrypted) {
                                rollingCrc.update(chunk.rawBytes, 0, chunk.rawLen)
                            }
                            if (cipher != null) {
                                cipher.encryptBytesInPlace(chunk.compressedBytes, 0, chunk.compressedBytes.size)
                            }
                            outChannel.write(ByteBuffer.wrap(chunk.compressedBytes))
                            reportDelta(
                                deltaBytes = chunk.rawLen.toLong(),
                                fileIdx = fileIdx,
                                fileName = firstFile.name,
                                outBytesNow = outChannel.position()
                            )
                        }
                    }
                }

                val payloadEndOffset = outChannel.position()
                val totalCompressedSize = (payloadEndOffset - payloadStartOffset).coerceAtLeast(0L)
                val finalCrc32 = if (isEncrypted) precomputedCrc else rollingCrc.value

                // Backpatch CRC32 (offset +14) and compressedSize (offset +18) in Local File Header
                val patchBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                patchBuf.putInt(finalCrc32.toInt())
                patchBuf.putInt(totalCompressedSize.toInt())
                patchBuf.flip()
                outChannel.write(patchBuf, localOffset + 14L)
                outChannel.position(payloadEndOffset)

                centralRecords.add(
                    CentralRecord(
                        nameBytes = nameBytes,
                        generalFlag = generalFlag,
                        method = entryMethod,
                        crc32 = finalCrc32,
                        compressedSize = totalCompressedSize,
                        uncompressedSize = uncompressedSize,
                        localOffset = localOffset
                    )
                )
            }

            // Write Central Directory
            val cdStartOffset = outChannel.position()
            for (rec in centralRecords) {
                val cdh = ByteBuffer.allocate(46 + rec.nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                cdh.putInt(0x02014b50)
                cdh.putShort(20)
                cdh.putShort(20)
                cdh.putShort(rec.generalFlag.toShort())
                cdh.putShort(rec.method.toShort())
                cdh.putShort(0)
                cdh.putShort(0x21)
                cdh.putInt(rec.crc32.toInt())
                cdh.putInt(rec.compressedSize.toInt())
                cdh.putInt(rec.uncompressedSize.toInt())
                cdh.putShort(rec.nameBytes.size.toShort())
                cdh.putShort(0)
                cdh.putShort(0)
                cdh.putShort(0)
                cdh.putShort(0)
                cdh.putInt(0)
                cdh.putInt(rec.localOffset.toInt())
                cdh.put(rec.nameBytes)
                cdh.flip()
                outChannel.write(cdh)
            }
            val cdSize = outChannel.position() - cdStartOffset

            // Write 22-byte EOCD + optional comment
            val commentBytes = comment.toByteArray(Charsets.UTF_8)
            val eocd = ByteBuffer.allocate(22 + commentBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            eocd.putInt(0x06054b50)
            eocd.putShort(0)
            eocd.putShort(0)
            eocd.putShort(centralRecords.size.toShort())
            eocd.putShort(centralRecords.size.toShort())
            eocd.putInt(cdSize.toInt())
            eocd.putInt(cdStartOffset.toInt())
            eocd.putShort(commentBytes.size.toShort())
            if (commentBytes.isNotEmpty()) {
                eocd.put(commentBytes)
            }
            eocd.flip()
            outChannel.write(eocd)
        }
    }

    private suspend fun writeTarEntry(
        file: File,
        relPath: String,
        out: OutputStream,
        onBytesDelta: suspend (Int) -> Unit
    ) {
        val header = ByteArray(512)
        val nameBytes = relPath.take(99).toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)

        writeOctal(header, 100, 8, 0b110100100L) // mode 0644
        writeOctal(header, 108, 8, 0L)
        writeOctal(header, 116, 8, 0L)
        writeOctal(header, 124, 12, file.length())
        writeOctal(header, 136, 12, file.lastModified() / 1000L)

        // Fill checksum field with spaces (0x20) before computing header checksum
        for (i in 148 until 156) header[i] = 0x20.toByte()
        header[156] = '0'.code.toByte()

        val ustar = "ustar\u000000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(ustar, 0, header, 257, ustar.size)

        var chksum = 0L
        for (b in header) chksum += (b.toInt() and 0xFF)
        writeOctal(header, 148, 8, chksum)

        out.write(header)

        val buf = ByteArray(64 * 1024)
        var written = 0L
        FileInputStream(file).use { fis ->
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                out.write(buf, 0, r)
                written += r
                onBytesDelta(r)
            }
        }

        val pad = ((512L - (written % 512L)) % 512L).toInt()
        if (pad > 0) {
            out.write(ByteArray(pad))
        }
    }

    private fun writeOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
        val oct = java.lang.Long.toOctalString(value)
        val padded = oct.padStart(length - 1, '0') + "\u0000"
        val bytes = padded.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, buf, offset, bytes.size.coerceAtMost(length))
    }

    private fun splitArchiveIntoVolumes(sourceFile: File, maxVolumeBytes: Long): Int {
        var partNum = 1
        val buffer = ByteArray(64 * 1024)
        FileInputStream(sourceFile).use { fis ->
            var remainingTotal = sourceFile.length()
            while (remainingTotal > 0L) {
                val ext = String.format(Locale.US, "%03d", partNum)
                val partFile = File(sourceFile.parentFile, "${sourceFile.name}.$ext")
                var partRemaining = maxVolumeBytes.coerceAtMost(remainingTotal)
                FileOutputStream(partFile).use { fos ->
                    while (partRemaining > 0L) {
                        val toRead = partRemaining.coerceAtMost(buffer.size.toLong()).toInt()
                        val r = fis.read(buffer, 0, toRead)
                        if (r <= 0) break
                        fos.write(buffer, 0, r)
                        partRemaining -= r
                        remainingTotal -= r
                    }
                }
                partNum++
            }
        }
        return (partNum - 1).coerceAtLeast(1)
    }

    private fun computeFileCrc32(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                crc.update(buf, 0, r)
            }
        }
        return crc.value
    }

    private fun collectFilesRecursively(dir: File, baseName: String, result: MutableList<Pair<File, String>>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val childPath = "$baseName/${child.name}"
            if (child.isDirectory) {
                collectFilesRecursively(child, childPath, result)
            } else {
                result.add(Pair(child, childPath))
            }
        }
    }
}
