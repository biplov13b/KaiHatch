package com.biplov.kaihatch.engine

import android.content.Context
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.VerificationLevel
import com.biplov.kaihatch.model.VerificationStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
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
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Restore-After-Tail-Swap Integration Test Suite:
 * Verifies Restore Remaining ZIP after recoverable v2 DEFLATE tail-swap interruptions:
 * - Every transactional crash phase (PREPARED torn pwrite, RELOCATED_TRUNCATE_PENDING across
 *   first-half / odd middle-chunk / second-half, COMMITTED with uncommitted trailing output)
 * - Finishing ONLY the interrupted DEFLATED entry and leaving unrelated earlier entries intact
 *   inside the original ZIP without recompressing payloads or creating a second ZIP
 * - Repeated interruptions across Stage 1 (entry recovery) and Stage 2 (in-place ZIP rebuild)
 * - ZIP64 restoration and independent reader verification (`java.util.zip.ZipFile` and
 *   Apache Commons Compress `ZipFile`)
 * - Fail-closed rejection for single-entry archives, legacy v1 checkpoints, corrupted state,
 *   tampered partial output/archive tail, SAF source/destination, and low disk space
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestoreAfterTailSwapIntegrationTest {

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

    private data class MultiEntryFixture(
        val archiveDir: File,
        val archiveFile: File,
        val outputDir: File,
        val originalArchiveSize: Long,
        val storedEntryName: String,
        val storedPayload: ByteArray,
        val storedCrc: Long,
        val storedSha: String,
        val deflatedEntryName: String,
        val deflatedPayload: ByteArray,
        val deflatedCrc: Long,
        val deflatedSha: String,
        val largeEntryName: String,
        val largePayload: ByteArray,
        val largeCrc: Long,
        val largeSha: String,
        val alreadyExtractedEntryName: String,
        val alreadyExtractedPayload: ByteArray,
        val alreadyExtractedSha: String
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

    private fun createMultiEntryZip(
        tag: String,
        largeSizeBytes: Int,
        seed: Long,
        forceZip64: Boolean = false
    ): MultiEntryFixture {
        val archiveDir = tempFolder.newFolder("src_part3b_${tag}")
        val archiveFile = File(archiveDir, "part3b_${tag}.zip")
        val outputDir = tempFolder.newFolder("out_part3b_${tag}")

        val storedEntryName = "keep/a_stored_blob.bin"
        val deflatedEntryName = "keep/b_deflated_notes.txt"
        val largeEntryName = "interrupted/c_large_stream.bin"
        val alreadyExtractedEntryName = "done/d_already_extracted.txt"

        val storedPayload = ByteArray(96 * 1024).also { Random(seed + 1).nextBytes(it) }
        val deflatedPayload = "Part3B remaining DEFLATED entry [$tag] seed=$seed\n"
            .repeat(600)
            .toByteArray(Charsets.UTF_8)
        val largePayload = generateMixedDeflatePayload(largeSizeBytes, seed)
        val alreadyExtractedPayload = "Already extracted tail entry before large DEFLATE [$tag]\n"
            .repeat(64)
            .toByteArray(Charsets.UTF_8)

        val storedCrc = crc32Of(storedPayload)
        val deflatedCrc = crc32Of(deflatedPayload)
        val largeCrc = crc32Of(largePayload)

        if (forceZip64) {
            ZipArchiveOutputStream(archiveFile).use { zos ->
                zos.setUseZip64(Zip64Mode.Always)
                zos.setLevel(Deflater.DEFAULT_COMPRESSION)

                // Entry 1: STORED (must remain inside restored ZIP, untouched & not recompressed)
                val e1 = ZipArchiveEntry(storedEntryName).apply {
                    method = ZipEntry.STORED
                    size = storedPayload.size.toLong()
                    crc = storedCrc
                }
                zos.putArchiveEntry(e1)
                zos.write(storedPayload)
                zos.closeArchiveEntry()

                // Entry 2: DEFLATED (must remain inside restored ZIP, untouched)
                val e2 = ZipArchiveEntry(deflatedEntryName).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putArchiveEntry(e2)
                zos.write(deflatedPayload)
                zos.closeArchiveEntry()

                // Entry 3: Large DEFLATED (interrupted mid-tail-swap, ONLY this finishes on Restore)
                val e3 = ZipArchiveEntry(largeEntryName).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putArchiveEntry(e3)
                zos.write(largePayload)
                zos.closeArchiveEntry()

                // Entry 4: Tail entry extracted BEFORE Entry 3 in reverse-offset Hatch Mode
                val e4 = ZipArchiveEntry(alreadyExtractedEntryName).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putArchiveEntry(e4)
                zos.write(alreadyExtractedPayload)
                zos.closeArchiveEntry()
            }
        } else {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
                zos.setLevel(Deflater.DEFAULT_COMPRESSION)

                val e1 = ZipEntry(storedEntryName).apply {
                    method = ZipEntry.STORED
                    size = storedPayload.size.toLong()
                    compressedSize = storedPayload.size.toLong()
                    crc = storedCrc
                }
                zos.putNextEntry(e1)
                zos.write(storedPayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(deflatedEntryName))
                zos.write(deflatedPayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(largeEntryName))
                zos.write(largePayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(alreadyExtractedEntryName))
                zos.write(alreadyExtractedPayload)
                zos.closeEntry()
            }
        }

        return MultiEntryFixture(
            archiveDir = archiveDir,
            archiveFile = archiveFile,
            outputDir = outputDir,
            originalArchiveSize = archiveFile.length(),
            storedEntryName = storedEntryName,
            storedPayload = storedPayload,
            storedCrc = storedCrc,
            storedSha = sha256Hex(storedPayload),
            deflatedEntryName = deflatedEntryName,
            deflatedPayload = deflatedPayload,
            deflatedCrc = deflatedCrc,
            deflatedSha = sha256Hex(deflatedPayload),
            largeEntryName = largeEntryName,
            largePayload = largePayload,
            largeCrc = largeCrc,
            largeSha = sha256Hex(largePayload),
            alreadyExtractedEntryName = alreadyExtractedEntryName,
            alreadyExtractedPayload = alreadyExtractedPayload,
            alreadyExtractedSha = sha256Hex(alreadyExtractedPayload)
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
                    message = "Part3B fault at $point #$triggerOnOccurrence ($detail)"
                )
            }
        }
    }

    private fun writeLegacyV1Checkpoint(
        stateFilePath: String,
        ckpt: ResumeJournalManager.DeflateTailSwapCheckpoint
    ) {
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

    /**
     * Validates all Part 3B invariants after a completed Restore:
     * - Only the interrupted DEFLATED entry finished extracting (bit-exact length, CRC32, SHA-256).
     * - Remaining unextracted entries were NOT extracted to disk.
     * - No second ZIP file was created in the source archive's folder.
     * - Original archive shrank in-place and passes KaiHatch structural + full integrity verification.
     * - Both `java.util.zip.ZipFile` and Apache Commons Compress `ZipFile` read exact remaining entries
     *   with original compression methods preserved (no recompression) and bit-exact payloads.
     * - All recovery/restoration journals and checkpoints are cleaned up.
     */
    private suspend fun verifyRestoredMultiEntryOutcome(
        tag: String,
        fixture: MultiEntryFixture,
        stateFilePath: String,
        expectZip64: Boolean = false
    ) {
        val largeDest = File(fixture.outputDir, fixture.largeEntryName)
        val doneDest = File(fixture.outputDir, fixture.alreadyExtractedEntryName)
        val storedDest = File(fixture.outputDir, fixture.storedEntryName)
        val deflatedDest = File(fixture.outputDir, fixture.deflatedEntryName)

        // 1. Only interrupted entry finished (+ previously extracted tail entry remains intact)
        assertTrue("[$tag] Interrupted large DEFLATE output must exist", largeDest.exists())
        assertEquals("[$tag] Large output exact size mismatch", fixture.largePayload.size.toLong(), largeDest.length())
        assertEquals("[$tag] Large output CRC32 mismatch", fixture.largeCrc, crc32Of(largeDest))
        assertEquals("[$tag] Large output SHA-256 mismatch", fixture.largeSha, sha256Hex(largeDest))

        assertTrue("[$tag] Already-extracted tail entry must remain intact", doneDest.exists())
        assertEquals("[$tag] Already-extracted tail entry SHA-256 mismatch", fixture.alreadyExtractedSha, sha256Hex(doneDest))

        // 2. Unrelated remaining entries MUST NOT have been extracted
        assertFalse("[$tag] Unextracted STORED entry must NOT be extracted during Restore", storedDest.exists())
        assertFalse("[$tag] Unextracted DEFLATED entry must NOT be extracted during Restore", deflatedDest.exists())

        // 3. No second ZIP created in archive folder; original archive shrank in-place
        val filesInArchiveDir = fixture.archiveDir.listFiles()?.toList().orEmpty()
        assertEquals(
            "[$tag] Must not create a second ZIP file; found: ${filesInArchiveDir.map { it.name }}",
            listOf(fixture.archiveFile.name),
            filesInArchiveDir.map { it.name }
        )
        assertTrue("[$tag] Original archive must still exist", fixture.archiveFile.exists())
        assertTrue(
            "[$tag] Restored archive must be strictly smaller than original (${fixture.archiveFile.length()} vs ${fixture.originalArchiveSize})",
            fixture.archiveFile.length() < fixture.originalArchiveSize
        )

        // 4. KaiHatch structural & full-integrity verification
        RandomAccessFile(fixture.archiveFile, "r").use { raf ->
            val structErr = ArchiveVerifierEngine.validateZipEocdAndZip64Structure(raf.channel, raf.length())
            assertNull("[$tag] Structural ZIP/ZIP64 validation failed: $structErr", structErr)
            val parsedEntries = ZipParser.parseZipChannel(raf.channel)
            assertEquals("[$tag] ZipParser remaining entry count mismatch", 2, parsedEntries.size)
            if (expectZip64) {
                assertTrue("[$tag] Restored ZIP64 archive must retain ZIP64 metadata", parsedEntries.any { it.isZip64 })
            }
        }
        val verifyStates = ArchiveVerifierEngine.verifyArchive(
            context = context,
            archiveFile = fixture.archiveFile,
            level = VerificationLevel.FULL_VERIFY
        ).toList()
        assertEquals(
            "[$tag] Full integrity verification must pass: ${verifyStates.last()}",
            VerificationStatus.PASSED_FULL_INTEGRITY,
            verifyStates.last().status
        )

        // 5. Independent Reader #1: java.util.zip.ZipFile
        ZipFile(fixture.archiveFile).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            assertEquals(
                "[$tag] java.util.zip.ZipFile entry list mismatch",
                listOf(fixture.storedEntryName, fixture.deflatedEntryName),
                names
            )
            val eStored = zf.getEntry(fixture.storedEntryName)
            assertNotNull(eStored)
            assertEquals("[$tag] STORED entry must not be recompressed", ZipEntry.STORED, eStored.method)
            assertEquals("[$tag] STORED CRC mismatch in java.util.zip.ZipFile", fixture.storedCrc, eStored.crc)
            val storedRead = zf.getInputStream(eStored).use { it.readBytes() }
            assertEquals("[$tag] STORED SHA-256 mismatch in java.util.zip.ZipFile", fixture.storedSha, sha256Hex(storedRead))

            val eDeflated = zf.getEntry(fixture.deflatedEntryName)
            assertNotNull(eDeflated)
            assertEquals("[$tag] DEFLATED entry method must be preserved", ZipEntry.DEFLATED, eDeflated.method)
            assertEquals("[$tag] DEFLATED CRC mismatch in java.util.zip.ZipFile", fixture.deflatedCrc, eDeflated.crc)
            val deflatedRead = zf.getInputStream(eDeflated).use { it.readBytes() }
            assertEquals("[$tag] DEFLATED SHA-256 mismatch in java.util.zip.ZipFile", fixture.deflatedSha, sha256Hex(deflatedRead))
        }

        // 6. Independent Reader #2: Apache Commons Compress ZipFile
        org.apache.commons.compress.archivers.zip.ZipFile.builder()
            .setFile(fixture.archiveFile)
            .get()
            .use { czf ->
                val names = czf.entries.asSequence().map { it.name }.toList()
                assertEquals(
                    "[$tag] Commons Compress ZipFile entry list mismatch",
                    listOf(fixture.storedEntryName, fixture.deflatedEntryName),
                    names
                )
                val ce1 = czf.getEntry(fixture.storedEntryName)
                assertNotNull(ce1)
                assertEquals(ZipEntry.STORED, ce1.method)
                assertEquals(fixture.storedSha, sha256Hex(czf.getInputStream(ce1).use { it.readBytes() }))

                val ce2 = czf.getEntry(fixture.deflatedEntryName)
                assertNotNull(ce2)
                assertEquals(ZipEntry.DEFLATED, ce2.method)
                assertEquals(fixture.deflatedSha, sha256Hex(czf.getInputStream(ce2).use { it.readBytes() }))
            }

        // 7. Journals, guards, checkpoints all cleaned up ONLY after validation succeeded
        assertFalse("[$tag] State file must be deleted after validated Restore", File(stateFilePath).exists())
        assertNull("[$tag] TailSwapGuard must be cleared", ResumeJournalManager.loadTailSwapGuard(stateFilePath))
        assertNull("[$tag] DeflateTailSwapCheckpoint must be cleared", ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateFilePath))
        assertNull("[$tag] RestorationCheckpoint must be cleared", ResumeJournalManager.loadRestorationCheckpoint(stateFilePath))
    }

    private fun runCrashAndRestoreScenario(
        tag: String,
        largeSizeBytes: Int,
        seed: Long,
        triggerPoint: DestructivePoint,
        triggerOnOccurrence: Int,
        simulateTornWrite: Boolean = false,
        expectedTxPhase: ResumeJournalManager.DeflateTailSwapTxPhase,
        expectedPendingOp: ResumeJournalManager.DeflateTailSwapOp,
        appendUncommittedTrailingOutputBytes: Int = 0,
        forceZip64: Boolean = false
    ) = runBlocking {
        val fixture = createMultiEntryZip(tag, largeSizeBytes, seed, forceZip64 = forceZip64)
        val largeDest = File(fixture.outputDir, fixture.largeEntryName)

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
            FileOutputStream(largeDest, true).use { fos ->
                fos.write(ByteArray(appendUncommittedTrailingOutputBytes) { 0x3C })
                fos.flush()
                fos.fd.sync()
            }
            assertEquals(ckpt.outputBytesWritten + appendUncommittedTrailingOutputBytes, largeDest.length())
        }

        // Verify BOTH Resume and Restore are independently eligible as separate user choices!
        assertTrue(
            "[$tag] Resume must remain available as a separate choice",
            ResumeJournalManager.isStateSafelyResumable(savedState, context)
        )
        assertFalse("[$tag] hasPendingRestoration must be false before Restore begins", savedState.hasPendingRestoration)

        val restoreEligibility = ResumeJournalManager.assessRestoreEligibility(savedState, context)
        assertTrue(
            "[$tag] Restore eligibility must be Restorable with pendingDeflateTailSwapEntryPath: $restoreEligibility",
            restoreEligibility is ResumeJournalManager.RestoreEligibility.Restorable &&
                restoreEligibility.remainingCount == 2 &&
                !restoreEligibility.noBytesReclaimed &&
                !restoreEligibility.hasPendingRestoreCheckpoint &&
                restoreEligibility.pendingDeflateTailSwapEntryPath == fixture.largeEntryName
        )

        // Execute PUBLIC Restore Remaining ZIP!
        val restored = KaiHatchEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = savedState
        ).toList()

        assertTrue(
            "[$tag] Restore must emit FINALIZING progress",
            restored.any { it.status == ExtractionStatus.FINALIZING }
        )
        assertEquals(
            "[$tag] Restore must conclude with CANCELLED: ${restored.last().errorMessage}",
            ExtractionStatus.CANCELLED,
            restored.last().status
        )

        verifyRestoredMultiEntryOutcome(tag, fixture, savedState.stateFilePath, expectZip64 = forceZip64)
    }

    // =========================================================================
    // 1. Restore From Every Transactional Crash Phase
    // =========================================================================

    @Test
    fun restore_preparedPhase_tornPwriteChunk0AndChunk1_finishesOnlyInterruptedEntryAndRestoresZip() {
        val evenSize = 7_500_000 // 6 chunks

        runCrashAndRestoreScenario(
            tag = "prepared_c0_torn",
            largeSizeBytes = evenSize,
            seed = 61001L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        runCrashAndRestoreScenario(
            tag = "prepared_c1_torn",
            largeSizeBytes = evenSize,
            seed = 61002L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 2,
            simulateTornWrite = true,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )
    }

    @Test
    fun restore_relocatedTruncatePending_firstHalfMiddleAndSecondHalf_restoresRemainingZip() {
        val evenSize = 7_500_000 // 6 chunks
        val oddSize = 6_250_000  // 5 chunks (has odd middle chunk index 2)

        // First-half before ftruncate
        runCrashAndRestoreScenario(
            tag = "relocated_first_before_trunc",
            largeSizeBytes = evenSize,
            seed = 62001L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // First-half after ftruncate (before chunk commit)
        runCrashAndRestoreScenario(
            tag = "relocated_first_after_trunc",
            largeSizeBytes = evenSize,
            seed = 62002L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE
        )

        // Odd 5-chunk middle-chunk truncation
        runCrashAndRestoreScenario(
            tag = "relocated_odd_middle",
            largeSizeBytes = oddSize,
            seed = 62003L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 3,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.MIDDLE_TRUNCATE
        )

        // Second-half slot truncation (chunk 3 on 6-chunk archive)
        runCrashAndRestoreScenario(
            tag = "relocated_second_half",
            largeSizeBytes = evenSize,
            seed = 62004L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
            triggerOnOccurrence = 4,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.SECOND_HALF_TRUNCATE
        )
    }

    @Test
    fun restore_committedPhase_withUncommittedTrailingOutputBytes_truncatesOutputAndRestoresZip() {
        runCrashAndRestoreScenario(
            tag = "committed_trailing_output",
            largeSizeBytes = 7_500_000,
            seed = 63001L,
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT,
            triggerOnOccurrence = 1,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.NONE,
            appendUncommittedTrailingOutputBytes = 49_152
        )
    }

    // =========================================================================
    // 2. Repeated Interruptions Across Recovery -> Restoration Transition
    // =========================================================================

    @Test
    fun restore_repeatedCrashesAcrossStage1AndStage2_isDurableAndIdempotent() = runBlocking {
        val tag = "repeated_stage1_stage2"
        val fixture = createMultiEntryZip(tag, largeSizeBytes = 7_500_000, seed = 64001L)

        // Crash 1: Initial extraction crashes with torn pwrite on chunk 0 (PREPARED)
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true
        )
        val run1 = KaiHatchEngine.extractArchive(
            archiveFile = fixture.archiveFile,
            targetDir = fixture.outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, run1.last().status)

        // Crash 2: Restore Stage 1 crashes mid-recovery after chunk 2 truncate
        val stateAfterCrash1 = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 2
        )
        val restoreAttempt1 = KaiHatchEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = stateAfterCrash1
        ).toList()
        assertEquals(ExtractionStatus.ERROR, restoreAttempt1.last().status)

        // Journals MUST be preserved and BOTH Resume and Restore must still be separate valid choices!
        val stateAfterCrash2 = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == fixture.archiveFile.absolutePath }
        assertNotNull("Journal must be retained after Stage 1 crash", stateAfterCrash2)
        stateAfterCrash2!!
        assertTrue(ResumeJournalManager.isStateSafelyResumable(stateAfterCrash2, context))
        val eligAfterCrash2 = ResumeJournalManager.assessRestoreEligibility(stateAfterCrash2, context)
        assertTrue(
            "Restore must remain eligible after Stage 1 crash: $eligAfterCrash2",
            eligAfterCrash2 is ResumeJournalManager.RestoreEligibility.Restorable &&
                eligAfterCrash2.pendingDeflateTailSwapEntryPath == fixture.largeEntryName
        )

        // Crash 3: Restore finishes Stage 1 (completes interrupted entry + persists durable journal),
        // then Stage 2 faults BEFORE_RESTORE_CD_WRITE!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val crash3Err = runCatching {
            KaiHatchEngine.restoreInterruptedZipArchive(
                context = context,
                resumeState = stateAfterCrash2,
                faultInjector = SingleShotFaultInjector(DestructivePoint.BEFORE_RESTORE_CD_WRITE, 1)
            ).toList()
        }.exceptionOrNull()
        assertTrue(
            "Stage 2 must fault at BEFORE_RESTORE_CD_WRITE, got $crash3Err",
            crash3Err is SimulatedFaultException && crash3Err.point == DestructivePoint.BEFORE_RESTORE_CD_WRITE
        )

        // Verify Stage 1 transition was persisted durably to disk:
        // interrupted entry is now COMPLETED, no tail-swap checkpoint remains, BOTH Resume & Restore still work!
        val stateAfterStage1 = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == fixture.archiveFile.absolutePath }
        assertNotNull("Journal must be retained after Stage 2 pre-write crash", stateAfterStage1)
        stateAfterStage1!!
        assertTrue(
            "Interrupted DEFLATED entry must be recorded in completedPaths after Stage 1",
            fixture.largeEntryName in stateAfterStage1.completedPaths
        )
        assertNull("DeflateTailSwapCheckpoint must be cleared after Stage 1 completes", ResumeJournalManager.loadDeflateTailSwapCheckpoint(stateAfterStage1.stateFilePath))
        assertNull("TailSwapGuard must be cleared after Stage 1 completes", ResumeJournalManager.loadTailSwapGuard(stateAfterStage1.stateFilePath))
        assertTrue("Resume must still be available before CD rewrite starts", ResumeJournalManager.isStateSafelyResumable(stateAfterStage1, context))
        val eligAfterStage1 = ResumeJournalManager.assessRestoreEligibility(stateAfterStage1, context)
        assertTrue(
            "Restore must now proceed directly to Stage 2 (pendingDeflateTailSwapEntryPath == null): $eligAfterStage1",
            eligAfterStage1 is ResumeJournalManager.RestoreEligibility.Restorable &&
                eligAfterStage1.remainingCount == 2 &&
                eligAfterStage1.pendingDeflateTailSwapEntryPath == null
        )

        // Crashes 4, 5, 6: Stage 2 faults during CD write, before sync, and after verify before cleanup
        val stage2CrashPoints = listOf(
            DestructivePoint.MID_RESTORE_CD_WRITE,
            DestructivePoint.AFTER_RESTORE_CD_WRITE_BEFORE_SYNC,
            DestructivePoint.AFTER_RESTORE_VERIFY_BEFORE_CLEANUP
        )
        for (pt in stage2CrashPoints) {
            val reloaded = ResumeJournalManager.loadPendingStates(context)
                .firstOrNull { it.archivePath == fixture.archiveFile.absolutePath }
            assertNotNull("Journal must still exist before retry at $pt", reloaded)
            val err = runCatching {
                KaiHatchEngine.restoreInterruptedZipArchive(
                    context = context,
                    resumeState = reloaded!!,
                    faultInjector = SingleShotFaultInjector(pt, 1)
                ).toList()
            }.exceptionOrNull()
            assertTrue(
                "Stage 2 attempt must fault at $pt, got $err",
                err is SimulatedFaultException && err.point == pt
            )
            assertNotNull(
                "Restoration checkpoint must be preserved after fault at $pt",
                ResumeJournalManager.loadRestorationCheckpoint(reloaded!!.stateFilePath)
            )
            assertTrue(
                "State journal must be retained until validation + cleanup completes ($pt)",
                File(reloaded.stateFilePath).exists()
            )
        }

        // Final retry with no faults succeeds cleanly!
        val finalReloaded = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == fixture.archiveFile.absolutePath }
        assertNotNull("Journal must exist for final retry", finalReloaded)
        assertTrue("Pending restoration flag must be active after mid-CD crash", finalReloaded!!.hasPendingRestoration)

        val finalRestore = KaiHatchEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = finalReloaded
        ).toList()
        assertEquals(
            "Final restore retry must succeed: ${finalRestore.last().errorMessage}",
            ExtractionStatus.CANCELLED,
            finalRestore.last().status
        )

        verifyRestoredMultiEntryOutcome(tag, fixture, finalReloaded.stateFilePath)
    }

    // =========================================================================
    // 3. ZIP64 Restoration After Interrupted DEFLATE Tail-Swap
    // =========================================================================

    @Test
    fun restore_zip64Archive_afterMidTailSwapCrash_rebuildsValidZip64Archive() {
        runCrashAndRestoreScenario(
            tag = "zip64_prepared_torn",
            largeSizeBytes = 7_500_000,
            seed = 65001L,
            triggerPoint = DestructivePoint.MID_TAILSWAP_PWRITE_TORN,
            triggerOnOccurrence = 1,
            simulateTornWrite = true,
            expectedTxPhase = ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            expectedPendingOp = ResumeJournalManager.DeflateTailSwapOp.FIRST_HALF_RELOCATE,
            forceZip64 = true
        )
    }

    // =========================================================================
    // 4. Fail-Closed Checks (Single-Entry, Invalid Checkpoints, SAF, Low Disk)
    // =========================================================================

    @Test
    fun restore_singleEntryMutatedArchive_refusesRestoreWhileKeepingResumeWorking() = runBlocking {
        val archiveDir = tempFolder.newFolder("src_single_entry")
        val archiveFile = File(archiveDir, "single_large.zip")
        val outputDir = tempFolder.newFolder("out_single_entry")
        val largeName = "only/huge_deflate.bin"
        val payload = generateMixedDeflatePayload(7_500_000, 66001L)
        val expectedSha = sha256Hex(payload)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry(largeName))
            zos.write(payload)
            zos.closeEntry()
        }

        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1
        )
        val crash = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, crash.last().status)
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val savedState = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == archiveFile.absolutePath }

        // 0 remaining entries besides the interrupted entry -> Restore refuses, Resume succeeds!
        val restoreElig = ResumeJournalManager.assessRestoreEligibility(savedState, context)
        assertTrue(
            "Single-entry archive must refuse Restore (no remaining entries for ZIP): $restoreElig",
            restoreElig is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )
        assertTrue(
            "Resume must still be eligible for single-entry archive",
            ResumeJournalManager.isStateSafelyResumable(savedState, context)
        )

        val restoreAttempt = KaiHatchEngine.restoreInterruptedZipArchive(context, savedState).toList()
        assertEquals(ExtractionStatus.ERROR, restoreAttempt.last().status)
        assertTrue("Archive must remain preserved after refused Restore", archiveFile.exists())
        assertTrue("Journal must remain preserved after refused Restore", File(savedState.stateFilePath).exists())

        // User can still choose Resume and complete extraction cleanly!
        val resumeRun = KaiHatchEngine.resumeInterruptedExtraction(context, savedState).toList()
        assertEquals(ExtractionStatus.COMPLETED, resumeRun.last().status)
        assertEquals(expectedSha, sha256Hex(File(outputDir, largeName)))
    }

    @Test
    fun restore_failClosed_legacyV1CorruptedTamperedAndSafStatesRejected() = runBlocking {
        // Case A: Legacy v1 checkpoint rejected
        run {
            val fixture = createMultiEntryZip("fc_v1", 7_500_000, 67001L)
            KaiHatchEngine.faultInjector = SingleShotFaultInjector(DestructivePoint.AFTER_TAILSWAP_TRUNCATE, 1)
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

            val saved = ResumeJournalManager.loadPendingStates(context)
                .first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(saved.stateFilePath)!!
            writeLegacyV1Checkpoint(saved.stateFilePath, ckpt)

            assertTrue(ResumeJournalManager.assessRestoreEligibility(saved, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable)
            val run = KaiHatchEngine.restoreInterruptedZipArchive(context, saved).toList()
            assertEquals(ExtractionStatus.ERROR, run.last().status)
            assertTrue(fixture.archiveFile.exists())
            assertTrue(File(saved.stateFilePath).exists())
        }

        // Case B: Corrupted .tailswap.ckpt rejected
        run {
            val fixture = createMultiEntryZip("fc_corrupt_ckpt", 7_500_000, 67002L)
            KaiHatchEngine.faultInjector = SingleShotFaultInjector(DestructivePoint.AFTER_TAILSWAP_TRUNCATE, 1)
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

            val saved = ResumeJournalManager.loadPendingStates(context)
                .first { it.archivePath == fixture.archiveFile.absolutePath }
            val ckptFile = ResumeJournalManager.getDeflateTailSwapCheckpointFile(saved.stateFilePath)
            val bakFile = File("${ckptFile.absolutePath}.bak")
            ckptFile.writeBytes("CORRUPTED_PART3B_CKPT".toByteArray())
            if (bakFile.exists()) bakFile.writeBytes("CORRUPTED_PART3B_BAK".toByteArray())

            assertTrue(ResumeJournalManager.assessRestoreEligibility(saved, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable)
            val run = KaiHatchEngine.restoreInterruptedZipArchive(context, saved).toList()
            assertEquals(ExtractionStatus.ERROR, run.last().status)
            assertTrue(fixture.archiveFile.exists())
        }

        // Case C: Tampered partial output file rejected
        run {
            val fixture = createMultiEntryZip("fc_tampered_out", 7_500_000, 67003L)
            KaiHatchEngine.faultInjector = SingleShotFaultInjector(DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT, 1)
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

            val saved = ResumeJournalManager.loadPendingStates(context)
                .first { it.archivePath == fixture.archiveFile.absolutePath }
            val largeDest = File(fixture.outputDir, fixture.largeEntryName)
            RandomAccessFile(largeDest, "rw").use { raf ->
                raf.seek(1024L)
                val b = raf.read()
                raf.seek(1024L)
                raf.write(b xor 0xFF)
            }

            assertTrue(ResumeJournalManager.assessRestoreEligibility(saved, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable)
            val run = KaiHatchEngine.restoreInterruptedZipArchive(context, saved).toList()
            assertEquals(ExtractionStatus.ERROR, run.last().status)
            assertTrue(fixture.archiveFile.exists())
            assertTrue(largeDest.exists())
        }

        // Case D: Non-direct-POSIX SAF destination or SAF source rejected for mutated v2 tail-swap Restore
        run {
            val fixture = createMultiEntryZip("fc_saf", 7_500_000, 67004L)
            KaiHatchEngine.faultInjector = SingleShotFaultInjector(DestructivePoint.AFTER_TAILSWAP_TRUNCATE, 1)
            KaiHatchEngine.extractArchive(fixture.archiveFile, fixture.outputDir, true, context = context).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

            val saved = ResumeJournalManager.loadPendingStates(context)
                .first { it.archivePath == fixture.archiveFile.absolutePath }

            val safDestState: ResumeJournalState = saved.copy(
                targetDirPath = File(context.filesDir, "saf_fallback_restore_out").absolutePath,
                targetTreeUriString = "content://com.android.externalstorage.documents/tree/primary%3AOut"
            )
            assertTrue(
                "Non-direct-POSIX SAF destination must be rejected",
                ResumeJournalManager.assessRestoreEligibility(safDestState, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
            )
            val runSafDest = KaiHatchEngine.restoreInterruptedZipArchive(context, safDestState).toList()
            assertEquals(ExtractionStatus.ERROR, runSafDest.last().status)

            val safSourceState: ResumeJournalState = saved.copy(
                sourceUriString = "content://com.android.externalstorage.documents/document/primary%3Aarchive.zip"
            )
            assertTrue(
                "SAF source URI must be rejected for mutated v2 tail-swap Restore",
                ResumeJournalManager.assessRestoreEligibility(safSourceState, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
            )
            val runSafSrc = KaiHatchEngine.restoreInterruptedZipArchive(context, safSourceState).toList()
            assertEquals(ExtractionStatus.ERROR, runSafSrc.last().status)
        }
    }

    @Test
    fun restore_lowDiskSpaceDuringStage2_retainsJournalAndSucceedsOnRetry() = runBlocking {
        val tag = "low_disk_stage2"
        val fixture = createMultiEntryZip(tag, largeSizeBytes = 7_500_000, seed = 68001L)

        KaiHatchEngine.faultInjector = SingleShotFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1
        )
        val crash = KaiHatchEngine.extractArchive(
            archiveFile = fixture.archiveFile,
            targetDir = fixture.outputDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, crash.last().status)
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val savedState = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }

        // Stage 1 succeeds and commits journal; Stage 2 refuses due to insufficient disk space
        val lowDiskRun = KaiHatchEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = savedState,
            simulatedAvailableDiskBytes = 1024L
        ).toList()
        assertEquals(ExtractionStatus.ERROR, lowDiskRun.last().status)
        assertTrue(
            "Error message should indicate insufficient storage: ${lowDiskRun.last().errorMessage}",
            lowDiskRun.last().errorMessage?.contains("storage", ignoreCase = true) == true ||
                lowDiskRun.last().errorMessage?.contains("space", ignoreCase = true) == true
        )
        assertTrue("Journal must be retained after low-disk Stage 2 failure", File(savedState.stateFilePath).exists())

        // Retry with normal disk space completes cleanly!
        val reloaded = ResumeJournalManager.loadPendingStates(context)
            .first { it.archivePath == fixture.archiveFile.absolutePath }
        val retryRun = KaiHatchEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = reloaded
        ).toList()
        assertEquals(ExtractionStatus.CANCELLED, retryRun.last().status)

        verifyRestoredMultiEntryOutcome(tag, fixture, reloaded.stateFilePath)
    }
}
