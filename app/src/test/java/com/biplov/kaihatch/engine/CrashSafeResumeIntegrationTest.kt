package com.biplov.kaihatch.engine

import android.content.Context
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ResumeJournalState
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crash-Safe Resume Integration Test Suite:
 * Verifies end-to-end crash-safe Resume through the public APIs
 * (`ResumeJournalManager.assessResumeEligibility`, `isStateSafelyResumable`,
 * and `KaiHatchEngine.resumeInterruptedExtraction`) across every transactional DEFLATE
 * tail-swap phase, repeated interruptions, uncommitted output truncation (no duplication),
 * remaining ZIP entry preservation, and fail-closed rejection of invalid, legacy v1,
 * corrupted, or unsupported SAF states — while keeping Restore fail-closed for mutated entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashSafeResumeIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
    }

    private data class ArchiveFixture(
        val archiveFile: File,
        val outputDir: File,
        val smallEntryName: String,
        val smallPayload: ByteArray,
        val smallSha: String,
        val largeEntryName: String,
        val largePayload: ByteArray,
        val largeSha: String,
        val largeCrc: Long
    )

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

    private fun crc32Of(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun crc32Of(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                crc.update(buf, 0, r)
            }
        }
        return crc.value
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createTwoEntryZip(
        tag: String,
        largeSizeBytes: Int,
        seed: Long
    ): ArchiveFixture {
        val archiveFile = tempFolder.newFile("part3a_${tag}.zip")
        val outputDir = tempFolder.newFolder("out_part3a_${tag}")
        val smallEntryName = "meta/earlier_entry.txt"
        val largeEntryName = "media/large_stream.bin"

        val smallPayload = "Part3A remaining ZIP entry integrity payload [$tag] seed=$seed\n"
            .repeat(128)
            .toByteArray(Charsets.UTF_8)
        val largePayload = generateMixedDeflatePayload(largeSizeBytes, seed)

        // Small entry is placed first (lower offset), large entry second (higher offset),
        // so Hatch Mode's reverse-offset order extracts the large DEFLATE entry FIRST
        // and leaves `smallEntryName` as a remaining ZIP entry to extract AFTER recovery!
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry(smallEntryName))
            zos.write(smallPayload)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(largeEntryName))
            zos.write(largePayload)
            zos.closeEntry()
        }

        return ArchiveFixture(
            archiveFile = archiveFile,
            outputDir = outputDir,
            smallEntryName = smallEntryName,
            smallPayload = smallPayload,
            smallSha = sha256Hex(smallPayload),
            largeEntryName = largeEntryName,
            largePayload = largePayload,
            largeSha = sha256Hex(largePayload),
            largeCrc = crc32Of(largePayload)
        )
    }

    private class SingleShotFaultInjector(
        private val triggerPoint: DestructivePoint,
        private val triggerOnOccurrence: Int,
        private val simulateTornWrite: Boolean = false
    ) : TruncationFaultInjector {
        private val hits = AtomicInteger(0)

        override fun shouldSimulateTornWrite(point: DestructivePoint): Boolean {
            return simulateTornWrite && point == DestructivePoint.BEFORE_TAILSWAP_PWRITE
        }

        override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
            if (point == triggerPoint && hits.incrementAndGet() == triggerOnOccurrence) {
                throw SimulatedFaultException(
                    point = point,
                    occurrence = triggerOnOccurrence,
                    archiveBytesAtFault = currentArchiveBytes,
                    message = "Part3A fault at $point #$triggerOnOccurrence ($detail)"
                )
            }
        }
    }

    private fun writeLegacyV1Checkpoint(
        stateFilePath: String,
        ckpt: ResumeJournalManager.DeflateTailSwapCheckpoint
    ) {
        // Remove v2 primary + .bak + .tmp and write a checksum-valid v1 binary checkpoint
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateFilePath)
        val ckptFile = ResumeJournalManager.getDeflateTailSwapCheckpointFile(stateFilePath)
        val crc = CRC32()
        FileOutputStream(ckptFile).use { fos ->
            val bos = BufferedOutputStream(fos, 32 * 1024)
            val cos = CheckedOutputStream(bos, crc)
            val dos = DataOutputStream(cos)

            dos.writeInt(ResumeJournalManager.DEFLATE_TAILSWAP_CKPT_MAGIC)
            dos.writeInt(ResumeJournalManager.DEFLATE_TAILSWAP_CKPT_VERSION_V1)
            dos.writeUTF(ckpt.entryPath)
            dos.writeUTF(ckpt.entryName)
            dos.writeLong(ckpt.payloadOffset)
            dos.writeLong(ckpt.compressedSize)
            dos.writeLong(ckpt.uncompressedSize)
            dos.writeLong(ckpt.expectedEntryCrc32)
            dos.writeInt(ckpt.chunkStep)
            dos.writeInt(ckpt.totalChunks)
            dos.writeInt(ckpt.lastCompletedChunkIndex)
            dos.writeBoolean(ckpt.relocationCommittedForChunk)
            dos.writeBoolean(ckpt.canSwapInPlace)
            dos.writeBoolean(ckpt.hasMutatedArchive)
            dos.writeLong(ckpt.currentArchiveBytes)
            dos.writeLong(ckpt.compressedBytesConsumed)
            dos.writeLong(ckpt.outputBytesWritten)
            dos.writeLong(ckpt.runningCrc32)

            for (i in 0 until ckpt.totalChunks) {
                dos.writeInt(ckpt.relocatedSlot[i])
                dos.writeInt(ckpt.relocatedLen[i])
            }
            dos.writeInt(ckpt.inflateCheckpointBytes.size)
            dos.write(ckpt.inflateCheckpointBytes)
            dos.flush()
            bos.flush()

            DataOutputStream(fos).apply {
                writeLong(crc.value)
                flush()
            }
            fos.fd.sync()
        }
    }

    private fun runCrashAndPublicResumeScenario(
        tag: String,
        largeSizeBytes: Int,
        seed: Long,
        triggerPoint: DestructivePoint,
        triggerOnOccurrence: Int,
        simulateTornWrite: Boolean = false,
        expectedTxPhase: ResumeJournalManager.DeflateTailSwapTxPhase,
        expectedPendingOp: ResumeJournalManager.DeflateTailSwapOp,
        appendUncommittedTrailingOutputBytes: Int = 0
    ) = runBlocking {
        val fixture = createTwoEntryZip(tag, largeSizeBytes, seed)
        val largeDestFile = File(fixture.outputDir, fixture.largeEntryName)
        val smallDestFile = File(fixture.outputDir, fixture.smallEntryName)

        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = triggerPoint,
            triggerOnOccurrence = triggerOnOccurrence,
            simulateTornWrite = simulateTornWrite
        )

        val crashRun = KaiHatchEngine.extractArchive(
            archiveFile = fixture.archiveFile,
            targetDir = fixture.outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals("[$tag] Initial run must abort at $triggerPoint #$triggerOnOccurrence", ExtractionStatus.ERROR, crashRun.last().status)

        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val savedState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == fixture.archiveFile.absolutePath }
        assertNotNull("[$tag] Pending resume journal must be preserved", savedState)
        savedState!!

        val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath)
        assertNotNull("[$tag] Valid v2 DeflateTailSwapCheckpoint must be persisted", ckpt)
        ckpt!!
        assertEquals("[$tag] Checkpoint must be v2", ResumeJournalManager.DEFLATE_TAILSWAP_CKPT_VERSION, ckpt.formatVersion)
        assertEquals("[$tag] Unexpected txPhase", expectedTxPhase, ckpt.txPhase)
        assertEquals("[$tag] Unexpected pendingOp", expectedPendingOp, ckpt.pendingOp)

        if (appendUncommittedTrailingOutputBytes > 0) {
            FileOutputStream(largeDestFile, true).use { fos ->
                fos.write(ByteArray(appendUncommittedTrailingOutputBytes) { 0x5A })
                fos.flush()
                fos.fd.sync()
            }
            assertEquals(ckpt.outputBytesWritten + appendUncommittedTrailingOutputBytes, largeDestFile.length())
        }

        // Eligibility checks must be idempotent and expose BOTH Resume and Part 3B Restore as separate choices!
        repeat(2) {
            assertEquals(
                "[$tag] Public assessResumeEligibility must return Resumable",
                ResumeJournalManager.ResumeEligibility.Resumable,
                ResumeJournalManager.assessResumeEligibility(savedState, context)
            )
            assertTrue(
                "[$tag] Public isStateSafelyResumable must be true",
                ResumeJournalManager.isStateSafelyResumable(savedState, context)
            )
            val restoreEligibility = ResumeJournalManager.assessRestoreEligibility(savedState, context)
            assertTrue(
                "[$tag] Public assessRestoreEligibility must return Restorable for 2-entry mutated archive in Part 3B: $restoreEligibility",
                restoreEligibility is ResumeJournalManager.RestoreEligibility.Restorable &&
                    restoreEligibility.remainingCount == 1 &&
                    !restoreEligibility.noBytesReclaimed &&
                    restoreEligibility.pendingDeflateTailSwapEntryPath == fixture.largeEntryName
            )
        }

        // Execute PUBLIC resumeInterruptedExtraction through completion!
        val resumed = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = savedState
        ).toList()

        assertEquals(
            "[$tag] Public resumeInterruptedExtraction must succeed: ${resumed.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            resumed.last().status
        )
        assertFalse("[$tag] Source archive must be deleted after completion", fixture.archiveFile.exists())
        assertTrue("[$tag] Large DEFLATE output must exist", largeDestFile.exists())
        assertTrue("[$tag] Remaining ZIP entry must also be extracted", smallDestFile.exists())
        assertEquals("[$tag] Large output exact length mismatch (no duplication/loss)", fixture.largePayload.size.toLong(), largeDestFile.length())
        assertEquals("[$tag] Large output CRC32 mismatch", fixture.largeCrc, crc32Of(largeDestFile))
        assertEquals("[$tag] Large output SHA-256 mismatch", fixture.largeSha, sha256Hex(largeDestFile))
        assertEquals("[$tag] Remaining ZIP entry SHA-256 mismatch", fixture.smallSha, sha256Hex(smallDestFile))

        // Recovery metadata must be cleanly removed after completion
        assertNull(ResumeJournalManager.loadTailSwapGuard(savedState.stateFilePath))
        assertNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath))
        assertFalse(ResumeJournalManager.getDeflateTailSwapCheckpointFile(savedState.stateFilePath).exists())
    }

    // =========================================================================
    // 1. Public Resume Recovery at Every Transaction Phase
    // =========================================================================

    @Test
    fun publicResume_preparedPhase_tornPwriteChunk0AndChunk1_recoversBitExact() {
        val evenSize = 7_500_000 // 6 chunks

        // Chunk 0 torn pwrite (PREPARED, FIRST_HALF_RELOCATE)
        runCrashAndPublicResumeScenario(
            tag = "prepared_c0_torn",
            largeSizeBytes = evenSize,
            seed = 51001L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 1 after PREPARED checkpoint (after chunk 0 committed & truncated)
        runCrashAndPublicResumeScenario(
            tag = "prepared_c1",
            largeSizeBytes = evenSize,
            seed = 51002L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_PREPARED_CKPT,
            triggerOnOccurrence = 2,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Chunk 1 torn pwrite (PREPARED, FIRST_HALF_RELOCATE)
        runCrashAndPublicResumeScenario(
            tag = "prepared_c1_torn",
            largeSizeBytes = evenSize,
            seed = 51003L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 2,
            simulateTornWrite = true,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )
    }

    @Test
    fun publicResume_relocatedTruncatePendingPhase_firstHalfMiddleAndSecondHalf_recoversBitExact() {
        val evenSize = 7_500_000 // 6 chunks (c=0,1,2 first-half; c=3,4 second-half; c=5 final)
        val oddSize = 6_300_000  // 5 chunks (c=0,1 first-half; c=2 middle; c=3 second-half; c=4 final)

        // First-half: before ftruncate
        runCrashAndPublicResumeScenario(
            tag = "reloc_firsthalf_before_trunc",
            largeSizeBytes = evenSize,
            seed = 52001L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // First-half: after ftruncate before COMMITTED checkpoint
        runCrashAndPublicResumeScenario(
            tag = "reloc_firsthalf_after_trunc",
            largeSizeBytes = evenSize,
            seed = 52002L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Odd chunk count: middle-chunk truncation pending
        runCrashAndPublicResumeScenario(
            tag = "reloc_middle_before_trunc",
            largeSizeBytes = oddSize,
            seed = 52003L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 3,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE
        )

        // Second-half truncation pending (chunk 3 on 6-chunk archive -> 4th RELOCATED_TRUNCATE_PENDING)
        runCrashAndPublicResumeScenario(
            tag = "reloc_secondhalf_before_trunc",
            largeSizeBytes = evenSize,
            seed = 52004L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 4,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.SECOND_HALF_TRUNCATE
        )
    }

    @Test
    fun publicResume_committedPhaseWithUncommittedTrailingOutput_trimsCleanlyWithoutDuplication() {
        runCrashAndPublicResumeScenario(
            tag = "committed_trailing_bytes",
            largeSizeBytes = 7_500_000,
            seed = 53001L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 2,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.NONE,
            appendUncommittedTrailingOutputBytes = 49_152
        )
    }

    // =========================================================================
    // 2. Repeated Public Resume Interruptions (Chained Force-Stops -> Completion)
    // =========================================================================

    @Test
    fun publicResume_repeatedInterruptionsAcrossAllPhases_idempotentToBitExactCompletion() = runBlocking {
        val fixture = createTwoEntryZip("chained_public_resumes", largeSizeBytes = 7_500_000, seed = 54001L)
        val largeDestFile = File(fixture.outputDir, fixture.largeEntryName)
        val smallDestFile = File(fixture.outputDir, fixture.smallEntryName)

        // Crash 1: initial run torn pwrite on chunk 0 (PREPARED)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context)
                .toList().last().status
        )

        val state1 = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        assertTrue(ResumeJournalManager.isStateSafelyResumable(state1, context))
        assertEquals(
            ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(state1.stateFilePath)!!.txPhase
        )

        // Crash 2: public Resume #1 replays chunk 0 pwrite, saves RELOCATED_TRUNCATE_PENDING, then crashes before ftruncate!
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.resumeInterruptedExtraction(context, state1).toList().last().status
        )

        val state2 = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        assertTrue(ResumeJournalManager.isStateSafelyResumable(state2, context))
        assertEquals(
            ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(state2.stateFilePath)!!.txPhase
        )

        // Crash 3: public Resume #2 completes chunk 0 truncate + chunk 1, commits chunk 1, then crashes after COMMITTED!
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 2
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.resumeInterruptedExtraction(context, state2).toList().last().status
        )

        val state3 = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        assertTrue(ResumeJournalManager.isStateSafelyResumable(state3, context))
        assertEquals(
            ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(state3.stateFilePath)!!.txPhase
        )

        // Crash 4: public Resume #3 crashes after second-half truncation
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 2
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.resumeInterruptedExtraction(context, state3).toList().last().status
        )

        val state4 = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        assertTrue(ResumeJournalManager.isStateSafelyResumable(state4, context))

        // Final public Resume #4: succeeds cleanly with zero duplication and intact remaining ZIP entry!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val finalResult = KaiHatchEngine.resumeInterruptedExtraction(context, state4).toList().last()
        assertEquals(
            "Final public resume after 4 chained crashes must succeed: ${finalResult.errorMessage}",
            ExtractionStatus.COMPLETED,
            finalResult.status
        )
        assertFalse(fixture.archiveFile.exists())
        assertEquals(fixture.largePayload.size.toLong(), largeDestFile.length())
        assertEquals(fixture.largeCrc, crc32Of(largeDestFile))
        assertEquals(fixture.largeSha, sha256Hex(largeDestFile))
        assertEquals(fixture.smallSha, sha256Hex(smallDestFile))
    }

    // =========================================================================
    // 3. Fail-Closed Tests: Legacy v1, Corrupted, Tampered, and SAF States
    // =========================================================================

    @Test
    fun failClosed_legacyV1Checkpoint_isRejectedByEligibilityAndPublicResume() = runBlocking {
        val fixture = createTwoEntryZip("legacy_v1", largeSizeBytes = 7_500_000, seed = 55001L)
        val largeDestFile = File(fixture.outputDir, fixture.largeEntryName)

        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context)
                .toList().last().status
        )
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val savedState = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        val v2Ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath)!!

        // Replace v2 checkpoint with a valid CRC32 legacy v1 checkpoint
        writeLegacyV1Checkpoint(savedState.stateFilePath, v2Ckpt)
        val loadedV1 = ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath)
        assertNotNull("Legacy v1 checkpoint must still deserialize cleanly", loadedV1)
        assertEquals(ResumeJournalManager.DEFLATE_TAILSWAP_CKPT_VERSION_V1, loadedV1!!.formatVersion)

        // Eligibility AND public Resume MUST fail closed on legacy v1!
        val eligibility = ResumeJournalManager.assessResumeEligibility(savedState, context)
        assertTrue(
            "Legacy v1 checkpoint must be Unrecoverable, got $eligibility",
            eligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertFalse(ResumeJournalManager.isStateSafelyResumable(savedState, context))

        val partialLenBefore = largeDestFile.length()
        val resumeResult = KaiHatchEngine.resumeInterruptedExtraction(context, savedState).toList().last()
        assertEquals(ExtractionStatus.ERROR, resumeResult.status)
        assertTrue("Archive must be preserved on fail-closed rejection", fixture.archiveFile.exists())
        assertEquals("Partial output must be preserved on fail-closed rejection", partialLenBefore, largeDestFile.length())
        ResumeJournalManager.deleteState(savedState.stateFilePath)
    }

    @Test
    fun failClosed_corruptedCheckpointAndCorruptedGuard_rejectResumeAndPreserveArtifacts() = runBlocking {
        // Case A: Corrupted .tailswap.ckpt + .bak
        val fixtureA = createTwoEntryZip("corrupt_ckpt", largeSizeBytes = 7_500_000, seed = 55002L)
        val destA = File(fixtureA.outputDir, fixtureA.largeEntryName)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.extractArchive(fixtureA.archiveFile, fixtureA.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val stateA = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixtureA.archiveFile.absolutePath }
        val ckptFileA = ResumeJournalManager.getDeflateTailSwapCheckpointFile(stateA.stateFilePath)
        val ckptBakA = File("${ckptFileA.absolutePath}.bak")
        ckptFileA.writeBytes(ByteArray(128) { 0x33 })
        if (ckptBakA.exists()) ckptBakA.writeBytes(ByteArray(128) { 0x44 })

        assertTrue(ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(stateA.stateFilePath))
        assertTrue(ResumeJournalManager.assessResumeEligibility(stateA, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertFalse(ResumeJournalManager.isStateSafelyResumable(stateA, context))
        assertEquals(ExtractionStatus.ERROR, KaiHatchEngine.resumeInterruptedExtraction(context, stateA).toList().last().status)
        assertTrue(fixtureA.archiveFile.exists())
        assertTrue(destA.exists())
        ResumeJournalManager.deleteState(stateA.stateFilePath)

        // Case B: Corrupted TailSwapGuardState + .bak (even if .tailswap.ckpt is intact)
        val fixtureB = createTwoEntryZip("corrupt_guard", largeSizeBytes = 7_500_000, seed = 55003L)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.extractArchive(fixtureB.archiveFile, fixtureB.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val stateB = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixtureB.archiveFile.absolutePath }
        val guardFileB = File("${stateB.stateFilePath}.tailswap.guard")
        val guardBakB = File("${guardFileB.absolutePath}.bak")
        guardFileB.writeBytes(ByteArray(64) { 0x77 })
        if (guardBakB.exists()) guardBakB.writeBytes(ByteArray(64) { 0x88.toByte() })

        assertTrue(ResumeJournalManager.hasCorruptedTailSwapGuard(stateB.stateFilePath))
        assertTrue(ResumeJournalManager.assessResumeEligibility(stateB, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertFalse(ResumeJournalManager.isStateSafelyResumable(stateB, context))
        assertEquals(ExtractionStatus.ERROR, KaiHatchEngine.resumeInterruptedExtraction(context, stateB).toList().last().status)
        assertTrue(fixtureB.archiveFile.exists())
        ResumeJournalManager.deleteState(stateB.stateFilePath)
    }

    @Test
    fun failClosed_corruptedPartialOutputOrCorruptedArchiveTailOrSlot_rejectsResume() = runBlocking {
        // Case A: Partial output 1-bit flip
        val fixtureA = createTwoEntryZip("tamper_out", largeSizeBytes = 7_500_000, seed = 56001L)
        val destA = File(fixtureA.outputDir, fixtureA.largeEntryName)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.extractArchive(fixtureA.archiveFile, fixtureA.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val stateA = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixtureA.archiveFile.absolutePath }
        RandomAccessFile(destA, "rw").use { raf ->
            raf.seek(4096L)
            val b = raf.read()
            raf.seek(4096L)
            raf.write(b xor 0x01)
            raf.fd.sync()
        }
        assertTrue(ResumeJournalManager.assessResumeEligibility(stateA, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertEquals(ExtractionStatus.ERROR, KaiHatchEngine.resumeInterruptedExtraction(context, stateA).toList().last().status)
        assertTrue(fixtureA.archiveFile.exists())
        ResumeJournalManager.deleteState(stateA.stateFilePath)

        // Case B: Tail chunk corrupted during PREPARED phase
        val fixtureB = createTwoEntryZip("tamper_tail", largeSizeBytes = 7_500_000, seed = 56002L)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true
        )
        KaiHatchEngine.extractArchive(fixtureB.archiveFile, fixtureB.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val stateB = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixtureB.archiveFile.absolutePath }
        val ckptB = ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateB.stateFilePath)!!
        RandomAccessFile(fixtureB.archiveFile, "rw").use { raf ->
            raf.seek(ckptB.pendingTruncateTarget + 256L)
            val b = raf.read()
            raf.seek(ckptB.pendingTruncateTarget + 256L)
            raf.write(b xor 0x80)
            raf.fd.sync()
        }
        assertTrue(ResumeJournalManager.assessResumeEligibility(stateB, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertEquals(ExtractionStatus.ERROR, KaiHatchEngine.resumeInterruptedExtraction(context, stateB).toList().last().status)
        assertTrue(fixtureB.archiveFile.exists())
        ResumeJournalManager.deleteState(stateB.stateFilePath)

        // Case C: Relocated slot corrupted during RELOCATED_TRUNCATE_PENDING phase
        val fixtureC = createTwoEntryZip("tamper_slot", largeSizeBytes = 7_500_000, seed = 56003L)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.extractArchive(fixtureC.archiveFile, fixtureC.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val stateC = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixtureC.archiveFile.absolutePath }
        val ckptC = ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateC.stateFilePath)!!
        val slotPos = ckptC.payloadOffset + ckptC.pendingSlotIndex.toLong() * ckptC.chunkStep.toLong()
        RandomAccessFile(fixtureC.archiveFile, "rw").use { raf ->
            raf.seek(slotPos + 512L)
            val b = raf.read()
            raf.seek(slotPos + 512L)
            raf.write(b xor 0xFF)
            raf.fd.sync()
        }
        assertTrue(ResumeJournalManager.assessResumeEligibility(stateC, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertEquals(ExtractionStatus.ERROR, KaiHatchEngine.resumeInterruptedExtraction(context, stateC).toList().last().status)
        assertTrue(fixtureC.archiveFile.exists())
        ResumeJournalManager.deleteState(stateC.stateFilePath)

        // Case D: Source archive truncated below pendingTruncateTarget
        val fixtureD = createTwoEntryZip("short_archive", largeSizeBytes = 7_500_000, seed = 56004L)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.extractArchive(fixtureD.archiveFile, fixtureD.outputDir, isHatchMode = true, context = context).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val stateD = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixtureD.archiveFile.absolutePath }
        val ckptD = ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateD.stateFilePath)!!
        RandomAccessFile(fixtureD.archiveFile, "rw").use { raf ->
            raf.setLength((ckptD.pendingTruncateTarget - 4096L).coerceAtLeast(0L))
            raf.fd.sync()
        }
        assertTrue(ResumeJournalManager.assessResumeEligibility(stateD, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertEquals(ExtractionStatus.ERROR, KaiHatchEngine.resumeInterruptedExtraction(context, stateD).toList().last().status)
        assertTrue(fixtureD.archiveFile.exists())
        ResumeJournalManager.deleteState(stateD.stateFilePath)
    }

    @Test
    fun failClosed_safDestinationAndSafSource_remainRejectedForMutatedTailSwap() = runBlocking {
        val fixture = createTwoEntryZip("saf_rejected", largeSizeBytes = 7_500_000, seed = 57001L)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1
        )
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, isHatchMode = true, context = context)
                .toList().last().status
        )
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val directPosixState = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }

        // Sanity check: genuine direct-POSIX state IS Resumable
        assertEquals(
            ResumeJournalManager.ResumeEligibility.Resumable,
            ResumeJournalManager.assessResumeEligibility(directPosixState, context)
        )

        // 1. SAF destination (non-direct-POSIX targetTreeUriString + app-internal fallback path) MUST fail closed!
        val safDestState: ResumeJournalState = directPosixState.copy(
            targetDirPath = File(context.filesDir, "saf_fallback_out").absolutePath,
            targetTreeUriString = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        )
        val safDestEligibility = ResumeJournalManager.assessResumeEligibility(safDestState, context)
        assertTrue(
            "SAF destination must fail closed for mutated tail-swap recovery, got $safDestEligibility",
            safDestEligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertFalse(ResumeJournalManager.isStateSafelyResumable(safDestState, context))
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.resumeInterruptedExtraction(context, safDestState).toList().last().status
        )

        // 2. SAF source URI MUST fail closed for mutated tail-swap recovery!
        val safSourceState: ResumeJournalState = directPosixState.copy(
            sourceUriString = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Ftest.zip"
        )
        val safSourceEligibility = ResumeJournalManager.assessResumeEligibility(safSourceState, context)
        assertTrue(
            "SAF source must fail closed for mutated tail-swap recovery, got $safSourceEligibility",
            safSourceEligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertFalse(ResumeJournalManager.isStateSafelyResumable(safSourceState, context))
        assertEquals(
            ExtractionStatus.ERROR,
            KaiHatchEngine.resumeInterruptedExtraction(context, safSourceState).toList().last().status
        )

        // Original direct-POSIX artifacts must still be completely untouched
        assertTrue(fixture.archiveFile.exists())
        assertEquals(
            ResumeJournalManager.ResumeEligibility.Resumable,
            ResumeJournalManager.assessResumeEligibility(directPosixState, context)
        )
        ResumeJournalManager.deleteState(directPosixState.stateFilePath)
    }
}
