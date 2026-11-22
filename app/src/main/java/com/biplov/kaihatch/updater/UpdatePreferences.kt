package com.biplov.kaihatch.updater

import android.content.Context
import android.content.SharedPreferences

class UpdatePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var skippedVersionTag: String?
        get() = prefs.getString(KEY_SKIPPED_TAG, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_SKIPPED_TAG) else putString(KEY_SKIPPED_TAG, value)
            }.apply()
        }

    var lastNotifiedVersionTag: String?
        get() = prefs.getString(KEY_LAST_NOTIFIED_TAG, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_NOTIFIED_TAG) else putString(KEY_LAST_NOTIFIED_TAG, value)
            }.apply()
        }

    var lastCheckEpochMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK_EPOCH_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK_EPOCH_MS, value).apply()
        }

    var cachedReleaseEtag: String?
        get() = prefs.getString(KEY_CACHED_ETAG, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_CACHED_ETAG) else putString(KEY_CACHED_ETAG, value)
            }.apply()
        }

    var cachedReleaseJson: String?
        get() = prefs.getString(KEY_CACHED_RELEASE_JSON, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_CACHED_RELEASE_JSON) else putString(KEY_CACHED_RELEASE_JSON, value)
            }.apply()
        }

    var lastKnownRelease: GitHubReleaseInfo?
        get() = GitHubReleaseInfo.fromJsonString(prefs.getString(KEY_LAST_KNOWN_RELEASE, null))
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_KNOWN_RELEASE) else putString(KEY_LAST_KNOWN_RELEASE, value.toJsonString())
            }.apply()
        }

    fun getCachedApkMetadata(): CachedApkMetadata? {
        val fileName = prefs.getString(KEY_CACHED_APK_FILE_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val tagName = prefs.getString(KEY_CACHED_APK_TAG, null)?.takeIf { it.isNotBlank() } ?: return null
        val versionName = prefs.getString(KEY_CACHED_APK_VERSION_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = prefs.getLong(KEY_CACHED_APK_VERSION_CODE, -1L)
        val sha256 = prefs.getString(KEY_CACHED_APK_SHA256, null)?.takeIf { it.isNotBlank() } ?: return null
        val sizeBytes = prefs.getLong(KEY_CACHED_APK_SIZE_BYTES, 0L)
        if (versionCode <= 0L) return null
        return CachedApkMetadata(
            fileName = fileName,
            tagName = tagName,
            versionName = versionName,
            versionCode = versionCode,
            sha256 = sha256,
            sizeBytes = sizeBytes
        )
    }

    fun saveCachedApkMetadata(metadata: CachedApkMetadata) {
        prefs.edit()
            .putString(KEY_CACHED_APK_FILE_NAME, metadata.fileName)
            .putString(KEY_CACHED_APK_TAG, metadata.tagName)
            .putString(KEY_CACHED_APK_VERSION_NAME, metadata.versionName)
            .putLong(KEY_CACHED_APK_VERSION_CODE, metadata.versionCode)
            .putString(KEY_CACHED_APK_SHA256, metadata.sha256)
            .putLong(KEY_CACHED_APK_SIZE_BYTES, metadata.sizeBytes)
            .apply()
    }

    fun clearCachedApkMetadata() {
        prefs.edit()
            .remove(KEY_CACHED_APK_FILE_NAME)
            .remove(KEY_CACHED_APK_TAG)
            .remove(KEY_CACHED_APK_VERSION_NAME)
            .remove(KEY_CACHED_APK_VERSION_CODE)
            .remove(KEY_CACHED_APK_SHA256)
            .remove(KEY_CACHED_APK_SIZE_BYTES)
            .apply()
    }

    fun clearAllForTesting() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "kaihatch_updater_prefs"
        private const val KEY_SKIPPED_TAG = "skipped_version_tag"
        private const val KEY_LAST_NOTIFIED_TAG = "last_notified_version_tag"
        private const val KEY_LAST_CHECK_EPOCH_MS = "last_check_epoch_ms"
        private const val KEY_CACHED_ETAG = "cached_release_etag"
        private const val KEY_CACHED_RELEASE_JSON = "cached_release_json"
        private const val KEY_LAST_KNOWN_RELEASE = "last_known_release"
        private const val KEY_CACHED_APK_FILE_NAME = "cached_apk_file_name"
        private const val KEY_CACHED_APK_TAG = "cached_apk_tag"
        private const val KEY_CACHED_APK_VERSION_NAME = "cached_apk_version_name"
        private const val KEY_CACHED_APK_VERSION_CODE = "cached_apk_version_code"
        private const val KEY_CACHED_APK_SHA256 = "cached_apk_sha256"
        private const val KEY_CACHED_APK_SIZE_BYTES = "cached_apk_size_bytes"
    }
}
