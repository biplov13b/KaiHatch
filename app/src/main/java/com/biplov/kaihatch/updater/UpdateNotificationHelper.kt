package com.biplov.kaihatch.updater

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.biplov.kaihatch.MainActivity
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.HatchNotificationHelper
import java.io.File

object UpdateNotificationHelper {

    const val CHANNEL_UPDATES_ID = "kaihatch_app_updates"

    const val NOTIFICATION_UPDATE_AVAILABLE_ID = 4101
    const val NOTIFICATION_UPDATE_DOWNLOADING_ID = 4102
    const val NOTIFICATION_UPDATE_READY_INSTALL_ID = 4103

    const val ACTION_DOWNLOAD_UPDATE = "com.biplov.kaihatch.ACTION_DOWNLOAD_UPDATE"
    const val ACTION_CANCEL_UPDATE_DOWNLOAD = "com.biplov.kaihatch.ACTION_CANCEL_UPDATE_DOWNLOAD"
    const val ACTION_OPEN_UPDATE_DIALOG = "com.biplov.kaihatch.ACTION_OPEN_UPDATE_DIALOG"
    const val ACTION_INSTALL_CACHED_UPDATE = "com.biplov.kaihatch.ACTION_INSTALL_CACHED_UPDATE"

    const val EXTRA_RELEASE_JSON = "extra_release_json"
    const val EXTRA_TAG_NAME = "extra_tag_name"
    const val EXTRA_APK_PATH = "extra_apk_path"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_UPDATES_ID,
                context.getString(R.string.update_notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.update_notif_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun showUpdateAvailableNotification(
        context: Context,
        release: GitHubReleaseInfo
    ): Boolean {
        if (!HatchNotificationHelper.hasNotificationPermission(context)) return false
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_UPDATE_DIALOG
            putExtra(EXTRA_TAG_NAME, release.tagName)
            putExtra(EXTRA_RELEASE_JSON, release.toCompactJsonString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_UPDATE_AVAILABLE_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val downloadIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_UPDATE
            putExtra(EXTRA_RELEASE_JSON, release.toCompactJsonString())
            putExtra(EXTRA_TAG_NAME, release.tagName)
        }
        val downloadPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_UPDATE_AVAILABLE_ID + 10,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.update_notif_available_title, release.versionName)
        val body = context.getString(R.string.update_notif_available_body)

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                0,
                context.getString(R.string.update_action_download),
                downloadPendingIntent
            )
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_AVAILABLE_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun showDownloadProgressNotification(
        context: Context,
        release: GitHubReleaseInfo,
        progressPercent: Int,
        indeterminate: Boolean
    ): Boolean {
        if (!HatchNotificationHelper.hasNotificationPermission(context)) return false
        ensureChannel(context)

        val cancelIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_CANCEL_UPDATE_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_UPDATE_DOWNLOADING_ID + 10,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_UPDATE_DIALOG
            putExtra(EXTRA_TAG_NAME, release.tagName)
            putExtra(EXTRA_RELEASE_JSON, release.toCompactJsonString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_UPDATE_DOWNLOADING_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.update_notif_downloading_title, release.versionName)
        val clamped = progressPercent.coerceIn(0, 100)
        val text = if (indeterminate) {
            context.getString(R.string.update_downloading_preparing)
        } else {
            context.getString(R.string.update_notif_downloading_percent, clamped)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, clamped, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(
                0,
                context.getString(R.string.action_cancel),
                cancelPendingIntent
            )
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_DOWNLOADING_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun showReadyToInstallNotification(
        context: Context,
        release: GitHubReleaseInfo,
        apkFile: File
    ): Boolean {
        cancelProgressNotification(context)
        cancelAvailableNotification(context)
        if (!HatchNotificationHelper.hasNotificationPermission(context)) return false
        ensureChannel(context)

        val installIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_INSTALL_CACHED_UPDATE
            putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
            putExtra(EXTRA_TAG_NAME, release.tagName)
            putExtra(EXTRA_RELEASE_JSON, release.toCompactJsonString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val installPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_UPDATE_READY_INSTALL_ID,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.update_notif_ready_title, release.versionName)
        val body = context.getString(R.string.update_notif_ready_body)

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(installPendingIntent)
            .addAction(
                0,
                context.getString(R.string.update_action_install),
                installPendingIntent
            )
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_READY_INSTALL_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun showDownloadFailedNotification(
        context: Context,
        release: GitHubReleaseInfo,
        detail: String
    ): Boolean {
        cancelProgressNotification(context)
        if (!HatchNotificationHelper.hasNotificationPermission(context)) return false
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_UPDATE_DIALOG
            putExtra(EXTRA_TAG_NAME, release.tagName)
            putExtra(EXTRA_RELEASE_JSON, release.toCompactJsonString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_UPDATE_READY_INSTALL_ID + 1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.update_notif_failed_title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_READY_INSTALL_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelAvailableNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_UPDATE_AVAILABLE_ID)
    }

    fun cancelProgressNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_UPDATE_DOWNLOADING_ID)
    }

    fun cancelReadyToInstallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_UPDATE_READY_INSTALL_ID)
    }
}
