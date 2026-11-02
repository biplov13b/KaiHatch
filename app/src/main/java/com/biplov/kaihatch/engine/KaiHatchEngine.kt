package com.biplov.kaihatch.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.data.TargetOutput
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.coroutines.coroutineContext

object KaiHatchEngine {

    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val MAX_JOURNAL_ENTRIES = 5_000
    private val destDirResolveLock = Any()

    @Volatile
    var faultInjector: TruncationFaultInjector = TruncationFaultInjector.NoOp

    /**
     * Extracts a local File archive (ZIP, APK, ZIP64 >4GB, 7Z, TAR, TAR.GZ, or encrypted archive)
     * with full crash-safe journaling and optional selective entry filtering.
     */
    fun extractArchive(
        archiveFile: File,
        targetDir: File,
        isHatchMode: Boolean,
        password: String? = null,
        selectedEntryPaths: Set<String>? = null,
        context: Context? = null,
        targetTreeUri: Uri? = null,
        targetFolderDocUri: Uri? = null
    ): Flow<ExtractionProgress> = flow {
        try {
            if (!archiveFile.exists()) {
                emit(
                    ExtractionProgress(
                        archiveName = archiveFile.name,
                        status = ExtractionStatus.ERROR,
                        errorMessage = "Archive file not found: ${archiveFile.absolutePath}"
                    )
                )
                return@flow
            }

            val originalArchiveSize = archiveFile.length()
            val format = try {
                RandomAccessFile(archiveFile, "r").use { raf ->
                    ZipParser.detectFormat(archiveFile.name, raf.channel)
                }
            } catch (_: Exception) {
                ZipParser.detectFormat(archiveFile.name)
            }

            emit(
                ExtractionProgress(
                    archiveName = archiveFile.name,
                    isHatchMode = isHatchMode,
                    status = ExtractionStatus.ANALYZING,
                    originalArchiveBytes = originalArchiveSize,
                    currentArchiveBytesOnDisk = originalArchiveSize,
                    journalLogs = listOf("Inspecting ${format.label} index...")
                )
            )

            if (format == ArchiveFormat.TAR_GZ) {
                extractTarGzStream(
                    archiveName = archiveFile.name,
                    originalArchiveSize = originalArchiveSize,
                    openStream = { FileInputStream(archiveFile) },
                    targetDir = targetDir,
                    selectedEntryPaths = selectedEntryPaths,
                    context = context,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    onDeleteSource = { if (isHatchMode && selectedEntryPaths == null) archiveFile.delete() }
                ).collect { emit(it) }
                return@flow
            }

            if (format == ArchiveFormat.SEVEN_Z) {
                RandomAccessFile(archiveFile, if (isHatchMode && selectedEntryPaths == null) "rw" else "r").use { raf ->
                    extractSevenZChannel(
                        channel = raf.channel,
                        fileDescriptor = raf.fd,
                        archiveName = archiveFile.name,
                        originalArchiveSize = originalArchiveSize,
                        targetDir = targetDir,
                        isHatchMode = isHatchMode && selectedEntryPaths == null,
                        isZeroCopySaf = false,
                        password = password,
                        selectedEntryPaths = selectedEntryPaths,
                        context = context,
                        targetTreeUri = targetTreeUri,
                        targetFolderDocUri = targetFolderDocUri,
                        onFinalTruncateDelete = {
                            if (isHatchMode && selectedEntryPaths == null) {
                                archiveFile.delete()
                            }
                        }
                    ).collect { emit(it) }
                }
                return@flow
            }

            val allEntries = try {
                ZipParser.parseArchive(archiveFile, password)
            } catch (e: Exception) {
                emit(
                    ExtractionProgress(
                        archiveName = archiveFile.name,
                        status = ExtractionStatus.ERROR,
                        errorMessage = "Failed to parse archive headers: ${e.message}"
                    )
                )
                return@flow
            }

            val entriesToProcess = if (selectedEntryPaths != null) {
                allEntries.filter { entry ->
                    selectedEntryPaths.any { sel -> entry.path == sel || entry.path.startsWith(sel.trimEnd('/') + "/") }
                }
            } else {
                allEntries
            }

            if (entriesToProcess.isEmpty()) {
                emit(
                    ExtractionProgress(
                        archiveName = archiveFile.name,
                        status = ExtractionStatus.ERROR,
                        errorMessage = "Archive is empty or no matching entries found"
                    )
                )
                return@flow
            }

            if (isHatchMode && selectedEntryPaths == null && !archiveFile.canWrite()) {
                val errMsg = "Hatch Mode requires writable access to '${archiveFile.name}', but the file is read-only. Switch to Standard Mode to extract without modifying the source archive."
                emit(
                    ExtractionProgress(
                        archiveName = archiveFile.name,
                        isHatchMode = true,
                        status = ExtractionStatus.ERROR,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = originalArchiveSize,
                        errorMessage = errMsg,
                        journalLogs = listOf("HATCH MODE ERROR: $errMsg")
                    )
                )
                return@flow
            }

            val effectiveHatchMode = isHatchMode && selectedEntryPaths == null

            val stateFilePath = if (effectiveHatchMode && context != null) {
                ResumeJournalManager.getStateFileForArchive(context, archiveFile.name).absolutePath
            } else {
                File(archiveFile.parentFile ?: targetDir, "${archiveFile.name}.hatchstate.json").absolutePath
            }

            RandomAccessFile(archiveFile, if (effectiveHatchMode) "rw" else "r").use { raf ->
                extractChannelInternal(
                    channel = raf.channel,
                    fileDescriptor = raf.fd,
                    archiveName = archiveFile.name,
                    archivePath = archiveFile.absolutePath,
                    sourceUriString = null,
                    format = format,
                    entries = entriesToProcess,
                    targetDir = targetDir,
                    isHatchMode = effectiveHatchMode,
                    isZeroCopySaf = false,
                    originalArchiveSize = originalArchiveSize,
                    initialReclaimedBytes = 0L,
                    initialCompletedPaths = emptySet(),
                    stateFilePath = stateFilePath,
                    password = password,
                    context = context,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    onFinalTruncateDelete = {
                        if (effectiveHatchMode) {
                            archiveFile.delete()
                        }
                    }
                ).collect { emit(it) }
            }
        } catch (t: Throwable) {
            val err = t.localizedMessage ?: t.javaClass.simpleName
            emit(
                ExtractionProgress(
                    archiveName = archiveFile.name,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "Extraction error: $err",
                    journalLogs = listOf("Error [${t.javaClass.simpleName}]: $err")
                )
            )
        }
    }.conflate().flowOn(Dispatchers.IO)

    /**
     * True Zero-Copy In-Place SAF URI Extractor:
     * Opens the external Uri directly via ParcelFileDescriptor and truncates the archive in place
     * using Os.ftruncate / FileChannel.truncate without copying the archive.
     * Safely falls back to read-only zero-copy streaming if the SAF provider disallows write/truncate
     * (prevents EACCES crashes on read-only APK/Download providers).
     */
    fun extractSafUriZeroCopy(
        context: Context,
        uri: Uri,
        archiveName: String,
        entries: List<ZipEntryInfo>,
        targetDir: File,
        isHatchMode: Boolean,
        password: String? = null,
        selectedEntryPaths: Set<String>? = null,
        targetTreeUri: Uri? = null,
        targetFolderDocUri: Uri? = null
    ): Flow<ExtractionProgress> = flow {
        try {
            val filteredEntries = if (selectedEntryPaths != null) {
                entries.filter { e ->
                    selectedEntryPaths.any { sel -> e.path == sel || e.path.startsWith(sel.trimEnd('/') + "/") }
                }
            } else {
                entries
            }

            if (filteredEntries.isEmpty()) {
                emit(
                    ExtractionProgress(
                        archiveName = archiveName,
                        status = ExtractionStatus.ERROR,
                        errorMessage = "No entries found to extract"
                    )
                )
                return@flow
            }

            val initialFormat = ZipParser.detectFormat(archiveName)
            if (initialFormat == ArchiveFormat.TAR_GZ) {
                val origSize = StorageHelperSafeSize(context, uri)
                extractTarGzStream(
                    archiveName = archiveName,
                    originalArchiveSize = origSize,
                    openStream = {
                        context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Cannot open input stream for URI")
                    },
                    targetDir = targetDir,
                    selectedEntryPaths = selectedEntryPaths,
                    context = context,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    onDeleteSource = {
                        if (isHatchMode && selectedEntryPaths == null) {
                            try {
                                DocumentsContract.deleteDocument(context.contentResolver, uri)
                            } catch (_: Exception) {
                            }
                        }
                    }
                ).collect { emit(it) }
                return@flow
            }

            val wantsWrite = isHatchMode && selectedEntryPaths == null
            var openedWritable = false
            var openWriteError: String? = null
            val pfd: ParcelFileDescriptor? = if (wantsWrite) {
                try {
                    val rwPfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (rwPfd != null) {
                        openedWritable = true
                        rwPfd
                    } else {
                        openWriteError = "ContentResolver returned null descriptor for 'rw' mode"
                        null
                    }
                } catch (t: Throwable) {
                    openedWritable = false
                    openWriteError = t.localizedMessage ?: t.javaClass.simpleName
                    null
                }
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            }

            if (wantsWrite && (!openedWritable || pfd == null)) {
                try { pfd?.close() } catch (_: Throwable) {}
                val reason = openWriteError ?: "write access denied"
                val errMsg = "Hatch Mode requires writable access to '$archiveName', but the selected file is read-only ($reason). Switch to Standard Mode to extract without modifying the source archive."
                emit(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = true,
                        status = ExtractionStatus.ERROR,
                        errorMessage = errMsg,
                        journalLogs = listOf("HATCH MODE ERROR: $errMsg")
                    )
                )
                return@flow
            }

            if (pfd == null) {
                emit(
                    ExtractionProgress(
                        archiveName = archiveName,
                        status = ExtractionStatus.ERROR,
                        errorMessage = "Cannot open file descriptor for selected archive"
                    )
                )
                return@flow
            }

            val stateFilePath = ResumeJournalManager.getStateFileForArchive(context, archiveName).absolutePath

            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    val readChannel = fis.channel
                    val originalSize = try {
                        readChannel.size().takeIf { it > 0L } ?: descriptor.statSize.coerceAtLeast(0L)
                    } catch (_: Throwable) {
                        descriptor.statSize.coerceAtLeast(0L)
                    }

                    val format = ZipParser.detectFormat(archiveName, readChannel)

                    // Verify if in-place write & ftruncate are actually permitted by the kernel/FUSE provider
                    var truncateErrorDetail: String? = null
                    val canTruncateInPlace = if (openedWritable && originalSize > 0L) {
                        try {
                            Os.ftruncate(descriptor.fileDescriptor, originalSize)
                            val probeByte = ByteArray(1)
                            val probeBuf = ByteBuffer.wrap(probeByte)
                            val readOk = readChannelAtPosition(readChannel, probeBuf, 0L) == 1
                            readOk && writeChannelAtPosition(readChannel, descriptor.fileDescriptor, probeByte, 0, 1, 0L)
                        } catch (t: Throwable) {
                            truncateErrorDetail = t.localizedMessage ?: t.javaClass.simpleName
                            false
                        }
                    } else {
                        false
                    }

                    if (wantsWrite && !canTruncateInPlace) {
                        val reason = truncateErrorDetail ?: "in-place truncation unsupported"
                        val errMsg = "Hatch Mode requires in-place file truncation (ftruncate) on '$archiveName', which failed ($reason). Switch to Standard Mode to extract without modifying the source archive."
                        emit(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = true,
                                isZeroCopySaf = true,
                                status = ExtractionStatus.ERROR,
                                originalArchiveBytes = originalSize,
                                currentArchiveBytesOnDisk = originalSize,
                                errorMessage = errMsg,
                                journalLogs = listOf("HATCH MODE ERROR: $errMsg")
                            )
                        )
                        return@flow
                    }

                    if (format == ArchiveFormat.SEVEN_Z) {
                        extractSevenZChannel(
                            channel = readChannel,
                            fileDescriptor = descriptor.fileDescriptor,
                            archiveName = archiveName,
                            originalArchiveSize = originalSize,
                            targetDir = targetDir,
                            isHatchMode = wantsWrite,
                            isZeroCopySaf = true,
                            password = password,
                            selectedEntryPaths = selectedEntryPaths,
                            context = context,
                            targetTreeUri = targetTreeUri,
                            targetFolderDocUri = targetFolderDocUri,
                            onFinalTruncateDelete = {
                                if (wantsWrite) {
                                    try {
                                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        ).collect { emit(it) }
                    } else {
                        extractChannelInternal(
                            channel = readChannel,
                            fileDescriptor = descriptor.fileDescriptor,
                            archiveName = archiveName,
                            archivePath = "",
                            sourceUriString = uri.toString(),
                            format = format,
                            entries = filteredEntries,
                            targetDir = targetDir,
                            isHatchMode = wantsWrite,
                            isZeroCopySaf = true,
                            originalArchiveSize = originalSize,
                            initialReclaimedBytes = 0L,
                            initialCompletedPaths = emptySet(),
                            stateFilePath = stateFilePath,
                            password = password,
                            context = context,
                            targetTreeUri = targetTreeUri,
                            targetFolderDocUri = targetFolderDocUri,
                            onFinalTruncateDelete = {
                                if (wantsWrite) {
                                    try {
                                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        ).collect { emit(it) }
                    }
                }
            }
        } catch (t: Throwable) {
            val err = t.localizedMessage ?: t.javaClass.simpleName
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "Failed to unpack archive: $err",
                    journalLogs = listOf("Error [${t.javaClass.simpleName}]: $err")
                )
            )
        }
    }.conflate().flowOn(Dispatchers.IO)

