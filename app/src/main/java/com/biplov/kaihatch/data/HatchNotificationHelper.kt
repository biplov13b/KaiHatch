package com.biplov.kaihatch.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.biplov.kaihatch.MainActivity
import com.biplov.kaihatch.R

class HatchNotificationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == HatchNotificationHelper.ACTION_CANCEL_TASK) {
            HatchNotificationHelper.onCancelRequested?.invoke()
            HatchNotificationHelper.cancelProgressNotification(context)
        }
    }
}

/**
 * Foreground Service + Partial WakeLock that keeps KaiHatch in Android's top/foreground
 * cgroup scheduling bucket when the user switches apps or turns off the screen.
 * Prevents the OS from pinning decompression threads to efficiency cores or throttling
 * FUSE / blkio disk throughput down to 100–500 KB/s in the background.
 */
class HatchForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FOREGROUND) {
            releaseWakeLock()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "KaiHatch Engine Active"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Processing archive..."
        val notification = HatchNotificationHelper.buildProgressNotification(
            context = this,
            title = title,
            subtitle = subtitle,
            detailBigText = subtitle,
            progressFraction = 0f,
            speedText = "",
            indeterminate = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    HatchNotificationHelper.NOTIFICATION_PROGRESS_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(HatchNotificationHelper.NOTIFICATION_PROGRESS_ID, notification)
            }
        } catch (_: Throwable) {
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KaiHatch::ArchiveEngineWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 30-minute safety ceiling
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val ACTION_START_FOREGROUND = "com.biplov.kaihatch.ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.biplov.kaihatch.ACTION_STOP_FOREGROUND"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
    }
}

object HatchNotificationHelper {

    const val ACTION_CANCEL_TASK = "com.biplov.kaihatch.ACTION_CANCEL_TASK"

    private const val CHANNEL_LIVE_PROGRESS_ID = "kaihatch_live_progress_channel"
    private const val CHANNEL_COMPLETION_ID = "kaihatch_completion_channel"

    const val NOTIFICATION_PROGRESS_ID = 2001
    private const val NOTIFICATION_COMPLETION_ID = 2002

    @Volatile
    var onCancelRequested: (() -> Unit)? = null

    @Volatile
    private var lastUpdateTimestamp: Long = 0L

    @Volatile
    private var channelsCreated: Boolean = false

    @Volatile
    private var isForegroundServiceRunning: Boolean = false

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun ensureChannels(context: Context) {
        if (channelsCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val progressChannel = NotificationChannel(
                CHANNEL_LIVE_PROGRESS_ID,
                "Extraction & Compression Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Extraction and compression progress notifications with Live Updates"
                setShowBadge(true)
            }

            val completionChannel = NotificationChannel(
                CHANNEL_COMPLETION_ID,
                "Completed Archive Tasks",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when archive operations finish and reports total saved storage"
            }

            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(completionChannel)
        }
        channelsCreated = true
    }

    fun startForegroundExecution(context: Context, title: String, subtitle: String) {
        ensureChannels(context)
        if (isForegroundServiceRunning) return
        isForegroundServiceRunning = true
        try {
            val serviceIntent = Intent(context, HatchForegroundService::class.java).apply {
                action = HatchForegroundService.ACTION_START_FOREGROUND
                putExtra(HatchForegroundService.EXTRA_TITLE, title)
                putExtra(HatchForegroundService.EXTRA_SUBTITLE, subtitle)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Throwable) {
            try {
                context.startService(Intent(context, HatchForegroundService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    fun stopForegroundExecution(context: Context) {
        if (!isForegroundServiceRunning) return
        isForegroundServiceRunning = false
        try {
            context.stopService(Intent(context, HatchForegroundService::class.java))
        } catch (_: Throwable) {
        }
    }

    /**
     * Fast pre-check so callers can skip string formatting and coroutine dispatch when throttled.
     */
    fun shouldDispatchLiveUpdate(forceUpdate: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceUpdate && (now - lastUpdateTimestamp) < 600L) {
            return false
        }
        lastUpdateTimestamp = now
        return true
    }

    fun formatRemainingLine(remainingBytes: Long, bytesPerSecond: Long): String {
        val safeRemaining = remainingBytes.coerceAtLeast(0L)
        val sizeStr = StorageHelper.formatBytes(safeRemaining)
        if (bytesPerSecond <= 0L || safeRemaining <= 0L) {
            return "Remaining: $sizeStr"
        }
        val totalSeconds = (safeRemaining / bytesPerSecond).coerceAtLeast(1L)
        val etaStr = when {
            totalSeconds >= 3600L -> {
                val hours = totalSeconds / 3600L
                val mins = (totalSeconds % 3600L) / 60L
                "${hours}h ${mins}m"
            }
            totalSeconds >= 60L -> {
                val mins = totalSeconds / 60L
                val secs = totalSeconds % 60L
                "${mins}m ${secs}s"
            }
            else -> "${totalSeconds}s"
        }
        return "Remaining: $etaStr • $sizeStr"
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        subtitle: String,
        detailBigText: String,
        progressFraction: Float,
        speedText: String = "",
        indeterminate: Boolean = false
    ): Notification {
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clampedFraction = progressFraction.coerceIn(0f, 1f)
        val permille = (clampedFraction * 1000f).toInt()
        val percent = (clampedFraction * 100f).toInt()
        val shortChipText = if (indeterminate) "Prep" else "$percent%"

        val cleanFileLine = subtitle.trim()
        val cleanRemainingLine = detailBigText.trim()
        val twoLineBody = when {
            cleanFileLine.isNotEmpty() && cleanRemainingLine.isNotEmpty() && cleanFileLine != cleanRemainingLine ->
                "$cleanFileLine\n$cleanRemainingLine"
            cleanFileLine.isNotEmpty() -> cleanFileLine
            else -> cleanRemainingLine
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE_PROGRESS_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(twoLineBody)
            .setShortCriticalText(shortChipText)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF10B981.toInt())
            .setColorized(false)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, permille, indeterminate)
            .setContentIntent(openPendingIntent)

        if (speedText.isNotEmpty()) {
            builder.setSubText(speedText)
        }

        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun showLiveProgressNotification(
        context: Context,
        title: String,
        subtitle: String,
        detailBigText: String,
        progressFraction: Float,
        speedText: String = "",
        indeterminate: Boolean = false,
        forceUpdate: Boolean = false,
        alreadyThrottled: Boolean = false
    ) {
        if (!alreadyThrottled && !shouldDispatchLiveUpdate(forceUpdate)) {
            return
        }

        if (!isForegroundServiceRunning) {
            startForegroundExecution(context, title, subtitle)
        }

        if (!hasNotificationPermission(context)) return

        val notification = buildProgressNotification(
            context = context,
            title = title,
            subtitle = subtitle,
            detailBigText = detailBigText,
            progressFraction = progressFraction,
            speedText = speedText,
            indeterminate = indeterminate
        )

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_PROGRESS_ID, notification)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun showCompletionNotification(
        context: Context,
        title: String,
        message: String
    ) {
        cancelProgressNotification(context)
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETION_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF10B981.toInt())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_COMPLETION_ID, notification)
        } catch (_: Exception) {
        }
    }

    fun cancelProgressNotification(context: Context) {
        stopForegroundExecution(context)
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_PROGRESS_ID)
        } catch (_: Exception) {
        }
    }
}
