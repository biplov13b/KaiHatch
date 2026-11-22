package com.biplov.kaihatch.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ArchiveSourceFingerprint
import com.biplov.kaihatch.model.ArchiveVerificationState
import com.biplov.kaihatch.model.VerificationLevel
import com.biplov.kaihatch.model.VerificationStatus
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.coroutines.coroutineContext

/**
 * Phase 2: Read-Only Pre-Extraction Archive Verifier ("Verify Before Hatch").
 *
 * Guarantees:
 * - Strictly read-only (`"r"` mode on `RandomAccessFile` and `ContentResolver.openFileDescriptor`).
 * - Never modifies, truncates, or deletes the source archive, and creates zero temporary files on disk.
 * - Supports two distinct verification levels:
 *   1. [VerificationLevel.QUICK_CHECK]: Structural, header signature, offset bounds, and metadata check.
 *   2. [VerificationLevel.FULL_VERIFY]: Full streaming payload decompression and CRC32 integrity verification.
 * - Bounded memory footprint using [CommonsCompressBufferStrategy] thread-local buffers.
 * - Captures and validates [ArchiveSourceFingerprint] to detect source modifications between
 *   verification and extraction.
 */
object ArchiveVerifierEngine {

    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50
    private const val ZIP64_EOCD_RECORD_SIGNATURE = 0x06064b50
    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val FINGERPRINT_WINDOW_BYTES = 64 * 1024 // 64 KB head + 64 KB tail digest

