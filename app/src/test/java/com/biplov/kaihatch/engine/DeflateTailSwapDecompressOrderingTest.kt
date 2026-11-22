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
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression Suite: Decompress-First Ordering for Large DEFLATE Tail-Swap Using Java Inflater.
 *
 * Verifies:
 * 1. Compressed data is never overwritten (`pwrite`) or truncated (`ftruncate`) until the
 *    corresponding chunk's decompressed output is durably written (`fsync`) to the target file.
 * 2. Genuine progressive archive shrinking and peak storage reduction with Java's `Inflater`.
 * 3. Recoverable interruptions:
 *    - Before compressed data is overwritten (`BEFORE_TAILSWAP_PWRITE` #1)
 *    - After all chunks are decompressed/verified, before final slot 0 truncation (`BEFORE_WINDOW_TRUNCATE`)
 * 4. Unrecoverable mid-stream interruptions (clearly distinguished and reported with diagnostic error):
 *    - During torn `pwrite` (`MID_TAILSWAP_PWRITE_TORN` #1)
 *    - After `pwrite` before `ftruncate` (`AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE` #1)
 *    - After mid-entry `ftruncate` (`AFTER_TAILSWAP_TRUNCATE` #1)
 * 5. SHA-256 output correctness, peak storage savings, and extraction speed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeflateTailSwapDecompressOrderingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
    }

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

    private fun generatePayload(size: Int, seed: Long, compressible: Boolean = false): ByteArray {
        val out = ByteArray(size)
        val rng = Random(seed)
        rng.nextBytes(out)
        if (compressible) {
            var offset = 0
            while (offset + 256 <= size) {
                for (i in 0 until 96) {
                    out[offset + i] = ((i + seed) and 0xFF).toByte()
                }
                offset += 256
            }
        }
        return out
    }

    private fun createLargeDeflateZip(zipFile: File, entryName: String, payload: ByteArray): String {
        val expectedSha256 = sha256Hex(payload)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(payload)
            zos.closeEntry()
        }
        return expectedSha256
    }

    // =========================================================================
    // 1. Verify Decompress-First Ordering: Durable Output Written BEFORE pwrite/ftruncate
    // =========================================================================

    @Test
    fun decompressFirstOrdering_verifiesDecompressedOutputWrittenBeforeAnyPwriteOrTruncate() = runBlocking {
        val archiveFile = tempFolder.newFile("ordering_verify.zip")
        val outputDir = tempFolder.newFolder("out_ordering_verify")
        val entryName = "stream/big_deflate.bin"

        val payload = generatePayload(5_200_000, 7101L, compressible = false)
        val expectedSha256 = createLargeDeflateZip(archiveFile, entryName, payload)
        val originalArchiveSize = archiveFile.length()
        val destFile = File(outputDir, entryName)

        val outputLenAtPwrite = mutableListOf<Long>()
        val outputLenAtTruncate = mutableListOf<Long>()
        val peakCombinedDiskBytes = AtomicLong(originalArchiveSize)

        val injector = RecordingFaultInjector { point, _, _ ->
            val currentArchiveLen = if (archiveFile.exists()) archiveFile.length() else 0L
            val currentDestLen = if (destFile.exists()) destFile.length() else 0L
            peakCombinedDiskBytes.accumulateAndGet(currentArchiveLen + currentDestLen, ::maxOf)

            if (point == DestructivePoint.BEFORE_TAILSWAP_PWRITE) {
                outputLenAtPwrite.add(currentDestLen)
            } else if (point == DestructivePoint.AFTER_TAILSWAP_TRUNCATE) {
                outputLenAtTruncate.add(currentDestLen)
            }
        }
        KaiHatchEngine.faultInjector = injector

        val startNs = System.nanoTime()
        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse("Archive must be deleted on completion", archiveFile.exists())
        assertTrue("Extracted file must exist", destFile.exists())
        assertEquals(expectedSha256, sha256Hex(destFile))

        // Verify that before EVERY pwrite relocation, decompressed output for that chunk was already on disk!
        assertTrue("Expected >= 2 pwrite relocations, got ${outputLenAtPwrite.size}", outputLenAtPwrite.size >= 2)
        for ((idx, outLen) in outputLenAtPwrite.withIndex()) {
            assertTrue(
                "At BEFORE_TAILSWAP_PWRITE #$idx, decompressed output on disk must already be > 0 (was $outLen)",
                outLen >= (idx + 1) * 800_000L
            )
            if (idx > 0) {
                assertTrue(
                    "Decompressed output on disk must strictly grow before each subsequent pwrite ($outLen <= ${outputLenAtPwrite[idx - 1]})",
                    outLen > outputLenAtPwrite[idx - 1]
                )
            }
        }

        // Verify progressive physical shrinking and peak storage below naive 2x
        assertTrue("Expected >= 5 tail-swap truncations", outputLenAtTruncate.size >= 5)
        val naivePeak = originalArchiveSize + payload.size
        assertTrue(
            "Peak combined disk usage (${peakCombinedDiskBytes.get()}) must be strictly below naive peak ($naivePeak)",
            peakCombinedDiskBytes.get() < naivePeak
        )
        println(
            String.format(
                "Decompress-First 5.2MB DEFLATE: %.1f ms (%.1f MB/s), Peak Disk: %.2f MB vs Naive: %.2f MB",
                elapsedMs,
                (payload.size / (1024.0 * 1024.0)) / (elapsedMs / 1000.0),
                peakCombinedDiskBytes.get() / (1024.0 * 1024.0),
                naivePeak / (1024.0 * 1024.0)
            )
        )
    }

    // =========================================================================
    // 2. Interruption BEFORE Relocation (`BEFORE_TAILSWAP_PWRITE` #1) -> Recoverable
    // =========================================================================

    @Test
    fun interruptionBeforeCompressedRelocation_beforeTailSwapPwrite_recoversCleanlyWithSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("recoverable_pre_pwrite.zip")
        val outputDir = tempFolder.newFolder("out_pre_pwrite")
        val entryName = "recoverable_big.bin"

        val payload = generatePayload(5_200_000, 7202L, compressible = false)
        val expectedSha256 = createLargeDeflateZip(archiveFile, entryName, payload)
        val destFile = File(outputDir, entryName)

        // Fault inject at BEFORE_TAILSWAP_PWRITE (#1): Chunk 0 is already decompressed & synced to disk,
        // and TailSwapGuardState is saved, but Slot 0 in the archive has NOT been overwritten yet!
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_PWRITE,
            triggerOnOccurrence = 1
        )

        val firstRun = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.ERROR, firstRun.last().status)
        assertTrue("Chunk 0 decompressed output must already be on disk before pwrite", destFile.exists() && destFile.length() >= 800_000L)

        val resumeState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == archiveFile.absolutePath }
        assertNotNull("Expected resume journal state", resumeState)
        assertNotNull("Expected TailSwapGuardState to exist", ResumeJournalManager.loadTailSwapGuard(resumeState!!.stateFilePath))

        // Resume: engine verifies Slot 0 CRC matches original Chunk 0 CRC, restarts entry cleanly, and completes!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumed = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = resumeState
        ).toList()

        assertEquals(
            "Pre-pwrite interruption must recover cleanly: ${resumed.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            resumed.last().status
        )
        assertFalse("Archive must be deleted after successful recovery", archiveFile.exists())
        assertEquals(expectedSha256, sha256Hex(destFile))
    }

    // =========================================================================
    // 3. Interruption DURING Relocation (`MID_TAILSWAP_PWRITE_TORN` #1) -> Unrecoverable
    // =========================================================================

    @Test
    fun interruptionDuringCompressedRelocation_midTailSwapTornPwrite_detectedAsUnrecoverable() = runBlocking {
        val archiveFile = tempFolder.newFile("torn_pwrite.zip")
        val outputDir = tempFolder.newFolder("out_torn_pwrite")
        val entryName = "torn_big.bin"

        val payload = generatePayload(5_200_000, 7303L, compressible = false)
        createLargeDeflateZip(archiveFile, entryName, payload)
        val destFile = File(outputDir, entryName)

        // Fault inject mid-pwrite (torn write of Slot 0)
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true
        )

        val firstRun = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.ERROR, firstRun.last().status)
        // Confirm Chunk 0 output was durably written BEFORE the torn pwrite occurred
        assertTrue("Chunk 0 output must be on disk before torn pwrite", destFile.exists() && destFile.length() >= 800_000L)

        val resumeState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == archiveFile.absolutePath }
        assertNotNull(resumeState)

        // Part 3A: with valid v2 checkpoint intact, Resume is eligible while Restore remains Unrecoverable.
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertTrue(
            "Valid v2 checkpoint at torn pwrite must be safely resumable in Part 3A",
            ResumeJournalManager.isStateSafelyResumable(resumeState!!, context)
        )
        assertTrue(
            "Mutated torn pwrite state must remain Unrecoverable for Restore",
            ResumeJournalManager.assessRestoreEligibility(resumeState, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )

        // Legacy fail-closed guard test (without v2 checkpoint): must detect Slot 0 overwrite and report error
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(resumeState.stateFilePath)
        val resumed = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = resumeState
        ).toList()

        assertEquals(ExtractionStatus.ERROR, resumed.last().status)
        val errMsg = resumed.last().errorMessage ?: ""
        assertTrue(
            "Error message must clearly identify overwritten head slot 0 via pwrite, got: $errMsg",
            errMsg.contains("compressed head slot 0 was already overwritten via pwrite") &&
                    errMsg.contains("Java Inflater cannot resume mid-stream")
        )
        assertTrue("Archive file must be preserved on unrecoverable error", archiveFile.exists())
        ResumeJournalManager.deleteState(resumeState.stateFilePath)
    }

    // =========================================================================
    // 4. Interruption AFTER Relocation Before Truncate (`AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE` #1)
    // =========================================================================

    @Test
    fun interruptionAfterPwriteBeforeTruncate_detectedAsUnrecoverableWithSlotCrcMismatch() = runBlocking {
        val archiveFile = tempFolder.newFile("after_pwrite.zip")
        val outputDir = tempFolder.newFolder("out_after_pwrite")
        val entryName = "after_pwrite_big.bin"

        val payload = generatePayload(5_200_000, 7404L, compressible = false)
        createLargeDeflateZip(archiveFile, entryName, payload)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1
        )

        val firstRun = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, firstRun.last().status)

        val resumeState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == archiveFile.absolutePath }!!

        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertTrue(
            "Valid v2 checkpoint after pwrite must be safely resumable in Part 3A",
            ResumeJournalManager.isStateSafelyResumable(resumeState, context)
        )
        // Legacy fail-closed guard test (without v2 checkpoint): must detect Slot 0 CRC mismatch
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(resumeState.stateFilePath)
        val resumed = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = resumeState
        ).toList()

        assertEquals(ExtractionStatus.ERROR, resumed.last().status)
        val errMsg = resumed.last().errorMessage ?: ""
        assertTrue(
            "Expected explicit pwrite overwrite error, got: $errMsg",
            errMsg.contains("compressed head slot 0 was already overwritten via pwrite")
        )
        assertTrue("Archive must not be deleted", archiveFile.exists())
        ResumeJournalManager.deleteState(resumeState.stateFilePath)
    }

    // =========================================================================
    // 5. Interruption After Mid-Entry Truncate (Unrecoverable) vs. Final Truncate (Recoverable)
    // =========================================================================

    @Test
    fun interruptionAfterMidEntryTruncateVsFinalEntryTruncate_distinguishesUnrecoverableFromCompleted() = runBlocking {
        // Part A: Crash after 1st mid-entry truncation (`AFTER_TAILSWAP_TRUNCATE` #1) -> Unrecoverable
        val archiveA = tempFolder.newFile("mid_trunc.zip")
        val outDirA = tempFolder.newFolder("out_mid_trunc")
        val payloadA = generatePayload(5_200_000, 7505L, compressible = false)
        createLargeDeflateZip(archiveA, "mid_trunc.bin", payloadA)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1
        )

        val runA = KaiHatchEngine.extractArchive(
            archiveFile = archiveA,
            targetDir = outDirA,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, runA.last().status)

        val stateA = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == archiveA.absolutePath }!!

        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertTrue(
            "Valid v2 checkpoint after mid-entry truncate must be safely resumable in Part 3A",
            ResumeJournalManager.isStateSafelyResumable(stateA, context)
        )
        // Legacy fail-closed guard test (without v2 checkpoint): must report overwritten and truncated
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateA.stateFilePath)
        val resumedA = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = stateA
        ).toList()

        assertEquals(ExtractionStatus.ERROR, resumedA.last().status)
        val errMsgA = resumedA.last().errorMessage ?: ""
        assertTrue(
            "Expected explicit overwritten and truncated error message, got: $errMsgA",
            errMsgA.contains("compressed data was already overwritten and truncated")
        )
        assertTrue(archiveA.exists())
        ResumeJournalManager.deleteState(stateA.stateFilePath)

        // Part B: Crash right before final slot 0 / Local File Header truncation (`BEFORE_WINDOW_TRUNCATE` #1),
        // AFTER all chunks have been decompressed, flushed, and CRC32-verified -> 100% Recoverable!
        val archiveB = tempFolder.newFile("final_trunc.zip")
        val outDirB = tempFolder.newFolder("out_final_trunc")
        val payloadB = generatePayload(5_200_000, 7606L, compressible = false)
        val expectedShaB = createLargeDeflateZip(archiveB, "final_trunc.bin", payloadB)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_WINDOW_TRUNCATE,
            triggerOnOccurrence = 1
        )

        val runB = KaiHatchEngine.extractArchive(
            archiveFile = archiveB,
            targetDir = outDirB,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, runB.last().status)

        val stateB = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == archiveB.absolutePath }!!

        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumedB = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = stateB
        ).toList()

        assertEquals(
            "Crash after full entry decompression (before final header truncation) must recover cleanly: ${resumedB.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            resumedB.last().status
        )
        assertFalse(archiveB.exists())
        assertEquals(expectedShaB, sha256Hex(File(outDirB, "final_trunc.bin")))
    }

    // =========================================================================
    // 6. Adaptive Chunk-Size Cap Optimization (>32 MB Compressed DEFLATE Entries)
    // =========================================================================

    @Test
    fun adaptiveLargeDeflateChunkStep_reducesChunkCountAndRandomIoForMultiMegabyteArchives() = runBlocking {
        System.gc()
        val normalHeadroom = 256L * 1024L * 1024L

        // Verify adaptive chunk-step scaling & 4 KB (4096-byte) page alignment
        val step5Mb = KaiHatchEngine.computeLargeDeflateChunkStep(5_200_000L, normalHeadroom)
        val step32Mb = KaiHatchEngine.computeLargeDeflateChunkStep(32L * 1024L * 1024L, normalHeadroom)
        val step36_5MiB = KaiHatchEngine.computeLargeDeflateChunkStep(38_273_024L, normalHeadroom) // 36.50 MiB exact
        val step36_52MbUnaligned = KaiHatchEngine.computeLargeDeflateChunkStep(38_294_437L, normalHeadroom) // ~36.52 MiB (328.69 MB / 9)
        val step64Mb = KaiHatchEngine.computeLargeDeflateChunkStep(64L * 1024L * 1024L, normalHeadroom)
        val step128Mb = KaiHatchEngine.computeLargeDeflateChunkStep(128L * 1024L * 1024L, normalHeadroom)
        val step256Mb = KaiHatchEngine.computeLargeDeflateChunkStep(256L * 1024L * 1024L, normalHeadroom)
        val step2Gb = KaiHatchEngine.computeLargeDeflateChunkStep(2048L * 1024L * 1024L, normalHeadroom)

        assertEquals(1 * 1024 * 1024, step5Mb)
        assertEquals(4 * 1024 * 1024, step32Mb)
        assertEquals(4_784_128, step36_5MiB)
        assertEquals(4_788_224, step36_52MbUnaligned)
        assertEquals(8 * 1024 * 1024, step64Mb)
        assertEquals(16 * 1024 * 1024, step128Mb)
        assertEquals(32 * 1024 * 1024, step256Mb)
        assertEquals(32 * 1024 * 1024, step2Gb)

        // Verify memory-constrained headroom tiers also preserve 4 KB page alignment
        val stepLowMem = KaiHatchEngine.computeLargeDeflateChunkStep(38_294_437L, 32L * 1024L * 1024L)
        assertEquals(4 * 1024 * 1024, stepLowMem)
        assertEquals(0, stepLowMem % 4096)

        // Verify strict 4 KB (4096-byte) page alignment across arbitrary / unaligned compressed sizes
        val arbitrarySizes = listOf(
            4_194_305L,
            5_200_000L,
            33_554_437L,
            38_273_024L,
            38_294_437L,
            40_123_456L,
            67_108_871L,
            150_999_111L,
            344_649_933L
        )
        for (size in arbitrarySizes) {
            val stepDefault = KaiHatchEngine.computeLargeDeflateChunkStep(size)
            val stepNormal = KaiHatchEngine.computeLargeDeflateChunkStep(size, normalHeadroom)
            assertEquals("Default chunkStep ($stepDefault) for size=$size must be 4 KB aligned", 0, stepDefault % 4096)
            assertEquals("Normal-heap chunkStep ($stepNormal) for size=$size must be 4 KB aligned", 0, stepNormal % 4096)
        }

        // Verify ~36.5 MB entry chunk counts and remainder handling
        val totalChunks36_5MiB = ((38_273_024L + step36_5MiB - 1L) / step36_5MiB).toInt()
        val totalChunks36_52Mb = ((38_294_437L + step36_52MbUnaligned - 1L) / step36_52MbUnaligned).toInt()
        assertEquals(8, totalChunks36_5MiB)
        assertEquals(8, totalChunks36_52Mb)
        val finalRemainder36_52Mb = (38_294_437L - 7L * step36_52MbUnaligned).toInt()
        assertEquals(4_776_869, finalRemainder36_52Mb)
        assertTrue("Final remainder ($finalRemainder36_52Mb) must be in 1..$step36_52MbUnaligned", finalRemainder36_52Mb in 1..step36_52MbUnaligned)

        // End-to-end 64 MB incompressible DEFLATE extraction in Hatch Mode
        val archiveFile = tempFolder.newFile("large_64mb_deflate.zip")
        val outputDir = tempFolder.newFolder("out_64mb_deflate")
        val entryName = "dataset_64mb.bin"
        val payloadSize = 64 * 1024 * 1024
        val payload = generatePayload(payloadSize, 7909L, compressible = false)
        val expectedSha256 = createLargeDeflateZip(archiveFile, entryName, payload)
        System.gc()

        val injector = RecordingFaultInjector()
        KaiHatchEngine.faultInjector = injector

        val startNs = System.nanoTime()
        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse("Source archive must be deleted after completion", archiveFile.exists())
        val destFile = File(outputDir, entryName)
        assertTrue(destFile.exists())
        assertEquals(expectedSha256, sha256Hex(destFile))

        val pwriteCount = injector.getCount(DestructivePoint.BEFORE_TAILSWAP_PWRITE)
        val truncateCount = injector.getCount(DestructivePoint.AFTER_TAILSWAP_TRUNCATE)

        assertTrue("Expected <= 8 pwrite relocations for 64 MB archive, got $pwriteCount", pwriteCount in 2..8)
        assertTrue("Expected <= 17 tail-swap truncations for 64 MB archive, got $truncateCount", truncateCount in 4..17)

        println(
            String.format(
                "Simulated JVM Benchmark (64 MB DEFLATE Hatch Mode): %.1f ms (%.1f MB/s), pwrites=%d, truncates=%d, chunkStep=%d KB",
                elapsedMs,
                (payloadSize / (1024.0 * 1024.0)) / (elapsedMs / 1000.0),
                pwriteCount,
                truncateCount,
                step64Mb / 1024
            )
        )
    }
}
