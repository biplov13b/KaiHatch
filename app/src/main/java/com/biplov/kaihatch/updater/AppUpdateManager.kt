package com.biplov.kaihatch.updater

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.biplov.kaihatch.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface UpdateNetworkStatusProvider {
    fun isNetworkAvailable(context: Context): Boolean
}

class AndroidUpdateNetworkStatusProvider : UpdateNetworkStatusProvider {
    override fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}

sealed class UpdateDownloadOutcome {
    data class ReadyToInstall(
        val release: GitHubReleaseInfo,
        val apkFile: File,
        val reusedFromCache: Boolean
    ) : UpdateDownloadOutcome()

    data object Cancelled : UpdateDownloadOutcome()
    data class Failed(val message: String, val reason: ApkValidationFailureReason? = null) : UpdateDownloadOutcome()
}

class AppUpdateManager(
    private val context: Context,
    val preferences: UpdatePreferences = UpdatePreferences(context),
    private val releaseClient: GitHubReleaseClient = GitHubReleaseClient(),
    val apkVerifier: UpdateApkVerifier = UpdateApkVerifier(),
    val cacheManager: UpdateCacheManager = UpdateCacheManager(),
    private val networkStatusProvider: UpdateNetworkStatusProvider = AndroidUpdateNetworkStatusProvider(),
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val installedPackageNameProvider: () -> String = { context.packageName },
    private val installedVersionCodeProvider: () -> Long = { BuildConfig.VERSION_CODE.toLong() },
    private val installedVersionNameProvider: () -> String = { BuildConfig.VERSION_NAME }
) {

    suspend fun performLaunchCleanup(): Int = withContext(Dispatchers.IO) {
        cacheManager.cleanupObsoleteAndCorruptedOnLaunch(
            context = context,
            prefs = preferences,
            verifier = apkVerifier,
            installedPackageName = installedPackageNameProvider(),
            installedVersionCode = installedVersionCodeProvider(),
            installedVersionName = installedVersionNameProvider()
        )
    }

    suspend fun checkForUpdates(
        isManual: Boolean,
        minIntervalMs: Long = if (isManual) 0L else BACKGROUND_MIN_CHECK_INTERVAL_MS
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!networkStatusProvider.isNetworkAvailable(context)) {
            return@withContext UpdateCheckResult.Offline
        }

        val now = currentTimeMillisProvider()
        if (!isManual && minIntervalMs > 0L) {
            val elapsed = now - preferences.lastCheckEpochMs
            if (preferences.lastCheckEpochMs > 0L && elapsed in 0 until minIntervalMs) {
                return@withContext UpdateCheckResult.Throttled
            }
        }

        val release = try {
            releaseClient.fetchLatestStableRelease(preferences)
        } catch (e: Exception) {
            return@withContext UpdateCheckResult.Error(e.message ?: "Unable to reach GitHub")
        }

        preferences.lastCheckEpochMs = now

        if (release == null) {
            return@withContext UpdateCheckResult.UpToDate(null)
        }

        preferences.lastKnownRelease = release

        val currentVersionName = installedVersionNameProvider()
        if (!GitHubReleaseClient.isNewerVersion(release.versionName, currentVersionName)) {
            return@withContext UpdateCheckResult.UpToDate(release.versionName)
        }

        if (!isManual && preferences.skippedVersionTag == release.tagName) {
            return@withContext UpdateCheckResult.Skipped(release)
        }

        val cachedApk = cacheManager.findReusableVerifiedApk(
            context = context,
            prefs = preferences,
            verifier = apkVerifier,
            release = release,
            installedPackageName = installedPackageNameProvider(),
            installedVersionCode = installedVersionCodeProvider()
        )

        UpdateCheckResult.UpdateAvailable(
            release = release,
            cachedApkFile = cachedApk
        )
    }

    fun findReusableCachedApk(release: GitHubReleaseInfo): File? {
        return cacheManager.findReusableVerifiedApk(
            context = context,
            prefs = preferences,
            verifier = apkVerifier,
            release = release,
            installedPackageName = installedPackageNameProvider(),
            installedVersionCode = installedVersionCodeProvider()
        )
    }

    fun findVerifiedCachedApkForInstall(expectedTagName: String? = null): Pair<File, CachedApkMetadata>? {
        return cacheManager.findVerifiedCachedApkForInstall(
            context = context,
            prefs = preferences,
            verifier = apkVerifier,
            expectedTagName = expectedTagName,
            installedPackageName = installedPackageNameProvider(),
            installedVersionCode = installedVersionCodeProvider()
        )
    }

    suspend fun downloadOrReuseUpdate(
        release: GitHubReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): UpdateDownloadOutcome = withContext(Dispatchers.IO) {
        val tagMutex = downloadMutexForTag(release.tagName)
        tagMutex.withLock {
            val installedPkg = installedPackageNameProvider()
            val installedCode = installedVersionCodeProvider()

            // 1. Reuse already verified cached APK if present (e.g. after cancelled installation)
            val existingVerified = cacheManager.findReusableVerifiedApk(
                context = context,
                prefs = preferences,
                verifier = apkVerifier,
                release = release,
                installedPackageName = installedPkg,
                installedVersionCode = installedCode
            )
            if (existingVerified != null) {
                val size = existingVerified.length()
                onProgress(size, size)
                return@withLock UpdateDownloadOutcome.ReadyToInstall(
                    release = release,
                    apkFile = existingVerified,
                    reusedFromCache = true
                )
            }

            // 2. Check connectivity & official URL before downloading
            if (!networkStatusProvider.isNetworkAvailable(context)) {
                return@withLock UpdateDownloadOutcome.Failed("No internet connection")
            }
            if (!GitHubReleaseClient.isAllowedGitHubUrl(release.apkDownloadUrl)) {
                return@withLock UpdateDownloadOutcome.Failed(
                    message = "Untrusted release download URL",
                    reason = ApkValidationFailureReason.UNTRUSTED_DOWNLOAD_URL
                )
            }

            val tempFile = cacheManager.getTempFileForTag(context, release.tagName)
            cacheManager.markTempFileActive(tempFile.name)
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                try {
                    releaseClient.downloadApk(
                        release = release,
                        targetFile = tempFile,
                        onProgress = onProgress,
                        isCancelled = isCancelled
                    )
                } catch (_: CancellationException) {
                    tempFile.delete()
                    return@withLock UpdateDownloadOutcome.Cancelled
                } catch (e: Exception) {
                    tempFile.delete()
                    if (isCancelled()) {
                        return@withLock UpdateDownloadOutcome.Cancelled
                    }
                    return@withLock UpdateDownloadOutcome.Failed(e.message ?: "Download failed")
                }

                if (isCancelled()) {
                    tempFile.delete()
                    return@withLock UpdateDownloadOutcome.Cancelled
                }

                // 3. Verify SHA-256, package ID, versionCode, and signing compatibility
                val (validation, committedFile) = cacheManager.verifyAndCommitDownloadedApk(
                    context = context,
                    prefs = preferences,
                    verifier = apkVerifier,
                    release = release,
                    tempFile = tempFile,
                    installedPackageName = installedPkg,
                    installedVersionCode = installedCode
                )

                when (validation) {
                    is ApkValidationResult.Valid -> {
                        if (committedFile != null) {
                            UpdateDownloadOutcome.ReadyToInstall(
                                release = release,
                                apkFile = committedFile,
                                reusedFromCache = false
                            )
                        } else {
                            UpdateDownloadOutcome.Failed("Failed to cache verified update APK")
                        }
                    }
                    is ApkValidationResult.Invalid -> {
                        UpdateDownloadOutcome.Failed(
                            message = validation.detail,
                            reason = validation.reason
                        )
                    }
                }
            } finally {
                cacheManager.unmarkTempFileActive(tempFile.name)
            }
        }
    }

    fun skipVersion(tagName: String) {
        preferences.skippedVersionTag = tagName
        UpdateNotificationHelper.cancelAvailableNotification(context)
    }

    fun shouldPostBackgroundNotificationFor(tagName: String): Boolean {
        if (preferences.skippedVersionTag == tagName) return false
        return preferences.lastNotifiedVersionTag != tagName
    }

    fun markVersionNotified(tagName: String) {
        preferences.lastNotifiedVersionTag = tagName
    }

    companion object {
        const val BACKGROUND_MIN_CHECK_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L // 6 hours
        const val STARTUP_MIN_CHECK_INTERVAL_MS: Long = 0L // Startup checks once per launch using ETag
        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

        private val KNOWN_SYSTEM_INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.oplus.appdetail",
            "com.coloros.appdetail",
            "com.vivo.appfilter",
            "com.huawei.appmarket"
        )

        private val tagDownloadMutexes = ConcurrentHashMap<String, Mutex>()

        private fun downloadMutexForTag(tagName: String): Mutex {
            val key = UpdateCacheManager.buildSafeApkFileName(tagName)
            return tagDownloadMutexes.computeIfAbsent(key) { Mutex() }
        }

        @Volatile
        internal var instanceOverrideForTesting: AppUpdateManager? = null

        @Volatile
        private var defaultInstance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            instanceOverrideForTesting?.let { return it }
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: AppUpdateManager(context.applicationContext).also {
                    defaultInstance = it
                }
            }
        }

        fun canRequestPackageInstalls(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.packageManager.canRequestPackageInstalls()
                } catch (_: Exception) {
                    false
                }
            } else {
                true
            }
        }

        fun createUnknownAppSourcesSettingsIntent(context: Context): Intent {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }

        /**
         * Creates an intent targeting Android's trusted native Package Installer while strictly
         * excluding KaiHatch itself (whose MainActivity declares ACTION_VIEW for .apk extraction).
         */
        fun createNativeInstallerIntent(context: Context, apkFile: File): Intent {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            @Suppress("DEPRECATION")
            val installPackageMimeProbe = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(contentUri, MIME_TYPE_APK)
            }
            @Suppress("DEPRECATION")
            val installPackageSchemeProbe = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = contentUri
            }
            val viewApkProbe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, MIME_TYPE_APK)
            }

            val viewTarget = selectBestTrustedInstaller(
                context = context,
                candidates = queryExportedActivities(context.packageManager, viewApkProbe)
            )
            val installTarget = selectBestTrustedInstaller(
                context = context,
                candidates = queryExportedActivities(context.packageManager, installPackageMimeProbe) +
                    queryExportedActivities(context.packageManager, installPackageSchemeProbe)
            )

            // Prefer a trusted system installer resolved via ACTION_VIEW (non-deprecated on API 29+);
            // next prefer a system installer resolved via ACTION_INSTALL_PACKAGE or ACTION_VIEW;
            // never route to KaiHatch itself or an untrusted 3rd-party APK viewer — fall back to
            // implicit ACTION_INSTALL_PACKAGE which the OS routes to the native PackageInstaller.
            val chosenAction: String
            val chosenComponent: ComponentName?
            when {
                viewTarget != null && isTrustedSystemInstaller(viewTarget) -> {
                    chosenAction = Intent.ACTION_VIEW
                    chosenComponent = ComponentName(
                        viewTarget.activityInfo.packageName,
                        viewTarget.activityInfo.name
                    )
                }
                installTarget != null && isSystemApp(installTarget) -> {
                    @Suppress("DEPRECATION")
                    chosenAction = Intent.ACTION_INSTALL_PACKAGE
                    chosenComponent = ComponentName(
                        installTarget.activityInfo.packageName,
                        installTarget.activityInfo.name
                    )
                }
                viewTarget != null && isSystemApp(viewTarget) -> {
                    chosenAction = Intent.ACTION_VIEW
                    chosenComponent = ComponentName(
                        viewTarget.activityInfo.packageName,
                        viewTarget.activityInfo.name
                    )
                }
                else -> {
                    @Suppress("DEPRECATION")
                    chosenAction = Intent.ACTION_INSTALL_PACKAGE
                    chosenComponent = null
                }
            }

            if (chosenComponent != null) {
                try {
                    context.grantUriPermission(
                        chosenComponent.packageName,
                        contentUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // FLAG_GRANT_READ_URI_PERMISSION + ClipData still propagate URI grant
                }
            }

            return Intent(chosenAction).apply {
                setDataAndType(contentUri, MIME_TYPE_APK)
                clipData = ClipData.newRawUri(null, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (chosenComponent != null) {
                    component = chosenComponent
                }
            }
        }

        private fun queryExportedActivities(
            packageManager: PackageManager,
            probeIntent: Intent
        ): List<ResolveInfo> {
            return try {
                val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        probeIntent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                }
                results.filter { info ->
                    val ai = info.activityInfo ?: return@filter false
                    ai.exported && !ai.packageName.isNullOrBlank() && !ai.name.isNullOrBlank()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun selectBestTrustedInstaller(
            context: Context,
            candidates: List<ResolveInfo>
        ): ResolveInfo? {
            val selfPackage = context.packageName
            val external = candidates.filter { it.activityInfo?.packageName != selfPackage }
            if (external.isEmpty()) return null
            return external.maxByOrNull { scoreInstallerCandidate(it) }
        }

        private fun isSystemApp(info: ResolveInfo): Boolean {
            val flags = info.activityInfo?.applicationInfo?.flags ?: 0
            return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }

        private fun isTrustedSystemInstaller(info: ResolveInfo): Boolean {
            if (!isSystemApp(info)) return false
            val pkg = info.activityInfo?.packageName?.lowercase() ?: return false
            return pkg in KNOWN_SYSTEM_INSTALLER_PACKAGES || pkg.contains("packageinstaller")
        }

        private fun scoreInstallerCandidate(info: ResolveInfo): Int {
            val pkg = info.activityInfo?.packageName?.lowercase().orEmpty()
            val system = isSystemApp(info)
            var score = 0
            if (system) score += 100
            if (pkg in KNOWN_SYSTEM_INSTALLER_PACKAGES) score += 50
            if (pkg.contains("packageinstaller")) score += 30
            if (pkg == "com.google.android.packageinstaller" || pkg == "com.android.packageinstaller") {
                score += 20
            }
            return score
        }
    }
}
