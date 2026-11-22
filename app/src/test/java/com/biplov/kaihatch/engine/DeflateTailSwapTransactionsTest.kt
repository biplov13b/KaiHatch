package com.biplov.kaihatch.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Validation Suite: Crash-Safe DEFLATE Tail-Swap Transactions.
 *
 * Deterministically injects crashes before/after `pwrite`, torn `pwrite`, `fsync`,
 * `PREPARED` / `RELOCATED_TRUNCATE_PENDING` / `COMMITTED` checkpoint saves, and `ftruncate`
 * across first-half relocation, middle-chunk truncation (odd chunk count), and second-half
 * truncation, plus repeated crashes during recovery itself and fail-closed corruption checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeflateTailSwapTransactionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
        CheckpointableInflateEngine.persistenceFaultHook = null
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
        CheckpointableInflateEngine.persistenceFaultHook = null
    }

    private class InjectedPersistenceCrash(msg: String) : RuntimeException(msg)

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun crc32Of(bytes: ByteArray): Long {
        val c = CRC32()
        c.update(bytes)
        return c.value
    }

    private fun crc32Of(file: File): Long {
        val c = CRC32()
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                c.update(buf, 0, r)
            }
        }
        return c.value
    }

    /**
     * Generates a mixed payload (alternating incompressible random blocks and structured repeating
     * blocks) whose compressed size is ~75% of [sizeBytes], exercising dynamic Huffman tables and
     * 32 KB LZ77 sliding window back-references across every chunk boundary.
     */
    private fun generateMixedPayload(sizeBytes: Int, seed: Long): ByteArray {
        val out = ByteArray(sizeBytes)
        val rng = Random(seed)
        val pattern = ByteArray(4096) { i -> ((i * 67 + seed.toInt()) and 0xFF).toByte() }
        var pos = 0
        var blockIdx = 0
        while (pos < sizeBytes) {
            val segLen = minOf(64 * 1024, sizeBytes - pos)
            if (blockIdx % 4 == 1) {
                var copied = 0
                while (copied < segLen) {
                    val step = minOf(pattern.size, segLen - copied)
                    System.arraycopy(pattern, 0, out, pos + copied, step)
                    copied += step
                }
            } else {
                val randomSeg = ByteArray(segLen)
                rng.nextBytes(randomSeg)
                System.arraycopy(randomSeg, 0, out, pos, segLen)
            }
            pos += segLen
            blockIdx++
        }
        return out
    }

    private data class BuiltTestArchive(
        val archiveFile: File,
        val outputDir: File,
        val largeEntryName: String,
        val largePayload: ByteArray,
        val largeSha: String,
        val largeCrc: Long,
        val smallEntryName: String,
        val smallPayload: ByteArray,
        val smallSha: String
    )

    /**
     * Builds a 2-entry ZIP archive:
     * 1) `00_first_small.bin` at the beginning (extracted SECOND in reverse Hatch order, verifying
     *    that post-recovery extraction continues cleanly to remaining entries!).
     * 2) `01_large_deflate.bin` at the tail (extracted FIRST in reverse Hatch order via tail-swap).
     *
     * Use [largeSizeBytes] = 6_200_000 for 5 chunks (odd: exercises middle-chunk truncation!)
     * or [largeSizeBytes] = 7_500_000 for 6 chunks (even: 3 first-half + 2 second-half + final).
     */
    private fun createTwoEntryDeflateArchive(
        tag: String,
        largeSizeBytes: Int,
        seed: Long
    ): BuiltTestArchive {
        val archiveFile = tempFolder.newFile("part2c_${tag}.zip")
        val outputDir = tempFolder.newFolder("out_part2c_${tag}")
        val smallName = "00_first_small.bin"
        val largeName = "dir/01_large_deflate.bin"
        val smallPayload = generateMixedPayload(sizeBytes = 96 * 1024, seed = seed + 1L)
        val largePayload = generateMixedPayload(sizeBytes = largeSizeBytes, seed = seed)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry(smallName))
            zos.write(smallPayload)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(largeName))
            zos.write(largePayload)
            zos.closeEntry()
        }

        return BuiltTestArchive(
            archiveFile = archiveFile,
            outputDir = outputDir,
            largeEntryName = largeName,
            largePayload = largePayload,
            largeSha = sha256Hex(largePayload),
            largeCrc = crc32Of(largePayload),
            smallEntryName = smallName,
            smallPayload = smallPayload,
            smallSha = sha256Hex(smallPayload)
        )
    }

    private fun runCrashAndTransactionalRecoveryScenario(
        tag: String,
        largeSizeBytes: Int,
        seed: Long,
        triggerPoint: DestructivePoint,
        triggerOnOccurrence: Int,
        simulateTornWrite: Boolean = false,
        expectMutatedFailClosedForPublicUi: Boolean = true,
        expectedTxPhaseOnCrash: ResumeJournalManager.DeflateTailSwapTxPhase? = null,
        expectedPendingOpOnCrash: ResumeJournalManager.DeflateTailSwapOp? = null
    ) = runBlocking {
        val fixture = createTwoEntryDeflateArchive(tag, largeSizeBytes, seed)
        val largeDestFile = File(fixture.outputDir, fixture.largeEntryName)
        val smallDestFile = File(fixture.outputDir, fixture.smallEntryName)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = triggerPoint,
            triggerOnOccurrence = triggerOnOccurrence,
            simulateTornWrite = simulateTornWrite
        )

        val crashEmissions = KaiHatchEngine.extractArchive(
            archiveFile = fixture.archiveFile,
            targetDir = fixture.outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(
            "[$tag] Initial run must abort with ERROR at $triggerPoint #$triggerOnOccurrence",
            ExtractionStatus.ERROR,
            crashEmissions.last().status
        )

        val savedState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == fixture.archiveFile.absolutePath }
        assertNotNull("[$tag] Resume journal must be preserved after crash", savedState)
        savedState!!

        val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath)
        assertNotNull("[$tag] DeflateTailSwapCheckpoint must be persisted on disk", ckpt)
        ckpt!!
        if (expectedTxPhaseOnCrash != null) {
            assertEquals("[$tag] Unexpected txPhase at crash", expectedTxPhaseOnCrash, ckpt.txPhase)
        }
        if (expectedPendingOpOnCrash != null) {
            assertEquals("[$tag] Unexpected pendingOp at crash", expectedPendingOpOnCrash, ckpt.pendingOp)
        }

        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        // Part 3A/3B: valid v2 transactional checkpoints are eligible for direct-POSIX Resume,
        // and multi-entry archives with remaining unextracted entries are also eligible for Part 3B Restore.
        if (expectMutatedFailClosedForPublicUi) {
            assertTrue(
                "[$tag] Partial DEFLATE output must be preserved on disk after mutated crash",
                largeDestFile.exists() && largeDestFile.length() >= ckpt.outputBytesWritten
            )
            assertEquals(
                "[$tag] Public assessResumeEligibility must recognize valid v2 direct-POSIX checkpoint in Part 3A",
                ResumeJournalManager.ResumeEligibility.Resumable,
                ResumeJournalManager.assessResumeEligibility(savedState, context)
            )
            assertTrue(
                "[$tag] Public isStateSafelyResumable must be true for valid v2 checkpoint in Part 3A",
                ResumeJournalManager.isStateSafelyResumable(savedState, context)
            )
            val restoreEligibility = ResumeJournalManager.assessRestoreEligibility(savedState, context)
            assertTrue(
                "[$tag] Public assessRestoreEligibility must recognize 2-entry v2 direct-POSIX checkpoint as Restorable in Part 3B: $restoreEligibility",
                restoreEligibility is ResumeJournalManager.RestoreEligibility.Restorable &&
                    restoreEligibility.remainingCount == 1 &&
                    !restoreEligibility.noBytesReclaimed &&
                    restoreEligibility.pendingDeflateTailSwapEntryPath == fixture.largeEntryName
            )
        }

        // Now execute Part 2C engine-level transactional recovery!
        val recoveryEmissions = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(
            context = context,
            resumeState = savedState
        ).toList()

        assertEquals(
            "[$tag] Transactional recovery must complete cleanly: ${recoveryEmissions.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            recoveryEmissions.last().status
        )
        assertFalse("[$tag] Source archive must be deleted after completion", fixture.archiveFile.exists())
        assertTrue("[$tag] Large DEFLATE output must exist", largeDestFile.exists())
        assertTrue("[$tag] Subsequent archive entry must also be extracted", smallDestFile.exists())
        assertEquals("[$tag] Large output length mismatch", fixture.largePayload.size.toLong(), largeDestFile.length())
        assertEquals("[$tag] Large output CRC32 mismatch", fixture.largeCrc, crc32Of(largeDestFile))
        assertEquals("[$tag] Large output SHA-256 mismatch", fixture.largeSha, sha256Hex(largeDestFile))
        assertEquals("[$tag] Small output SHA-256 mismatch", fixture.smallSha, sha256Hex(smallDestFile))

        // All transient recovery artifacts must be cleaned up on completion
        assertNull(ResumeJournalManager.loadTailSwapGuard(savedState.stateFilePath))
        assertNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath))
        assertFalse(ResumeJournalManager.getDeflateTailSwapCheckpointFile(savedState.stateFilePath).exists())
    }

    // =========================================================================
    // 1. First-Half Relocation Boundaries (Chunk 0 & Chunk 1 — Even 6-Chunk Archive)
    // =========================================================================

    @Test
    fun firstHalfRelocation_allCrashBoundariesAndTornPwrite_recoverBitExact() {
        val evenSize = 7_500_000 // ~5.6 MB compressed -> 6 chunks (c=0,1,2 first-half; c=3,4 second-half; c=5 final)

        // Chunk 0 before pwrite (unmutated archive -> clean restart)
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_after_prepared",
            largeSizeBytes = evenSize,
            seed = 30001L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_PREPARED_CKPT,
            triggerOnOccurrence = 1,
            expectMutatedFailClosedForPublicUi = false,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 TORN pwrite (slot 0 half-overwritten with garbage, tail intact at tailFilePos!)
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_torn_pwrite",
            largeSizeBytes = evenSize,
            seed = 30002L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 before pwrite fsync
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_before_pwrite_sync",
            largeSizeBytes = evenSize,
            seed = 30003L,
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_PWRITE_SYNC,
            triggerOnOccurrence = 1,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 after pwrite+fsync, before RELOCATED_TRUNCATE_PENDING commit
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_after_pwrite_before_reloc_ckpt",
            largeSizeBytes = evenSize,
            seed = 30004L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 after RELOCATED_TRUNCATE_PENDING commit, before ftruncate
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_after_reloc_ckpt_before_trunc",
            largeSizeBytes = evenSize,
            seed = 30005L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 after ftruncate, before ftruncate fsync
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_before_trunc_sync",
            largeSizeBytes = evenSize,
            seed = 30006L,
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
            triggerOnOccurrence = 1,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 after ftruncate+fsync, before COMMITTED commit
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_after_trunc_before_committed",
            largeSizeBytes = evenSize,
            seed = 30007L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 0 after COMMITTED commit
        runCrashAndTransactionalRecoveryScenario(
            tag = "c0_after_committed",
            largeSizeBytes = evenSize,
            seed = 30008L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.NONE
        )

        // Chunk 1 TORN pwrite (already mutated archive from chunk 0 + torn slot 1 write!)
        runCrashAndTransactionalRecoveryScenario(
            tag = "c1_torn_pwrite",
            largeSizeBytes = evenSize,
            seed = 30009L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 2,
            simulateTornWrite = true,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )
    }

    // =========================================================================
    // 2. Middle-Chunk & Second-Half Truncation Boundaries (Odd 5-Chunk Archive)
    // =========================================================================

    @Test
    fun middleChunkAndSecondHalfTruncation_allCrashBoundaries_recoverBitExact() {
        // ~4.65 MB compressed -> 5 chunks at 1 MB chunkStep:
        // c=0 (FIRST_HALF_RELOCATE: 4->0), c=1 (FIRST_HALF_RELOCATE: 3->1),
        // c=2 (MIDDLE_TRUNCATE: c==tailIdx==2!), c=3 (SECOND_HALF_TRUNCATE: slot 1), c=4 (final)
        val oddSize = 6_200_000

        // Middle chunk (occurrence #3 of RELOCATED_TRUNCATE_PENDING / TRUNCATE_SYNC / AFTER_TRUNCATE)
        runCrashAndTransactionalRecoveryScenario(
            tag = "mid_before_trunc",
            largeSizeBytes = oddSize,
            seed = 31001L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 3,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE
        )

        runCrashAndTransactionalRecoveryScenario(
            tag = "mid_before_trunc_sync",
            largeSizeBytes = oddSize,
            seed = 31002L,
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_TRUNCATE_SYNC,
            triggerOnOccurrence = 3,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE
        )

        runCrashAndTransactionalRecoveryScenario(
            tag = "mid_after_trunc_before_committed",
            largeSizeBytes = oddSize,
            seed = 31003L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 3,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE
        )

        // Second-half chunk (occurrence #4: c=3, slot=1)
        runCrashAndTransactionalRecoveryScenario(
            tag = "second_half_before_trunc",
            largeSizeBytes = oddSize,
            seed = 31004L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 4,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.SECOND_HALF_TRUNCATE
        )

        runCrashAndTransactionalRecoveryScenario(
            tag = "second_half_after_trunc_before_committed",
            largeSizeBytes = oddSize,
            seed = 31005L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 4,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.SECOND_HALF_TRUNCATE
        )

        runCrashAndTransactionalRecoveryScenario(
            tag = "second_half_after_committed",
            largeSizeBytes = oddSize,
            seed = 31006L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 4,
            expectedTxPhaseOnCrash = ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
            expectedPendingOpOnCrash = ResumeJournalManager.DeflateTailSwapOp.NONE
        )
    }

    // =========================================================================
    // 3. Interrupted Checkpoint Saves (Mid-.tmp & Mid-.bak) Across Tx Phases
    // =========================================================================

    @Test
    fun interruptedCheckpointSaves_duringPreparedRelocatedAndCommitted_recoverBitExact() = runBlocking {
        // Case A: Crash mid-.tmp write while saving chunk 1's PREPARED checkpoint
        // (chunk 0's COMMITTED checkpoint remains intact on disk; chunk 1's partial output gets trimmed & re-decoded!)
        run {
            val fixture = createTwoEntryDeflateArchive("ckpt_fault_prepared_c1", 6_200_000, 32001L)
            var ckptWriteCount = 0
            // Chunk 0 writes: #1 PREPARED, #2 RELOCATED_TRUNCATE_PENDING, #3 COMMITTED.
            // Chunk 1 writes: #4 PREPARED -> inject crash at AFTER_TMP_PARTIAL_WRITE!
            ResumeJournalManager.persistenceFaultHook = { stage, targetFile, _ ->
                if (targetFile.name.endsWith(".tailswap.ckpt") &&
                    stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE
                ) {
                    ckptWriteCount++
                    if (ckptWriteCount == 4) {
                        throw InjectedPersistenceCrash("Simulated crash mid-.tmp on chunk 1 PREPARED")
                    }
                }
            }
            val run1 = KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            ResumeJournalManager.persistenceFaultHook = null
            assertEquals(ExtractionStatus.ERROR, run1.last().status)

            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            assertEquals("Must fall back cleanly to chunk 0 COMMITTED", 0, ckpt.lastCompletedChunkIndex)
            assertEquals(ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED, ckpt.txPhase)

            // Verify destFile currently has MORE bytes than chunk 0's outputBytesWritten (chunk 1 was already flushed!)
            val largeDest = File(fixture.outputDir, fixture.largeEntryName)
            assertTrue("destFile should contain uncommitted chunk 1 bytes to be trimmed", largeDest.length() > ckpt.outputBytesWritten)

            val rec = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList()
            assertEquals(ExtractionStatus.COMPLETED, rec.last().status)
            assertEquals(fixture.largeSha, sha256Hex(largeDest))
            assertEquals(fixture.smallSha, sha256Hex(File(fixture.outputDir, fixture.smallEntryName)))
        }

        // Case B: Crash mid-.tmp write while saving chunk 0's RELOCATED_TRUNCATE_PENDING checkpoint
        // (pwrite already happened, ftruncate MUST NOT happen; PREPARED stays on disk -> recovery replays cleanly!)
        run {
            val fixture = createTwoEntryDeflateArchive("ckpt_fault_reloc_c0", 6_200_000, 32002L)
            var ckptWriteCount = 0
            ResumeJournalManager.persistenceFaultHook = { stage, targetFile, _ ->
                if (targetFile.name.endsWith(".tailswap.ckpt") &&
                    stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE
                ) {
                    ckptWriteCount++
                    if (ckptWriteCount == 2) {
                        throw InjectedPersistenceCrash("Simulated crash mid-.tmp on chunk 0 RELOCATED_TRUNCATE_PENDING")
                    }
                }
            }
            val run1 = KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            ResumeJournalManager.persistenceFaultHook = null
            assertEquals(ExtractionStatus.ERROR, run1.last().status)

            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            assertEquals(0, ckpt.lastCompletedChunkIndex)
            assertEquals(ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED, ckpt.txPhase)

            val rec = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList()
            assertEquals(ExtractionStatus.COMPLETED, rec.last().status)
            assertEquals(fixture.largeSha, sha256Hex(File(fixture.outputDir, fixture.largeEntryName)))
            assertEquals(fixture.smallSha, sha256Hex(File(fixture.outputDir, fixture.smallEntryName)))
        }

        // Case C: Crash AFTER_PRIMARY_REPLACE_BEFORE_BACKUP while saving chunk 0's RELOCATED_TRUNCATE_PENDING
        // (primary already holds RELOCATED_TRUNCATE_PENDING; ftruncate did not run -> recovery completes ftruncate!)
        run {
            val fixture = createTwoEntryDeflateArchive("ckpt_fault_reloc_bak_c0", 6_200_000, 32003L)
            var ckptWriteCount = 0
            ResumeJournalManager.persistenceFaultHook = { stage, targetFile, _ ->
                if (targetFile.name.endsWith(".tailswap.ckpt") &&
                    stage == ResumeJournalManager.PersistenceFaultStage.AFTER_PRIMARY_REPLACE_BEFORE_BACKUP
                ) {
                    ckptWriteCount++
                    if (ckptWriteCount == 2) {
                        throw InjectedPersistenceCrash("Simulated crash before .bak mirror on RELOCATED_TRUNCATE_PENDING")
                    }
                }
            }
            val run1 = KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            ResumeJournalManager.persistenceFaultHook = null
            assertEquals(ExtractionStatus.ERROR, run1.last().status)

            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            assertEquals(ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING, ckpt.txPhase)

            val rec = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList()
            assertEquals(ExtractionStatus.COMPLETED, rec.last().status)
            assertEquals(fixture.largeSha, sha256Hex(File(fixture.outputDir, fixture.largeEntryName)))
        }

        // Case D: Crash mid-.tmp write while saving chunk 0's COMMITTED checkpoint (AFTER ftruncate already ran!)
        // (RELOCATED_TRUNCATE_PENDING stays on disk while physLen == pendingTruncateTarget -> recovery detects already-truncated!)
        run {
            val fixture = createTwoEntryDeflateArchive("ckpt_fault_committed_c0", 6_200_000, 32004L)
            var ckptWriteCount = 0
            ResumeJournalManager.persistenceFaultHook = { stage, targetFile, _ ->
                if (targetFile.name.endsWith(".tailswap.ckpt") &&
                    stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE
                ) {
                    ckptWriteCount++
                    if (ckptWriteCount == 3) {
                        throw InjectedPersistenceCrash("Simulated crash mid-.tmp on chunk 0 COMMITTED")
                    }
                }
            }
            val run1 = KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            ResumeJournalManager.persistenceFaultHook = null
            assertEquals(ExtractionStatus.ERROR, run1.last().status)

            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            assertEquals(ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING, ckpt.txPhase)
            assertEquals(
                "Archive must already be truncated to pendingTruncateTarget",
                ckpt.pendingTruncateTarget,
                fixture.archiveFile.length()
            )

            val rec = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList()
            assertEquals(ExtractionStatus.COMPLETED, rec.last().status)
            assertEquals(fixture.largeSha, sha256Hex(File(fixture.outputDir, fixture.largeEntryName)))
        }
    }

    // =========================================================================
    // 4. Repeated Crashes Across Sequential Recovery Attempts + Trailing Output Trim
    // =========================================================================

    @Test
    fun repeatedCrashesDuringRecoveryAttempts_andTrailingPartialOutput_recoverBitExact() = runBlocking {
        val fixture = createTwoEntryDeflateArchive("repeated_recovery_chain", 6_200_000, 33001L)
        val largeDest = File(fixture.outputDir, fixture.largeEntryName)
        val smallDest = File(fixture.outputDir, fixture.smallEntryName)

        // Crash 1: Initial extraction crashes on TORN pwrite during chunk 0
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList().last().status
        )
        val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }

        // Crash 2: Recovery attempt #1 crashes AGAIN on a second TORN pwrite while replaying chunk 0!
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last().status
        )
        assertEquals(
            ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!.txPhase
        )

        // Crash 3: Recovery attempt #2 succeeds on pwrite + RELOCATED_TRUNCATE_PENDING, then crashes before ftruncate!
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last().status
        )
        assertEquals(
            ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!.txPhase
        )

        // Crash 4: Recovery attempt #3 finishes chunk 0 + chunk 1, then crashes after middle-chunk ftruncate!
        // Occurrence #1 of AFTER_TAILSWAP_TRUNCATE is chunk 0 (from recovery), #2 is chunk 1, #3 is middle chunk (c=2).
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 3
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last().status
        )
        val midCkpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
        assertEquals(2, midCkpt.lastCompletedChunkIndex)
        assertEquals(ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE, midCkpt.pendingOp)

        // Crash 5: Recovery attempt #4 finishes middle chunk -> crashes on second-half chunk (c=3) COMMITTED!
        // Occurrence #1 of AFTER_TAILSWAP_COMMITTED_CKPT is middle chunk promotion, #2 is second-half c=3.
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 2
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last().status
        )
        val c3Ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
        assertEquals(3, c3Ckpt.lastCompletedChunkIndex)
        assertEquals(ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED, c3Ckpt.txPhase)

        // Simulate uncommitted partial bytes appended during final chunk decode before crash
        FileOutputStream(largeDest, true).use { fos ->
            fos.write(ByteArray(12345) { 0xAB.toByte() })
            fos.fd.sync()
        }
        assertEquals(c3Ckpt.outputBytesWritten + 12345L, largeDest.length())

        // Attempt 6: Final clean recovery trims trailing garbage bytes, finishes final chunk + small entry bit-exact!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val finalResult = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
        assertEquals("Final recovery failed: ${finalResult.errorMessage}", ExtractionStatus.COMPLETED, finalResult.status)
        assertFalse(fixture.archiveFile.exists())
        assertEquals(fixture.largeSha, sha256Hex(largeDest))
        assertEquals(fixture.largeCrc, crc32Of(largeDest))
        assertEquals(fixture.smallSha, sha256Hex(smallDest))
    }

    // =========================================================================
    // 5. Fail-Closed Safeguards on Inconsistent / Corrupted States
    // =========================================================================

    @Test
    fun failClosed_onCorruptedOutputPrefixTailSlotArchiveLengthOrCheckpoint_preservesArtifacts() = runBlocking {
        // 5A: Corrupted output prefix byte -> CRC mismatch -> must fail closed and preserve files
        run {
            val fixture = createTwoEntryDeflateArchive("failclosed_output_crc", 6_200_000, 34001L)
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val largeDest = File(fixture.outputDir, fixture.largeEntryName)
            RandomAccessFile(largeDest, "rw").use { raf ->
                raf.seek(1024L)
                val b = raf.read()
                raf.seek(1024L)
                raf.write(b xor 0xFF)
            }
            val res = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
            assertEquals(ExtractionStatus.ERROR, res.status)
            assertTrue("Expected prefix CRC mismatch message: ${res.errorMessage}", res.errorMessage?.contains("CRC32 mismatch", ignoreCase = true) == true)
            assertTrue(largeDest.exists())
            assertNotNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath))
            ResumeJournalManager.deleteState(state.stateFilePath)
        }

        // 5B: Output file shorter than checkpoint offset -> must fail closed
        run {
            val fixture = createTwoEntryDeflateArchive("failclosed_output_short", 6_200_000, 34002L)
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val largeDest = File(fixture.outputDir, fixture.largeEntryName)
            RandomAccessFile(largeDest, "rw").use { raf -> raf.setLength(100L) }

            val res = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
            assertEquals(ExtractionStatus.ERROR, res.status)
            assertTrue(largeDest.exists())
            assertNotNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath))
            ResumeJournalManager.deleteState(state.stateFilePath)
        }

        // 5C: Corrupted intact tail chunk while in PREPARED phase -> must fail closed on tail CRC mismatch
        run {
            val fixture = createTwoEntryDeflateArchive("failclosed_tail_crc", 6_200_000, 34003L)
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
                triggerOnOccurrence = 1,
                simulateTornWrite = true
            )
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            RandomAccessFile(fixture.archiveFile, "rw").use { raf ->
                raf.seek(ckpt.pendingTruncateTarget + 16L)
                val b = raf.read()
                raf.seek(ckpt.pendingTruncateTarget + 16L)
                raf.write(b xor 0xFF)
            }
            val res = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
            assertEquals(ExtractionStatus.ERROR, res.status)
            assertTrue("Expected tail chunk CRC mismatch: ${res.errorMessage}", res.errorMessage?.contains("tail chunk CRC32 mismatch", ignoreCase = true) == true)
            assertNotNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath))
            ResumeJournalManager.deleteState(state.stateFilePath)
        }

        // 5D: Corrupted relocated slot while in RELOCATED_TRUNCATE_PENDING phase -> must fail closed
        run {
            val fixture = createTwoEntryDeflateArchive("failclosed_slot_crc", 6_200_000, 34004L)
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            val slotPos = ckpt.payloadOffset + ckpt.pendingSlotIndex.toLong() * ckpt.chunkStep.toLong()
            RandomAccessFile(fixture.archiveFile, "rw").use { raf ->
                raf.seek(slotPos + 32L)
                val b = raf.read()
                raf.seek(slotPos + 32L)
                raf.write(b xor 0xFF)
            }
            val res = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
            assertEquals(ExtractionStatus.ERROR, res.status)
            assertTrue("Expected relocated slot CRC mismatch: ${res.errorMessage}", res.errorMessage?.contains("relocated slot", ignoreCase = true) == true)
            assertNotNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath))
            ResumeJournalManager.deleteState(state.stateFilePath)
        }

        // 5E: Archive externally truncated below pendingTruncateTarget -> must fail closed
        run {
            val fixture = createTwoEntryDeflateArchive("failclosed_short_archive", 6_200_000, 34005L)
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath)!!
            RandomAccessFile(fixture.archiveFile, "rw").use { raf ->
                raf.setLength(ckpt.pendingTruncateTarget - 1024L)
            }
            val res = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
            assertEquals(ExtractionStatus.ERROR, res.status)
            assertNotNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(state.stateFilePath))
            ResumeJournalManager.deleteState(state.stateFilePath)
        }

        // 5F: Both primary and .bak checkpoint corrupted -> must fail closed
        run {
            val fixture = createTwoEntryDeflateArchive("failclosed_corrupt_ckpt", 6_200_000, 34006L)
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckptFile = ResumeJournalManager.getDeflateTailSwapCheckpointFile(state.stateFilePath)
            val bakFile = File("${ckptFile.absolutePath}.bak")
            ckptFile.writeBytes(ByteArray(128) { 0x11.toByte() })
            bakFile.writeBytes(ByteArray(128) { 0x22.toByte() })

            val res = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
            assertEquals(ExtractionStatus.ERROR, res.status)
            assertTrue(File(fixture.outputDir, fixture.largeEntryName).exists())
            ResumeJournalManager.deleteState(state.stateFilePath)
        }
    }

    // =========================================================================
    // 6. Bounded O(1) Storage Overhead Verification (Zero Chunk Backup Files)
    // =========================================================================

    @Test
    fun storageOverhead_remainsBoundedUnder128KB_withNoChunkBackups() = runBlocking {
        val fixture = createTwoEntryDeflateArchive("storage_overhead", 7_500_000, 35001L)
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 2
        )
        KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val state = ResumeJournalManager.loadPendingStates(context).first { it.archivePath == fixture.archiveFile.absolutePath }
        val ckptPrimary = ResumeJournalManager.getDeflateTailSwapCheckpointFile(state.stateFilePath)
        val ckptBak = File("${ckptPrimary.absolutePath}.bak")
        val guardPrimary = File("${state.stateFilePath}.tailswap.guard")
        val guardBak = File("${guardPrimary.absolutePath}.bak")

        assertTrue(ckptPrimary.exists() && ckptBak.exists())
        assertTrue(guardPrimary.exists() && guardBak.exists())

        val totalTxMetadataBytes = ckptPrimary.length() + ckptBak.length() + guardPrimary.length() + guardBak.length()
        println(
            "Part 2C measured storage overhead: ckptPrimary=${ckptPrimary.length()} B, " +
                "ckptBak=${ckptBak.length()} B, guardPrimary=${guardPrimary.length()} B, " +
                "guardBak=${guardBak.length()} B, total=$totalTxMetadataBytes B"
        )
        assertTrue(
            "Transactional metadata overhead must remain tightly bounded (< 128 KB), got $totalTxMetadataBytes B",
            totalTxMetadataBytes in 1024L..(128L * 1024L)
        )

        // Complete recovery and verify all metadata files are removed
        val rec = KaiHatchEngine.recoverInterruptedDeflateTailSwapExtraction(context, state).toList().last()
        assertEquals(ExtractionStatus.COMPLETED, rec.status)
        assertFalse(ckptPrimary.exists())
        assertFalse(ckptBak.exists())
        assertFalse(guardPrimary.exists())
        assertFalse(guardBak.exists())
    }
}
