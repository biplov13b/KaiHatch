package com.biplov.kaihatch.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.biplov.kaihatch.data.HatchNotificationHelper
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object UpdateWorkScheduler {

    const val PERIODIC_CHECK_WORK_NAME = "kaihatch_periodic_update_check"
    const val BACKGROUND_DOWNLOAD_WORK_NAME = "kaihatch_background_update_download"

    fun schedulePeriodicUpdateChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            repeatInterval = 12L,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 2L,
            flexTimeIntervalUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun buildDownloadWorkInputData(release: GitHubReleaseInfo): Data {
        return Data.Builder()
            .putString(UpdateDownloadWorker.KEY_RELEASE_JSON, release.toCompactJsonString())
            .build()
    }

    fun enqueueBackgroundDownload(context: Context, release: GitHubReleaseInfo) {
        val prefs = AppUpdateManager.getInstance(context).preferences
        if (release.releaseNotes.isNotBlank() || prefs.lastKnownRelease?.tagName != release.tagName) {
            prefs.lastKnownRelease = release
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = buildDownloadWorkInputData(release)

        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BACKGROUND_DOWNLOAD_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelBackgroundDownload(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(BACKGROUND_DOWNLOAD_WORK_NAME)
        UpdateNotificationHelper.cancelProgressNotification(context)
    }
}

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val manager = AppUpdateManager.getInstance(applicationContext)
        manager.performLaunchCleanup()

        // Respect notification permission: skip background GitHub polling if user hasn't enabled notifications
        if (!HatchNotificationHelper.hasNotificationPermission(applicationContext)) {
            return Result.success()
        }

        return when (val result = manager.checkForUpdates(isManual = false)) {
            is UpdateCheckResult.UpdateAvailable -> {
                val release = result.release
                if (manager.shouldPostBackgroundNotificationFor(release.tagName)) {
                    val posted = if (result.cachedApkFile != null) {
                        UpdateNotificationHelper.showReadyToInstallNotification(
                            context = applicationContext,
                            release = release,
                            apkFile = result.cachedApkFile
                        )
                    } else {
                        UpdateNotificationHelper.showUpdateAvailableNotification(
                            context = applicationContext,
                            release = release
                        )
                    }
                    if (posted) {
                        manager.markVersionNotified(release.tagName)
                    }
                }
                Result.success()
            }
            is UpdateCheckResult.UpToDate,
            is UpdateCheckResult.Skipped,
            is UpdateCheckResult.Throttled -> Result.success()
            is UpdateCheckResult.Offline -> Result.retry()
            is UpdateCheckResult.Error -> Result.success()
        }
    }
}

class UpdateDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val manager = AppUpdateManager.getInstance(applicationContext)
        val cachedRelease = manager.preferences.lastKnownRelease
        val parsed = GitHubReleaseInfo.fromJsonString(inputData.getString(KEY_RELEASE_JSON))
        val release = when {
            parsed != null && parsed.releaseNotes.isBlank() && cachedRelease?.tagName == parsed.tagName ->
                parsed.copy(releaseNotes = cachedRelease.releaseNotes)
            parsed != null -> parsed
            else -> cachedRelease
        } ?: return Result.failure()

        UpdateNotificationHelper.cancelAvailableNotification(applicationContext)

        var lastPercent = -1
        UpdateNotificationHelper.showDownloadProgressNotification(
            context = applicationContext,
            release = release,
            progressPercent = 0,
            indeterminate = true
        )

        return try {
            val outcome = manager.downloadOrReuseUpdate(
                release = release,
                onProgress = { downloaded, total ->
                    if (!isStopped) {
                        val indeterminate = total <= 0L
                        val percent = if (indeterminate) 0 else ((downloaded.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                        if (indeterminate && lastPercent == -1 || percent != lastPercent) {
                            lastPercent = percent
                            UpdateNotificationHelper.showDownloadProgressNotification(
                                context = applicationContext,
                                release = release,
                                progressPercent = percent,
                                indeterminate = indeterminate
                            )
                        }
                    }
                },
                isCancelled = { isStopped }
            )

            when (outcome) {
                is UpdateDownloadOutcome.ReadyToInstall -> {
                    // Never launch installer directly from background: post an Install notification
                    // so installation is initiated strictly by user interaction.
                    UpdateNotificationHelper.showReadyToInstallNotification(
                        context = applicationContext,
                        release = outcome.release,
                        apkFile = outcome.apkFile
                    )
                    Result.success()
                }
                is UpdateDownloadOutcome.Cancelled -> {
                    UpdateNotificationHelper.cancelProgressNotification(applicationContext)
                    Result.success()
                }
                is UpdateDownloadOutcome.Failed -> {
                    UpdateNotificationHelper.showDownloadFailedNotification(
                        context = applicationContext,
                        release = release,
                        detail = outcome.message
                    )
                    Result.failure()
                }
            }
        } catch (_: CancellationException) {
            UpdateNotificationHelper.cancelProgressNotification(applicationContext)
            Result.success()
        }
    }

    companion object {
        const val KEY_RELEASE_JSON = "release_json"
    }
}

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            UpdateNotificationHelper.ACTION_DOWNLOAD_UPDATE -> {
                val release = GitHubReleaseInfo.fromJsonString(
                    intent.getStringExtra(UpdateNotificationHelper.EXTRA_RELEASE_JSON)
                ) ?: AppUpdateManager.getInstance(context).preferences.lastKnownRelease
                UpdateNotificationHelper.cancelAvailableNotification(context)
                if (release != null) {
                    UpdateWorkScheduler.enqueueBackgroundDownload(context, release)
                }
            }
            UpdateNotificationHelper.ACTION_CANCEL_UPDATE_DOWNLOAD -> {
                UpdateWorkScheduler.cancelBackgroundDownload(context)
            }
        }
    }
}
