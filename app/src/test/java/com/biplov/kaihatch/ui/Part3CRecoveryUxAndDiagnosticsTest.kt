package com.biplov.kaihatch.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.engine.DestructivePoint
import com.biplov.kaihatch.engine.KaiHatchEngine
import com.biplov.kaihatch.engine.SimulatedFaultException
import com.biplov.kaihatch.engine.TruncationFaultInjector
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ResumeJournalState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import android.os.Looper
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Part 3C UX & Diagnostics test suite:
 * Verifies:
 * 1. Recovery card visibility and state classification across all verified eligibility combinations
 *    (BOTH_AVAILABLE, RESUME_ONLY, RESTORE_PENDING, UNAVAILABLE, SAF/direct-POSIX limitations).
 * 2. Meaningful archive-open failure diagnostics in the I/O Console (including Recovery Notice
 *    when opening a partially consumed archive that has an active recovery journal).
 * 3. Recovery failure diagnostics, non-empty I/O Console logs on ERROR/FINALIZING, and
 *    ViewModel FINALIZING -> CANCELLED transitions during Part 3B Restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Part3CRecoveryUxAndDiagnosticsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
        File(app.filesDir, "journals").deleteRecursively()
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
        File(app.filesDir, "journals").deleteRecursively()
    }

    private fun generateMixedDeflatePayload(sizeBytes: Int, seed: Long): ByteArray {
        val rng = Random(seed)
        val out = ByteArray(sizeBytes)
        var pos = 0
        while (pos < sizeBytes) {
            val block = (sizeBytes - pos).coerceAtMost(4096)
            when ((pos / 4096) % 4) {
                0 -> {
                    val v = (seed.toInt() + pos).toByte()
                    for (i in 0 until block) out[pos + i] = ((v.toInt() + (i % 19)) and 0xFF).toByte()
                }
                else -> {
                    val tmp = ByteArray(block)
                    rng.nextBytes(tmp)
                    System.arraycopy(tmp, 0, out, pos, block)
                }
            }
            pos += block
        }
        return out
    }

    private class SingleShotFault(
        private val point: DestructivePoint,
        private val onOccurrence: Int = 1
    ) : TruncationFaultInjector {
        private val hits = AtomicInteger(0)
        override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
            if (point == this.point && hits.incrementAndGet() == onOccurrence) {
                throw SimulatedFaultException(point, onOccurrence, currentArchiveBytes, "Part3C fault at $point")
            }
        }
    }

    private fun createInterruptedMultiEntryTailSwap(tag: String): Triple<File, File, ResumeJournalState> = runBlocking {
        val archiveFile = tempFolder.newFile("p3c_${tag}.zip")
        val outputDir = tempFolder.newFolder("p3c_out_${tag}")
        val smallPayload = "Remaining intact entry for $tag\n".repeat(256).toByteArray(Charsets.UTF_8)
        val largePayload = generateMixedDeflatePayload(7_500_000, 71001L + tag.hashCode())

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry("keep/remaining.txt"))
            zos.write(smallPayload)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("stream/large_deflate.bin"))
            zos.write(largePayload)
            zos.closeEntry()
        }

        KaiHatchEngine.faultInjector = SingleShotFault(DestructivePoint.AFTER_TAILSWAP_TRUNCATE, 1)
        val run = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = app
        ).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertEquals(ExtractionStatus.ERROR, run.last().status)

        val saved = ResumeJournalManager.loadPendingStates(app)
            .first { it.archivePath == archiveFile.absolutePath }
        Triple(archiveFile, outputDir, saved)
    }

    // =========================================================================
    // 1. Recovery Card Visibility, Misleading Title Replacement, SAF Limitations
    // =========================================================================

    @Test
    fun recoveryCardState_verifiedMultiEntryTailSwap_showsBothResumeAndRestore() {
        val (_, _, saved) = createInterruptedMultiEntryTailSwap("both_avail")

        val resumeElig = ResumeJournalManager.assessResumeEligibility(saved, app)
        val restoreElig = ResumeJournalManager.assessRestoreEligibility(saved, app)
        val card = resolveRecoveryCardState(saved, resumeElig, restoreElig)

        assertEquals(RecoveryCardKind.BOTH_AVAILABLE, card.kind)
        assertTrue("Resume button must be visible", card.showResumeButton)
        assertTrue("Restore Remaining ZIP button must be visible", card.showRestoreButton)
        assertFalse(card.isPendingRestoreRetry)
        assertEquals("stream/large_deflate.bin", card.pendingDeflateTailSwapEntryPath)
        assertEquals(1, card.restoreRemainingCount)
        assertFalse(card.isSafDirectPosixLimitation)
    }

    @Test
    fun recoveryCardState_singleEntryTailSwap_showsResumeOnlyWithClearRestoreExplanation() = runBlocking {
        val archiveFile = tempFolder.newFile("p3c_single.zip")
        val outputDir = tempFolder.newFolder("p3c_out_single")
        val largePayload = generateMixedDeflatePayload(7_500_000, 72001L)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry("only/huge.bin"))
            zos.write(largePayload)
            zos.closeEntry()
        }

        KaiHatchEngine.faultInjector = SingleShotFault(DestructivePoint.AFTER_TAILSWAP_TRUNCATE, 1)
        KaiHatchEngine.extractArchive(archiveFile, outputDir, true, context = app).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val saved = ResumeJournalManager.loadPendingStates(app)
            .first { it.archivePath == archiveFile.absolutePath }
        val card = resolveRecoveryCardState(
            resumeState = saved,
            resumeEligibility = ResumeJournalManager.assessResumeEligibility(saved, app),
            restoreEligibility = ResumeJournalManager.assessRestoreEligibility(saved, app)
        )

        assertEquals(RecoveryCardKind.RESUME_ONLY, card.kind)
        assertTrue("Resume must be shown for single-entry archive", card.showResumeButton)
        assertFalse("Restore must be hidden when 0 remaining entries exist", card.showRestoreButton)
        assertNotNull("Secondary reason must explain why Restore is unavailable", card.secondaryReason)
        assertTrue(card.secondaryReason!!.contains("final remaining entry", ignoreCase = true))
    }

    @Test
    fun recoveryCardState_corruptedCheckpointAndSafLimitations_neverShowsReadyTitleAndExplainsClearly() {
        val (_, _, saved) = createInterruptedMultiEntryTailSwap("unavail_and_saf")

        // Case A: SAF source URI limitation -> UNAVAILABLE with explicit SAF/direct-POSIX flag & explanation
        val safSrcState = saved.copy(
            sourceUriString = "content://com.android.externalstorage.documents/document/primary%3Atest.zip"
        )
        val safSrcCard = resolveRecoveryCardState(
            resumeState = safSrcState,
            resumeEligibility = ResumeJournalManager.assessResumeEligibility(safSrcState, app),
            restoreEligibility = ResumeJournalManager.assessRestoreEligibility(safSrcState, app)
        )
        assertEquals(RecoveryCardKind.UNAVAILABLE, safSrcCard.kind)
        assertFalse(safSrcCard.showResumeButton)
        assertFalse(safSrcCard.showRestoreButton)
        assertTrue("SAF source limitation must set isSafDirectPosixLimitation", safSrcCard.isSafDirectPosixLimitation)
        assertNotNull(safSrcCard.unavailableReason)
        assertTrue(safSrcCard.unavailableReason!!.contains("direct-POSIX", ignoreCase = true))

        // Case B: SAF-only destination limitation -> UNAVAILABLE with explicit SAF/direct-POSIX flag
        val safDestState = saved.copy(
            targetDirPath = File(app.filesDir, "saf_fallback_p3c").absolutePath,
            targetTreeUriString = "content://com.android.externalstorage.documents/tree/primary%3AOut"
        )
        val safDestCard = resolveRecoveryCardState(
            resumeState = safDestState,
            resumeEligibility = ResumeJournalManager.assessResumeEligibility(safDestState, app),
            restoreEligibility = ResumeJournalManager.assessRestoreEligibility(safDestState, app)
        )
        assertEquals(RecoveryCardKind.UNAVAILABLE, safDestCard.kind)
        assertFalse(safDestCard.showResumeButton)
        assertFalse(safDestCard.showRestoreButton)
        assertTrue("SAF destination limitation must set isSafDirectPosixLimitation", safDestCard.isSafDirectPosixLimitation)

        // Case C: Corrupted checkpoint -> UNAVAILABLE (never BOTH_AVAILABLE / "Interrupted Unpack Ready")
        val ckptFile = ResumeJournalManager.getDeflateTailSwapCheckpointFile(saved.stateFilePath)
        val bakFile = File("${ckptFile.absolutePath}.bak")
        ckptFile.writeBytes("CORRUPT_PART3C".toByteArray())
        if (bakFile.exists()) bakFile.writeBytes("CORRUPT_BAK_PART3C".toByteArray())

        val corruptCard = resolveRecoveryCardState(
            resumeState = saved,
            resumeEligibility = ResumeJournalManager.assessResumeEligibility(saved, app),
            restoreEligibility = ResumeJournalManager.assessRestoreEligibility(saved, app)
        )
        assertEquals(RecoveryCardKind.UNAVAILABLE, corruptCard.kind)
        assertFalse(corruptCard.showResumeButton)
        assertFalse(corruptCard.showRestoreButton)
        assertNotNull(corruptCard.unavailableReason)
        assertTrue(corruptCard.unavailableReason!!.contains("corrupt", ignoreCase = true))
    }

    @Test
    fun recoveryCardState_interruptedStage2Restore_showsRestorePendingAndHidesResume() = runBlocking {
        val (archiveFile, _, saved) = createInterruptedMultiEntryTailSwap("restore_pending")

        val faultErr = runCatching {
            KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = saved,
                faultInjector = SingleShotFault(DestructivePoint.MID_RESTORE_CD_WRITE, 1)
            ).toList()
        }.exceptionOrNull()
        assertTrue("Must fault during Stage 2 CD write, got $faultErr", faultErr is SimulatedFaultException)

        val reloaded = ResumeJournalManager.loadPendingStates(app)
            .first { it.archivePath == archiveFile.absolutePath }
        val card = resolveRecoveryCardState(
            resumeState = reloaded,
            resumeEligibility = ResumeJournalManager.assessResumeEligibility(reloaded, app),
            restoreEligibility = ResumeJournalManager.assessRestoreEligibility(reloaded, app)
        )

        assertEquals(RecoveryCardKind.RESTORE_PENDING, card.kind)
        assertFalse("Resume must be hidden while Central Directory rewrite is pending", card.showResumeButton)
        assertTrue("Complete Interrupted Restoration button must be visible", card.showRestoreButton)
        assertTrue(card.isPendingRestoreRetry)
    }

    @Test
    fun recoveryCardState_nonZipFormat_showsResumeOnlyAndUnsupportedRestoreReason() {
        val dummy7z = tempFolder.newFile("sample.7z").apply { writeBytes(ByteArray(1024)) }
        val dummyState = ResumeJournalState(
            stateFilePath = tempFolder.newFile("dummy.hatchstate.json").absolutePath,
            archivePath = dummy7z.absolutePath,
            archiveName = "sample.7z",
            targetDirPath = tempFolder.newFolder("out_7z").absolutePath,
            totalEntries = 3,
            completedPaths = setOf("a.txt"),
            remainingEntries = emptyList(),
            originalArchiveBytes = 1024L,
            currentArchiveBytes = 1024L,
            bytesReclaimedSoFar = 0L
        )
        val card = resolveRecoveryCardState(
            resumeState = dummyState,
            resumeEligibility = ResumeJournalManager.ResumeEligibility.Resumable,
            restoreEligibility = ResumeJournalManager.assessRestoreEligibility(dummyState, app)
        )
        assertEquals(RecoveryCardKind.RESUME_ONLY, card.kind)
        assertTrue(card.showResumeButton)
        assertFalse(card.showRestoreButton)
        assertNotNull(card.secondaryReason)
        assertTrue(card.secondaryReason!!.contains("7Z", ignoreCase = true))
    }

    // =========================================================================
    // 2. Archive-Open & Recovery Failure Diagnostics in I/O Console
    // =========================================================================

    @Test
    fun viewModel_openCorruptedOrInterruptedLocalArchive_logsMeaningfulDiagnosticsToIoConsole() = runBlocking {
        val (interruptedZip, _, _) = createInterruptedMultiEntryTailSwap("open_corrupt")

        val vm = HatchViewModel(app)
        vm.refreshPendingResumeStates()
        val refreshDeadline = System.currentTimeMillis() + 3_000L
        while (vm.pendingResumeStates.value.isEmpty() && System.currentTimeMillis() < refreshDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(30)
        }

        // Opening the partially consumed ZIP (missing EOCD) must fail cleanly AND log cause + recovery notice
        vm.selectArchive(interruptedZip)
        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.extractionProgress.value.status != ExtractionStatus.ERROR && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(40)
        }

        val errProgress = vm.extractionProgress.value
        assertEquals(ExtractionStatus.ERROR, errProgress.status)
        assertNotNull(errProgress.errorMessage)
        assertTrue(
            "I/O Console must show [Archive Open Error], got: ${errProgress.journalLogs}",
            errProgress.journalLogs.any { it.contains("[Archive Open Error]") }
        )
        assertTrue(
            "I/O Console must show [Cause], got: ${errProgress.journalLogs}",
            errProgress.journalLogs.any { it.contains("[Cause]") }
        )
        assertTrue(
            "I/O Console must show [Recovery Notice] when an interrupted journal exists, got: ${errProgress.journalLogs}",
            errProgress.journalLogs.any { it.contains("[Recovery Notice]") }
        )

        // Opening an unrecognized SAF URI must also populate I/O Console logs (never empty)
        vm.selectSafUriZeroCopy(Uri.parse("content://com.biplov.kaihatch.invalid.provider/document/missing.zip"))
        val safDeadline = System.currentTimeMillis() + 5_000L
        while (
            (vm.extractionProgress.value.status != ExtractionStatus.ERROR || !vm.extractionProgress.value.isZeroCopySaf) &&
            System.currentTimeMillis() < safDeadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(40)
        }
        val safErr = vm.extractionProgress.value
        assertEquals(ExtractionStatus.ERROR, safErr.status)
        assertTrue(
            "SAF open error must populate I/O Console logs, got: ${safErr.journalLogs}",
            safErr.journalLogs.any { it.contains("[Archive Open Error]") }
        )
    }

    @Test
    fun engineAndViewModel_recoveryFailuresAndFinalizingRestore_populateAndPreserveConsoleLogs() = runBlocking {
        // 1. Missing archive on Resume must emit non-empty [RECOVERY ERROR] logs
        val missingState = ResumeJournalState(
            stateFilePath = tempFolder.newFile("missing.hatchstate.json").absolutePath,
            archivePath = File(tempFolder.root, "does_not_exist.zip").absolutePath,
            archiveName = "does_not_exist.zip",
            targetDirPath = tempFolder.newFolder("missing_out").absolutePath,
            totalEntries = 2,
            completedPaths = emptySet(),
            remainingEntries = emptyList(),
            originalArchiveBytes = 4096L,
            currentArchiveBytes = 4096L,
            bytesReclaimedSoFar = 0L
        )
        val missingRun = KaiHatchEngine.resumeInterruptedExtraction(app, missingState).toList()
        assertEquals(ExtractionStatus.ERROR, missingRun.last().status)
        assertTrue(
            "Missing-archive resume error must populate journalLogs",
            missingRun.last().journalLogs.isNotEmpty()
        )

        // 2. ViewModel.restoreInterruptedZip on recoverable v2 tail-swap enters FINALIZING with logs and completes cleanly
        val (archiveFile, _, saved) = createInterruptedMultiEntryTailSwap("vm_restore_finalizing")
        val vm = HatchViewModel(app)
        val observedStatuses = mutableListOf<ExtractionStatus>()
        val collectJob = launch(Dispatchers.Default) {
            vm.extractionProgress.collect { p ->
                observedStatuses.add(p.status)
            }
        }
        try {
            vm.restoreInterruptedZip(saved)
            // Immediately after calling restoreInterruptedZip, status must be FINALIZING with non-empty console logs
            assertEquals(ExtractionStatus.FINALIZING, vm.extractionProgress.value.status)
            assertTrue(
                "Initial FINALIZING state must seed I/O Console logs",
                vm.extractionProgress.value.journalLogs.any { it.contains("[RESTORE]") }
            )

            val deadline = System.currentTimeMillis() + 15_000L
            while (vm.extractionProgress.value.status != ExtractionStatus.CANCELLED &&
                vm.extractionProgress.value.status != ExtractionStatus.ERROR &&
                System.currentTimeMillis() < deadline
            ) {
                delay(50)
            }

            val finalProgress = vm.extractionProgress.value
            assertEquals(
                "ViewModel restore must finish with CANCELLED: ${finalProgress.errorMessage}",
                ExtractionStatus.CANCELLED,
                finalProgress.status
            )
            assertTrue(
                "Terminal restored state must retain [RESTORE] logs in I/O Console: ${finalProgress.journalLogs}",
                finalProgress.journalLogs.any { it.contains("[RESTORE]") }
            )
            assertTrue(
                "FINALIZING status must have been observed during restore",
                ExtractionStatus.FINALIZING in observedStatuses
            )
            assertTrue("Restored original archive must remain valid on disk", archiveFile.exists())
        } finally {
            collectJob.cancel()
        }

        // 3. handleExtractionProgressUpdate synthesizes [ERROR] log if an error emission has empty journalLogs
        val handleMethod = HatchViewModel::class.java.getDeclaredMethod(
            "handleExtractionProgressUpdate",
            ExtractionProgress::class.java,
            String::class.java,
            File::class.java,
            Uri::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java
        ).apply { isAccessible = true }

        val blankLogError = ExtractionProgress(
            archiveName = "sample.zip",
            status = ExtractionStatus.ERROR,
            errorMessage = "Simulated blank-log failure",
            journalLogs = emptyList()
        )
        val invoker = launch(Dispatchers.Main.immediate) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                handleMethod.invoke(
                    vm,
                    blankLogError,
                    "sample.zip",
                    tempFolder.root,
                    null,
                    1024L,
                    true,
                    System.currentTimeMillis(),
                    cont
                )
            }
        }
        invoker.join()

        val normalizedErr = vm.extractionProgress.value
        assertEquals(ExtractionStatus.ERROR, normalizedErr.status)
        assertTrue(
            "Blank-log ERROR must synthesize [ERROR] diagnostic line for I/O Console: ${normalizedErr.journalLogs}",
            normalizedErr.journalLogs.any { it.contains("[ERROR]") && it.contains("Simulated blank-log failure") }
        )
    }
}
