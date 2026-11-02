package com.biplov.kaihatch.engine

import android.content.Context
import android.net.Uri
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ZipEntryInfo
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
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipFile

object ZipParser {

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50
    private const val ZIP64_EOCD_RECORD_SIGNATURE = 0x06064b50
    private const val CENTRAL_DIR_HEADER_SIGNATURE = 0x02014b50
    private const val ZIP64_EXTRA_FIELD_ID = 0x0001

    private const val UINT32_MAX = 0xFFFFFFFFL

    // 7z magic bytes: 37 7A BC AF 27 1C
    private val SEVEN_Z_MAGIC = byteArrayOf(
        0x37.toByte(),
        0x7A.toByte(),
        0xBC.toByte(),
        0xAF.toByte(),
        0x27.toByte(),
        0x1C.toByte()
    )

    fun detectFormat(fileName: String, channel: FileChannel? = null): ArchiveFormat {
        val lower = fileName.lowercase().trim()
        when {
            lower.endsWith(".7z") || lower.matches(Regex(".*\\.7z\\.\\d{3}$")) -> return ArchiveFormat.SEVEN_Z
            lower.endsWith(".rar") || lower.matches(Regex(".*\\.rar\\.\\d{3}$")) -> return ArchiveFormat.RAR
            lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".aab") -> return ArchiveFormat.APK
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> return ArchiveFormat.TAR_GZ
            lower.endsWith(".tar") -> return ArchiveFormat.TAR
            lower.matches(Regex(".*\\.zip\\.\\d{3}$")) || lower.matches(Regex(".*\\.z\\d{2}$")) -> return ArchiveFormat.SPLIT_ZIP
            lower.endsWith(".zip") || lower.endsWith(".jar") -> return ArchiveFormat.ZIP
        }

