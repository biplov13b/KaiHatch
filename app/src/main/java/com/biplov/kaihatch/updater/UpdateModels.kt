package com.biplov.kaihatch.updater

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.io.File

@Immutable
data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val expectedSha256: String? = null,
    val checksumAssetUrl: String? = null,
    val isDraft: Boolean = false,
    val isPrerelease: Boolean = false
) {
    fun toJsonString(): String = toJsonObject(includeReleaseNotes = true).toString()

    /**
     * Bounded compact JSON representation omitting unbounded Markdown release notes,
     * safe for WorkManager Data (10 KB limit) and notification PendingIntent extras.
     */
    fun toCompactJsonString(): String = toJsonObject(includeReleaseNotes = false).toString()

    private fun toJsonObject(includeReleaseNotes: Boolean): JSONObject = JSONObject().apply {
        put("tagName", tagName)
        put("versionName", versionName)
        put("releaseName", if (includeReleaseNotes) releaseName else releaseName.take(MAX_COMPACT_NAME_CHARS))
        if (includeReleaseNotes) {
            put("releaseNotes", releaseNotes)
        }
        put("htmlUrl", htmlUrl)
        put("publishedAt", publishedAt)
        put("apkAssetName", apkAssetName)
        put("apkDownloadUrl", apkDownloadUrl)
        put("apkSizeBytes", apkSizeBytes)
        if (!expectedSha256.isNullOrBlank()) put("expectedSha256", expectedSha256)
        if (!checksumAssetUrl.isNullOrBlank()) put("checksumAssetUrl", checksumAssetUrl)
        put("isDraft", isDraft)
        put("isPrerelease", isPrerelease)
    }

    companion object {
        private const val MAX_COMPACT_NAME_CHARS = 200
        fun fromJsonString(raw: String?): GitHubReleaseInfo? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val tag = obj.optString("tagName").trim()
                val downloadUrl = obj.optString("apkDownloadUrl").trim()
                if (tag.isEmpty() || downloadUrl.isEmpty()) return null
                GitHubReleaseInfo(
                    tagName = tag,
                    versionName = obj.optString("versionName", tag.removePrefix("v").removePrefix("V")),
                    releaseName = obj.optString("releaseName", tag),
                    releaseNotes = obj.optString("releaseNotes", ""),
                    htmlUrl = obj.optString("htmlUrl", ""),
                    publishedAt = obj.optString("publishedAt", ""),
                    apkAssetName = obj.optString("apkAssetName", "KaiHatch.apk"),
                    apkDownloadUrl = downloadUrl,
                    apkSizeBytes = obj.optLong("apkSizeBytes", -1L),
                    expectedSha256 = obj.optString("expectedSha256").takeIf { it.isNotBlank() },
                    checksumAssetUrl = obj.optString("checksumAssetUrl").takeIf { it.isNotBlank() },
                    isDraft = obj.optBoolean("isDraft", false),
                    isPrerelease = obj.optBoolean("isPrerelease", false)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class CachedApkMetadata(
    val fileName: String,
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
    val sizeBytes: Long
)

enum class ApkValidationFailureReason {
    FILE_MISSING,
    SHA256_MISSING,
    SHA256_MISMATCH,
    INVALID_ARCHIVE,
    PACKAGE_ID_MISMATCH,
    VERSION_NOT_NEWER,
    SIGNING_MISMATCH,
    UNTRUSTED_DOWNLOAD_URL
}

sealed class ApkValidationResult {
    data class Valid(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val sha256: String,
        val signingDigests: Set<String>
    ) : ApkValidationResult()

    data class Invalid(
        val reason: ApkValidationFailureReason,
        val detail: String
    ) : ApkValidationResult()
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val release: GitHubReleaseInfo,
        val cachedApkFile: File?
    ) : UpdateCheckResult()

    data class UpToDate(val latestVersionName: String? = null) : UpdateCheckResult()
    data class Skipped(val release: GitHubReleaseInfo) : UpdateCheckResult()
    data object Offline : UpdateCheckResult()
    data object Throttled : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

@Immutable
sealed class UpdateDialogState {
    data object Hidden : UpdateDialogState()

    data class Available(
        val release: GitHubReleaseInfo,
        val hasCachedApk: Boolean
    ) : UpdateDialogState()

    data class Downloading(
        val release: GitHubReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : UpdateDialogState()

    data class PermissionRequired(
        val release: GitHubReleaseInfo,
        val apkFile: File
    ) : UpdateDialogState()

    data class Error(
        val release: GitHubReleaseInfo?,
        val message: String
    ) : UpdateDialogState()
}

sealed class UpdateUiEvent {
    data class ShowToast(val message: String) : UpdateUiEvent()
    data class LaunchInstaller(val apkFile: File, val release: GitHubReleaseInfo) : UpdateUiEvent()
    data class RequestInstallPermission(val apkFile: File, val release: GitHubReleaseInfo) : UpdateUiEvent()
}
