package com.biplov.kaihatch.updater

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import android.os.Looper
import com.biplov.kaihatch.R
import com.biplov.kaihatch.ui.HatchViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {

    private lateinit var app: Application
    private lateinit var updatesDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        UpdatePreferences(app).clearAllForTesting()
        updatesDir = File(app.filesDir, UpdateCacheManager.UPDATES_DIR_NAME)
        updatesDir.deleteRecursively()
        AppUpdateManager.instanceOverrideForTesting = null

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    @After
    fun tearDown() {
        AppUpdateManager.instanceOverrideForTesting = null
        UpdatePreferences(app).clearAllForTesting()
        updatesDir.deleteRecursively()
    }

    private class FakeTransport(
        private val getHandler: (url: String, headers: Map<String, String>) -> UpdateHttpResponse,
        private val downloadBytes: ByteArray = VALID_APK_BYTES,
        private val cancelAfterFirstChunk: Boolean = false
    ) : UpdateHttpTransport {
        val getCallCount = AtomicInteger(0)
        val downloadCallCount = AtomicInteger(0)
        val receivedGetHeaders = mutableListOf<Map<String, String>>()

        override fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
            getCallCount.incrementAndGet()
            receivedGetHeaders.add(headers)
            return getHandler(url, headers)
        }

        override fun download(
            url: String,
            headers: Map<String, String>,
            targetFile: File,
            expectedTotalBytes: Long,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
            isCancelled: () -> Boolean
        ) {
            downloadCallCount.incrementAndGet()
            targetFile.parentFile?.mkdirs()
            val half = (downloadBytes.size / 2).coerceAtLeast(1)
            targetFile.outputStream().use { out ->
                out.write(downloadBytes, 0, half)
                onProgress(half.toLong(), downloadBytes.size.toLong())
                if (cancelAfterFirstChunk || isCancelled()) {
                    throw CancellationException("Download cancelled in test")
                }
                if (downloadBytes.size > half) {
                    out.write(downloadBytes, half, downloadBytes.size - half)
                }
                out.flush()
            }
            onProgress(downloadBytes.size.toLong(), downloadBytes.size.toLong())
        }
    }

    private class FakeArchiveInspector(
        var archiveMetadata: ApkArchiveMetadata? = ApkArchiveMetadata(
            packageName = "com.biplov.kaihatch",
            versionCode = 3L,
            versionName = "1.0.2",
            signingCertSha256Digests = setOf(TRUSTED_SIGNER_SHA256)
        ),
        var installedMetadata: ApkArchiveMetadata = ApkArchiveMetadata(
            packageName = "com.biplov.kaihatch",
            versionCode = 2L,
            versionName = "1.0.1",
            signingCertSha256Digests = setOf(TRUSTED_SIGNER_SHA256)
        )
    ) : PackageArchiveInspector {
        override fun inspectArchive(context: Context, apkFile: File): ApkArchiveMetadata? = archiveMetadata
        override fun getInstalledAppInfo(context: Context): ApkArchiveMetadata = installedMetadata
    }

    private class FakeNetworkProvider(var online: Boolean = true) : UpdateNetworkStatusProvider {
        override fun isNetworkAvailable(context: Context): Boolean = online
    }

    private fun buildReleaseJson(
        tag: String = "v1.0.2",
        name: String = "KaiHatch 1.0.2",
        body: String = "Performance improvements and bug fixes.",
        apkName: String = "KaiHatch-v1.0.2-release.apk",
        apkUrl: String = "https://github.com/biplov13b/KaiHatch/releases/download/$tag/$apkName",
        apkSize: Long = VALID_APK_BYTES.size.toLong(),
        sha256: String? = VALID_APK_SHA256,
        draft: Boolean = false,
        prerelease: Boolean = false
    ): String {
        val digestField = if (sha256 != null) "\"digest\": \"sha256:$sha256\"," else ""
        return """
            {
              "tag_name": "$tag",
              "name": "$name",
              "body": "$body",
              "html_url": "https://github.com/biplov13b/KaiHatch/releases/tag/$tag",
              "published_at": "2026-11-20T10:00:00Z",
              "draft": $draft,
              "prerelease": $prerelease,
              "assets": [
                {
                  "name": "$apkName",
                  "size": $apkSize,
                  $digestField
                  "browser_download_url": "$apkUrl"
                }
              ]
            }
        """.trimIndent()
    }

    private fun createManager(
        transport: FakeTransport,
        inspector: FakeArchiveInspector = FakeArchiveInspector(),
        network: FakeNetworkProvider = FakeNetworkProvider(true),
        clock: () -> Long = { 1_700_000_000_000L },
        installedVersionCode: Long = 2L,
        installedVersionName: String = "1.0.1"
    ): AppUpdateManager {
        return AppUpdateManager(
            context = app,
            preferences = UpdatePreferences(app),
            releaseClient = GitHubReleaseClient(transport = transport),
            apkVerifier = UpdateApkVerifier(archiveInspector = inspector),
            cacheManager = UpdateCacheManager(),
            networkStatusProvider = network,
            currentTimeMillisProvider = clock,
            installedPackageNameProvider = { "com.biplov.kaihatch" },
            installedVersionCodeProvider = { installedVersionCode },
            installedVersionNameProvider = { installedVersionName }
        )
    }

    private fun idleMainLooperUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `startup check toasts checking and shows popup only when newer stable release exists`() {
        val transport = FakeTransport(
            getHandler = { _, _ ->
                UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2"), etag = "\"etag-102\"")
            }
        )
        AppUpdateManager.instanceOverrideForTesting = createManager(transport)

        val viewModel = HatchViewModel(app)
        val emittedEvents = java.util.Collections.synchronizedList(mutableListOf<UpdateUiEvent>())
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.updateEvents.collect { emittedEvents.add(it) }
        }
        try {
            viewModel.initializeUpdaterOnStartup()
            idleMainLooperUntil { viewModel.updateDialogState.value is UpdateDialogState.Available }
        } finally {
            collectJob.cancel()
        }

        assertTrue(emittedEvents.any {
            it is UpdateUiEvent.ShowToast && it.message == app.getString(R.string.update_toast_checking)
        })
        val state = viewModel.updateDialogState.value
        assertTrue("Expected UpdateDialogState.Available, got $state", state is UpdateDialogState.Available)
        val available = state as UpdateDialogState.Available
        assertEquals("v1.0.2", available.release.tagName)
        assertEquals("1.0.2", available.release.versionName)
        assertFalse(available.hasCachedApk)
    }

    @Test
    fun `startup check shows no popup when already up to date`() {
        val transport = FakeTransport(
            getHandler = { _, _ ->
                UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.1"), etag = "\"etag-101\"")
            }
        )
        AppUpdateManager.instanceOverrideForTesting = createManager(transport)

        val viewModel = HatchViewModel(app)
        viewModel.initializeUpdaterOnStartup()
        idleMainLooperUntil { transport.getCallCount.get() >= 1 && !viewModel.isCheckingForUpdates.value }

        assertTrue(viewModel.updateDialogState.value is UpdateDialogState.Hidden)
    }

    @Test
    fun `prerelease and draft releases are ignored in favor of stable release`() = runBlocking {
        val listJson = """
            [
              ${buildReleaseJson(tag = "v2.0.0-beta.1", prerelease = true)},
              ${buildReleaseJson(tag = "v1.5.0", draft = true)},
              ${buildReleaseJson(tag = "v1.0.3", prerelease = false, draft = false)}
            ]
        """.trimIndent()

        val transport = FakeTransport(
            getHandler = { url, _ ->
                if (url.contains("/releases/latest")) {
                    UpdateHttpResponse(404, "{}")
                } else {
                    UpdateHttpResponse(200, listJson)
                }
            }
        )
        val manager = createManager(transport)
        val result = manager.checkForUpdates(isManual = true)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals("v1.0.3", (result as UpdateCheckResult.UpdateAvailable).release.tagName)
    }

    @Test
    fun `skip version suppresses startup and background prompts but manual check still offers it`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ ->
                UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2"), etag = "\"etag-102\"")
            }
        )
        val manager = createManager(transport)

        // User clicks Skip on v1.0.2
        manager.skipVersion("v1.0.2")
        assertEquals("v1.0.2", manager.preferences.skippedVersionTag)

        // Startup / background check skips it
        val autoCheck = manager.checkForUpdates(isManual = false, minIntervalMs = 0L)
        assertTrue(autoCheck is UpdateCheckResult.Skipped)
        assertFalse(manager.shouldPostBackgroundNotificationFor("v1.0.2"))

        // Manual check from About still offers the skipped version
        val manualCheck = manager.checkForUpdates(isManual = true)
        assertTrue(manualCheck is UpdateCheckResult.UpdateAvailable)
        assertEquals("v1.0.2", (manualCheck as UpdateCheckResult.UpdateAvailable).release.tagName)
    }

    @Test
    fun `manual check emits up to date toast when current`() {
        val transport = FakeTransport(
            getHandler = { _, _ ->
                UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.1"))
            }
        )
        AppUpdateManager.instanceOverrideForTesting = createManager(transport)

        val viewModel = HatchViewModel(app)
        val events = java.util.Collections.synchronizedList(mutableListOf<UpdateUiEvent>())
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.updateEvents.collect { events.add(it) }
        }
        try {
            viewModel.checkForUpdatesManually()
            idleMainLooperUntil { events.size >= 2 }
        } finally {
            collectJob.cancel()
        }

        assertEquals(2, events.size)
        assertEquals(
            app.getString(R.string.update_toast_checking),
            (events[0] as UpdateUiEvent.ShowToast).message
        )
        assertTrue(
            (events[1] as UpdateUiEvent.ShowToast).message.contains("up to date", ignoreCase = true)
        )
        assertTrue(viewModel.updateDialogState.value is UpdateDialogState.Hidden)
    }

    @Test
    fun `etag 304 caching and minimum interval prevent excessive github requests`() = runBlocking {
        var now = 1_700_000_000_000L
        val transport = FakeTransport(
            getHandler = { _, headers ->
                if (headers["If-None-Match"] == "\"etag-102\"") {
                    UpdateHttpResponse(304, null, etag = "\"etag-102\"")
                } else {
                    UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2"), etag = "\"etag-102\"")
                }
            }
        )
        val manager = createManager(transport, clock = { now })

        // First check stores ETag and timestamp
        val first = manager.checkForUpdates(isManual = false)
        assertTrue(first is UpdateCheckResult.UpdateAvailable)
        assertEquals(1, transport.getCallCount.get())

        // Immediate second background check is throttled without hitting GitHub
        now += 10 * 60 * 1000L // 10 minutes
        val throttled = manager.checkForUpdates(isManual = false)
        assertTrue(throttled is UpdateCheckResult.Throttled)
        assertEquals(1, transport.getCallCount.get())

        // After 6+ hours, sends If-None-Match ETag and parses cached JSON on 304 Not Modified
        now += AppUpdateManager.BACKGROUND_MIN_CHECK_INTERVAL_MS + 1000L
        val revalidated = manager.checkForUpdates(isManual = false)
        assertTrue(revalidated is UpdateCheckResult.UpdateAvailable)
        assertEquals(2, transport.getCallCount.get())
        assertEquals("\"etag-102\"", transport.receivedGetHeaders.last()["If-None-Match"])
    }

    @Test
    fun `background update check worker posts deduplicated notification with download action and respects permission`() = runBlocking {
        var now = 1_700_000_000_000L
        val transport = FakeTransport(
            getHandler = { _, _ ->
                UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2"))
            }
        )
        val manager = createManager(transport, clock = { now })
        AppUpdateManager.instanceOverrideForTesting = manager

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(nm)

        // 1. Without POST_NOTIFICATIONS permission on API 34 -> no notification posted
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val workerDenied = createTestWorker(UpdateCheckWorker::class.java)
        assertEquals(ListenableWorker.Result.success(), workerDenied.doWork())
        assertEquals(0, shadowNm.allNotifications.size)

        // 2. With POST_NOTIFICATIONS granted -> posts Update Available notification with Download action
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val workerGranted = createTestWorker(UpdateCheckWorker::class.java)
        assertEquals(ListenableWorker.Result.success(), workerGranted.doWork())

        val availableNotif = shadowNm.getNotification(UpdateNotificationHelper.NOTIFICATION_UPDATE_AVAILABLE_ID)
        assertNotNull("Expected Update Available notification", availableNotif)
        assertEquals(1, availableNotif.actions?.size)
        assertEquals(app.getString(R.string.update_action_download), availableNotif.actions[0].title.toString())

        // 3. Re-running after cooldown does NOT post a duplicate notification for same version
        nm.cancelAll()
        now += AppUpdateManager.BACKGROUND_MIN_CHECK_INTERVAL_MS + 5000L
        val workerSecond = createTestWorker(UpdateCheckWorker::class.java)
        assertEquals(ListenableWorker.Result.success(), workerSecond.doWork())
        assertNull(shadowNm.getNotification(UpdateNotificationHelper.NOTIFICATION_UPDATE_AVAILABLE_ID))
    }

    @Test
    fun `background download worker verifies apk caches in filesDir updates and posts install notification without auto launching installer`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )
        val manager = createManager(transport)
        AppUpdateManager.instanceOverrideForTesting = manager

        val release = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!
        val input = Data.Builder()
            .putString(UpdateDownloadWorker.KEY_RELEASE_JSON, release.toJsonString())
            .build()

        val downloadWorker = createTestWorker(UpdateDownloadWorker::class.java, input)
        assertEquals(ListenableWorker.Result.success(), downloadWorker.doWork())

        // Verified APK is stored inside app-private filesDir/updates
        val cachedFile = File(updatesDir, UpdateCacheManager.buildSafeApkFileName("v1.0.2"))
        assertTrue(cachedFile.exists())
        assertEquals(VALID_APK_BYTES.size.toLong(), cachedFile.length())

        // Install notification is posted; installer activity was NEVER launched automatically
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val installNotif = shadowOf(nm).getNotification(UpdateNotificationHelper.NOTIFICATION_UPDATE_READY_INSTALL_ID)
        assertNotNull("Expected Ready to Install notification", installNotif)
        assertEquals(app.getString(R.string.update_action_install), installNotif.actions[0].title.toString())
        assertNull("Installer must NOT be launched automatically from background", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `cancelled installation retains verified apk and retry reuses cache without redownloading`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )
        val manager = createManager(transport)
        val release = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!

        // First update downloads and caches the verified APK
        val firstOutcome = manager.downloadOrReuseUpdate(release)
        assertTrue(firstOutcome is UpdateDownloadOutcome.ReadyToInstall)
        val firstReady = firstOutcome as UpdateDownloadOutcome.ReadyToInstall
        assertFalse(firstReady.reusedFromCache)
        assertEquals(1, transport.downloadCallCount.get())
        assertTrue(firstReady.apkFile.exists())

        // User cancels system installer -> APK remains retained in filesDir/updates
        assertTrue(firstReady.apkFile.exists())

        // Verify native installer intent uses FileProvider, read grant, and never targets KaiHatch itself
        val installerIntent = AppUpdateManager.createNativeInstallerIntent(app, firstReady.apkFile)
        @Suppress("DEPRECATION")
        assertTrue(
            installerIntent.action == Intent.ACTION_VIEW ||
                installerIntent.action == Intent.ACTION_INSTALL_PACKAGE
        )
        assertEquals("application/vnd.android.package-archive", installerIntent.type)
        assertTrue((installerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals("com.biplov.kaihatch.fileprovider", installerIntent.data?.authority)
        assertFalse(
            "Installer intent must never target KaiHatch itself",
            installerIntent.component?.packageName == app.packageName
        )

        // Retry reuses cached verified APK without downloading again
        val retryOutcome = manager.downloadOrReuseUpdate(release)
        assertTrue(retryOutcome is UpdateDownloadOutcome.ReadyToInstall)
        val retryReady = retryOutcome as UpdateDownloadOutcome.ReadyToInstall
        assertTrue(retryReady.reusedFromCache)
        assertEquals(1, transport.downloadCallCount.get())
    }

    @Test
    fun `corrupted cached apk is safely deleted and redownloaded on retry`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )
        val manager = createManager(transport)
        val release = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!

        val first = manager.downloadOrReuseUpdate(release) as UpdateDownloadOutcome.ReadyToInstall
        assertEquals(1, transport.downloadCallCount.get())

        // Corrupt cached file on disk
        first.apkFile.writeBytes("CORRUPTED_BYTES".toByteArray())
        assertNull(manager.findReusableCachedApk(release))
        assertFalse("Corrupted cache file must be deleted", first.apkFile.exists())

        // Retry cleanly downloads a fresh verified APK
        val redownloaded = manager.downloadOrReuseUpdate(release)
        assertTrue(redownloaded is UpdateDownloadOutcome.ReadyToInstall)
        assertFalse((redownloaded as UpdateDownloadOutcome.ReadyToInstall).reusedFromCache)
        assertEquals(2, transport.downloadCallCount.get())
    }

    @Test
    fun `cancelled download deletes partial file cleanly`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES,
            cancelAfterFirstChunk = true
        )
        val manager = createManager(transport)
        val release = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!

        val outcome = manager.downloadOrReuseUpdate(release)
        assertTrue(outcome is UpdateDownloadOutcome.Cancelled)
        assertTrue("No partial or corrupted files should remain", updatesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `apk verification enforces sha256 package id version code signing compatibility and https host`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )
        val baseRelease = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!

        // 1. Untrusted download URL rejected
        val untrustedOutcome = createManager(transport).downloadOrReuseUpdate(
            baseRelease.copy(apkDownloadUrl = "https://evil.example.com/KaiHatch.apk")
        )
        assertTrue(untrustedOutcome is UpdateDownloadOutcome.Failed)
        assertEquals(ApkValidationFailureReason.UNTRUSTED_DOWNLOAD_URL, (untrustedOutcome as UpdateDownloadOutcome.Failed).reason)

        // 2. Missing SHA-256 rejected fail-closed
        val missingShaOutcome = createManager(transport).downloadOrReuseUpdate(
            baseRelease.copy(expectedSha256 = null)
        )
        assertTrue(missingShaOutcome is UpdateDownloadOutcome.Failed)
        assertEquals(ApkValidationFailureReason.SHA256_MISSING, (missingShaOutcome as UpdateDownloadOutcome.Failed).reason)

        // 3. Mismatched SHA-256 rejected & file deleted
        val wrongShaOutcome = createManager(transport).downloadOrReuseUpdate(
            baseRelease.copy(expectedSha256 = "0".repeat(64))
        )
        assertTrue(wrongShaOutcome is UpdateDownloadOutcome.Failed)
        assertEquals(ApkValidationFailureReason.SHA256_MISMATCH, (wrongShaOutcome as UpdateDownloadOutcome.Failed).reason)
        assertTrue(updatesDir.listFiles().orEmpty().isEmpty())

        // 4. Wrong package ID rejected
        val badPkgInspector = FakeArchiveInspector(
            archiveMetadata = ApkArchiveMetadata("com.spoof.other", 3L, "1.0.2", setOf(TRUSTED_SIGNER_SHA256))
        )
        val badPkgOutcome = createManager(transport, inspector = badPkgInspector).downloadOrReuseUpdate(baseRelease)
        assertTrue(badPkgOutcome is UpdateDownloadOutcome.Failed)
        assertEquals(ApkValidationFailureReason.PACKAGE_ID_MISMATCH, (badPkgOutcome as UpdateDownloadOutcome.Failed).reason)

        // 5. Downgrade / equal versionCode rejected
        val downgradeInspector = FakeArchiveInspector(
            archiveMetadata = ApkArchiveMetadata("com.biplov.kaihatch", 2L, "1.0.2", setOf(TRUSTED_SIGNER_SHA256))
        )
        val downgradeOutcome = createManager(transport, inspector = downgradeInspector).downloadOrReuseUpdate(baseRelease)
        assertTrue(downgradeOutcome is UpdateDownloadOutcome.Failed)
        assertEquals(ApkValidationFailureReason.VERSION_NOT_NEWER, (downgradeOutcome as UpdateDownloadOutcome.Failed).reason)

        // 6. Signing certificate mismatch rejected
        val badSigInspector = FakeArchiveInspector(
            archiveMetadata = ApkArchiveMetadata("com.biplov.kaihatch", 3L, "1.0.2", setOf("f".repeat(64)))
        )
        val badSigOutcome = createManager(transport, inspector = badSigInspector).downloadOrReuseUpdate(baseRelease)
        assertTrue(badSigOutcome is UpdateDownloadOutcome.Failed)
        assertEquals(ApkValidationFailureReason.SIGNING_MISMATCH, (badSigOutcome as UpdateDownloadOutcome.Failed).reason)
    }

    @Test
    fun `obsolete cached apk is deleted on next launch after successful app update and superseded apks are removed`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )

        // Download v1.0.2 while running v1.0.1 (versionCode 2)
        val oldManager = createManager(transport, installedVersionCode = 2L, installedVersionName = "1.0.1")
        val r102 = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!
        val outcome102 = oldManager.downloadOrReuseUpdate(r102) as UpdateDownloadOutcome.ReadyToInstall
        assertTrue(outcome102.apkFile.exists())

        // Superseded APK cleanup: downloading v1.0.3 removes v1.0.2 APK
        val r103 = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.3"))
        )!!
        val inspector103 = FakeArchiveInspector(
            archiveMetadata = ApkArchiveMetadata("com.biplov.kaihatch", 4L, "1.0.3", setOf(TRUSTED_SIGNER_SHA256))
        )
        val outcome103 = createManager(transport, inspector = inspector103).downloadOrReuseUpdate(r103) as UpdateDownloadOutcome.ReadyToInstall
        assertTrue(outcome103.apkFile.exists())
        assertFalse("Superseded v1.0.2 APK must be removed", outcome102.apkFile.exists())

        // Simulate app now updated to v1.0.3 (versionCode 4) -> next launch cleanup deletes obsolete cached APK
        val updatedManager = createManager(
            transport = transport,
            inspector = FakeArchiveInspector(
                archiveMetadata = ApkArchiveMetadata("com.biplov.kaihatch", 4L, "1.0.3", setOf(TRUSTED_SIGNER_SHA256)),
                installedMetadata = ApkArchiveMetadata("com.biplov.kaihatch", 4L, "1.0.3", setOf(TRUSTED_SIGNER_SHA256))
            ),
            installedVersionCode = 4L,
            installedVersionName = "1.0.3"
        )
        val deleted = updatedManager.performLaunchCleanup()
        assertTrue("Expected obsolete APK to be deleted", deleted >= 1)
        assertFalse(outcome103.apkFile.exists())
        assertNull(updatedManager.preferences.getCachedApkMetadata())
    }

    @Test
    fun `offline behavior is graceful across startup manual and background checks`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val transport = FakeTransport(
            getHandler = { _, _ -> throw IOException("Should not hit network when offline") }
        )
        val offlineNetwork = FakeNetworkProvider(online = false)
        val manager = createManager(transport, network = offlineNetwork)
        AppUpdateManager.instanceOverrideForTesting = manager

        assertEquals(UpdateCheckResult.Offline, manager.checkForUpdates(isManual = false))
        assertEquals(UpdateCheckResult.Offline, manager.checkForUpdates(isManual = true))
        assertEquals(0, transport.getCallCount.get())

        // Background worker requests retry gracefully without crashing or posting notifications
        val worker = createTestWorker(UpdateCheckWorker::class.java)
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())

        // Manual check through ViewModel emits offline toast and keeps dialog hidden
        val viewModel = HatchViewModel(app)
        val events = java.util.Collections.synchronizedList(mutableListOf<UpdateUiEvent>())
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.updateEvents.collect { events.add(it) }
        }
        try {
            viewModel.checkForUpdatesManually()
            idleMainLooperUntil { events.size >= 2 }
        } finally {
            collectJob.cancel()
        }

        assertEquals(app.getString(R.string.update_toast_offline), (events[1] as UpdateUiEvent.ShowToast).message)
        assertTrue(viewModel.updateDialogState.value is UpdateDialogState.Hidden)
    }

    @Test
    fun `native installer intent targets trusted system package installer and never self-intercepts`() {
        val apkFile = File(updatesDir, UpdateCacheManager.buildSafeApkFileName("v1.0.2")).apply {
            parentFile?.mkdirs()
            writeBytes(VALID_APK_BYTES)
        }
        val shadowPm = shadowOf(app.packageManager)

        fun buildResolveInfo(pkg: String, cls: String, isSystem: Boolean): ResolveInfo {
            return ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = cls
                    exported = true
                    applicationInfo = ApplicationInfo().apply {
                        packageName = pkg
                        flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
                    }
                }
            }
        }

        // Register KaiHatch itself, a third-party APK extractor, and Google's system Package Installer for ACTION_VIEW
        val kaiHatchSelf = buildResolveInfo(app.packageName, "com.biplov.kaihatch.MainActivity", isSystem = false)
        val thirdPartyViewer = buildResolveInfo("com.other.apkviewer", "com.other.apkviewer.ViewerActivity", isSystem = false)
        val systemInstaller = buildResolveInfo(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller.InstallStart",
            isSystem = true
        )

        val probeViewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apkFile),
                "application/vnd.android.package-archive"
            )
        }
        shadowPm.addResolveInfoForIntent(probeViewIntent, kaiHatchSelf)
        shadowPm.addResolveInfoForIntent(probeViewIntent, thirdPartyViewer)
        shadowPm.addResolveInfoForIntent(probeViewIntent, systemInstaller)

        val resolvedIntent = AppUpdateManager.createNativeInstallerIntent(app, apkFile)
        assertEquals(Intent.ACTION_VIEW, resolvedIntent.action)
        assertEquals("com.google.android.packageinstaller", resolvedIntent.component?.packageName)
        assertEquals("com.android.packageinstaller.InstallStart", resolvedIntent.component?.className)
        assertTrue((resolvedIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNotNull(resolvedIntent.clipData)
        assertEquals(1, resolvedIntent.clipData?.itemCount)

        // When system installer is absent from ACTION_VIEW (only KaiHatch and a non-system 3rd-party viewer remain),
        // fall back to ACTION_INSTALL_PACKAGE (never self-targeting or launching a 3rd-party APK viewer)
        shadowPm.removeResolveInfosForIntent(probeViewIntent, "com.google.android.packageinstaller")
        val fallbackIntent = AppUpdateManager.createNativeInstallerIntent(app, apkFile)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, fallbackIntent.action)
        assertNull(fallbackIntent.component)
        assertEquals("application/vnd.android.package-archive", fallbackIntent.type)
    }

    @Test
    fun `long release notes stay under WorkManager 10KB data limit and hydrate cleanly`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val hugeReleaseNotes = buildString {
            repeat(500) { idx ->
                append("- Detailed changelog bullet #$idx describing extraction improvements and fixes.\n")
            }
        }
        assertTrue("Test release notes should exceed 30 KB", hugeReleaseNotes.toByteArray().size > 30_000)

        val release = GitHubReleaseInfo(
            tagName = "v1.0.2",
            versionName = "1.0.2",
            releaseName = "KaiHatch v1.0.2",
            releaseNotes = hugeReleaseNotes,
            htmlUrl = "https://github.com/biplov13b/KaiHatch/releases/tag/v1.0.2",
            publishedAt = "2026-11-21T10:00:00Z",
            apkAssetName = "KaiHatch-v1.0.2-release.apk",
            apkDownloadUrl = "https://github.com/biplov13b/KaiHatch/releases/download/v1.0.2/KaiHatch-v1.0.2-release.apk",
            apkSizeBytes = VALID_APK_BYTES.size.toLong(),
            expectedSha256 = VALID_APK_SHA256
        )

        // Compact WorkManager Data must succeed well below the 10,240-byte limit
        val inputData = UpdateWorkScheduler.buildDownloadWorkInputData(release)
        val serializedJson = inputData.getString(UpdateDownloadWorker.KEY_RELEASE_JSON)
        assertNotNull(serializedJson)
        assertTrue("Compact WorkManager payload must be < 2 KB", serializedJson!!.toByteArray().size < 2048)
        assertFalse("Compact payload must omit unbounded releaseNotes", serializedJson.contains("Detailed changelog bullet"))

        // Notification PendingIntent must also use compact payload
        assertTrue(UpdateNotificationHelper.showUpdateAvailableNotification(app, release))
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = shadowOf(nm).getNotification(UpdateNotificationHelper.NOTIFICATION_UPDATE_AVAILABLE_ID)
        assertNotNull(notif)
        val actionIntent = shadowOf(notif.actions[0].actionIntent).savedIntent
        val extraJson = actionIntent.getStringExtra(UpdateNotificationHelper.EXTRA_RELEASE_JSON)
        assertNotNull(extraJson)
        assertTrue(extraJson!!.toByteArray().size < 2048)

        // Persist full release in preferences & verify UpdateDownloadWorker completes cleanly with compact Data
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )
        val manager = createManager(transport)
        manager.preferences.lastKnownRelease = release
        AppUpdateManager.instanceOverrideForTesting = manager

        val worker = createTestWorker(UpdateDownloadWorker::class.java, inputData)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val cachedFile = File(updatesDir, UpdateCacheManager.buildSafeApkFileName("v1.0.2"))
        assertTrue(cachedFile.exists())

        // Opening update dialog from compact notification extra hydrates full release notes from preferences
        val viewModel = HatchViewModel(app)
        viewModel.openUpdateDialogFromNotification(extraJson, release.tagName)
        idleMainLooperUntil { viewModel.updateDialogState.value is UpdateDialogState.Available }
        val available = viewModel.updateDialogState.value as UpdateDialogState.Available
        assertEquals(hugeReleaseNotes, available.release.releaseNotes)
    }

    @Test
    fun `concurrent cleanup does not delete active part download while orphaned part files are cleaned`() = runBlocking {
        val cleanupRanDuringDownload = java.util.concurrent.atomic.AtomicBoolean(false)
        val activePartExistedAfterCleanup = java.util.concurrent.atomic.AtomicBoolean(false)

        lateinit var manager: AppUpdateManager

        val orphanPart = File(updatesDir, "kaihatch-v1.0.0.apk.part").apply {
            parentFile?.mkdirs()
            writeBytes("STALE_ORPHAN".toByteArray())
        }

        val transport = object : UpdateHttpTransport {
            override fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
                return UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2"))
            }

            override fun download(
                url: String,
                headers: Map<String, String>,
                targetFile: File,
                expectedTotalBytes: Long,
                onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
                isCancelled: () -> Boolean
            ) {
                targetFile.parentFile?.mkdirs()
                val half = (VALID_APK_BYTES.size / 2).coerceAtLeast(1)
                targetFile.outputStream().use { out ->
                    out.write(VALID_APK_BYTES, 0, half)
                    out.flush()
                    // Trigger concurrent launch cleanup & temp cleanup mid-stream!
                    runBlocking { manager.performLaunchCleanup() }
                    manager.cacheManager.cleanupTempFiles(app)
                    cleanupRanDuringDownload.set(true)
                    activePartExistedAfterCleanup.set(targetFile.exists())

                    out.write(VALID_APK_BYTES, half, VALID_APK_BYTES.size - half)
                    out.flush()
                }
                onProgress(VALID_APK_BYTES.size.toLong(), VALID_APK_BYTES.size.toLong())
            }
        }

        manager = AppUpdateManager(
            context = app,
            preferences = UpdatePreferences(app),
            releaseClient = GitHubReleaseClient(transport = transport),
            apkVerifier = UpdateApkVerifier(archiveInspector = FakeArchiveInspector()),
            cacheManager = UpdateCacheManager(),
            networkStatusProvider = FakeNetworkProvider(true),
            installedPackageNameProvider = { "com.biplov.kaihatch" },
            installedVersionCodeProvider = { 2L },
            installedVersionNameProvider = { "1.0.1" }
        )

        val release = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!

        val outcome = manager.downloadOrReuseUpdate(release)
        assertTrue("Cleanup should have executed mid-download", cleanupRanDuringDownload.get())
        assertTrue("Active .part file must survive concurrent cleanup", activePartExistedAfterCleanup.get())
        assertFalse("Orphaned .part file from an earlier interrupted run must be cleaned", orphanPart.exists())
        assertTrue("Download should complete and verify cleanly, got $outcome", outcome is UpdateDownloadOutcome.ReadyToInstall)
        assertTrue((outcome as UpdateDownloadOutcome.ReadyToInstall).apkFile.exists())
    }

    @Test
    fun `cold start from install notification buffers LaunchInstaller event and suppresses conflicting startup popup`() = runBlocking {
        val transport = FakeTransport(
            getHandler = { _, _ -> UpdateHttpResponse(200, buildReleaseJson(tag = "v1.0.2")) },
            downloadBytes = VALID_APK_BYTES
        )
        val manager = createManager(transport)
        AppUpdateManager.instanceOverrideForTesting = manager

        val release = GitHubReleaseClient.parseStableReleaseObject(
            org.json.JSONObject(buildReleaseJson(tag = "v1.0.2"))
        )!!
        val downloaded = manager.downloadOrReuseUpdate(release) as UpdateDownloadOutcome.ReadyToInstall
        assertTrue(downloaded.apkFile.exists())

        // Allow package installs in Robolectric so LaunchInstaller is emitted directly
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)

        val viewModel = HatchViewModel(app)

        // Simulate cold start from Install notification BEFORE any UI collector attaches to updateEvents
        viewModel.handleInstallCachedUpdateFromNotification(release.toCompactJsonString(), release.tagName)
        viewModel.initializeUpdaterOnStartup(launchedFromUpdateNotification = true)

        // Wait for ViewModel coroutines to finish BEFORE attaching the collector
        idleMainLooperUntil { !viewModel.isCheckingForUpdates.value }
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        // Attach collector late (simulating child composable attaching after parent LaunchedEffect fired)
        val collectedEvents = java.util.Collections.synchronizedList(mutableListOf<UpdateUiEvent>())
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.updateEvents.collect { collectedEvents.add(it) }
        }
        try {
            idleMainLooperUntil { collectedEvents.any { it is UpdateUiEvent.LaunchInstaller } }
        } finally {
            collectJob.cancel()
        }

        assertTrue(
            "LaunchInstaller event must be buffered and delivered to late cold-start collector",
            collectedEvents.any { it is UpdateUiEvent.LaunchInstaller && it.apkFile == downloaded.apkFile }
        )
        assertFalse(
            "Startup checking toast must be suppressed when launched from Install notification",
            collectedEvents.any {
                it is UpdateUiEvent.ShowToast && it.message == app.getString(R.string.update_toast_checking)
            }
        )
        assertTrue(
            "Conflicting UpdateDialogState.Available popup must NOT appear during Install notification launch",
            viewModel.updateDialogState.value is UpdateDialogState.Hidden
        )
    }

    private fun <T : ListenableWorker> createTestWorker(
        workerClass: Class<T>,
        inputData: Data = Data.EMPTY
    ): T {
        val factory = androidx.work.DefaultWorkerFactory
        val params = WorkerParameters::class.java.declaredConstructors.first().let { ctor ->
            ctor.isAccessible = true
            val executor = java.util.concurrent.Executor { runnable -> runnable.run() }
            val taskExecutor = object : androidx.work.impl.utils.taskexecutor.TaskExecutor {
                private val serial = androidx.work.impl.utils.SerialExecutorImpl(executor)
                override fun getMainThreadExecutor() = executor
                override fun getSerialTaskExecutor() = serial
            }
            val progressUpdater = androidx.work.ProgressUpdater { _, _, _ ->
                androidx.concurrent.futures.CallbackToFutureAdapter.getFuture { it.set(null) }
            }
            val foregroundUpdater = androidx.work.ForegroundUpdater { _, _, _ ->
                androidx.concurrent.futures.CallbackToFutureAdapter.getFuture { it.set(null) }
            }
            ctor.newInstance(
                java.util.UUID.randomUUID(),
                inputData,
                emptyList<String>(),
                WorkerParameters.RuntimeExtras(),
                1,
                0,
                executor,
                kotlinx.coroutines.Dispatchers.IO,
                taskExecutor,
                factory,
                progressUpdater,
                foregroundUpdater
            ) as WorkerParameters
        }
        return workerClass
            .getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
            .newInstance(app, params)
    }

    companion object {
        private val VALID_APK_BYTES = "PK\u0003\u0004KAIHATCH_VERIFIED_OFFICIAL_RELEASE_APK_PAYLOAD".toByteArray()
        private val VALID_APK_SHA256 = UpdateApkVerifier.sha256Hex(VALID_APK_BYTES)
        private val TRUSTED_SIGNER_SHA256 = UpdateApkVerifier.sha256Hex("KAIHATCH_RELEASE_CERT".toByteArray())
    }
}