    private fun StorageHelperSafeSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val chSize = try {
                    FileInputStream(pfd.fileDescriptor).use { it.channel.size() }
                } catch (_: Throwable) {
                    -1L
                }
                when {
                    chSize >= 0L -> chSize
                    pfd.statSize >= 0L -> pfd.statSize
                    else -> try { Os.fstat(pfd.fileDescriptor).st_size.coerceAtLeast(0L) } catch (_: Throwable) { 0L }
                }
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Resumes an interrupted in-place extraction from its saved `.hatchstate.json` / `.index.bin` backup
     * with full-stream CRC32 verification of already extracted output files.
     */
    fun resumeInterruptedExtraction(
        context: Context,
        resumeState: ResumeJournalState,
        password: String? = null
    ): Flow<ExtractionProgress> = flow {
        try {
            val targetDir = File(resumeState.targetDirPath)
            val format = ZipParser.detectFormat(resumeState.archiveName)

            val eligibility = ResumeJournalManager.assessResumeEligibility(resumeState, context)
            if (eligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable) {
                val guard = ResumeJournalManager.loadTailSwapGuard(resumeState.stateFilePath)
                if (guard != null) {
                    quarantineOutputFile(File(targetDir, guard.entryPath))
                }
                emit(
                    ExtractionProgress(
                        archiveName = resumeState.archiveName,
                        isHatchMode = true,
                        status = ExtractionStatus.ERROR,
                        isCrcValid = false,
                        errorMessage = "UNRECOVERABLE RESUME STATE: ${eligibility.reason} [SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE]",
                        originalArchiveBytes = resumeState.originalArchiveBytes,
                        currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                        bytesReclaimed = resumeState.bytesReclaimedSoFar,
                        journalLogs = listOf("UNRECOVERABLE RESUME STATE: ${eligibility.reason}")
                    )
                )
                return@flow
            }

            // Reconcile completedPaths and remainingEntries using full-stream CRC32 verification
            val fullIndex = ResumeJournalManager.loadInitialIndex(resumeState.stateFilePath)
            val physicalArchiveLen = when {
                resumeState.archivePath.isNotEmpty() -> {
                    val f = File(resumeState.archivePath)
                    if (f.exists()) f.length() else 0L
                }
                !resumeState.sourceUriString.isNullOrEmpty() -> {
                    StorageHelperSafeSize(context, Uri.parse(resumeState.sourceUriString))
                }
                else -> resumeState.currentArchiveBytes
            }

            val verifiedCompleted = LinkedHashSet<String>()
            val reQueuedEntries = ArrayList<ZipEntryInfo>()
            val indexByPath = fullIndex?.associateBy { it.path } ?: emptyMap()

            for (compPath in resumeState.completedPaths) {
                val meta = indexByPath[compPath]
                if (meta != null && meta.isDirectory) {
                    val dirFile = File(targetDir, meta.path)
                    if (!dirFile.exists()) dirFile.mkdirs()
                    verifiedCompleted.add(compPath)
                    continue
                }
                val destFile = resolveSafeDestFile(targetDir, compPath)
                if (meta != null) {
                    val expectedCrc = if (meta.crc32 != 0L) meta.crc32 else resumeState.verifiedEntryCrcs[compPath]
                    val isIntact = ResumeJournalManager.verifyOutputFileIntegrity(
                        file = destFile,
                        expectedSize = meta.uncompressedSize,
                        expectedCrc32 = expectedCrc
                    )
                    if (isIntact) {
                        verifiedCompleted.add(compPath)
                    } else {
                        val approxEnd = meta.localHeaderOffset + 30L + meta.path.length + meta.compressedSize
                        if (approxEnd <= physicalArchiveLen) {
                            reQueuedEntries.add(meta)
                        } else {
                            emit(
                                ExtractionProgress(
                                    archiveName = resumeState.archiveName,
                                    status = ExtractionStatus.ERROR,
                                    errorMessage = "Resume integrity check failed: '${meta.name}' is corrupted or missing on disk and its source archive region was already truncated."
                                )
                            )
                            return@flow
                        }
                    }
                } else {
                    val expectedCrc = resumeState.verifiedEntryCrcs[compPath]
                    if (destFile.exists() && destFile.isFile && (expectedCrc == null || ResumeJournalManager.computeFileCrc32(destFile) == expectedCrc)) {
                        verifiedCompleted.add(compPath)
                    }
                }
            }

            val effectiveRemaining = if (fullIndex != null) {
                fullIndex.filter { !verifiedCompleted.contains(it.path) }
            } else {
                (reQueuedEntries + resumeState.remainingEntries)
                    .distinctBy { it.path }
                    .sortedByDescending { it.localHeaderOffset }
            }

            if (!resumeState.sourceUriString.isNullOrEmpty()) {
                val uri = Uri.parse(resumeState.sourceUriString)
                val pfd = try {
                    context.contentResolver.openFileDescriptor(uri, "rw")
                } catch (t: Throwable) {
                    val err = t.localizedMessage ?: t.javaClass.simpleName
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            isZeroCopySaf = true,
                            status = ExtractionStatus.ERROR,
                            errorMessage = "Could not re-open SAF URI in writable ('rw') mode to resume Hatch Mode extraction ($err)"
                        )
                    )
                    return@flow
                }
                if (pfd == null) {
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            isZeroCopySaf = true,
                            status = ExtractionStatus.ERROR,
                            errorMessage = "Could not re-open SAF URI in writable ('rw') mode to resume Hatch Mode extraction"
                        )
                    )
                    return@flow
                }
                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { fis ->
                        val actualLen = safeChannelSize(fis.channel, descriptor.fileDescriptor, physicalArchiveLen)
                        val canTruncate = try {
                            Os.ftruncate(descriptor.fileDescriptor, actualLen)
                            val probeByte = ByteArray(1)
                            val probeBuf = ByteBuffer.wrap(probeByte)
                            val readOk = readChannelAtPosition(fis.channel, probeBuf, 0L) == 1
                            readOk && writeChannelAtPosition(fis.channel, descriptor.fileDescriptor, probeByte, 0, 1, 0L)
                        } catch (_: Throwable) {
                            false
                        }
                        if (!canTruncate) {
                            emit(
                                ExtractionProgress(
                                    archiveName = resumeState.archiveName,
                                    isHatchMode = true,
                                    isZeroCopySaf = true,
                                    status = ExtractionStatus.ERROR,
                                    errorMessage = "Cannot resume Hatch Mode extraction: source SAF URI descriptor does not support in-place truncation (ftruncate)"
                                )
                            )
                            return@flow
                        }
                        extractChannelInternal(
                            channel = fis.channel,
                            fileDescriptor = descriptor.fileDescriptor,
                            archiveName = resumeState.archiveName,
                            archivePath = resumeState.archivePath,
                            sourceUriString = resumeState.sourceUriString,
                            format = format,
                            entries = effectiveRemaining,
                            targetDir = targetDir,
                            isHatchMode = true,
                            isZeroCopySaf = true,
                            originalArchiveSize = resumeState.originalArchiveBytes,
                            initialReclaimedBytes = (resumeState.originalArchiveBytes - actualLen).coerceAtLeast(0L),
                            initialCompletedPaths = verifiedCompleted,
                            initialVerifiedCrcs = resumeState.verifiedEntryCrcs,
                            stateFilePath = resumeState.stateFilePath,
                            password = password ?: resumeState.password,
                            onFinalTruncateDelete = {
                                try {
                                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                                } catch (_: Exception) {
                                }
                            }
                        ).collect { emit(it) }
                    }
                }
            } else {
                val archiveFile = File(resumeState.archivePath)
                if (!archiveFile.exists()) {
                    ResumeJournalManager.deleteState(resumeState.stateFilePath)
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            status = ExtractionStatus.ERROR,
                            errorMessage = "Interrupted archive file no longer exists"
                        )
                    )
                    return@flow
                }
                RandomAccessFile(archiveFile, "rw").use { raf ->
                    extractChannelInternal(
                        channel = raf.channel,
                        fileDescriptor = raf.fd,
                        archiveName = resumeState.archiveName,
                        archivePath = resumeState.archivePath,
                        sourceUriString = null,
                        format = format,
                        entries = effectiveRemaining,
                        targetDir = targetDir,
                        isHatchMode = true,
                        isZeroCopySaf = false,
                        originalArchiveSize = resumeState.originalArchiveBytes,
                        initialReclaimedBytes = (resumeState.originalArchiveBytes - archiveFile.length()).coerceAtLeast(0L),
                        initialCompletedPaths = verifiedCompleted,
                        initialVerifiedCrcs = resumeState.verifiedEntryCrcs,
                        stateFilePath = resumeState.stateFilePath,
                        password = password ?: resumeState.password,
                        onFinalTruncateDelete = {
                            archiveFile.delete()
                        }
                    ).collect { emit(it) }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(
                ExtractionProgress(
                    archiveName = resumeState.archiveName,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "Resume failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                )
            )
        }
    }.conflate().flowOn(Dispatchers.IO)

    /**
     * Zero-Copy 64-Bit 7z (LZMA/LZMA2) Extractor supporting 6GB+ .7z archives directly over FileChannel.
     */
    private fun extractSevenZChannel(
        channel: FileChannel,
        fileDescriptor: FileDescriptor?,
        archiveName: String,
        originalArchiveSize: Long,
        targetDir: File,
        isHatchMode: Boolean,
        isZeroCopySaf: Boolean,
        password: String?,
        selectedEntryPaths: Set<String>?,
        context: Context? = null,
        targetTreeUri: Uri? = null,
        targetFolderDocUri: Uri? = null,
        onFinalTruncateDelete: () -> Unit
    ): Flow<ExtractionProgress> = flow {
        val startTime = System.currentTimeMillis()
        var lastSpeedCalcTime = startTime
        var lastEmitTime = 0L
        var bytesSinceSpeedCheck = 0L
        var currentSpeed = 0L

        val journalLogs = mutableListOf<String>()
        fun logJournal(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            journalLogs.add("[$time] $msg")
            if (journalLogs.size > 100) journalLogs.removeAt(0)
        }

        if (!targetDir.exists()) targetDir.mkdirs()

        logJournal("KAIHATCH 7Z ENGINE: 64-Bit Zero-Copy LZMA2 Stream (${formatSize(originalArchiveSize)})")

        val isHeaderEncrypted = ZipParser.detectSevenZHeaderEncryption(channel)
        if (isHeaderEncrypted && password.isNullOrEmpty()) {
            logJournal("7Z ENCRYPTED: Archive header is encrypted with AES-256 (7zAES). Password required.")
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "This .7z archive uses AES-256 header encryption. Please enter the archive password and try again.",
                    journalLogs = journalLogs.toList()
                )
            )
            return@flow
        }

        var totalUncompressedBytes = originalArchiveSize
        var bytesExtracted = 0L
        var currentDiskBytes = originalArchiveSize
        var bytesReclaimed = 0L
        var pendingTruncateEmit = false

        val truncating7zChannel = if (isHatchMode && selectedEntryPaths == null) {
            CommonsCompressBufferStrategy.HatchSevenZTruncatingChannel(
                delegate = channel,
                fd = fileDescriptor,
                originalSize = originalArchiveSize,
                isHatchMode = true,
                onTruncate = { newPhysSize, totalReclaimedNow, freedDelta ->
                    currentDiskBytes = newPhysSize
                    bytesReclaimed = totalReclaimedNow
                    pendingTruncateEmit = true
                    if (freedDelta >= 512 * 1024L || newPhysSize == 0L) {
                        logJournal("ftruncate($newPhysSize): Reclaimed ${formatSize(freedDelta)} -> Archive: ${formatSize(newPhysSize)}")
                    }
                }
            )
        } else null

        var lastOpenError: Throwable? = null
        val archive = try {
            channel.position(0L)
            if (truncating7zChannel != null) {
                CommonsCompressBufferStrategy.openTruncatingSevenZFile(
                    truncatingChannel = truncating7zChannel,
                    archiveName = archiveName,
                    password = password,
                    tryRecover = false
                )
            } else {
                CommonsCompressBufferStrategy.openDirectSevenZFile(
                    channel = channel,
                    archiveName = archiveName,
                    password = password,
                    tryRecover = false
                )
            }
        } catch (openEx1: Throwable) {
            lastOpenError = openEx1
            null
        }

        if (archive == null) {
            if (truncating7zChannel != null) {
                currentDiskBytes = truncating7zChannel.getCurrentPhysicalSize()
                bytesReclaimed = truncating7zChannel.getTotalReclaimedBytes()
            }
            val payloadConsumed = truncating7zChannel?.hasModifiedArchiveOnDisk() == true ||
                currentDiskBytes < originalArchiveSize ||
                bytesReclaimed > 0L
            val stateDesc = describeArchiveConsumptionState(
                isHatchMode = isHatchMode,
                originalSize = originalArchiveSize,
                currentDiskBytes = currentDiskBytes,
                bytesReclaimed = bytesReclaimed,
                payloadModifiedOrTruncated = payloadConsumed,
                verifiedFilesCount = 0
            )
            if (lastOpenError is PasswordRequiredException || (isHeaderEncrypted && password.isNullOrEmpty())) {
                val pwdMsg = "This .7z archive is encrypted. Please enter the archive password. [$stateDesc]"
                logJournal("7Z ERROR: $pwdMsg")
                emit(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = isHatchMode,
                        isZeroCopySaf = isZeroCopySaf,
                        status = ExtractionStatus.ERROR,
                        isCrcValid = false,
                        errorMessage = pwdMsg,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesReclaimed = bytesReclaimed,
                        journalLogs = journalLogs.toList()
                    )
                )
                return@flow
            }
            val errDetail = lastOpenError?.localizedMessage
                ?: lastOpenError?.message
                ?: "Corrupted 7z archive or unsupported compression method"
            val fullErr = "7z extraction failed: $errDetail [$stateDesc]"
            logJournal("FATAL 7Z OPEN ERROR: [${lastOpenError?.javaClass?.simpleName ?: "IOException"}] $fullErr")
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = fullErr,
                    originalArchiveBytes = originalArchiveSize,
                    currentArchiveBytesOnDisk = currentDiskBytes,
                    bytesReclaimed = bytesReclaimed,
                    journalLogs = journalLogs.toList()
                )
            )
            return@flow
        }

        val displayDir = if (context != null && targetTreeUri != null) {
            StorageHelper.getGrantedFolderInfo(context)?.let { "${it.displayName}/${targetDir.name}" } ?: targetDir.absolutePath
        } else targetDir.absolutePath

        var activeEntryTargetOut: TargetOutput? = null
        var completedCount = 0

        try {
            archive.use { sevenZArchive ->
                val allMeta = sevenZArchive.entries.toList()
                val totalEntriesCount = allMeta.size.coerceAtLeast(1)
                totalUncompressedBytes = allMeta.sumOf { if (it.size > 0L) it.size else 0L }.coerceAtLeast(originalArchiveSize)
                logJournal("7Z ARCHIVE OPENED: $totalEntriesCount entries, uncompressed total: ${formatSize(totalUncompressedBytes)}")
                allMeta.take(3).forEach { e ->
                    logJournal("  Entry: ${e.name} (${formatSize(e.size)})")
                }
                val buffer = CommonsCompressBufferStrategy.borrowIoBuffer(originalArchiveSize)

                while (coroutineContext.isActive) {
                    val entry = try {
                        sevenZArchive.nextEntry
                    } catch (pwdEx: PasswordRequiredException) {
                        logJournal("ERROR: Password required for encrypted .7z")
                        emit(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = isHatchMode,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                                status = ExtractionStatus.ERROR,
                                errorMessage = "This .7z archive is password-protected. Please enter the password and try again.",
                                journalLogs = journalLogs.toList()
                            )
                        )
                        return@flow
                    } ?: break

                    val rawPath = entry.name?.replace('\\', '/')?.trimStart('/') ?: "entry_$completedCount"
                    val shouldExtract = selectedEntryPaths == null || selectedEntryPaths.any {
                        rawPath == it || rawPath.startsWith(it.trimEnd('/') + "/")
                    }

                    if (!shouldExtract) {
                        // Drain skipped entry in solid stream
                        var rem = entry.size.coerceAtLeast(0L)
                        while (rem > 0L && coroutineContext.isActive) {
                            val r = sevenZArchive.read(buffer, 0, rem.coerceAtMost(buffer.size.toLong()).toInt())
                            if (r <= 0) break
                            rem -= r
                        }
                        continue
                    }

                    if (entry.isDirectory || rawPath.endsWith("/")) {
                        if (context != null && targetTreeUri != null && targetFolderDocUri != null) {
                            StorageHelper.getOrCreateDocumentChildDir(context, targetTreeUri, targetFolderDocUri, rawPath)
                        } else {
                            val dirFile = File(targetDir, rawPath)
                            if (!dirFile.exists()) dirFile.mkdirs()
                        }
                        completedCount++
                        continue
                    }

                    val entryTotal = entry.size.coerceAtLeast(0L)
                    var entryWritten = 0L
                    var lastProgressLogChunk = 0L
                    val crc = CRC32()

                    logJournal("Extracting: ${rawPath.substringAfterLast('/')} (${formatSize(entryTotal)})...")

                    val targetOut = StorageHelper.openTargetOutput(
                        context = context,
                        targetDir = targetDir,
                        targetTreeUri = targetTreeUri,
                        targetFolderDocUri = targetFolderDocUri,
                        relativePath = rawPath,
                        expectedBytes = entryTotal
                    )
                    activeEntryTargetOut = targetOut

                    try {
                        CommonsCompressBufferStrategy.wrapBufferedOutput(targetOut.outputStream, entryTotal).use { out ->
                            while (coroutineContext.isActive) {
                                val r = sevenZArchive.read(buffer, 0, buffer.size)
                                if (r <= 0) break
                                out.write(buffer, 0, r)
                                crc.update(buffer, 0, r)
                                entryWritten += r
                                bytesExtracted += r
                                bytesSinceSpeedCheck += r

                                if (truncating7zChannel != null) {
                                    currentDiskBytes = truncating7zChannel.getCurrentPhysicalSize()
                                    bytesReclaimed = truncating7zChannel.getTotalReclaimedBytes()
                                }

                                if (entryWritten - lastProgressLogChunk >= 500L * 1024L * 1024L) {
                                    lastProgressLogChunk = entryWritten
                                    logJournal("Streaming: ${formatSize(entryWritten)} / ${formatSize(entryTotal)} (${currentSpeed / (1024 * 1024)} MB/s)")
                                }

                                val now = System.currentTimeMillis()
                                if (now - lastSpeedCalcTime >= 300L) {
                                    currentSpeed = (bytesSinceSpeedCheck * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1L)
                                    bytesSinceSpeedCheck = 0L
                                    lastSpeedCalcTime = now
                                }
                                if (pendingTruncateEmit || now - lastEmitTime >= 160L) {
                                    val wasTruncate = pendingTruncateEmit
                                    pendingTruncateEmit = false
                                    lastEmitTime = now

                                    emit(
                                        ExtractionProgress(
                                            archiveName = archiveName,
                                            isHatchMode = isHatchMode,
                                            isZeroCopySaf = isZeroCopySaf,
                                            isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                                            status = if (wasTruncate && isHatchMode) ExtractionStatus.TRUNCATING else ExtractionStatus.EXTRACTING,
                                            totalEntries = totalEntriesCount,
                                            completedEntries = completedCount,
                                            currentFileName = rawPath.substringAfterLast('/'),
                                            currentFileBytesProcessed = entryWritten,
                                            currentFileTotalBytes = entryTotal,
                                            totalUncompressedBytes = totalUncompressedBytes,
                                            currentEntryCrc = crc.value,
                                            isCrcValid = true,
                                            originalArchiveBytes = originalArchiveSize,
                                            currentArchiveBytesOnDisk = currentDiskBytes,
                                            bytesExtracted = bytesExtracted,
                                            bytesReclaimed = bytesReclaimed,
                                            bytesPerSecond = currentSpeed,
                                            journalLogs = journalLogs.toList(),
                                            outputDirectory = displayDir,
                                            startTime = startTime,
                                            elapsedTimeMs = now - startTime
                                        )
                                    )
                                }
                            }
                            out.flush()
                        }
                    } finally {
                        targetOut.close()
                    }

                    if (truncating7zChannel != null) {
                        currentDiskBytes = truncating7zChannel.getCurrentPhysicalSize()
                        bytesReclaimed = truncating7zChannel.getTotalReclaimedBytes()
                    }

                    // Verify 7z entry uncompressed size and CRC32 (when provided in 7z header)
                    val expected7zCrc = entry.crcValue and 0xFFFFFFFFL
                    val actual7zCrc = crc.value
                    val isSizeOk = entryWritten == entryTotal
                    val isCrcOk = !entry.hasCrc || actual7zCrc == expected7zCrc
                    if (!isSizeOk || !isCrcOk) {
                        quarantineOutputFile(targetOut.localFile, context, targetOut.documentUri)
                        activeEntryTargetOut = null
                        val payloadConsumed = truncating7zChannel?.hasModifiedArchiveOnDisk() == true ||
                            currentDiskBytes < originalArchiveSize ||
                            bytesReclaimed > 0L
                        val stateDesc = describeArchiveConsumptionState(
                            isHatchMode = isHatchMode,
                            originalSize = originalArchiveSize,
                            currentDiskBytes = currentDiskBytes,
                            bytesReclaimed = bytesReclaimed,
                            payloadModifiedOrTruncated = payloadConsumed,
                            verifiedFilesCount = completedCount
                        )
                        val mismatchDetail = if (!isSizeOk) {
                            "Incomplete 7z entry '$rawPath': expected $entryTotal bytes, got $entryWritten bytes"
                        } else {
                            "CRC32 verification failed for 7z entry '$rawPath': expected 0x${String.format("%08X", expected7zCrc)}, got 0x${String.format("%08X", actual7zCrc)}"
                        }
                        val fullErr = "$mismatchDetail [$stateDesc]"
                        logJournal("7Z INTEGRITY ERROR: $fullErr")
                        emit(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = isHatchMode,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                errorMessage = fullErr,
                                totalEntries = totalEntriesCount,
                                completedEntries = completedCount,
                                currentFileName = rawPath.substringAfterLast('/'),
                                currentEntryCrc = actual7zCrc,
                                originalArchiveBytes = originalArchiveSize,
                                currentArchiveBytesOnDisk = currentDiskBytes,
                                bytesExtracted = bytesExtracted,
                                bytesReclaimed = bytesReclaimed,
                                journalLogs = journalLogs.toList()
                            )
                        )
                        return@flow
                    }

                    activeEntryTargetOut = null
                    completedCount++
                    logJournal("7Z Extracted & Verified [0x${String.format("%08X", actual7zCrc)}]: ${rawPath.substringAfterLast('/')} (${formatSize(entryWritten)})")

                    val nowAfterEntry = System.currentTimeMillis()
                    lastEmitTime = nowAfterEntry
                    pendingTruncateEmit = false
                    emit(
                        ExtractionProgress(
                            archiveName = archiveName,
                            isHatchMode = isHatchMode,
                            isZeroCopySaf = isZeroCopySaf,
                            isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                            status = ExtractionStatus.EXTRACTING,
                            totalEntries = totalEntriesCount,
                            completedEntries = completedCount,
                            currentFileName = rawPath.substringAfterLast('/'),
                            currentFileBytesProcessed = entryWritten,
                            currentFileTotalBytes = entryTotal,
                            totalUncompressedBytes = totalUncompressedBytes,
                            currentEntryCrc = actual7zCrc,
                            isCrcValid = true,
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = currentDiskBytes,
                            bytesExtracted = bytesExtracted,
                            bytesReclaimed = bytesReclaimed,
                            bytesPerSecond = currentSpeed,
                            journalLogs = journalLogs.toList(),
                            outputDirectory = displayDir,
                            startTime = startTime,
                            elapsedTimeMs = nowAfterEntry - startTime
                        )
                    )
                }

                if (!coroutineContext.isActive) {
                    emit(
                        ExtractionProgress(
                            archiveName = archiveName,
                            isHatchMode = isHatchMode,
                            isZeroCopySaf = isZeroCopySaf,
                            status = ExtractionStatus.CANCELLED,
                            totalEntries = totalEntriesCount,
                            completedEntries = completedCount,
                            totalUncompressedBytes = totalUncompressedBytes,
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = currentDiskBytes,
                            bytesExtracted = bytesExtracted,
                            bytesReclaimed = bytesReclaimed,
                            journalLogs = journalLogs.toList()
                        )
                    )
                    return@flow
                }

                if (isHatchMode) {
                    truncating7zChannel?.flushPendingRelocation()
                    if (truncating7zChannel != null) {
                        currentDiskBytes = truncating7zChannel.getCurrentPhysicalSize()
                        bytesReclaimed = truncating7zChannel.getTotalReclaimedBytes()
                    }
                    val prevDisk = currentDiskBytes
                    faultInjector.onPoint(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, archiveName)
                    truncateDescriptor(channel, fileDescriptor, 0L)
                    if (prevDisk > 0L) {
                        faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, 0L)
                    }
                    onFinalTruncateDelete()
                    logJournal("7Z COMPLETE: Reclaimed ${formatSize(originalArchiveSize)} source archive!")
                } else {
                    logJournal("7Z COMPLETE: Extracted $completedCount files (${formatSize(bytesExtracted)}).")
                }

                val finalDisplayDir = if (context != null && targetTreeUri != null) {
                    StorageHelper.getGrantedFolderInfo(context)?.let { "${it.displayName}/${targetDir.name}" } ?: targetDir.absolutePath
                } else targetDir.absolutePath

                emit(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = isHatchMode,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                        status = ExtractionStatus.COMPLETED,
                        totalEntries = completedCount.coerceAtLeast(1),
                        completedEntries = completedCount.coerceAtLeast(1),
                        currentFileName = "Complete",
                        totalUncompressedBytes = bytesExtracted.coerceAtLeast(totalUncompressedBytes),
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = if (isHatchMode) 0L else originalArchiveSize,
                        bytesExtracted = bytesExtracted,
                        bytesReclaimed = if (isHatchMode) originalArchiveSize else 0L,
                        journalLogs = journalLogs.toList(),
                        outputDirectory = finalDisplayDir,
                        startTime = startTime,
                        elapsedTimeMs = System.currentTimeMillis() - startTime
                    )
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            activeEntryTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            throw ce
        } catch (t: SimulatedFaultException) {
            throw t
        } catch (pwdEx: PasswordRequiredException) {
            activeEntryTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            logJournal("7Z ERROR: Archive password required")
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "This .7z archive is encrypted. Please enter the archive password.",
                    journalLogs = journalLogs.toList()
                )
            )
        } catch (t: Throwable) {
            activeEntryTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            if (truncating7zChannel != null) {
                currentDiskBytes = truncating7zChannel.getCurrentPhysicalSize()
                bytesReclaimed = truncating7zChannel.getTotalReclaimedBytes()
            }
            val payloadConsumed = truncating7zChannel?.hasModifiedArchiveOnDisk() == true ||
                currentDiskBytes < originalArchiveSize ||
                bytesReclaimed > 0L
            val stateDesc = describeArchiveConsumptionState(
                isHatchMode = isHatchMode,
                originalSize = originalArchiveSize,
                currentDiskBytes = currentDiskBytes,
                bytesReclaimed = bytesReclaimed,
                payloadModifiedOrTruncated = payloadConsumed,
                verifiedFilesCount = completedCount
            )
            val errClass = t.javaClass.simpleName
            val errMessage = t.localizedMessage ?: t.message ?: "Unknown decompression error"
            logJournal("FATAL 7Z ERROR: [$errClass] $errMessage ($stateDesc)")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val traceLines = sw.toString().lines().take(12)
            for (line in traceLines) {
                if (line.isNotBlank()) {
                    logJournal("  $line")
                }
            }
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = "7z extraction failed: $errMessage [$stateDesc]",
                    completedEntries = completedCount,
                    currentArchiveBytesOnDisk = currentDiskBytes,
                    bytesReclaimed = bytesReclaimed,
                    journalLogs = journalLogs.toList(),
                    bytesExtracted = bytesExtracted,
                    totalUncompressedBytes = totalUncompressedBytes,
                    originalArchiveBytes = originalArchiveSize
                )
            )
        }
    }

    private fun quarantineOutputFile(
        file: File?,
        context: Context? = null,
        docUri: Uri? = null
    ) {
        if (file != null) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Throwable) {
            }
        }
        if (context != null && docUri != null) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, docUri)
            } catch (_: Throwable) {
            }
        }
    }

    internal fun describeArchiveConsumptionState(
        isHatchMode: Boolean,
        originalSize: Long,
        currentDiskBytes: Long,
        bytesReclaimed: Long,
        payloadModifiedOrTruncated: Boolean,
        verifiedFilesCount: Int
    ): String {
        return when {
            !isHatchMode || (!payloadModifiedOrTruncated && currentDiskBytes >= originalSize && bytesReclaimed <= 0L) ->
                "SOURCE ARCHIVE STATE: INTACT (all $originalSize bytes preserved on disk)"
            !payloadModifiedOrTruncated ->
                "SOURCE ARCHIVE STATE: PAYLOAD INTACT (all entry payloads preserved; Central Directory backed up in journal; $currentDiskBytes / $originalSize bytes on disk)"
            else ->
                "SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE ($currentDiskBytes / $originalSize bytes remaining on disk, $bytesReclaimed bytes reclaimed; $verifiedFilesCount previously verified file(s) preserved)"
        }
    }

    internal fun verifyTarHeaderChecksum(block: ByteArray): Boolean {
        if (block.size < 512) return false
        var chkEnd = 148
        while (chkEnd < 156 && (block[chkEnd] == 0.toByte() || block[chkEnd] == ' '.code.toByte())) {
            if (block[chkEnd] == ' '.code.toByte()) {
                chkEnd++
            } else {
                break
            }
        }
        val chkStr = String(block, 148, 8, Charsets.US_ASCII)
            .replace("\u0000", " ")
            .trim()
        if (chkStr.isEmpty()) return false
        val expectedSum = chkStr.toLongOrNull(8) ?: return false
        var unsignedSum = 0L
        var signedSum = 0L
        for (i in 0 until 512) {
            if (i in 148..155) {
                unsignedSum += 32L
                signedSum += 32L
            } else {
                unsignedSum += (block[i].toInt() and 0xFF)
                signedSum += block[i].toLong()
            }
        }
        return expectedSum == unsignedSum || expectedSum == signedSum
    }

    private sealed class SingleEntryOutcome {
        data class Success(
            val entry: ZipEntryInfo,
            val entryOffset: Long,
            val calculatedCrc: Long,
            val isCrcMatch: Boolean
        ) : SingleEntryOutcome()

        data class Error(val message: String) : SingleEntryOutcome()
    }

    private fun readChannelAtPosition(channel: FileChannel, dst: ByteBuffer, position: Long): Int {
        return try {
            channel.read(dst, position)
        } catch (_: Throwable) {
            synchronized(channel) {
                channel.position(position)
                channel.read(dst)
            }
        }
    }

    private fun readFullyAtPosition(channel: FileChannel, dst: ByteBuffer, startPosition: Long) {
        var pos = startPosition
        while (dst.hasRemaining()) {
            val r = readChannelAtPosition(channel, dst, pos)
            if (r <= 0) break
            pos += r
        }
    }

    private fun writeChannelAtPosition(
        channel: FileChannel,
        fd: FileDescriptor?,
        src: ByteArray,
        srcOffset: Int,
        srcLength: Int,
        filePosition: Long
    ): Boolean {
        if (srcLength <= 0) return true
        try {
            val bb = ByteBuffer.wrap(src, srcOffset, srcLength)
            var pos = filePosition
            while (bb.hasRemaining()) {
                val w = channel.write(bb, pos)
                if (w <= 0) break
                pos += w
            }
            if (!bb.hasRemaining()) return true
        } catch (_: Throwable) {
        }
        if (fd != null) {
            try {
                var rem = srcLength
                var curOff = srcOffset
                var curPos = filePosition
                while (rem > 0) {
                    val w = Os.pwrite(fd, src, curOff, rem, curPos)
                    if (w <= 0) break
                    curOff += w
                    rem -= w
                    curPos += w
                }
                if (rem == 0) return true
            } catch (_: Throwable) {
            }
            try {
                val outCh = FileOutputStream(fd).channel
                val bb = ByteBuffer.wrap(src, srcOffset, srcLength)
                var pos = filePosition
                while (bb.hasRemaining()) {
                    val w = outCh.write(bb, pos)
                    if (w <= 0) break
                    pos += w
                }
                if (!bb.hasRemaining()) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun resolvePayloadOffsetPositional(
        channel: FileChannel,
        format: ArchiveFormat,
        entry: ZipEntryInfo
    ): Long {
        val entryOffset = entry.localHeaderOffset
        if (format == ArchiveFormat.TAR) {
            return entryOffset + 512L
        }
        val localHeaderBytes = ByteArray(30)
        val localHeaderBuf = ByteBuffer.wrap(localHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
        readFullyAtPosition(channel, localHeaderBuf, entryOffset)
        localHeaderBuf.flip()
        return if (localHeaderBuf.remaining() >= 30) {
            localHeaderBuf.position(26)
            val fnLen = localHeaderBuf.short.toInt() and 0xFFFF
            val efLen = localHeaderBuf.short.toInt() and 0xFFFF
            entryOffset + 30L + fnLen + efLen
        } else {
            entryOffset + 30L + entry.path.length
        }
    }

    private fun extractChannelInternal(
        channel: FileChannel,
        fileDescriptor: FileDescriptor?,
        archiveName: String,
        archivePath: String,
        sourceUriString: String?,
        format: ArchiveFormat,
        entries: List<ZipEntryInfo>,
        targetDir: File,
        isHatchMode: Boolean,
        isZeroCopySaf: Boolean,
        originalArchiveSize: Long,
        initialReclaimedBytes: Long,
        initialCompletedPaths: Set<String>,
        initialVerifiedCrcs: Map<String, Long> = emptyMap(),
        stateFilePath: String,
        password: String?,
        context: Context? = null,
        targetTreeUri: Uri? = null,
        targetFolderDocUri: Uri? = null,
        onFinalTruncateDelete: () -> Unit
    ): Flow<ExtractionProgress> = channelFlow {
        val startTime = System.currentTimeMillis()
        val atomicLastSpeedCalcTime = AtomicLong(startTime)
        val atomicLastEmitTime = AtomicLong(0L)
        val atomicBytesSinceSpeedCheck = AtomicLong(0L)
        val atomicCurrentSpeed = AtomicLong(0L)

        val isZip64 = entries.any { it.isZip64 } || originalArchiveSize >= 0xFFFFFFFFL
        val totalUncompressedBytes = entries.sumOf { it.uncompressedSize }
        val completedPaths = ConcurrentHashMap.newKeySet<String>().apply {
            addAll(initialCompletedPaths)
        }
        val verifiedEntryCrcs = ConcurrentHashMap<String, Long>().apply {
            putAll(initialVerifiedCrcs)
        }
        val journalCommitLock = Any()
        val totalEntriesCount = entries.size + initialCompletedPaths.size
        val atomicCompletedCount = AtomicInteger(initialCompletedPaths.size)
        val atomicBytesExtracted = AtomicLong(0L)
        var bytesReclaimed = initialReclaimedBytes
        var payloadConsumedInPlace = initialCompletedPaths.isNotEmpty()
        var currentDiskBytes = safeChannelSize(
            channel,
            fileDescriptor,
            (originalArchiveSize - initialReclaimedBytes).coerceAtLeast(0L)
        )

        val journalLock = Any()
        val journalLogs = mutableListOf<String>()
        var journalSnapshot: List<String> = emptyList()
        fun logJournal(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val logLine = "[$time] $msg"
            synchronized(journalLock) {
                journalLogs.add(logLine)
                if (journalLogs.size > 100) journalLogs.removeAt(0)
                journalSnapshot = journalLogs.toList()
            }
        }

        val displayDir = if (context != null && targetTreeUri != null) {
            "SAF / " + StorageHelper.getFolderDisplayName(context, targetTreeUri)
        } else {
            if (!targetDir.exists()) targetDir.mkdirs()
            targetDir.absolutePath
        }

        val isDirectPosixTarget = targetTreeUri == null || !targetDir.absolutePath.contains("/cache/")
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxWorkers = if (isDirectPosixTarget) {
            cpuCores.coerceIn(2, 6)
        } else {
            cpuCores.coerceIn(2, 3)
        }
        val workerDispatcher = Dispatchers.IO.limitedParallelism(maxWorkers)

        // Sort descending by 64-bit localHeaderOffset for Hatch Mode, or ascending for Standard Mode
        val orderedEntries = if (isHatchMode) {
            entries.sortedByDescending { it.localHeaderOffset }
        } else {
            entries.sortedBy { it.localHeaderOffset }
        }

        // Streaming binary index (.index.bin) + compact checkpoint (.ckpt.bin/.hatchstate.json)
        // supports unlimited archive entry counts (>5,000+ entries).
        val canUseResumeJournal = isHatchMode

        fun commitJournalCheckpoint(
            targetArchiveBytes: Long,
            reclaimedBytes: Long,
            detail: String,
            notifyFaultPoint: Boolean = true
        ) {
            if (!canUseResumeJournal) return
            synchronized(journalCommitLock) {
                val snapshotCompleted = LinkedHashSet(completedPaths)
                val remaining = orderedEntries.filter { !snapshotCompleted.contains(it.path) }
                ResumeJournalManager.saveState(
                    ResumeJournalState(
                        stateFilePath = stateFilePath,
                        archivePath = archivePath,
                        sourceUriString = sourceUriString,
                        archiveName = archiveName,
                        targetDirPath = targetDir.absolutePath,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytes = targetArchiveBytes,
                        bytesReclaimedSoFar = reclaimedBytes,
                        totalEntries = totalEntriesCount,
                        completedPaths = snapshotCompleted,
                        remainingEntries = remaining,
                        password = password,
                        verifiedEntryCrcs = HashMap(verifiedEntryCrcs)
                    )
                )
                if (notifyFaultPoint) {
                    faultInjector.onPoint(DestructivePoint.AFTER_JOURNAL_SAVE, targetArchiveBytes, detail)
                }
            }
        }

        if (isHatchMode) {
            logJournal("KAIHATCH ENGINE: Reverse 64-Bit Truncation (${if (isZeroCopySaf) "ZERO-COPY SAF FD" else "DIRECT FILE IO"} • $maxWorkers threads)")
            if (isZip64) logJournal("ZIP64 64-Bit Addressing Enabled (>4GB Support)")
            if (canUseResumeJournal) {
                if (initialCompletedPaths.isEmpty()) {
                    ResumeJournalManager.saveInitialIndex(stateFilePath, orderedEntries)
                }
                commitJournalCheckpoint(
                    targetArchiveBytes = currentDiskBytes,
                    reclaimedBytes = bytesReclaimed,
                    detail = "initial"
                )
                logJournal("Saved crash-safe Central Directory index (${orderedEntries.size} entries) -> ${File(stateFilePath).name}")
            }

            // Immediately reclaim the Central Directory / APK Signing Block tail after the last entry's payload
            val tailEntry = orderedEntries.firstOrNull { !it.isDirectory }
            if (tailEntry != null && format == ArchiveFormat.ZIP) {
                val tailPayloadStart = resolvePayloadOffsetPositional(channel, format, tailEntry)
                val tailPayloadEnd = tailPayloadStart + tailEntry.compressedSize.coerceAtLeast(0L) +
                        (if (tailEntry.isEncrypted) 12L else 0L) + 16L // +16L for optional Data Descriptor
                if (tailPayloadEnd in 64L until currentDiskBytes) {
                    val oldLen = currentDiskBytes
                    val freedCd = (oldLen - tailPayloadEnd).coerceAtLeast(0L)
                    // Persist updated watermark before truncating Central Directory
                    commitJournalCheckpoint(
                        targetArchiveBytes = tailPayloadEnd,
                        reclaimedBytes = bytesReclaimed + freedCd,
                        detail = "pre_cd_truncate",
                        notifyFaultPoint = false
                    )
                    faultInjector.onPoint(DestructivePoint.BEFORE_CD_TRUNCATE, oldLen, "target=$tailPayloadEnd")
                    if (truncateDescriptor(channel, fileDescriptor, tailPayloadEnd)) {
                        bytesReclaimed += freedCd
                        currentDiskBytes = tailPayloadEnd
                        faultInjector.onPhysicalTruncate(DestructivePoint.AFTER_CD_TRUNCATE, oldLen, currentDiskBytes)
                        faultInjector.onPoint(DestructivePoint.AFTER_CD_TRUNCATE, currentDiskBytes, "freed=$freedCd")
                        logJournal("ftruncate($tailPayloadEnd): Reclaimed Central Directory tail (${formatSize(freedCd)})")
                    }
                }
            }
        } else {
            logJournal("STANDARD MODE: Parallel extraction (${orderedEntries.size} entries • $maxWorkers worker threads)")
        }

        fun updateSpeedAndMaybeEmit(
            entryName: String,
            entryWritten: Long,
            entryTotal: Long,
            entryCrc: Long,
            diskBytesNow: Long = currentDiskBytes,
            reclaimedNow: Long = bytesReclaimed,
            forceEmit: Boolean = false
        ) {
            val now = System.currentTimeMillis()
            val prevSpeedTime = atomicLastSpeedCalcTime.get()
            if (now - prevSpeedTime >= 300L && atomicLastSpeedCalcTime.compareAndSet(prevSpeedTime, now)) {
                val deltaBytes = atomicBytesSinceSpeedCheck.getAndSet(0L)
                val speed = (deltaBytes * 1000L) / (now - prevSpeedTime).coerceAtLeast(1L)
                atomicCurrentSpeed.set(speed)
            }
            val prevEmit = atomicLastEmitTime.get()
            if (forceEmit || (now - prevEmit >= 160L && atomicLastEmitTime.compareAndSet(prevEmit, now))) {
                if (forceEmit) atomicLastEmitTime.set(now)
                trySend(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = isHatchMode,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.EXTRACTING,
                        totalEntries = totalEntriesCount,
                        completedEntries = atomicCompletedCount.get(),
                        currentFileName = entryName,
                        currentFileBytesProcessed = entryWritten,
                        currentFileTotalBytes = entryTotal,
                        totalUncompressedBytes = totalUncompressedBytes,
                        currentEntryCrc = entryCrc,
                        isCrcValid = true,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = diskBytesNow,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = reclaimedNow,
                        bytesPerSecond = atomicCurrentSpeed.get(),
                        journalLogs = journalSnapshot,
                        outputDirectory = displayDir,
                        startTime = startTime,
                        elapsedTimeMs = now - startTime
                    )
                )
            }
        }

        fun extractSingleEntryPositional(
            entryIdx: Int,
            entry: ZipEntryInfo
        ): SingleEntryOutcome {
            if (entry.isDirectory) {
                if (isDirectPosixTarget) {
                    val dirDest = File(targetDir, entry.path)
                    if (!dirDest.exists()) {
                        synchronized(destDirResolveLock) {
                            dirDest.mkdirs()
                        }
                    }
                } else if (context != null && targetTreeUri != null && targetFolderDocUri != null) {
                    StorageHelper.getOrCreateDocumentChildDir(context, targetTreeUri, targetFolderDocUri, entry.path)
                } else {
                    val dirDest = File(targetDir, entry.path)
                    if (!dirDest.exists()) {
                        synchronized(destDirResolveLock) {
                            dirDest.mkdirs()
                        }
                    }
                }
                atomicCompletedCount.incrementAndGet()
                completedPaths.add(entry.path)
                return SingleEntryOutcome.Success(entry, entry.localHeaderOffset, entry.crc32, true)
            }

            if (entry.isEncrypted && password.isNullOrEmpty()) {
                return SingleEntryOutcome.Error(
                    "Archive entry '${entry.name}' is password-protected. Please enter the archive password."
                )
            }

            val entryOffset = entry.localHeaderOffset
            var payloadOffset = resolvePayloadOffsetPositional(channel, format, entry)
            var compressedPayloadSize = entry.compressedSize

            // Crash-recovery guard: If this entry was already extracted or its source archive region
            // was already truncated, verify the existing output file via full-stream CRC32 before
            // opening FileOutputStream (never overwrite a valid extracted file with a 0-byte file!).
            if (isHatchMode && isDirectPosixTarget) {
                val existingDest = resolveSafeDestFile(targetDir, entry.path)
                val expectedCrc = if (entry.crc32 != 0L) entry.crc32 else verifiedEntryCrcs[entry.path]
                val physLen = safeChannelSize(channel, fileDescriptor, currentDiskBytes)
                val sourceRegionTruncated = payloadOffset + compressedPayloadSize > physLen

                if (existingDest.exists() && existingDest.isFile) {
                    if (ResumeJournalManager.verifyOutputFileIntegrity(existingDest, entry.uncompressedSize, expectedCrc)) {
                        val verifiedCrc = expectedCrc ?: ResumeJournalManager.computeFileCrc32(existingDest)
                        verifiedEntryCrcs[entry.path] = verifiedCrc
                        if (completedPaths.add(entry.path)) {
                            atomicCompletedCount.incrementAndGet()
                        }
                        atomicBytesExtracted.addAndGet(entry.uncompressedSize)
                        logJournal("RECOVERY VERIFIED [0x${String.format("%08X", verifiedCrc)}]: ${entry.name} (${formatSize(entry.uncompressedSize)})")
                        return SingleEntryOutcome.Success(entry, entryOffset, verifiedCrc, true)
                    } else if (sourceRegionTruncated) {
                        quarantineOutputFile(existingDest)
                        return SingleEntryOutcome.Error(
                            "Source archive region for '${entry.name}' was already truncated and existing output file failed CRC32 verification."
                        )
                    }
                } else if (sourceRegionTruncated && entry.uncompressedSize > 0L) {
                    return SingleEntryOutcome.Error(
                        "Source archive region for '${entry.name}' was already truncated (offset $payloadOffset + $compressedPayloadSize > $physLen) and output file is missing."
                    )
                }
            }

            if (format == ArchiveFormat.TAR) {
                val tarHeader = ByteArray(512)
                val tarHeaderBuf = ByteBuffer.wrap(tarHeader)
                readFullyAtPosition(channel, tarHeaderBuf, entryOffset)
                if (tarHeaderBuf.position() < 512 || !verifyTarHeaderChecksum(tarHeader)) {
                    return SingleEntryOutcome.Error(
                        "TAR header checksum verification failed for '${entry.name}' at offset $entryOffset"
                    )
                }
            }

            var cipher: ZipCryptoCipher? = null
            if (entry.isEncrypted && !password.isNullOrEmpty()) {
                cipher = ZipCryptoCipher(password)
                val encHeader = ByteArray(12)
                val encBuf = ByteBuffer.wrap(encHeader)
                readFullyAtPosition(channel, encBuf, payloadOffset)
                val validPass = cipher.decryptHeaderAndVerify(encHeader, entry.crc32)
                if (!validPass) {
                    return SingleEntryOutcome.Error("Incorrect password for encrypted entry '${entry.name}'")
                }
                compressedPayloadSize = (compressedPayloadSize - 12L).coerceAtLeast(0L)
                payloadOffset += 12L
            }

            updateSpeedAndMaybeEmit(
                entryName = entry.name,
                entryWritten = 0L,
                entryTotal = entry.uncompressedSize,
                entryCrc = entry.crc32,
                forceEmit = entryIdx == 0
            )

            val crc = CRC32()
            var entryBytesWritten = 0L
            val ioBuffer = CommonsCompressBufferStrategy.borrowIoBuffer(
                entry.uncompressedSize.coerceAtLeast(originalArchiveSize)
            )
            val ioByteBuf = ByteBuffer.wrap(ioBuffer)

            val targetOut = StorageHelper.openTargetOutput(
                context = context,
                targetDir = targetDir,
                targetTreeUri = targetTreeUri,
                targetFolderDocUri = targetFolderDocUri,
                relativePath = entry.path,
                expectedBytes = entry.uncompressedSize
            )

            try {
                CommonsCompressBufferStrategy.wrapBufferedOutput(
                    targetOut.outputStream,
                    entry.uncompressedSize
                ).use { outputStream ->
                    var currentReadPos = payloadOffset
                    if (entry.compressionMethod == 0 || entry.compressionMethod == 100) {
                        var remaining = entry.uncompressedSize
                        while (remaining > 0L && isActive) {
                            val toRead = remaining.coerceAtMost(ioBuffer.size.toLong()).toInt()
                            ioByteBuf.clear()
                            ioByteBuf.limit(toRead)
                            val read = readChannelAtPosition(channel, ioByteBuf, currentReadPos)
                            if (read <= 0) break
                            currentReadPos += read

                            cipher?.decryptBytesInPlace(ioBuffer, 0, read)
                            outputStream.write(ioBuffer, 0, read)
                            crc.update(ioBuffer, 0, read)

                            remaining -= read
                            entryBytesWritten += read
                            atomicBytesExtracted.addAndGet(read.toLong())
                            atomicBytesSinceSpeedCheck.addAndGet(read.toLong())

                            updateSpeedAndMaybeEmit(
                                entryName = entry.name,
                                entryWritten = entryBytesWritten,
                                entryTotal = entry.uncompressedSize,
                                entryCrc = entry.crc32
                            )
                        }
                    } else {
                        val inflateOutBuffer = CommonsCompressBufferStrategy.borrowInflateBuffer(
                            entry.uncompressedSize.coerceAtLeast(originalArchiveSize)
                        )
                        val inflater = Inflater(true)
                        var compressedRemaining = compressedPayloadSize

                        try {
                            while ((compressedRemaining > 0L || !inflater.finished()) && isActive) {
                                if (inflater.needsInput() && compressedRemaining > 0L) {
                                    val toRead = compressedRemaining.coerceAtMost(ioBuffer.size.toLong()).toInt()
                                    ioByteBuf.clear()
                                    ioByteBuf.limit(toRead)
                                    val read = readChannelAtPosition(channel, ioByteBuf, currentReadPos)
                                    if (read <= 0) break
                                    currentReadPos += read
                                    cipher?.decryptBytesInPlace(ioBuffer, 0, read)
                                    inflater.setInput(ioBuffer, 0, read)
                                    compressedRemaining -= read
                                }

                                val decompressed = inflater.inflate(inflateOutBuffer)
                                if (decompressed > 0) {
                                    outputStream.write(inflateOutBuffer, 0, decompressed)
                                    crc.update(inflateOutBuffer, 0, decompressed)
                                    entryBytesWritten += decompressed
                                    atomicBytesExtracted.addAndGet(decompressed.toLong())
                                    atomicBytesSinceSpeedCheck.addAndGet(decompressed.toLong())

                                    updateSpeedAndMaybeEmit(
                                        entryName = entry.name,
                                        entryWritten = entryBytesWritten,
                                        entryTotal = entry.uncompressedSize,
                                        entryCrc = entry.crc32
                                    )
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
                    outputStream.flush()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                try {
                    targetOut.close()
                } catch (_: Throwable) {
                }
                quarantineOutputFile(targetOut.localFile, context, targetOut.documentUri)
                throw ce
            } catch (fe: SimulatedFaultException) {
                try {
                    targetOut.close()
                } catch (_: Throwable) {
                }
                throw fe
            } catch (t: Throwable) {
                try {
                    targetOut.close()
                } catch (_: Throwable) {
                }
                quarantineOutputFile(targetOut.localFile, context, targetOut.documentUri)
                val errDetail = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                logJournal("DECOMPRESSION ERROR: ${entry.name} -> [${t.javaClass.simpleName}] $errDetail")
                return SingleEntryOutcome.Error(
                    "Decompression failed for '${entry.name}': $errDetail"
                )
            } finally {
                try {
                    targetOut.close()
                } catch (_: Throwable) {
                }
            }

            val calculatedCrc = crc.value
            val isSizeMatch = entryBytesWritten == entry.uncompressedSize
            val isCrcMatch = isSizeMatch && (format == ArchiveFormat.TAR || entry.crc32 == 0L || entry.crc32 == calculatedCrc)
            if (!isCrcMatch) {
                quarantineOutputFile(targetOut.localFile, context, targetOut.documentUri)
                logJournal("CRC MISMATCH: ${entry.name} expected ${entry.crcHex} (${entry.uncompressedSize} B) got 0x${String.format("%08X", calculatedCrc)} ($entryBytesWritten B)")
                return SingleEntryOutcome.Error(
                    "CRC32 verification failed for '${entry.name}': expected ${entry.crcHex} (${entry.uncompressedSize} B), got 0x${String.format("%08X", calculatedCrc)} ($entryBytesWritten B)"
                )
            } else if (orderedEntries.size <= 250 || entryIdx % 50 == 0 || entry.uncompressedSize >= 1024 * 1024) {
                logJournal("CRC OK [${entry.crcHex}]: ${entry.name} (${formatSize(entry.uncompressedSize)})")
            }

            verifiedEntryCrcs[entry.path] = calculatedCrc
            if (completedPaths.add(entry.path)) {
                atomicCompletedCount.incrementAndGet()
            }
            return SingleEntryOutcome.Success(entry, entryOffset, calculatedCrc, true)
        }

        // Partition entries into execution windows:
        // - Standard Mode: 1 full parallel work-stealing pool across all entries
        // - KaiHatch In-Place Mode:
        //   * Entries >= 4 MB (both STORED and DEFLATED) use live sub-entry progressive chunk ftruncate()
        //   * Archive with <= 16 entries processes each entry with immediate per-file ftruncate()
        //   * Archive with > 16 entries batches small (< 4 MB) entries across worker threads with live
        //     tail-watermark ftruncate() as each entry completes
        val subEntryThresholdBytes = 4L * 1024L * 1024L
        data class IndexedZipEntry(val index: Int, val entry: ZipEntryInfo, val useSubEntryReverseChunk: Boolean)

        val indexedEntries = orderedEntries.mapIndexed { idx, entry ->
            val useSubChunk = isHatchMode &&
                    !entry.isDirectory &&
                    !entry.isEncrypted &&
                    (entry.compressionMethod == 0 || entry.compressionMethod == 100 || entry.compressionMethod == 8) &&
                    (entry.uncompressedSize >= subEntryThresholdBytes || entry.compressedSize >= subEntryThresholdBytes)
            IndexedZipEntry(idx, entry, useSubChunk)
        }

        val executionWindows = mutableListOf<List<IndexedZipEntry>>()
        if (!isHatchMode) {
            if (indexedEntries.isNotEmpty()) {
                executionWindows.add(indexedEntries)
            }
        } else {
            val maxWindowCompressedBytes = 4L * 1024L * 1024L
            val maxWindowUncompressedBytes = 8L * 1024L * 1024L
            val maxWindowEntries = if (indexedEntries.size <= 16) 1 else maxWorkers.coerceIn(2, 6)

            var currentWindow = mutableListOf<IndexedZipEntry>()
            val currentWindowPaths = HashSet<String>()
            var currentWinCompressed = 0L
            var currentWinUncompressed = 0L

            for (item in indexedEntries) {
                if (item.useSubEntryReverseChunk) {
                    if (currentWindow.isNotEmpty()) {
                        executionWindows.add(currentWindow)
                        currentWindow = mutableListOf()
                        currentWindowPaths.clear()
                        currentWinCompressed = 0L
                        currentWinUncompressed = 0L
                    }
                    executionWindows.add(listOf(item))
                    continue
                }

                val exceedsCount = currentWindow.size >= maxWindowEntries
                val exceedsCompressed = currentWindow.isNotEmpty() &&
                        (currentWinCompressed + item.entry.compressedSize > maxWindowCompressedBytes)
                val exceedsUncompressed = currentWindow.isNotEmpty() &&
                        (currentWinUncompressed + item.entry.uncompressedSize > maxWindowUncompressedBytes)
                val duplicatePath = currentWindowPaths.contains(item.entry.path)

                if (exceedsCount || exceedsCompressed || exceedsUncompressed || duplicatePath) {
                    executionWindows.add(currentWindow)
                    currentWindow = mutableListOf()
                    currentWindowPaths.clear()
                    currentWinCompressed = 0L
                    currentWinUncompressed = 0L
                }

                currentWindow.add(item)
                currentWindowPaths.add(item.entry.path)
                currentWinCompressed += item.entry.compressedSize
                currentWinUncompressed += item.entry.uncompressedSize
            }
            if (currentWindow.isNotEmpty()) {
                executionWindows.add(currentWindow)
            }
        }

        val fatalError = AtomicReference<String?>(null)

        for ((winIdx, window) in executionWindows.withIndex()) {
            if (!coroutineContext.isActive) {
                send(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = isHatchMode,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.CANCELLED,
                        totalEntries = totalEntriesCount,
                        completedEntries = atomicCompletedCount.get(),
                        totalUncompressedBytes = totalUncompressedBytes,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = bytesReclaimed,
                        journalLogs = journalSnapshot
                    )
                )
                return@channelFlow
            }

            // Case 1: Large Entry (>= 4 MB) in Hatch Mode -> Sub-Entry Progressive Chunk ftruncate()
            if (window.size == 1 && window[0].useSubEntryReverseChunk) {
                val item = window[0]
                val entry = item.entry
                val entryIdx = item.index
                val entryOffset = entry.localHeaderOffset
                val payloadOffset = resolvePayloadOffsetPositional(channel, format, entry)
                val compressedPayloadSize = entry.compressedSize.coerceAtLeast(0L)

                // Reclaim any trailing bytes after this entry's payload before chunking
                val entryPayloadEnd = payloadOffset + compressedPayloadSize
                if (entryPayloadEnd in 64L until currentDiskBytes) {
                    val oldLen = currentDiskBytes
                    if (truncateDescriptor(channel, fileDescriptor, entryPayloadEnd)) {
                        val freedTail = (oldLen - entryPayloadEnd).coerceAtLeast(0L)
                        bytesReclaimed += freedTail
                        currentDiskBytes = entryPayloadEnd
                    }
                }

                val isStoredEntry = (entry.compressionMethod == 0 || entry.compressionMethod == 100)
                if (isStoredEntry && isDirectPosixTarget) {
                    val destFile = resolveSafeDestFile(targetDir, entry.path)
                    val expectedStoredCrc = if (entry.crc32 != 0L) entry.crc32 else verifiedEntryCrcs[entry.path]
                    val alreadyVerifiedOnDisk = destFile.exists() && destFile.isFile &&
                            ResumeJournalManager.verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedStoredCrc)

                    var combinedReverseSliceCrc: Long? = null

                    if (!alreadyVerifiedOnDisk) {
                        val physLenNow = safeChannelSize(channel, fileDescriptor, currentDiskBytes)
                        val availableInArchive = (physLenNow - payloadOffset).coerceIn(0L, entry.uncompressedSize)

                        if (availableInArchive < entry.uncompressedSize &&
                            (!destFile.exists() || destFile.length() != entry.uncompressedSize)
                        ) {
                            quarantineOutputFile(destFile)
                            val stateDesc = describeArchiveConsumptionState(
                                isHatchMode = isHatchMode,
                                originalSize = originalArchiveSize,
                                currentDiskBytes = currentDiskBytes,
                                bytesReclaimed = bytesReclaimed,
                                payloadModifiedOrTruncated = true,
                                verifiedFilesCount = completedPaths.size
                            )
                            val errMsg = "Unrecoverable incomplete STORED entry '${entry.name}': source archive was already truncated ($availableInArchive / ${entry.uncompressedSize} bytes remaining) and output file is missing or incomplete [$stateDesc]"
                            logJournal("UNRECOVERABLE STORED STATE: $errMsg")
                            send(
                                ExtractionProgress(
                                    archiveName = archiveName,
                                    isHatchMode = isHatchMode,
                                    isZeroCopySaf = isZeroCopySaf,
                                    isZip64 = isZip64,
                                    status = ExtractionStatus.ERROR,
                                    isCrcValid = false,
                                    errorMessage = errMsg,
                                    currentArchiveBytesOnDisk = currentDiskBytes,
                                    originalArchiveBytes = originalArchiveSize,
                                    bytesExtracted = atomicBytesExtracted.get(),
                                    bytesReclaimed = bytesReclaimed,
                                    journalLogs = journalSnapshot
                                )
                            )
                            return@channelFlow
                        }

                        if (format == ArchiveFormat.TAR) {
                            val tarHeader = ByteArray(512)
                            val tarHeaderBuf = ByteBuffer.wrap(tarHeader)
                            readFullyAtPosition(channel, tarHeaderBuf, entryOffset)
                            if (tarHeaderBuf.position() < 512 || !verifyTarHeaderChecksum(tarHeader)) {
                                quarantineOutputFile(destFile)
                                val stateDesc = describeArchiveConsumptionState(
                                    isHatchMode = isHatchMode,
                                    originalSize = originalArchiveSize,
                                    currentDiskBytes = currentDiskBytes,
                                    bytesReclaimed = bytesReclaimed,
                                    payloadModifiedOrTruncated = payloadConsumedInPlace,
                                    verifiedFilesCount = completedPaths.size
                                )
                                val errMsg = "TAR header checksum verification failed for '${entry.name}' at offset $entryOffset [$stateDesc]"
                                logJournal("TAR HEADER ERROR: $errMsg")
                                send(
                                    ExtractionProgress(
                                        archiveName = archiveName,
                                        isHatchMode = isHatchMode,
                                        isZeroCopySaf = isZeroCopySaf,
                                        isZip64 = isZip64,
                                        status = ExtractionStatus.ERROR,
                                        isCrcValid = false,
                                        errorMessage = errMsg,
                                        currentArchiveBytesOnDisk = currentDiskBytes,
                                        originalArchiveBytes = originalArchiveSize,
                                        bytesExtracted = atomicBytesExtracted.get(),
                                        bytesReclaimed = bytesReclaimed,
                                        journalLogs = journalSnapshot
                                    )
                                )
                                return@channelFlow
                            }
                        }

                        val chunkStep = (entry.uncompressedSize / 8L)
                            .coerceIn(1024L * 1024L, 8L * 1024L * 1024L)
                        logJournal("Sub-entry ${formatSize(chunkStep)} reverse chunk truncation active for ${entry.name} (${formatSize(entry.uncompressedSize)})")
                        val sharedIoBuffer = CommonsCompressBufferStrategy.borrowIoBuffer(originalArchiveSize)
                        val sharedByteBuf = ByteBuffer.wrap(sharedIoBuffer)
                        var entryBytesWritten = 0L

                        data class SliceCrcRecord(val sliceStart: Long, val sliceLen: Long, val crc32: Long)
                        val sliceCrcRecords = ArrayList<SliceCrcRecord>(16)
                        var shortReadError: String? = null

                        RandomAccessFile(destFile, "rw").use { destRaf ->
                            var sliceEnd = if (destFile.exists() && destFile.length() == entry.uncompressedSize && availableInArchive in 1L until entry.uncompressedSize) {
                                availableInArchive
                            } else {
                                entry.uncompressedSize
                            }

                            while (sliceEnd > 0L && coroutineContext.isActive) {
                                val sliceStart = (sliceEnd - chunkStep).coerceAtLeast(0L)
                                var posInSlice = sliceStart
                                val sliceCrc = CRC32()
                                destRaf.seek(sliceStart)

                                while (posInSlice < sliceEnd && coroutineContext.isActive) {
                                    val toRead = (sliceEnd - posInSlice).coerceAtMost(sharedIoBuffer.size.toLong()).toInt()
                                    sharedByteBuf.clear()
                                    sharedByteBuf.limit(toRead)
                                    val read = readChannelAtPosition(channel, sharedByteBuf, payloadOffset + posInSlice)
                                    if (read <= 0) break

                                    destRaf.write(sharedIoBuffer, 0, read)
                                    sliceCrc.update(sharedIoBuffer, 0, read)
                                    posInSlice += read
                                    entryBytesWritten += read
                                    atomicBytesExtracted.addAndGet(read.toLong())
                                    atomicBytesSinceSpeedCheck.addAndGet(read.toLong())

                                    updateSpeedAndMaybeEmit(
                                        entryName = entry.name,
                                        entryWritten = entryBytesWritten,
                                        entryTotal = entry.uncompressedSize,
                                        entryCrc = entry.crc32,
                                        diskBytesNow = currentDiskBytes,
                                        reclaimedNow = bytesReclaimed
                                    )
                                }

                                if (posInSlice < sliceEnd && coroutineContext.isActive) {
                                    shortReadError = "Short read in STORED entry '${entry.name}' at slice [$sliceStart..$sliceEnd): read ${posInSlice - sliceStart} of ${sliceEnd - sliceStart} bytes"
                                    break
                                }

                                sliceCrcRecords.add(
                                    SliceCrcRecord(
                                        sliceStart = sliceStart,
                                        sliceLen = sliceEnd - sliceStart,
                                        crc32 = sliceCrc.value
                                    )
                                )

                                // Ensure slice bytes are durably synced to destFile before truncating archive!
                                try {
                                    destRaf.fd.sync()
                                } catch (_: Throwable) {
                                }

                                // For intermediate slices (sliceStart > 0L), truncate down to payloadOffset + sliceStart.
                                // For the final slice (sliceStart == 0L), verify full-file CRC32 BEFORE truncating the final slice!
                                if (sliceStart > 0L) {
                                    val targetTruncate = payloadOffset + sliceStart
                                    if (targetTruncate in 0L until currentDiskBytes) {
                                        val oldLen = currentDiskBytes
                                        val freedSlice = (oldLen - targetTruncate).coerceAtLeast(0L)
                                        commitJournalCheckpoint(
                                            targetArchiveBytes = targetTruncate,
                                            reclaimedBytes = bytesReclaimed + freedSlice,
                                            detail = "stored_slice_${entry.name}_$sliceStart",
                                            notifyFaultPoint = false
                                        )
                                        faultInjector.onPoint(
                                            DestructivePoint.BEFORE_STORED_SLICE_TRUNCATE,
                                            oldLen,
                                            "entry=${entry.name},sliceStart=$sliceStart,target=$targetTruncate"
                                        )
                                        if (truncateDescriptor(channel, fileDescriptor, targetTruncate)) {
                                            payloadConsumedInPlace = true
                                            bytesReclaimed += freedSlice
                                            currentDiskBytes = targetTruncate
                                            faultInjector.onPhysicalTruncate(
                                                DestructivePoint.AFTER_STORED_SLICE_TRUNCATE,
                                                oldLen,
                                                currentDiskBytes
                                            )
                                            faultInjector.onPoint(
                                                DestructivePoint.AFTER_STORED_SLICE_TRUNCATE,
                                                currentDiskBytes,
                                                "entry=${entry.name},sliceStart=$sliceStart"
                                            )
                                            logJournal("ftruncate($targetTruncate): Reclaimed ${formatSize(freedSlice)} -> Archive: ${formatSize(currentDiskBytes)}")
                                            updateSpeedAndMaybeEmit(
                                                entryName = entry.name,
                                                entryWritten = entryBytesWritten,
                                                entryTotal = entry.uncompressedSize,
                                                entryCrc = entry.crc32,
                                                diskBytesNow = currentDiskBytes,
                                                reclaimedNow = bytesReclaimed,
                                                forceEmit = true
                                            )
                                        }
                                    }
                                }
                                sliceEnd = sliceStart
                            }
                        }

                        if (shortReadError != null) {
                            quarantineOutputFile(destFile)
                            val stateDesc = describeArchiveConsumptionState(
                                isHatchMode = isHatchMode,
                                originalSize = originalArchiveSize,
                                currentDiskBytes = currentDiskBytes,
                                bytesReclaimed = bytesReclaimed,
                                payloadModifiedOrTruncated = payloadConsumedInPlace,
                                verifiedFilesCount = completedPaths.size
                            )
                            val fullErr = "$shortReadError [$stateDesc]"
                            logJournal("STORED SHORT-READ ERROR: $fullErr")
                            send(
                                ExtractionProgress(
                                    archiveName = archiveName,
                                    isHatchMode = isHatchMode,
                                    isZeroCopySaf = isZeroCopySaf,
                                    isZip64 = isZip64,
                                    status = ExtractionStatus.ERROR,
                                    isCrcValid = false,
                                    errorMessage = fullErr,
                                    currentArchiveBytesOnDisk = currentDiskBytes,
                                    originalArchiveBytes = originalArchiveSize,
                                    bytesExtracted = atomicBytesExtracted.get(),
                                    bytesReclaimed = bytesReclaimed,
                                    journalLogs = journalSnapshot
                                )
                            )
                            return@channelFlow
                        }

                        // Combine reverse-extracted slice CRC32s in forward logical order (sliceStart = 0 ascending)
                        // using GF(2) matrix squaring (`ResumeJournalManager.crc32Combine`).
                        val sortedSlices = sliceCrcRecords.sortedBy { it.sliceStart }
                        var expectedNextStart = 0L
                        var runningForwardCrc = 0L
                        var coversFullEntry = sortedSlices.isNotEmpty()
                        for (rec in sortedSlices) {
                            if (rec.sliceStart != expectedNextStart) {
                                coversFullEntry = false
                                break
                            }
                            runningForwardCrc = if (expectedNextStart == 0L) {
                                rec.crc32
                            } else {
                                ResumeJournalManager.crc32Combine(runningForwardCrc, rec.crc32, rec.sliceLen)
                            }
                            expectedNextStart += rec.sliceLen
                        }
                        if (coversFullEntry && expectedNextStart == entry.uncompressedSize) {
                            combinedReverseSliceCrc = runningForwardCrc
                        }
                    }

                    // Verify reverse-slice combined CRC32 (and full-file CRC32 on resume) before final slice / header truncation
                    val actualStoredCrc = combinedReverseSliceCrc ?: ResumeJournalManager.computeFileCrc32(destFile)
                    val isStoredCrcValid = destFile.exists() &&
                            destFile.length() == entry.uncompressedSize &&
                            (format == ArchiveFormat.TAR || entry.crc32 == 0L || actualStoredCrc == entry.crc32)
                    if (!isStoredCrcValid) {
                        quarantineOutputFile(destFile)
                        val stateDesc = describeArchiveConsumptionState(
                            isHatchMode = isHatchMode,
                            originalSize = originalArchiveSize,
                            currentDiskBytes = currentDiskBytes,
                            bytesReclaimed = bytesReclaimed,
                            payloadModifiedOrTruncated = payloadConsumedInPlace,
                            verifiedFilesCount = completedPaths.size
                        )
                        val errMsg = "CRC32 mismatch in STORED entry '${entry.name}': expected ${entry.crcHex}, got 0x${String.format("%08X", actualStoredCrc)} [$stateDesc]"
                        logJournal("CRC MISMATCH: $errMsg")
                        send(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = isHatchMode,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = isZip64,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                errorMessage = errMsg,
                                currentArchiveBytesOnDisk = currentDiskBytes,
                                originalArchiveBytes = originalArchiveSize,
                                bytesExtracted = atomicBytesExtracted.get(),
                                bytesReclaimed = bytesReclaimed,
                                journalLogs = journalSnapshot
                            )
                        )
                        return@channelFlow
                    }

                    verifiedEntryCrcs[entry.path] = actualStoredCrc
                    if (completedPaths.add(entry.path)) {
                        atomicCompletedCount.incrementAndGet()
                    }
                    logJournal("CRC OK [${entry.crcHex}]: ${entry.name} (${formatSize(entry.uncompressedSize)})")

                    // Commit journal checkpoint BEFORE truncating the final STORED slice & Local File Header!
                    val targetFinalSlice = entryOffset.coerceAtLeast(0L)
                    val pendingFreed = (currentDiskBytes - targetFinalSlice).coerceAtLeast(0L)
                    commitJournalCheckpoint(
                        targetArchiveBytes = targetFinalSlice,
                        reclaimedBytes = bytesReclaimed + pendingFreed,
                        detail = "entry=${entry.name}"
                    )

                    if (targetFinalSlice in 0L until currentDiskBytes) {
                        val oldLen = currentDiskBytes
                        faultInjector.onPoint(
                            DestructivePoint.BEFORE_STORED_SLICE_TRUNCATE,
                            oldLen,
                            "entry=${entry.name},sliceStart=0,target=$targetFinalSlice"
                        )
                        if (truncateDescriptor(channel, fileDescriptor, targetFinalSlice)) {
                            payloadConsumedInPlace = true
                            val freedSlice = (oldLen - targetFinalSlice).coerceAtLeast(0L)
                            bytesReclaimed += freedSlice
                            currentDiskBytes = targetFinalSlice
                            faultInjector.onPhysicalTruncate(
                                DestructivePoint.AFTER_STORED_SLICE_TRUNCATE,
                                oldLen,
                                currentDiskBytes
                            )
                            faultInjector.onPoint(
                                DestructivePoint.AFTER_STORED_SLICE_TRUNCATE,
                                currentDiskBytes,
                                "entry=${entry.name},sliceStart=0"
                            )
                            logJournal("ftruncate($targetFinalSlice): Reclaimed ${formatSize(freedSlice)} -> Archive: ${formatSize(currentDiskBytes)}")
                        }
                    }
                } else {
                    // DEFLATED (method == 8) or SAF target -> Symmetric Tail-Swap Sub-Entry Chunk Truncation
                    // Uses Decompress-First, Relocate-Second ordering with Java's Inflater:
                    // 1. Decompressed output for chunk `c` is flushed and fsync'd to disk BEFORE any
                    //    compressed bytes in the archive are overwritten (`pwrite`) or truncated (`ftruncate`).
                    // 2. Distinguishes recoverable crashes (before slot 0 overwrite or after full entry
                    //    decompression) from unrecoverable mid-stream crashes (after compressed head slots
                    //    have already been overwritten/truncated).
                    val destFile = if (isDirectPosixTarget) resolveSafeDestFile(targetDir, entry.path) else null
                    val expectedDeflateCrc = if (entry.crc32 != 0L) entry.crc32 else verifiedEntryCrcs[entry.path]
                    val alreadyVerifiedOnDisk = destFile != null && destFile.exists() && destFile.isFile &&
                            ResumeJournalManager.verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedDeflateCrc)

                    if (alreadyVerifiedOnDisk && destFile != null) {
                        val verifiedCrc = expectedDeflateCrc ?: ResumeJournalManager.computeFileCrc32(destFile)
                        ResumeJournalManager.clearTailSwapGuard(stateFilePath)
                        verifiedEntryCrcs[entry.path] = verifiedCrc
                        if (completedPaths.add(entry.path)) {
                            atomicCompletedCount.incrementAndGet()
                        }
                        atomicBytesExtracted.addAndGet(entry.uncompressedSize)
                        logJournal("RECOVERY VERIFIED [0x${String.format("%08X", verifiedCrc)}]: ${entry.name} (${formatSize(entry.uncompressedSize)})")

                        val targetOffset = entryOffset.coerceAtLeast(0L)
                        val pendingFreed = (currentDiskBytes - targetOffset).coerceAtLeast(0L)
                        commitJournalCheckpoint(
                            targetArchiveBytes = targetOffset,
                            reclaimedBytes = bytesReclaimed + pendingFreed,
                            detail = "entry=${entry.name}"
                        )
                        if (entryOffset in 0L until currentDiskBytes) {
                            val oldLen = currentDiskBytes
                            faultInjector.onPoint(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, "entry=${entry.name},target=$entryOffset")
                            if (truncateDescriptor(channel, fileDescriptor, entryOffset)) {
                                payloadConsumedInPlace = true
                                val freed = (oldLen - entryOffset).coerceAtLeast(0L)
                                bytesReclaimed += freed
                                currentDiskBytes = entryOffset
                                faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, currentDiskBytes)
                                faultInjector.onPoint(DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL, currentDiskBytes, "entry=${entry.name}")
                            }
                        }
                    } else {
                        val physLenNow = safeChannelSize(channel, fileDescriptor, currentDiskBytes)
                        val activeGuard = if (canUseResumeJournal) ResumeJournalManager.loadTailSwapGuard(stateFilePath) else null

                        if (activeGuard != null && activeGuard.entryPath == entry.path) {
                            val expectedEnd = activeGuard.payloadOffset + activeGuard.compressedSize
                            var slot0ActualCrc = -1L
                            if (physLenNow >= activeGuard.payloadOffset + activeGuard.chunk0Len && activeGuard.chunk0Len > 0) {
                                val checkBuf = ByteArray(activeGuard.chunk0Len)
                                val checkBb = ByteBuffer.wrap(checkBuf)
                                readFullyAtPosition(channel, checkBb, activeGuard.payloadOffset)
                                if (checkBb.position() == activeGuard.chunk0Len) {
                                    val crcCheck = CRC32()
                                    crcCheck.update(checkBuf, 0, activeGuard.chunk0Len)
                                    slot0ActualCrc = crcCheck.value
                                }
                            }

                            if (!activeGuard.overwriteStarted &&
                                physLenNow >= expectedEnd &&
                                slot0ActualCrc == activeGuard.chunk0OriginalCrc32
                            ) {
                                // Recoverable interruption: crash occurred before pwrite modified slot 0!
                                ResumeJournalManager.clearTailSwapGuard(stateFilePath)
                                logJournal(
                                    "RECOVERY RESTART: Source compressed bytes for '${entry.name}' are intact (slot 0 CRC 0x${String.format("%08X", slot0ActualCrc)} verified); restarting entry decompression"
                                )
                            } else {
                                quarantineOutputFile(destFile)
                                val stateDesc = describeArchiveConsumptionState(
                                    isHatchMode = isHatchMode,
                                    originalSize = originalArchiveSize,
                                    currentDiskBytes = currentDiskBytes,
                                    bytesReclaimed = bytesReclaimed,
                                    payloadModifiedOrTruncated = true,
                                    verifiedFilesCount = completedPaths.size
                                )
                                val reason = if (physLenNow >= expectedEnd) {
                                    "compressed head slot 0 was already overwritten via pwrite (expected CRC 0x${String.format("%08X", activeGuard.chunk0OriginalCrc32)}, got 0x${String.format("%08X", slot0ActualCrc)}, overwriteStarted=${activeGuard.overwriteStarted})"
                                } else {
                                    "compressed data was already overwritten and truncated ($physLenNow < $expectedEnd)"
                                }
                                val errMsg = "Unrecoverable mid-tail-swap interruption for '${entry.name}': $reason before entry completion, and Java Inflater cannot resume mid-stream. [$stateDesc]"
                                logJournal("UNRECOVERABLE TAIL-SWAP STATE: $errMsg")
                                send(
                                    ExtractionProgress(
                                        archiveName = archiveName,
                                        isHatchMode = isHatchMode,
                                        isZeroCopySaf = isZeroCopySaf,
                                        isZip64 = isZip64,
                                        status = ExtractionStatus.ERROR,
                                        isCrcValid = false,
                                        errorMessage = errMsg,
                                        currentArchiveBytesOnDisk = currentDiskBytes,
                                        originalArchiveBytes = originalArchiveSize,
                                        bytesExtracted = atomicBytesExtracted.get(),
                                        bytesReclaimed = bytesReclaimed,
                                        journalLogs = journalSnapshot
                                    )
                                )
                                return@channelFlow
                            }
                        } else if (payloadOffset + compressedPayloadSize > physLenNow) {
                            quarantineOutputFile(destFile)
                            val stateDesc = describeArchiveConsumptionState(
                                isHatchMode = isHatchMode,
                                originalSize = originalArchiveSize,
                                currentDiskBytes = currentDiskBytes,
                                bytesReclaimed = bytesReclaimed,
                                payloadModifiedOrTruncated = true,
                                verifiedFilesCount = completedPaths.size
                            )
                            val errMsg = "Unrecoverable mid-tail-swap interruption for '${entry.name}': source archive region was already truncated ($physLenNow < ${payloadOffset + compressedPayloadSize}) before entry completion. [$stateDesc]"
                            logJournal("UNRECOVERABLE TAIL-SWAP STATE: $errMsg")
                            send(
                                ExtractionProgress(
                                    archiveName = archiveName,
                                    isHatchMode = isHatchMode,
                                    isZeroCopySaf = isZeroCopySaf,
                                    isZip64 = isZip64,
                                    status = ExtractionStatus.ERROR,
                                    isCrcValid = false,
                                    errorMessage = errMsg,
                                    currentArchiveBytesOnDisk = currentDiskBytes,
                                    originalArchiveBytes = originalArchiveSize,
                                    bytesExtracted = atomicBytesExtracted.get(),
                                    bytesReclaimed = bytesReclaimed,
                                    journalLogs = journalSnapshot
                                )
                            )
                            return@channelFlow
                        }

                        val chunkStep = computeLargeDeflateChunkStep(compressedPayloadSize)
                        val totalChunks = ((compressedPayloadSize + chunkStep.toLong() - 1L) / chunkStep.toLong())
                            .toInt()
                            .coerceAtLeast(1)
                        logJournal("Sub-entry ${formatSize(chunkStep.toLong())} tail-swap inflate truncation active for ${entry.name} ($totalChunks chunks)")

                        // Single bounded chunk buffer reused sequentially:
                        // 1) Reads compressed chunk `c` and feeds it completely into Java's Inflater.
                        // 2) Once chunk `c` is fully inflated (and chunk 0's CRC32 recorded), reuses the same
                        //    buffer to read and relocate `tailIdx` via pwrite, cutting buffer memory in half.
                        val activeChunkBuf = ByteArray(chunkStep)
                        val relocatedSlot = IntArray(totalChunks) { -1 }
                        val relocatedLen = IntArray(totalChunks) { 0 }
                        var canSwapInPlace = totalChunks > 1

                        fun expectedDeflateChunkLen(cIdx: Int): Int {
                            val start = cIdx.toLong() * chunkStep.toLong()
                            val rem = (compressedPayloadSize - start).coerceAtLeast(0L)
                            return rem.coerceAtMost(chunkStep.toLong()).toInt()
                        }

                        val inflateOutBuffer = CommonsCompressBufferStrategy.borrowInflateBuffer(
                            entry.uncompressedSize.coerceAtLeast(originalArchiveSize)
                        )
                        val crc = CRC32()
                        var entryBytesWritten = 0L

                        val targetOut = StorageHelper.openTargetOutput(
                            context = context,
                            targetDir = targetDir,
                            targetTreeUri = targetTreeUri,
                            targetFolderDocUri = targetFolderDocUri,
                            relativePath = entry.path,
                            expectedBytes = entry.uncompressedSize
                        )

                        try {
                            CommonsCompressBufferStrategy.wrapBufferedOutput(
                                targetOut.outputStream,
                                entry.uncompressedSize
                            ).use { outputStream ->
                                val rawFileOut = targetOut.outputStream as? FileOutputStream
                                val inflater = Inflater(true)

                                try {
                                    for (c in 0 until totalChunks) {
                                        if (!coroutineContext.isActive) break
                                        val cLen = expectedDeflateChunkLen(c)
                                        val tailIdx = totalChunks - 1 - c

                                        // 1. Read compressed chunk `c` into `activeChunkBuf` (without mutating archive yet)
                                        val activeChunkLimit: Int
                                        var secondHalfSlot = -1
                                        var secondHalfReadPos = 0L
                                        if (!canSwapInPlace || c <= tailIdx) {
                                            val bb = ByteBuffer.wrap(activeChunkBuf, 0, cLen)
                                            readFullyAtPosition(channel, bb, payloadOffset + c.toLong() * chunkStep.toLong())
                                            activeChunkLimit = bb.position()
                                        } else {
                                            secondHalfSlot = relocatedSlot[c]
                                            secondHalfReadPos = if (secondHalfSlot >= 0) {
                                                payloadOffset + secondHalfSlot.toLong() * chunkStep.toLong()
                                            } else {
                                                payloadOffset + c.toLong() * chunkStep.toLong()
                                            }
                                            val readLen = if (secondHalfSlot >= 0 && relocatedLen[c] > 0) relocatedLen[c] else cLen
                                            val bb = ByteBuffer.wrap(activeChunkBuf, 0, readLen)
                                            readFullyAtPosition(channel, bb, secondHalfReadPos)
                                            activeChunkLimit = bb.position()
                                        }

                                        if (activeChunkLimit < cLen && (c <= tailIdx || secondHalfSlot < 0)) {
                                            throw java.io.EOFException(
                                                "Unexpected EOF reading compressed chunk $c of '${entry.name}' ($activeChunkLimit / $cLen bytes)"
                                            )
                                        }

                                        // 2. DECOMPRESS-FIRST: Feed chunk `c` to Java Inflater and durably flush
                                        //    all resulting decompressed output BEFORE any compressed bytes in the
                                        //    archive are overwritten or truncated!
                                        var activeChunkOffset = 0
                                        while ((activeChunkOffset < activeChunkLimit || !inflater.needsInput()) && coroutineContext.isActive) {
                                            if (inflater.needsInput() && activeChunkOffset < activeChunkLimit) {
                                                val feedLen = (activeChunkLimit - activeChunkOffset).coerceAtMost(4 * 1024 * 1024)
                                                inflater.setInput(activeChunkBuf, activeChunkOffset, feedLen)
                                                activeChunkOffset += feedLen
                                            }

                                            val decompressed = inflater.inflate(inflateOutBuffer)
                                            if (decompressed > 0) {
                                                outputStream.write(inflateOutBuffer, 0, decompressed)
                                                crc.update(inflateOutBuffer, 0, decompressed)
                                                entryBytesWritten += decompressed
                                                atomicBytesExtracted.addAndGet(decompressed.toLong())
                                                atomicBytesSinceSpeedCheck.addAndGet(decompressed.toLong())

                                                updateSpeedAndMaybeEmit(
                                                    entryName = entry.name,
                                                    entryWritten = entryBytesWritten,
                                                    entryTotal = entry.uncompressedSize,
                                                    entryCrc = entry.crc32,
                                                    diskBytesNow = currentDiskBytes,
                                                    reclaimedNow = bytesReclaimed
                                                )
                                            } else if (inflater.finished() || inflater.needsDictionary()) {
                                                break
                                            } else if (inflater.needsInput() && activeChunkOffset >= activeChunkLimit) {
                                                break
                                            }
                                        }

                                        // On the final chunk, drain any remaining uncompressed bytes still buffered in Inflater
                                        if (c == totalChunks - 1) {
                                            while (!inflater.finished() && !inflater.needsInput() && !inflater.needsDictionary() && coroutineContext.isActive) {
                                                val decompressed = inflater.inflate(inflateOutBuffer)
                                                if (decompressed <= 0) break
                                                outputStream.write(inflateOutBuffer, 0, decompressed)
                                                crc.update(inflateOutBuffer, 0, decompressed)
                                                entryBytesWritten += decompressed
                                                atomicBytesExtracted.addAndGet(decompressed.toLong())
                                                atomicBytesSinceSpeedCheck.addAndGet(decompressed.toLong())
                                            }
                                        }

                                        // Durably flush and sync decompressed output of chunk `c` to disk before any in-loop
                                        // archive mutation (`pwrite` relocation or `ftruncate`). For the final chunk (`c == totalChunks - 1`),
                                        // no in-loop mutation occurs; its flush + sync is consolidated immediately after the loop
                                        // before closing the stream, committing the journal checkpoint, and truncating slot 0.
                                        val willMutateArchiveInLoop = canSwapInPlace && c < totalChunks - 1 && (c <= tailIdx || secondHalfSlot > 0)
                                        if (willMutateArchiveInLoop) {
                                            outputStream.flush()
                                            try {
                                                rawFileOut?.fd?.sync()
                                            } catch (_: Throwable) {
                                            }
                                        }

                                        // 3. RELOCATE & TRUNCATE SECOND: Now that chunk `c`'s output is durable on disk,
                                        //    relocate tail chunk `tailIdx` into slot `c` (if `c < tailIdx`) and truncate!
                                        if (canSwapInPlace && c < tailIdx) {
                                            var chunk0OriginalCrcValue = 0L
                                            if (c == 0 && canUseResumeJournal && activeChunkLimit > 0) {
                                                val c0Crc = CRC32()
                                                c0Crc.update(activeChunkBuf, 0, activeChunkLimit)
                                                chunk0OriginalCrcValue = c0Crc.value
                                                ResumeJournalManager.saveTailSwapGuard(
                                                    stateFilePath,
                                                    ResumeJournalManager.TailSwapGuardState(
                                                        entryPath = entry.path,
                                                        entryName = entry.name,
                                                        payloadOffset = payloadOffset,
                                                        compressedSize = compressedPayloadSize,
                                                        uncompressedSize = entry.uncompressedSize,
                                                        expectedEntryCrc32 = entry.crc32,
                                                        chunk0Len = activeChunkLimit,
                                                        chunk0OriginalCrc32 = chunk0OriginalCrcValue,
                                                        decompressedChunk0Bytes = entryBytesWritten,
                                                        overwriteStarted = false
                                                    )
                                                )
                                            }

                                            // Reuse `activeChunkBuf` (now fully inflated) to read and relocate `tailIdx`
                                            val tailFilePos = payloadOffset + tailIdx.toLong() * chunkStep.toLong()
                                            val tailLen = expectedDeflateChunkLen(tailIdx)
                                            val tailBb = ByteBuffer.wrap(activeChunkBuf, 0, tailLen)
                                            readFullyAtPosition(channel, tailBb, tailFilePos)
                                            val tailRead = tailBb.position()
                                            val slotFilePos = payloadOffset + c.toLong() * chunkStep.toLong()
                                            faultInjector.onPoint(
                                                DestructivePoint.BEFORE_TAILSWAP_PWRITE,
                                                currentDiskBytes,
                                                "c=$c,tailIdx=$tailIdx,slotFilePos=$slotFilePos,tailRead=$tailRead"
                                            )
                                            if (c == 0 && canUseResumeJournal && activeChunkLimit > 0 && tailRead > 0) {
                                                ResumeJournalManager.saveTailSwapGuard(
                                                    stateFilePath,
                                                    ResumeJournalManager.TailSwapGuardState(
                                                        entryPath = entry.path,
                                                        entryName = entry.name,
                                                        payloadOffset = payloadOffset,
                                                        compressedSize = compressedPayloadSize,
                                                        uncompressedSize = entry.uncompressedSize,
                                                        expectedEntryCrc32 = entry.crc32,
                                                        chunk0Len = activeChunkLimit,
                                                        chunk0OriginalCrc32 = chunk0OriginalCrcValue,
                                                        decompressedChunk0Bytes = entryBytesWritten,
                                                        overwriteStarted = true
                                                    )
                                                )
                                            }
                                            if (tailRead > 0 && faultInjector.shouldSimulateTornWrite(DestructivePoint.BEFORE_TAILSWAP_PWRITE)) {
                                                val halfLen = (tailRead / 2).coerceAtLeast(1)
                                                writeChannelAtPosition(channel, fileDescriptor, activeChunkBuf, 0, halfLen, slotFilePos)
                                                payloadConsumedInPlace = true
                                                faultInjector.onPhysicalSlotWrite(
                                                    DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
                                                    c,
                                                    tailIdx,
                                                    slotFilePos,
                                                    halfLen
                                                )
                                                faultInjector.onPoint(
                                                    DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
                                                    currentDiskBytes,
                                                    "c=$c,tailIdx=$tailIdx,tornBytes=$halfLen/$tailRead"
                                                )
                                            }
                                            if (tailRead > 0 && writeChannelAtPosition(channel, fileDescriptor, activeChunkBuf, 0, tailRead, slotFilePos)) {
                                                payloadConsumedInPlace = true
                                                try {
                                                    fileDescriptor?.sync()
                                                } catch (_: Throwable) {
                                                }
                                                faultInjector.onPhysicalSlotWrite(
                                                    DestructivePoint.BEFORE_TAILSWAP_PWRITE,
                                                    c,
                                                    tailIdx,
                                                    slotFilePos,
                                                    tailRead
                                                )
                                                faultInjector.onPoint(
                                                    DestructivePoint.AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE,
                                                    currentDiskBytes,
                                                    "c=$c,tailIdx=$tailIdx,tailFilePos=$tailFilePos"
                                                )
                                                relocatedSlot[tailIdx] = c
                                                relocatedLen[tailIdx] = tailRead
                                                if (tailFilePos in 0L until currentDiskBytes) {
                                                    val oldLen = currentDiskBytes
                                                    if (truncateDescriptor(channel, fileDescriptor, tailFilePos)) {
                                                        val freed = (oldLen - tailFilePos).coerceAtLeast(0L)
                                                        bytesReclaimed += freed
                                                        currentDiskBytes = tailFilePos
                                                        faultInjector.onPhysicalTruncate(
                                                            DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                            oldLen,
                                                            currentDiskBytes
                                                        )
                                                        faultInjector.onPoint(
                                                            DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                            currentDiskBytes,
                                                            "c=$c,tailIdx=$tailIdx"
                                                        )
                                                        logJournal("ftruncate($tailFilePos): Reclaimed ${formatSize(freed)} -> Archive: ${formatSize(currentDiskBytes)}")
                                                    }
                                                }
                                            } else {
                                                canSwapInPlace = false
                                            }
                                        } else if (canSwapInPlace && c == tailIdx && c < totalChunks - 1) {
                                            val midFilePos = payloadOffset + c.toLong() * chunkStep.toLong()
                                            if (midFilePos in 0L until currentDiskBytes) {
                                                val oldLen = currentDiskBytes
                                                if (truncateDescriptor(channel, fileDescriptor, midFilePos)) {
                                                    payloadConsumedInPlace = true
                                                    val freed = (oldLen - midFilePos).coerceAtLeast(0L)
                                                    bytesReclaimed += freed
                                                    currentDiskBytes = midFilePos
                                                    faultInjector.onPhysicalTruncate(
                                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                        oldLen,
                                                        currentDiskBytes
                                                    )
                                                    faultInjector.onPoint(
                                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                        currentDiskBytes,
                                                        "c=$c,midFilePos=$midFilePos"
                                                    )
                                                    logJournal("ftruncate($midFilePos): Reclaimed ${formatSize(freed)} -> Archive: ${formatSize(currentDiskBytes)}")
                                                }
                                            }
                                        } else if (canSwapInPlace && c > tailIdx && c < totalChunks - 1 && secondHalfSlot > 0) {
                                            val truncTarget = secondHalfReadPos
                                            if (truncTarget in 0L until currentDiskBytes) {
                                                val oldLen = currentDiskBytes
                                                if (truncateDescriptor(channel, fileDescriptor, truncTarget)) {
                                                    payloadConsumedInPlace = true
                                                    val freed = (oldLen - truncTarget).coerceAtLeast(0L)
                                                    bytesReclaimed += freed
                                                    currentDiskBytes = truncTarget
                                                    faultInjector.onPhysicalTruncate(
                                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                        oldLen,
                                                        currentDiskBytes
                                                    )
                                                    faultInjector.onPoint(
                                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                        currentDiskBytes,
                                                        "c=$c,slot=$secondHalfSlot"
                                                    )
                                                    logJournal("ftruncate($truncTarget): Reclaimed ${formatSize(freed)} -> Archive: ${formatSize(currentDiskBytes)}")
                                                }
                                            }
                                        }

                                        updateSpeedAndMaybeEmit(
                                            entryName = entry.name,
                                            entryWritten = entryBytesWritten,
                                            entryTotal = entry.uncompressedSize,
                                            entryCrc = entry.crc32,
                                            diskBytesNow = currentDiskBytes,
                                            reclaimedNow = bytesReclaimed,
                                            forceEmit = true
                                        )
                                    }
                                } finally {
                                    inflater.end()
                                }
                                // Consolidated final flush + fsync of the completed output stream before
                                // closing outputStream, verifying full-entry CRC32, committing the journal
                                // checkpoint, and truncating slot 0 / local header.
                                outputStream.flush()
                                try {
                                    rawFileOut?.fd?.sync()
                                } catch (_: Throwable) {
                                }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            try {
                                targetOut.close()
                            } catch (_: Throwable) {
                            }
                            quarantineOutputFile(destFile ?: targetOut.localFile, context, targetOut.documentUri)
                            throw ce
                        } catch (fe: SimulatedFaultException) {
                            try {
                                targetOut.close()
                            } catch (_: Throwable) {
                            }
                            throw fe
                        } catch (t: Throwable) {
                            try {
                                targetOut.close()
                            } catch (_: Throwable) {
                            }
                            quarantineOutputFile(destFile ?: targetOut.localFile, context, targetOut.documentUri)
                            val stateDesc = describeArchiveConsumptionState(
                                isHatchMode = isHatchMode,
                                originalSize = originalArchiveSize,
                                currentDiskBytes = currentDiskBytes,
                                bytesReclaimed = bytesReclaimed,
                                payloadModifiedOrTruncated = payloadConsumedInPlace,
                                verifiedFilesCount = completedPaths.size
                            )
                            val errDetail = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                            val fullErr = "Decompression failed in DEFLATED entry '${entry.name}': $errDetail [$stateDesc]"
                            logJournal("DECOMPRESSION ERROR: $fullErr")
                            send(
                                ExtractionProgress(
                                    archiveName = archiveName,
                                    isHatchMode = isHatchMode,
                                    isZeroCopySaf = isZeroCopySaf,
                                    isZip64 = isZip64,
                                    status = ExtractionStatus.ERROR,
                                    isCrcValid = false,
                                    errorMessage = fullErr,
                                    currentArchiveBytesOnDisk = currentDiskBytes,
                                    originalArchiveBytes = originalArchiveSize,
                                    bytesExtracted = atomicBytesExtracted.get(),
                                    bytesReclaimed = bytesReclaimed,
                                    journalLogs = journalSnapshot
                                )
                            )
                            return@channelFlow
                        } finally {
                            try {
                                targetOut.close()
                            } catch (_: Throwable) {
                            }
                        }

                        val calculatedCrc = crc.value
                        val isCrcMatch = entryBytesWritten == entry.uncompressedSize &&
                                (format == ArchiveFormat.TAR || entry.crc32 == 0L || entry.crc32 == calculatedCrc)
                        if (!isCrcMatch) {
                            quarantineOutputFile(destFile ?: targetOut.localFile, context, targetOut.documentUri)
                            val stateDesc = describeArchiveConsumptionState(
                                isHatchMode = isHatchMode,
                                originalSize = originalArchiveSize,
                                currentDiskBytes = currentDiskBytes,
                                bytesReclaimed = bytesReclaimed,
                                payloadModifiedOrTruncated = payloadConsumedInPlace,
                                verifiedFilesCount = completedPaths.size
                            )
                            val errMsg = "CRC32 mismatch in DEFLATED entry '${entry.name}': expected ${entry.crcHex} (${entry.uncompressedSize} B), got 0x${String.format("%08X", calculatedCrc)} ($entryBytesWritten B) [$stateDesc]"
                            logJournal("CRC MISMATCH: $errMsg")
                            send(
                                ExtractionProgress(
                                    archiveName = archiveName,
                                    isHatchMode = isHatchMode,
                                    isZeroCopySaf = isZeroCopySaf,
                                    isZip64 = isZip64,
                                    status = ExtractionStatus.ERROR,
                                    isCrcValid = false,
                                    errorMessage = errMsg,
                                    currentArchiveBytesOnDisk = currentDiskBytes,
                                    originalArchiveBytes = originalArchiveSize,
                                    bytesExtracted = atomicBytesExtracted.get(),
                                    bytesReclaimed = bytesReclaimed,
                                    journalLogs = journalSnapshot
                                )
                            )
                            return@channelFlow
                        } else {
                            logJournal("CRC OK [${entry.crcHex}]: ${entry.name} (${formatSize(entry.uncompressedSize)})")
                        }

                        ResumeJournalManager.clearTailSwapGuard(stateFilePath)
                        verifiedEntryCrcs[entry.path] = calculatedCrc
                        if (completedPaths.add(entry.path)) {
                            atomicCompletedCount.incrementAndGet()
                        }

                        val targetOffset = entryOffset.coerceAtLeast(0L)
                        val pendingFreed = (currentDiskBytes - targetOffset).coerceAtLeast(0L)
                        commitJournalCheckpoint(
                            targetArchiveBytes = targetOffset,
                            reclaimedBytes = bytesReclaimed + pendingFreed,
                            detail = "entry=${entry.name}"
                        )

                        // Truncate final slot 0 & Local File Header down to entryOffset
                        if (entryOffset in 0L until currentDiskBytes) {
                            val oldLen = currentDiskBytes
                            faultInjector.onPoint(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, "entry=${entry.name},target=$entryOffset")
                            if (truncateDescriptor(channel, fileDescriptor, entryOffset)) {
                                payloadConsumedInPlace = true
                                val freed = (oldLen - entryOffset).coerceAtLeast(0L)
                                bytesReclaimed += freed
                                currentDiskBytes = entryOffset
                                if (canSwapInPlace) {
                                    faultInjector.onPhysicalTruncate(
                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                        oldLen,
                                        currentDiskBytes
                                    )
                                    faultInjector.onPoint(
                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                        currentDiskBytes,
                                        "c=${totalChunks - 1},slot=0"
                                    )
                                } else {
                                    faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, currentDiskBytes)
                                }
                                faultInjector.onPoint(DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL, currentDiskBytes, "entry=${entry.name}")
                                logJournal("ftruncate($entryOffset): Reclaimed ${formatSize(freed)} -> Archive: ${formatSize(currentDiskBytes)}")
                            }
                        }
                    }
                }

                val nowAfterTrunc = System.currentTimeMillis()
                trySend(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.TRUNCATING,
                        totalEntries = totalEntriesCount,
                        completedEntries = atomicCompletedCount.get(),
                        currentFileName = entry.name,
                        currentFileBytesProcessed = entry.uncompressedSize,
                        currentFileTotalBytes = entry.uncompressedSize,
                        totalUncompressedBytes = totalUncompressedBytes,
                        currentEntryCrc = entry.crc32,
                        isCrcValid = true,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = bytesReclaimed,
                        bytesPerSecond = atomicCurrentSpeed.get(),
                        journalLogs = journalSnapshot,
                        outputDirectory = displayDir,
                        startTime = startTime,
                        elapsedTimeMs = nowAfterTrunc - startTime
                    )
                )
                continue
            }

            // Case 2: Singleton Standard/Hatch Entry (no coroutine fan-out overhead needed)
            val outcomes = ArrayList<SingleEntryOutcome>(window.size)
            var watermarkAlreadyLogged = false
            if (window.size == 1) {
                val item = window[0]
                val res = extractSingleEntryPositional(
                    entryIdx = item.index,
                    entry = item.entry
                )
                if (res is SingleEntryOutcome.Error) {
                    fatalError.compareAndSet(null, res.message)
                } else {
                    outcomes.add(res)
                }
            } else {
                // Case 3: Multi-Threaded Parallel Window / Pool Execution with Immediate Per-Entry Tail Watermark Truncation
                val workQueueIndex = AtomicInteger(0)
                val outcomeLock = Any()
                val windowCompleted = BooleanArray(window.size)
                var windowWatermark = 0
                val activeWorkers = minOf(maxWorkers, window.size)

                coroutineScope {
                    repeat(activeWorkers) {
                        launch(workerDispatcher) {
                            while (isActive && fatalError.get() == null) {
                                val localIdx = workQueueIndex.getAndIncrement()
                                if (localIdx >= window.size) break
                                val item = window[localIdx]
                                val res = extractSingleEntryPositional(
                                    entryIdx = item.index,
                                    entry = item.entry
                                )
                                if (res is SingleEntryOutcome.Error) {
                                    fatalError.compareAndSet(null, res.message)
                                    break
                                } else {
                                    synchronized(outcomeLock) {
                                        outcomes.add(res)
                                        if (res is SingleEntryOutcome.Success && res.isCrcMatch) {
                                            windowCompleted[localIdx] = true
                                            if (isHatchMode && localIdx == windowWatermark) {
                                                var latestOffset = currentDiskBytes
                                                while (windowWatermark < window.size && windowCompleted[windowWatermark]) {
                                                    latestOffset = minOf(latestOffset, window[windowWatermark].entry.localHeaderOffset)
                                                    windowWatermark++
                                                }
                                                if (latestOffset in 0L until currentDiskBytes) {
                                                    val oldLen = currentDiskBytes
                                                    val pendingFreed = (oldLen - latestOffset).coerceAtLeast(0L)
                                                    // Durably commit journal checkpoint BEFORE truncating watermark!
                                                    commitJournalCheckpoint(
                                                        targetArchiveBytes = latestOffset,
                                                        reclaimedBytes = bytesReclaimed + pendingFreed,
                                                        detail = "watermark=$latestOffset"
                                                    )
                                                    faultInjector.onPoint(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, "watermark=$latestOffset")
                                                    if (truncateDescriptor(channel, fileDescriptor, latestOffset)) {
                                                        payloadConsumedInPlace = true
                                                        val freedNow = (oldLen - latestOffset).coerceAtLeast(0L)
                                                        bytesReclaimed += freedNow
                                                        currentDiskBytes = latestOffset
                                                        faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, currentDiskBytes)
                                                        faultInjector.onPoint(DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL, currentDiskBytes, "watermark=$latestOffset")
                                                        watermarkAlreadyLogged = true
                                                        if (executionWindows.size <= 250 || winIdx % 10 == 0 || freedNow >= 512 * 1024) {
                                                            logJournal("ftruncate($latestOffset): Reclaimed ${formatSize(freedNow)} -> Archive: ${formatSize(currentDiskBytes)}")
                                                        }
                                                        updateSpeedAndMaybeEmit(
                                                            entryName = item.entry.name,
                                                            entryWritten = item.entry.uncompressedSize,
                                                            entryTotal = item.entry.uncompressedSize,
                                                            entryCrc = item.entry.crc32,
                                                            diskBytesNow = currentDiskBytes,
                                                            reclaimedNow = bytesReclaimed,
                                                            forceEmit = true
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val errMsg = fatalError.get()
            if (errMsg != null) {
                val stateDesc = describeArchiveConsumptionState(
                    isHatchMode = isHatchMode,
                    originalSize = originalArchiveSize,
                    currentDiskBytes = currentDiskBytes,
                    bytesReclaimed = bytesReclaimed,
                    payloadModifiedOrTruncated = payloadConsumedInPlace,
                    verifiedFilesCount = completedPaths.size
                )
                val fullErr = "$errMsg [$stateDesc]"
                logJournal("INTEGRITY HALT: $fullErr")
                send(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = isHatchMode,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.ERROR,
                        isCrcValid = false,
                        errorMessage = fullErr,
                        completedEntries = atomicCompletedCount.get(),
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        originalArchiveBytes = originalArchiveSize,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = bytesReclaimed,
                        journalLogs = journalSnapshot
                    )
                )
                return@channelFlow
            }

            if (!coroutineContext.isActive) {
                send(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = isHatchMode,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.CANCELLED,
                        totalEntries = totalEntriesCount,
                        completedEntries = atomicCompletedCount.get(),
                        totalUncompressedBytes = totalUncompressedBytes,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = bytesReclaimed,
                        journalLogs = journalSnapshot
                    )
                )
                return@channelFlow
            }

            // In KaiHatch In-Place Mode, commit the journal checkpoint and then truncate the completed window's head offset!
            if (isHatchMode && outcomes.isNotEmpty()) {
                val allCrcValid = outcomes.all { it is SingleEntryOutcome.Success && it.isCrcMatch }
                if (allCrcValid) {
                    val windowHeadOffset = window.minOf { it.entry.localHeaderOffset }
                    val lastItem = window.last()
                    val lastEntry = lastItem.entry
                    var freed = 0L
                    if (windowHeadOffset in 0L until currentDiskBytes) {
                        val oldLen = currentDiskBytes
                        val pendingFreed = (oldLen - windowHeadOffset).coerceAtLeast(0L)
                        // Durably commit journal BEFORE truncating windowHeadOffset (zero 2.5s throttling!)
                        commitJournalCheckpoint(
                            targetArchiveBytes = windowHeadOffset,
                            reclaimedBytes = bytesReclaimed + pendingFreed,
                            detail = "windowIdx=$winIdx"
                        )
                        faultInjector.onPoint(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, "windowHead=$windowHeadOffset")
                        if (truncateDescriptor(channel, fileDescriptor, windowHeadOffset)) {
                            payloadConsumedInPlace = true
                            freed = (oldLen - windowHeadOffset).coerceAtLeast(0L)
                            bytesReclaimed += freed
                            currentDiskBytes = windowHeadOffset
                            faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_WINDOW_TRUNCATE, oldLen, currentDiskBytes)
                            faultInjector.onPoint(DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL, currentDiskBytes, "windowHead=$windowHeadOffset")
                        }
                    } else if (!watermarkAlreadyLogged) {
                        commitJournalCheckpoint(
                            targetArchiveBytes = currentDiskBytes,
                            reclaimedBytes = bytesReclaimed,
                            detail = "windowIdx=$winIdx"
                        )
                    }
                    val newLen = currentDiskBytes

                    if (freed > 0L || !watermarkAlreadyLogged) {
                        if (executionWindows.size <= 250 || winIdx % 10 == 0 || freed >= 1024 * 1024) {
                            logJournal("ftruncate($windowHeadOffset): Reclaimed ${formatSize(freed)} -> Archive: ${formatSize(newLen)}")
                        }
                    }

                    val nowAfterTrunc = System.currentTimeMillis()
                    if (freed > 0L || nowAfterTrunc - atomicLastEmitTime.get() >= 160L || winIdx == executionWindows.lastIndex || executionWindows.size <= 16) {
                        atomicLastEmitTime.set(nowAfterTrunc)
                        trySend(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = true,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = isZip64,
                                status = ExtractionStatus.TRUNCATING,
                                totalEntries = totalEntriesCount,
                                completedEntries = atomicCompletedCount.get(),
                                currentFileName = lastEntry.name,
                                currentFileBytesProcessed = lastEntry.uncompressedSize,
                                currentFileTotalBytes = lastEntry.uncompressedSize,
                                totalUncompressedBytes = totalUncompressedBytes,
                                currentEntryCrc = lastEntry.crc32,
                                isCrcValid = true,
                                originalArchiveBytes = originalArchiveSize,
                                currentArchiveBytesOnDisk = newLen,
                                bytesExtracted = atomicBytesExtracted.get(),
                                bytesReclaimed = bytesReclaimed,
                                bytesPerSecond = atomicCurrentSpeed.get(),
                                journalLogs = journalSnapshot,
                                outputDirectory = displayDir,
                                startTime = startTime,
                                elapsedTimeMs = nowAfterTrunc - startTime
                            )
                        )
                    }
                }
            }
        }

        val finalCompletedCount = atomicCompletedCount.get()
        val finalBytesExtracted = atomicBytesExtracted.get()

        if (isHatchMode) {
            val prevDisk = currentDiskBytes
            faultInjector.onPoint(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, archiveName)
            truncateDescriptor(channel, fileDescriptor, 0L)
            if (prevDisk > 0L) {
                faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, 0L)
            }
            onFinalTruncateDelete()
            ResumeJournalManager.deleteState(stateFilePath)
            logJournal("COMPLETE: Unpacked in-place & reclaimed ${formatSize(originalArchiveSize)}!")
        } else {
            logJournal("COMPLETE: Extracted $finalCompletedCount files (${formatSize(finalBytesExtracted)}).")
        }

        val finalDisplayDir = if (context != null && targetTreeUri != null) {
            StorageHelper.getGrantedFolderInfo(context)?.let { "${it.displayName}/${targetDir.name}" } ?: targetDir.absolutePath
        } else targetDir.absolutePath

        send(
            ExtractionProgress(
                archiveName = archiveName,
                isHatchMode = isHatchMode,
                isZeroCopySaf = isZeroCopySaf,
                isZip64 = isZip64,
                status = ExtractionStatus.COMPLETED,
                totalEntries = totalEntriesCount,
                completedEntries = totalEntriesCount,
                currentFileName = "Complete",
                currentFileBytesProcessed = 0L,
                currentFileTotalBytes = 0L,
                totalUncompressedBytes = totalUncompressedBytes,
                originalArchiveBytes = originalArchiveSize,
                currentArchiveBytesOnDisk = if (isHatchMode) 0L else originalArchiveSize,
                bytesExtracted = finalBytesExtracted,
                bytesReclaimed = if (isHatchMode) originalArchiveSize else 0L,
                bytesPerSecond = 0L,
                journalLogs = journalSnapshot,
                outputDirectory = finalDisplayDir,
                startTime = startTime,
                elapsedTimeMs = System.currentTimeMillis() - startTime
            )
        )
    }

    /**
     * Safely resolves an output File for an archive entry, preventing ZipSlip traversal as well as
     * ENOTDIR / EISDIR crashes when obfuscated APKs contain colliding file and folder names.
     */
    private fun resolveSafeDestFile(targetDir: File, rawEntryPath: String): File {
        val sanitizedRel = rawEntryPath
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
            .ifEmpty { "extracted_entry_${System.nanoTime()}" }

        synchronized(destDirResolveLock) {
            var candidate = File(targetDir, sanitizedRel)

            // Ensure all parent path segments are directories (not existing regular files from obfuscated APKs)
            val parents = mutableListOf<File>()
            var p = candidate.parentFile
            while (p != null && p.absolutePath != targetDir.absolutePath && p.absolutePath.startsWith(targetDir.absolutePath)) {
                parents.add(0, p)
                p = p.parentFile
            }
            for (dirSegment in parents) {
                if (dirSegment.exists() && !dirSegment.isDirectory) {
                    val renamedConflict = File(dirSegment.parentFile, "${dirSegment.name}_file")
                    dirSegment.renameTo(renamedConflict)
                }
                if (!dirSegment.exists()) {
                    dirSegment.mkdirs()
                }
            }
            candidate.parentFile?.mkdirs()

            // If candidate itself already exists as a directory, disambiguate the file name
            if (candidate.exists() && candidate.isDirectory) {
                candidate = File(candidate.parentFile, "${candidate.name}_payload")
            }
            return candidate
        }
    }

    internal fun computeLargeDeflateChunkStep(
        compressedPayloadSize: Long,
        freeHeadroomOverrideBytes: Long = -1L
    ): Int {
        val freeHeadroom = if (freeHeadroomOverrideBytes >= 0L) {
            freeHeadroomOverrideBytes
        } else {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory().coerceAtLeast(64L * 1024L * 1024L)
            val usedMem = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
            (maxMem - usedMem).coerceAtLeast(0L)
        }

        val lowMemory = if (freeHeadroomOverrideBytes >= 0L) {
            freeHeadroom < 24L * 1024L * 1024L
        } else {
            CommonsCompressBufferStrategy.isLowMemoryPressure()
        }

        val maxChunkBytes = when {
            lowMemory || freeHeadroom < 48L * 1024L * 1024L -> 4L * 1024L * 1024L
            freeHeadroom < 96L * 1024L * 1024L -> 8L * 1024L * 1024L
            freeHeadroom < 192L * 1024L * 1024L -> 16L * 1024L * 1024L
            else -> 32L * 1024L * 1024L
        }

        val pageBytes = 4096L
        val rawStep = ((compressedPayloadSize.coerceAtLeast(0L) + 7L) / 8L)
            .coerceIn(1024L * 1024L, maxChunkBytes)
        val alignedStep = ((rawStep + pageBytes - 1L) / pageBytes) * pageBytes

        return alignedStep
            .coerceIn(1024L * 1024L, maxChunkBytes)
            .toInt()
    }

    private fun safeChannelSize(channel: FileChannel, fd: FileDescriptor?, fallback: Long): Long {
        return try {
            channel.size()
        } catch (_: Throwable) {
            try {
                if (fd != null) Os.fstat(fd).st_size else fallback
            } catch (_: Throwable) {
                fallback.coerceAtLeast(0L)
            }
        }
    }

    private fun truncateDescriptor(channel: FileChannel, fd: FileDescriptor?, newLength: Long): Boolean {
        val safeLen = newLength.coerceAtLeast(0L)
        try {
            channel.truncate(safeLen)
            return true
        } catch (_: Throwable) {
        }
        if (fd != null) {
            var truncated = false
            try {
                Os.ftruncate(fd, safeLen)
                truncated = true
            } catch (_: Throwable) {
            }
            if (!truncated || try { channel.size() > safeLen } catch (_: Throwable) { false }) {
                try {
                    FileOutputStream(fd).channel.truncate(safeLen)
                    truncated = true
                } catch (_: Throwable) {
                }
            }
            if (truncated) return true
        }
        return false
    }

    private fun extractTarGzStream(
        archiveName: String,
        originalArchiveSize: Long,
        openStream: () -> java.io.InputStream,
        targetDir: File,
        selectedEntryPaths: Set<String>?,
        context: Context? = null,
        targetTreeUri: Uri? = null,
        targetFolderDocUri: Uri? = null,
        onDeleteSource: () -> Unit
    ): Flow<ExtractionProgress> = flow {
        val startTime = System.currentTimeMillis()
        val logs = mutableListOf("[00:00] Decompressing GZIP TAR stream...")
        val displayDir = if (targetTreeUri != null && context != null) {
            "SAF / " + StorageHelper.getFolderDisplayName(context, targetTreeUri)
        } else {
            if (!targetDir.exists()) targetDir.mkdirs()
            targetDir.absolutePath
        }

        var bytesExtracted = 0L
        var filesCount = 0
        var activeTargetOut: TargetOutput? = null
        val streamExtractedOutputs = mutableListOf<TargetOutput>()

        try {
            GZIPInputStream(BufferedInputStream(openStream())).use { gzIn ->
                val block = ByteArray(512)
                val buffer = ByteArray(32 * 1024)

                while (coroutineContext.isActive) {
                    var rTotal = 0
                    while (rTotal < 512) {
                        val r = gzIn.read(block, rTotal, 512 - rTotal)
                        if (r <= 0) break
                        rTotal += r
                    }
                    if (rTotal == 0 || block.all { it == 0.toByte() }) {
                        // Drain remaining GZIP stream so GZIPInputStream verifies the GZIP CRC32 + ISIZE trailer!
                        while (gzIn.read(buffer) > 0) {
                            // draining trailing zero blocks & GZIP trailer
                        }
                        break
                    }
                    if (rTotal < 512) {
                        throw java.io.EOFException("Truncated TAR header block in GZIP stream ($rTotal / 512 bytes)")
                    }
                    if (!verifyTarHeaderChecksum(block)) {
                        throw java.io.IOException("Corrupted TAR header checksum in GZIP stream")
                    }

                    var end = 0
                    while (end < 100 && block[end] != 0.toByte()) end++
                    val name = String(block, 0, end, Charsets.UTF_8).trim()
                    if (name.isEmpty()) break

                    var sEnd = 124
                    while (sEnd < 136 && block[sEnd] != 0.toByte()) sEnd++
                    val sizeOctal = String(block, 124, sEnd - 124, Charsets.UTF_8).trim()
                    val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
                    val isDir = block[156].toInt().toChar() == '5' || name.endsWith("/")

                    val shouldExtract = selectedEntryPaths == null || selectedEntryPaths.any {
                        name == it || name.startsWith(it.trimEnd('/') + "/")
                    }

                    if (isDir) {
                        if (shouldExtract) {
                            if (targetTreeUri != null && targetFolderDocUri != null && context != null) {
                                val segments = name.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
                                var cur: Uri = targetFolderDocUri
                                for (seg in segments) {
                                    val next = StorageHelper.getOrCreateDocumentChildDir(context, targetTreeUri, cur, seg)
                                    if (next != null) cur = next
                                }
                            } else {
                                File(targetDir, name).mkdirs()
                            }
                        }
                    } else {
                        val targetOut = if (shouldExtract) {
                            StorageHelper.openTargetOutput(
                                context = context,
                                targetDir = targetDir,
                                targetTreeUri = targetTreeUri,
                                targetFolderDocUri = targetFolderDocUri,
                                relativePath = name,
                                expectedBytes = entrySize
                            )
                        } else null
                        activeTargetOut = targetOut

                        var rem = entrySize
                        try {
                            while (rem > 0L) {
                                val toRead = rem.coerceAtMost(buffer.size.toLong()).toInt()
                                val r = gzIn.read(buffer, 0, toRead)
                                if (r <= 0) break
                                targetOut?.outputStream?.write(buffer, 0, r)
                                rem -= r
                                if (shouldExtract) bytesExtracted += r
                            }
                            targetOut?.outputStream?.flush()
                        } finally {
                            targetOut?.close()
                        }

                        if (rem > 0L) {
                            targetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
                            activeTargetOut = null
                            throw java.io.EOFException("Truncated TAR.GZ entry '$name': missing $rem of $entrySize bytes")
                        }
                        if (targetOut != null) {
                            streamExtractedOutputs.add(targetOut)
                        }
                        activeTargetOut = null

                        // Skip TAR 512-byte padding
                        val pad = ((512L - (entrySize % 512L)) % 512L).toInt()
                        var padRem = pad
                        while (padRem > 0) {
                            val s = gzIn.read(block, 0, padRem)
                            if (s <= 0) break
                            padRem -= s
                        }
                        if (shouldExtract) {
                            filesCount++
                            logs.add("Extracted TAR.GZ entry: $name (${formatSize(entrySize)})")
                            emit(
                                ExtractionProgress(
                                    archiveName = archiveName,
                                    isHatchMode = false,
                                    status = ExtractionStatus.EXTRACTING,
                                    totalEntries = filesCount + 1,
                                    completedEntries = filesCount,
                                    currentFileName = name.substringAfterLast('/'),
                                    currentFileBytesProcessed = entrySize,
                                    currentFileTotalBytes = entrySize,
                                    originalArchiveBytes = originalArchiveSize,
                                    currentArchiveBytesOnDisk = originalArchiveSize,
                                    bytesExtracted = bytesExtracted,
                                    journalLogs = logs.toList(),
                                    outputDirectory = displayDir,
                                    startTime = startTime,
                                    elapsedTimeMs = System.currentTimeMillis() - startTime
                                )
                            )
                        }
                    }
                }
            }

            onDeleteSource()
            logs.add("COMPLETE: Unpacked $filesCount files from $archiveName")
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = false,
                    status = ExtractionStatus.COMPLETED,
                    totalEntries = filesCount,
                    completedEntries = filesCount,
                    currentFileName = "Complete",
                    originalArchiveBytes = originalArchiveSize,
                    currentArchiveBytesOnDisk = originalArchiveSize,
                    bytesExtracted = bytesExtracted,
                    journalLogs = logs.toList(),
                    outputDirectory = displayDir,
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime
                )
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            activeTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            throw ce
        } catch (t: Throwable) {
            activeTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            for (unverifiedOut in streamExtractedOutputs) {
                quarantineOutputFile(unverifiedOut.localFile, context, unverifiedOut.documentUri)
            }
            val stateDesc = describeArchiveConsumptionState(
                isHatchMode = false,
                originalSize = originalArchiveSize,
                currentDiskBytes = originalArchiveSize,
                bytesReclaimed = 0L,
                payloadModifiedOrTruncated = false,
                verifiedFilesCount = filesCount
            )
            val errMsg = "TAR.GZ integrity error: ${t.localizedMessage ?: t.message ?: t.javaClass.simpleName} [$stateDesc]"
            logs.add("ERROR: $errMsg")
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = false,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = errMsg,
                    completedEntries = filesCount,
                    originalArchiveBytes = originalArchiveSize,
                    currentArchiveBytesOnDisk = originalArchiveSize,
                    bytesExtracted = bytesExtracted,
                    journalLogs = logs.toList(),
                    outputDirectory = displayDir,
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
