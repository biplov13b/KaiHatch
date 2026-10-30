package com.example.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ResumeJournalManager
import com.example.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2 Regression Suite: Crash-Consistent Resume Journaling & Output Integrity Verification.
 *
 * Covers:
 * 1. Crashes before and after Central Directory truncation (`BEFORE_CD_TRUNCATE`, `AFTER_CD_TRUNCATE`).
 * 2. Crashes before and after each window truncation and journal transition (`BEFORE_WINDOW_TRUNCATE`,
 *    `AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL`) across ZIP and TAR archives.
 * 3. Crashes before and after slice truncation on >= 4 MB `STORED` entries (`BEFORE_STORED_SLICE_TRUNCATE`,
 *    `AFTER_STORED_SLICE_TRUNCATE`), verifying progressive slice resume without data loss.
 * 4. Streaming binary journal (`<stateFilePath>.index.bin` + `.ckpt.bin`) for archives exceeding
 *    5,000 entries (5,200 entries tested) with mid-extraction crash and resume.
 * 5. Full-stream CRC32 output verification during recovery:
 *    - Detects 1-byte tampering on an already-truncated output file of identical size and halts with ERROR.
 *    - Detects 1-byte tampering/incompleteness on an output file whose source archive region was NOT yet
 *      truncated (`BEFORE_WINDOW_TRUNCATE`) and automatically re-extracts & repairs it from the archive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KaiHatchPhase2JournalRegressionTest {

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

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(65536)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deterministicBytes(size: Int, seed: Long): ByteArray {
        val rng = Random(seed)
        val out = ByteArray(size)
        rng.nextBytes(out)
        return out
    }

    private fun createDisposableZip(
        zipFile: File,
        entries: List<Triple<String, ByteArray, Int>>
    ): Map<String, String> {
        val expectedHashes = LinkedHashMap<String, String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for ((path, data, method) in entries) {
                expectedHashes[path] = sha256(data)
                val ze = ZipEntry(path)
                ze.method = method
                if (method == ZipEntry.STORED) {
                    ze.size = data.size.toLong()
                    ze.compressedSize = data.size.toLong()
                    val crc = CRC32()
                    crc.update(data)
                    ze.crc = crc.value
                }
                zos.putNextEntry(ze)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return expectedHashes
    }

    private fun createDisposableTar(
        tarFile: File,
        entries: List<Pair<String, ByteArray>>
    ): Map<String, String> {
        val expectedHashes = LinkedHashMap<String, String>()
        TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(tarFile))).use { tos ->
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for ((path, data) in entries) {
                expectedHashes[path] = sha256(data)
                val te = TarArchiveEntry(path)
                te.size = data.size.toLong()
                tos.putArchiveEntry(te)
                tos.write(data)
                tos.closeArchiveEntry()
            }
        }
        return expectedHashes
    }

    private fun assertExtractedFilesMatchSha256(
        outputDir: File,
        expectedHashes: Map<String, String>
    ) {
        for ((relPath, expectedHash) in expectedHashes) {
            val outFile = File(outputDir, relPath)
            assertTrue("Expected extracted file missing: $relPath", outFile.exists())
            assertTrue("Expected extracted file is empty: $relPath", outFile.length() > 0L)
            val actualHash = sha256(outFile)
            assertEquals("SHA-256 mismatch for $relPath", expectedHash, actualHash)
        }
    }

    // ========================================================================
    // 1. Crashes Before and After Central Directory Truncation
    // ========================================================================

    @Test
    fun crashBeforeCdTruncate_and_crashAfterCdTruncate_resumesWithSha256() = runBlocking {
        for (point in listOf(DestructivePoint.BEFORE_CD_TRUNCATE, DestructivePoint.AFTER_CD_TRUNCATE)) {
            val workDir = tempFolder.newFolder("cd_fault_${point.name}")
            val zipFile = File(workDir, "cd_test.zip")
            val outDir = File(workDir, "out").apply { mkdirs() }

            val entries = listOf(
                Triple("a.bin", deterministicBytes(120 * 1024, 101L), ZipEntry.DEFLATED),
                Triple("b.bin", deterministicBytes(140 * 1024, 102L), ZipEntry.STORED),
                Triple("c.bin", deterministicBytes(160 * 1024, 103L), ZipEntry.DEFLATED)
            )
            val expectedHashes = createDisposableZip(zipFile, entries)

            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = point,
                triggerOnOccurrence = 1
            )

            val firstRunStates = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir,
                isHatchMode = true,
                context = context
            ).toList()
            assertEquals(
                "Expected ERROR on fault at $point",
                ExtractionStatus.ERROR,
                firstRunStates.last().status
            )

            val pending = ResumeJournalManager.loadPendingStates(context)
            val journal = pending.find { it.archivePath == zipFile.absolutePath }
            assertNotNull("Journal must exist at $point so CD truncation is recoverable", journal)

            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val resumeStates = KaiHatchEngine.resumeInterruptedExtraction(
                context = context,
                resumeState = journal!!
            ).toList()
            assertEquals(
                "Resume after $point must succeed: ${resumeStates.last().errorMessage}",
                ExtractionStatus.COMPLETED,
                resumeStates.last().status
            )

            assertExtractedFilesMatchSha256(outDir, expectedHashes)
            assertFalse("Archive should be removed after completion", zipFile.exists())
        }
    }

    // ========================================================================
    // 2. Crashes Before and After Window Truncation (ZIP and TAR)
    // ========================================================================

    @Test
    fun crashBeforeAndAfterEachWindowTruncate_zipAndTar_resumesWithoutOverwritingValidFiles() = runBlocking {
        val points = listOf(
            DestructivePoint.BEFORE_WINDOW_TRUNCATE,
            DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL
        )
        for (point in points) {
            for (occurrence in 1..3) {
                // Test ZIP
                val zipDir = tempFolder.newFolder("win_zip_${point.name}_$occurrence")
                val zipFile = File(zipDir, "windowed.zip")
                val zipOut = File(zipDir, "out").apply { mkdirs() }
                val zipEntries = (1..4).map { idx ->
                    Triple(
                        "item_$idx.bin",
                        deterministicBytes(150 * 1024, 500L + idx),
                        if (idx % 2 == 0) ZipEntry.STORED else ZipEntry.DEFLATED
                    )
                }
                val expectedZipHashes = createDisposableZip(zipFile, zipEntries)

                KaiHatchEngine.faultInjector = RecordingFaultInjector(
                    triggerPoint = point,
                    triggerOnOccurrence = occurrence
                )

                val states1 = KaiHatchEngine.extractArchive(
                    archiveFile = zipFile,
                    targetDir = zipOut,
                    isHatchMode = true,
                    context = context
                ).toList()
                assertEquals(ExtractionStatus.ERROR, states1.last().status)

                val journal = ResumeJournalManager.loadPendingStates(context)
                    .find { it.archivePath == zipFile.absolutePath }
                assertNotNull("Expected journal for ZIP at $point #$occurrence", journal)

                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
                val resumeStates = KaiHatchEngine.resumeInterruptedExtraction(
                    context = context,
                    resumeState = journal!!
                ).toList()
                assertEquals(
                    "ZIP resume failed for $point #$occurrence: ${resumeStates.last().errorMessage}",
                    ExtractionStatus.COMPLETED,
                    resumeStates.last().status
                )
                assertExtractedFilesMatchSha256(zipOut, expectedZipHashes)
                assertFalse(zipFile.exists())
            }

            // Test TAR at occurrence 2
            val tarDir = tempFolder.newFolder("win_tar_${point.name}")
            val tarFile = File(tarDir, "windowed.tar")
            val tarOut = File(tarDir, "out").apply { mkdirs() }
            val tarEntries = (1..4).map { idx ->
                "tar_item_$idx.bin" to deterministicBytes(128 * 1024, 800L + idx)
            }
            val expectedTarHashes = createDisposableTar(tarFile, tarEntries)

            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = point,
                triggerOnOccurrence = 2
            )

            val tarStates1 = KaiHatchEngine.extractArchive(
                archiveFile = tarFile,
                targetDir = tarOut,
                isHatchMode = true,
                context = context
            ).toList()
            assertEquals(ExtractionStatus.ERROR, tarStates1.last().status)

            val tarJournal = ResumeJournalManager.loadPendingStates(context)
                .find { it.archivePath == tarFile.absolutePath }
            assertNotNull("Expected journal for TAR at $point #2", tarJournal)

            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val tarResumeStates = KaiHatchEngine.resumeInterruptedExtraction(
                context = context,
                resumeState = tarJournal!!
            ).toList()
            assertEquals(
                "TAR resume failed for $point #2: ${tarResumeStates.last().errorMessage}",
                ExtractionStatus.COMPLETED,
                tarResumeStates.last().status
            )
            assertExtractedFilesMatchSha256(tarOut, expectedTarHashes)
            assertFalse(tarFile.exists())
        }
    }

    // ========================================================================
    // 3. Crashes Before and After Large STORED Slice Truncation (>= 4 MB)
    // ========================================================================

    @Test
    fun crashMidLargeStoredSlices_beforeAndAfterSliceTruncate_resumesAndVerifiesSha256() = runBlocking {
        val points = listOf(
            DestructivePoint.BEFORE_STORED_SLICE_TRUNCATE,
            DestructivePoint.AFTER_STORED_SLICE_TRUNCATE
        )
        for (point in points) {
            val workDir = tempFolder.newFolder("stored_slice_${point.name}")
            val zipFile = File(workDir, "large_stored.zip")
            val outDir = File(workDir, "out").apply { mkdirs() }

            // 5.5 MB STORED entry (6 slices of 1 MB) + a smaller entry before it
            val entries = listOf(
                Triple("header_doc.bin", deterministicBytes(128 * 1024, 910L), ZipEntry.DEFLATED),
                Triple("huge_stored.bin", deterministicBytes(5 * 1024 * 1024 + 512 * 1024, 920L), ZipEntry.STORED)
            )
            val expectedHashes = createDisposableZip(zipFile, entries)
            val initialSize = zipFile.length()

            // Crash on the 3rd 1MB slice truncation
            KaiHatchEngine.faultInjector = RecordingFaultInjector(
                triggerPoint = point,
                triggerOnOccurrence = 3
            )

            val states1 = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir,
                isHatchMode = true,
                context = context
            ).toList()
            assertEquals(ExtractionStatus.ERROR, states1.last().status)

            // Confirm the archive actually shrunk before the fault
            assertTrue(
                "Archive should have progressively shrunk before slice #3 fault",
                zipFile.length() < initialSize - 2 * 1024 * 1024
            )

            val journal = ResumeJournalManager.loadPendingStates(context)
                .find { it.archivePath == zipFile.absolutePath }
            assertNotNull("Expected journal at $point #3", journal)

            // Resume and verify the remaining slices are extracted and full CRC32 + SHA-256 pass
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val resumeStates = KaiHatchEngine.resumeInterruptedExtraction(
                context = context,
                resumeState = journal!!
            ).toList()
            assertEquals(
                "Resume after $point #3 failed: ${resumeStates.last().errorMessage}",
                ExtractionStatus.COMPLETED,
                resumeStates.last().status
            )

            assertExtractedFilesMatchSha256(outDir, expectedHashes)
            assertFalse(zipFile.exists())
        }
    }

    // ========================================================================
    // 4. Streaming Journal for Archives Exceeding 5,000 Entries (5,200 Entries)
    // ========================================================================

    @Test
    fun streamingJournalOver5000Entries_crashAndResume_recoversAllEntriesWithSha256() = runBlocking {
        val workDir = tempFolder.newFolder("streaming_journal_5200")
        val zipFile = File(workDir, "many_entries.zip")
        val outDir = File(workDir, "out").apply { mkdirs() }

        val entryCount = 5_200
        val expectedHashes = LinkedHashMap<String, String>(entryCount)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), 65536)).use { zos ->
            val rng = Random(2026L)
            for (i in 0 until entryCount) {
                val path = "batch_${i / 100}/file_$i.bin"
                // 256-byte payload per entry so 5,200 entries build & extract quickly while exceeding 5,000 limit
                val payload = ByteArray(256)
                rng.nextBytes(payload)
                expectedHashes[path] = sha256(payload)

                val ze = ZipEntry(path)
                ze.method = ZipEntry.STORED
                ze.size = payload.size.toLong()
                ze.compressedSize = payload.size.toLong()
                val crc = CRC32()
                crc.update(payload)
                ze.crc = crc.value
                zos.putNextEntry(ze)
                zos.write(payload)
                zos.closeEntry()
            }
        }

        // Inject fault on the 5th window truncation (after earlier windows are already truncated)
        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_WINDOW_TRUNCATE,
            triggerOnOccurrence = 5
        )

        val states1 = KaiHatchEngine.extractArchive(
            archiveFile = zipFile,
            targetDir = outDir,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, states1.last().status)

        // Verify that the streaming journal files (.index.bin and .ckpt.bin) were persisted
        val journal = ResumeJournalManager.loadPendingStates(context)
            .find { it.archivePath == zipFile.absolutePath }
        assertNotNull(
            "Streaming journal must be active for > 5,000 entries (5,200 entries)",
            journal
        )
        val indexBin = File("${journal!!.stateFilePath}.index.bin")
        val ckptBin = File("${journal.stateFilePath}.ckpt.bin")
        assertTrue("Streaming binary index file (.index.bin) must exist", indexBin.exists())
        assertTrue("Binary checkpoint file (.ckpt.bin) must exist", ckptBin.exists())
        assertTrue(
            "Some entries should already be completed and verified before window #5 fault",
            journal.completedPaths.isNotEmpty() && journal.verifiedEntryCrcs.isNotEmpty()
        )
        assertTrue(
            "Remaining entries should be fewer than $entryCount",
            journal.remainingEntries.size < entryCount
        )

        // Resume and verify all 5,200 files match their SHA-256 hashes
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumeStates = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journal
        ).toList()
        assertEquals(
            "Streaming journal resume for 5,200 entries must succeed: ${resumeStates.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            resumeStates.last().status
        )

        assertExtractedFilesMatchSha256(outDir, expectedHashes)
        assertFalse("Archive must be deleted upon full completion", zipFile.exists())
        assertFalse("Binary index must be cleaned up upon completion", indexBin.exists())
        assertFalse("Binary checkpoint must be cleaned up upon completion", ckptBin.exists())
    }

    // ========================================================================
    // 5. Output Integrity Verification (Never Relying Only on Existence/Size)
    // ========================================================================

    @Test
    fun recoveryIntegrityVerification_detectsSameSizeTamperedOutputFile_andRepairsOrErrors() = runBlocking {
        // Case A: Crash AFTER window #1 truncation (`AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL`).
        // The last entry (`entry_3.bin`) has already been truncated off the source archive.
        // If `entry_3.bin` on disk has 1 byte flipped (keeping exact file existence and file size),
        // `resumeInterruptedExtraction` must detect the CRC32 mismatch and fail with ERROR rather
        // than trusting file existence + size!
        val dirA = tempFolder.newFolder("tamper_after_trunc")
        val zipA = File(dirA, "case_a.zip")
        val outA = File(dirA, "out").apply { mkdirs() }

        val entriesA = (0..3).map { idx ->
            Triple("entry_$idx.bin", deterministicBytes(150 * 1024, 1500L + idx), ZipEntry.DEFLATED)
        }
        createDisposableZip(zipA, entriesA)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL,
            triggerOnOccurrence = 1
        )

        val statesA = KaiHatchEngine.extractArchive(
            archiveFile = zipA,
            targetDir = outA,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, statesA.last().status)

        // Flip 1 byte in `entry_3.bin` on disk without changing its file length!
        val entry3FileA = File(outA, "entry_3.bin")
        assertTrue(entry3FileA.exists())
        val originalLenA = entry3FileA.length()
        RandomAccessFile(entry3FileA, "rw").use { raf ->
            raf.seek(1024L)
            val b = raf.readByte().toInt()
            raf.seek(1024L)
            raf.writeByte(b xor 0xFF)
            raf.fd.sync()
        }
        assertEquals("File size must remain identical to test non-size integrity verification", originalLenA, entry3FileA.length())

        val journalA = ResumeJournalManager.loadPendingStates(context)
            .find { it.archivePath == zipA.absolutePath }!!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumeStatesA = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journalA
        ).toList()
        assertEquals(
            "Resume must detect 1-byte tampering on already-truncated entry_3.bin and report ERROR",
            ExtractionStatus.ERROR,
            resumeStatesA.last().status
        )
        assertTrue("Source archive must be preserved on recovery integrity failure", zipA.exists())
        ResumeJournalManager.deleteState(journalA.stateFilePath)

        // Case B: Crash BEFORE window #1 truncation (`BEFORE_WINDOW_TRUNCATE`).
        // The journal checkpoint was committed, AND the source archive STILL contains `entry_3.bin`!
        // If `entry_3.bin` on disk had 1 byte flipped (same file size), `resumeInterruptedExtraction`
        // detects the CRC32 mismatch, sees that `entry_3.bin`'s source bytes are still intact inside
        // the physical archive, re-queues `entry_3.bin`, and repairs it to match the original SHA-256!
        val dirB = tempFolder.newFolder("tamper_before_trunc")
        val zipB = File(dirB, "case_b.zip")
        val outB = File(dirB, "out").apply { mkdirs() }

        val entriesB = (0..3).map { idx ->
            Triple("entry_$idx.bin", deterministicBytes(150 * 1024, 1600L + idx), ZipEntry.DEFLATED)
        }
        val expectedHashesB = createDisposableZip(zipB, entriesB)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_WINDOW_TRUNCATE,
            triggerOnOccurrence = 1
        )

        val statesB = KaiHatchEngine.extractArchive(
            archiveFile = zipB,
            targetDir = outB,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, statesB.last().status)

        // Flip 1 byte in `entry_3.bin` on disk (same file size) while its archive bytes are still un-truncated
        val entry3FileB = File(outB, "entry_3.bin")
        assertTrue(entry3FileB.exists())
        RandomAccessFile(entry3FileB, "rw").use { raf ->
            raf.seek(2048L)
            val b = raf.readByte().toInt()
            raf.seek(2048L)
            raf.writeByte(b xor 0xFF)
            raf.fd.sync()
        }

        val journalB = ResumeJournalManager.loadPendingStates(context)
            .find { it.archivePath == zipB.absolutePath }!!
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumeStatesB = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journalB
        ).toList()
        assertEquals(
            "Resume must re-extract and repair corrupted entry_3.bin when archive bytes are still intact",
            ExtractionStatus.COMPLETED,
            resumeStatesB.last().status
        )
        assertExtractedFilesMatchSha256(outB, expectedHashesB)
    }
}