    /**
     * Captures a fast, read-only fingerprint of a local [File] archive.
     */
    fun captureSourceFingerprint(file: File): ArchiveSourceFingerprint? {
        if (!file.exists() || !file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.channel.size()
                val digest = computeHeadTailCrc32(raf.channel, size)
                ArchiveSourceFingerprint(
                    filePath = file.absolutePath,
                    sourceUriString = null,
                    fileSizeBytes = size,
                    lastModifiedMs = file.lastModified(),
                    headTailDigestCrc32 = digest
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Captures a fast, read-only fingerprint of a SAF [Uri] archive.
     */
    fun captureSourceFingerprint(context: Context, uri: Uri): ArchiveSourceFingerprint? {
        val lastMod = querySafLastModified(context, uri)
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val size = channel.size()
                    val digest = computeHeadTailCrc32(channel, size)
                    ArchiveSourceFingerprint(
                        filePath = null,
                        sourceUriString = uri.toString(),
                        fileSizeBytes = size,
                        lastModifiedMs = lastMod,
                        headTailDigestCrc32 = digest
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Checks whether the underlying local [File] or SAF [Uri] has been modified, truncated, or
     * replaced since [fingerprint] was recorded.
     */
    fun hasSourceChangedSinceVerification(
        fingerprint: ArchiveSourceFingerprint,
        archiveFile: File? = null,
        context: Context? = null,
        safUri: Uri? = null
    ): Boolean {
        if (fingerprint.filePath != null) {
            val targetFile = archiveFile ?: File(fingerprint.filePath)
            if (targetFile.absolutePath != fingerprint.filePath) return true
            val current = captureSourceFingerprint(targetFile) ?: return true
            if (current.fileSizeBytes != fingerprint.fileSizeBytes) return true
            if (fingerprint.lastModifiedMs > 0L && current.lastModifiedMs > 0L &&
                current.lastModifiedMs != fingerprint.lastModifiedMs
            ) {
                return true
            }
            return current.headTailDigestCrc32 != fingerprint.headTailDigestCrc32
        }

        if (fingerprint.sourceUriString != null) {
            if (context == null) return true
            val targetUri = safUri ?: runCatching { Uri.parse(fingerprint.sourceUriString) }.getOrNull() ?: return true
            if (targetUri.toString() != fingerprint.sourceUriString) return true
            val current = captureSourceFingerprint(context, targetUri) ?: return true
            if (current.fileSizeBytes != fingerprint.fileSizeBytes) return true
            if (fingerprint.lastModifiedMs > 0L && current.lastModifiedMs > 0L &&
                current.lastModifiedMs != fingerprint.lastModifiedMs
            ) {
                return true
            }
            return current.headTailDigestCrc32 != fingerprint.headTailDigestCrc32
        }

        return true
    }

    private fun computeHeadTailCrc32(channel: FileChannel, size: Long): Long {
        if (size <= 0L) return 0L
        val crc = CRC32()
        // Mix file size into CRC
        for (shift in 0 until 64 step 8) {
            crc.update(((size ushr shift) and 0xFFL).toInt())
        }
        val bufBytes = ByteArray(16 * 1024)
        val bb = ByteBuffer.wrap(bufBytes)

        val headLen = minOf(size, FINGERPRINT_WINDOW_BYTES.toLong())
        var pos = 0L
        while (pos < headLen) {
            val toRead = minOf((headLen - pos).toInt(), bufBytes.size)
            bb.clear()
            bb.limit(toRead)
            val r = readChannelPositional(channel, bb, pos)
            if (r <= 0) break
            crc.update(bufBytes, 0, r)
            pos += r
        }

        if (size > FINGERPRINT_WINDOW_BYTES.toLong()) {
            val tailStart = (size - FINGERPRINT_WINDOW_BYTES.toLong()).coerceAtLeast(headLen)
            pos = tailStart
            while (pos < size) {
                val toRead = minOf((size - pos).toInt(), bufBytes.size)
                bb.clear()
                bb.limit(toRead)
                val r = readChannelPositional(channel, bb, pos)
                if (r <= 0) break
                crc.update(bufBytes, 0, r)
                pos += r
            }
        }
        return crc.value
    }

    private fun querySafLastModified(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Verifies a local [File] archive strictly in read-only mode.
     */
    fun verifyArchive(
        archiveFile: File,
        level: VerificationLevel,
        password: String? = null,
        context: Context? = null
    ): Flow<ArchiveVerificationState> = flow {
        val startTime = System.currentTimeMillis()
        val archiveName = archiveFile.name
        if (!archiveFile.exists() || !archiveFile.isFile) {
            emit(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Archive file not found",
                    detailMessage = "File does not exist: ${archiveFile.absolutePath}"
                )
            )
            return@flow
        }

        val initialFingerprint = captureSourceFingerprint(archiveFile)
        if (initialFingerprint == null || initialFingerprint.fileSizeBytes <= 0L) {
            emit(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Archive is empty or unreadable",
                    detailMessage = "Cannot read source archive (${archiveFile.length()} bytes)."
                )
            )
            return@flow
        }

        try {
            RandomAccessFile(archiveFile, "r").use { raf ->
                val channel = raf.channel
                val format = ZipParser.detectFormat(archiveName, channel)
                verifyChannelInternal(
                    channel = channel,
                    openRawInputStream = { FileInputStream(archiveFile) },
                    archiveName = archiveName,
                    format = format,
                    level = level,
                    password = password,
                    initialFingerprint = initialFingerprint,
                    recheckFingerprint = {
                        hasSourceChangedSinceVerification(initialFingerprint, archiveFile = archiveFile)
                    },
                    context = context,
                    startTime = startTime,
                    emitState = { emit(it) }
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            emit(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Verification failed",
                    detailMessage = t.localizedMessage ?: t.javaClass.simpleName,
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Verifies a SAF [Uri] archive strictly in read-only mode.
     */
    fun verifySafUri(
        context: Context,
        uri: Uri,
        archiveName: String,
        level: VerificationLevel,
        password: String? = null
    ): Flow<ArchiveVerificationState> = flow {
        val startTime = System.currentTimeMillis()
        val initialFingerprint = captureSourceFingerprint(context, uri)
        if (initialFingerprint == null || initialFingerprint.fileSizeBytes <= 0L) {
            emit(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "SAF archive is empty or unreadable",
                    detailMessage = "Unable to open read descriptor for $archiveName."
                )
            )
            return@flow
        }

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val format = ZipParser.detectFormat(archiveName, channel)
                    verifyChannelInternal(
                        channel = channel,
                        openRawInputStream = {
                            context.contentResolver.openInputStream(uri)
                                ?: throw IllegalStateException("Cannot open input stream for $uri")
                        },
                        archiveName = archiveName,
                        format = format,
                        level = level,
                        password = password,
                        initialFingerprint = initialFingerprint,
                        recheckFingerprint = {
                            hasSourceChangedSinceVerification(initialFingerprint, context = context, safUri = uri)
                        },
                        context = context,
                        startTime = startTime,
                        emitState = { emit(it) }
                    )
                }
            } ?: emit(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Unable to open SAF descriptor",
                    detailMessage = "Provider returned null file descriptor for $archiveName."
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            emit(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Verification failed",
                    detailMessage = t.localizedMessage ?: t.javaClass.simpleName,
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun verifyChannelInternal(
        channel: FileChannel,
        openRawInputStream: () -> InputStream,
        archiveName: String,
        format: ArchiveFormat,
        level: VerificationLevel,
        password: String?,
        initialFingerprint: ArchiveSourceFingerprint,
        recheckFingerprint: () -> Boolean,
        context: Context?,
        startTime: Long,
        emitState: suspend (ArchiveVerificationState) -> Unit
    ) {
        val fileLen = channel.size()
        emitState(
            ArchiveVerificationState(
                archiveName = archiveName,
                level = level,
                status = VerificationStatus.RUNNING,
                summaryMessage = if (level == VerificationLevel.QUICK_CHECK) {
                    "Checking ${format.label} structure & headers..."
                } else {
                    "Verifying ${format.label} payload streams & CRC32..."
                },
                sourceFingerprint = initialFingerprint
            )
        )

        when (format) {
            ArchiveFormat.RAR -> {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.UNSUPPORTED,
                        summaryMessage = "RAR verification is not supported",
                        detailMessage = "Proprietary RAR archives cannot be verified before extraction.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
            }

            ArchiveFormat.SPLIT_ZIP -> {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.UNSUPPORTED,
                        summaryMessage = "Unmerged split ZIP volume",
                        detailMessage = "Select the .zip.001 primary volume in local storage to reassemble before verifying.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
            }

            ArchiveFormat.ZIP, ArchiveFormat.APK -> {
                verifyZipChannel(
                    channel = channel,
                    fileLen = fileLen,
                    archiveName = archiveName,
                    level = level,
                    password = password,
                    initialFingerprint = initialFingerprint,
                    recheckFingerprint = recheckFingerprint,
                    startTime = startTime,
                    emitState = emitState
                )
            }

            ArchiveFormat.TAR -> {
                verifyTarChannel(
                    channel = channel,
                    fileLen = fileLen,
                    archiveName = archiveName,
                    level = level,
                    initialFingerprint = initialFingerprint,
                    recheckFingerprint = recheckFingerprint,
                    startTime = startTime,
                    emitState = emitState
                )
            }

            ArchiveFormat.TAR_GZ -> {
                verifyTarGzStream(
                    openRawInputStream = openRawInputStream,
                    fileLen = fileLen,
                    archiveName = archiveName,
                    level = level,
                    initialFingerprint = initialFingerprint,
                    recheckFingerprint = recheckFingerprint,
                    startTime = startTime,
                    emitState = emitState
                )
            }

            ArchiveFormat.SEVEN_Z -> {
                verifySevenZChannel(
                    channel = channel,
                    fileLen = fileLen,
                    archiveName = archiveName,
                    level = level,
                    password = password,
                    initialFingerprint = initialFingerprint,
                    recheckFingerprint = recheckFingerprint,
                    context = context,
                    startTime = startTime,
                    emitState = emitState
                )
            }
        }
    }

    private suspend fun verifyZipChannel(
        channel: FileChannel,
        fileLen: Long,
        archiveName: String,
        level: VerificationLevel,
        password: String?,
        initialFingerprint: ArchiveSourceFingerprint,
        recheckFingerprint: () -> Boolean,
        startTime: Long,
        emitState: suspend (ArchiveVerificationState) -> Unit
    ) {
        if (fileLen < 22L) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Corrupted ZIP archive",
                    detailMessage = "File is smaller than the minimum 22-byte EOCD record ($fileLen bytes).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        // Verify EOCD & optional ZIP64 EOCD structure explicitly
        val eocdError = validateZipEocdAndZip64Structure(channel, fileLen)
        if (eocdError != null) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Corrupted ZIP central directory",
                    detailMessage = eocdError,
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val entries = ZipParser.parseZipChannel(channel)
        if (entries.isEmpty()) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "No valid ZIP entries found",
                    detailMessage = "Central Directory is empty or unreadable.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val fileEntries = entries.filter { !it.isDirectory }.sortedBy { it.localHeaderOffset }
        val totalFiles = fileEntries.size
        val totalUncompressedBytes = fileEntries.sumOf { it.uncompressedSize.coerceAtLeast(0L) }

        val localHeaderBytes = ByteArray(30)
        val localHeaderBuf = ByteBuffer.wrap(localHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)

        var structuralChecked = 0
        var crcVerified = 0
        var unsupportedCount = 0
        var firstUnsupportedDetail: String? = null
        var previousSpanEnd = 0L
        var processedBytes = 0L
        var lastEmitTimeMs = 0L

        for ((index, entry) in fileEntries.withIndex()) {
            if (!coroutineContext.isActive) {
                throw CancellationException("Archive verification cancelled")
            }

            val entryOffset = entry.localHeaderOffset
            if (entryOffset < 0L || entryOffset + 30L > fileLen) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        totalEntries = totalFiles,
                        processedEntries = index,
                        totalBytes = totalUncompressedBytes,
                        processedBytes = processedBytes,
                        corruptedEntryPath = entry.path,
                        summaryMessage = "Header offset out of bounds",
                        detailMessage = "Entry '${entry.name}' points to invalid offset $entryOffset (archive size $fileLen).",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            if (index > 0 && entryOffset < previousSpanEnd) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        totalEntries = totalFiles,
                        processedEntries = index,
                        totalBytes = totalUncompressedBytes,
                        processedBytes = processedBytes,
                        corruptedEntryPath = entry.path,
                        summaryMessage = "Overlapping ZIP entry spans detected",
                        detailMessage = "Entry '${entry.name}' at offset $entryOffset overlaps previous entry ending at $previousSpanEnd.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            localHeaderBuf.clear()
            readFullyPositional(channel, localHeaderBuf, entryOffset)
            localHeaderBuf.flip()
            if (localHeaderBuf.remaining() < 30) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        totalEntries = totalFiles,
                        processedEntries = index,
                        totalBytes = totalUncompressedBytes,
                        processedBytes = processedBytes,
                        corruptedEntryPath = entry.path,
                        summaryMessage = "Truncated Local File Header",
                        detailMessage = "Could not read 30-byte Local File Header for '${entry.name}' at offset $entryOffset.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            val lfhSig = localHeaderBuf.int
            if (lfhSig != LOCAL_HEADER_SIGNATURE) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        totalEntries = totalFiles,
                        processedEntries = index,
                        totalBytes = totalUncompressedBytes,
                        processedBytes = processedBytes,
                        corruptedEntryPath = entry.path,
                        summaryMessage = "Invalid Local File Header signature",
                        detailMessage = "Entry '${entry.name}' at offset $entryOffset has corrupt signature 0x${String.format("%08X", lfhSig)}.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            localHeaderBuf.position(26)
            val fnLen = localHeaderBuf.short.toInt() and 0xFFFF
            val efLen = localHeaderBuf.short.toInt() and 0xFFFF
            var payloadOffset = entryOffset + 30L + fnLen.toLong() + efLen.toLong()
            var compressedPayloadSize = entry.compressedSize

            if (compressedPayloadSize < 0L || entry.uncompressedSize < 0L || payloadOffset + compressedPayloadSize > fileLen) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        totalEntries = totalFiles,
                        processedEntries = index,
                        totalBytes = totalUncompressedBytes,
                        processedBytes = processedBytes,
                        corruptedEntryPath = entry.path,
                        summaryMessage = "Truncated entry payload",
                        detailMessage = "Entry '${entry.name}' payload ($payloadOffset + $compressedPayloadSize) exceeds archive size ($fileLen).",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            previousSpanEnd = payloadOffset + compressedPayloadSize

            val isSupportedMethod = entry.compressionMethod == 0 || entry.compressionMethod == 8
            if (!isSupportedMethod) {
                unsupportedCount++
                if (firstUnsupportedDetail == null) {
                    firstUnsupportedDetail = "Entry '${entry.name}' uses unsupported ZIP compression method (${entry.compressionMethod})."
                }
                if (level == VerificationLevel.FULL_VERIFY) {
                    emitState(
                        ArchiveVerificationState(
                            archiveName = archiveName,
                            level = level,
                            status = VerificationStatus.UNSUPPORTED,
                            totalEntries = totalFiles,
                            processedEntries = index,
                            totalBytes = totalUncompressedBytes,
                            processedBytes = processedBytes,
                            skippedOrUnsupportedEntries = unsupportedCount,
                            corruptedEntryPath = entry.path,
                            summaryMessage = "Unsupported compression method (${entry.compressionMethod})",
                            detailMessage = firstUnsupportedDetail,
                            sourceFingerprint = initialFingerprint,
                            elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                        )
                    )
                    return
                }
            }

            var cipher: ZipCryptoCipher? = null
            if (entry.isEncrypted) {
                if (entry.compressionMethod == 99) {
                    unsupportedCount++
                    val aesMsg = "Entry '${entry.name}' uses WinZip AES-99 encryption, which is not supported for verification."
                    if (firstUnsupportedDetail == null) firstUnsupportedDetail = aesMsg
                    if (level == VerificationLevel.FULL_VERIFY) {
                        emitState(
                            ArchiveVerificationState(
                                archiveName = archiveName,
                                level = level,
                                status = VerificationStatus.UNSUPPORTED,
                                totalEntries = totalFiles,
                                processedEntries = index,
                                totalBytes = totalUncompressedBytes,
                                processedBytes = processedBytes,
                                skippedOrUnsupportedEntries = unsupportedCount,
                                corruptedEntryPath = entry.path,
                                summaryMessage = "AES-99 encryption verification unsupported",
                                detailMessage = aesMsg,
                                sourceFingerprint = initialFingerprint,
                                elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                            )
                        )
                        return
                    }
                } else if (password.isNullOrEmpty()) {
                    unsupportedCount++
                    val pwdMsg = "Entry '${entry.name}' is password-protected. Enter the archive password to verify encrypted payloads."
                    if (firstUnsupportedDetail == null) firstUnsupportedDetail = pwdMsg
                    if (level == VerificationLevel.FULL_VERIFY) {
                        emitState(
                            ArchiveVerificationState(
                                archiveName = archiveName,
                                level = level,
                                status = VerificationStatus.UNSUPPORTED,
                                totalEntries = totalFiles,
                                processedEntries = index,
                                totalBytes = totalUncompressedBytes,
                                processedBytes = processedBytes,
                                skippedOrUnsupportedEntries = unsupportedCount,
                                corruptedEntryPath = entry.path,
                                summaryMessage = "Password required for full verification",
                                detailMessage = pwdMsg,
                                sourceFingerprint = initialFingerprint,
                                elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                            )
                        )
                        return
                    }
                } else {
                    if (compressedPayloadSize < 12L) {
                        emitState(
                            ArchiveVerificationState(
                                archiveName = archiveName,
                                level = level,
                                status = VerificationStatus.FAILED,
                                totalEntries = totalFiles,
                                processedEntries = index,
                                totalBytes = totalUncompressedBytes,
                                processedBytes = processedBytes,
                                corruptedEntryPath = entry.path,
                                summaryMessage = "Truncated ZipCrypto encryption header",
                                detailMessage = "Encrypted entry '${entry.name}' is smaller than 12 bytes.",
                                sourceFingerprint = initialFingerprint,
                                elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                            )
                        )
                        return
                    }
                    val testCipher = ZipCryptoCipher(password)
                    val encHeader = ByteArray(12)
                    readFullyPositional(channel, ByteBuffer.wrap(encHeader), payloadOffset)
                    if (!testCipher.decryptHeaderAndVerify(encHeader, entry.crc32)) {
                        emitState(
                            ArchiveVerificationState(
                                archiveName = archiveName,
                                level = level,
                                status = VerificationStatus.FAILED,
                                totalEntries = totalFiles,
                                processedEntries = index,
                                totalBytes = totalUncompressedBytes,
                                processedBytes = processedBytes,
                                corruptedEntryPath = entry.path,
                                summaryMessage = "Incorrect password or corrupted header",
                                detailMessage = "Password check failed for encrypted entry '${entry.name}'.",
                                sourceFingerprint = initialFingerprint,
                                elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                            )
                        )
                        return
                    }
                    cipher = testCipher
                    payloadOffset += 12L
                    compressedPayloadSize -= 12L
                }
            }

            structuralChecked++

            if (level == VerificationLevel.FULL_VERIFY) {
                if (entry.uncompressedSize > 0L && entry.crc32 == 0L) {
                    emitState(
                        ArchiveVerificationState(
                            archiveName = archiveName,
                            level = level,
                            status = VerificationStatus.UNSUPPORTED,
                            totalEntries = totalFiles,
                            processedEntries = index,
                            totalBytes = totalUncompressedBytes,
                            processedBytes = processedBytes,
                            skippedOrUnsupportedEntries = 1,
                            corruptedEntryPath = entry.path,
                            summaryMessage = "Missing CRC32 checksum in archive metadata",
                            detailMessage = "Entry '${entry.name}' has 0x00000000 CRC32 for ${entry.uncompressedSize} bytes; payload integrity cannot be verified.",
                            sourceFingerprint = initialFingerprint,
                            elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                        )
                    )
                    return
                }

                val ioBuffer = CommonsCompressBufferStrategy.borrowIoBuffer(
                    entry.uncompressedSize.coerceAtLeast(fileLen)
                )
                val ioByteBuf = ByteBuffer.wrap(ioBuffer)
                val crc = CRC32()
                var entryDecompressedBytes = 0L
                var currentReadPos = payloadOffset

                if (entry.compressionMethod == 0) {
                    var remaining = entry.uncompressedSize
                    while (remaining > 0L) {
                        if (!coroutineContext.isActive) {
                            throw CancellationException("Archive verification cancelled")
                        }
                        val toRead = remaining.coerceAtMost(ioBuffer.size.toLong()).toInt()
                        ioByteBuf.clear()
                        ioByteBuf.limit(toRead)
                        val read = readChannelPositional(channel, ioByteBuf, currentReadPos)
                        if (read <= 0) break
                        currentReadPos += read
                        cipher?.decryptBytesInPlace(ioBuffer, 0, read)
                        crc.update(ioBuffer, 0, read)
                        remaining -= read
                        entryDecompressedBytes += read
                        processedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTimeMs >= 120L) {
                            lastEmitTimeMs = now
                            emitState(
                                ArchiveVerificationState(
                                    archiveName = archiveName,
                                    level = level,
                                    status = VerificationStatus.RUNNING,
                                    totalEntries = totalFiles,
                                    processedEntries = index,
                                    totalBytes = totalUncompressedBytes,
                                    processedBytes = processedBytes,
                                    currentEntryName = entry.name,
                                    crcVerifiedEntries = crcVerified,
                                    structuralCheckedEntries = structuralChecked,
                                    summaryMessage = "Verifying ${entry.name}...",
                                    sourceFingerprint = initialFingerprint,
                                    elapsedTimeMs = (now - startTime).coerceAtLeast(0L)
                                )
                            )
                        }
                    }
                } else {
                    val inflateOutBuffer = CommonsCompressBufferStrategy.borrowInflateBuffer(
                        entry.uncompressedSize.coerceAtLeast(fileLen)
                    )
                    val inflater = Inflater(true)
                    var compressedRemaining = compressedPayloadSize
                    try {
                        while (compressedRemaining > 0L || !inflater.finished()) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Archive verification cancelled")
                            }
                            if (inflater.needsInput() && compressedRemaining > 0L) {
                                val toRead = compressedRemaining.coerceAtMost(ioBuffer.size.toLong()).toInt()
                                ioByteBuf.clear()
                                ioByteBuf.limit(toRead)
                                val read = readChannelPositional(channel, ioByteBuf, currentReadPos)
                                if (read <= 0) break
                                currentReadPos += read
                                cipher?.decryptBytesInPlace(ioBuffer, 0, read)
                                inflater.setInput(ioBuffer, 0, read)
                                compressedRemaining -= read
                            }
                            val decompressed = try {
                                inflater.inflate(inflateOutBuffer)
                            } catch (t: Throwable) {
                                emitState(
                                    ArchiveVerificationState(
                                        archiveName = archiveName,
                                        level = level,
                                        status = VerificationStatus.FAILED,
                                        totalEntries = totalFiles,
                                        processedEntries = index,
                                        totalBytes = totalUncompressedBytes,
                                        processedBytes = processedBytes,
                                        corruptedEntryPath = entry.path,
                                        summaryMessage = "Corrupted DEFLATE stream in '${entry.name}'",
                                        detailMessage = "Decompression failed: ${t.localizedMessage ?: t.javaClass.simpleName}",
                                        sourceFingerprint = initialFingerprint,
                                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                                    )
                                )
                                return
                            }
                            if (decompressed > 0) {
                                crc.update(inflateOutBuffer, 0, decompressed)
                                entryDecompressedBytes += decompressed
                                processedBytes += decompressed

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTimeMs >= 120L) {
                                    lastEmitTimeMs = now
                                    emitState(
                                        ArchiveVerificationState(
                                            archiveName = archiveName,
                                            level = level,
                                            status = VerificationStatus.RUNNING,
                                            totalEntries = totalFiles,
                                            processedEntries = index,
                                            totalBytes = totalUncompressedBytes,
                                            processedBytes = processedBytes,
                                            currentEntryName = entry.name,
                                            crcVerifiedEntries = crcVerified,
                                            structuralCheckedEntries = structuralChecked,
                                            summaryMessage = "Verifying ${entry.name}...",
                                            sourceFingerprint = initialFingerprint,
                                            elapsedTimeMs = (now - startTime).coerceAtLeast(0L)
                                        )
                                    )
                                }
                            } else if (inflater.needsInput() && compressedRemaining <= 0L) {
                                break
                            } else if (inflater.needsDictionary()) {
                                break
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                }

                val calculatedCrc = crc.value
                val isSizeMatch = entryDecompressedBytes == entry.uncompressedSize
                val isCrcMatch = isSizeMatch && (entry.uncompressedSize == 0L || calculatedCrc == entry.crc32)
                if (!isCrcMatch) {
                    emitState(
                        ArchiveVerificationState(
                            archiveName = archiveName,
                            level = level,
                            status = VerificationStatus.FAILED,
                            totalEntries = totalFiles,
                            processedEntries = index,
                            totalBytes = totalUncompressedBytes,
                            processedBytes = processedBytes,
                            corruptedEntryPath = entry.path,
                            summaryMessage = "CRC32 integrity mismatch in '${entry.name}'",
                            detailMessage = "Expected ${entry.crcHex} (${entry.uncompressedSize} B), computed 0x${String.format("%08X", calculatedCrc)} ($entryDecompressedBytes B).",
                            sourceFingerprint = initialFingerprint,
                            elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                        )
                    )
                    return
                }
                crcVerified++
            } else {
                val now = System.currentTimeMillis()
                if (now - lastEmitTimeMs >= 120L) {
                    lastEmitTimeMs = now
                    emitState(
                        ArchiveVerificationState(
                            archiveName = archiveName,
                            level = level,
                            status = VerificationStatus.RUNNING,
                            totalEntries = totalFiles,
                            processedEntries = index + 1,
                            totalBytes = totalUncompressedBytes,
                            currentEntryName = entry.name,
                            structuralCheckedEntries = structuralChecked,
                            summaryMessage = "Checking headers (${index + 1}/$totalFiles)...",
                            sourceFingerprint = initialFingerprint,
                            elapsedTimeMs = (now - startTime).coerceAtLeast(0L)
                        )
                    )
                }
            }
        }

        // Verify source was not mutated while verification ran
        if (recheckFingerprint()) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    totalEntries = totalFiles,
                    processedEntries = totalFiles,
                    totalBytes = totalUncompressedBytes,
                    processedBytes = processedBytes,
                    summaryMessage = "Source archive changed during verification",
                    detailMessage = "The archive was modified on disk while verification was in progress. Please re-verify.",
                    sourceFingerprint = initialFingerprint,
                    isSourceModifiedSinceVerify = true,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        if (level == VerificationLevel.QUICK_CHECK) {
            if (unsupportedCount > 0) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.UNSUPPORTED,
                        totalEntries = totalFiles,
                        processedEntries = totalFiles,
                        totalBytes = totalUncompressedBytes,
                        structuralCheckedEntries = structuralChecked,
                        skippedOrUnsupportedEntries = unsupportedCount,
                        summaryMessage = "Headers valid, but $unsupportedCount entry(s) need password or unsupported method",
                        detailMessage = firstUnsupportedDetail,
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = elapsed
                    )
                )
            } else {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.PASSED_STRUCTURAL,
                        totalEntries = totalFiles,
                        processedEntries = totalFiles,
                        totalBytes = totalUncompressedBytes,
                        structuralCheckedEntries = structuralChecked,
                        summaryMessage = "Structural check passed ($totalFiles files)",
                        detailMessage = "Central Directory, ZIP/ZIP64 EOCD & all $totalFiles Local File Headers verified. Payloads were not decompressed.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = elapsed
                    )
                )
            }
        } else {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.PASSED_FULL_INTEGRITY,
                    totalEntries = totalFiles,
                    processedEntries = totalFiles,
                    totalBytes = totalUncompressedBytes,
                    processedBytes = totalUncompressedBytes,
                    crcVerifiedEntries = crcVerified,
                    structuralCheckedEntries = structuralChecked,
                    summaryMessage = "Full payload & CRC32 integrity verified ($crcVerified files)",
                    detailMessage = "Stream-decompressed and verified CRC32 checksums for all $crcVerified file(s) without modifying the source archive.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = elapsed
                )
            )
        }
    }

    internal fun validateZipEocdAndZip64Structure(channel: FileChannel, fileLen: Long): String? {
        val maxCommentLen = 65535L
        val searchLen = (maxCommentLen + 22L + 20L).coerceAtMost(fileLen)
        val searchStart = (fileLen - searchLen).coerceAtLeast(0L)
        val searchBuffer = ByteArray(searchLen.toInt())
        val searchBuf = ByteBuffer.wrap(searchBuffer)
        readFullyPositional(channel, searchBuf, searchStart)

        var eocdOffset = -1L
        var centralDirOffset = -1L
        for (i in searchBuffer.size - 22 downTo 0) {
            val sig = readLeInt(searchBuffer, i)
            if (sig == EOCD_SIGNATURE) {
                val candidateEocdOffset = searchStart + i
                val candidateCdOffset = readLeInt(searchBuffer, i + 16).toLong() and 0xFFFFFFFFL
                if (candidateCdOffset < candidateEocdOffset || candidateCdOffset == UINT32_MAX) {
                    eocdOffset = candidateEocdOffset
                    centralDirOffset = candidateCdOffset
                    break
                }
            }
        }
        if (eocdOffset == -1L) {
            return "Missing End of Central Directory (EOCD) signature (archive may be truncated)."
        }

        if (eocdOffset >= 20L) {
            val locBytes = ByteArray(20)
            readFullyPositional(channel, ByteBuffer.wrap(locBytes), eocdOffset - 20L)
            val locBuf = ByteBuffer.wrap(locBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (locBuf.int == ZIP64_EOCD_LOCATOR_SIGNATURE) {
                locBuf.position(8)
                val zip64EocdOffset = locBuf.long
                if (zip64EocdOffset < 0L || zip64EocdOffset + 56L > fileLen) {
                    return "ZIP64 EOCD Locator points to out-of-bounds offset ($zip64EocdOffset)."
                }
                val recBytes = ByteArray(56)
                readFullyPositional(channel, ByteBuffer.wrap(recBytes), zip64EocdOffset)
                val recBuf = ByteBuffer.wrap(recBytes).order(ByteOrder.LITTLE_ENDIAN)
                if (recBuf.int != ZIP64_EOCD_RECORD_SIGNATURE) {
                    return "Corrupted ZIP64 EOCD Record signature at offset $zip64EocdOffset."
                }
                recBuf.position(48)
                centralDirOffset = recBuf.long
            } else if (centralDirOffset == UINT32_MAX) {
                return "ZIP64 Central Directory sentinel (0xFFFFFFFF) present without valid ZIP64 EOCD Locator."
            }
        }

        if (centralDirOffset < 0L || centralDirOffset >= fileLen) {
            return "Central Directory offset ($centralDirOffset) is outside archive bounds ($fileLen)."
        }
        return null
    }

    private suspend fun verifyTarChannel(
        channel: FileChannel,
        fileLen: Long,
        archiveName: String,
        level: VerificationLevel,
        initialFingerprint: ArchiveSourceFingerprint,
        recheckFingerprint: () -> Boolean,
        startTime: Long,
        emitState: suspend (ArchiveVerificationState) -> Unit
    ) {
        if (fileLen < 512L) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Corrupted TAR archive",
                    detailMessage = "TAR file is smaller than a single 512-byte UStar header block ($fileLen bytes).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val block = ByteArray(512)
        val buf = ByteBuffer.wrap(block)
        var pos = 0L
        var checkedHeaders = 0
        var totalPayloadBytes = 0L

        while (pos + 512L <= fileLen) {
            if (!coroutineContext.isActive) {
                throw CancellationException("Archive verification cancelled")
            }
            buf.clear()
            readFullyPositional(channel, buf, pos)
            if (buf.position() < 512) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        structuralCheckedEntries = checkedHeaders,
                        summaryMessage = "Truncated TAR header at offset $pos",
                        detailMessage = "Unexpected EOF while reading 512-byte TAR header block.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            if (block.all { it == 0.toByte() }) {
                break
            }

            if (!KaiHatchEngine.verifyTarHeaderChecksum(block)) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        structuralCheckedEntries = checkedHeaders,
                        summaryMessage = "Corrupted TAR header checksum",
                        detailMessage = "512-byte UStar header checksum failed at offset $pos.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            val sizeOctal = String(block, 124, 12, Charsets.US_ASCII).replace("\u0000", "").trim()
            val entrySize = sizeOctal.toLongOrNull(8) ?: -1L
            if (entrySize < 0L) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        structuralCheckedEntries = checkedHeaders,
                        summaryMessage = "Invalid TAR octal entry size",
                        detailMessage = "Header at offset $pos contains invalid octal size '$sizeOctal'.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            val dataBlocks = (entrySize + 511L) / 512L
            val nextPos = pos + 512L + (dataBlocks * 512L)
            if (nextPos > fileLen) {
                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.FAILED,
                        structuralCheckedEntries = checkedHeaders,
                        summaryMessage = "Truncated TAR entry payload",
                        detailMessage = "Entry at offset $pos requires ${dataBlocks * 512L} payload bytes, exceeding archive size ($fileLen).",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
                return
            }

            checkedHeaders++
            totalPayloadBytes += entrySize
            pos = nextPos
        }

        if (checkedHeaders == 0) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Empty or unrecognized TAR archive",
                    detailMessage = "No valid 512-byte UStar headers found.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        if (recheckFingerprint()) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Source archive changed during verification",
                    detailMessage = "The TAR archive was modified on disk while verification was running.",
                    sourceFingerprint = initialFingerprint,
                    isSourceModifiedSinceVerify = true,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        if (level == VerificationLevel.QUICK_CHECK) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.PASSED_STRUCTURAL,
                    totalEntries = checkedHeaders,
                    processedEntries = checkedHeaders,
                    totalBytes = totalPayloadBytes,
                    structuralCheckedEntries = checkedHeaders,
                    summaryMessage = "TAR header & block structure verified ($checkedHeaders entries)",
                    detailMessage = "All 512-byte UStar header checksums and block boundaries are valid.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = elapsed
                )
            )
        } else {
            // Standard TAR format stores header checksums only and does NOT contain per-file payload CRC32s.
            // Never report false payload-integrity success.
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.UNSUPPORTED,
                    totalEntries = checkedHeaders,
                    processedEntries = checkedHeaders,
                    totalBytes = totalPayloadBytes,
                    structuralCheckedEntries = checkedHeaders,
                    skippedOrUnsupportedEntries = checkedHeaders,
                    summaryMessage = "TAR format lacks per-file payload CRC32 checksums",
                    detailMessage = "All $checkedHeaders UStar header checksums and block spans are valid, but uncompressed TAR archives do not store payload checksums for full integrity verification.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = elapsed
                )
            )
        }
    }

    private suspend fun verifyTarGzStream(
        openRawInputStream: () -> InputStream,
        fileLen: Long,
        archiveName: String,
        level: VerificationLevel,
        initialFingerprint: ArchiveSourceFingerprint,
        recheckFingerprint: () -> Boolean,
        startTime: Long,
        emitState: suspend (ArchiveVerificationState) -> Unit
    ) {
        if (fileLen < 18L) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Corrupted GZIP archive",
                    detailMessage = "File is smaller than minimum 18-byte GZIP header + trailer ($fileLen bytes).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        var checkedEntries = 0
        var totalPayloadBytes = 0L
        val block = ByteArray(512)
        val drainBuf = CommonsCompressBufferStrategy.borrowIoBuffer(fileLen)
        var lastEmitTimeMs = 0L

        try {
            openRawInputStream().use { rawIn ->
                val bufferedIn = CommonsCompressBufferStrategy.wrapBufferedInput(rawIn, fileLen)
                GzipCompressorInputStream(bufferedIn, true).use { gzIn ->
                    while (true) {
                        if (!coroutineContext.isActive) {
                            throw CancellationException("Archive verification cancelled")
                        }
                        var readTotal = 0
                        while (readTotal < 512) {
                            val r = gzIn.read(block, readTotal, 512 - readTotal)
                            if (r <= 0) break
                            readTotal += r
                        }
                        if (readTotal == 0) break
                        if (readTotal < 512) {
                            emitState(
                                ArchiveVerificationState(
                                    archiveName = archiveName,
                                    level = level,
                                    status = VerificationStatus.FAILED,
                                    structuralCheckedEntries = checkedEntries,
                                    summaryMessage = "Truncated TAR header inside GZIP stream",
                                    detailMessage = "Expected 512-byte TAR block, got $readTotal bytes.",
                                    sourceFingerprint = initialFingerprint,
                                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                                )
                            )
                            return
                        }

                        if (block.all { it == 0.toByte() }) {
                            if (level == VerificationLevel.QUICK_CHECK && checkedEntries > 0) {
                                break
                            }
                            continue
                        }

                        if (!KaiHatchEngine.verifyTarHeaderChecksum(block)) {
                            emitState(
                                ArchiveVerificationState(
                                    archiveName = archiveName,
                                    level = level,
                                    status = VerificationStatus.FAILED,
                                    structuralCheckedEntries = checkedEntries,
                                    summaryMessage = "Corrupted TAR header checksum in GZIP stream",
                                    detailMessage = "UStar header checksum failed at entry #${checkedEntries + 1}.",
                                    sourceFingerprint = initialFingerprint,
                                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                                )
                            )
                            return
                        }

                        val entryName = String(block, 0, 100, Charsets.UTF_8).replace("\u0000", "").trim()
                        val sizeOctal = String(block, 124, 12, Charsets.US_ASCII).replace("\u0000", "").trim()
                        val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
                        val paddedSize = ((entrySize + 511L) / 512L) * 512L

                        var remaining = paddedSize
                        while (remaining > 0L) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Archive verification cancelled")
                            }
                            val toRead = remaining.coerceAtMost(drainBuf.size.toLong()).toInt()
                            val r = gzIn.read(drainBuf, 0, toRead)
                            if (r <= 0) {
                                emitState(
                                    ArchiveVerificationState(
                                        archiveName = archiveName,
                                        level = level,
                                        status = VerificationStatus.FAILED,
                                        corruptedEntryPath = entryName,
                                        summaryMessage = "Unexpected EOF in TAR.GZ payload",
                                        detailMessage = "Stream ended with $remaining bytes remaining for '$entryName'.",
                                        sourceFingerprint = initialFingerprint,
                                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                                    )
                                )
                                return
                            }
                            remaining -= r
                        }

                        checkedEntries++
                        totalPayloadBytes += entrySize

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTimeMs >= 120L) {
                            lastEmitTimeMs = now
                            emitState(
                                ArchiveVerificationState(
                                    archiveName = archiveName,
                                    level = level,
                                    status = VerificationStatus.RUNNING,
                                    processedEntries = checkedEntries,
                                    processedBytes = totalPayloadBytes,
                                    currentEntryName = entryName.substringAfterLast('/'),
                                    structuralCheckedEntries = checkedEntries,
                                    summaryMessage = "Verifying $entryName...",
                                    sourceFingerprint = initialFingerprint,
                                    elapsedTimeMs = (now - startTime).coerceAtLeast(0L)
                                )
                            )
                        }
                    }

                    if (level == VerificationLevel.FULL_VERIFY) {
                        // Drain remaining trailer padding so GzipCompressorInputStream verifies the GZIP CRC32 trailer
                        while (gzIn.read(drainBuf) > 0) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Archive verification cancelled")
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    structuralCheckedEntries = checkedEntries,
                    summaryMessage = "Corrupted TAR.GZ stream or GZIP CRC32 mismatch",
                    detailMessage = t.localizedMessage ?: t.javaClass.simpleName,
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        if (recheckFingerprint()) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Source archive changed during verification",
                    detailMessage = "The TAR.GZ archive was modified on disk while verification was running.",
                    sourceFingerprint = initialFingerprint,
                    isSourceModifiedSinceVerify = true,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        if (level == VerificationLevel.QUICK_CHECK) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.PASSED_STRUCTURAL,
                    totalEntries = checkedEntries,
                    processedEntries = checkedEntries,
                    totalBytes = totalPayloadBytes,
                    structuralCheckedEntries = checkedEntries,
                    summaryMessage = "TAR.GZ headers verified ($checkedEntries entries)",
                    detailMessage = "GZIP header and TAR UStar entry checksums verified.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = elapsed
                )
            )
        } else {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.PASSED_FULL_INTEGRITY,
                    totalEntries = checkedEntries,
                    processedEntries = checkedEntries,
                    totalBytes = totalPayloadBytes,
                    processedBytes = totalPayloadBytes,
                    crcVerifiedEntries = checkedEntries,
                    structuralCheckedEntries = checkedEntries,
                    summaryMessage = "Full GZIP CRC32 & TAR integrity verified ($checkedEntries entries)",
                    detailMessage = "Full GZIP stream decompressed, trailer CRC32 verified, and all $checkedEntries TAR blocks validated.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = elapsed
                )
            )
        }
    }

    private suspend fun verifySevenZChannel(
        channel: FileChannel,
        fileLen: Long,
        archiveName: String,
        level: VerificationLevel,
        password: String?,
        initialFingerprint: ArchiveSourceFingerprint,
        recheckFingerprint: () -> Boolean,
        context: Context?,
        startTime: Long,
        emitState: suspend (ArchiveVerificationState) -> Unit
    ) {
        if (fileLen < 32L) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Corrupted 7z archive",
                    detailMessage = "File is smaller than the 32-byte 7z signature header ($fileLen bytes).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val sigHeader = ByteArray(32)
        readFullyPositional(channel, ByteBuffer.wrap(sigHeader), 0L)
        val expectedMagic = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        if (!sigHeader.take(6).toByteArray().contentEquals(expectedMagic)) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Invalid 7z signature header",
                    detailMessage = "File does not begin with 7z magic bytes (37 7A BC AF 27 1C).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val sigBuf = ByteBuffer.wrap(sigHeader).order(ByteOrder.LITTLE_ENDIAN)
        sigBuf.position(12)
        val nextHeaderOffset = sigBuf.long
        val nextHeaderSize = sigBuf.long
        if (nextHeaderOffset < 0L || nextHeaderSize < 0L || 32L + nextHeaderOffset + nextHeaderSize > fileLen) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "Truncated 7z NextHeader",
                    detailMessage = "7z NextHeader at offset ${32L + nextHeaderOffset} (+$nextHeaderSize B) exceeds archive size ($fileLen B).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val isHeaderEncrypted = ZipParser.detectSevenZHeaderEncryption(channel)
        if (isHeaderEncrypted && password.isNullOrEmpty()) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.UNSUPPORTED,
                    summaryMessage = "Password required for encrypted 7z archive",
                    detailMessage = "7z header is AES-256 encrypted. Enter the archive password to verify.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
            return
        }

        val budget = CommonsCompressBufferStrategy.prepareHeapForSevenZExtraction(
            context = context,
            includeTruncatingChannelReserve = false
        )

        try {
            channel.position(0L)
            CommonsCompressBufferStrategy.openBufferedSevenZFile(
                channel = channel,
                archiveName = archiveName,
                password = password,
                maxMemoryLimitKb = budget.safeDecoderLimitKb,
                closeChannelOnClose = false,
                tryRecover = false
            ).use { sevenZ ->
                val allEntries = sevenZ.entries.filter { !it.isDirectory }
                val totalFiles = allEntries.size
                val totalUncompressedBytes = allEntries.sumOf { it.size.coerceAtLeast(0L) }

                if (level == VerificationLevel.QUICK_CHECK) {
                    if (recheckFingerprint()) {
                        emitState(
                            ArchiveVerificationState(
                                archiveName = archiveName,
                                level = level,
                                status = VerificationStatus.FAILED,
                                summaryMessage = "Source archive changed during verification",
                                detailMessage = "The 7z archive was modified on disk while verification was running.",
                                sourceFingerprint = initialFingerprint,
                                isSourceModifiedSinceVerify = true,
                                elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                            )
                        )
                        return
                    }
                    emitState(
                        ArchiveVerificationState(
                            archiveName = archiveName,
                            level = level,
                            status = VerificationStatus.PASSED_STRUCTURAL,
                            totalEntries = totalFiles,
                            processedEntries = totalFiles,
                            totalBytes = totalUncompressedBytes,
                            structuralCheckedEntries = totalFiles,
                            summaryMessage = "7z header & entry table verified ($totalFiles files)",
                            detailMessage = "Signature header, NextHeader CRC & $totalFiles entry descriptors validated (streams not decompressed).",
                            sourceFingerprint = initialFingerprint,
                            elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                        )
                    )
                    return
                }

                // FULL_VERIFY: Stream all 7z entries and verify decompression + CRC32
                val ioBuf = CommonsCompressBufferStrategy.borrowIoBuffer(totalUncompressedBytes.coerceAtLeast(fileLen))
                var processedEntries = 0
                var crcVerified = 0
                var processedBytes = 0L
                var lastEmitTimeMs = 0L

                while (true) {
                    if (!coroutineContext.isActive) {
                        throw CancellationException("Archive verification cancelled")
                    }
                    val entry = sevenZ.nextEntry ?: break
                    if (entry.isDirectory) continue

                    val crc = CRC32()
                    var entryRead = 0L
                    while (true) {
                        if (!coroutineContext.isActive) {
                            throw CancellationException("Archive verification cancelled")
                        }
                        val r = sevenZ.read(ioBuf, 0, ioBuf.size)
                        if (r <= 0) break
                        crc.update(ioBuf, 0, r)
                        entryRead += r
                        processedBytes += r

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTimeMs >= 120L) {
                            lastEmitTimeMs = now
                            emitState(
                                ArchiveVerificationState(
                                    archiveName = archiveName,
                                    level = level,
                                    status = VerificationStatus.RUNNING,
                                    totalEntries = totalFiles,
                                    processedEntries = processedEntries,
                                    totalBytes = totalUncompressedBytes,
                                    processedBytes = processedBytes,
                                    currentEntryName = (entry.name ?: "").substringAfterLast('/'),
                                    crcVerifiedEntries = crcVerified,
                                    structuralCheckedEntries = totalFiles,
                                    summaryMessage = "Verifying ${entry.name ?: "entry"}...",
                                    sourceFingerprint = initialFingerprint,
                                    elapsedTimeMs = (now - startTime).coerceAtLeast(0L)
                                )
                            )
                        }
                    }

                    if (entry.size >= 0L && entryRead != entry.size) {
                        emitState(
                            ArchiveVerificationState(
                                archiveName = archiveName,
                                level = level,
                                status = VerificationStatus.FAILED,
                                totalEntries = totalFiles,
                                processedEntries = processedEntries,
                                totalBytes = totalUncompressedBytes,
                                processedBytes = processedBytes,
                                corruptedEntryPath = entry.name,
                                summaryMessage = "7z entry size mismatch",
                                detailMessage = "Entry '${entry.name}' expected ${entry.size} B, decompressed $entryRead B.",
                                sourceFingerprint = initialFingerprint,
                                elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                            )
                        )
                        return
                    }

                    if (entry.hasCrc) {
                        val expectedCrc = entry.crcValue and 0xFFFFFFFFL
                        if (crc.value != expectedCrc) {
                            emitState(
                                ArchiveVerificationState(
                                    archiveName = archiveName,
                                    level = level,
                                    status = VerificationStatus.FAILED,
                                    totalEntries = totalFiles,
                                    processedEntries = processedEntries,
                                    totalBytes = totalUncompressedBytes,
                                    processedBytes = processedBytes,
                                    corruptedEntryPath = entry.name,
                                    summaryMessage = "7z CRC32 mismatch in '${entry.name}'",
                                    detailMessage = "Expected 0x${String.format("%08X", expectedCrc)}, got 0x${String.format("%08X", crc.value)}.",
                                    sourceFingerprint = initialFingerprint,
                                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                                )
                            )
                            return
                        }
                        crcVerified++
                    } else if (entry.size == 0L) {
                        crcVerified++
                    }
                    processedEntries++
                }

                if (recheckFingerprint()) {
                    emitState(
                        ArchiveVerificationState(
                            archiveName = archiveName,
                            level = level,
                            status = VerificationStatus.FAILED,
                            summaryMessage = "Source archive changed during verification",
                            detailMessage = "The 7z archive was modified on disk while verification was running.",
                            sourceFingerprint = initialFingerprint,
                            isSourceModifiedSinceVerify = true,
                            elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                        )
                    )
                    return
                }

                emitState(
                    ArchiveVerificationState(
                        archiveName = archiveName,
                        level = level,
                        status = VerificationStatus.PASSED_FULL_INTEGRITY,
                        totalEntries = totalFiles,
                        processedEntries = processedEntries,
                        totalBytes = totalUncompressedBytes,
                        processedBytes = processedBytes,
                        crcVerifiedEntries = crcVerified,
                        structuralCheckedEntries = totalFiles,
                        summaryMessage = "Full 7z stream & CRC32 integrity verified ($processedEntries files)",
                        detailMessage = "Decompressed all 7z solid/non-solid blocks and verified CRC32 checksums without modifying the source archive.",
                        sourceFingerprint = initialFingerprint,
                        elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    )
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (pwdEx: PasswordRequiredException) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = if (password.isNullOrEmpty()) VerificationStatus.UNSUPPORTED else VerificationStatus.FAILED,
                    summaryMessage = if (password.isNullOrEmpty()) "Password required for 7z verification" else "Incorrect 7z password",
                    detailMessage = pwdEx.localizedMessage ?: "Encrypted 7z stream requires a valid password.",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
        } catch (memEx: MemoryLimitException) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.UNSUPPORTED,
                    summaryMessage = "7z dictionary exceeds safe device memory limit",
                    detailMessage = "Required ${memEx.memoryNeededInKb / 1024} MB exceeds safe budget (${memEx.memoryLimitInKb / 1024} MB).",
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
        } catch (t: Throwable) {
            emitState(
                ArchiveVerificationState(
                    archiveName = archiveName,
                    level = level,
                    status = VerificationStatus.FAILED,
                    summaryMessage = "7z verification failed",
                    detailMessage = t.localizedMessage ?: t.javaClass.simpleName,
                    sourceFingerprint = initialFingerprint,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                )
            )
        }
    }

    private fun readChannelPositional(channel: FileChannel, dst: ByteBuffer, position: Long): Int {
        return try {
            channel.read(dst, position)
        } catch (_: Throwable) {
            synchronized(channel) {
                channel.position(position)
                channel.read(dst)
            }
        }
    }

    private fun readFullyPositional(channel: FileChannel, dst: ByteBuffer, startPosition: Long) {
        var pos = startPosition
        while (dst.hasRemaining()) {
            val r = readChannelPositional(channel, dst, pos)
            if (r <= 0) break
            pos += r
        }
    }

    private fun readLeInt(arr: ByteArray, index: Int): Int {
        return (arr[index].toInt() and 0xFF) or
            ((arr[index + 1].toInt() and 0xFF) shl 8) or
            ((arr[index + 2].toInt() and 0xFF) shl 16) or
            ((arr[index + 3].toInt() and 0xFF) shl 24)
    }
}
