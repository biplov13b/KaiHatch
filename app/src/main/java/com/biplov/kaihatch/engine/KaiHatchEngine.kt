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
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    @Volatile
    internal var sourceCloseFaultHookForTest: ((String) -> java.io.IOException?)? = null

    @Volatile
    internal var outputSyncFaultHookForTest: ((String) -> Boolean?)? = null

    @Volatile
    internal var sourceSyncFaultHookForTest: ((String) -> Boolean?)? = null

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
        targetFolderDocUri: Uri? = null,
        pauseController: ExtractionPauseController? = null
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
                    journalLogs = listOf("Inspecting ${format.label} index..."),
                    isPauseSupported = pauseController?.isPauseSupported ?: true
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
                    pauseController = pauseController,
                    onDeleteSource = { if (isHatchMode && selectedEntryPaths == null) archiveFile.delete() }
                ).collect { emit(it) }
                return@flow
            }

            if (format == ArchiveFormat.SEVEN_Z) {
                val effective7zHatch = isHatchMode && selectedEntryPaths == null
                var pendingCompletion: ExtractionProgress? = null
                var terminalEmitted = false
                var lastObservedLogs: List<String> = emptyList()
                try {
                    RandomAccessFile(archiveFile, if (effective7zHatch) "rw" else "r").use { raf ->
                        extractSevenZChannel(
                            channel = raf.channel,
                            fileDescriptor = raf.fd,
                            archiveName = archiveFile.name,
                            originalArchiveSize = originalArchiveSize,
                            targetDir = targetDir,
                            isHatchMode = effective7zHatch,
                            isZeroCopySaf = false,
                            password = password,
                            selectedEntryPaths = selectedEntryPaths,
                            context = context,
                            targetTreeUri = targetTreeUri,
                            targetFolderDocUri = targetFolderDocUri,
                            pauseController = pauseController,
                            onFinalTruncateDelete = {},
                            onReadyToComplete = { completed -> pendingCompletion = completed }
                        ).collect { progress ->
                            if (progress.journalLogs.isNotEmpty()) lastObservedLogs = progress.journalLogs
                            if (progress.status == ExtractionStatus.ERROR || progress.status == ExtractionStatus.CANCELLED) {
                                terminalEmitted = true
                            }
                            emit(progress)
                        }
                        if (pendingCompletion != null) {
                            maybeThrowSourceCloseFaultForTest(archiveFile.name)
                        }
                    }
                    val completed = pendingCompletion
                    if (completed != null && !terminalEmitted) {
                        if (effective7zHatch) {
                            archiveFile.delete()
                        }
                        terminalEmitted = true
                        emit(completed)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (!terminalEmitted) {
                        val err = t.localizedMessage ?: t.javaClass.simpleName
                        val completedSnapshot = pendingCompletion
                        emit(
                            ExtractionProgress(
                                archiveName = archiveFile.name,
                                isHatchMode = effective7zHatch,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                totalEntries = completedSnapshot?.totalEntries ?: 0,
                                completedEntries = completedSnapshot?.completedEntries ?: 0,
                                originalArchiveBytes = originalArchiveSize,
                                currentArchiveBytesOnDisk = completedSnapshot?.currentArchiveBytesOnDisk ?: if (archiveFile.exists()) archiveFile.length() else 0L,
                                bytesExtracted = completedSnapshot?.bytesExtracted ?: 0L,
                                bytesReclaimed = completedSnapshot?.bytesReclaimed ?: 0L,
                                outputDirectory = completedSnapshot?.outputDirectory ?: targetDir.absolutePath,
                                errorMessage = "Extraction error: $err",
                                journalLogs = (lastObservedLogs + "Error [${t.javaClass.simpleName}]: $err").takeLast(100)
                            )
                        )
                    }
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

            var pendingCompletion: ExtractionProgress? = null
            var terminalEmitted = false
            var lastObservedLogs: List<String> = emptyList()
            try {
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
                        pauseController = pauseController,
                        onFinalTruncateDelete = {},
                        onReadyToComplete = { completed -> pendingCompletion = completed }
                    ).collect { progress ->
                        if (progress.journalLogs.isNotEmpty()) lastObservedLogs = progress.journalLogs
                        if (progress.status == ExtractionStatus.ERROR || progress.status == ExtractionStatus.CANCELLED) {
                            terminalEmitted = true
                        }
                        emit(progress)
                    }
                    if (pendingCompletion != null) {
                        maybeThrowSourceCloseFaultForTest(archiveFile.name)
                    }
                }
                val completed = pendingCompletion
                if (completed != null && !terminalEmitted) {
                    if (effectiveHatchMode) {
                        archiveFile.delete()
                        ResumeJournalManager.deleteState(stateFilePath)
                    }
                    terminalEmitted = true
                    emit(completed)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (!terminalEmitted) {
                    val err = t.localizedMessage ?: t.javaClass.simpleName
                    val completedSnapshot = pendingCompletion
                    emit(
                        ExtractionProgress(
                            archiveName = archiveFile.name,
                            isHatchMode = effectiveHatchMode,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            totalEntries = completedSnapshot?.totalEntries ?: entriesToProcess.size,
                            completedEntries = completedSnapshot?.completedEntries ?: 0,
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = completedSnapshot?.currentArchiveBytesOnDisk ?: if (archiveFile.exists()) archiveFile.length() else 0L,
                            bytesExtracted = completedSnapshot?.bytesExtracted ?: 0L,
                            bytesReclaimed = completedSnapshot?.bytesReclaimed ?: 0L,
                            outputDirectory = completedSnapshot?.outputDirectory ?: targetDir.absolutePath,
                            errorMessage = "Extraction error: $err",
                            journalLogs = (lastObservedLogs + "Error [${t.javaClass.simpleName}]: $err").takeLast(100)
                        )
                    )
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val err = t.localizedMessage ?: t.javaClass.simpleName
            emit(
                ExtractionProgress(
                    archiveName = archiveFile.name,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = "Extraction error: $err",
                    journalLogs = listOf("Error [${t.javaClass.simpleName}]: $err")
                )
            )
        }
    }.flowOn(Dispatchers.IO)

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
        targetFolderDocUri: Uri? = null,
        pauseController: ExtractionPauseController? = null
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
                    pauseController = pauseController,
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

            var pendingCompletion: ExtractionProgress? = null
            var terminalEmitted = false
            var lastObservedLogs: List<String> = emptyList()
            var isSevenZFormat = false
            try {
                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { fis ->
                        val readChannel = fis.channel
                        val originalSize = try {
                            readChannel.size().takeIf { it > 0L } ?: descriptor.statSize.coerceAtLeast(0L)
                        } catch (_: Throwable) {
                            descriptor.statSize.coerceAtLeast(0L)
                        }

                        val format = ZipParser.detectFormat(archiveName, readChannel)
                        isSevenZFormat = (format == ArchiveFormat.SEVEN_Z)

                        // Verify if in-place seekable read-write, pwrite, ftruncate, and durable fsync
                        // are actually permitted by the kernel/FUSE/SAF provider.
                        val descriptorProbe = if (openedWritable && originalSize > 0L) {
                            StorageHelper.probeTransactableFileDescriptor(
                                fd = descriptor.fileDescriptor,
                                channel = readChannel,
                                requireWriteAndTruncate = true
                            )
                        } else {
                            null
                        }
                        val canTruncateInPlace = descriptorProbe?.isSupported == true

                        if (wantsWrite && !canTruncateInPlace) {
                            val reason = descriptorProbe?.reason ?: "in-place truncation and durable fsync unsupported"
                            val errMsg = "Hatch Mode requires verified seekable read-write, in-place file truncation (ftruncate), and durable fsync on '$archiveName', which failed ($reason). Switch to Standard Mode to extract without modifying the source archive."
                            terminalEmitted = true
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

                        if (isSevenZFormat) {
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
                                pauseController = pauseController,
                                onFinalTruncateDelete = {},
                                onReadyToComplete = { completed -> pendingCompletion = completed }
                            ).collect { progress ->
                                if (progress.journalLogs.isNotEmpty()) lastObservedLogs = progress.journalLogs
                                if (progress.status == ExtractionStatus.ERROR || progress.status == ExtractionStatus.CANCELLED) {
                                    terminalEmitted = true
                                }
                                emit(progress)
                            }
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
                                pauseController = pauseController,
                                onFinalTruncateDelete = {},
                                onReadyToComplete = { completed -> pendingCompletion = completed }
                            ).collect { progress ->
                                if (progress.journalLogs.isNotEmpty()) lastObservedLogs = progress.journalLogs
                                if (progress.status == ExtractionStatus.ERROR || progress.status == ExtractionStatus.CANCELLED) {
                                    terminalEmitted = true
                                }
                                emit(progress)
                            }
                        }
                        if (pendingCompletion != null) {
                            maybeThrowSourceCloseFaultForTest(archiveName)
                        }
                    }
                }
                val completed = pendingCompletion
                if (completed != null && !terminalEmitted) {
                    if (wantsWrite) {
                        try {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        } catch (_: Throwable) {
                        }
                        if (!isSevenZFormat) {
                            ResumeJournalManager.deleteState(stateFilePath)
                        }
                    }
                    terminalEmitted = true
                    emit(completed)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (!terminalEmitted) {
                    val err = t.localizedMessage ?: t.javaClass.simpleName
                    val completedSnapshot = pendingCompletion
                    emit(
                        ExtractionProgress(
                            archiveName = archiveName,
                            isHatchMode = wantsWrite,
                            isZeroCopySaf = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            totalEntries = completedSnapshot?.totalEntries ?: 0,
                            completedEntries = completedSnapshot?.completedEntries ?: 0,
                            originalArchiveBytes = completedSnapshot?.originalArchiveBytes ?: 0L,
                            currentArchiveBytesOnDisk = completedSnapshot?.currentArchiveBytesOnDisk ?: 0L,
                            bytesExtracted = completedSnapshot?.bytesExtracted ?: 0L,
                            bytesReclaimed = completedSnapshot?.bytesReclaimed ?: 0L,
                            outputDirectory = completedSnapshot?.outputDirectory ?: targetDir.absolutePath,
                            errorMessage = "Failed to unpack archive: $err",
                            journalLogs = (lastObservedLogs + "Error [${t.javaClass.simpleName}]: $err").takeLast(100)
                        )
                    )
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val err = t.localizedMessage ?: t.javaClass.simpleName
            emit(
                ExtractionProgress(
                    archiveName = archiveName,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = "Failed to unpack archive: $err",
                    journalLogs = listOf("Error [${t.javaClass.simpleName}]: $err")
                )
            )
        }
    }.flowOn(Dispatchers.IO)

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
     * Restores an interrupted Hatch Mode ZIP/ZIP64 archive in-place inside the original archive file,
     * rebuilding Central Directory, ZIP64 metadata, and EOCD for all remaining unextracted entries.
     *
     * Part 3B: When an archive was interrupted mid-stream during a recoverable direct-POSIX v2 DEFLATE
     * tail-swap transaction, finishes ONLY that single interrupted DEFLATED entry using Part 3A's
     * transactional recovery engine, preserves its verified output and updates the durable journal,
     * and then rebuilds the original ZIP's Central Directory/ZIP64 metadata in-place for all
     * remaining unextracted entries without extracting any unrelated entries.
     */
    fun restoreInterruptedZipArchive(
        context: Context,
        resumeState: ResumeJournalState,
        faultInjector: TruncationFaultInjector = TruncationFaultInjector.NoOp,
        simulatedAvailableDiskBytes: Long? = null
    ): Flow<ExtractionProgress> {
        val eligibility = ResumeJournalManager.assessRestoreEligibility(resumeState, context)
        if (eligibility is ResumeJournalManager.RestoreEligibility.Restorable &&
            eligibility.pendingDeflateTailSwapEntryPath != null
        ) {
            return restoreInterruptedDeflateTailSwapZipArchive(
                context = context,
                resumeState = resumeState,
                interruptedDeflateEntryPath = eligibility.pendingDeflateTailSwapEntryPath,
                restoreFaultInjector = faultInjector,
                simulatedAvailableDiskBytes = simulatedAvailableDiskBytes
            )
        }
        if (eligibility is ResumeJournalManager.RestoreEligibility.Unrecoverable &&
            (ResumeJournalManager.loadDeflateTailSwapCheckpoint(resumeState.stateFilePath) != null ||
                ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(resumeState.stateFilePath))
        ) {
            return flow {
                emit(
                    ExtractionProgress(
                        archiveName = resumeState.archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
                        status = ExtractionStatus.ERROR,
                        isCrcValid = false,
                        totalEntries = resumeState.totalEntries,
                        completedEntries = resumeState.completedPaths.size,
                        currentFileName = "Restoration failed",
                        originalArchiveBytes = resumeState.originalArchiveBytes,
                        currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                        bytesReclaimed = resumeState.bytesReclaimedSoFar,
                        errorMessage = eligibility.reason,
                        journalLogs = listOf("[RESTORE] Refused restoration: ${eligibility.reason}"),
                        outputDirectory = resumeState.targetDirPath,
                        isPauseSupported = false
                    )
                )
            }.flowOn(Dispatchers.IO)
        }
        return ZipInPlaceRestoreEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = resumeState,
            faultInjector = faultInjector,
            simulatedAvailableDiskBytes = simulatedAvailableDiskBytes
        )
    }

    /**
     * Durable, idempotent two-stage Restore flow for a recoverable v2 DEFLATE tail-swap interruption:
     * 1. Finishes ONLY [interruptedDeflateEntryPath] via Part 3A's transactional recovery engine,
     *    verifies full-stream CRC32, truncates its LFH/slot 0, syncs, and persists the updated journal.
     * 2. Reloads the persisted journal from disk and invokes [ZipInPlaceRestoreEngine.restoreInterruptedZipArchive]
     *    to rebuild Central Directory / ZIP64 / EOCD in-place for all remaining unextracted entries.
     */
    private fun restoreInterruptedDeflateTailSwapZipArchive(
        context: Context,
        resumeState: ResumeJournalState,
        interruptedDeflateEntryPath: String,
        restoreFaultInjector: TruncationFaultInjector,
        simulatedAvailableDiskBytes: Long?
    ): Flow<ExtractionProgress> = flow {
        val startTime = System.currentTimeMillis()
        val rechecked = ResumeJournalManager.assessRestoreEligibility(resumeState, context)
        if (rechecked !is ResumeJournalManager.RestoreEligibility.Restorable ||
            rechecked.pendingDeflateTailSwapEntryPath != interruptedDeflateEntryPath
        ) {
            val reason = when (rechecked) {
                is ResumeJournalManager.RestoreEligibility.Unrecoverable -> rechecked.reason
                is ResumeJournalManager.RestoreEligibility.UnsupportedFormat -> rechecked.reason
                else -> "Interrupted DEFLATE tail-swap state changed before restoration began."
            }
            emit(
                ExtractionProgress(
                    archiveName = resumeState.archiveName,
                    isHatchMode = true,
                    isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    totalEntries = resumeState.totalEntries,
                    completedEntries = resumeState.completedPaths.size,
                    currentFileName = "Restoration failed",
                    originalArchiveBytes = resumeState.originalArchiveBytes,
                    currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                    bytesReclaimed = resumeState.bytesReclaimedSoFar,
                    errorMessage = reason,
                    journalLogs = listOf("[RESTORE] Pre-flight verification failed: $reason"),
                    outputDirectory = resumeState.targetDirPath,
                    startTime = startTime,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L),
                    isPauseSupported = false
                )
            )
            return@flow
        }

        emit(
            ExtractionProgress(
                archiveName = resumeState.archiveName,
                isHatchMode = true,
                isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
                isZip64 = resumeState.remainingEntries.any { it.isZip64 },
                status = ExtractionStatus.FINALIZING,
                totalEntries = resumeState.totalEntries,
                completedEntries = resumeState.completedPaths.size,
                currentFileName = "Finishing interrupted entry before restoring archive…",
                originalArchiveBytes = resumeState.originalArchiveBytes,
                currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                bytesReclaimed = resumeState.bytesReclaimedSoFar,
                journalLogs = listOf(
                    "[RESTORE] Finishing only interrupted DEFLATED entry '$interruptedDeflateEntryPath' before rebuilding remaining ZIP…"
                ),
                outputDirectory = resumeState.targetDirPath,
                startTime = startTime,
                elapsedTimeMs = 0L,
                isPauseSupported = false
            )
        )

        // Stage 1: Finish ONLY the single interrupted DEFLATED entry using Part 3A's transactional recovery engine.
        var stage1Failed = false
        var stage1ErrorProgress: ExtractionProgress? = null
        resumeInterruptedExtractionInternal(
            context = context,
            resumeState = resumeState,
            password = resumeState.password,
            pauseController = null,
            allowMutatedDeflateTransactionRecovery = true,
            stopAfterDeflateEntryPathForRestore = interruptedDeflateEntryPath
        ).collect { progress ->
            if (progress.status == ExtractionStatus.ERROR) {
                stage1Failed = true
                stage1ErrorProgress = progress.copy(isPauseSupported = false)
            } else {
                emit(
                    progress.copy(
                        status = ExtractionStatus.FINALIZING,
                        isPauseSupported = false
                    )
                )
            }
        }

        if (stage1Failed) {
            emit(
                stage1ErrorProgress ?: ExtractionProgress(
                    archiveName = resumeState.archiveName,
                    isHatchMode = true,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = "Failed to finish interrupted DEFLATED entry '$interruptedDeflateEntryPath' prior to ZIP restoration.",
                    outputDirectory = resumeState.targetDirPath,
                    isPauseSupported = false
                )
            )
            return@flow
        }

        // Stage 2: Reload the freshly persisted durable journal state from disk and verify transition invariants.
        val updatedState = ResumeJournalManager.parseStateFile(File(resumeState.stateFilePath))
        val stillHasTailSwapGuard = ResumeJournalManager.loadTailSwapGuard(resumeState.stateFilePath) != null ||
            ResumeJournalManager.hasCorruptedTailSwapGuard(resumeState.stateFilePath)
        val stillHasTailSwapCkpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(resumeState.stateFilePath) != null ||
            ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(resumeState.stateFilePath)

        if (updatedState == null ||
            !updatedState.completedPaths.contains(interruptedDeflateEntryPath) ||
            stillHasTailSwapGuard ||
            stillHasTailSwapCkpt
        ) {
            emit(
                ExtractionProgress(
                    archiveName = resumeState.archiveName,
                    isHatchMode = true,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    totalEntries = resumeState.totalEntries,
                    completedEntries = resumeState.completedPaths.size,
                    currentFileName = "Restoration failed",
                    errorMessage = "Interrupted DEFLATED entry '$interruptedDeflateEntryPath' did not reach a verified completed journal state before ZIP restoration.",
                    outputDirectory = resumeState.targetDirPath,
                    startTime = startTime,
                    elapsedTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L),
                    isPauseSupported = false
                )
            )
            return@flow
        }

        // Rebuild Central Directory / ZIP64 / EOCD in-place for all remaining unextracted entries,
        // retaining journals until full validation passes.
        ZipInPlaceRestoreEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = updatedState,
            faultInjector = restoreFaultInjector,
            simulatedAvailableDiskBytes = simulatedAvailableDiskBytes
        ).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * Resumes an interrupted in-place extraction from its saved `.hatchstate.json` / `.index.bin` backup
     * with full-stream CRC32 verification of already extracted output files and, for verified direct-POSIX
     * archives with valid v2 `DeflateTailSwapCheckpoint` state, crash-safe transactional DEFLATE recovery.
     */
    fun resumeInterruptedExtraction(
        context: Context,
        resumeState: ResumeJournalState,
        password: String? = null,
        pauseController: ExtractionPauseController? = null
    ): Flow<ExtractionProgress> = recoverInterruptedDeflateTailSwapExtraction(
        context = context,
        resumeState = resumeState,
        password = password,
        pauseController = pauseController
    )

    /**
     * Transactional recovery entry point for interrupted large DEFLATE tail-swap extractions.
     * Replays or completes verified direct-POSIX `PREPARED` / `RELOCATED_TRUNCATE_PENDING` / `COMMITTED`
     * v2 transactions after verifying output prefix CRC32, archive length, and tail/slot CRCs, while
     * failing closed on any inconsistency, corruption, legacy checkpoint, or unsupported storage mode.
     */
    internal fun recoverInterruptedDeflateTailSwapExtraction(
        context: Context,
        resumeState: ResumeJournalState,
        password: String? = null,
        pauseController: ExtractionPauseController? = null
    ): Flow<ExtractionProgress> = resumeInterruptedExtractionInternal(
        context = context,
        resumeState = resumeState,
        password = password,
        pauseController = pauseController,
        allowMutatedDeflateTransactionRecovery = true
    )

    private fun resumeInterruptedExtractionInternal(
        context: Context,
        resumeState: ResumeJournalState,
        password: String? = null,
        pauseController: ExtractionPauseController? = null,
        allowMutatedDeflateTransactionRecovery: Boolean,
        stopAfterDeflateEntryPathForRestore: String? = null
    ): Flow<ExtractionProgress> = flow {
        try {
            val targetDir = File(resumeState.targetDirPath)
            val resumeTreeUri = resumeState.targetTreeUriString?.takeIf { it.isNotEmpty() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            } ?: if (StorageHelper.isAppInternalFallbackDir(context, targetDir)) {
                StorageHelper.getGrantedFolderInfo(context)?.let {
                    runCatching { Uri.parse(it.uriString) }.getOrNull()
                }
            } else null

            val resumeFolderDocUri = resumeState.targetFolderDocUriString?.takeIf { it.isNotEmpty() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            } ?: if (resumeTreeUri != null && targetDir.name.isNotEmpty()) {
                runCatching {
                    StorageHelper.getOrCreateDocumentSubfolder(context, resumeTreeUri, targetDir.name)
                }.getOrNull()
            } else null

            val isDirectPosixTarget = StorageHelper.isDirectPosixDestination(context, targetDir, resumeTreeUri)
            val format = ZipParser.detectFormat(resumeState.archiveName)

            val effectiveSafFolderDocUri = if (!isDirectPosixTarget) {
                if (resumeTreeUri == null) {
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = "Cannot resume extraction: SAF destination folder URI is missing and app-internal fallback is disabled.",
                            originalArchiveBytes = resumeState.originalArchiveBytes,
                            currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                            bytesReclaimed = resumeState.bytesReclaimedSoFar,
                            journalLogs = listOf("SAF destination URI missing on resume; preserving source archive and journal.")
                        )
                    )
                    return@flow
                }
                val verifiedFolderUri = StorageHelper.verifySafDestinationAccessible(
                    context = context,
                    treeUri = resumeTreeUri,
                    targetFolderDocUri = resumeFolderDocUri,
                    subfolderName = targetDir.name
                )
                if (verifiedFolderUri == null) {
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = "Cannot resume extraction: selected SAF destination folder ($resumeTreeUri) is inaccessible or SAF permission was revoked.",
                            originalArchiveBytes = resumeState.originalArchiveBytes,
                            currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                            bytesReclaimed = resumeState.bytesReclaimedSoFar,
                            journalLogs = listOf("SAF destination inaccessible or permission revoked; preserving source archive and journal.")
                        )
                    )
                    return@flow
                }
                verifiedFolderUri
            } else {
                resumeFolderDocUri
            }

            val eligibility = ResumeJournalManager.assessResumeEligibility(resumeState, context)
            if (eligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable) {
                // Preserve any partial DEFLATE output and recovery metadata on disk for inspection/future recovery;
                // never delete them when reporting an unrecoverable tail-swap state.
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
                    if (isDirectPosixTarget) {
                        val dirFile = File(targetDir, meta.path)
                        if (!dirFile.exists()) dirFile.mkdirs()
                    } else if (resumeTreeUri != null) {
                        StorageHelper.ensureSafDirectoryPath(
                            context = context,
                            treeUri = resumeTreeUri,
                            targetFolderDocUri = effectiveSafFolderDocUri,
                            fallbackSubfolderName = targetDir.name,
                            relativeDirPath = meta.path
                        )
                    }
                    verifiedCompleted.add(compPath)
                    continue
                }
                if (!isDirectPosixTarget) {
                    if (resumeTreeUri != null && effectiveSafFolderDocUri != null) {
                        val expectedCrc = if (meta != null && meta.crc32 != 0L) meta.crc32 else resumeState.verifiedEntryCrcs[compPath]
                        val expectedSize = meta?.uncompressedSize ?: -1L
                        val isSafIntact = StorageHelper.verifySafOutputFileIntegrity(
                            context = context,
                            treeUri = resumeTreeUri,
                            rootFolderDocUri = effectiveSafFolderDocUri,
                            relativePath = compPath,
                            expectedSize = expectedSize,
                            expectedCrc32 = expectedCrc
                        )
                        if (isSafIntact) {
                            verifiedCompleted.add(compPath)
                        } else if (meta != null) {
                            val approxEnd = meta.localHeaderOffset + 30L + meta.path.length + meta.compressedSize
                            if (approxEnd <= physicalArchiveLen) {
                                reQueuedEntries.add(meta)
                            } else {
                                val errMsg = "Resume integrity check failed: '${meta.name}' is corrupted or missing in SAF destination and its source archive region was already truncated."
                                emit(
                                    ExtractionProgress(
                                        archiveName = resumeState.archiveName,
                                        isHatchMode = true,
                                        status = ExtractionStatus.ERROR,
                                        isCrcValid = false,
                                        errorMessage = errMsg,
                                        originalArchiveBytes = resumeState.originalArchiveBytes,
                                        currentArchiveBytesOnDisk = physicalArchiveLen,
                                        bytesReclaimed = resumeState.bytesReclaimedSoFar,
                                        journalLogs = listOf("[RECOVERY ERROR] $errMsg")
                                    )
                                )
                                return@flow
                            }
                        }
                    }
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
                            val errMsg = "Resume integrity check failed: '${meta.name}' is corrupted or missing on disk and its source archive region was already truncated."
                            emit(
                                ExtractionProgress(
                                    archiveName = resumeState.archiveName,
                                    isHatchMode = true,
                                    status = ExtractionStatus.ERROR,
                                    isCrcValid = false,
                                    errorMessage = errMsg,
                                    originalArchiveBytes = resumeState.originalArchiveBytes,
                                    currentArchiveBytesOnDisk = physicalArchiveLen,
                                    bytesReclaimed = resumeState.bytesReclaimedSoFar,
                                    journalLogs = listOf("[RECOVERY ERROR] $errMsg")
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
                    val errMsg = "Could not re-open SAF URI in writable ('rw') mode to resume Hatch Mode extraction ($err)"
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            isZeroCopySaf = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = errMsg,
                            originalArchiveBytes = resumeState.originalArchiveBytes,
                            currentArchiveBytesOnDisk = physicalArchiveLen,
                            bytesReclaimed = resumeState.bytesReclaimedSoFar,
                            journalLogs = listOf(
                                "[RECOVERY ERROR] $errMsg",
                                "[SAF] Check storage permissions or re-grant folder access before retrying."
                            )
                        )
                    )
                    return@flow
                }
                if (pfd == null) {
                    val errMsg = "Could not re-open SAF URI in writable ('rw') mode to resume Hatch Mode extraction"
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            isZeroCopySaf = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = errMsg,
                            originalArchiveBytes = resumeState.originalArchiveBytes,
                            currentArchiveBytesOnDisk = physicalArchiveLen,
                            bytesReclaimed = resumeState.bytesReclaimedSoFar,
                            journalLogs = listOf(
                                "[RECOVERY ERROR] $errMsg",
                                "[SAF] DocumentProvider returned a null file descriptor."
                            )
                        )
                    )
                    return@flow
                }
                var pendingCompletion: ExtractionProgress? = null
                var terminalEmitted = false
                var lastObservedLogs: List<String> = emptyList()
                try {
                    pfd.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { fis ->
                            val actualLen = safeChannelSize(fis.channel, descriptor.fileDescriptor, physicalArchiveLen)
                            val sourceProbe = StorageHelper.probeTransactableFileDescriptor(
                                fd = descriptor.fileDescriptor,
                                channel = fis.channel,
                                requireWriteAndTruncate = true
                            )
                            if (!sourceProbe.isSupported) {
                                val errMsg = "Cannot resume Hatch Mode extraction: source SAF URI descriptor failed transactional capability check (${sourceProbe.reason})"
                                terminalEmitted = true
                                emit(
                                    ExtractionProgress(
                                        archiveName = resumeState.archiveName,
                                        isHatchMode = true,
                                        isZeroCopySaf = true,
                                        status = ExtractionStatus.ERROR,
                                        isCrcValid = false,
                                        errorMessage = errMsg,
                                        originalArchiveBytes = resumeState.originalArchiveBytes,
                                        currentArchiveBytesOnDisk = actualLen,
                                        bytesReclaimed = resumeState.bytesReclaimedSoFar,
                                        journalLogs = listOf(
                                            "[RECOVERY ERROR] $errMsg",
                                            "[SAF] Source provider does not support POSIX ftruncate/pwrite/fsync on this URI."
                                        )
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
                                context = context,
                                targetTreeUri = resumeTreeUri,
                                targetFolderDocUri = effectiveSafFolderDocUri,
                                pauseController = pauseController,
                                allowMutatedDeflateTransactionRecovery = allowMutatedDeflateTransactionRecovery,
                                stopAfterDeflateEntryPathForRestore = stopAfterDeflateEntryPathForRestore,
                                onFinalTruncateDelete = {},
                                onReadyToComplete = { completed -> pendingCompletion = completed }
                            ).collect { progress ->
                                if (progress.journalLogs.isNotEmpty()) lastObservedLogs = progress.journalLogs
                                if (progress.status == ExtractionStatus.ERROR || progress.status == ExtractionStatus.CANCELLED) {
                                    terminalEmitted = true
                                }
                                emit(progress)
                            }
                            if (pendingCompletion != null) {
                                maybeThrowSourceCloseFaultForTest(resumeState.archiveName)
                            }
                        }
                    }
                    // Source descriptor is now closed cleanly without IOException/EIO.
                    // Safely unlink the zero-truncated SAF source document, delete recovery journal,
                    // and emit the single terminal COMPLETED status.
                    val completed = pendingCompletion
                    if (completed != null && !terminalEmitted) {
                        try {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        } catch (_: Exception) {
                        }
                        ResumeJournalManager.deleteState(resumeState.stateFilePath)
                        terminalEmitted = true
                        emit(completed)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (!terminalEmitted) {
                        val errMsg = "Resume failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                        val completedSnapshot = pendingCompletion
                        emit(
                            ExtractionProgress(
                                archiveName = resumeState.archiveName,
                                isHatchMode = true,
                                isZeroCopySaf = true,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                totalEntries = completedSnapshot?.totalEntries ?: resumeState.totalEntries,
                                completedEntries = completedSnapshot?.completedEntries ?: resumeState.completedPaths.size,
                                errorMessage = errMsg,
                                originalArchiveBytes = resumeState.originalArchiveBytes,
                                currentArchiveBytesOnDisk = completedSnapshot?.currentArchiveBytesOnDisk ?: resumeState.currentArchiveBytes,
                                bytesExtracted = completedSnapshot?.bytesExtracted ?: 0L,
                                bytesReclaimed = completedSnapshot?.bytesReclaimed ?: resumeState.bytesReclaimedSoFar,
                                outputDirectory = completedSnapshot?.outputDirectory ?: targetDir.absolutePath,
                                journalLogs = (lastObservedLogs + "[RECOVERY ERROR] $errMsg").takeLast(100)
                            )
                        )
                    }
                }
            } else {
                val archiveFile = File(resumeState.archivePath)
                if (!archiveFile.exists()) {
                    ResumeJournalManager.deleteState(resumeState.stateFilePath)
                    val errMsg = "Interrupted archive file no longer exists"
                    emit(
                        ExtractionProgress(
                            archiveName = resumeState.archiveName,
                            isHatchMode = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = errMsg,
                            originalArchiveBytes = resumeState.originalArchiveBytes,
                            bytesReclaimed = resumeState.bytesReclaimedSoFar,
                            journalLogs = listOf(
                                "[RECOVERY ERROR] $errMsg (${resumeState.archivePath})",
                                "[Journal] Cleared orphaned recovery state."
                            )
                        )
                    )
                    return@flow
                }
                var pendingCompletion: ExtractionProgress? = null
                var terminalEmitted = false
                var lastObservedLogs: List<String> = emptyList()
                try {
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
                            context = context,
                            targetTreeUri = resumeTreeUri,
                            targetFolderDocUri = effectiveSafFolderDocUri,
                            pauseController = pauseController,
                            allowMutatedDeflateTransactionRecovery = allowMutatedDeflateTransactionRecovery,
                            stopAfterDeflateEntryPathForRestore = stopAfterDeflateEntryPathForRestore,
                            onFinalTruncateDelete = {},
                            onReadyToComplete = { completed -> pendingCompletion = completed }
                        ).collect { progress ->
                            if (progress.journalLogs.isNotEmpty()) lastObservedLogs = progress.journalLogs
                            if (progress.status == ExtractionStatus.ERROR || progress.status == ExtractionStatus.CANCELLED) {
                                terminalEmitted = true
                            }
                            emit(progress)
                        }
                        if (pendingCompletion != null) {
                            maybeThrowSourceCloseFaultForTest(resumeState.archiveName)
                        }
                    }
                    // Source descriptor is now closed cleanly without IOException/EIO.
                    val completed = pendingCompletion
                    if (completed != null && !terminalEmitted) {
                        archiveFile.delete()
                        ResumeJournalManager.deleteState(resumeState.stateFilePath)
                        terminalEmitted = true
                        emit(completed)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (!terminalEmitted) {
                        val errMsg = "Resume failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                        val completedSnapshot = pendingCompletion
                        emit(
                            ExtractionProgress(
                                archiveName = resumeState.archiveName,
                                isHatchMode = true,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                totalEntries = completedSnapshot?.totalEntries ?: resumeState.totalEntries,
                                completedEntries = completedSnapshot?.completedEntries ?: resumeState.completedPaths.size,
                                errorMessage = errMsg,
                                originalArchiveBytes = resumeState.originalArchiveBytes,
                                currentArchiveBytesOnDisk = completedSnapshot?.currentArchiveBytesOnDisk ?: if (archiveFile.exists()) archiveFile.length() else 0L,
                                bytesExtracted = completedSnapshot?.bytesExtracted ?: 0L,
                                bytesReclaimed = completedSnapshot?.bytesReclaimed ?: resumeState.bytesReclaimedSoFar,
                                outputDirectory = completedSnapshot?.outputDirectory ?: targetDir.absolutePath,
                                journalLogs = (lastObservedLogs + "[RECOVERY ERROR] $errMsg").takeLast(100)
                            )
                        )
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val errMsg = "Resume failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
            emit(
                ExtractionProgress(
                    archiveName = resumeState.archiveName,
                    isHatchMode = true,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = errMsg,
                    originalArchiveBytes = resumeState.originalArchiveBytes,
                    currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
                    bytesReclaimed = resumeState.bytesReclaimedSoFar,
                    journalLogs = listOf("[RECOVERY ERROR] $errMsg")
                )
            )
        }
    }.flowOn(Dispatchers.IO)

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
        pauseController: ExtractionPauseController? = null,
        onFinalTruncateDelete: () -> Unit,
        onReadyToComplete: ((ExtractionProgress) -> Unit)? = null
    ): Flow<ExtractionProgress> = channelFlow {
        val startTime = System.currentTimeMillis()
        var lastSpeedCalcTime = startTime
        var lastEmitTime = 0L
        var bytesSinceSpeedCheck = 0L
        var currentSpeed = 0L

        val journalLock = Any()
        val journalLogs = mutableListOf<String>()
        var journalSnapshot: List<String> = emptyList()
        fun logJournal(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            synchronized(journalLock) {
                journalLogs.add("[$time] $msg")
                if (journalLogs.size > 100) journalLogs.removeAt(0)
                journalSnapshot = journalLogs.toList()
            }
        }

        val isDirectPosix7zTarget = StorageHelper.isDirectPosixDestination(context, targetDir, targetTreeUri)
        if (isDirectPosix7zTarget && !targetDir.exists()) targetDir.mkdirs()

        logJournal("KAIHATCH 7Z ENGINE: 64-Bit Zero-Copy LZMA2 Stream (${formatSize(originalArchiveSize)})")

        val isHeaderEncrypted = ZipParser.detectSevenZHeaderEncryption(channel)
        if (isHeaderEncrypted && password.isNullOrEmpty()) {
            logJournal("7Z ENCRYPTED: Archive header is encrypted with AES-256 (7zAES). Password required.")
            send(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "This .7z archive uses AES-256 header encryption. Please enter the archive password and try again.",
                    journalLogs = journalSnapshot
                )
            )
            return@channelFlow
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

        val sevenZBudget = CommonsCompressBufferStrategy.prepareHeapForSevenZExtraction(
            context = context,
            includeTruncatingChannelReserve = truncating7zChannel != null
        )
        logJournal(
            "7Z MEMORY BUDGET: Limit ${sevenZBudget.safeDecoderLimitKb} KB (${formatSize(sevenZBudget.safeDecoderLimitBytes)}) | " +
                "Heap free ${formatSize(sevenZBudget.availableHeapBytes)} / ${formatSize(sevenZBudget.maxHeapBytes)}"
        )

        var lastOpenError: Throwable? = null
        val archive = try {
            channel.position(0L)
            if (truncating7zChannel != null) {
                CommonsCompressBufferStrategy.openTruncatingSevenZFile(
                    truncatingChannel = truncating7zChannel,
                    archiveName = archiveName,
                    password = password,
                    tryRecover = false,
                    context = context,
                    maxMemoryLimitKb = sevenZBudget.safeDecoderLimitKb
                )
            } else {
                CommonsCompressBufferStrategy.openDirectSevenZFile(
                    channel = channel,
                    archiveName = archiveName,
                    password = password,
                    tryRecover = false,
                    context = context,
                    maxMemoryLimitKb = sevenZBudget.safeDecoderLimitKb
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
                send(
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
                        journalLogs = journalSnapshot
                    )
                )
                return@channelFlow
            }
            val fullErr = when (val openErr = lastOpenError) {
                is MemoryLimitException -> {
                    CommonsCompressBufferStrategy.onLowMemoryCleanup()
                    formatSevenZMemoryLimitError(
                        archiveName = archiveName,
                        memEx = openErr,
                        budget = sevenZBudget,
                        stateDesc = stateDesc
                    )
                }
                is OutOfMemoryError -> {
                    CommonsCompressBufferStrategy.onLowMemoryCleanup()
                    formatSevenZOutOfMemoryError(
                        archiveName = archiveName,
                        budget = sevenZBudget,
                        stateDesc = stateDesc
                    )
                }
                else -> {
                    val errDetail = openErr?.localizedMessage
                        ?: openErr?.message
                        ?: "Corrupted 7z archive or unsupported compression method"
                    "7z extraction failed: $errDetail [$stateDesc]"
                }
            }
            logJournal("FATAL 7Z OPEN ERROR: [${lastOpenError?.javaClass?.simpleName ?: "IOException"}] $fullErr")
            send(
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
                    journalLogs = journalSnapshot
                )
            )
            return@channelFlow
        }

        val displayDir = if (context != null && targetTreeUri != null) {
            StorageHelper.getGrantedFolderInfo(context)?.let { "${it.displayName}/${targetDir.name}" } ?: targetDir.absolutePath
        } else targetDir.absolutePath

        var activeEntryTargetOut: TargetOutput? = null
        var completedCount = 0
        var totalEntriesCount = 1
        var activeFileName = ""
        var activeEntryWritten = 0L
        var activeEntryTotal = 0L
        var activeEntryCrc = 0L

        fun build7zProgress(statusOverride: ExtractionStatus, speedOverride: Long = currentSpeed): ExtractionProgress {
            val nowMs = System.currentTimeMillis()
            val pausedMs = pauseController?.totalPausedDurationMs(nowMs) ?: 0L
            return ExtractionProgress(
                archiveName = archiveName,
                isHatchMode = isHatchMode,
                isZeroCopySaf = isZeroCopySaf,
                isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                status = statusOverride,
                totalEntries = totalEntriesCount,
                completedEntries = completedCount,
                currentFileName = activeFileName,
                currentFileBytesProcessed = activeEntryWritten,
                currentFileTotalBytes = activeEntryTotal,
                totalUncompressedBytes = totalUncompressedBytes,
                currentEntryCrc = activeEntryCrc,
                isCrcValid = true,
                originalArchiveBytes = originalArchiveSize,
                currentArchiveBytesOnDisk = currentDiskBytes,
                bytesExtracted = bytesExtracted,
                bytesReclaimed = bytesReclaimed,
                bytesPerSecond = speedOverride,
                journalLogs = journalSnapshot,
                outputDirectory = displayDir,
                startTime = startTime,
                elapsedTimeMs = (nowMs - startTime - pausedMs).coerceAtLeast(0L),
                isPauseSupported = pauseController?.isPauseSupported ?: true
            )
        }

        pauseController?.setActiveWorkerCount(1)
        pauseController?.bindCallbacks(
            onPauseRequested = {
                trySend(build7zProgress(statusOverride = ExtractionStatus.PAUSING, speedOverride = 0L))
            },
            onPauseAcknowledged = {
                currentSpeed = 0L
                logJournal("PAUSED: 7z stream suspended at safe checkpoint")
                trySend(build7zProgress(statusOverride = ExtractionStatus.PAUSED, speedOverride = 0L))
            },
            onResumed = {
                lastSpeedCalcTime = System.currentTimeMillis()
                bytesSinceSpeedCheck = 0L
                logJournal("RESUMED: Continuing 7z LZMA2 stream extraction")
                trySend(build7zProgress(statusOverride = ExtractionStatus.EXTRACTING, speedOverride = 0L))
            }
        )

        try {
            archive.use { sevenZArchive ->
                val allMeta = sevenZArchive.entries.toList()
                totalEntriesCount = allMeta.size.coerceAtLeast(1)
                totalUncompressedBytes = allMeta.sumOf { if (it.size > 0L) it.size else 0L }.coerceAtLeast(originalArchiveSize)
                logJournal("7Z ARCHIVE OPENED: $totalEntriesCount entries, uncompressed total: ${formatSize(totalUncompressedBytes)}")
                allMeta.take(3).forEach { e ->
                    logJournal("  Entry: ${e.name} (${formatSize(e.size)})")
                }
                var streamIoBuffer: ByteArray? = null

                while (coroutineContext.isActive) {
                    pauseController?.checkpoint()

                    val entry = try {
                        sevenZArchive.nextEntry
                    } catch (pwdEx: PasswordRequiredException) {
                        logJournal("ERROR: Password required for encrypted .7z")
                        send(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = isHatchMode,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                                status = ExtractionStatus.ERROR,
                                errorMessage = "This .7z archive is password-protected. Please enter the password and try again.",
                                journalLogs = journalSnapshot
                            )
                        )
                        return@channelFlow
                    } ?: break

                    // Borrow the streaming I/O buffer AFTER sevenZArchive.nextEntry has instantiated
                    // the LZMA/LZMA2 dictionary so buffer sizing adapts to post-dictionary heap headroom.
                    val buffer = streamIoBuffer ?: CommonsCompressBufferStrategy.borrowIoBuffer(originalArchiveSize).also {
                        streamIoBuffer = it
                    }

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
                            pauseController?.checkpoint()
                        }
                        continue
                    }

                    if (entry.isDirectory || rawPath.endsWith("/")) {
                        if (isDirectPosix7zTarget) {
                            val dirFile = File(targetDir, rawPath)
                            if (!dirFile.exists()) dirFile.mkdirs()
                        } else if (context != null && targetTreeUri != null) {
                            StorageHelper.ensureSafDirectoryPath(
                                context = context,
                                treeUri = targetTreeUri,
                                targetFolderDocUri = targetFolderDocUri,
                                fallbackSubfolderName = targetDir.name,
                                relativeDirPath = rawPath
                            )
                        }
                        completedCount++
                        continue
                    }

                    val entryTotal = entry.size.coerceAtLeast(0L)
                    var entryWritten = 0L
                    var lastProgressLogChunk = 0L
                    val crc = CRC32()
                    activeFileName = rawPath.substringAfterLast('/')
                    activeEntryWritten = 0L
                    activeEntryTotal = entryTotal
                    activeEntryCrc = 0L

                    logJournal("Extracting: $activeFileName (${formatSize(entryTotal)})...")

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
                                activeEntryWritten = entryWritten
                                activeEntryCrc = crc.value

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
                                    val statusNow = when {
                                        pauseController?.isPausing == true -> ExtractionStatus.PAUSING
                                        wasTruncate && isHatchMode -> ExtractionStatus.TRUNCATING
                                        else -> ExtractionStatus.EXTRACTING
                                    }
                                    send(
                                        build7zProgress(
                                            statusOverride = statusNow,
                                            speedOverride = if (statusNow == ExtractionStatus.PAUSING) 0L else currentSpeed
                                        )
                                    )
                                }

                                pauseController?.checkpoint(flushBeforePause = { out.flush() })
                            }
                            out.flush()
                            if (isHatchMode) {
                                syncTargetOutputOrThrow(targetOut, rawPath)
                            }
                        }
                        targetOut.closeStrict()
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
                        send(
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
                                journalLogs = journalSnapshot
                            )
                        )
                        return@channelFlow
                    }

                    activeEntryTargetOut = null
                    completedCount++
                    activeEntryCrc = actual7zCrc
                    logJournal("7Z Extracted & Verified [0x${String.format("%08X", actual7zCrc)}]: ${rawPath.substringAfterLast('/')} (${formatSize(entryWritten)})")

                    val nowAfterEntry = System.currentTimeMillis()
                    lastEmitTime = nowAfterEntry
                    pendingTruncateEmit = false
                    val statusAfterEntry = if (pauseController?.isPausing == true) ExtractionStatus.PAUSING else ExtractionStatus.EXTRACTING
                    send(
                        build7zProgress(
                            statusOverride = statusAfterEntry,
                            speedOverride = if (statusAfterEntry == ExtractionStatus.PAUSING) 0L else currentSpeed
                        )
                    )
                }

                if (!coroutineContext.isActive) {
                    send(
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
                            journalLogs = journalSnapshot
                        )
                    )
                    return@channelFlow
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
                    currentDiskBytes = safeChannelSize(channel, fileDescriptor, 0L)
                    if (!checkSourceSyncForFinalization(channel, fileDescriptor, archiveName)) {
                        val syncErr = "Failed to durably fsync 7z source archive '$archiveName' after final truncation."
                        logJournal("7Z FINAL SYNC ERROR: $syncErr")
                        send(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = true,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                errorMessage = syncErr,
                                totalEntries = totalEntriesCount,
                                completedEntries = completedCount,
                                originalArchiveBytes = originalArchiveSize,
                                currentArchiveBytesOnDisk = currentDiskBytes,
                                bytesExtracted = bytesExtracted,
                                bytesReclaimed = bytesReclaimed,
                                journalLogs = journalSnapshot
                            )
                        )
                        return@channelFlow
                    }
                    if (prevDisk > 0L) {
                        faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, 0L)
                    }
                    if (onReadyToComplete == null) {
                        onFinalTruncateDelete()
                    }
                    logJournal("7Z COMPLETE: Reclaimed ${formatSize(originalArchiveSize)} source archive!")
                } else {
                    logJournal("7Z COMPLETE: Extracted $completedCount files (${formatSize(bytesExtracted)}).")
                }

                val finalDisplayDir = if (context != null && targetTreeUri != null) {
                    StorageHelper.getGrantedFolderInfo(context)?.let { "${it.displayName}/${targetDir.name}" } ?: targetDir.absolutePath
                } else targetDir.absolutePath

                val endNow = System.currentTimeMillis()
                val pausedMs = pauseController?.totalPausedDurationMs(endNow) ?: 0L
                val completedProgress = ExtractionProgress(
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
                    journalLogs = journalSnapshot,
                    outputDirectory = finalDisplayDir,
                    startTime = startTime,
                    elapsedTimeMs = (endNow - startTime - pausedMs).coerceAtLeast(0L),
                    isPauseSupported = pauseController?.isPauseSupported ?: true
                )
                if (onReadyToComplete != null) {
                    onReadyToComplete(completedProgress)
                } else {
                    send(completedProgress)
                }
            }
        } catch (_: CooperativeStopException) {
            activeEntryTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            if (truncating7zChannel != null) {
                currentDiskBytes = truncating7zChannel.getCurrentPhysicalSize()
                bytesReclaimed = truncating7zChannel.getTotalReclaimedBytes()
            }
            logJournal("STOPPED: 7z extraction stopped cleanly at safe boundary")
            send(
                build7zProgress(statusOverride = ExtractionStatus.CANCELLED, speedOverride = 0L)
                    .copy(currentFileName = "Stopped at safe boundary", isPauseSupported = false)
            )
            return@channelFlow
        } catch (ce: kotlinx.coroutines.CancellationException) {
            activeEntryTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            throw ce
        } catch (pwdEx: PasswordRequiredException) {
            activeEntryTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            logJournal("7Z ERROR: Archive password required")
            send(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "This .7z archive is encrypted. Please enter the archive password.",
                    journalLogs = journalSnapshot
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
            val fullErrorMessage = when (t) {
                is MemoryLimitException -> {
                    CommonsCompressBufferStrategy.onLowMemoryCleanup()
                    formatSevenZMemoryLimitError(
                        archiveName = archiveName,
                        memEx = t,
                        budget = sevenZBudget,
                        stateDesc = stateDesc
                    )
                }
                is OutOfMemoryError -> {
                    CommonsCompressBufferStrategy.onLowMemoryCleanup()
                    formatSevenZOutOfMemoryError(
                        archiveName = archiveName,
                        budget = sevenZBudget,
                        stateDesc = stateDesc
                    )
                }
                else -> {
                    val errMessage = t.localizedMessage ?: t.message ?: "Unknown decompression error"
                    "7z extraction failed: $errMessage [$stateDesc]"
                }
            }
            logJournal("FATAL 7Z ERROR: [$errClass] $fullErrorMessage")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val traceLines = sw.toString().lines().take(12)
            for (line in traceLines) {
                if (line.isNotBlank()) {
                    logJournal("  $line")
                }
            }
            send(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    isZip64 = originalArchiveSize >= 0xFFFFFFFFL,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = fullErrorMessage,
                    completedEntries = completedCount,
                    currentArchiveBytesOnDisk = currentDiskBytes,
                    bytesReclaimed = bytesReclaimed,
                    journalLogs = journalSnapshot,
                    bytesExtracted = bytesExtracted,
                    totalUncompressedBytes = totalUncompressedBytes,
                    originalArchiveBytes = originalArchiveSize
                )
            )
        }
    }

    internal fun formatSevenZMemoryLimitError(
        archiveName: String,
        memEx: MemoryLimitException,
        budget: CommonsCompressBufferStrategy.SevenZMemoryBudget,
        stateDesc: String
    ): String {
        val neededKb = memEx.memoryNeededInKb
        val limitKb = memEx.memoryLimitInKb
        val neededMb = String.format(Locale.US, "%.1f MB", neededKb / 1024.0)
        val limitMb = String.format(Locale.US, "%.1f MB", limitKb / 1024.0)
        val maxHeapMb = String.format(Locale.US, "%.0f MB", budget.maxHeapBytes / (1024.0 * 1024.0))
        return "7z extraction failed: Archive '$archiveName' requires $neededKb KB ($neededMb) of LZMA/LZMA2 decoder memory, " +
            "which exceeds this device's safe Java heap limit of $limitKb KB ($limitMb; max app heap $maxHeapMb). " +
            "Close background tasks or re-compress the archive with a smaller LZMA2 dictionary (e.g. 64 MB). [$stateDesc]"
    }

    internal fun formatSevenZOutOfMemoryError(
        archiveName: String,
        budget: CommonsCompressBufferStrategy.SevenZMemoryBudget,
        stateDesc: String
    ): String {
        val limitMb = String.format(Locale.US, "%.1f MB", budget.safeDecoderLimitKb / 1024.0)
        val maxHeapMb = String.format(Locale.US, "%.0f MB", budget.maxHeapBytes / (1024.0 * 1024.0))
        return "7z extraction failed: Insufficient contiguous Android Java heap to allocate the LZMA/LZMA2 dictionary for '$archiveName' " +
            "(device safe decoder limit: ${budget.safeDecoderLimitKb} KB / $limitMb; max app heap $maxHeapMb). [$stateDesc]"
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
        pauseController: ExtractionPauseController? = null,
        allowMutatedDeflateTransactionRecovery: Boolean = false,
        stopAfterDeflateEntryPathForRestore: String? = null,
        onFinalTruncateDelete: () -> Unit,
        onReadyToComplete: ((ExtractionProgress) -> Unit)? = null
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
        var diskBytesVolatile = currentDiskBytes
        var reclaimedBytesVolatile = bytesReclaimed
        val activeFileNameRef = AtomicReference("")
        val activeFileProcessedRef = AtomicLong(0L)
        val activeFileTotalRef = AtomicLong(0L)
        val activeFileCrcRef = AtomicLong(0L)

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

        val isDirectPosixTarget = StorageHelper.isDirectPosixDestination(context, targetDir, targetTreeUri)
        val displayDir = if (context != null && targetTreeUri != null) {
            "SAF / " + StorageHelper.getFolderDisplayName(context, targetTreeUri)
        } else {
            if (isDirectPosixTarget && !targetDir.exists()) targetDir.mkdirs()
            targetDir.absolutePath
        }
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
                        verifiedEntryCrcs = HashMap(verifiedEntryCrcs),
                        targetTreeUriString = targetTreeUri?.toString(),
                        targetFolderDocUriString = targetFolderDocUri?.toString()
                    )
                )
                if (notifyFaultPoint) {
                    faultInjector.onPoint(DestructivePoint.AFTER_JOURNAL_SAVE, targetArchiveBytes, detail)
                }
            }
        }

        fun buildSnapshotProgress(
            status: ExtractionStatus,
            entryName: String = activeFileNameRef.get(),
            entryWritten: Long = activeFileProcessedRef.get(),
            entryTotal: Long = activeFileTotalRef.get(),
            entryCrc: Long = activeFileCrcRef.get(),
            diskBytesNow: Long = diskBytesVolatile,
            reclaimedNow: Long = reclaimedBytesVolatile,
            speedNow: Long = atomicCurrentSpeed.get(),
            nowMs: Long = System.currentTimeMillis()
        ): ExtractionProgress {
            val pausedMs = pauseController?.totalPausedDurationMs(nowMs) ?: 0L
            return ExtractionProgress(
                archiveName = archiveName,
                isHatchMode = isHatchMode,
                isZeroCopySaf = isZeroCopySaf,
                isZip64 = isZip64,
                status = status,
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
                bytesPerSecond = speedNow,
                journalLogs = journalSnapshot,
                outputDirectory = displayDir,
                startTime = startTime,
                elapsedTimeMs = (nowMs - startTime - pausedMs).coerceAtLeast(0L)
            )
        }

        pauseController?.onPauseRequested = {
            trySend(buildSnapshotProgress(status = ExtractionStatus.PAUSING, speedNow = 0L))
        }
        pauseController?.onPauseAcknowledged = {
            atomicCurrentSpeed.set(0L)
            logJournal("PAUSED: Extraction suspended at safe checkpoint (${atomicCompletedCount.get()}/$totalEntriesCount entries)")
            trySend(buildSnapshotProgress(status = ExtractionStatus.PAUSED, speedNow = 0L))
        }
        pauseController?.onResumed = {
            val nowResumed = System.currentTimeMillis()
            atomicLastSpeedCalcTime.set(nowResumed)
            atomicBytesSinceSpeedCheck.set(0L)
            logJournal("RESUMED: Extraction continuing from checkpoint")
            trySend(buildSnapshotProgress(status = ExtractionStatus.EXTRACTING, nowMs = nowResumed))
        }
        pauseController?.onFinalizingSafeBoundary = {
            atomicCurrentSpeed.set(0L)
            logJournal("FINALIZING: Completing in-flight entry boundary before restoring remaining archive…")
            trySend(
                buildSnapshotProgress(
                    status = ExtractionStatus.FINALIZING,
                    entryName = "Finalizing remaining archive…",
                    speedNow = 0L
                )
            )
        }

        if (isHatchMode) {
            logJournal("KAIHATCH ENGINE: Reverse 64-Bit Truncation (${if (isZeroCopySaf) "ZERO-COPY SAF FD" else "DIRECT FILE IO"} • $maxWorkers threads)")
            if (isZip64) logJournal("ZIP64 64-Bit Addressing Enabled (>4GB Support)")
            if (canUseResumeJournal) {
                var indexSaved = true
                if (initialCompletedPaths.isEmpty()) {
                    indexSaved = ResumeJournalManager.saveInitialIndex(stateFilePath, orderedEntries)
                }
                commitJournalCheckpoint(
                    targetArchiveBytes = currentDiskBytes,
                    reclaimedBytes = bytesReclaimed,
                    detail = "initial"
                )
                if (!indexSaved || !ResumeJournalManager.hasValidJournalOnDisk(stateFilePath, requireIndex = orderedEntries.isNotEmpty())) {
                    val errMsg = "Failed to persist crash-recovery journal before in-place truncation; aborting Hatch Mode while source archive is 100% intact."
                    logJournal("JOURNAL ERROR: $errMsg")
                    send(
                        ExtractionProgress(
                            archiveName = archiveName,
                            isHatchMode = true,
                            isZeroCopySaf = isZeroCopySaf,
                            isZip64 = isZip64,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = errMsg,
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = currentDiskBytes,
                            bytesReclaimed = bytesReclaimed,
                            journalLogs = journalSnapshot
                        )
                    )
                    return@channelFlow
                }
                logJournal("Saved crash-safe Central Directory index (${orderedEntries.size} entries) -> ${File(stateFilePath).name}")
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
            activeFileNameRef.set(entryName)
            activeFileProcessedRef.set(entryWritten)
            activeFileTotalRef.set(entryTotal)
            activeFileCrcRef.set(entryCrc)
            diskBytesVolatile = diskBytesNow
            reclaimedBytesVolatile = reclaimedNow

            if (pauseController?.isPaused == true) return

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
                val isStopping = pauseController?.isCancelled == true
                val effectiveStatus = when {
                    isStopping -> ExtractionStatus.FINALIZING
                    pauseController?.isPausing == true -> ExtractionStatus.PAUSING
                    else -> ExtractionStatus.EXTRACTING
                }
                val effectiveEntryName = if (isStopping) "Finalizing remaining archive…" else entryName
                trySend(
                    buildSnapshotProgress(
                        status = effectiveStatus,
                        entryName = effectiveEntryName,
                        entryWritten = entryWritten,
                        entryTotal = entryTotal,
                        entryCrc = entryCrc,
                        diskBytesNow = diskBytesNow,
                        reclaimedNow = reclaimedNow,
                        speedNow = if (effectiveStatus == ExtractionStatus.PAUSING || isStopping) 0L else atomicCurrentSpeed.get(),
                        nowMs = now
                    )
                )
            }
        }

        suspend fun extractSingleEntryPositional(
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
                } else if (context != null && targetTreeUri != null) {
                    StorageHelper.ensureSafDirectoryPath(
                        context = context,
                        treeUri = targetTreeUri,
                        targetFolderDocUri = targetFolderDocUri,
                        fallbackSubfolderName = targetDir.name,
                        relativeDirPath = entry.path
                    )
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
            if (isHatchMode) {
                val expectedCrc = if (entry.crc32 != 0L) entry.crc32 else verifiedEntryCrcs[entry.path]
                val physLen = safeChannelSize(channel, fileDescriptor, currentDiskBytes)
                val sourceRegionTruncated = payloadOffset + compressedPayloadSize > physLen

                if (isDirectPosixTarget) {
                    val existingDest = resolveSafeDestFile(targetDir, entry.path)
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
                } else if ((sourceRegionTruncated || initialCompletedPaths.isNotEmpty()) &&
                    context != null && targetTreeUri != null
                ) {
                    val safFolderUri = targetFolderDocUri
                        ?: StorageHelper.getOrCreateDocumentSubfolder(context, targetTreeUri, targetDir.name)
                    val isSafIntact = safFolderUri != null && StorageHelper.verifySafOutputFileIntegrity(
                        context = context,
                        treeUri = targetTreeUri,
                        rootFolderDocUri = safFolderUri,
                        relativePath = entry.path,
                        expectedSize = entry.uncompressedSize,
                        expectedCrc32 = expectedCrc
                    )
                    if (isSafIntact) {
                        val verifiedCrc = expectedCrc ?: entry.crc32
                        verifiedEntryCrcs[entry.path] = verifiedCrc
                        if (completedPaths.add(entry.path)) {
                            atomicCompletedCount.incrementAndGet()
                        }
                        atomicBytesExtracted.addAndGet(entry.uncompressedSize)
                        logJournal("RECOVERY VERIFIED (SAF) [0x${String.format("%08X", verifiedCrc)}]: ${entry.name} (${formatSize(entry.uncompressedSize)})")
                        return SingleEntryOutcome.Success(entry, entryOffset, verifiedCrc, true)
                    } else if (sourceRegionTruncated && entry.uncompressedSize > 0L) {
                        return SingleEntryOutcome.Error(
                            "Source archive region for '${entry.name}' was already truncated (offset $payloadOffset + $compressedPayloadSize > $physLen) and SAF output file is missing or incomplete."
                        )
                    }
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

            var targetOut: TargetOutput? = null

            try {
                val openedTarget = StorageHelper.openTargetOutput(
                    context = context,
                    targetDir = targetDir,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    relativePath = entry.path,
                    expectedBytes = entry.uncompressedSize
                )
                targetOut = openedTarget
                CommonsCompressBufferStrategy.wrapBufferedOutput(
                    openedTarget.outputStream,
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
                                entryCrc = entry.crc32,
                                forceEmit = orderedEntries.size <= 16 && entryBytesWritten == read.toLong()
                            )
                            pauseController?.checkpoint(
                                flushBeforePause = { outputStream.flush() }
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
                                        entryCrc = entry.crc32,
                                        forceEmit = orderedEntries.size <= 16 && entryBytesWritten == decompressed.toLong()
                                    )
                                    pauseController?.checkpoint(
                                        flushBeforePause = { outputStream.flush() }
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
                    if (isHatchMode) {
                        syncTargetOutputOrThrow(openedTarget, entry.name)
                    }
                }
                openedTarget.closeStrict()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                try {
                    targetOut?.close()
                } catch (_: Throwable) {
                }
                quarantineOutputFile(targetOut?.localFile, context, targetOut?.documentUri)
                throw ce
            } catch (fe: SimulatedFaultException) {
                try {
                    targetOut?.close()
                } catch (_: Throwable) {
                }
                throw fe
            } catch (t: Throwable) {
                try {
                    targetOut?.close()
                } catch (_: Throwable) {
                }
                quarantineOutputFile(targetOut?.localFile, context, targetOut?.documentUri)
                val errDetail = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                logJournal("DECOMPRESSION ERROR: ${entry.name} -> [${t.javaClass.simpleName}] $errDetail")
                return SingleEntryOutcome.Error(
                    "Decompression failed for '${entry.name}': $errDetail"
                )
            } finally {
                try {
                    targetOut?.close()
                } catch (_: Throwable) {
                }
            }

            val calculatedCrc = crc.value
            val isSizeMatch = entryBytesWritten == entry.uncompressedSize
            val isCrcMatch = isSizeMatch && (format == ArchiveFormat.TAR || entry.crc32 == 0L || entry.crc32 == calculatedCrc)
            if (!isCrcMatch) {
                quarantineOutputFile(targetOut?.localFile, context, targetOut?.documentUri)
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

        try {
            // Check cooperative stop/pause before reclaiming the Central Directory tail
            pauseController?.checkpoint()

            if (isHatchMode && stopAfterDeflateEntryPathForRestore == null) {
                // Immediately reclaim the Central Directory / APK Signing Block tail after the highest-offset entry
                val tailEntry = orderedEntries.firstOrNull()
                if (tailEntry != null && (format == ArchiveFormat.ZIP || format == ArchiveFormat.APK)) {
                    val tailPayloadStart = resolvePayloadOffsetPositional(channel, format, tailEntry)
                    var tailGpBitFlag = 0
                    val tailLfhBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    readFullyAtPosition(channel, tailLfhBuf, tailEntry.localHeaderOffset)
                    if (tailLfhBuf.position() >= 8) {
                        tailLfhBuf.flip()
                        tailLfhBuf.position(6)
                        tailGpBitFlag = tailLfhBuf.short.toInt() and 0xFFFF
                    }
                    val hasDataDescriptor = (tailGpBitFlag and 0x0008) != 0
                    val isZip64Tail = tailEntry.isZip64 ||
                        tailEntry.compressedSize >= 0xFFFFFFFFL ||
                        tailEntry.uncompressedSize >= 0xFFFFFFFFL
                    val ddReserve = if (!tailEntry.isDirectory && hasDataDescriptor) {
                        if (isZip64Tail) 24L else 16L
                    } else {
                        0L
                    }
                    val tailPayloadEnd = tailPayloadStart + tailEntry.compressedSize.coerceAtLeast(0L) +
                            (if (tailEntry.isEncrypted) 12L else 0L) + ddReserve
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
            }

            val windowsToExecute = if (stopAfterDeflateEntryPathForRestore != null) {
                val matching = executionWindows.filter { w ->
                    w.size == 1 &&
                        w[0].useSubEntryReverseChunk &&
                        w[0].entry.path == stopAfterDeflateEntryPathForRestore &&
                        w[0].entry.compressionMethod == 8
                }
                if (matching.size != 1) {
                    send(
                        ExtractionProgress(
                            archiveName = archiveName,
                            isHatchMode = isHatchMode,
                            isZeroCopySaf = isZeroCopySaf,
                            isZip64 = isZip64,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = "Restore recovery error: interrupted DEFLATED entry '$stopAfterDeflateEntryPathForRestore' was not uniquely matched in remaining extraction plan.",
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = currentDiskBytes,
                            bytesReclaimed = bytesReclaimed,
                            journalLogs = journalSnapshot
                        )
                    )
                    return@channelFlow
                }
                matching
            } else {
                executionWindows
            }

            for ((winIdx, window) in windowsToExecute.withIndex()) {
                pauseController?.checkpoint()
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
                if (isStoredEntry) {
                    val destFile = if (isDirectPosixTarget) resolveSafeDestFile(targetDir, entry.path) else null
                    val expectedStoredCrc = if (entry.crc32 != 0L) entry.crc32 else verifiedEntryCrcs[entry.path]
                    val alreadyVerifiedOnDisk = if (destFile != null) {
                        destFile.exists() && destFile.isFile &&
                                ResumeJournalManager.verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedStoredCrc)
                    } else if (initialCompletedPaths.isNotEmpty() && context != null && targetTreeUri != null) {
                        val safFolderUri = targetFolderDocUri
                            ?: StorageHelper.getOrCreateDocumentSubfolder(context, targetTreeUri, targetDir.name)
                        safFolderUri != null && StorageHelper.verifySafOutputFileIntegrity(
                            context = context,
                            treeUri = targetTreeUri,
                            rootFolderDocUri = safFolderUri,
                            relativePath = entry.path,
                            expectedSize = entry.uncompressedSize,
                            expectedCrc32 = expectedStoredCrc
                        )
                    } else {
                        false
                    }

                    var combinedReverseSliceCrc: Long? = null
                    var storedTargetOut: TargetOutput? = null
                    var entryBytesWritten = 0L

                    if (!alreadyVerifiedOnDisk) {
                        val physLenNow = safeChannelSize(channel, fileDescriptor, currentDiskBytes)
                        val availableInArchive = (physLenNow - payloadOffset).coerceIn(0L, entry.uncompressedSize)

                        if (availableInArchive < entry.uncompressedSize &&
                            (destFile == null || !destFile.exists() || destFile.length() != entry.uncompressedSize)
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

                        data class SliceCrcRecord(val sliceStart: Long, val sliceLen: Long, val crc32: Long)
                        val sliceCrcRecords = ArrayList<SliceCrcRecord>(16)
                        var shortReadError: String? = null
                        val isResumingPartialLocalStored = destFile != null &&
                            destFile.exists() &&
                            destFile.length() == entry.uncompressedSize &&
                            availableInArchive in 1L until entry.uncompressedSize
                        var hasTruncatedIntermediateStoredSlice = isResumingPartialLocalStored

                        try {
                            val openedTarget = StorageHelper.openTargetOutput(
                                context = context,
                                targetDir = targetDir,
                                targetTreeUri = targetTreeUri,
                                targetFolderDocUri = targetFolderDocUri,
                                relativePath = entry.path,
                                expectedBytes = entry.uncompressedSize,
                                randomAccess = true
                            )
                            storedTargetOut = openedTarget
                            val outChannel = openedTarget.channel
                            val rawFileOut = openedTarget.outputStream as? FileOutputStream
                            fun syncStoredTarget() {
                                if (isHatchMode) {
                                    syncTargetOutputOrThrow(openedTarget, entry.name)
                                } else {
                                    try {
                                        outChannel.force(false)
                                    } catch (_: Throwable) {
                                        try {
                                            rawFileOut?.fd?.sync()
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                            }

                            var sliceEnd = if (isResumingPartialLocalStored) {
                                availableInArchive
                            } else {
                                entry.uncompressedSize
                            }

                            while (sliceEnd > 0L && coroutineContext.isActive) {
                                val sliceStart = (sliceEnd - chunkStep).coerceAtLeast(0L)
                                var posInSlice = sliceStart
                                val sliceCrc = CRC32()

                                while (posInSlice < sliceEnd && coroutineContext.isActive) {
                                    val toRead = (sliceEnd - posInSlice).coerceAtMost(sharedIoBuffer.size.toLong()).toInt()
                                    sharedByteBuf.clear()
                                    sharedByteBuf.limit(toRead)
                                    val read = readChannelAtPosition(channel, sharedByteBuf, payloadOffset + posInSlice)
                                    if (read <= 0) break

                                    var outWritten = 0
                                    while (outWritten < read) {
                                        val outBb = ByteBuffer.wrap(sharedIoBuffer, outWritten, read - outWritten)
                                        val w = try {
                                            outChannel.write(outBb, posInSlice + outWritten)
                                        } catch (_: Throwable) {
                                            outChannel.position(posInSlice + outWritten)
                                            outChannel.write(outBb)
                                        }
                                        if (w <= 0) {
                                            throw java.io.IOException(
                                                "Failed to write STORED slice bytes at offset ${posInSlice + outWritten} for '${entry.name}'"
                                            )
                                        }
                                        outWritten += w
                                    }

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
                                    pauseController?.checkpoint(
                                        allowAbortCurrentEntry = !hasTruncatedIntermediateStoredSlice,
                                        flushBeforePause = { syncStoredTarget() }
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

                                // Ensure slice bytes are durably synced to output before truncating archive!
                                syncStoredTarget()

                                // Only perform intermediate sub-entry slice truncation when writing to a direct POSIX
                                // file (where RandomAccessFile can resume in-place into the existing file). For SAF
                                // destinations, keep the source archive intact until the full SAF document is written,
                                // synced, and CRC32-verified.
                                if (sliceStart > 0L && isDirectPosixTarget) {
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
                                            hasTruncatedIntermediateStoredSlice = true
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
                                pauseController?.checkpoint(
                                    allowAbortCurrentEntry = !hasTruncatedIntermediateStoredSlice
                                )
                            }
                            if (coroutineContext.isActive && shortReadError == null) {
                                syncStoredTarget()
                                openedTarget.closeStrict()
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            try {
                                storedTargetOut?.close()
                            } catch (_: Throwable) {
                            }
                            quarantineOutputFile(destFile ?: storedTargetOut?.localFile, context, storedTargetOut?.documentUri)
                            throw ce
                        } catch (fe: SimulatedFaultException) {
                            try {
                                storedTargetOut?.close()
                            } catch (_: Throwable) {
                            }
                            throw fe
                        } catch (t: Throwable) {
                            try {
                                storedTargetOut?.close()
                            } catch (_: Throwable) {
                            }
                            if (!hasTruncatedIntermediateStoredSlice) {
                                quarantineOutputFile(destFile ?: storedTargetOut?.localFile, context, storedTargetOut?.documentUri)
                            }
                            val stateDesc = describeArchiveConsumptionState(
                                isHatchMode = isHatchMode,
                                originalSize = originalArchiveSize,
                                currentDiskBytes = currentDiskBytes,
                                bytesReclaimed = bytesReclaimed,
                                payloadModifiedOrTruncated = payloadConsumedInPlace,
                                verifiedFilesCount = completedPaths.size
                            )
                            val errDetail = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                            val fullErr = "Extraction failed in STORED entry '${entry.name}': $errDetail [$stateDesc]"
                            logJournal("STORED EXTRACTION ERROR: $fullErr")
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
                                storedTargetOut?.close()
                            } catch (_: Throwable) {
                            }
                        }

                        if (shortReadError != null) {
                            if (!hasTruncatedIntermediateStoredSlice) {
                                quarantineOutputFile(destFile ?: storedTargetOut?.localFile, context, storedTargetOut?.documentUri)
                            }
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

                    // Verify reverse-slice combined CRC32 (and full-file CRC32 on local resume) before final slice / header truncation
                    val actualStoredCrc = when {
                        alreadyVerifiedOnDisk -> expectedStoredCrc
                            ?: (if (destFile != null) ResumeJournalManager.computeFileCrc32(destFile) else entry.crc32)
                        combinedReverseSliceCrc != null -> combinedReverseSliceCrc
                        destFile != null -> ResumeJournalManager.computeFileCrc32(destFile)
                        else -> -1L
                    }
                    val isStoredSizeValid = when {
                        alreadyVerifiedOnDisk -> true
                        destFile != null -> destFile.exists() && destFile.length() == entry.uncompressedSize
                        else -> entryBytesWritten == entry.uncompressedSize && storedTargetOut?.documentUri != null
                    }
                    val isStoredCrcValid = isStoredSizeValid &&
                            actualStoredCrc >= 0L &&
                            (format == ArchiveFormat.TAR || entry.crc32 == 0L || actualStoredCrc == entry.crc32)
                    if (!isStoredCrcValid) {
                        quarantineOutputFile(destFile ?: storedTargetOut?.localFile, context, storedTargetOut?.documentUri)
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
                    val alreadyVerifiedOnDisk = if (destFile != null) {
                        destFile.exists() && destFile.isFile &&
                                ResumeJournalManager.verifyOutputFileIntegrity(destFile, entry.uncompressedSize, expectedDeflateCrc)
                    } else if (initialCompletedPaths.isNotEmpty() && context != null && targetTreeUri != null) {
                        val safFolderUri = targetFolderDocUri
                            ?: StorageHelper.getOrCreateDocumentSubfolder(context, targetTreeUri, targetDir.name)
                        safFolderUri != null && StorageHelper.verifySafOutputFileIntegrity(
                            context = context,
                            treeUri = targetTreeUri,
                            rootFolderDocUri = safFolderUri,
                            relativePath = entry.path,
                            expectedSize = entry.uncompressedSize,
                            expectedCrc32 = expectedDeflateCrc
                        )
                    } else {
                        false
                    }

                    if (alreadyVerifiedOnDisk) {
                        val verifiedCrc = expectedDeflateCrc
                            ?: if (destFile != null) ResumeJournalManager.computeFileCrc32(destFile) else entry.crc32
                        ResumeJournalManager.clearTailSwapGuard(stateFilePath)
                        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateFilePath)
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
                        suspend fun emitUnrecoverableTailSwapError(errMsg: String) {
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
                        }

                        fun buildMutatedStateDesc(): String = describeArchiveConsumptionState(
                            isHatchMode = isHatchMode,
                            originalSize = originalArchiveSize,
                            currentDiskBytes = currentDiskBytes,
                            bytesReclaimed = bytesReclaimed,
                            payloadModifiedOrTruncated = true,
                            verifiedFilesCount = completedPaths.size
                        )

                        val physLenNow = safeChannelSize(channel, fileDescriptor, currentDiskBytes)
                        currentDiskBytes = physLenNow
                        val activeGuard = if (canUseResumeJournal) ResumeJournalManager.loadTailSwapGuard(stateFilePath) else null
                        val hasCorruptedGuard = activeGuard == null &&
                            canUseResumeJournal &&
                            ResumeJournalManager.hasCorruptedTailSwapGuard(stateFilePath)
                        val hasCorruptedDeflateCkpt = canUseResumeJournal &&
                            ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(stateFilePath)

                        var needsTransactionalRecovery = false

                        if (hasCorruptedGuard) {
                            // Fail closed when a committed tail-swap guard is corrupted without a valid backup;
                            // preserve partial DEFLATE output and recovery metadata.
                            emitUnrecoverableTailSwapError(
                                "Unrecoverable mid-tail-swap interruption for '${entry.name}': tail-swap recovery guard is corrupted and no valid backup exists. [${buildMutatedStateDesc()}]"
                            )
                            return@channelFlow
                        } else if (hasCorruptedDeflateCkpt) {
                            // Fail closed when a committed deflate tail-swap checkpoint is corrupted without a valid backup.
                            emitUnrecoverableTailSwapError(
                                "Unrecoverable mid-tail-swap interruption for '${entry.name}': deflate tail-swap checkpoint is corrupted and no valid backup exists. [${buildMutatedStateDesc()}]"
                            )
                            return@channelFlow
                        } else if (activeGuard != null && activeGuard.entryPath == entry.path) {
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
                                ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateFilePath)
                                logJournal(
                                    "RECOVERY RESTART: Source compressed bytes for '${entry.name}' are intact (slot 0 CRC 0x${String.format("%08X", slot0ActualCrc)} verified); restarting entry decompression"
                                )
                            } else if (allowMutatedDeflateTransactionRecovery) {
                                needsTransactionalRecovery = true
                            } else {
                                // Preserve partial DEFLATE output and recovery metadata when tail-swap mutation already started.
                                val reason = if (physLenNow >= expectedEnd) {
                                    "compressed head slot 0 was already overwritten via pwrite (expected CRC 0x${String.format("%08X", activeGuard.chunk0OriginalCrc32)}, got 0x${String.format("%08X", slot0ActualCrc)}, overwriteStarted=${activeGuard.overwriteStarted})"
                                } else {
                                    "compressed data was already overwritten and truncated ($physLenNow < $expectedEnd)"
                                }
                                emitUnrecoverableTailSwapError(
                                    "Unrecoverable mid-tail-swap interruption for '${entry.name}': $reason before entry completion, and Java Inflater cannot resume mid-stream. [${buildMutatedStateDesc()}]"
                                )
                                return@channelFlow
                            }
                        } else if (payloadOffset + compressedPayloadSize > physLenNow) {
                            if (allowMutatedDeflateTransactionRecovery) {
                                needsTransactionalRecovery = true
                            } else {
                                // Preserve partial DEFLATE output and recovery metadata when source archive was already truncated.
                                emitUnrecoverableTailSwapError(
                                    "Unrecoverable mid-tail-swap interruption for '${entry.name}': source archive region was already truncated ($physLenNow < ${payloadOffset + compressedPayloadSize}) before entry completion. [${buildMutatedStateDesc()}]"
                                )
                                return@channelFlow
                            }
                        }

                        val chunkStep = computeLargeDeflateChunkStep(compressedPayloadSize)
                        val totalChunks = ((compressedPayloadSize + chunkStep.toLong() - 1L) / chunkStep.toLong())
                            .toInt()
                            .coerceAtLeast(1)
                        logJournal("Sub-entry ${formatSize(chunkStep.toLong())} tail-swap inflate truncation active for ${entry.name} ($totalChunks chunks)")

                        // Single bounded chunk buffer reused sequentially:
                        // 1) Reads compressed chunk `c` and feeds it completely into the inflater.
                        // 2) Once chunk `c` is fully inflated (and chunk 0's CRC32 recorded), reuses the same
                        //    buffer to read and relocate `tailIdx` via pwrite, cutting buffer memory in half.
                        val activeChunkBuf = ByteArray(chunkStep)
                        val relocatedSlot = IntArray(totalChunks) { -1 }
                        val relocatedLen = IntArray(totalChunks) { 0 }
                        var canSwapInPlace = totalChunks > 1
                        val useCheckpointableInflate = canSwapInPlace

                        fun expectedDeflateChunkLen(cIdx: Int): Int {
                            val start = cIdx.toLong() * chunkStep.toLong()
                            val rem = (compressedPayloadSize - start).coerceAtLeast(0L)
                            return rem.coerceAtMost(chunkStep.toLong()).toInt()
                        }

                        fun validateRemainingChunksWithinArchive(completedChunk: Int, archiveLen: Long): Boolean {
                            for (remChunk in (completedChunk + 1) until totalChunks) {
                                val slot = relocatedSlot[remChunk]
                                val readPos = if (slot >= 0) {
                                    payloadOffset + slot.toLong() * chunkStep.toLong()
                                } else {
                                    payloadOffset + remChunk.toLong() * chunkStep.toLong()
                                }
                                val readLen = if (slot >= 0 && relocatedLen[remChunk] > 0) {
                                    relocatedLen[remChunk]
                                } else {
                                    expectedDeflateChunkLen(remChunk)
                                }
                                if (readLen <= 0 || readPos < payloadOffset || readPos + readLen.toLong() > archiveLen) {
                                    return false
                                }
                            }
                            return true
                        }

                        fun buildDeflateCheckpoint(
                            lastCompletedChunkIndex: Int,
                            txPhase: ResumeJournalManager.DeflateTailSwapTxPhase,
                            pendingOp: ResumeJournalManager.DeflateTailSwapOp = ResumeJournalManager.DeflateTailSwapOp.NONE,
                            pendingSlotIndex: Int = -1,
                            pendingTailIndex: Int = -1,
                            pendingRelocateLen: Int = 0,
                            pendingRelocateCrc32: Long = 0L,
                            pendingTruncateTarget: Long = -1L,
                            hasMutatedArchive: Boolean,
                            archiveBytes: Long,
                            inflater: CheckpointableInflateEngine,
                            inflateSnap: ByteArray
                        ): ResumeJournalManager.DeflateTailSwapCheckpoint =
                            ResumeJournalManager.DeflateTailSwapCheckpoint(
                                entryPath = entry.path,
                                entryName = entry.name,
                                payloadOffset = payloadOffset,
                                compressedSize = compressedPayloadSize,
                                uncompressedSize = entry.uncompressedSize,
                                expectedEntryCrc32 = entry.crc32,
                                chunkStep = chunkStep,
                                totalChunks = totalChunks,
                                lastCompletedChunkIndex = lastCompletedChunkIndex,
                                relocationCommittedForChunk = txPhase == ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
                                canSwapInPlace = canSwapInPlace,
                                hasMutatedArchive = hasMutatedArchive,
                                currentArchiveBytes = archiveBytes,
                                compressedBytesConsumed = inflater.compressedBytesConsumed,
                                outputBytesWritten = inflater.outputBytesWritten,
                                runningCrc32 = inflater.currentCrc32,
                                relocatedSlot = relocatedSlot.copyOf(),
                                relocatedLen = relocatedLen.copyOf(),
                                inflateCheckpointBytes = inflateSnap,
                                txPhase = txPhase,
                                pendingOp = pendingOp,
                                pendingSlotIndex = pendingSlotIndex,
                                pendingTailIndex = pendingTailIndex,
                                pendingRelocateLen = pendingRelocateLen,
                                pendingRelocateCrc32 = pendingRelocateCrc32,
                                pendingTruncateTarget = pendingTruncateTarget
                            )

                        val inflateOutBuffer = if (!useCheckpointableInflate) {
                            CommonsCompressBufferStrategy.borrowInflateBuffer(
                                entry.uncompressedSize.coerceAtLeast(originalArchiveSize)
                            )
                        } else {
                            null
                        }
                        val checkpointableInflater = if (useCheckpointableInflate) CheckpointableInflateEngine() else null
                        val crc = CRC32()
                        var entryBytesWritten = 0L
                        var hasMutatedCurrentDeflateArchive = false
                        var startChunk = 0
                        var resumedFromTransactionCheckpoint = false
                        var recoveredSafTargetOutput: TargetOutput? = null

                        // Phase-driven transactional restart recovery for mutated DEFLATE entries
                        // across both direct-POSIX storage and verified seekable read-write SAF handles.
                        if (needsTransactionalRecovery) {
                            when (
                                val replay = replayDeflateTailSwapTransactionOnRestart(
                                    canUseResumeJournal = canUseResumeJournal,
                                    stateFilePath = stateFilePath,
                                    entry = entry,
                                    payloadOffset = payloadOffset,
                                    compressedPayloadSize = compressedPayloadSize,
                                    chunkStep = chunkStep,
                                    totalChunks = totalChunks,
                                    checkpointableInflater = checkpointableInflater,
                                    isDirectPosixTarget = isDirectPosixTarget,
                                    destFile = destFile,
                                    context = context,
                                    targetTreeUri = targetTreeUri,
                                    targetFolderDocUri = targetFolderDocUri,
                                    channel = channel,
                                    fileDescriptor = fileDescriptor,
                                    activeChunkBuf = activeChunkBuf,
                                    relocatedSlot = relocatedSlot,
                                    relocatedLen = relocatedLen,
                                    initialDiskBytes = currentDiskBytes,
                                    originalArchiveSize = originalArchiveSize,
                                    faultInjector = faultInjector,
                                    buildMutatedStateDesc = ::buildMutatedStateDesc,
                                    validateRemainingChunksWithinArchive = ::validateRemainingChunksWithinArchive,
                                    buildDeflateCheckpoint = { lastCompletedChunkIndex, txPhase, pendingOp, pendingSlotIndex, pendingTailIndex, pendingRelocateLen, pendingRelocateCrc32, pendingTruncateTarget, hasMutatedArchive, archiveBytes, inflater, inflateSnap ->
                                        buildDeflateCheckpoint(
                                            lastCompletedChunkIndex = lastCompletedChunkIndex,
                                            txPhase = txPhase,
                                            pendingOp = pendingOp,
                                            pendingSlotIndex = pendingSlotIndex,
                                            pendingTailIndex = pendingTailIndex,
                                            pendingRelocateLen = pendingRelocateLen,
                                            pendingRelocateCrc32 = pendingRelocateCrc32,
                                            pendingTruncateTarget = pendingTruncateTarget,
                                            hasMutatedArchive = hasMutatedArchive,
                                            archiveBytes = archiveBytes,
                                            inflater = inflater,
                                            inflateSnap = inflateSnap
                                        )
                                    },
                                    logJournal = ::logJournal
                                )
                            ) {
                                is TransactionalRecoveryReplayResult.Failed -> {
                                    emitUnrecoverableTailSwapError(replay.errorMessage)
                                    return@channelFlow
                                }
                                is TransactionalRecoveryReplayResult.Resumed -> {
                                    hasMutatedCurrentDeflateArchive = true
                                    payloadConsumedInPlace = true
                                    entryBytesWritten = replay.outputBytesWritten
                                    atomicBytesExtracted.addAndGet(replay.outputBytesWritten)
                                    currentDiskBytes = replay.updatedDiskBytes
                                    bytesReclaimed = replay.updatedBytesReclaimed
                                    recoveredSafTargetOutput = replay.recoveredSafTargetOutput
                                    startChunk = replay.startChunk
                                    resumedFromTransactionCheckpoint = true
                                    logJournal(replay.logLine)
                                }
                            }
                        }

                        var targetOut: TargetOutput? = null

                        try {
                            val openedTarget = if (resumedFromTransactionCheckpoint && recoveredSafTargetOutput != null) {
                                val reopened = recoveredSafTargetOutput!!
                                recoveredSafTargetOutput = null
                                reopened
                            } else if (resumedFromTransactionCheckpoint && destFile != null) {
                                val appendFos = FileOutputStream(destFile, /* append = */ true)
                                TargetOutput(
                                    outputStream = appendFos,
                                    channel = appendFos.channel,
                                    localFile = destFile,
                                    documentUri = null,
                                    close = {
                                        try {
                                            appendFos.close()
                                        } catch (_: Throwable) {
                                        }
                                    },
                                    sync = { StorageHelper.syncFileDescriptorStrict(appendFos.fd, appendFos.channel) }
                                )
                            } else {
                                StorageHelper.openTargetOutput(
                                    context = context,
                                    targetDir = targetDir,
                                    targetTreeUri = targetTreeUri,
                                    targetFolderDocUri = targetFolderDocUri,
                                    relativePath = entry.path,
                                    expectedBytes = entry.uncompressedSize,
                                    randomAccess = canSwapInPlace && !isDirectPosixTarget
                                )
                            }
                            targetOut = openedTarget
                            if (!resumedFromTransactionCheckpoint && canSwapInPlace && !isDirectPosixTarget) {
                                if (!openedTarget.sync()) {
                                    throw java.io.IOException(
                                        "SAF destination provider failed durable fsync verification for '${entry.name}'; refusing crash-safe tail-swap mutation."
                                    )
                                }
                            }
                            CommonsCompressBufferStrategy.wrapBufferedOutput(
                                openedTarget.outputStream,
                                entry.uncompressedSize
                            ).use { outputStream ->
                                val rawFileOut = openedTarget.outputStream as? FileOutputStream
                                val nativeInflater = if (!useCheckpointableInflate) Inflater(true) else null
                                val progressTrackingOutput = if (useCheckpointableInflate) {
                                    object : OutputStream() {
                                        override fun write(b: Int) {
                                            outputStream.write(b)
                                            entryBytesWritten += 1L
                                            atomicBytesExtracted.addAndGet(1L)
                                            atomicBytesSinceSpeedCheck.addAndGet(1L)
                                        }

                                        override fun write(b: ByteArray, off: Int, len: Int) {
                                            if (len <= 0) return
                                            outputStream.write(b, off, len)
                                            entryBytesWritten += len.toLong()
                                            atomicBytesExtracted.addAndGet(len.toLong())
                                            atomicBytesSinceSpeedCheck.addAndGet(len.toLong())
                                            updateSpeedAndMaybeEmit(
                                                entryName = entry.name,
                                                entryWritten = entryBytesWritten,
                                                entryTotal = entry.uncompressedSize,
                                                entryCrc = entry.crc32,
                                                diskBytesNow = currentDiskBytes,
                                                reclaimedNow = bytesReclaimed
                                            )
                                        }

                                        override fun flush() {
                                            outputStream.flush()
                                        }
                                    }
                                } else {
                                    null
                                }
                                val inflateSliceStep = 512 * 1024

                                try {
                                    for (c in startChunk until totalChunks) {
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

                                        // 2. DECOMPRESS-FIRST: Feed chunk `c` to the decoder and durably flush
                                        //    all resulting decompressed output BEFORE any compressed bytes in the
                                        //    archive are overwritten or truncated!
                                        if (checkpointableInflater != null && progressTrackingOutput != null) {
                                            var sliceOffset = 0
                                            while (sliceOffset < activeChunkLimit && coroutineContext.isActive) {
                                                val sliceLen = (activeChunkLimit - sliceOffset).coerceAtMost(inflateSliceStep)
                                                val isFinalSliceOfEntry = (c == totalChunks - 1) && (sliceOffset + sliceLen >= activeChunkLimit)
                                                checkpointableInflater.processChunk(
                                                    chunk = activeChunkBuf,
                                                    offset = sliceOffset,
                                                    length = sliceLen,
                                                    isLastChunk = isFinalSliceOfEntry,
                                                    output = progressTrackingOutput
                                                )
                                                sliceOffset += sliceLen
                                                pauseController?.checkpoint(
                                                    allowAbortCurrentEntry = !hasMutatedCurrentDeflateArchive,
                                                    flushBeforePause = {
                                                        outputStream.flush()
                                                        try {
                                                            rawFileOut?.fd?.sync()
                                                        } catch (_: Throwable) {
                                                        }
                                                    }
                                                )
                                            }
                                        } else {
                                            val inflater = nativeInflater!!
                                            val outBuf = inflateOutBuffer!!
                                            var activeChunkOffset = 0
                                            while ((activeChunkOffset < activeChunkLimit || !inflater.needsInput()) && coroutineContext.isActive) {
                                                if (inflater.needsInput() && activeChunkOffset < activeChunkLimit) {
                                                    val feedLen = (activeChunkLimit - activeChunkOffset).coerceAtMost(4 * 1024 * 1024)
                                                    inflater.setInput(activeChunkBuf, activeChunkOffset, feedLen)
                                                    activeChunkOffset += feedLen
                                                }

                                                val decompressed = inflater.inflate(outBuf)
                                                if (decompressed > 0) {
                                                    outputStream.write(outBuf, 0, decompressed)
                                                    crc.update(outBuf, 0, decompressed)
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
                                                    pauseController?.checkpoint(
                                                        allowAbortCurrentEntry = !hasMutatedCurrentDeflateArchive,
                                                        flushBeforePause = {
                                                            outputStream.flush()
                                                            try {
                                                                rawFileOut?.fd?.sync()
                                                            } catch (_: Throwable) {
                                                            }
                                                        }
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
                                                    val decompressed = inflater.inflate(outBuf)
                                                    if (decompressed <= 0) break
                                                    outputStream.write(outBuf, 0, decompressed)
                                                    crc.update(outBuf, 0, decompressed)
                                                    entryBytesWritten += decompressed
                                                    atomicBytesExtracted.addAndGet(decompressed.toLong())
                                                    atomicBytesSinceSpeedCheck.addAndGet(decompressed.toLong())
                                                }
                                            }
                                        }

                                        // Durably flush and fsync decompressed output of chunk `c` to disk before any
                                        // checkpoint commit or in-loop archive mutation (`pwrite` / `ftruncate`).
                                        val willMutateArchiveInLoop = canSwapInPlace && c < totalChunks - 1 && (c <= tailIdx || secondHalfSlot > 0)
                                        val shouldPersistDeflateCheckpoint = checkpointableInflater != null &&
                                            canUseResumeJournal &&
                                            c < totalChunks - 1 &&
                                            coroutineContext.isActive
                                        if (willMutateArchiveInLoop || shouldPersistDeflateCheckpoint) {
                                            outputStream.flush()
                                            if (!openedTarget.sync()) {
                                                throw java.io.IOException(
                                                    "Failed to durably fsync extracted output for '${entry.name}' at chunk $c before tail-swap checkpoint/mutation"
                                                )
                                            }
                                        }

                                        val inflateSnapshotBytes = if (shouldPersistDeflateCheckpoint) {
                                            checkpointableInflater!!.saveCheckpointBytes()
                                        } else {
                                            null
                                        }

                                        // 3. PART 2C TRANSACTIONAL RELOCATE & TRUNCATE ORDERING:
                                        //    (a) Flush/fsync extracted output + snapshot decoder state (done above)
                                        //    (b) Persist relocation intent (`PREPARED` with tail CRC32) BEFORE `pwrite`
                                        //    (c) Perform `pwrite` + `fsync`
                                        //    (d) Persist updated relocation map + truncation intent (`RELOCATED_TRUNCATE_PENDING`)
                                        //    (e) Perform `ftruncate` + `fsync`
                                        //    (f) Commit completed chunk progress (`COMMITTED`)
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
                                            val tailChunkCrc32 = if (tailRead > 0) {
                                                val tailCrcCalc = CRC32()
                                                tailCrcCalc.update(activeChunkBuf, 0, tailRead)
                                                tailCrcCalc.value
                                            } else {
                                                0L
                                            }

                                            // Phase 1: Persist PREPARED relocation intent BEFORE destructive pwrite!
                                            if (inflateSnapshotBytes != null && checkpointableInflater != null && tailRead > 0) {
                                                val savedPrepared = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                                    stateFilePath = stateFilePath,
                                                    checkpoint = buildDeflateCheckpoint(
                                                        lastCompletedChunkIndex = c,
                                                        txPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
                                                        pendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE,
                                                        pendingSlotIndex = c,
                                                        pendingTailIndex = tailIdx,
                                                        pendingRelocateLen = tailRead,
                                                        pendingRelocateCrc32 = tailChunkCrc32,
                                                        pendingTruncateTarget = tailFilePos,
                                                        hasMutatedArchive = hasMutatedCurrentDeflateArchive,
                                                        archiveBytes = currentDiskBytes,
                                                        inflater = checkpointableInflater,
                                                        inflateSnap = inflateSnapshotBytes
                                                    )
                                                )
                                                if (!savedPrepared) {
                                                    throw java.io.IOException(
                                                        "Failed to durably persist PREPARED tail-swap checkpoint for '${entry.name}' chunk $c"
                                                    )
                                                }
                                                faultInjector.onPoint(
                                                    DestructivePoint.AFTER_TAILSWAP_PREPARED_CKPT,
                                                    currentDiskBytes,
                                                    "c=$c,tailIdx=$tailIdx,tailFilePos=$tailFilePos"
                                                )
                                            }

                                            faultInjector.onPoint(
                                                DestructivePoint.BEFORE_TAILSWAP_PWRITE,
                                                currentDiskBytes,
                                                "c=$c,tailIdx=$tailIdx,slotFilePos=$slotFilePos,tailRead=$tailRead"
                                            )
                                            if (c == 0 && canUseResumeJournal && activeChunkLimit > 0 && tailRead > 0) {
                                                hasMutatedCurrentDeflateArchive = true
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
                                                hasMutatedCurrentDeflateArchive = true
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
                                                hasMutatedCurrentDeflateArchive = true
                                                payloadConsumedInPlace = true
                                                faultInjector.onPoint(
                                                    DestructivePoint.BEFORE_TAILSWAP_PWRITE_SYNC,
                                                    currentDiskBytes,
                                                    "c=$c,tailIdx=$tailIdx,slotFilePos=$slotFilePos"
                                                )
                                                if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                                    throw java.io.IOException(
                                                        "Failed to durably fsync archive after tail-swap pwrite for '${entry.name}' chunk $c"
                                                    )
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

                                                // Phase 2: Persist updated relocation map + truncation intent BEFORE ftruncate!
                                                if (inflateSnapshotBytes != null && checkpointableInflater != null) {
                                                    val savedRelocPending = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                                        stateFilePath = stateFilePath,
                                                        checkpoint = buildDeflateCheckpoint(
                                                            lastCompletedChunkIndex = c,
                                                            txPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
                                                            pendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE,
                                                            pendingSlotIndex = c,
                                                            pendingTailIndex = tailIdx,
                                                            pendingRelocateLen = tailRead,
                                                            pendingRelocateCrc32 = tailChunkCrc32,
                                                            pendingTruncateTarget = tailFilePos,
                                                            hasMutatedArchive = true,
                                                            archiveBytes = currentDiskBytes,
                                                            inflater = checkpointableInflater,
                                                            inflateSnap = inflateSnapshotBytes
                                                        )
                                                    )
                                                    if (!savedRelocPending) {
                                                        throw java.io.IOException(
                                                            "Failed to durably persist RELOCATED_TRUNCATE_PENDING checkpoint for '${entry.name}' chunk $c"
                                                        )
                                                    }
                                                    faultInjector.onPoint(
                                                        DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
                                                        currentDiskBytes,
                                                        "c=$c,tailIdx=$tailIdx,tailFilePos=$tailFilePos"
                                                    )
                                                }

                                                if (tailFilePos in 0L until currentDiskBytes) {
                                                    val oldLen = currentDiskBytes
                                                    if (truncateDescriptor(channel, fileDescriptor, tailFilePos)) {
                                                        faultInjector.onPoint(
                                                            DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
                                                            tailFilePos,
                                                            "c=$c,tailIdx=$tailIdx"
                                                        )
                                                        if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                                            throw java.io.IOException(
                                                                "Failed to durably fsync archive after tail-swap ftruncate($tailFilePos) for '${entry.name}' chunk $c"
                                                            )
                                                        }
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
                                                if (inflateSnapshotBytes != null && checkpointableInflater != null) {
                                                    val savedMidPending = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                                        stateFilePath = stateFilePath,
                                                        checkpoint = buildDeflateCheckpoint(
                                                            lastCompletedChunkIndex = c,
                                                            txPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
                                                            pendingOp = ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE,
                                                            pendingTruncateTarget = midFilePos,
                                                            hasMutatedArchive = hasMutatedCurrentDeflateArchive,
                                                            archiveBytes = currentDiskBytes,
                                                            inflater = checkpointableInflater,
                                                            inflateSnap = inflateSnapshotBytes
                                                        )
                                                    )
                                                    if (!savedMidPending) {
                                                        throw java.io.IOException(
                                                            "Failed to durably persist RELOCATED_TRUNCATE_PENDING checkpoint for middle chunk $c of '${entry.name}'"
                                                        )
                                                    }
                                                    faultInjector.onPoint(
                                                        DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
                                                        currentDiskBytes,
                                                        "c=$c,midFilePos=$midFilePos"
                                                    )
                                                }
                                                val oldLen = currentDiskBytes
                                                if (truncateDescriptor(channel, fileDescriptor, midFilePos)) {
                                                    hasMutatedCurrentDeflateArchive = true
                                                    payloadConsumedInPlace = true
                                                    faultInjector.onPoint(
                                                        DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
                                                        midFilePos,
                                                        "c=$c,midFilePos=$midFilePos"
                                                    )
                                                    if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                                        throw java.io.IOException(
                                                            "Failed to durably fsync archive after middle-chunk ftruncate($midFilePos) for '${entry.name}' chunk $c"
                                                        )
                                                    }
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
                                                if (inflateSnapshotBytes != null && checkpointableInflater != null) {
                                                    val savedSecondHalfPending = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                                        stateFilePath = stateFilePath,
                                                        checkpoint = buildDeflateCheckpoint(
                                                            lastCompletedChunkIndex = c,
                                                            txPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
                                                            pendingOp = ResumeJournalManager.DeflateTailSwapOp.SECOND_HALF_TRUNCATE,
                                                            pendingSlotIndex = secondHalfSlot,
                                                            pendingTruncateTarget = truncTarget,
                                                            hasMutatedArchive = hasMutatedCurrentDeflateArchive,
                                                            archiveBytes = currentDiskBytes,
                                                            inflater = checkpointableInflater,
                                                            inflateSnap = inflateSnapshotBytes
                                                        )
                                                    )
                                                    if (!savedSecondHalfPending) {
                                                        throw java.io.IOException(
                                                            "Failed to durably persist RELOCATED_TRUNCATE_PENDING checkpoint for second-half chunk $c of '${entry.name}'"
                                                        )
                                                    }
                                                    faultInjector.onPoint(
                                                        DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
                                                        currentDiskBytes,
                                                        "c=$c,slot=$secondHalfSlot,target=$truncTarget"
                                                    )
                                                }
                                                val oldLen = currentDiskBytes
                                                if (truncateDescriptor(channel, fileDescriptor, truncTarget)) {
                                                    hasMutatedCurrentDeflateArchive = true
                                                    payloadConsumedInPlace = true
                                                    faultInjector.onPoint(
                                                        DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
                                                        truncTarget,
                                                        "c=$c,slot=$secondHalfSlot"
                                                    )
                                                    if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                                        throw java.io.IOException(
                                                            "Failed to durably fsync archive after second-half ftruncate($truncTarget) for '${entry.name}' chunk $c"
                                                        )
                                                    }
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

                                        // Phase 3: Commit completed chunk progress (`COMMITTED`) after ftruncate + fsync!
                                        if (inflateSnapshotBytes != null && checkpointableInflater != null) {
                                            val savedCommitted = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                                stateFilePath = stateFilePath,
                                                checkpoint = buildDeflateCheckpoint(
                                                    lastCompletedChunkIndex = c,
                                                    txPhase = ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
                                                    hasMutatedArchive = hasMutatedCurrentDeflateArchive,
                                                    archiveBytes = currentDiskBytes,
                                                    inflater = checkpointableInflater,
                                                    inflateSnap = inflateSnapshotBytes
                                                )
                                            )
                                            if (!savedCommitted) {
                                                throw java.io.IOException(
                                                    "Failed to durably persist COMMITTED tail-swap checkpoint for '${entry.name}' chunk $c"
                                                )
                                            }
                                            faultInjector.onPoint(
                                                DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
                                                currentDiskBytes,
                                                "c=$c"
                                            )
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
                                        pauseController?.checkpoint(
                                            allowAbortCurrentEntry = !hasMutatedCurrentDeflateArchive
                                        )
                                    }
                                } finally {
                                    nativeInflater?.end()
                                }
                                // Consolidated final flush + fsync of the completed output stream before
                                // closing outputStream, verifying full-entry CRC32, committing the journal
                                // checkpoint, and truncating slot 0 / local header.
                                outputStream.flush()
                                if (isHatchMode) {
                                    syncTargetOutputOrThrow(openedTarget, entry.name)
                                } else {
                                    try {
                                        rawFileOut?.fd?.sync()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            openedTarget.closeStrict()
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            try {
                                targetOut?.close()
                            } catch (_: Throwable) {
                            }
                            if (!hasMutatedCurrentDeflateArchive) {
                                quarantineOutputFile(destFile ?: targetOut?.localFile, context, targetOut?.documentUri)
                            }
                            throw ce
                        } catch (fe: SimulatedFaultException) {
                            try {
                                targetOut?.close()
                            } catch (_: Throwable) {
                            }
                            throw fe
                        } catch (t: Throwable) {
                            try {
                                targetOut?.close()
                            } catch (_: Throwable) {
                            }
                            if (!hasMutatedCurrentDeflateArchive) {
                                quarantineOutputFile(destFile ?: targetOut?.localFile, context, targetOut?.documentUri)
                            }
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
                                targetOut?.close()
                            } catch (_: Throwable) {
                            }
                        }

                        val calculatedCrc = checkpointableInflater?.currentCrc32 ?: crc.value
                        val isCrcMatch = entryBytesWritten == entry.uncompressedSize &&
                                (format == ArchiveFormat.TAR || entry.crc32 == 0L || entry.crc32 == calculatedCrc)
                        if (!isCrcMatch) {
                            quarantineOutputFile(destFile ?: targetOut?.localFile, context, targetOut?.documentUri)
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
                        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateFilePath)
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
                if (stopAfterDeflateEntryPathForRestore != null && entry.path == stopAfterDeflateEntryPathForRestore) {
                    if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                        throw java.io.IOException(
                            "Failed to durably fsync archive before committing Stage 1 restoration checkpoint for '${entry.name}'"
                        )
                    }
                    commitJournalCheckpoint(
                        targetArchiveBytes = currentDiskBytes,
                        reclaimedBytes = bytesReclaimed,
                        detail = "restore_deflate_entry_complete=${entry.name}",
                        notifyFaultPoint = false
                    )
                    logJournal("RESTORE STAGE 1 COMPLETE: Finished interrupted DEFLATED entry '${entry.name}'; stopping extraction before rebuilding remaining ZIP.")
                    send(
                        ExtractionProgress(
                            archiveName = archiveName,
                            isHatchMode = true,
                            isZeroCopySaf = isZeroCopySaf,
                            isZip64 = isZip64,
                            status = ExtractionStatus.FINALIZING,
                            totalEntries = totalEntriesCount,
                            completedEntries = atomicCompletedCount.get(),
                            currentFileName = "Finalizing remaining archive…",
                            currentFileBytesProcessed = entry.uncompressedSize,
                            currentFileTotalBytes = entry.uncompressedSize,
                            totalUncompressedBytes = totalUncompressedBytes,
                            currentEntryCrc = entry.crc32,
                            isCrcValid = true,
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = currentDiskBytes,
                            bytesExtracted = atomicBytesExtracted.get(),
                            bytesReclaimed = bytesReclaimed,
                            bytesPerSecond = 0L,
                            journalLogs = journalSnapshot,
                            outputDirectory = displayDir,
                            startTime = startTime,
                            elapsedTimeMs = nowAfterTrunc - startTime,
                            isPauseSupported = false
                        )
                    )
                    return@channelFlow
                }
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
                pauseController?.checkpoint()
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
                val capturedStop = AtomicReference<CooperativeStopException?>(null)
                pauseController?.setActiveWorkerCount(activeWorkers)
                try {
                    coroutineScope {
                        repeat(activeWorkers) {
                            launch(workerDispatcher) {
                                try {
                                    while (isActive &&
                                        fatalError.get() == null &&
                                        pauseController?.isCancelled != true &&
                                        capturedStop.get() == null
                                    ) {
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
                                                                diskBytesVolatile = currentDiskBytes
                                                                reclaimedBytesVolatile = bytesReclaimed
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
                                            pauseController?.checkpoint()
                                        }
                                    }
                                } catch (stopEx: CooperativeStopException) {
                                    capturedStop.compareAndSet(null, stopEx)
                                    throw stopEx
                                } finally {
                                    pauseController?.onWorkerExitedSafeZone()
                                }
                            }
                        }
                    }
                } finally {
                    pauseController?.setActiveWorkerCount(1)
                }

                capturedStop.get()?.let { throw it }
                if (pauseController?.isCancelled == true) {
                    throw CooperativeStopException("Extraction stopped by user")
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

            if (pauseController?.isCancelled == true) {
                throw CooperativeStopException("Extraction stopped by user")
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
            if (isHatchMode && outcomes.size == window.size && outcomes.isNotEmpty()) {
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
                        diskBytesVolatile = newLen
                        reclaimedBytesVolatile = bytesReclaimed
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
                pauseController?.checkpoint()
            }
        } catch (stopEx: CooperativeStopException) {
            pauseController?.onPauseRequested = null
            pauseController?.onPauseAcknowledged = null
            pauseController?.onResumed = null
            pauseController?.onFinalizingSafeBoundary = null

            if (!isHatchMode) {
                logJournal("STOPPED: Standard extraction cancelled by user.")
                send(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = false,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.CANCELLED,
                        totalEntries = totalEntriesCount,
                        completedEntries = atomicCompletedCount.get(),
                        currentFileName = "Cancelled",
                        totalUncompressedBytes = totalUncompressedBytes,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = 0L,
                        journalLogs = journalSnapshot,
                        outputDirectory = displayDir,
                        startTime = startTime,
                        elapsedTimeMs = System.currentTimeMillis() - startTime,
                        isPauseSupported = false
                    )
                )
                return@channelFlow
            }

            // Reclaim any contiguous completed tail entries before rebuilding the remaining Central Directory
            val fullIndexEntries = ResumeJournalManager.loadInitialIndex(stateFilePath) ?: orderedEntries
            val allDescending = fullIndexEntries.sortedByDescending { it.localHeaderOffset }
            var contiguousTailOffset = currentDiskBytes
            for (descEntry in allDescending) {
                if (completedPaths.contains(descEntry.path)) {
                    contiguousTailOffset = minOf(contiguousTailOffset, descEntry.localHeaderOffset)
                } else {
                    break
                }
            }
            if (contiguousTailOffset in 1L until currentDiskBytes) {
                val oldLen = currentDiskBytes
                val pendingFreed = (oldLen - contiguousTailOffset).coerceAtLeast(0L)
                commitJournalCheckpoint(
                    targetArchiveBytes = contiguousTailOffset,
                    reclaimedBytes = bytesReclaimed + pendingFreed,
                    detail = "stop_boundary_truncate",
                    notifyFaultPoint = false
                )
                if (truncateDescriptor(channel, fileDescriptor, contiguousTailOffset)) {
                    bytesReclaimed += pendingFreed
                    currentDiskBytes = contiguousTailOffset
                    diskBytesVolatile = currentDiskBytes
                    reclaimedBytesVolatile = bytesReclaimed
                }
            } else if (canUseResumeJournal) {
                commitJournalCheckpoint(
                    targetArchiveBytes = currentDiskBytes,
                    reclaimedBytes = bytesReclaimed,
                    detail = "stop_boundary_ckpt",
                    notifyFaultPoint = false
                )
            }

            // Check if all entries were actually completed right before stop
            val remainingCountNow = fullIndexEntries.count { !completedPaths.contains(it.path) }
            if (remainingCountNow > 0 && (format == ArchiveFormat.ZIP || format == ArchiveFormat.APK)) {
                logJournal("FINALIZING: Rebuilding Central Directory & EOCD for $remainingCountNow remaining entries…")
                send(
                    buildSnapshotProgress(
                        status = ExtractionStatus.FINALIZING,
                        entryName = "Finalizing remaining archive…",
                        diskBytesNow = currentDiskBytes,
                        reclaimedNow = bytesReclaimed,
                        speedNow = 0L
                    ).copy(isPauseSupported = false)
                )

                val restoreOutcome = ZipInPlaceRestoreEngine.restoreRemainingZipOnChannel(
                    channel = channel,
                    fd = fileDescriptor,
                    stateFilePath = stateFilePath,
                    archivePath = archivePath,
                    sourceUriString = sourceUriString,
                    archiveName = archiveName,
                    targetDir = targetDir,
                    originalArchiveBytes = originalArchiveSize,
                    bytesReclaimedSoFar = bytesReclaimed,
                    allEntries = fullIndexEntries,
                    completedPathsInput = completedPaths,
                    verifiedEntryCrcsInput = verifiedEntryCrcs,
                    context = context,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    faultInjector = faultInjector,
                    onLog = { logJournal(it) }
                )

                when (restoreOutcome) {
                    is ZipInPlaceRestoreEngine.RestoreOutcome.RestoredInPlace -> {
                        currentDiskBytes = restoreOutcome.finalArchiveBytes
                        bytesReclaimed = (originalArchiveSize - restoreOutcome.finalArchiveBytes).coerceAtLeast(0L)
                        diskBytesVolatile = currentDiskBytes
                        reclaimedBytesVolatile = bytesReclaimed
                        send(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = true,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = restoreOutcome.isZip64,
                                status = ExtractionStatus.CANCELLED,
                                totalEntries = totalEntriesCount,
                                completedEntries = completedPaths.size,
                                currentFileName = if (restoreOutcome.noRewriteNeeded) {
                                    "Stopped (archive untouched)"
                                } else {
                                    "Restored remaining archive (${restoreOutcome.remainingEntryCount} entries)"
                                },
                                totalUncompressedBytes = totalUncompressedBytes,
                                originalArchiveBytes = originalArchiveSize,
                                currentArchiveBytesOnDisk = currentDiskBytes,
                                bytesExtracted = atomicBytesExtracted.get(),
                                bytesReclaimed = bytesReclaimed,
                                journalLogs = journalSnapshot,
                                outputDirectory = displayDir,
                                startTime = startTime,
                                elapsedTimeMs = System.currentTimeMillis() - startTime,
                                isPauseSupported = false
                            )
                        )
                        return@channelFlow
                    }
                    is ZipInPlaceRestoreEngine.RestoreOutcome.Failed -> {
                        val failMsg = "Stop finalization failed: ${restoreOutcome.reason}"
                        logJournal("RESTORE ERROR: $failMsg")
                        send(
                            ExtractionProgress(
                                archiveName = archiveName,
                                isHatchMode = true,
                                isZeroCopySaf = isZeroCopySaf,
                                isZip64 = isZip64,
                                status = ExtractionStatus.ERROR,
                                isCrcValid = false,
                                totalEntries = totalEntriesCount,
                                completedEntries = completedPaths.size,
                                currentFileName = "Finalization failed",
                                errorMessage = failMsg,
                                totalUncompressedBytes = totalUncompressedBytes,
                                originalArchiveBytes = originalArchiveSize,
                                currentArchiveBytesOnDisk = currentDiskBytes,
                                bytesExtracted = atomicBytesExtracted.get(),
                                bytesReclaimed = bytesReclaimed,
                                journalLogs = journalSnapshot,
                                outputDirectory = displayDir,
                                startTime = startTime,
                                elapsedTimeMs = System.currentTimeMillis() - startTime,
                                isPauseSupported = false
                            )
                        )
                        return@channelFlow
                    }
                }
            } else if (remainingCountNow > 0) {
                // Non-ZIP Hatch Mode (e.g. TAR) or unsupported in-place ZIP restoration format
                if (bytesReclaimed == 0L && currentDiskBytes == originalArchiveSize) {
                    ResumeJournalManager.deleteState(stateFilePath)
                }
                send(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.CANCELLED,
                        totalEntries = totalEntriesCount,
                        completedEntries = completedPaths.size,
                        currentFileName = "Stopped at safe boundary",
                        totalUncompressedBytes = totalUncompressedBytes,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesExtracted = atomicBytesExtracted.get(),
                        bytesReclaimed = bytesReclaimed,
                        journalLogs = journalSnapshot,
                        outputDirectory = displayDir,
                        startTime = startTime,
                        elapsedTimeMs = System.currentTimeMillis() - startTime,
                        isPauseSupported = false
                    )
                )
                return@channelFlow
            }
        }

        val finalCompletedCount = completedPaths.size
        val finalBytesExtracted = atomicBytesExtracted.get()

        // Guard against reporting completion or deleting the source archive/journal if any scheduled
        // entry in orderedEntries was not verified and recorded in completedPaths.
        val missingEntriesCount = orderedEntries.count { !completedPaths.contains(it.path) }
        if (fatalError.get() != null || missingEntriesCount > 0 || finalCompletedCount < totalEntriesCount) {
            val errMsg = fatalError.get()
                ?: "Incomplete extraction: only $finalCompletedCount of $totalEntriesCount entries were verified ($missingEntriesCount entries remaining)."
            logJournal("INCOMPLETE EXTRACTION GUARD: $errMsg")
            pauseController?.onPauseRequested = null
            pauseController?.onPauseAcknowledged = null
            pauseController?.onResumed = null
            send(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = isHatchMode,
                    isZeroCopySaf = isZeroCopySaf,
                    isZip64 = isZip64,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    totalEntries = totalEntriesCount,
                    completedEntries = finalCompletedCount,
                    errorMessage = errMsg,
                    originalArchiveBytes = originalArchiveSize,
                    currentArchiveBytesOnDisk = currentDiskBytes,
                    bytesExtracted = finalBytesExtracted,
                    bytesReclaimed = bytesReclaimed,
                    journalLogs = journalSnapshot,
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime
                )
            )
            return@channelFlow
        }

        if (isHatchMode) {
            val prevDisk = currentDiskBytes
            // Durably record all verified entries and 0 remaining entries before final zero-truncation
            // so a post-truncate fsync/close failure can never lose track of verified extraction results
            // or leave a misleading resumable state.
            commitJournalCheckpoint(
                targetArchiveBytes = 0L,
                reclaimedBytes = originalArchiveSize,
                detail = "final_zero_truncate",
                notifyFaultPoint = false
            )
            faultInjector.onPoint(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, archiveName)
            truncateDescriptor(channel, fileDescriptor, 0L)
            currentDiskBytes = safeChannelSize(channel, fileDescriptor, 0L)
            if (!checkSourceSyncForFinalization(channel, fileDescriptor, archiveName)) {
                val syncErr = "Failed to durably fsync source archive '$archiveName' after final truncation; preserving recovery journal."
                logJournal("FINAL SYNC ERROR: $syncErr")
                pauseController?.onPauseRequested = null
                pauseController?.onPauseAcknowledged = null
                pauseController?.onResumed = null
                send(
                    ExtractionProgress(
                        archiveName = archiveName,
                        isHatchMode = true,
                        isZeroCopySaf = isZeroCopySaf,
                        isZip64 = isZip64,
                        status = ExtractionStatus.ERROR,
                        isCrcValid = false,
                        totalEntries = totalEntriesCount,
                        completedEntries = finalCompletedCount,
                        errorMessage = syncErr,
                        originalArchiveBytes = originalArchiveSize,
                        currentArchiveBytesOnDisk = currentDiskBytes,
                        bytesExtracted = finalBytesExtracted,
                        bytesReclaimed = bytesReclaimed,
                        journalLogs = journalSnapshot,
                        startTime = startTime,
                        elapsedTimeMs = System.currentTimeMillis() - startTime
                    )
                )
                return@channelFlow
            }
            if (prevDisk > 0L) {
                faultInjector.onPhysicalTruncate(DestructivePoint.BEFORE_FINAL_ZERO_TRUNCATE, prevDisk, 0L)
            }
            if (onReadyToComplete == null) {
                onFinalTruncateDelete()
                ResumeJournalManager.deleteState(stateFilePath)
            }
            logJournal("COMPLETE: Unpacked in-place & reclaimed ${formatSize(originalArchiveSize)}!")
        } else {
            logJournal("COMPLETE: Extracted $finalCompletedCount files (${formatSize(finalBytesExtracted)}).")
        }

        val finalDisplayDir = if (context != null && targetTreeUri != null) {
            StorageHelper.getGrantedFolderInfo(context)?.let { "${it.displayName}/${targetDir.name}" } ?: targetDir.absolutePath
        } else targetDir.absolutePath

        pauseController?.onPauseRequested = null
        pauseController?.onPauseAcknowledged = null
        pauseController?.onResumed = null

        val completedProgress = ExtractionProgress(
            archiveName = archiveName,
            isHatchMode = isHatchMode,
            isZeroCopySaf = isZeroCopySaf,
            isZip64 = isZip64,
            status = ExtractionStatus.COMPLETED,
            totalEntries = totalEntriesCount,
            completedEntries = finalCompletedCount,
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
        if (onReadyToComplete != null) {
            onReadyToComplete(completedProgress)
        } else {
            send(completedProgress)
        }
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

    private fun syncTargetOutputOrThrow(openedTarget: TargetOutput, entryName: String) {
        val synced = outputSyncFaultHookForTest?.invoke(entryName) ?: openedTarget.sync()
        if (!synced) {
            throw java.io.IOException(
                "Failed to durably fsync completed extracted output for '$entryName' before finalizing entry"
            )
        }
    }

    private fun checkSourceSyncForFinalization(
        channel: FileChannel,
        fd: FileDescriptor?,
        archiveName: String
    ): Boolean {
        return sourceSyncFaultHookForTest?.invoke(archiveName) ?: syncArchiveDescriptor(channel, fd)
    }

    private fun maybeThrowSourceCloseFaultForTest(archiveName: String) {
        sourceCloseFaultHookForTest?.invoke(archiveName)?.let { throw it }
    }

    private fun syncArchiveDescriptor(channel: FileChannel, fd: FileDescriptor?): Boolean {
        return StorageHelper.syncFileDescriptorStrict(fd, channel)
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
        pauseController: ExtractionPauseController? = null,
        onDeleteSource: () -> Unit
    ): Flow<ExtractionProgress> = channelFlow {
        val startTime = System.currentTimeMillis()
        val logs = mutableListOf("[00:00] Decompressing GZIP TAR stream...")
        val isDirectPosixTarGzTarget = StorageHelper.isDirectPosixDestination(context, targetDir, targetTreeUri)
        val displayDir = if (targetTreeUri != null && context != null) {
            "SAF / " + StorageHelper.getFolderDisplayName(context, targetTreeUri)
        } else {
            if (isDirectPosixTarGzTarget && !targetDir.exists()) targetDir.mkdirs()
            targetDir.absolutePath
        }

        var bytesExtracted = 0L
        var filesCount = 0
        var currentEntryName = ""
        var currentEntryProcessed = 0L
        var currentEntryTotal = 0L
        var activeTargetOut: TargetOutput? = null
        val streamExtractedOutputs = mutableListOf<TargetOutput>()

        fun buildTarGzSnapshot(status: ExtractionStatus): ExtractionProgress {
            val now = System.currentTimeMillis()
            val pausedMs = pauseController?.totalPausedDurationMs(now) ?: 0L
            return ExtractionProgress(
                archiveName = archiveName,
                isHatchMode = false,
                status = status,
                totalEntries = (filesCount + 1).coerceAtLeast(1),
                completedEntries = filesCount,
                currentFileName = currentEntryName,
                currentFileBytesProcessed = currentEntryProcessed,
                currentFileTotalBytes = currentEntryTotal,
                originalArchiveBytes = originalArchiveSize,
                currentArchiveBytesOnDisk = originalArchiveSize,
                bytesExtracted = bytesExtracted,
                journalLogs = synchronized(logs) { logs.toList() },
                outputDirectory = displayDir,
                startTime = startTime,
                elapsedTimeMs = (now - startTime - pausedMs).coerceAtLeast(0L)
            )
        }

        pauseController?.onPauseRequested = {
            trySend(buildTarGzSnapshot(ExtractionStatus.PAUSING))
        }
        pauseController?.onPauseAcknowledged = {
            synchronized(logs) {
                logs.add("PAUSED: TAR.GZ stream suspended at safe checkpoint")
            }
            trySend(buildTarGzSnapshot(ExtractionStatus.PAUSED))
        }
        pauseController?.onResumed = {
            synchronized(logs) {
                logs.add("RESUMED: TAR.GZ stream continuing")
            }
            trySend(buildTarGzSnapshot(ExtractionStatus.EXTRACTING))
        }

        try {
            GZIPInputStream(BufferedInputStream(openStream())).use { gzIn ->
                val block = ByteArray(512)
                val buffer = ByteArray(32 * 1024)

                while (coroutineContext.isActive) {
                    pauseController?.checkpoint()
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
                            if (isDirectPosixTarGzTarget) {
                                File(targetDir, name).mkdirs()
                            } else if (targetTreeUri != null && context != null) {
                                StorageHelper.ensureSafDirectoryPath(
                                    context = context,
                                    treeUri = targetTreeUri,
                                    targetFolderDocUri = targetFolderDocUri,
                                    fallbackSubfolderName = targetDir.name,
                                    relativeDirPath = name
                                )
                            }
                        }
                    } else {
                        currentEntryName = name.substringAfterLast('/')
                        currentEntryProcessed = 0L
                        currentEntryTotal = entrySize
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
                                currentEntryProcessed += r
                                if (shouldExtract) bytesExtracted += r
                                pauseController?.checkpoint(
                                    flushBeforePause = { targetOut?.outputStream?.flush() }
                                )
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
                            synchronized(logs) {
                                logs.add("Extracted TAR.GZ entry: $name (${formatSize(entrySize)})")
                            }
                            send(
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
                                    journalLogs = synchronized(logs) { logs.toList() },
                                    outputDirectory = displayDir,
                                    startTime = startTime,
                                    elapsedTimeMs = System.currentTimeMillis() - startTime
                                )
                            )
                            pauseController?.checkpoint()
                        }
                    }
                }
            }

            onDeleteSource()
            synchronized(logs) {
                logs.add("COMPLETE: Unpacked $filesCount files from $archiveName")
            }
            pauseController?.onPauseRequested = null
            pauseController?.onPauseAcknowledged = null
            pauseController?.onResumed = null
            send(
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
                    journalLogs = synchronized(logs) { logs.toList() },
                    outputDirectory = displayDir,
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime
                )
            )
        } catch (_: CooperativeStopException) {
            pauseController?.onPauseRequested = null
            pauseController?.onPauseAcknowledged = null
            pauseController?.onResumed = null
            activeTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            synchronized(logs) {
                logs.add("STOPPED: TAR.GZ extraction stopped at safe boundary")
            }
            send(
                ExtractionProgress(
                    archiveName = archiveName,
                    isHatchMode = false,
                    status = ExtractionStatus.CANCELLED,
                    totalEntries = filesCount,
                    completedEntries = filesCount,
                    currentFileName = "Stopped at safe boundary",
                    originalArchiveBytes = originalArchiveSize,
                    currentArchiveBytesOnDisk = originalArchiveSize,
                    bytesExtracted = bytesExtracted,
                    journalLogs = synchronized(logs) { logs.toList() },
                    outputDirectory = displayDir,
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime,
                    isPauseSupported = false
                )
            )
            return@channelFlow
        } catch (ce: kotlinx.coroutines.CancellationException) {
            pauseController?.onPauseRequested = null
            pauseController?.onPauseAcknowledged = null
            pauseController?.onResumed = null
            activeTargetOut?.let { quarantineOutputFile(it.localFile, context, it.documentUri) }
            throw ce
        } catch (t: Throwable) {
            pauseController?.onPauseRequested = null
            pauseController?.onPauseAcknowledged = null
            pauseController?.onResumed = null
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
            synchronized(logs) {
                logs.add("ERROR: $errMsg")
            }
            send(
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
                    journalLogs = synchronized(logs) { logs.toList() },
                    outputDirectory = displayDir,
                    startTime = startTime,
                    elapsedTimeMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    private sealed interface TransactionalRecoveryReplayResult {
        data class Resumed(
            val startChunk: Int,
            val outputBytesWritten: Long,
            val updatedDiskBytes: Long,
            val updatedBytesReclaimed: Long,
            val recoveredSafTargetOutput: TargetOutput?,
            val logLine: String
        ) : TransactionalRecoveryReplayResult

        data class Failed(val errorMessage: String) : TransactionalRecoveryReplayResult
    }

    private fun replayDeflateTailSwapTransactionOnRestart(
        canUseResumeJournal: Boolean,
        stateFilePath: String,
        entry: ZipEntryInfo,
        payloadOffset: Long,
        compressedPayloadSize: Long,
        chunkStep: Int,
        totalChunks: Int,
        checkpointableInflater: CheckpointableInflateEngine?,
        isDirectPosixTarget: Boolean,
        destFile: File?,
        context: Context?,
        targetTreeUri: Uri?,
        targetFolderDocUri: Uri?,
        channel: FileChannel,
        fileDescriptor: FileDescriptor?,
        activeChunkBuf: ByteArray,
        relocatedSlot: IntArray,
        relocatedLen: IntArray,
        initialDiskBytes: Long,
        originalArchiveSize: Long,
        faultInjector: TruncationFaultInjector,
        buildMutatedStateDesc: () -> String,
        validateRemainingChunksWithinArchive: (Int, Long) -> Boolean,
        buildDeflateCheckpoint: (
            lastCompletedChunkIndex: Int,
            txPhase: ResumeJournalManager.DeflateTailSwapTxPhase,
            pendingOp: ResumeJournalManager.DeflateTailSwapOp,
            pendingSlotIndex: Int,
            pendingTailIndex: Int,
            pendingRelocateLen: Int,
            pendingRelocateCrc32: Long,
            pendingTruncateTarget: Long,
            hasMutatedArchive: Boolean,
            archiveBytes: Long,
            inflater: CheckpointableInflateEngine,
            inflateSnap: ByteArray
        ) -> ResumeJournalManager.DeflateTailSwapCheckpoint,
        logJournal: (String) -> Unit
    ): TransactionalRecoveryReplayResult {
        val ckpt = if (canUseResumeJournal) {
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFilePath)
        } else {
            null
        }
        if (ckpt == null) {
            return TransactionalRecoveryReplayResult.Failed(
                "Unrecoverable mid-tail-swap interruption for '${entry.name}': missing valid deflate tail-swap transaction checkpoint. [${buildMutatedStateDesc()}]"
            )
        }
        if (ckpt.formatVersion < ResumeJournalManager.DEFLATE_TAILSWAP_CKPT_VERSION) {
            return TransactionalRecoveryReplayResult.Failed(
                "Unrecoverable mid-tail-swap interruption for '${entry.name}': legacy v${ckpt.formatVersion} deflate tail-swap checkpoint lacks transactional phase metadata. [${buildMutatedStateDesc()}]"
            )
        }
        if (checkpointableInflater == null ||
            ckpt.entryPath != entry.path ||
            ckpt.payloadOffset != payloadOffset ||
            ckpt.compressedSize != compressedPayloadSize ||
            ckpt.uncompressedSize != entry.uncompressedSize ||
            ckpt.expectedEntryCrc32 != entry.crc32 ||
            ckpt.chunkStep != chunkStep ||
            ckpt.totalChunks != totalChunks ||
            !ckpt.canSwapInPlace ||
            ckpt.lastCompletedChunkIndex !in 0 until (totalChunks - 1)
        ) {
            return TransactionalRecoveryReplayResult.Failed(
                "Unrecoverable mid-tail-swap interruption for '${entry.name}': checkpoint metadata does not match archive entry layout. [${buildMutatedStateDesc()}]"
            )
        }

        var recoveredSafTargetOutput: TargetOutput? = null
        if (isDirectPosixTarget && destFile != null) {
            if (!destFile.exists() || !destFile.isFile || destFile.length() < ckpt.outputBytesWritten) {
                return TransactionalRecoveryReplayResult.Failed(
                    "Unrecoverable mid-tail-swap interruption for '${entry.name}': partial output file is missing or shorter than checkpoint offset (${if (destFile.exists()) destFile.length() else -1L} < ${ckpt.outputBytesWritten}). [${buildMutatedStateDesc()}]"
                )
            }

            // Trim any uncommitted trailing output bytes written after the last checkpointed chunk,
            // and verify durable fsync on the partial output descriptor.
            val trimAndSyncOk = try {
                RandomAccessFile(destFile, "rw").use { outRaf ->
                    if (destFile.length() > ckpt.outputBytesWritten) {
                        outRaf.channel.truncate(ckpt.outputBytesWritten)
                    }
                    StorageHelper.syncFileDescriptorStrict(outRaf.fd, outRaf.channel)
                } && destFile.length() == ckpt.outputBytesWritten
            } catch (_: Throwable) {
                false
            }
            if (!trimAndSyncOk) {
                return TransactionalRecoveryReplayResult.Failed(
                    "Unrecoverable mid-tail-swap interruption for '${entry.name}': failed to truncate or durably fsync partial output file back to checkpoint offset ${ckpt.outputBytesWritten}. [${buildMutatedStateDesc()}]"
                )
            }

            // Verify exact prefix CRC32 of partial output on disk before mutating or appending anything.
            val actualOutputPrefixCrc = ResumeJournalManager.computeFilePrefixCrc32(destFile, ckpt.outputBytesWritten)
            if (actualOutputPrefixCrc != ckpt.runningCrc32) {
                return TransactionalRecoveryReplayResult.Failed(
                    "Unrecoverable mid-tail-swap interruption for '${entry.name}': partial output prefix CRC32 mismatch (expected 0x${String.format("%08X", ckpt.runningCrc32)}, got 0x${String.format("%08X", actualOutputPrefixCrc)}). [${buildMutatedStateDesc()}]"
                )
            }
        } else {
            if (context == null || targetTreeUri == null || targetFolderDocUri == null) {
                return TransactionalRecoveryReplayResult.Failed(
                    "Unrecoverable mid-tail-swap interruption for '${entry.name}': transactional recovery requires a writable direct-POSIX destination or accessible seekable read-write SAF destination. [${buildMutatedStateDesc()}]"
                )
            }
            when (
                val safOpen = StorageHelper.openExistingSafOutputForRecovery(
                    context = context,
                    treeUri = targetTreeUri,
                    rootFolderDocUri = targetFolderDocUri,
                    relativePath = ckpt.entryPath,
                    expectedOffset = ckpt.outputBytesWritten,
                    expectedPrefixCrc32 = ckpt.runningCrc32
                )
            ) {
                is com.biplov.kaihatch.data.SafRecoveryOutputOpenResult.Opened -> {
                    recoveredSafTargetOutput = safOpen.targetOutput
                    if (safOpen.trimmedTrailingBytes > 0L) {
                        logJournal(
                            "SAF PARTIAL OUTPUT TRIMMED: '${entry.name}' trimmed ${safOpen.trimmedTrailingBytes} uncommitted trailing bytes back to verified offset ${ckpt.outputBytesWritten}"
                        )
                    }
                }
                is com.biplov.kaihatch.data.SafRecoveryOutputOpenResult.Failed -> {
                    return TransactionalRecoveryReplayResult.Failed(
                        "Unrecoverable mid-tail-swap interruption for '${entry.name}': ${safOpen.reason} [${buildMutatedStateDesc()}]"
                    )
                }
            }
        }

        // Restore CheckpointableInflateEngine state and relocation map.
        val restoredDecoderOk = try {
            checkpointableInflater.restoreFromCheckpointBytes(ckpt.inflateCheckpointBytes)
            checkpointableInflater.compressedBytesConsumed == ckpt.compressedBytesConsumed &&
                checkpointableInflater.outputBytesWritten == ckpt.outputBytesWritten &&
                checkpointableInflater.currentCrc32 == ckpt.runningCrc32
        } catch (_: Throwable) {
            false
        }
        if (!restoredDecoderOk) {
            try { recoveredSafTargetOutput?.close() } catch (_: Throwable) {}
            return TransactionalRecoveryReplayResult.Failed(
                "Unrecoverable mid-tail-swap interruption for '${entry.name}': failed to restore consistent DEFLATE decoder state from checkpoint. [${buildMutatedStateDesc()}]"
            )
        }

        System.arraycopy(ckpt.relocatedSlot, 0, relocatedSlot, 0, totalChunks)
        System.arraycopy(ckpt.relocatedLen, 0, relocatedLen, 0, totalChunks)
        var currentDiskBytes = initialDiskBytes
        var bytesReclaimed = (originalArchiveSize - currentDiskBytes).coerceAtLeast(0L)

        val cDone = ckpt.lastCompletedChunkIndex
        val recoveryFailureMsg: String? = try {
            when (ckpt.txPhase) {
                ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED -> {
                    val expectedTailIdx = totalChunks - 1 - cDone
                    val expectedTailPos = payloadOffset + expectedTailIdx.toLong() * chunkStep.toLong()
                    if (ckpt.pendingOp != ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE ||
                        ckpt.pendingSlotIndex != cDone ||
                        ckpt.pendingTailIndex != expectedTailIdx ||
                        ckpt.pendingSlotIndex >= ckpt.pendingTailIndex ||
                        ckpt.pendingTruncateTarget != expectedTailPos
                    ) {
                        "inconsistent PREPARED transaction metadata for chunk $cDone"
                    } else if (currentDiskBytes < expectedTailPos + ckpt.pendingRelocateLen.toLong()) {
                        "archive was truncated below intact tail chunk ($currentDiskBytes < ${expectedTailPos + ckpt.pendingRelocateLen.toLong()}) while in PREPARED phase"
                    } else {
                        val tailBb = ByteBuffer.wrap(activeChunkBuf, 0, ckpt.pendingRelocateLen)
                        readFullyAtPosition(channel, tailBb, expectedTailPos)
                        val tailRead = tailBb.position()
                        val tailCrc = if (tailRead == ckpt.pendingRelocateLen) {
                            val cCalc = CRC32()
                            cCalc.update(activeChunkBuf, 0, tailRead)
                            cCalc.value
                        } else -1L

                        if (tailCrc != ckpt.pendingRelocateCrc32) {
                            "tail chunk CRC32 mismatch during PREPARED replay (expected 0x${String.format("%08X", ckpt.pendingRelocateCrc32)}, got 0x${String.format("%08X", tailCrc)})"
                        } else {
                            val slotFilePos = payloadOffset + ckpt.pendingSlotIndex.toLong() * chunkStep.toLong()
                            faultInjector.onPoint(
                                DestructivePoint.BEFORE_TAILSWAP_PWRITE,
                                currentDiskBytes,
                                "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx,slotFilePos=$slotFilePos,tailRead=$tailRead"
                            )
                            if (faultInjector.shouldSimulateTornWrite(DestructivePoint.BEFORE_TAILSWAP_PWRITE)) {
                                val halfLen = (tailRead / 2).coerceAtLeast(1)
                                writeChannelAtPosition(channel, fileDescriptor, activeChunkBuf, 0, halfLen, slotFilePos)
                                faultInjector.onPhysicalSlotWrite(
                                    DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
                                    cDone,
                                    expectedTailIdx,
                                    slotFilePos,
                                    halfLen
                                )
                                faultInjector.onPoint(
                                    DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
                                    currentDiskBytes,
                                    "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx,tornBytes=$halfLen/$tailRead"
                                )
                            }
                            if (!writeChannelAtPosition(channel, fileDescriptor, activeChunkBuf, 0, tailRead, slotFilePos)) {
                                "failed to replay pwrite for chunk $cDone during PREPARED recovery"
                            } else {
                                faultInjector.onPoint(
                                    DestructivePoint.BEFORE_TAILSWAP_PWRITE_SYNC,
                                    currentDiskBytes,
                                    "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx"
                                )
                                if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                    "failed to durably fsync archive after pwrite during PREPARED recovery for chunk $cDone"
                                } else {
                                    faultInjector.onPhysicalSlotWrite(
                                        DestructivePoint.BEFORE_TAILSWAP_PWRITE,
                                        cDone,
                                        expectedTailIdx,
                                        slotFilePos,
                                        tailRead
                                    )
                                    faultInjector.onPoint(
                                        DestructivePoint.AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE,
                                        currentDiskBytes,
                                        "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx,tailFilePos=$expectedTailPos"
                                    )

                                    relocatedSlot[expectedTailIdx] = cDone
                                    relocatedLen[expectedTailIdx] = tailRead
                                    val savedReloc = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                        stateFilePath = stateFilePath,
                                        checkpoint = buildDeflateCheckpoint(
                                            cDone,
                                            ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
                                            ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE,
                                            cDone,
                                            expectedTailIdx,
                                            tailRead,
                                            ckpt.pendingRelocateCrc32,
                                            expectedTailPos,
                                            true,
                                            currentDiskBytes,
                                            checkpointableInflater,
                                            ckpt.inflateCheckpointBytes
                                        )
                                    )
                                    if (!savedReloc) {
                                        "failed to persist RELOCATED_TRUNCATE_PENDING checkpoint during PREPARED recovery"
                                    } else {
                                        faultInjector.onPoint(
                                            DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
                                            currentDiskBytes,
                                            "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx,target=$expectedTailPos"
                                        )
                                        if (expectedTailPos in 0L until currentDiskBytes) {
                                            val oldLen = currentDiskBytes
                                            if (!truncateDescriptor(channel, fileDescriptor, expectedTailPos)) {
                                                "failed to execute ftruncate($expectedTailPos) during PREPARED recovery"
                                            } else {
                                                faultInjector.onPoint(
                                                    DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
                                                    expectedTailPos,
                                                    "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx"
                                                )
                                                if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                                    "failed to durably fsync archive after ftruncate($expectedTailPos) during PREPARED recovery"
                                                } else {
                                                    val freed = (oldLen - expectedTailPos).coerceAtLeast(0L)
                                                    bytesReclaimed += freed
                                                    currentDiskBytes = expectedTailPos
                                                    faultInjector.onPhysicalTruncate(
                                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                        oldLen,
                                                        currentDiskBytes
                                                    )
                                                    faultInjector.onPoint(
                                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                                        currentDiskBytes,
                                                        "recovery=PREPARED,c=$cDone,tailIdx=$expectedTailIdx"
                                                    )
                                                    null
                                                }
                                            }
                                        } else null
                                    }?.let { it } ?: if (!validateRemainingChunksWithinArchive(cDone, currentDiskBytes)) {
                                        "remaining compressed chunks do not fit within archive size $currentDiskBytes after PREPARED recovery"
                                    } else {
                                        val savedCommitted = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                            stateFilePath = stateFilePath,
                                            checkpoint = buildDeflateCheckpoint(
                                                cDone,
                                                ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
                                                ResumeJournalManager.DeflateTailSwapOp.NONE,
                                                -1,
                                                -1,
                                                0,
                                                0L,
                                                -1L,
                                                true,
                                                currentDiskBytes,
                                                checkpointableInflater,
                                                ckpt.inflateCheckpointBytes
                                            )
                                        )
                                        if (!savedCommitted) {
                                            "failed to persist COMMITTED checkpoint after PREPARED recovery"
                                        } else {
                                            faultInjector.onPoint(
                                                DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
                                                currentDiskBytes,
                                                "recovery=PREPARED,c=$cDone"
                                            )
                                            null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING -> {
                    var phaseErr: String? = null
                    if (ckpt.pendingOp == ResumeJournalManager.DeflateTailSwapOp.NONE) {
                        phaseErr = "RELOCATED_TRUNCATE_PENDING checkpoint has pendingOp=NONE"
                    } else if (ckpt.pendingOp == ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE) {
                        val slotFilePos = payloadOffset + ckpt.pendingSlotIndex.toLong() * chunkStep.toLong()
                        if (relocatedSlot[ckpt.pendingTailIndex] != ckpt.pendingSlotIndex ||
                            relocatedLen[ckpt.pendingTailIndex] != ckpt.pendingRelocateLen ||
                            slotFilePos + ckpt.pendingRelocateLen.toLong() > currentDiskBytes
                        ) {
                            phaseErr = "inconsistent slot bounds in RELOCATED_TRUNCATE_PENDING for chunk $cDone"
                        } else {
                            val slotBb = ByteBuffer.wrap(activeChunkBuf, 0, ckpt.pendingRelocateLen)
                            readFullyAtPosition(channel, slotBb, slotFilePos)
                            val slotRead = slotBb.position()
                            val slotCrc = if (slotRead == ckpt.pendingRelocateLen) {
                                val cCalc = CRC32()
                                cCalc.update(activeChunkBuf, 0, slotRead)
                                cCalc.value
                            } else -1L
                            if (slotCrc != ckpt.pendingRelocateCrc32) {
                                phaseErr = "relocated slot ${ckpt.pendingSlotIndex} CRC32 mismatch in RELOCATED_TRUNCATE_PENDING (expected 0x${String.format("%08X", ckpt.pendingRelocateCrc32)}, got 0x${String.format("%08X", slotCrc)})"
                            }
                        }
                    }

                    if (phaseErr == null) {
                        if (currentDiskBytes < ckpt.pendingTruncateTarget) {
                            phaseErr = "archive size $currentDiskBytes is already smaller than pendingTruncateTarget ${ckpt.pendingTruncateTarget}"
                        } else if (currentDiskBytes > ckpt.pendingTruncateTarget) {
                            val oldLen = currentDiskBytes
                            if (!truncateDescriptor(channel, fileDescriptor, ckpt.pendingTruncateTarget)) {
                                phaseErr = "failed to complete ftruncate(${ckpt.pendingTruncateTarget}) during RELOCATED_TRUNCATE_PENDING recovery"
                            } else {
                                faultInjector.onPoint(
                                    DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
                                    ckpt.pendingTruncateTarget,
                                    "recovery=RELOCATED_TRUNCATE_PENDING,c=$cDone,op=${ckpt.pendingOp}"
                                )
                                if (!syncArchiveDescriptor(channel, fileDescriptor)) {
                                    phaseErr = "failed to durably fsync archive after ftruncate(${ckpt.pendingTruncateTarget}) during RELOCATED_TRUNCATE_PENDING recovery"
                                } else {
                                    val freed = (oldLen - ckpt.pendingTruncateTarget).coerceAtLeast(0L)
                                    bytesReclaimed += freed
                                    currentDiskBytes = ckpt.pendingTruncateTarget
                                    faultInjector.onPhysicalTruncate(
                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                        oldLen,
                                        currentDiskBytes
                                    )
                                    faultInjector.onPoint(
                                        DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
                                        currentDiskBytes,
                                        "recovery=RELOCATED_TRUNCATE_PENDING,c=$cDone,op=${ckpt.pendingOp}"
                                    )
                                }
                            }
                        }
                    }

                    if (phaseErr == null) {
                        if (!validateRemainingChunksWithinArchive(cDone, currentDiskBytes)) {
                            phaseErr = "remaining compressed chunks do not fit within archive size $currentDiskBytes after RELOCATED_TRUNCATE_PENDING recovery"
                        } else {
                            val savedCommitted = ResumeJournalManager.saveDeflateTailSwapCheckpoint(
                                stateFilePath = stateFilePath,
                                checkpoint = buildDeflateCheckpoint(
                                    cDone,
                                    ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
                                    ResumeJournalManager.DeflateTailSwapOp.NONE,
                                    -1,
                                    -1,
                                    0,
                                    0L,
                                    -1L,
                                    true,
                                    currentDiskBytes,
                                    checkpointableInflater,
                                    ckpt.inflateCheckpointBytes
                                )
                            )
                            if (!savedCommitted) {
                                phaseErr = "failed to persist COMMITTED checkpoint after RELOCATED_TRUNCATE_PENDING recovery"
                            } else {
                                faultInjector.onPoint(
                                    DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
                                    currentDiskBytes,
                                    "recovery=RELOCATED_TRUNCATE_PENDING,c=$cDone"
                                )
                            }
                        }
                    }
                    phaseErr
                }

                ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED -> {
                    if (!ckpt.relocationCommittedForChunk) {
                        "inconsistent COMMITTED checkpoint (relocationCommittedForChunk=false) for chunk $cDone"
                    } else if (!validateRemainingChunksWithinArchive(cDone, currentDiskBytes)) {
                        "remaining compressed chunks do not fit within archive size $currentDiskBytes for COMMITTED chunk $cDone"
                    } else {
                        null
                    }
                }
            }
        } catch (fe: SimulatedFaultException) {
            try { recoveredSafTargetOutput?.close() } catch (_: Throwable) {}
            throw fe
        } catch (t: Throwable) {
            t.localizedMessage ?: t.message ?: t.javaClass.simpleName
        }

        if (recoveryFailureMsg != null) {
            try { recoveredSafTargetOutput?.close() } catch (_: Throwable) {}
            return TransactionalRecoveryReplayResult.Failed(
                "Unrecoverable mid-tail-swap interruption for '${entry.name}': $recoveryFailureMsg. [${buildMutatedStateDesc()}]"
            )
        }

        val startChunk = cDone + 1
        return TransactionalRecoveryReplayResult.Resumed(
            startChunk = startChunk,
            outputBytesWritten = ckpt.outputBytesWritten,
            updatedDiskBytes = currentDiskBytes,
            updatedBytesReclaimed = bytesReclaimed,
            recoveredSafTargetOutput = recoveredSafTargetOutput,
            logLine = "TRANSACTIONAL RECOVERY RESUMED: '${entry.name}' from chunk $startChunk/$totalChunks " +
                "(phase=${ckpt.txPhase}, op=${ckpt.pendingOp}, verifiedPrefix=${formatSize(ckpt.outputBytesWritten)}, " +
                "CRC 0x${String.format("%08X", ckpt.runningCrc32)}, archive=${formatSize(currentDiskBytes)})"
        )
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

    fun verifyArchive(
        archiveFile: File,
        level: com.biplov.kaihatch.model.VerificationLevel,
        password: String? = null,
        context: Context? = null
    ): Flow<com.biplov.kaihatch.model.ArchiveVerificationState> =
        ArchiveVerifierEngine.verifyArchive(
            archiveFile = archiveFile,
            level = level,
            password = password,
            context = context
        )

    fun verifySafUri(
        context: Context,
        uri: Uri,
        archiveName: String,
        level: com.biplov.kaihatch.model.VerificationLevel,
        password: String? = null
    ): Flow<com.biplov.kaihatch.model.ArchiveVerificationState> =
        ArchiveVerifierEngine.verifySafUri(
            context = context,
            uri = uri,
            archiveName = archiveName,
            level = level,
            password = password
        )

    fun captureSourceFingerprint(file: File): com.biplov.kaihatch.model.ArchiveSourceFingerprint? =
        ArchiveVerifierEngine.captureSourceFingerprint(file)

    fun captureSourceFingerprint(
        context: Context,
        uri: Uri
    ): com.biplov.kaihatch.model.ArchiveSourceFingerprint? =
        ArchiveVerifierEngine.captureSourceFingerprint(context, uri)

    fun hasSourceChangedSinceVerification(
        fingerprint: com.biplov.kaihatch.model.ArchiveSourceFingerprint,
        archiveFile: File? = null,
        context: Context? = null,
        safUri: Uri? = null
    ): Boolean = ArchiveVerifierEngine.hasSourceChangedSinceVerification(
        fingerprint = fingerprint,
        archiveFile = archiveFile,
        context = context,
        safUri = safUri
    )
}