        // Fallback magic-byte inspection when URI display name lacks a standard extension
        if (channel != null) {
            try {
                if (channel.size() >= 6L) {
                    val magic = ByteArray(6)
                    val buf = ByteBuffer.wrap(magic)
                    channel.position(0L)
                    readFully(channel, buf)
                    if (magic.contentEquals(SEVEN_Z_MAGIC)) {
                        return ArchiveFormat.SEVEN_Z
                    }
                    if (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
                        return ArchiveFormat.TAR_GZ
                    }
                    if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) {
                        return ArchiveFormat.ZIP
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return ArchiveFormat.ZIP
    }

    fun parseArchive(file: File, password: String? = null): List<ZipEntryInfo> {
        if (!file.exists() || file.length() < 6L) {
            return emptyList()
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val format = detectFormat(file.name, raf.channel)
                when (format) {
                    ArchiveFormat.SEVEN_Z -> parseSevenZChannel(raf.channel, file.name, password)
                    ArchiveFormat.TAR -> parseTarChannel(raf.channel)
                    ArchiveFormat.TAR_GZ -> parseTarGzFile(file)
                    ArchiveFormat.APK, ArchiveFormat.ZIP, ArchiveFormat.RAR, ArchiveFormat.SPLIT_ZIP -> {
                        val parsed = parseZipChannel(raf.channel)
                        if (parsed.isNotEmpty()) {
                            parsed
                        } else {
                            val ccParsed = CommonsCompressBufferStrategy.parseZipWithCommonsCompress(raf.channel, file.name)
                            if (ccParsed.isNotEmpty()) ccParsed else parseWithZipFile(file)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            parseWithZipFile(file)
        }
    }

    fun parseSafUri(context: Context, uri: Uri, fileName: String, password: String? = null): List<ZipEntryInfo> {
        return try {
            val initialFormat = detectFormat(fileName)
            if (initialFormat == ArchiveFormat.TAR_GZ) {
                return context.contentResolver.openInputStream(uri)?.use { rawIn ->
                    GzipCompressorInputStream(CommonsCompressBufferStrategy.wrapBufferedInput(rawIn)).use { gzIn ->
                        parseTarStream(gzIn)
                    }
                } ?: emptyList()
            }

            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val format = detectFormat(fileName, channel)
                    when (format) {
                        ArchiveFormat.SEVEN_Z -> parseSevenZChannel(channel, fileName, password)
                        ArchiveFormat.TAR -> parseTarChannel(channel)
                        ArchiveFormat.TAR_GZ -> {
                            context.contentResolver.openInputStream(uri)?.use { rawIn ->
                                GzipCompressorInputStream(CommonsCompressBufferStrategy.wrapBufferedInput(rawIn)).use { gzIn ->
                                    parseTarStream(gzIn)
                                }
                            } ?: emptyList()
                        }
                        ArchiveFormat.APK, ArchiveFormat.ZIP, ArchiveFormat.RAR, ArchiveFormat.SPLIT_ZIP -> {
                            val parsed = parseZipChannel(channel)
                            if (parsed.isNotEmpty()) {
                                parsed
                            } else {
                                CommonsCompressBufferStrategy.parseZipWithCommonsCompress(channel, fileName)
                            }
                        }
                    }
                }
            } ?: emptyList()
        } catch (t: Throwable) {
            CommonsCompressBufferStrategy.onLowMemoryCleanup()
            emptyList()
        }
    }

    /**
     * Checks if a 7z archive uses header encryption (AES-256 via 7zAES coder ID 0x06, 0xF1, 0x07, 0x01)
     * by inspecting the NextHeader block pointed to by the 32-byte Signature Header.
     */
    fun detectSevenZHeaderEncryption(channel: SeekableByteChannel): Boolean {
        return try {
            val size = channel.size()
            if (size < 32L) return false
            val header = ByteArray(32)
            channel.position(0L)
            readFully(channel, ByteBuffer.wrap(header))
            if (!header.take(6).toByteArray().contentEquals(SEVEN_Z_MAGIC)) return false

            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(12)
            val nextHeaderOffset = buf.long
            val nextHeaderSize = buf.long
            if (nextHeaderOffset <= 0L || nextHeaderSize <= 0L) return false

            val headerPos = 32L + nextHeaderOffset
            if (headerPos < 32L || headerPos >= size) return false

            val checkLen = minOf(nextHeaderSize, 4096L).toInt()
            if (checkLen < 4) return false

            val checkBytes = ByteArray(checkLen)
            channel.position(headerPos)
            readFully(channel, ByteBuffer.wrap(checkBytes))

            // Scan for 7zAES coder ID: 0x06, 0xF1, 0x07, 0x01
            for (i in 0..checkBytes.size - 4) {
                if (checkBytes[i] == 0x06.toByte() &&
                    (checkBytes[i + 1].toInt() and 0xFF) == 0xF1 &&
                    checkBytes[i + 2] == 0x07.toByte() &&
                    checkBytes[i + 3] == 0x01.toByte()
                ) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Zero-Copy 64-bit 7z Archive Parser supporting 6GB+ .7z files directly over FileChannel.
     * Uses [CommonsCompressBufferStrategy.openBufferedSevenZFile] with a 128 KB read-ahead window
     * and safe memory limits (`SevenZFileOptions`) so multi-gigabyte headers never cause OOM.
     */
    fun parseSevenZChannel(
        channel: FileChannel,
        fileName: String = "Archive.7z",
        password: String? = null
    ): List<ZipEntryInfo> {
        val fileLen = try {
            channel.size()
        } catch (_: Throwable) {
            0L
        }
        if (fileLen < 32L) return emptyList()

        // Verify 7z magic header
        val sigHeader = ByteArray(32)
        channel.position(0L)
        readFully(channel, ByteBuffer.wrap(sigHeader))
        val isSevenZMagic = sigHeader.take(6).toByteArray().contentEquals(SEVEN_Z_MAGIC)
        if (!isSevenZMagic) return emptyList()

        val isHeaderEncrypted = detectSevenZHeaderEncryption(channel)
        if (isHeaderEncrypted && password.isNullOrEmpty()) {
            val cleanBase = fileName.removeSuffix(".7z").removeSuffix(".7Z").ifEmpty { "encrypted_7z_archive" }
            return listOf(
                ZipEntryInfo(
                    path = cleanBase,
                    name = "$cleanBase (Encrypted 7z Archive)",
                    isDirectory = false,
                    compressedSize = (fileLen - 32L).coerceAtLeast(1L),
                    uncompressedSize = (fileLen - 32L).coerceAtLeast(1L),
                    crc32 = 0L,
                    compressionMethod = 70,
                    localHeaderOffset = 32L,
                    isEncrypted = true,
                    isZip64 = fileLen >= UINT32_MAX
                )
            )
        }

        val entries = mutableListOf<ZipEntryInfo>()

        try {
            channel.position(0L)
            val archive = try {
                CommonsCompressBufferStrategy.openDirectSevenZFile(
                    channel = channel,
                    archiveName = fileName,
                    password = password,
                    tryRecover = false
                )
            } catch (_: Throwable) {
                try {
                    channel.position(0L)
                    CommonsCompressBufferStrategy.openBufferedSevenZFile(
                        channel = channel,
                        archiveName = fileName,
                        password = password,
                        closeChannelOnClose = false,
                        tryRecover = false
                    )
                } catch (_: Throwable) {
                    try {
                        channel.position(0L)
                        CommonsCompressBufferStrategy.openDirectSevenZFile(
                            channel = channel,
                            archiveName = fileName,
                            password = password,
                            tryRecover = true
                        )
                    } catch (_: Throwable) {
                        channel.position(0L)
                        CommonsCompressBufferStrategy.openBufferedSevenZFile(
                            channel = channel,
                            archiveName = fileName,
                            password = password,
                            closeChannelOnClose = false,
                            tryRecover = true
                        )
                    }
                }
            }
            archive.use { archive ->
                val archiveEntries = archive.entries.toList()
                val totalUncompWeight = archiveEntries.sumOf { if (it.size > 0L) it.size else 1024L }.coerceAtLeast(1L)
                var cumulativeWeight = 0L
                var index = 0L
                for (entry in archiveEntries) {
                    val rawName = entry.name?.replace('\\', '/')?.trimStart('/') ?: "entry_$index"
                    if (rawName.isEmpty()) continue
                    val isDir = entry.isDirectory || rawName.endsWith("/")
                    val normalizedPath = if (isDir && !rawName.endsWith("/")) "$rawName/" else rawName
                    val cleanName = normalizedPath.trimEnd('/').substringAfterLast('/').ifEmpty { normalizedPath }
                    val uSize = if (entry.size >= 0L) entry.size else 0L
                    val entryWeight = if (isDir) 0L else uSize.coerceAtLeast(1024L)
                    // Estimate proportional offset across the 7z file weighted by entry size for accurate progressive telemetry
                    val estOffset = ((fileLen.toDouble() * cumulativeWeight.toDouble()) / totalUncompWeight.toDouble()).toLong()
                        .coerceIn(0L, (fileLen - 1L).coerceAtLeast(0L))
                    val estCompSize = if (isDir) {
                        0L
                    } else {
                        ((fileLen.toDouble() * entryWeight.toDouble()) / totalUncompWeight.toDouble()).toLong().coerceAtLeast(1L)
                    }
                    cumulativeWeight += entryWeight
                    val crc: Long = if (entry.hasCrc) (entry.crcValue and 0xFFFFFFFFL) else 0L
                    val lastMod = try {
                        if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else System.currentTimeMillis()
                    } catch (_: Throwable) {
                        System.currentTimeMillis()
                    }

                    entries.add(
                        ZipEntryInfo(
                            path = normalizedPath,
                            name = cleanName,
                            isDirectory = isDir,
                            compressedSize = estCompSize.coerceAtMost(uSize.coerceAtLeast(estCompSize)),
                            uncompressedSize = uSize,
                            crc32 = crc,
                            compressionMethod = 70, // 7Z LZMA/LZMA2
                            localHeaderOffset = estOffset,
                            lastModified = lastMod,
                            isEncrypted = isHeaderEncrypted || !password.isNullOrEmpty(),
                            isZip64 = fileLen >= UINT32_MAX || uSize >= UINT32_MAX
                        )
                    )
                    index++
                    if (index >= 150_000L) break
                }
            }
        } catch (pwdEx: PasswordRequiredException) {
            if (password.isNullOrEmpty()) {
                val baseName = fileName.removeSuffix(".7z").removeSuffix(".7Z").ifEmpty { "encrypted_7z_archive" }
                entries.add(
                    ZipEntryInfo(
                        path = baseName,
                        name = "$baseName (Encrypted 7z Payload)",
                        isDirectory = false,
                        compressedSize = fileLen,
                        uncompressedSize = fileLen,
                        crc32 = 0L,
                        compressionMethod = 70,
                        localHeaderOffset = 32L,
                        isEncrypted = true,
                        isZip64 = fileLen >= UINT32_MAX
                    )
                )
            }
        } catch (_: MemoryLimitException) {
            // Do not fabricate fake .bin/.iso entries when 7z header exceeds memory limit or is corrupt
        } catch (_: Throwable) {
            // Do not fabricate fake .bin/.iso entries when 7z header or coder is corrupt/unsupported
        }

        return entries
    }

    /**
     * Full 64-bit ZIP, APK & ZIP64 Central Directory parser supporting archives > 4 GB
     * and 2GB+ APKs with > 65,535 entries or APK Signing Blocks.
     *
     * Uses [CommonsCompressBufferStrategy.BufferedSeekableByteChannel] and reusable thread-local
     * scratch buffers so scanning 50,000+ APK entries performs zero per-entry `ByteArray` allocations
     * and coalesces Central Directory reads into 128 KB windows.
     */
    fun parseZipChannel(channel: FileChannel): List<ZipEntryInfo> {
        val fileLen = try {
            channel.size()
        } catch (_: Throwable) {
            0L
        }
        if (fileLen < 22L) return emptyList()

        val maxCommentLen = 65535L
        val searchLen = (maxCommentLen + 22L + 20L).coerceAtMost(fileLen)
        val searchStart = (fileLen - searchLen).coerceAtLeast(0L)

        val searchBuffer = ByteArray(searchLen.toInt())
        channel.position(searchStart)
        readFully(channel, ByteBuffer.wrap(searchBuffer))

        var eocdOffset = -1L
        var centralDirOffset = -1L
        var totalEntries = 0L

        // Scan backwards for a valid EOCD signature whose centralDirOffset is within bounds
        for (i in searchBuffer.size - 22 downTo 0) {
            val sig = readLeInt(searchBuffer, i)
            if (sig == EOCD_SIGNATURE) {
                val candidateEocdOffset = searchStart + i
                val candidateCdOffset = readLeInt(searchBuffer, i + 16).toLong() and 0xFFFFFFFFL
                val candidateEntries = (readLeShort(searchBuffer, i + 10) and 0xFFFF).toLong()
                if (candidateCdOffset < candidateEocdOffset || candidateCdOffset == UINT32_MAX) {
                    eocdOffset = candidateEocdOffset
                    centralDirOffset = candidateCdOffset
                    totalEntries = candidateEntries
                    break
                }
            }
        }

        if (eocdOffset == -1L) return emptyList()

        var isArchiveZip64 = fileLen >= 0x7FFFFFFFL

        // Check for ZIP64 EOCD Locator (20 bytes right before eocdOffset)
        if (eocdOffset >= 20L) {
            val locBytes = ByteArray(20)
            channel.position(eocdOffset - 20L)
            readFully(channel, ByteBuffer.wrap(locBytes))
            val locBuf = ByteBuffer.wrap(locBytes).order(ByteOrder.LITTLE_ENDIAN)
            val locSig = locBuf.int
            if (locSig == ZIP64_EOCD_LOCATOR_SIGNATURE) {
                locBuf.position(8)
                val zip64EocdOffset = locBuf.long
                if (zip64EocdOffset in 0 until fileLen) {
                    val recBytes = ByteArray(56)
                    channel.position(zip64EocdOffset)
                    readFully(channel, ByteBuffer.wrap(recBytes))
                    val recBuf = ByteBuffer.wrap(recBytes).order(ByteOrder.LITTLE_ENDIAN)
                    if (recBuf.int == ZIP64_EOCD_RECORD_SIGNATURE) {
                        isArchiveZip64 = true
                        recBuf.position(32)
                        totalEntries = recBuf.long
                        recBuf.position(48)
                        centralDirOffset = recBuf.long
                    }
                }
            }
        }

        if (centralDirOffset < 0L || centralDirOffset >= fileLen) {
            return emptyList()
        }

        val bufferedChannel = CommonsCompressBufferStrategy.BufferedSeekableByteChannel(
            delegate = channel,
            readAheadCapacity = CommonsCompressBufferStrategy.CHANNEL_READ_AHEAD_BYTES,
            closeDelegateOnClose = false
        )
        bufferedChannel.position(centralDirOffset)

        val initialCap = totalEntries.coerceIn(16L, 8192L).toInt()
        val entries = ArrayList<ZipEntryInfo>(initialCap)

        val headerBytes = ByteArray(46)
        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Note: Standard 32-bit EOCD stores totalEntries in a 16-bit field (wrapping at 65,536
        // in large APKs). Read all contiguous Central Directory headers up to eocdOffset!
        val maxSafeEntries = 150_000L
        var count = 0L
        while (count < maxSafeEntries && bufferedChannel.position() + 46L <= eocdOffset) {
            headerBuf.clear()
            readFullySeekable(bufferedChannel, headerBuf)
            headerBuf.flip()
            if (headerBuf.remaining() < 46) break

            val signature = headerBuf.int
            if (signature != CENTRAL_DIR_HEADER_SIGNATURE) break

            headerBuf.position(8)
            val gpFlag = headerBuf.short.toInt() and 0xFFFF
            val compressionMethod = headerBuf.short.toInt() and 0xFFFF
            val lastModTime = headerBuf.short.toInt() and 0xFFFF
            val lastModDate = headerBuf.short.toInt() and 0xFFFF
            val crc32 = headerBuf.int.toLong() and 0xFFFFFFFFL
            var compressedSize = headerBuf.int.toLong() and 0xFFFFFFFFL
            var uncompressedSize = headerBuf.int.toLong() and 0xFFFFFFFFL
            val fileNameLength = headerBuf.short.toInt() and 0xFFFF
            val extraFieldLength = headerBuf.short.toInt() and 0xFFFF
            val fileCommentLength = headerBuf.short.toInt() and 0xFFFF

            headerBuf.position(42)
            var localHeaderOffset = headerBuf.int.toLong() and 0xFFFFFFFFL

            val remainingInFile = fileLen - bufferedChannel.position()
            if (fileNameLength.toLong() + extraFieldLength.toLong() + fileCommentLength.toLong() > remainingInFile) {
                break
            }

            // Read file name using reusable thread-local scratch buffer to prevent GC churn
            val scratch = CommonsCompressBufferStrategy.borrowHeaderScratch(maxOf(fileNameLength, extraFieldLength))
            if (fileNameLength > 0) {
                val nameBuf = ByteBuffer.wrap(scratch, 0, fileNameLength)
                readFullySeekable(bufferedChannel, nameBuf)
            }
            val fileName = if (fileNameLength > 0) String(scratch, 0, fileNameLength, Charsets.UTF_8) else ""

            var entryZip64 = isArchiveZip64

            // Parse Extra Field for ZIP64 Extended Information (0x0001)
            if (extraFieldLength > 0) {
                val extraBuf = ByteBuffer.wrap(scratch, 0, extraFieldLength).order(ByteOrder.LITTLE_ENDIAN)
                readFullySeekable(bufferedChannel, extraBuf)
                extraBuf.flip()

                if (uncompressedSize == UINT32_MAX || compressedSize == UINT32_MAX || localHeaderOffset == UINT32_MAX) {
                    while (extraBuf.remaining() >= 4) {
                        val headerId = extraBuf.short.toInt() and 0xFFFF
                        val dataSize = extraBuf.short.toInt() and 0xFFFF
                        if (dataSize > extraBuf.remaining()) break
                        if (headerId == ZIP64_EXTRA_FIELD_ID) {
                            entryZip64 = true
                            val blockStart = extraBuf.position()
                            val blockEnd = blockStart + dataSize

                            // Handle both conditional Zip64 fields and unconditional 24/28-byte Zip64 blocks
                            val needU = uncompressedSize == UINT32_MAX
                            val needC = compressedSize == UINT32_MAX
                            val needO = localHeaderOffset == UINT32_MAX
                            val neededBytes = (if (needU) 8 else 0) + (if (needC) 8 else 0) + (if (needO) 8 else 0)

                            if (dataSize >= 24 && neededBytes < 24) {
                                // Unconditional 24+ byte Zip64 record: [uSize(8), cSize(8), offset(8)]
                                val u64 = extraBuf.getLong(blockStart)
                                val c64 = extraBuf.getLong(blockStart + 8)
                                val o64 = extraBuf.getLong(blockStart + 16)
                                if (needU || u64 > 0L) uncompressedSize = u64
                                if (needC || c64 > 0L) compressedSize = c64
                                if (needO || o64 in 0L..fileLen) localHeaderOffset = o64
                            } else {
                                if (needU && extraBuf.position() + 8 <= blockEnd) {
                                    uncompressedSize = extraBuf.long
                                }
                                if (needC && extraBuf.position() + 8 <= blockEnd) {
                                    compressedSize = extraBuf.long
                                }
                                if (needO && extraBuf.position() + 8 <= blockEnd) {
                                    localHeaderOffset = extraBuf.long
                                }
                            }
                            break
                        } else {
                            extraBuf.position(extraBuf.position() + dataSize)
                        }
                    }
                }
            }

            if (fileCommentLength > 0) {
                val nextPos = (bufferedChannel.position() + fileCommentLength).coerceAtMost(fileLen)
                bufferedChannel.position(nextPos)
            }

            if (fileName.isEmpty()) {
                count++
                continue
            }

            val isDirectory = fileName.endsWith("/")
            val cleanName = if (isDirectory) {
                fileName.trimEnd('/').substringAfterLast('/')
            } else {
                fileName.substringAfterLast('/')
            }

            val isEncrypted = (gpFlag and 0x0001) != 0 || compressionMethod == 99

            // Guard against unresolved 0xFFFFFFFF sentinels or unknown uncompressed sizes
            val safeCompSize = if (compressedSize == UINT32_MAX || compressedSize < 0L) 0L else compressedSize
            val safeUncompSize = when {
                uncompressedSize == UINT32_MAX || uncompressedSize < 0L -> safeCompSize
                uncompressedSize == 0L && safeCompSize > 0L && !isDirectory -> safeCompSize
                else -> uncompressedSize
            }
            val safeOffset = if (localHeaderOffset == UINT32_MAX || localHeaderOffset < 0L) {
                0L
            } else {
                localHeaderOffset.coerceIn(0L, fileLen)
            }

            entries.add(
                ZipEntryInfo(
                    path = fileName,
                    name = if (cleanName.isEmpty()) fileName else cleanName,
                    isDirectory = isDirectory,
                    compressedSize = safeCompSize,
                    uncompressedSize = safeUncompSize,
                    crc32 = crc32,
                    compressionMethod = compressionMethod,
                    localHeaderOffset = safeOffset,
                    lastModified = decodeDosTime(lastModDate, lastModTime),
                    isEncrypted = isEncrypted,
                    isZip64 = entryZip64 || safeUncompSize >= UINT32_MAX || safeCompSize >= UINT32_MAX
                )
            )
            count++
        }

        return entries
    }

    /**
     * Parses a POSIX UStar .tar archive via FileChannel, recording exact 512-byte block offsets
     * for progressive reverse truncation.
     */
    fun parseTarFile(file: File): List<ZipEntryInfo> {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                parseTarChannel(raf.channel)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun parseTarChannel(channel: FileChannel): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        val fileLen = channel.size()
        var pos = 0L
        val block = ByteArray(512)
        val buf = ByteBuffer.wrap(block)
        val buffered = CommonsCompressBufferStrategy.BufferedSeekableByteChannel(
            delegate = channel,
            readAheadCapacity = CommonsCompressBufferStrategy.CHANNEL_READ_AHEAD_BYTES,
            closeDelegateOnClose = false
        )

        while (pos + 512L <= fileLen) {
            buf.clear()
            buffered.position(pos)
            readFullySeekable(buffered, buf)

            if (block.all { it == 0.toByte() }) {
                break
            }

            val rawName = readTarString(block, 0, 100)
            val prefix = readTarString(block, 345, 155)
            val fullPath = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            if (fullPath.isEmpty()) break

            val sizeOctal = readTarString(block, 124, 12).trim()
            val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
            val mtimeOctal = readTarString(block, 136, 12).trim()
            val mtimeSec = mtimeOctal.toLongOrNull(8) ?: (System.currentTimeMillis() / 1000L)
            val typeFlag = block[156].toInt().toChar()
            val isDir = typeFlag == '5' || fullPath.endsWith("/")
            val cleanName = fullPath.trimEnd('/').substringAfterLast('/')

            entries.add(
                ZipEntryInfo(
                    path = fullPath,
                    name = cleanName.ifEmpty { fullPath },
                    isDirectory = isDir,
                    compressedSize = entrySize,
                    uncompressedSize = entrySize,
                    crc32 = 0L,
                    compressionMethod = 100, // TAR 512B block
                    localHeaderOffset = pos,
                    lastModified = mtimeSec * 1000L
                )
            )

            val dataBlocks = (entrySize + 511L) / 512L
            pos += 512L + (dataBlocks * 512L)
        }
        return entries
    }

    fun parseTarGzFile(file: File): List<ZipEntryInfo> {
        return try {
            GzipCompressorInputStream(
                CommonsCompressBufferStrategy.wrapBufferedInput(FileInputStream(file), file.length())
            ).use { gzIn ->
                parseTarStream(gzIn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseTarStream(input: InputStream): List<ZipEntryInfo> {
        val ccEntries = CommonsCompressBufferStrategy.parseTarStreamWithCommonsCompress(input)
        if (ccEntries.isNotEmpty()) return ccEntries

        val entries = mutableListOf<ZipEntryInfo>()
        val block = ByteArray(512)
        var offset = 0L

        while (true) {
            var readTotal = 0
            while (readTotal < 512) {
                val r = input.read(block, readTotal, 512 - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            if (readTotal < 512 || block.all { it == 0.toByte() }) break

            val rawName = readTarString(block, 0, 100)
            val prefix = readTarString(block, 345, 155)
            val fullPath = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            if (fullPath.isEmpty()) break

            val sizeOctal = readTarString(block, 124, 12).trim()
            val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
            val typeFlag = block[156].toInt().toChar()
            val isDir = typeFlag == '5' || fullPath.endsWith("/")
            val cleanName = fullPath.trimEnd('/').substringAfterLast('/')

            entries.add(
                ZipEntryInfo(
                    path = fullPath,
                    name = cleanName.ifEmpty { fullPath },
                    isDirectory = isDir,
                    compressedSize = entrySize,
                    uncompressedSize = entrySize,
                    crc32 = 0L,
                    compressionMethod = 100,
                    localHeaderOffset = offset
                )
            )

            val paddedSize = ((entrySize + 511L) / 512L) * 512L
            var toSkip = paddedSize
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) {
                    if (input.read() == -1) break
                    toSkip--
                } else {
                    toSkip -= skipped
                }
            }
            offset += 512L + paddedSize
        }
        return entries
    }

    private fun readTarString(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = (offset + length).coerceAtMost(block.size)
        while (end < limit && block[end] != 0.toByte()) {
            end++
        }
        return String(block, offset, end - offset, Charsets.UTF_8).trim()
    }

    fun readFully(channel: SeekableByteChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val r = channel.read(buffer)
            if (r <= 0) break
        }
    }

    fun readFullySeekable(channel: SeekableByteChannel, buffer: ByteBuffer) = readFully(channel, buffer)

    private fun readLeInt(arr: ByteArray, index: Int): Int {
        return (arr[index].toInt() and 0xFF) or
                ((arr[index + 1].toInt() and 0xFF) shl 8) or
                ((arr[index + 2].toInt() and 0xFF) shl 16) or
                ((arr[index + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLeShort(arr: ByteArray, index: Int): Int {
        return (arr[index].toInt() and 0xFF) or
                ((arr[index + 1].toInt() and 0xFF) shl 8)
    }

    private fun parseWithZipFile(file: File): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        try {
            ZipFile(file).use { zip ->
                val enumEntries = zip.entries()
                var simulatedOffset = 0L
                while (enumEntries.hasMoreElements()) {
                    val entry = enumEntries.nextElement()
                    val isDir = entry.isDirectory
                    val cSize = if (entry.compressedSize >= 0) entry.compressedSize else 0L
                    val uSize = if (entry.size >= 0) entry.size else cSize
                    val crc = if (entry.crc >= 0) entry.crc else 0L

                    entries.add(
                        ZipEntryInfo(
                            path = entry.name,
                            name = entry.name.trimEnd('/').substringAfterLast('/'),
                            isDirectory = isDir,
                            compressedSize = cSize,
                            uncompressedSize = uSize,
                            crc32 = crc,
                            compressionMethod = entry.method,
                            localHeaderOffset = simulatedOffset,
                            lastModified = entry.time,
                            isZip64 = uSize >= UINT32_MAX || cSize >= UINT32_MAX
                        )
                    )
                    simulatedOffset += 30L + entry.name.toByteArray().size + cSize
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return entries
    }

    private fun decodeDosTime(date: Int, time: Int): Long {
        val year = ((date shr 9) and 0x7F) + 1980
        val month = ((date shr 5) and 0x0F) - 1
        val day = date and 0x1F
        val hour = (time shr 11) and 0x1F
        val min = (time shr 5) and 0x3F
        val sec = (time and 0x1F) * 2

        val cal = java.util.Calendar.getInstance()
        cal.set(
            year,
            month.coerceIn(0, 11),
            day.coerceIn(1, 31),
            hour.coerceIn(0, 23),
            min.coerceIn(0, 59),
            sec.coerceIn(0, 59)
        )
        return cal.timeInMillis
    }

    /**
     * Wraps a FileChannel so SevenZFile/ZipFile can read without closing the underlying descriptor.
     */
    class NonClosingSeekableByteChannel(private val delegate: FileChannel) : SeekableByteChannel {
        override fun isOpen(): Boolean = delegate.isOpen
        override fun close() {
            // Do not close underlying FileChannel here; caller manages descriptor lifecycle
        }
        override fun read(dst: ByteBuffer): Int = delegate.read(dst)
        override fun write(src: ByteBuffer): Int = delegate.write(src)
        override fun position(): Long = delegate.position()
        override fun position(newPosition: Long): SeekableByteChannel {
            delegate.position(newPosition)
            return this
        }
        override fun size(): Long = delegate.size()
        override fun truncate(size: Long): SeekableByteChannel {
            delegate.truncate(size)
            return this
        }
    }
}
