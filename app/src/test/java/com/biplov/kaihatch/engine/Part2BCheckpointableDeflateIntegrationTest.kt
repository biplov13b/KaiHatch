package com.biplov.kaihatch.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
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
 * Part 2B Validation Suite: Checkpointable DEFLATE Integration in Large DEFLATE Tail-Swap Path.
 *
 * Verifies:
 * 1. Multi-chunk DEFLATE decoding via `CheckpointableInflateEngine` across mixed Huffman/incompressible
 *    payloads produces bit-exact CRC32 & SHA-256 output, preserves progressive reclamation, and coexists
 *    with single-chunk and small-entry native Inflater paths.
 * 2. Durable, checksummed `DeflateTailSwapCheckpoint` save/load round-trip, interrupted-write recovery,
 *    `.bak` fallback, corruption rejection, and cross-field validation.
 * 3. Mid-tail-swap crash persists complete DEFLATE decoder state (`compressedBytesConsumed`,
 *    `outputBytesWritten`, `runningCrc32`, 32 KB window, Huffman tables, pending tail bytes) AND
 *    relocation metadata (`relocatedSlot`, `relocatedLen`, `lastCompletedChunkIndex`, `chunkStep`),
 *    sufficient to reconstruct bit-exact output from the mutated/truncated archive, while strictly
 *    maintaining fail-closed Resume/Restore safeguards until Part 2C.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Part2BCheckpointableDeflateIntegrationTest {

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

    private class InjectedFault(msg: String) : RuntimeException(msg)

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
     * Builds a mixed payload containing alternating high-entropy segments (forcing multi-chunk
     * compressed size > 5 MB) and structured repeating segments with long back-references
     * (exercising dynamic Huffman tables and 32 KB LZ77 sliding window wrap-around across chunk cuts).
     */
    private fun generateMixedDeflatePayload(sizeBytes: Int, seed: Long): ByteArray {
        val out = ByteArray(sizeBytes)
        val rng = Random(seed)
        val pattern = ByteArray(4096) { i -> ((i * 73 + seed.toInt()) and 0xFF).toByte() }
        var pos = 0
        var blockIdx = 0
        while (pos < sizeBytes) {
            val segLen = minOf(64 * 1024, sizeBytes - pos)
            if (blockIdx % 4 == 1) {
                // Repeating structured pattern to generate long-distance LZ77 matches
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

    private fun generateHighlyCompressiblePayload(sizeBytes: Int): ByteArray {
        val out = ByteArray(sizeBytes)
        val phrase = "KaiHatch-Part2B-SingleChunk-Fallback-Verification-0123456789\n".toByteArray(Charsets.UTF_8)
        var pos = 0
        while (pos < sizeBytes) {
            val step = minOf(phrase.size, sizeBytes - pos)
            System.arraycopy(phrase, 0, out, pos, step)
            pos += step
        }
        return out
    }

    // =========================================================================
    // 1. Multi-Chunk DEFLATE Tail-Swap Extraction + Compatibility With Native Paths
    // =========================================================================

    @Test
    fun multiChunkDeflateTailSwap_extractsMixedBlocksWithExactCrcAndSha256_andCleansUpCheckpoints() = runBlocking {
        val archiveFile = tempFolder.newFile("part2b_mixed_multi_entry.zip")
        val outputDir = tempFolder.newFolder("out_part2b_mixed")

        val multiChunkPayload = generateMixedDeflatePayload(sizeBytes = 6_800_000, seed = 20261L)
        val singleChunkLargePayload = generateHighlyCompressiblePayload(sizeBytes = 5_000_000)
        val smallEntryPayload = generateMixedDeflatePayload(sizeBytes = 128 * 1024, seed = 20262L)

        val expectedMultiSha = sha256Hex(multiChunkPayload)
        val expectedSingleSha = sha256Hex(singleChunkLargePayload)
        val expectedSmallSha = sha256Hex(smallEntryPayload)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)

            zos.putNextEntry(ZipEntry("01_multi_chunk_mixed.bin"))
            zos.write(multiChunkPayload)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("02_single_chunk_compressible.txt"))
            zos.write(singleChunkLargePayload)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("03_small_entry.bin"))
            zos.write(smallEntryPayload)
            zos.closeEntry()
        }

        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.name)
        val injector = RecordingFaultInjector()
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(
            "Extraction must complete cleanly: ${emissions.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            emissions.last().status
        )
        assertFalse("Source archive must be deleted after Hatch completion", archiveFile.exists())

        assertEquals(expectedMultiSha, sha256Hex(File(outputDir, "01_multi_chunk_mixed.bin")))
        assertEquals(expectedSingleSha, sha256Hex(File(outputDir, "02_single_chunk_compressible.txt")))
        assertEquals(expectedSmallSha, sha256Hex(File(outputDir, "03_small_entry.bin")))

        assertTrue(
            "Multi-chunk DEFLATE entry must execute tail-swap pwrite relocations",
            injector.getCount(DestructivePoint.BEFORE_TAILSWAP_PWRITE) >= 2
        )
        assertTrue(
            "Multi-chunk DEFLATE entry must execute progressive tail-swap truncations",
            injector.getCount(DestructivePoint.AFTER_TAILSWAP_TRUNCATE) >= 5
        )

        // All transient recovery artifacts must be cleaned up on completion
        assertNull(ResumeJournalManager.loadTailSwapGuard(stateFile.absolutePath))
        assertNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath))
        assertFalse(ResumeJournalManager.getDeflateTailSwapCheckpointFile(stateFile.absolutePath).exists())
    }

    // =========================================================================
    // 2. DeflateTailSwapCheckpoint Round-Trip, Fault-Injected Saves & Corruption Handling
    // =========================================================================

    @Test
    fun deflateTailSwapCheckpoint_saveLoadInterruptedWritesAndCorruptionFallback() {
        val stateFile = File(tempFolder.newFolder("part2b_ckpt_dir"), "archive.hatchstate.json")

        // Produce two real CheckpointableInflateEngine snapshots at chunk 0 and chunk 1
        val rawData = generateMixedDeflatePayload(sizeBytes = 256 * 1024, seed = 20263L)
        val compressed = ByteArray(rawData.size + 4096)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(rawData)
        deflater.finish()
        val compLen = deflater.deflate(compressed)
        deflater.end()

        val chunkStep = (compLen + 2) / 3
        val split1 = chunkStep
        val split2 = chunkStep * 2

        val engine = CheckpointableInflateEngine()
        val outBuf = java.io.ByteArrayOutputStream()
        engine.processChunk(compressed, 0, split1, isLastChunk = false, output = outBuf)
        val snap1 = engine.saveCheckpointBytes()
        val ckpt1 = ResumeJournalManager.DeflateTailSwapCheckpoint(
            entryPath = "folder/big.bin",
            entryName = "big.bin",
            payloadOffset = 64L,
            compressedSize = compLen.toLong(),
            uncompressedSize = rawData.size.toLong(),
            expectedEntryCrc32 = crc32Of(rawData),
            chunkStep = chunkStep,
            totalChunks = 3,
            lastCompletedChunkIndex = 0,
            relocationCommittedForChunk = true,
            canSwapInPlace = true,
            hasMutatedArchive = true,
            currentArchiveBytes = 64L + split2.toLong(),
            compressedBytesConsumed = engine.compressedBytesConsumed,
            outputBytesWritten = engine.outputBytesWritten,
            runningCrc32 = engine.currentCrc32,
            relocatedSlot = intArrayOf(-1, -1, 0),
            relocatedLen = intArrayOf(0, 0, compLen - split2),
            inflateCheckpointBytes = snap1
        )
        assertTrue(ResumeJournalManager.saveDeflateTailSwapCheckpoint(stateFile.absolutePath, ckpt1))
        assertEquals(ckpt1, ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath))

        engine.processChunk(compressed, split1, split2 - split1, isLastChunk = false, output = outBuf)
        val snap2 = engine.saveCheckpointBytes()
        val ckpt2 = ckpt1.copy(
            lastCompletedChunkIndex = 1,
            relocationCommittedForChunk = false,
            currentArchiveBytes = 64L + split2.toLong(),
            compressedBytesConsumed = engine.compressedBytesConsumed,
            outputBytesWritten = engine.outputBytesWritten,
            runningCrc32 = engine.currentCrc32,
            relocatedSlot = intArrayOf(-1, -1, 0),
            relocatedLen = intArrayOf(0, 0, compLen - split2),
            inflateCheckpointBytes = snap2
        )

        // Fault A: Interrupted mid-.tmp write must keep ckpt1 intact
        ResumeJournalManager.persistenceFaultHook = { stage, _, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE) {
                throw InjectedFault("crash mid-tmp")
            }
        }
        try {
            assertFalse(ResumeJournalManager.saveDeflateTailSwapCheckpoint(stateFile.absolutePath, ckpt2))
        } finally {
            ResumeJournalManager.persistenceFaultHook = null
        }
        assertEquals(ckpt1, ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath))

        // Fault B: Interrupted after primary replace before .bak mirror must load ckpt2 from primary
        ResumeJournalManager.persistenceFaultHook = { stage, _, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_PRIMARY_REPLACE_BEFORE_BACKUP) {
                throw InjectedFault("crash before bak")
            }
        }
        try {
            assertFalse(ResumeJournalManager.saveDeflateTailSwapCheckpoint(stateFile.absolutePath, ckpt2))
        } finally {
            ResumeJournalManager.persistenceFaultHook = null
        }
        assertEquals(ckpt2, ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath))

        // Full commit of ckpt2 mirrors to .bak
        assertTrue(ResumeJournalManager.saveDeflateTailSwapCheckpoint(stateFile.absolutePath, ckpt2))

        // Corrupt primary -> must recover ckpt2 from .bak
        val primaryFile = ResumeJournalManager.getDeflateTailSwapCheckpointFile(stateFile.absolutePath)
        val bakFile = File("${primaryFile.absolutePath}.bak")
        assertTrue(primaryFile.exists() && bakFile.exists())
        primaryFile.writeBytes(ByteArray(128) { 0x5A.toByte() })
        assertEquals(ckpt2, ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath))
        assertFalse(ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(stateFile.absolutePath))

        // Corrupt both primary & .bak -> must fail closed (null + corrupted flag)
        bakFile.writeBytes(ByteArray(128) { 0x33.toByte() })
        assertNull(ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath))
        assertTrue(ResumeJournalManager.hasCorruptedDeflateTailSwapCheckpoint(stateFile.absolutePath))

        // Cross-field inconsistency (outer runningCrc32 disagrees with embedded inflate snapshot) -> rejected
        val mismatched = ckpt2.copy(runningCrc32 = ckpt2.runningCrc32 xor 0xFFL)
        assertTrue(ResumeJournalManager.saveDeflateTailSwapCheckpoint(stateFile.absolutePath, mismatched))
        assertNull(
            "Checkpoint with outer/inner CRC mismatch must be rejected on load",
            ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath)
        )

        // deleteState cleans up all .tailswap.ckpt* artifacts
        ResumeJournalManager.deleteState(stateFile.absolutePath)
        assertFalse(primaryFile.exists())
        assertFalse(bakFile.exists())
    }

    // =========================================================================
    // 3. Mid-Tail-Swap Interruption: Persisted Decoder + Relocation Metadata
    //    Reconstructs Bit-Exact Output While Remaining Strictly Fail-Closed
    // =========================================================================

    @Test
    fun midTailSwapCrash_persistsDecoderAndRelocationMetadata_reconstructsExactOutputWhileRemainingFailClosed() = runBlocking {
        val archiveFile = tempFolder.newFile("part2b_mid_crash.zip")
        val outputDir = tempFolder.newFolder("out_part2b_mid_crash")
        val entryName = "payload/large_mixed_stream.bin"

        // ~7.5 MB mixed payload -> compressed > 5.6 MB -> 6 chunks at 1 MB chunkStep
        val payload = generateMixedDeflatePayload(sizeBytes = 7_500_000, seed = 20264L)
        val expectedSha = sha256Hex(payload)
        val expectedCrc = crc32Of(payload)
        val destFile = File(outputDir, entryName)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(payload)
            zos.closeEntry()
        }

        // Crash at BEFORE_TAILSWAP_PWRITE #3 (chunk c=2):
        // - Chunks 0 and 1 have already been decompressed, relocated via pwrite (tailIdx=N-1 -> slot 0,
        //   tailIdx=N-2 -> slot 1), and physically truncated via ftruncate!
        // - Chunk 2 has just been decompressed, flushed/synced to destFile, and checkpointed BEFORE chunk 2's pwrite!
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_PWRITE,
            triggerOnOccurrence = 3
        )

        val crashedRun = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.ERROR, crashedRun.last().status)
        assertTrue("Partial DEFLATE output must be preserved on disk", destFile.exists() && destFile.length() > 0L)

        val savedState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == archiveFile.absolutePath }
        assertNotNull("Pending resume journal must be preserved", savedState)

        // Part 3A: valid v2 checkpoint is eligible for direct-POSIX Resume, while Restore remains Unrecoverable!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertEquals(
            "Valid v2 mutated tail-swap state must be Resumable for direct-POSIX Resume in Part 3A",
            ResumeJournalManager.ResumeEligibility.Resumable,
            ResumeJournalManager.assessResumeEligibility(savedState!!, context)
        )
        assertTrue(
            "Mutated tail-swap state must remain Unrecoverable for Restore",
            ResumeJournalManager.assessRestoreEligibility(savedState, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )

        // Verify persisted DeflateTailSwapCheckpoint metadata & relocation map
        val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(savedState.stateFilePath)
        assertNotNull("DeflateTailSwapCheckpoint must be persisted and preserved after mid-tail-swap crash", ckpt)
        ckpt!!
        assertEquals(entryName, ckpt.entryPath)
        assertEquals(payload.size.toLong(), ckpt.uncompressedSize)
        assertEquals(expectedCrc, ckpt.expectedEntryCrc32)
        assertTrue("Expected >= 6 chunks for >5 MB compressed payload, got ${ckpt.totalChunks}", ckpt.totalChunks >= 6)
        assertEquals(2, ckpt.lastCompletedChunkIndex)
        assertFalse("Crash occurred before chunk 2 relocation commit", ckpt.relocationCommittedForChunk)
        assertTrue("Archive was already mutated during chunks 0 and 1", ckpt.hasMutatedArchive)
        assertTrue("Current archive size must be truncated below full payload end", ckpt.currentArchiveBytes < ckpt.payloadOffset + ckpt.compressedSize)

        // Verify relocation slots for tail chunks relocated in chunks 0 and 1 were persisted from RAM to disk!
        val lastIdx = ckpt.totalChunks - 1
        val secondLastIdx = ckpt.totalChunks - 2
        assertEquals("Chunk $lastIdx must be recorded as relocated into slot 0", 0, ckpt.relocatedSlot[lastIdx])
        assertTrue("Relocated length for chunk $lastIdx must be > 0", ckpt.relocatedLen[lastIdx] > 0)
        assertEquals("Chunk $secondLastIdx must be recorded as relocated into slot 1", 1, ckpt.relocatedSlot[secondLastIdx])
        assertEquals("Relocated length for chunk $secondLastIdx must equal chunkStep", ckpt.chunkStep, ckpt.relocatedLen[secondLastIdx])

        // Verify persisted output offset and running CRC32 match the partial output file on disk
        assertEquals(destFile.length(), ckpt.outputBytesWritten)
        assertEquals(crc32Of(destFile), ckpt.runningCrc32)
        assertEquals(3L * ckpt.chunkStep.toLong(), ckpt.compressedBytesConsumed)

        // Prove Part 2B checkpoint completeness: restore a brand-new CheckpointableInflateEngine from
        // the persisted checkpoint and finish decoding chunks 3..lastIdx from the MUTATED, TRUNCATED archive
        // using ONLY the persisted relocation metadata, reproducing the exact full payload bit-for-bit!
        val restoredInflater = CheckpointableInflateEngine()
        restoredInflater.restoreFromCheckpointBytes(ckpt.inflateCheckpointBytes)
        assertEquals(ckpt.compressedBytesConsumed, restoredInflater.compressedBytesConsumed)
        assertEquals(ckpt.outputBytesWritten, restoredInflater.outputBytesWritten)
        assertEquals(ckpt.runningCrc32, restoredInflater.currentCrc32)

        RandomAccessFile(archiveFile, "r").use { raf ->
            FileOutputStream(destFile, /* append = */ true).use { appendOut ->
                BufferedOutputStream(appendOut, 64 * 1024).use { bos ->
                    for (c in (ckpt.lastCompletedChunkIndex + 1) until ckpt.totalChunks) {
                        val slot = ckpt.relocatedSlot[c]
                        val readPos = if (slot >= 0) {
                            ckpt.payloadOffset + slot.toLong() * ckpt.chunkStep.toLong()
                        } else {
                            ckpt.payloadOffset + c.toLong() * ckpt.chunkStep.toLong()
                        }
                        val readLen = if (slot >= 0 && ckpt.relocatedLen[c] > 0) {
                            ckpt.relocatedLen[c]
                        } else {
                            val rem = (ckpt.compressedSize - c.toLong() * ckpt.chunkStep.toLong()).coerceAtLeast(0L)
                            rem.coerceAtMost(ckpt.chunkStep.toLong()).toInt()
                        }
                        val buf = ByteArray(readLen)
                        raf.seek(readPos)
                        raf.readFully(buf)
                        restoredInflater.processChunk(
                            chunk = buf,
                            offset = 0,
                            length = readLen,
                            isLastChunk = (c == ckpt.totalChunks - 1),
                            output = bos
                        )
                    }
                    bos.flush()
                }
            }
        }

        assertTrue("Restored CheckpointableInflateEngine must reach MODE_FINISHED", restoredInflater.isFinished)
        assertEquals(payload.size.toLong(), restoredInflater.outputBytesWritten)
        assertEquals(expectedCrc, restoredInflater.currentCrc32)
        assertEquals(payload.size.toLong(), destFile.length())
        assertEquals(expectedSha, sha256Hex(destFile))
        assertArrayEquals(payload, destFile.readBytes())

        ResumeJournalManager.deleteState(savedState.stateFilePath)
    }

    // =========================================================================
    // 4. Post-Relocation Checkpoint Persists Relocation Flag After Chunk 0 Truncate
    // =========================================================================

    @Test
    fun postChunk0Relocation_persistsRelocationCommittedTrueAndRelocatedSlot0BeforeChunk1Starts() = runBlocking {
        val archiveFile = tempFolder.newFile("part2b_post_reloc.zip")
        val outputDir = tempFolder.newFolder("out_part2b_post_reloc")
        val payload = generateMixedDeflatePayload(sizeBytes = 6_200_000, seed = 20265L)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry("post_reloc.bin"))
            zos.write(payload)
            zos.closeEntry()
        }

        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.name)
        var capturedPostRelocCommitted: ResumeJournalManager.DeflateTailSwapCheckpoint? = null
        var capturedBeforeChunk1Pwrite: ResumeJournalManager.DeflateTailSwapCheckpoint? = null

        // Observe the checkpoint on disk right when chunk 1 reaches BEFORE_TAILSWAP_PWRITE (#2),
        // and verify chunk 0's relocation (`relocatedSlot[last] == 0`) was already durably saved!
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_PWRITE,
            triggerOnOccurrence = 2,
            onStepCallback = { point, _, _ ->
                if (point == DestructivePoint.BEFORE_TAILSWAP_PWRITE) {
                    capturedBeforeChunk1Pwrite = ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile.absolutePath)
                }
            }
        )

        val run = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, run.last().status)

        val ckpt = capturedBeforeChunk1Pwrite
        assertNotNull("Expected durable checkpoint before chunk 1 pwrite", ckpt)
        ckpt!!
        assertEquals(1, ckpt.lastCompletedChunkIndex)
        assertFalse(ckpt.relocationCommittedForChunk)
        assertEquals(0, ckpt.relocatedSlot[ckpt.totalChunks - 1])
        assertTrue(ckpt.relocatedLen[ckpt.totalChunks - 1] > 0)
        assertTrue(ckpt.hasMutatedArchive)

        ResumeJournalManager.deleteState(stateFile.absolutePath)

        // Second archive: observe the live post-relocation checkpoint (`relocationCommittedForChunk == true`)
        // emitted immediately after chunk 0's relocation/truncation completes!
        val archive2 = tempFolder.newFile("part2b_post_reloc_obs.zip")
        val outputDir2 = tempFolder.newFolder("out_part2b_post_reloc_obs")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archive2))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry("post_reloc_obs.bin"))
            zos.write(payload)
            zos.closeEntry()
        }
        val stateFile2 = ResumeJournalManager.getStateFileForArchive(context, archive2.name)
        val pauseCtrl = ExtractionPauseController()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        var pausedOnce = false
        pauseCtrl.onBeforeCheckpoint = {
            if (!pausedOnce) {
                val liveCkpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFile2.absolutePath)
                if (liveCkpt != null && liveCkpt.relocationCommittedForChunk && liveCkpt.lastCompletedChunkIndex == 0) {
                    capturedPostRelocCommitted = liveCkpt
                    pausedOnce = true
                }
            }
        }
        val completedRun = KaiHatchEngine.extractArchive(
            archiveFile = archive2,
            targetDir = outputDir2,
            isHatchMode = true,
            context = context,
            pauseController = pauseCtrl
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, completedRun.last().status)
        assertNotNull("Expected post-relocation checkpoint with relocationCommittedForChunk=true after chunk 0", capturedPostRelocCommitted)
        assertEquals(0, capturedPostRelocCommitted!!.lastCompletedChunkIndex)
        assertTrue(capturedPostRelocCommitted!!.relocationCommittedForChunk)
        assertEquals(0, capturedPostRelocCommitted!!.relocatedSlot[capturedPostRelocCommitted!!.totalChunks - 1])
        assertTrue(capturedPostRelocCommitted!!.relocatedLen[capturedPostRelocCommitted!!.totalChunks - 1] > 0)
        assertTrue(capturedPostRelocCommitted!!.hasMutatedArchive)
    }
}
