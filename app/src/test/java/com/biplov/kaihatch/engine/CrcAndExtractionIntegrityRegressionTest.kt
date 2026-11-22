package com.biplov.kaihatch.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression Suite: Comprehensive CRC & Extraction-Integrity Protection.
 *
 * Verifies:
 * 1. Reverse-slice CRC32 combination (GF(2) polynomial matrix squaring) for large STORED entries (>= 4 MB).
 * 2. Corruption detected before payload truncation across ZIP (STORED & DEFLATE), 7z, and TAR.GZ:
 *    - Stops destructive operations and never reports COMPLETED.
 *    - Prevents final archive deletion or truncation to zero.
 *    - Quarantines/removes invalid partial output files.
 *    - Clearly reports SOURCE ARCHIVE STATE: INTACT or PAYLOAD INTACT.
 * 3. Corruption detected during progressive extraction and after partial source consumption:
 *    - Preserves previously verified output files with exact SHA-256 hashes.
 *    - Quarantines only the corrupted entry's partial output.
 *    - Retains the partially consumed archive without zero-truncating or deleting it.
 *    - Clearly reports SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE.
 * 4. Large DEFLATE tail-swap corruption after partial source consumption.
 * 5. Crash-recovery eligibility hardening (never marks an incomplete or overwritten archive as safely resumable).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrcAndExtractionIntegrityRegressionTest {

    private lateinit var context: Context
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        workDir = File(context.cacheDir, "phase4_integrity_tests_${System.nanoTime()}").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        workDir.deleteRecursively()
    }

    @Test
    fun reverseSliceCrc32Combine_matchesFullStreamCrc32AndDetectsLargeStoredSliceCorruption() = runBlocking {
        // Part A: Mathematical verification of GF(2) crc32Combine across uneven slices
        val payloadSize = 5 * 1024 * 1024 + 345_678 // ~5.59 MB (> 4 MB threshold)
        val payload = generateDeterministicPayload(seed = 4001L, sizeBytes = payloadSize)
        val fullCrc = computeCrc32(payload)

        val sliceSize = 4 * 1024 * 1024
        var sliceEnd = payloadSize
        var combinedForwardCrc = 0L
        var combinedForwardLen = 0L

        // Simulate reverse slice extraction (tail slice first, then head slice)
        while (sliceEnd > 0) {
            val sliceStart = maxOf(0, sliceEnd - sliceSize)
            val sliceLen = sliceEnd - sliceStart
            val sliceCrc = CRC32().apply { update(payload, sliceStart, sliceLen) }.value

            combinedForwardCrc = if (combinedForwardLen == 0L) {
                sliceCrc
            } else {
                ResumeJournalManager.crc32Combine(sliceCrc, combinedForwardCrc, combinedForwardLen)
            }
            combinedForwardLen += sliceLen.toLong()
            sliceEnd = sliceStart
        }

        assertEquals(
            "GF(2) combined reverse-slice CRC32 must exactly equal single-pass forward CRC32",
            fullCrc,
            combinedForwardCrc
        )

        // Part B: End-to-end Hatch Mode extraction of a >4 MB STORED entry with a single-byte corruption in slice 0
        val archiveFile = File(workDir, "large_stored_corrupted.zip")
        val outputDir = File(workDir, "out_large_stored_corrupted").apply { mkdirs() }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            val entry = ZipEntry("large_stored_video.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = fullCrc
            }
            zos.putNextEntry(entry)
            zos.write(payload)
            zos.closeEntry()
        }

        // Flip 1 bit inside the first slice (near offset 128 KB) after the archive is built
        RandomAccessFile(archiveFile, "rw").use { raf ->
            val targetOffset = 128 * 1024L
            raf.seek(targetOffset)
            val orig = raf.read()
            raf.seek(targetOffset)
            raf.write(orig xor 0x55)
        }

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(
            "Corrupted large STORED entry must fail with ERROR, never COMPLETED",
            ExtractionStatus.ERROR,
            finalState.status
        )
        assertFalse("isCrcValid must be false on CRC32 mismatch", finalState.isCrcValid)

        val extractedFile = File(outputDir, "large_stored_video.bin")
        assertFalse(
            "Corrupted large STORED output file must be quarantined/deleted upon CRC mismatch",
            extractedFile.exists()
        )
        assertTrue(
            "Source archive must not be deleted after integrity failure",
            archiveFile.exists()
        )
        assertTrue(
            "Error message must report partial consumption because tail slice was truncated before head slice finished",
            finalState.errorMessage?.contains("SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE") == true
        )
    }

    @Test
    fun corruptionDetectedBeforeTruncation_zip7zAndTarGz_reportsIntactSourceAndQuarantinesInvalidOutput() = runBlocking {
        // Case 1: ZIP DEFLATE in Standard Mode (source archive completely untouched)
        val zipStandardFile = File(workDir, "corrupt_standard.zip")
        val zipStandardOut = File(workDir, "out_corrupt_standard").apply { mkdirs() }
        val rawData = "Integrity verification payload for KaiHatch Phase 4 test suite.".repeat(400).toByteArray()
        createSingleEntryDeflateZip(zipStandardFile, "doc.txt", rawData)
        val zipStandardOrigSize = zipStandardFile.length()

        // Corrupt declared CRC32 in both Local File Header (offset 14) and Central Directory
        corruptZipDeclaredCrc(zipStandardFile, "doc.txt")

        val stdResult = KaiHatchEngine.extractArchive(
            archiveFile = zipStandardFile,
            targetDir = zipStandardOut,
            isHatchMode = false,
            context = context
        ).toList().last()

        assertEquals(ExtractionStatus.ERROR, stdResult.status)
        assertFalse(stdResult.isCrcValid)
        assertFalse("Invalid output must be quarantined in Standard Mode", File(zipStandardOut, "doc.txt").exists())
        assertEquals("Standard Mode archive size must remain completely intact", zipStandardOrigSize, zipStandardFile.length())
        assertTrue(
            "Expected SOURCE ARCHIVE STATE: INTACT but got: ${stdResult.errorMessage}",
            stdResult.errorMessage?.contains("SOURCE ARCHIVE STATE: INTACT") == true
        )

        // Case 2: Single-entry ZIP in Hatch Mode where payload fails CRC before any payload byte is truncated
        val zipHatchFile = File(workDir, "corrupt_hatch_single.zip")
        val zipHatchOut = File(workDir, "out_corrupt_hatch_single").apply { mkdirs() }
        createSingleEntryDeflateZip(zipHatchFile, "single_payload.bin", rawData)
        corruptZipDeclaredCrc(zipHatchFile, "single_payload.bin")

        val hatchSingleResult = KaiHatchEngine.extractArchive(
            archiveFile = zipHatchFile,
            targetDir = zipHatchOut,
            isHatchMode = true,
            context = context
        ).toList().last()

        assertEquals(ExtractionStatus.ERROR, hatchSingleResult.status)
        assertFalse(hatchSingleResult.isCrcValid)
        assertFalse("Quarantined file must not remain on disk", File(zipHatchOut, "single_payload.bin").exists())
        assertTrue("Archive must still exist and hold full payload", zipHatchFile.exists() && zipHatchFile.length() > 0L)
        assertTrue(
            "Expected PAYLOAD INTACT or INTACT diagnostic, got: ${hatchSingleResult.errorMessage}",
            hatchSingleResult.errorMessage?.contains("SOURCE ARCHIVE STATE: PAYLOAD INTACT") == true ||
                hatchSingleResult.errorMessage?.contains("SOURCE ARCHIVE STATE: INTACT") == true
        )

        // Case 3: 7z archive with corrupted compressed payload (detected before chunk overwrite/truncation)
        val sevenZFile = File(workDir, "corrupt_archive.7z")
        val sevenZOut = File(workDir, "out_corrupt_7z").apply { mkdirs() }
        SevenZOutputFile(sevenZFile).use { sz ->
            sz.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2)))
            val entry = sz.createArchiveEntry(File(workDir, "dummy").apply { writeBytes(rawData) }, "archive_item.bin")
            sz.putArchiveEntry(entry)
            sz.write(rawData)
            sz.closeArchiveEntry()
        }
        // Corrupt compressed LZMA2 stream right after the 32-byte 7z signature header
        RandomAccessFile(sevenZFile, "rw").use { raf ->
            raf.seek(36L)
            for (i in 0 until 16) {
                raf.write(0xFF)
            }
        }

        val sevenZResult = KaiHatchEngine.extractArchive(
            archiveFile = sevenZFile,
            targetDir = sevenZOut,
            isHatchMode = true,
            context = context
        ).toList().last()

        assertEquals("Corrupted 7z must never report COMPLETED", ExtractionStatus.ERROR, sevenZResult.status)
        assertFalse(sevenZResult.isCrcValid)
        assertFalse("Corrupted 7z output file must be quarantined", File(sevenZOut, "archive_item.bin").exists())
        assertTrue("Corrupted 7z archive must not be deleted", sevenZFile.exists() && sevenZFile.length() > 32L)
        assertTrue(
            "7z failure before slot relocation must report INTACT or PAYLOAD INTACT, got: ${sevenZResult.errorMessage}",
            sevenZResult.errorMessage?.contains("SOURCE ARCHIVE STATE: INTACT") == true ||
                sevenZResult.errorMessage?.contains("SOURCE ARCHIVE STATE: PAYLOAD INTACT") == true
        )

        // Case 4: TAR.GZ archive with corrupted GZIP stream
        val tarGzFile = File(workDir, "corrupt_archive.tar.gz")
        val tarGzOut = File(workDir, "out_corrupt_targz").apply { mkdirs() }
        FileOutputStream(tarGzFile).use { fos ->
            GzipCompressorOutputStream(BufferedOutputStream(fos)).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val entry = TarArchiveEntry("tar_item.txt").apply { size = rawData.size.toLong() }
                    tos.putArchiveEntry(entry)
                    tos.write(rawData)
                    tos.closeArchiveEntry()
                }
            }
        }
        val tarGzOrigSize = tarGzFile.length()
        RandomAccessFile(tarGzFile, "rw").use { raf ->
            val flipPos = tarGzOrigSize / 2
            raf.seek(flipPos)
            val b = raf.read()
            raf.seek(flipPos)
            raf.write(b xor 0xFF)
        }

        val tarGzResult = KaiHatchEngine.extractArchive(
            archiveFile = tarGzFile,
            targetDir = tarGzOut,
            isHatchMode = true,
            context = context
        ).toList().last()

        assertEquals("Corrupted TAR.GZ must fail with ERROR", ExtractionStatus.ERROR, tarGzResult.status)
        assertFalse(tarGzResult.isCrcValid)
        assertFalse("Corrupted TAR.GZ output must be quarantined", File(tarGzOut, "tar_item.txt").exists())
        assertEquals("TAR.GZ source archive must remain intact on stream error", tarGzOrigSize, tarGzFile.length())
        assertTrue(
            "TAR.GZ error must report SOURCE ARCHIVE STATE: INTACT, got: ${tarGzResult.errorMessage}",
            tarGzResult.errorMessage?.contains("SOURCE ARCHIVE STATE: INTACT") == true
        )
    }

    @Test
    fun corruptionDetectedDuringProgressiveExtractionAndAfterPartialConsumption_preservesVerifiedFilesAndHaltsTruncation() = runBlocking {
        val archiveFile = File(workDir, "multi_entry_progressive_corrupt.zip")
        val outputDir = File(workDir, "out_multi_progressive").apply { mkdirs() }

        // Create 3 entries (total > 2 MB so Case 3 reverse window truncation shrinks the archive between entries):
        // - entry_head_corrupt.bin (at offset 0, extracted LAST in reverse order, has corrupted CRC)
        // - entry_middle_valid.bin (at middle offset, extracted SECOND in reverse order, valid)
        // - entry_tail_valid.bin (at tail offset, extracted FIRST in reverse order, valid)
        val payloadHead = generateDeterministicPayload(seed = 5101L, sizeBytes = 400_000)
        val payloadMiddle = generateDeterministicPayload(seed = 5102L, sizeBytes = 1_500_000)
        val payloadTail = generateDeterministicPayload(seed = 5103L, sizeBytes = 1_500_000)

        val expectedMiddleSha256 = sha256Hex(payloadMiddle)
        val expectedTailSha256 = sha256Hex(payloadTail)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)

            zos.putNextEntry(ZipEntry("entry_head_corrupt.bin"))
            zos.write(payloadHead)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("entry_middle_valid.bin"))
            zos.write(payloadMiddle)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("entry_tail_valid.bin"))
            zos.write(payloadTail)
            zos.closeEntry()
        }

        val originalArchiveSize = archiveFile.length()
        // Corrupt only entry_head_corrupt.bin's declared CRC32 so it decompresses and then fails CRC verification
        // AFTER entry_tail_valid.bin and entry_middle_valid.bin have already been extracted and truncated from the tail.
        corruptZipDeclaredCrc(archiveFile, "entry_head_corrupt.bin")

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(
            "Progressive extraction must halt with ERROR when head entry fails CRC",
            ExtractionStatus.ERROR,
            finalState.status
        )
        assertFalse("isCrcValid must be false", finalState.isCrcValid)

        // 1. Previously verified files (tail and middle) MUST be preserved intact on disk
        val tailFile = File(outputDir, "entry_tail_valid.bin")
        val middleFile = File(outputDir, "entry_middle_valid.bin")
        val headFile = File(outputDir, "entry_head_corrupt.bin")

        assertTrue("Previously verified tail file must be preserved", tailFile.exists())
        assertTrue("Previously verified middle file must be preserved", middleFile.exists())
        assertEquals(expectedTailSha256, sha256Hex(tailFile.readBytes()))
        assertEquals(expectedMiddleSha256, sha256Hex(middleFile.readBytes()))

        // 2. Corrupted head file MUST be quarantined/removed
        assertFalse("Corrupted head output file must be quarantined/removed", headFile.exists())

        // 3. Source archive MUST still exist, be smaller than originalArchiveSize (since tail entries were truncated),
        //    and MUST NOT be zero-truncated or deleted.
        assertTrue("Source archive must not be deleted on integrity error", archiveFile.exists())
        val remainingArchiveSize = archiveFile.length()
        assertTrue(
            "Remaining archive size ($remainingArchiveSize) must be > 0 and < original ($originalArchiveSize)",
            remainingArchiveSize in 1 until originalArchiveSize
        )

        // 4. Error message MUST explicitly distinguish that the archive was partially consumed in-place
        assertTrue(
            "Error message must state SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE, got: ${finalState.errorMessage}",
            finalState.errorMessage?.contains("SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE") == true
        )
    }

    @Test
    fun largeDeflateTailSwapCorruptionAfterPartialConsumption_haltsAndQuarantinesWithoutDeletingArchive() = runBlocking {
        val archiveFile = File(workDir, "large_deflate_tailswap_corrupt.zip")
        val outputDir = File(workDir, "out_large_deflate_corrupt").apply { mkdirs() }

        // High-entropy payload > 5 MB so compressed size exceeds 4 MB (2 chunks in Case 2 tail-swap)
        val largePayload = generateIncompressiblePayload(seed = 6201L, sizeBytes = 5 * 1024 * 1024 + 300_000)
        val tailValidPayload = generateDeterministicPayload(seed = 6202L, sizeBytes = 250_000)
        val expectedTailSha256 = sha256Hex(tailValidPayload)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)

            zos.putNextEntry(ZipEntry("01_large_deflate.bin"))
            zos.write(largePayload)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("02_tail_valid.bin"))
            zos.write(tailValidPayload)
            zos.closeEntry()
        }

        val originalArchiveSize = archiveFile.length()
        // Corrupt the declared CRC32 of 01_large_deflate.bin so that after 02_tail_valid.bin is extracted & truncated
        // and 01_large_deflate.bin performs tail-swap chunk relocation/truncation, final CRC verification fails.
        corruptZipDeclaredCrc(archiveFile, "01_large_deflate.bin")

        val finalState = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList().last()

        assertEquals(ExtractionStatus.ERROR, finalState.status)
        assertFalse(finalState.isCrcValid)

        // Valid tail file must remain intact
        val tailFile = File(outputDir, "02_tail_valid.bin")
        assertTrue("Previously verified tail file must remain intact", tailFile.exists())
        assertEquals(expectedTailSha256, sha256Hex(tailFile.readBytes()))

        // Corrupted large DEFLATE file must be quarantined/removed
        val largeOutFile = File(outputDir, "01_large_deflate.bin")
        assertFalse("Corrupted large DEFLATE output must be quarantined", largeOutFile.exists())

        // Archive must not be deleted or zero-truncated, and must report PARTIALLY CONSUMED IN-PLACE
        assertTrue("Archive must still exist on disk", archiveFile.exists())
        assertTrue(
            "Archive must be partially consumed (0 < ${archiveFile.length()} < $originalArchiveSize)",
            archiveFile.length() in 1 until originalArchiveSize
        )
        assertTrue(
            "Expected PARTIALLY CONSUMED IN-PLACE diagnostic, got: ${finalState.errorMessage}",
            finalState.errorMessage?.contains("SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE") == true
        )
    }

    @Test
    fun phase3CrashRecoveryEligibility_neverMarksIncompleteOrOverwrittenArchiveAsSafelyResumable() = runBlocking {
        // Scenario A: TailSwapGuardState has overwriteStarted = true, EVEN IF slot 0 CRC32 matches chunk0OriginalCrc32
        // (e.g. identical 4 MB chunks or interrupted pwrite before truncation).
        val archiveA = File(workDir, "guard_overwrite_started.zip")
        val dummyBytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        archiveA.writeBytes(dummyBytes)
        val outDirA = File(workDir, "out_guard_a").apply { mkdirs() }
        val stateFileA = ResumeJournalManager.getStateFileForArchive(context, archiveA.absolutePath)

        val slot0Crc = CRC32().apply { update(dummyBytes, 0, 4 * 1024 * 1024) }.value
        val entryA = ZipEntryInfo(
            path = "large_entry.bin",
            name = "large_entry.bin",
            isDirectory = false,
            compressedSize = 6 * 1024 * 1024L,
            uncompressedSize = 6 * 1024 * 1024L,
            crc32 = 12345678L,
            compressionMethod = ZipEntry.DEFLATED,
            localHeaderOffset = 0L,
            isEncrypted = false
        )

        val stateA = ResumeJournalState(
            stateFilePath = stateFileA.absolutePath,
            archivePath = archiveA.absolutePath,
            archiveName = archiveA.name,
            targetDirPath = outDirA.absolutePath,
            originalArchiveBytes = archiveA.length(),
            currentArchiveBytes = archiveA.length(),
            bytesReclaimedSoFar = 0L,
            totalEntries = 1,
            completedPaths = emptySet(),
            remainingEntries = listOf(entryA),
            verifiedEntryCrcs = emptyMap()
        )
        ResumeJournalManager.saveState(stateA)
        ResumeJournalManager.saveTailSwapGuard(
            stateFilePath = stateFileA.absolutePath,
            guard = ResumeJournalManager.TailSwapGuardState(
                entryPath = entryA.path,
                entryName = entryA.name,
                payloadOffset = 0L,
                compressedSize = entryA.compressedSize,
                uncompressedSize = entryA.uncompressedSize,
                expectedEntryCrc32 = entryA.crc32,
                chunk0Len = 4 * 1024 * 1024,
                chunk0OriginalCrc32 = slot0Crc, // Matches current slot 0 CRC!
                decompressedChunk0Bytes = 4 * 1024 * 1024L,
                overwriteStarted = true         // ...but destructive pwrite was already initiated!
            )
        )

        val eligibilityA = ResumeJournalManager.assessResumeEligibility(stateA)
        assertTrue(
            "Archive with overwriteStarted=true must NEVER be marked safely resumable even if slot 0 CRC matches",
            eligibilityA is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertFalse(ResumeJournalManager.isStateSafelyResumable(stateA))

        // Verify KaiHatchEngine.resumeInterruptedExtraction also refuses to resume and reports ERROR
        val resumeResultA = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = stateA
        ).toList().last()
        assertEquals(ExtractionStatus.ERROR, resumeResultA.status)
        assertTrue(
            "Resume error must report unrecoverable tail-swap overwrite",
            resumeResultA.errorMessage?.contains("UNRECOVERABLE") == true
        )

        // Scenario B: Large STORED entry (>= 4 MB) whose tail slice was already truncated from the archive
        // while the destination output file is incomplete/missing.
        val archiveB = File(workDir, "stored_partially_truncated.zip")
        // Physical archive was truncated down to 4 MB while entry requires 8 MB
        archiveB.writeBytes(ByteArray(4 * 1024 * 1024))
        val outDirB = File(workDir, "out_stored_b").apply { mkdirs() }
        val stateFileB = ResumeJournalManager.getStateFileForArchive(context, archiveB.absolutePath)

        val entryB = ZipEntryInfo(
            path = "large_stored.bin",
            name = "large_stored.bin",
            isDirectory = false,
            compressedSize = 8 * 1024 * 1024L,
            uncompressedSize = 8 * 1024 * 1024L,
            crc32 = 87654321L,
            compressionMethod = ZipEntry.STORED,
            localHeaderOffset = 0L,
            isEncrypted = false
        )
        val stateB = ResumeJournalState(
            stateFilePath = stateFileB.absolutePath,
            archivePath = archiveB.absolutePath,
            archiveName = archiveB.name,
            targetDirPath = outDirB.absolutePath,
            originalArchiveBytes = 8 * 1024 * 1024L + 64L,
            currentArchiveBytes = archiveB.length(),
            bytesReclaimedSoFar = 4 * 1024 * 1024L,
            totalEntries = 1,
            completedPaths = emptySet(),
            remainingEntries = listOf(entryB),
            verifiedEntryCrcs = emptyMap()
        )

        val eligibilityB = ResumeJournalManager.assessResumeEligibility(stateB)
        assertTrue(
            "Partially truncated large STORED entry without verified output must be Unrecoverable",
            eligibilityB is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertFalse(ResumeJournalManager.isStateSafelyResumable(stateB))

        // Scenario C: Pre-pwrite state (overwriteStarted = false, archive intact, slot 0 CRC matches) IS safely resumable
        ResumeJournalManager.saveTailSwapGuard(
            stateFilePath = stateFileA.absolutePath,
            guard = ResumeJournalManager.TailSwapGuardState(
                entryPath = entryA.path,
                entryName = entryA.name,
                payloadOffset = 0L,
                compressedSize = entryA.compressedSize,
                uncompressedSize = entryA.uncompressedSize,
                expectedEntryCrc32 = entryA.crc32,
                chunk0Len = 4 * 1024 * 1024,
                chunk0OriginalCrc32 = slot0Crc,
                decompressedChunk0Bytes = 4 * 1024 * 1024L,
                overwriteStarted = false
            )
        )
        assertEquals(
            "Intact pre-pwrite state with overwriteStarted=false must remain safely Resumable",
            ResumeJournalManager.ResumeEligibility.Resumable,
            ResumeJournalManager.assessResumeEligibility(stateA)
        )
        assertTrue(ResumeJournalManager.isStateSafelyResumable(stateA))
        ResumeJournalManager.deleteState(stateFileA.absolutePath)
        ResumeJournalManager.deleteState(stateFileB.absolutePath)
    }

    // --- Helper utilities ---

    private fun generateDeterministicPayload(seed: Long, sizeBytes: Int): ByteArray {
        val random = Random(seed)
        val out = ByteArray(sizeBytes)
        var i = 0
        while (i < sizeBytes) {
            if (i % 1024 < 512) {
                out[i] = ((i * 31 + seed.toInt()) and 0xFF).toByte()
                i++
            } else {
                val len = minOf(512, sizeBytes - i)
                val tmp = ByteArray(len)
                random.nextBytes(tmp)
                System.arraycopy(tmp, 0, out, i, len)
                i += len
            }
        }
        return out
    }

    private fun generateIncompressiblePayload(seed: Long, sizeBytes: Int): ByteArray {
        val random = Random(seed)
        return ByteArray(sizeBytes).also { random.nextBytes(it) }
    }

    private fun computeCrc32(bytes: ByteArray): Long {
        return CRC32().apply { update(bytes) }.value
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun createSingleEntryDeflateZip(targetZip: File, entryName: String, payload: ByteArray) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(payload)
            zos.closeEntry()
        }
    }

    /**
     * Corrupts the declared 32-bit CRC32 of [targetEntryName] in both its Local File Header
     * and Central Directory Header inside [zipFile] so decompression succeeds while CRC32 verification fails.
     */
    private fun corruptZipDeclaredCrc(zipFile: File, targetEntryName: String) {
        val bytes = zipFile.readBytes()
        val nameBytes = targetEntryName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var i = 0
        while (i <= bytes.size - 30 - nameBytes.size) {
            val sig = buf.getInt(i)
            // Local File Header signature = 0x04034b50
            if (sig == 0x04034b50) {
                val nameLen = buf.getShort(i + 26).toInt() and 0xFFFF
                if (nameLen == nameBytes.size && i + 30 + nameLen <= bytes.size) {
                    val slice = bytes.copyOfRange(i + 30, i + 30 + nameLen)
                    if (slice.contentEquals(nameBytes)) {
                        val origCrc = buf.getInt(i + 14)
                        buf.putInt(i + 14, origCrc xor -0x12345678)
                    }
                }
            }
            // Central Directory File Header signature = 0x02014b50
            if (sig == 0x02014b50 && i <= bytes.size - 46 - nameBytes.size) {
                val nameLen = buf.getShort(i + 28).toInt() and 0xFFFF
                if (nameLen == nameBytes.size && i + 46 + nameLen <= bytes.size) {
                    val slice = bytes.copyOfRange(i + 46, i + 46 + nameLen)
                    if (slice.contentEquals(nameBytes)) {
                        val origCrc = buf.getInt(i + 16)
                        buf.putInt(i + 16, origCrc xor -0x12345678)
                    }
                }
            }
            i++
        }
        zipFile.writeBytes(bytes)
    }
}
