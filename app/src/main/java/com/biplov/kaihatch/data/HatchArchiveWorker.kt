package com.biplov.kaihatch.data

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.biplov.kaihatch.R
import com.biplov.kaihatch.engine.ExtractionPauseController
import com.biplov.kaihatch.engine.KaiHatchEngine
import com.biplov.kaihatch.model.CompressionProgress
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-surviving in-memory job bus + WorkManager coordinator.
 *
 * Avoids WorkManager's 10KB SQLite `setProgress()` bottleneck by streaming all high-frequency
 * UI progress updates through lock-free in-memory [StateFlow]s while using [HatchArchiveWorker]
 * exclusively to hold the OS Foreground Service (`dataSync`) lifecycle lock and recover from
 * process termination via `.hatchstate` journals.
 */
object ArchiveJobRunner {

    const val UNIQUE_WORK_NAME = "kaihatch_active_archive_work"
    const val KEY_TASK_TITLE = "task_title"
    const val KEY_TASK_SUBTITLE = "task_subtitle"

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _extractionProgress = MutableStateFlow(ExtractionProgress())
    val extractionProgress: StateFlow<ExtractionProgress> = _extractionProgress.asStateFlow()

    private val _compressionProgress = MutableStateFlow(CompressionProgress())
    val compressionProgress: StateFlow<CompressionProgress> = _compressionProgress.asStateFlow()

    @Volatile
    private var activeExtractJob: Job? = null

    @Volatile
    var activePauseController: ExtractionPauseController? = null
        private set

    @Volatile
    private var activeCompressJob: Job? = null

    @Volatile
    internal var workerCompletionGate: CompletableDeferred<Unit>? = null

    fun updateExtractionProgress(progress: ExtractionProgress) {
        _extractionProgress.value = progress
    }

    fun updateCompressionProgress(progress: CompressionProgress) {
        _compressionProgress.value = progress
    }

    fun isExtractionActive(): Boolean = activeExtractJob?.isActive == true

    fun isCompressionActive(): Boolean = activeCompressJob?.isActive == true

    fun pauseExtraction(): Boolean {
        if (!isExtractionActive()) return false
        val controller = activePauseController ?: return false
        return controller.requestPause()
    }

    fun resumeExtraction(): Boolean {
        if (!isExtractionActive()) return false
        val controller = activePauseController ?: return false
        return controller.resume()
    }

    /**
     * Signals the active [ExtractionPauseController] to stop at the next safe boundary and
     * finalize/restore the remaining archive in-place without killing [activeExtractJob] mid-write.
     */
    fun requestCooperativeStop(): Boolean {
        if (!isExtractionActive()) return false
        val controller = activePauseController ?: return false
        controller.cancel()
        return true
    }

    fun launchExtractionJob(
        context: Context,
        title: String,
        subtitle: String,
        block: suspend CoroutineScope.(ExtractionPauseController) -> Unit
    ): Job {
        activePauseController?.cancel()
        activeExtractJob?.cancel()
        val pauseController = ExtractionPauseController()
        activePauseController = pauseController
        val gate = CompletableDeferred<Unit>()
        workerCompletionGate = gate

        enqueueForegroundWorker(context.applicationContext, title, subtitle)
        HatchNotificationHelper.startForegroundExecution(context.applicationContext, title, subtitle)

        val job = appScope.launch {
            try {
                block(pauseController)
            } finally {
                if (activePauseController === pauseController) {
                    activePauseController = null
                }
                gate.complete(Unit)
            }
        }
        activeExtractJob = job
        return job
    }

    fun launchCompressionJob(
        context: Context,
        title: String,
        subtitle: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        activeCompressJob?.cancel()
        val gate = CompletableDeferred<Unit>()
        workerCompletionGate = gate

        enqueueForegroundWorker(context.applicationContext, title, subtitle)
        HatchNotificationHelper.startForegroundExecution(context.applicationContext, title, subtitle)

        val job = appScope.launch {
            try {
                block()
            } finally {
                gate.complete(Unit)
            }
        }
        activeCompressJob = job
        return job
    }

    fun cancelExtraction(context: Context) {
        activePauseController?.cancel()
        activePauseController = null
        activeExtractJob?.cancel()
        activeExtractJob = null
        workerCompletionGate?.complete(Unit)
        stopWorkerAndService(context.applicationContext)
    }

