package com.example.data

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
import com.example.engine.KaiHatchEngine
import com.example.model.CompressionProgress
import com.example.model.ExtractionProgress
import com.example.model.ExtractionStatus
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

    fun launchExtractionJob(
        context: Context,
        title: String,
        subtitle: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        activeExtractJob?.cancel()
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
        val title = inputData.getString(ArchiveJobRunner.KEY_TASK_TITLE) ?: "KaiHatch Archive Engine"
        val subtitle = inputData.getString(ArchiveJobRunner.KEY_TASK_SUBTITLE) ?: "Processing archive..."
        val notification = HatchNotificationHelper.buildProgressNotification(
            context = applicationContext,
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
            // An in-memory extraction or compression job is currently running; hold the foreground lock until it finishes
            activeGate.await()
            return Result.success()
        }

        // Process was restarted by the OS after being killed mid-extraction:
        // Check for any crash-safe .hatchstate journal and auto-resume it!
        val pendingJournals = ResumeJournalManager.loadPendingStates(applicationContext)
        val latestJournal = pendingJournals.firstOrNull() ?: return Result.success()

        return try {
            KaiHatchEngine.resumeInterruptedExtraction(
                context = applicationContext,
                resumeState = latestJournal,
                password = latestJournal.password
            ).collect { progress ->
                ArchiveJobRunner.updateExtractionProgress(progress)
                if (progress.status == ExtractionStatus.EXTRACTING || progress.status == ExtractionStatus.TRUNCATING) {
                    val countLabel = if (progress.totalEntries == 1) "1 file" else "${progress.totalEntries} files"
                    val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                    val speedText = if (progress.bytesPerSecond > 0L) {
                        "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s"
                    } else ""
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = applicationContext,
                        title = "Extracting $countLabel",
                        subtitle = progress.currentFileName.substringAfterLast('/').ifEmpty { latestJournal.archiveName },
                        detailBigText = HatchNotificationHelper.formatRemainingLine(remainingBytes, progress.bytesPerSecond),
                        progressFraction = progress.overallProgressFraction,
                        speedText = speedText
                    )
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
