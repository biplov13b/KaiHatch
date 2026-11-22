package com.biplov.kaihatch.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.HatchNotificationHelper
import com.biplov.kaihatch.data.IoConsoleLogStore
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.RecoveryCardKind
import com.biplov.kaihatch.ui.resolveRecoveryCardState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Looper
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecoveryCompletionEioAndConsolePersistenceTest {

    private lateinit var app: Application
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        testRoot = File(app.cacheDir, "recovery_eio_console_test_${System.nanoTime()}").apply {
            mkdirs()
        }
        resetHooks()
        clearPersistedConsoleAndJournals()
    }

    @After
    fun tearDown() {
        resetHooks()
        clearPersistedConsoleAndJournals()
        testRoot.deleteRecursively()
    }

    private fun resetHooks() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        KaiHatchEngine.sourceCloseFaultHookForTest = null
        KaiHatchEngine.outputSyncFaultHookForTest = null
        KaiHatchEngine.sourceSyncFaultHookForTest = null
        HatchNotificationHelper.notificationEventObserverForTest = null
    }

    private fun clearPersistedConsoleAndJournals() {
        File(app.filesDir, IoConsoleLogStore.LOG_DIR_NAME).deleteRecursively()
        ResumeJournalManager.loadPendingStates(app).forEach {
            ResumeJournalManager.deleteState(it.stateFilePath)
        }
    }

    private fun createTenFileZip(zipFile: File): Map<String, ByteArray> {
        val contents = LinkedHashMap<String, ByteArray>()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (i in 1..10) {
                val name = "file_${String.format("%02d", i)}.bin"
                val data = ByteArray(16 * 1024 + i * 777) { idx ->
                    ((idx * 31 + i * 17) and 0xFF).toByte()
                }
                contents[name] = data
                val entry = ZipEntry(name)
                entry.method = ZipEntry.DEFLATED
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return contents
    }

    /**
     * Simulates a real mid-extraction crash after the first 4 entries have been extracted & truncated,
     * leaving 6 remaining entries and a valid persisted ResumeJournalState on disk.
     */
    private suspend fun createInterruptedTenFileSession(
        archiveName: String
    ): Triple<File, File, Map<String, ByteArray>> {
        val archiveFile = File(testRoot, archiveName)
        val targetDir = File(testRoot, "${archiveName.removeSuffix(".zip")}_extracted").apply { mkdirs() }
        val expectedContents = createTenFileZip(archiveFile)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_JOURNAL_SAVE,
            triggerOnOccurrence = 4
        )

        try {
            KaiHatchEngine.extractArchive(
                archiveFile = archiveFile,
                targetDir = targetDir,
                isHatchMode = true,
                context = app
            ).toList()
        } catch (_: SimulatedFaultException) {
            // Expected simulated process death
        } finally {
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        }

        return Triple(archiveFile, targetDir, expectedContents)
    }

    @Test
    fun testSuccessfulResumeCompletesAllTenFilesDeletesSourceAndJournalAndEmitsSingleCompleted() = runBlocking {
        val (archiveFile, targetDir, expectedContents) = createInterruptedTenFileSession("ten_files_ok.zip")

        val pending = ResumeJournalManager.loadPendingStates(app)
            .firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull("Expected interrupted recovery journal to exist before resume", pending)
        assertTrue("Expected interrupted archive to still exist on disk", archiveFile.exists())

        val events = KaiHatchEngine.resumeInterruptedExtraction(
            context = app,
            resumeState = pending!!
        ).toList()

        val completedEvents = events.filter { it.status == ExtractionStatus.COMPLETED }
        val errorEvents = events.filter { it.status == ExtractionStatus.ERROR }
        assertEquals("Expected exactly one COMPLETED terminal event", 1, completedEvents.size)
        assertTrue("Expected zero ERROR events on clean resume, got: $errorEvents", errorEvents.isEmpty())
        assertEquals(ExtractionStatus.COMPLETED, events.last().status)

        // All 10 extracted files must exist and match exact bytes + CRC32
        assertEquals(10, expectedContents.size)
        expectedContents.forEach { (name, expectedBytes) ->
            val outFile = File(targetDir, name)
            assertTrue("Expected extracted file '$name' to exist", outFile.exists())
            assertArrayEquals("Byte mismatch for '$name'", expectedBytes, outFile.readBytes())
        }

        // Source archive and recovery journal cleaned up only after clean descriptor close
        assertFalse("Source archive should be deleted after clean close", archiveFile.exists())
        assertFalse(
            "Recovery journal should be deleted after verified completion",
            File(pending.stateFilePath).exists()
        )
    }

    @Test
    fun testResumeCloseEioNotSuppressedNoCompletedAndJournalPreserved() = runBlocking {
        val (archiveFile, targetDir, expectedContents) = createInterruptedTenFileSession("ten_files_eio.zip")

        val pending = ResumeJournalManager.loadPendingStates(app)
            .firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull(pending)

        KaiHatchEngine.sourceCloseFaultHookForTest = { name ->
            if (name == archiveFile.name) IOException("close failed: EIO (I/O error)") else null
        }

        val events = KaiHatchEngine.resumeInterruptedExtraction(
            context = app,
            resumeState = pending!!
        ).toList()

        val completedEvents = events.filter { it.status == ExtractionStatus.COMPLETED }
        val errorEvents = events.filter { it.status == ExtractionStatus.ERROR }

        assertTrue("COMPLETED must NEVER be emitted when source close throws EIO", completedEvents.isEmpty())
        assertEquals("Exactly one ERROR event must be emitted on close EIO", 1, errorEvents.size)
        val err = errorEvents.single()
        assertTrue(
            "Error message must surface close EIO without suppressing it: ${err.errorMessage}",
            err.errorMessage?.contains("close failed: EIO (I/O error)") == true
        )
        assertTrue(
            "Error journalLogs must retain the recovery error entry",
            err.journalLogs.any { it.contains("[RECOVERY ERROR]") && it.contains("close failed: EIO") }
        )
        assertTrue(
            "Recovery journal MUST be preserved when final close fails with EIO",
            File(pending.stateFilePath).exists()
        )
        assertTrue(
            "Source archive must not be deleted when descriptor close fails with EIO",
            archiveFile.exists()
        )
        assertEquals("Source archive should already be truncated to 0 bytes before close", 0L, archiveFile.length())
        assertEquals("Error telemetry must preserve completed count", 10, err.completedEntries)
        assertEquals("Error telemetry must preserve total count", 10, err.totalEntries)

        // All 10 extracted files written prior to source close must remain byte-identical on disk
        expectedContents.forEach { (name, expectedBytes) ->
            assertArrayEquals(expectedBytes, File(targetDir, name).readBytes())
        }

        // Reload preserved journal: must NEVER produce a misleading Resumable state or Resume button
        KaiHatchEngine.sourceCloseFaultHookForTest = null
        val reloaded = ResumeJournalManager.loadPendingStates(app)
            .firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull("Expected preserved recovery journal to be reloadable", reloaded)
        assertEquals("Preserved journal must record all 10 verified entries", 10, reloaded!!.completedPaths.size)
        assertTrue("Preserved journal must record 0 remaining entries", reloaded.remainingEntries.isEmpty())

        val resumeElig = ResumeJournalManager.assessResumeEligibility(reloaded, app)
        val restoreElig = ResumeJournalManager.assessRestoreEligibility(reloaded, app)
        assertTrue(
            "Zero-truncated post-close-EIO state must NEVER be reported as Resumable, got: $resumeElig",
            resumeElig is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertTrue(
            "Zero-truncated post-close-EIO state must not be restorable, got: $restoreElig",
            restoreElig is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )
        val card = resolveRecoveryCardState(reloaded, resumeElig, restoreElig)
        assertEquals(RecoveryCardKind.UNAVAILABLE, card.kind)
        assertFalse("Resume button must not be shown after final zero-truncation", card.showResumeButton)
        assertFalse("Restore button must not be shown after final zero-truncation", card.showRestoreButton)
        assertFalse("Direct-POSIX final close failure should not be misclassified as SAF limitation", card.isSafDirectPosixLimitation)

        // Direct engine resume attempt must fail closed without touching verified outputs
        val retryEvents = KaiHatchEngine.resumeInterruptedExtraction(app, reloaded).toList()
        assertEquals(ExtractionStatus.ERROR, retryEvents.last().status)
        assertTrue(retryEvents.none { it.status == ExtractionStatus.COMPLETED })
        expectedContents.forEach { (name, expectedBytes) ->
            assertArrayEquals("Verified output '$name' must remain intact", expectedBytes, File(targetDir, name).readBytes())
        }

        // If an extracted file is externally corrupted on disk, eligibility must report CRC corruption
        val corruptedFile = File(targetDir, expectedContents.keys.first())
        val originalBytes = corruptedFile.readBytes()
        corruptedFile.writeBytes(ByteArray(originalBytes.size) { 0x5A })
        val corruptElig = ResumeJournalManager.assessResumeEligibility(reloaded, app)
        assertTrue(corruptElig is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertTrue(
            "Corrupted output must be caught by CRC32 verification even when archive is 0 bytes: $corruptElig",
            (corruptElig as ResumeJournalManager.ResumeEligibility.Unrecoverable).reason.contains("CRC32", ignoreCase = true)
        )
        corruptedFile.writeBytes(originalBytes)
    }

    @Test
    fun testFinalSourceFsyncFailureNotSuppressedNoCompletedAndJournalPreserved() = runBlocking {
        val (archiveFile, targetDir, expectedContents) = createInterruptedTenFileSession("ten_files_src_fsync_fail.zip")
        val pending = ResumeJournalManager.loadPendingStates(app)
            .firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull(pending)

        KaiHatchEngine.sourceSyncFaultHookForTest = { name ->
            if (name == archiveFile.name) false else null
        }

        val events = KaiHatchEngine.resumeInterruptedExtraction(
            context = app,
            resumeState = pending!!
        ).toList()

        assertTrue("COMPLETED must not be emitted when final source fsync fails", events.none { it.status == ExtractionStatus.COMPLETED })
        val errorEvents = events.filter { it.status == ExtractionStatus.ERROR }
        assertEquals(1, errorEvents.size)
        assertTrue(
            "Expected fsync failure details in errorMessage: ${errorEvents.single().errorMessage}",
            errorEvents.single().errorMessage?.contains("fsync", ignoreCase = true) == true
        )
        assertEquals("Error telemetry must report 0 bytes on disk after truncate(0)", 0L, errorEvents.single().currentArchiveBytesOnDisk)
        assertTrue(
            "Recovery journal must be preserved on final source fsync failure",
            File(pending.stateFilePath).exists()
        )

        // All 10 verified extracted files remain intact on disk
        expectedContents.forEach { (name, expectedBytes) ->
            assertArrayEquals(expectedBytes, File(targetDir, name).readBytes())
        }

        // Reloaded journal must never appear resumable and dismissing it must keep extracted files intact
        KaiHatchEngine.sourceSyncFaultHookForTest = null
        val reloaded = ResumeJournalManager.loadPendingStates(app)
            .firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull(reloaded)
        assertEquals(10, reloaded!!.completedPaths.size)
        assertTrue(reloaded.remainingEntries.isEmpty())

        val resumeElig = ResumeJournalManager.assessResumeEligibility(reloaded, app)
        val restoreElig = ResumeJournalManager.assessRestoreEligibility(reloaded, app)
        assertTrue(
            "Final source fsync failure after truncate(0) must never produce Resumable state: $resumeElig",
            resumeElig is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        val card = resolveRecoveryCardState(reloaded, resumeElig, restoreElig)
        assertEquals(RecoveryCardKind.UNAVAILABLE, card.kind)
        assertFalse(card.showResumeButton)
        assertFalse(card.showRestoreButton)

        // Dismissing the recovery notice removes only the journal and keeps all verified outputs
        val vm = HatchViewModel(app)
        vm.dismissResumeState(reloaded)
        waitUntil(timeoutMs = 5_000L) { !File(reloaded.stateFilePath).exists() }
        expectedContents.forEach { (name, expectedBytes) ->
            assertArrayEquals(expectedBytes, File(targetDir, name).readBytes())
        }
    }

    @Test
    fun testOutputFsyncFailureInHatchModeAbortsAndPreservesSourceAndJournal() = runBlocking {
        val archiveFile = File(testRoot, "output_fsync_fail.zip")
        val targetDir = File(testRoot, "output_fsync_fail_extracted").apply { mkdirs() }
        createTenFileZip(archiveFile)

        KaiHatchEngine.outputSyncFaultHookForTest = { entryName ->
            if (entryName.contains("file_05.bin")) false else null
        }

        val events = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = targetDir,
            isHatchMode = true,
            context = app
        ).toList()

        assertTrue("COMPLETED must not be emitted when output fsync fails", events.none { it.status == ExtractionStatus.COMPLETED })
        val errorEvents = events.filter { it.status == ExtractionStatus.ERROR }
        assertTrue("Expected ERROR when output fsync fails", errorEvents.isNotEmpty())
        assertTrue(
            "Error message must explain output fsync failure: ${errorEvents.last().errorMessage}",
            errorEvents.last().errorMessage?.contains("fsync", ignoreCase = true) == true
        )
        assertTrue("Source archive must still exist after output fsync failure", archiveFile.exists())
        val stateFile = ResumeJournalManager.getStateFileForArchive(app, archiveFile.name)
        assertTrue("Recovery journal must be preserved after output fsync failure", stateFile.exists())
    }

    @Test
    fun testNotificationConsistencyOnResumeSuccessVsCloseEioError() = runBlocking {
        // Case 1: Clean Resume -> notification reports completed, zero error notifications
        val (okArchive, _, _) = createInterruptedTenFileSession("notif_resume_ok.zip")
        val okPending = ResumeJournalManager.loadPendingStates(app)
            .first { it.archiveName == okArchive.name }

        val okNotifEvents = CopyOnWriteArrayList<Triple<String, String, String>>()
        HatchNotificationHelper.notificationEventObserverForTest = { event, title, msg ->
            okNotifEvents.add(Triple(event, title, msg))
        }

        val vm1 = HatchViewModel(app)
        vm1.resumeInterruptedTask(okPending)
        waitUntil(timeoutMs = 15_000L) {
            vm1.extractionProgress.value.status == ExtractionStatus.COMPLETED ||
                vm1.extractionProgress.value.status == ExtractionStatus.ERROR
        }
        // Allow IO notification coroutine to dispatch
        waitUntil(timeoutMs = 5_000L) { okNotifEvents.any { it.first == "completed" } }

        assertEquals(ExtractionStatus.COMPLETED, vm1.extractionProgress.value.status)
        assertTrue("Expected completed notification on success", okNotifEvents.any { it.first == "completed" })
        assertTrue("Expected zero error notifications on success", okNotifEvents.none { it.first == "error" })

        // Case 2: Resume hits close failed: EIO -> notification reports error, ZERO completed notifications
        val (eioArchive, _, _) = createInterruptedTenFileSession("notif_resume_eio.zip")
        val eioPending = ResumeJournalManager.loadPendingStates(app)
            .first { it.archiveName == eioArchive.name }

        val eioNotifEvents = CopyOnWriteArrayList<Triple<String, String, String>>()
        HatchNotificationHelper.notificationEventObserverForTest = { event, title, msg ->
            eioNotifEvents.add(Triple(event, title, msg))
        }
        KaiHatchEngine.sourceCloseFaultHookForTest = { name ->
            if (name == eioArchive.name) IOException("close failed: EIO (I/O error)") else null
        }

        vm1.resumeInterruptedTask(eioPending)
        waitUntil(timeoutMs = 15_000L) {
            vm1.extractionProgress.value.status == ExtractionStatus.ERROR &&
                vm1.extractionProgress.value.archiveName == eioArchive.name
        }
        waitUntil(timeoutMs = 5_000L) { eioNotifEvents.any { it.first == "error" } }

        assertEquals(ExtractionStatus.ERROR, vm1.extractionProgress.value.status)
        assertTrue("Notification must NEVER show completed when close EIO occurs", eioNotifEvents.none { it.first == "completed" })
        assertTrue("Notification must report error when close EIO occurs", eioNotifEvents.any { it.first == "error" })
        assertTrue("Stale completion notification must be cancelled on error", eioNotifEvents.any { it.first == "cancel_completion" })
    }

    @Test
    fun testIoConsoleLogStoreDeduplicatesSlidingSnapshotsEnforcesBoundsAndPersists() {
        val logFile = File(testRoot, "console_store/session.log")
        val store = IoConsoleLogStore(
            logFile = logFile,
            maxLines = 20,
            maxBytes = 4096,
            debounceFlushMs = 0L
        )

        // Initial cumulative engine snapshot
        val first = store.appendEngineSnapshot(1L, listOf("L1", "L2", "L3"), flushImmediately = true)
        assertEquals(listOf("L1", "L2", "L3"), first)

        // Exact repeat snapshot returns null (prevents redundant Compose recomposition!)
        val repeat = store.appendEngineSnapshot(1L, listOf("L1", "L2", "L3"))
        assertNull("Repeated identical snapshot should return null", repeat)

        // Growing snapshot appends ONLY new lines
        val grown = store.appendEngineSnapshot(1L, listOf("L1", "L2", "L3", "L4", "L5"))
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), grown)

        // Sliding-window snapshot (front dropped, new tail added) appends ONLY new tail without duplicates
        val slid = store.appendEngineSnapshot(1L, listOf("L3", "L4", "L5", "L6", "L7"))
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5", "L6", "L7"), slid)

        // Enforce bounded retention (maxLines = 20)
        val many = (8..40).map { "L$it" }
        val bounded = store.appendLines(many, flushImmediately = true)
        assertNotNull(bounded)
        assertEquals(20, bounded!!.size)
        assertEquals("L21", bounded.first())
        assertEquals("L40", bounded.last())

        store.flushNowSync()
        assertTrue("Persisted log file must exist", logFile.exists())

        // Reload in a brand-new instance (simulating app restart / force-stop)
        val reloadedStore = IoConsoleLogStore(logFile = logFile, maxLines = 20, maxBytes = 4096)
        val reloaded = reloadedStore.loadPersistedSync()
        assertEquals(bounded, reloaded)

        // Explicit clear removes both memory and disk file
        val cleared = reloadedStore.clear()
        assertTrue(cleared.isEmpty())
        reloadedStore.flushNowSync()
        assertFalse("Persisted log file should be removed after explicit clear", logFile.exists())

        val afterClearStore = IoConsoleLogStore(logFile = logFile, maxLines = 20, maxBytes = 4096)
        assertTrue(afterClearStore.loadPersistedSync().isEmpty())
    }

    @Test
    fun testConsoleLogsPreservedAcrossStatesRefreshesRestartAndClearedOnlyExplicitly() = runBlocking {
        val archive1 = File(testRoot, "session_one.zip")
        createTenFileZip(archive1)
        val corruptArchive = File(testRoot, "corrupt_file.zip").apply {
            writeText("not-a-zip-archive")
        }

        val vm1 = HatchViewModel(app)
        vm1.setHatchMode(false) // Standard mode so archive1 stays on disk for refresh checks

        // 1. Select archive 1 -> Indexed logs recorded
        vm1.selectArchive(archive1)
        waitUntil(timeoutMs = 10_000L) {
            vm1.archiveSummary.value?.fileName == archive1.name &&
                vm1.consoleLogs.value.any { it.contains("[Ready] Indexed ${archive1.name}") }
        }
        val readyLine = vm1.consoleLogs.value.first { it.contains("[Ready] Indexed ${archive1.name}") }

        // 2. Run extraction to completion -> Prior [Ready] log AND extraction completion logs BOTH preserved
        vm1.startExtraction(requirePersistentFolder = false)
        waitUntil(timeoutMs = 15_000L) {
            vm1.extractionProgress.value.status == ExtractionStatus.COMPLETED
        }
        val logsAfterComplete = vm1.consoleLogs.value
        assertTrue("Prior [Ready] log must remain after extraction completes", logsAfterComplete.contains(readyLine))
        assertTrue(
            "Extraction start log must remain after completion",
            logsAfterComplete.any { it.contains("[Extract Start] ${archive1.name}") }
        )
        assertTrue(
            "Completion log must remain after extraction finishes",
            logsAfterComplete.any { it.contains("COMPLETE", ignoreCase = true) }
        )

        // 3. Dismiss status card & deselect archive -> ZERO logs erased!
        val countAfterComplete = logsAfterComplete.size
        vm1.dismissExtractionStatusCard()
        vm1.deselectArchive()
        assertEquals(
            "Dismissing status card and deselecting archive must NOT erase console logs",
            countAfterComplete,
            vm1.consoleLogs.value.size
        )
        assertEquals(vm1.consoleLogs.value, vm1.extractionProgress.value.journalLogs)

        // 4. Open corrupt archive (Error) -> Previous session logs AND new error logs BOTH preserved!
        vm1.selectArchive(corruptArchive)
        waitUntil(timeoutMs = 10_000L) {
            vm1.extractionProgress.value.status == ExtractionStatus.ERROR &&
                vm1.consoleLogs.value.any { it.contains("[Archive Open Error]") && it.contains(corruptArchive.name) }
        }
        val logsAfterError = vm1.consoleLogs.value
        assertTrue("Previous [Ready] log must still exist after error", logsAfterError.contains(readyLine))
        assertTrue("Previous COMPLETE log must still exist after error", logsAfterError.any { it.contains("COMPLETE", ignoreCase = true) })
        assertTrue("New error log must be appended", logsAfterError.any { it.contains("[Archive Open Error]") })

        // 5. Refresh/re-select archive1 -> ZERO logs erased!
        vm1.selectArchive(archive1)
        waitUntil(timeoutMs = 10_000L) {
            vm1.archiveSummary.value?.fileName == archive1.name
        }
        assertTrue(
            "Archive refresh must preserve earlier error logs and completion logs",
            vm1.consoleLogs.value.any { it.contains("[Archive Open Error]") } &&
                vm1.consoleLogs.value.any { it.contains("COMPLETE", ignoreCase = true) }
        )

        // 6. Pause / Resume / Stop control transitions preserve logs
        vm1.startExtraction(requirePersistentFolder = false)
        vm1.pauseExtraction()
        vm1.resumeExtraction()
        vm1.cancelExtraction()
        waitUntil(timeoutMs = 10_000L) {
            vm1.consoleLogs.value.any { it.contains("[Control] Stop requested") }
        }
        val logsBeforeRestart = vm1.consoleLogs.value
        assertTrue(logsBeforeRestart.any { it.contains("[Control] Pause requested") })
        assertTrue(logsBeforeRestart.any { it.contains("[Control] Resumed extraction") })
        assertTrue(logsBeforeRestart.any { it.contains("[Control] Stop requested") })
        assertTrue(logsBeforeRestart.contains(readyLine))
        vm1.flushConsoleLogsForTest()

        // 7. Simulate app restart / force-stop by instantiating a brand-new HatchViewModel
        val vmRestarted = HatchViewModel(app)
        waitUntil(timeoutMs = 10_000L) {
            vmRestarted.consoleLogs.value.size >= logsBeforeRestart.size
        }
        assertEquals(
            "Complete session console log history must survive app restart / force-stop",
            logsBeforeRestart,
            vmRestarted.consoleLogs.value
        )

        // 8. Clear logs ONLY through explicit user action
        vmRestarted.clearConsoleLogs()
        assertTrue("Explicit clear must empty consoleLogs", vmRestarted.consoleLogs.value.isEmpty())
        assertTrue("Explicit clear must empty extractionProgress.journalLogs", vmRestarted.extractionProgress.value.journalLogs.isEmpty())
        vmRestarted.flushConsoleLogsForTest()

        val vmAfterExplicitClear = HatchViewModel(app)
        Thread.sleep(200)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "Logs must remain empty on next restart after explicit user clear",
            vmAfterExplicitClear.consoleLogs.value.isEmpty()
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out after ${timeoutMs}ms waiting for condition", condition())
    }
}
