package com.biplov.kaihatch.updater

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class UpdateCacheManager {

    fun getUpdatesDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, UPDATES_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getApkFileForTag(context: Context, tagName: String): File {
        val updatesDir = getUpdatesDir(context)
        val safeName = buildSafeApkFileName(tagName)
        return resolveContainedFile(updatesDir, safeName)
    }

    fun getTempFileForTag(context: Context, tagName: String): File {
        val updatesDir = getUpdatesDir(context)
        val safeName = "${buildSafeApkFileName(tagName)}$PART_SUFFIX"
        return resolveContainedFile(updatesDir, safeName)
    }

    fun markTempFileActive(fileName: String) {
        activeTempFileCounts.compute(fileName) { _, existing ->
            (existing ?: AtomicInteger(0)).apply { incrementAndGet() }
        }
    }

    fun unmarkTempFileActive(fileName: String) {
        activeTempFileCounts.computeIfPresent(fileName) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    fun isTempFileActive(fileName: String): Boolean {
        return (activeTempFileCounts[fileName]?.get() ?: 0) > 0
    }

    fun cleanupTempFiles(context: Context) {
        val updatesDir = getUpdatesDir(context)
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile &&
                (file.name.endsWith(PART_SUFFIX) || file.name.endsWith(TMP_SUFFIX)) &&
                !isTempFileActive(file.name)
            ) {
                file.delete()
            }
        }
    }

    fun cleanupSupersededApks(context: Context, keepFileName: String) {
        val updatesDir = getUpdatesDir(context)
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != keepFileName && file.name.endsWith(".apk")) {
                file.delete()
            }
        }
    }

    /**
     * Runs on app launch:
     * 1. Removes leftover partial/temp downloads.
     * 2. Deletes obsolete cached APKs once the installed app has been updated to >= cached version.
     * 3. Deletes corrupted or invalid cached APKs safely.
     * 4. Clears skipped version state if the installed app is now at or beyond that version.
     */
    fun cleanupObsoleteAndCorruptedOnLaunch(
        context: Context,
        prefs: UpdatePreferences,
        verifier: UpdateApkVerifier,
        installedPackageName: String,
        installedVersionCode: Long,
        installedVersionName: String
    ): Int {
        var deletedCount = 0
        val updatesDir = getUpdatesDir(context)

        // Clear skipped tag if user already updated to or beyond it
        val skippedTag = prefs.skippedVersionTag
        if (skippedTag != null && !GitHubReleaseClient.isNewerVersion(skippedTag, installedVersionName)) {
            prefs.skippedVersionTag = null
        }

        val cachedMeta = prefs.getCachedApkMetadata()
        var retainedTrackedFileName: String? = null

        if (cachedMeta != null) {
            val trackedFile = resolveContainedFile(updatesDir, cachedMeta.fileName)
            val isObsoleteByCode = installedVersionCode >= cachedMeta.versionCode
            val isObsoleteByName = !GitHubReleaseClient.isNewerVersion(cachedMeta.versionName, installedVersionName)

            if (isObsoleteByCode || isObsoleteByName) {
                if (trackedFile.exists() && trackedFile.delete()) {
                    deletedCount++
                }
                prefs.clearCachedApkMetadata()
            } else if (!trackedFile.exists()) {
                prefs.clearCachedApkMetadata()
            } else {
                val validation = verifier.verifyApk(
                    context = context,
                    apkFile = trackedFile,
                    expectedSha256 = cachedMeta.sha256,
                    expectedPackageName = installedPackageName,
                    installedVersionCodeOverride = installedVersionCode
                )
                if (validation is ApkValidationResult.Valid) {
                    retainedTrackedFileName = trackedFile.name
                } else {
                    if (trackedFile.delete()) {
                        deletedCount++
                    }
                    prefs.clearCachedApkMetadata()
                }
            }
        }

        updatesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val isTemp = file.name.endsWith(PART_SUFFIX) || file.name.endsWith(TMP_SUFFIX)
            val isUntrackedApk = file.name.endsWith(".apk") && file.name != retainedTrackedFileName
            if ((isTemp && !isTempFileActive(file.name)) || isUntrackedApk) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }

        return deletedCount
    }

    /**
     * Checks if a verified cached APK already exists for [release].
     * Always re-verifies SHA-256, package ID, versionCode, and signing compatibility before reuse.
     * Deletes any corrupted/tampered cache file automatically.
     */
    fun findReusableVerifiedApk(
        context: Context,
        prefs: UpdatePreferences,
        verifier: UpdateApkVerifier,
        release: GitHubReleaseInfo,
        installedPackageName: String,
        installedVersionCode: Long
    ): File? {
        val candidateFile = getApkFileForTag(context, release.tagName)
        if (!candidateFile.exists() || !candidateFile.isFile) {
            return null
        }

        val cachedMeta = prefs.getCachedApkMetadata()
        val expectedSha = release.expectedSha256
            ?: cachedMeta?.takeIf { it.tagName == release.tagName }?.sha256

        val validation = verifier.verifyApk(
            context = context,
            apkFile = candidateFile,
            expectedSha256 = expectedSha,
            expectedPackageName = installedPackageName,
            installedVersionCodeOverride = installedVersionCode
        )

        return when (validation) {
            is ApkValidationResult.Valid -> {
                prefs.saveCachedApkMetadata(
                    CachedApkMetadata(
                        fileName = candidateFile.name,
                        tagName = release.tagName,
                        versionName = release.versionName,
                        versionCode = validation.versionCode,
                        sha256 = validation.sha256,
                        sizeBytes = candidateFile.length()
                    )
                )
                candidateFile
            }
            is ApkValidationResult.Invalid -> {
                candidateFile.delete()
                if (cachedMeta?.fileName == candidateFile.name) {
                    prefs.clearCachedApkMetadata()
                }
                null
            }
        }
    }

    /**
     * Locates and re-verifies the currently cached APK (for user-initiated Install from notification).
     */
    fun findVerifiedCachedApkForInstall(
        context: Context,
        prefs: UpdatePreferences,
        verifier: UpdateApkVerifier,
        expectedTagName: String? = null,
        installedPackageName: String,
        installedVersionCode: Long
    ): Pair<File, CachedApkMetadata>? {
        val meta = prefs.getCachedApkMetadata() ?: return null
        if (expectedTagName != null && meta.tagName != expectedTagName) {
            return null
        }
        val updatesDir = getUpdatesDir(context)
        val file = resolveContainedFile(updatesDir, meta.fileName)
        if (!file.exists()) {
            prefs.clearCachedApkMetadata()
            return null
        }
        val validation = verifier.verifyApk(
            context = context,
            apkFile = file,
            expectedSha256 = meta.sha256,
            expectedPackageName = installedPackageName,
            installedVersionCodeOverride = installedVersionCode
        )
        return if (validation is ApkValidationResult.Valid) {
            file to meta
        } else {
            file.delete()
            prefs.clearCachedApkMetadata()
            null
        }
    }

    /**
     * Verifies [tempFile] and atomically commits it into `filesDir/updates` as the cached APK for [release].
     * Deletes [tempFile] on any verification failure and cleans up superseded cached APKs on success.
     */
    fun verifyAndCommitDownloadedApk(
        context: Context,
        prefs: UpdatePreferences,
        verifier: UpdateApkVerifier,
        release: GitHubReleaseInfo,
        tempFile: File,
        installedPackageName: String,
        installedVersionCode: Long
    ): Pair<ApkValidationResult, File?> {
        val validation = verifier.verifyApk(
            context = context,
            apkFile = tempFile,
            expectedSha256 = release.expectedSha256,
            expectedPackageName = installedPackageName,
            installedVersionCodeOverride = installedVersionCode
        )

        if (validation is ApkValidationResult.Invalid) {
            tempFile.delete()
            return validation to null
        }

        val valid = validation as ApkValidationResult.Valid
        val finalFile = getApkFileForTag(context, release.tagName)
        try {
            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("Failed to replace existing cached APK")
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            finalFile.delete()
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.INVALID_ARCHIVE,
                detail = "Failed to store verified APK: ${e.message}"
            ) to null
        }

        cleanupSupersededApks(context, keepFileName = finalFile.name)
        prefs.saveCachedApkMetadata(
            CachedApkMetadata(
                fileName = finalFile.name,
                tagName = release.tagName,
                versionName = release.versionName,
                versionCode = valid.versionCode,
                sha256 = valid.sha256,
                sizeBytes = finalFile.length()
            )
        )
        return valid to finalFile
    }

    private fun resolveContainedFile(updatesDir: File, fileName: String): File {
        val candidate = File(updatesDir, fileName)
        val canonicalDir = updatesDir.canonicalPath
        val canonicalFile = candidate.canonicalPath
        require(canonicalFile.startsWith(canonicalDir + File.separator)) {
            "Unsafe update cache file path: $fileName"
        }
        return candidate
    }

    companion object {
        const val UPDATES_DIR_NAME = "updates"
        private const val PART_SUFFIX = ".part"
        private const val TMP_SUFFIX = ".tmp"
        private val activeTempFileCounts = ConcurrentHashMap<String, AtomicInteger>()

        fun buildSafeApkFileName(tagName: String): String {
            val sanitized = tagName
                .trim()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .ifEmpty { "release" }
            return "kaihatch-$sanitized.apk"
        }
    }
}