    fun cancelCompression(context: Context) {
        activeCompressJob?.cancel()
        activeCompressJob = null
        workerCompletionGate?.complete(Unit)
        stopWorkerAndService(context.applicationContext)
    }

    private fun enqueueForegroundWorker(context: Context, title: String, subtitle: String) {
        try {
            val request = OneTimeWorkRequestBuilder<HatchArchiveWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TASK_TITLE to title,
                        KEY_TASK_SUBTITLE to subtitle
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (_: Throwable) {
            // Safe fallback in unit test / Robolectric environments where WorkManager isn't initialized
        }
    }

    private fun stopWorkerAndService(context: Context) {
        HatchNotificationHelper.cancelProgressNotification(context)
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        } catch (_: Throwable) {
        }
    }
}

/**
 * Foreground-promoted [CoroutineWorker] that keeps the process alive during multi-gigabyte
 * archive unpacking/packing and automatically resumes `.hatchstate` journals if the OS restarts
 * the process after low-memory termination.
 */
class HatchArchiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val locContext = LanguageHelper.wrapContext(applicationContext)
        val title = inputData.getString(ArchiveJobRunner.KEY_TASK_TITLE)
            ?: locContext.getString(R.string.notif_worker_default_title)
        val subtitle = inputData.getString(ArchiveJobRunner.KEY_TASK_SUBTITLE)
            ?: locContext.getString(R.string.notif_default_subtitle)
        val notification = HatchNotificationHelper.buildProgressNotification(
            context = locContext,
            title = title,
            subtitle = subtitle,
            detailBigText = subtitle,
            progressFraction = 0f,
            speedText = "",
            indeterminate = true
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                HatchNotificationHelper.NOTIFICATION_PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                HatchNotificationHelper.NOTIFICATION_PROGRESS_ID,
                notification
            )
        }
    }

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (_: Throwable) {
        }

        val activeGate = ArchiveJobRunner.workerCompletionGate
        if (activeGate != null) {
            // An in-memory extraction or compression job is currently running (or paused); hold the foreground lock until it finishes
            activeGate.await()
            return Result.success()
        }

        // Process was restarted by the OS after being killed mid-extraction:
        // Never automatically restart a destructive Hatch Mode operation in the background if the
        // source archive may already have been partially truncated or requires user confirmation/password.
        // Instead, preserve the crash-safe .hatchstate journal on disk so HatchViewModel surfaces the
        // explicit recovery banner to the user on next launch.
        val pendingJournals = ResumeJournalManager.loadPendingStates(applicationContext)
        val latestJournal = pendingJournals.firstOrNull() ?: return Result.success()

        val eligibility = ResumeJournalManager.assessResumeEligibility(latestJournal, applicationContext)
        val archivePartiallyTruncated = latestJournal.bytesReclaimedSoFar > 0L ||
                (latestJournal.originalArchiveBytes > 0L && latestJournal.currentArchiveBytes < latestJournal.originalArchiveBytes)
        val requiresPassword = latestJournal.remainingEntries.any { it.isEncrypted } && latestJournal.password.isNullOrEmpty()

        val hasPendingRestoration = latestJournal.hasPendingRestoration ||
                ResumeJournalManager.hasPendingRestorationCheckpoint(latestJournal.stateFilePath)
        val hasActiveTailSwapRecovery =
                ResumeJournalManager.loadTailSwapGuard(latestJournal.stateFilePath) != null ||
                ResumeJournalManager.loadDeflateTailSwapCheckpoint(latestJournal.stateFilePath) != null

        if (eligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable ||
            archivePartiallyTruncated ||
            hasPendingRestoration ||
            hasActiveTailSwapRecovery ||
            requiresPassword
        ) {
            return Result.success()
        }

        val pauseController = ExtractionPauseController()
        ArchiveJobRunner.updateExtractionProgress(
            ExtractionProgress(
                archiveName = latestJournal.archiveName,
                isHatchMode = true,
                status = ExtractionStatus.EXTRACTING
            )
        )

        return try {
            var encounteredError = false
            KaiHatchEngine.resumeInterruptedExtraction(
                context = applicationContext,
                resumeState = latestJournal,
                password = latestJournal.password,
                pauseController = pauseController
            ).collect { progress ->
                ArchiveJobRunner.updateExtractionProgress(progress)
                if (progress.status == ExtractionStatus.ERROR) {
                    encounteredError = true
                }
                val locContext = LanguageHelper.wrapContext(applicationContext)
                when (progress.status) {
                    ExtractionStatus.EXTRACTING, ExtractionStatus.TRUNCATING -> {
                        val countLabel = if (progress.totalEntries == 1) {
                            locContext.getString(R.string.notif_count_one_file)
                        } else {
                            locContext.getString(R.string.notif_count_many_files, progress.totalEntries)
                        }
                        val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                        val speedText = if (progress.bytesPerSecond > 0L) {
                            "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s"
                        } else ""
                        HatchNotificationHelper.showLiveProgressNotification(
                            context = locContext,
                            title = locContext.getString(R.string.notif_extracting_title, countLabel),
                            subtitle = progress.currentFileName.substringAfterLast('/').ifEmpty { latestJournal.archiveName },
                            detailBigText = HatchNotificationHelper.formatRemainingLine(
                                remainingBytes = remainingBytes,
                                bytesPerSecond = progress.bytesPerSecond,
                                context = locContext
                            ),
                            progressFraction = progress.overallProgressFraction,
                            speedText = speedText
                        )
                    }
                    ExtractionStatus.PAUSING -> {
                        val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                        HatchNotificationHelper.showLiveProgressNotification(
                            context = locContext,
                            title = locContext.getString(R.string.notif_pausing_title),
                            subtitle = progress.currentFileName.substringAfterLast('/').ifEmpty { latestJournal.archiveName },
                            detailBigText = locContext.getString(
                                R.string.notif_pausing_detail,
                                StorageHelper.formatBytes(remainingBytes)
                            ),
                            progressFraction = progress.overallProgressFraction,
                            speedText = locContext.getString(R.string.extract_hud_prefix_pausing),
                            forceUpdate = true
                        )
                    }
                    ExtractionStatus.PAUSED -> {
                        val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                        HatchNotificationHelper.showLiveProgressNotification(
                            context = locContext,
                            title = locContext.getString(R.string.notif_paused_title),
                            subtitle = progress.currentFileName.substringAfterLast('/').ifEmpty { latestJournal.archiveName },
                            detailBigText = locContext.getString(
                                R.string.notif_paused_detail,
                                (progress.overallProgressFraction * 100f).toInt(),
                                StorageHelper.formatBytes(remainingBytes)
                            ),
                            progressFraction = progress.overallProgressFraction,
                            speedText = locContext.getString(R.string.extract_hud_prefix_paused),
                            forceUpdate = true
                        )
                    }
                    ExtractionStatus.COMPLETED -> {
                        val doneCountText = if (progress.totalEntries == 1) {
                            locContext.getString(R.string.notif_count_one_file)
                        } else {
                            locContext.getString(R.string.notif_count_many_files, progress.totalEntries)
                        }
                        val summaryMsg = "${latestJournal.archiveName} • ${StorageHelper.formatBytes(progress.bytesExtracted)}"
                        HatchNotificationHelper.showCompletionNotification(
                            context = locContext,
                            title = locContext.getString(R.string.notif_extracted_title, doneCountText),
                            message = summaryMsg
                        )
                    }
                    ExtractionStatus.ERROR -> {
                        val errSummary = progress.errorMessage?.takeIf { it.isNotBlank() } ?: latestJournal.archiveName
                        HatchNotificationHelper.showErrorNotification(
                            context = locContext,
                            title = locContext.getString(R.string.extract_error_title),
                            message = errSummary
                        )
                    }
                    ExtractionStatus.CANCELLED -> {
                        HatchNotificationHelper.cancelProgressNotification(locContext)
                        HatchNotificationHelper.cancelCompletionNotification(locContext)
                    }
                    else -> {}
                }
            }
            if (encounteredError) Result.failure() else Result.success()
        } catch (t: Throwable) {
            val locContext = LanguageHelper.wrapContext(applicationContext)
            HatchNotificationHelper.showErrorNotification(
                context = locContext,
                title = locContext.getString(R.string.extract_error_title),
                message = t.localizedMessage ?: t.javaClass.simpleName
            )
            // Never loop with Result.retry() on an interrupted Hatch Mode archive
            Result.failure()
        }
    }
}
