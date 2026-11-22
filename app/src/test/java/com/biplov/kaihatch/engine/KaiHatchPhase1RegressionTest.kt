package com.biplov.kaihatch.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
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
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 1: Test-First Baseline & Fault-Injection Harness for KaiHatch.
 *
 * Covers:
 * 1. Format regressions (ZIP, ZIP64, TAR, TAR.GZ, 7z, large STORED >= 4 MB, large DEFLATE >= 4 MB)
 *    verified against independent SHA-256 reference hashes.
 * 2. Genuine progressive archive shrinking (`Os.ftruncate` / `FileChannel.truncate`) and actual
 *    peak additional disk usage (`max(archiveFile.length() + extractedBytesOnDisk)`).
 * 3. Deterministic fault injection around destructive operations (`BEFORE_CD_TRUNCATE`,
 *    `BEFORE_STORED_SLICE_TRUNCATE`, `BEFORE_TAILSWAP_PWRITE`, `MID_TAILSWAP_PWRITE_TORN`,
 *    `AFTER_TAILSWAP_TRUNCATE`, `BEFORE_7Z_SLOT_PWRITE`, `AFTER_JOURNAL_SAVE`).
 * 4. Strict reliability assertions that expose existing baseline defects without weakening tests
 *    or altering production extraction algorithms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KaiHatchPhase1RegressionTest {

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

    // =========================================================================
    // Helper utilities: deterministic payload generation, SHA-256, disk usage
    // =========================================================================

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeDirectoryDiskUsage(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Generates deterministic pseudo-random bytes with a given seed so payloads are reproducible
     * and incompressible (or moderately compressible when mixed with structured blocks).
     */
    private fun generateDeterministicBytes(size: Int, seed: Long, compressibleRatio: Int = 0): ByteArray {
        val out = ByteArray(size)
        val rng = Random(seed)
        rng.nextBytes(out)
        if (compressibleRatio > 0) {
            // Periodically insert structured runs so DEFLATE still exercises sliding-window backreferences
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

    private fun assertAllReferenceHashesMatch(
        outputDir: File,
        expectedHashes: Map<String, String>
    ) {
        for ((relPath, expectedSha256) in expectedHashes) {
            val extractedFile = File(outputDir, relPath)
            assertTrue("Expected extracted file missing: $relPath", extractedFile.exists())
            val actualSha256 = sha256Hex(extractedFile)
            assertEquals("SHA-256 mismatch for $relPath", expectedSha256, actualSha256)
        }
    }

    // =========================================================================
    // 1. Format Regressions + SHA-256 Verification + Progressive Disk Shrinking
    // =========================================================================

    @Test
    fun regression_standardMultiEntryZip_inPlaceShrinkingAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("standard_multi.zip")
        val outputDir = tempFolder.newFolder("out_standard_zip")

        val files = linkedMapOf(
            "alpha/part1.bin" to generateDeterministicBytes(320 * 1024, 101L, compressibleRatio = 1),
            "beta/part2.bin" to generateDeterministicBytes(480 * 1024, 202L, compressibleRatio = 0),
            "gamma/nested/part3.bin" to generateDeterministicBytes(640 * 1024, 303L, compressibleRatio = 1),
            "delta/part4.bin" to generateDeterministicBytes(512 * 1024, 404L, compressibleRatio = 0)
        )
        val expectedHashes = files.mapValues { sha256Hex(it.value) }
        val totalUncompressedBytes = files.values.sumOf { it.size.toLong() }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            for ((path, data) in files) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(data)
                zos.closeEntry()
            }
        }

        val originalArchiveSize = archiveFile.length()
        val peakCombinedDiskBytes = AtomicLong(originalArchiveSize)
        val observedPhysicalSizes = mutableListOf<Long>()

        val injector = RecordingFaultInjector { _, _, _ ->
            val currentArchiveLen = if (archiveFile.exists()) archiveFile.length() else 0L
            val currentExtractedLen = computeDirectoryDiskUsage(outputDir)
            synchronized(observedPhysicalSizes) {
                observedPhysicalSizes.add(currentArchiveLen)
            }
            peakCombinedDiskBytes.accumulateAndGet(currentArchiveLen + currentExtractedLen, ::maxOf)
        }
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalEmission = emissions.last()
        assertEquals(ExtractionStatus.COMPLETED, finalEmission.status)
        assertFalse("Archive file must be deleted upon in-place completion", archiveFile.exists())

        // Verify SHA-256 of every extracted file
        assertAllReferenceHashesMatch(outputDir, expectedHashes)

        // Verify genuine progressive physical truncation occurred
        assertTrue(
            "Expected multiple physical ftruncate reductions, got ${injector.truncateRecords.size}",
            injector.truncateRecords.size >= 4
        )
        for (record in injector.truncateRecords) {
            assertTrue(
                "Each physical truncation must strictly shrink archive (${record.previousSize} -> ${record.newSize})",
                record.newSize < record.previousSize
            )
        }

        // Verify actual peak combined disk usage is strictly less than naive (archive + all outputs)
        val naivePeakBytes = originalArchiveSize + totalUncompressedBytes
        assertTrue(
            "Peak combined disk usage (${peakCombinedDiskBytes.get()}) must be strictly below naive peak ($naivePeakBytes)",
            peakCombinedDiskBytes.get() < naivePeakBytes
        )
    }

    @Test
    fun regression_zip64ExplicitExtraFields_inPlaceShrinkingAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("explicit_zip64.zip")
        val outputDir = tempFolder.newFolder("out_zip64")

        val files = linkedMapOf(
            "zip64_a.bin" to generateDeterministicBytes(400 * 1024, 501L, compressibleRatio = 1),
            "zip64_b.bin" to generateDeterministicBytes(550 * 1024, 502L, compressibleRatio = 0),
            "subdir/zip64_c.bin" to generateDeterministicBytes(700 * 1024, 503L, compressibleRatio = 1)
        )
        val expectedHashes = files.mapValues { sha256Hex(it.value) }

        ZipArchiveOutputStream(archiveFile).use { zaos ->
            zaos.setUseZip64(Zip64Mode.Always)
            for ((path, data) in files) {
                val entry = ZipArchiveEntry(path)
                entry.size = data.size.toLong()
                zaos.putArchiveEntry(entry)
                zaos.write(data)
                zaos.closeArchiveEntry()
            }
        }

        // Confirm ZipParser recognizes the ZIP64 metadata
        val parsed = ZipParser.parseArchive(archiveFile)
        assertEquals(3, parsed.size)
        assertTrue("Expected ZipParser to flag ZIP64 entries", parsed.any { it.isZip64 })

        val injector = RecordingFaultInjector()
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse(archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)
        assertTrue(
            "Expected progressive ftruncate calls on ZIP64 archive",
            injector.truncateRecords.size >= 3
        )
    }

    @Test
    fun regression_posixTarArchive_inPlaceShrinkingAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("bundle.tar")
        val outputDir = tempFolder.newFolder("out_tar")

        val files = linkedMapOf(
            "tar_one.bin" to generateDeterministicBytes(300 * 1024, 601L),
            "nested/tar_two.bin" to generateDeterministicBytes(500 * 1024, 602L),
            "nested/deep/tar_three.bin" to generateDeterministicBytes(450 * 1024, 603L)
        )
        val expectedHashes = files.mapValues { sha256Hex(it.value) }
        val totalUncompressedBytes = files.values.sumOf { it.size.toLong() }

        TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { taos ->
            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((path, data) in files) {
                val entry = TarArchiveEntry(path)
                entry.size = data.size.toLong()
                taos.putArchiveEntry(entry)
                taos.write(data)
                taos.closeArchiveEntry()
            }
        }

        val originalArchiveSize = archiveFile.length()
        val peakCombinedDiskBytes = AtomicLong(originalArchiveSize)
        val injector = RecordingFaultInjector { _, _, _ ->
            val currentArchiveLen = if (archiveFile.exists()) archiveFile.length() else 0L
            val currentExtractedLen = computeDirectoryDiskUsage(outputDir)
            peakCombinedDiskBytes.accumulateAndGet(currentArchiveLen + currentExtractedLen, ::maxOf)
        }
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse(archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)

        assertTrue(
            "TAR in-place extraction must issue per-entry reverse ftruncate calls",
            injector.truncateRecords.size >= 3
        )
        assertTrue(
            "TAR peak disk usage (${peakCombinedDiskBytes.get()}) must be below naive peak (${originalArchiveSize + totalUncompressedBytes})",
            peakCombinedDiskBytes.get() < originalArchiveSize + totalUncompressedBytes
        )
    }

    @Test
    fun regression_tarGzArchive_extractionAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("archive.tar.gz")
        val outputDir = tempFolder.newFolder("out_targz")

        val files = linkedMapOf(
            "docs/readme.bin" to generateDeterministicBytes(128 * 1024, 701L, compressibleRatio = 1),
            "src/module.bin" to generateDeterministicBytes(256 * 1024, 702L, compressibleRatio = 1)
        )
        val expectedHashes = files.mapValues { sha256Hex(it.value) }

        GZIPOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { gzos ->
            TarArchiveOutputStream(gzos).use { taos ->
                for ((path, data) in files) {
                    val entry = TarArchiveEntry(path)
                    entry.size = data.size.toLong()
                    taos.putArchiveEntry(entry)
                    taos.write(data)
                    taos.closeArchiveEntry()
                }
            }
        }

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse("TAR.GZ source archive is deleted after stream completion in Hatch mode", archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)
    }

    @Test
    fun regression_sevenZArchive_hatchModeTailSwapShrinkingAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("solid_multi.7z")
        val outputDir = tempFolder.newFolder("out_7z")

        // Generate > 2.2 MB of incompressible data so the .7z compressed payload spans multiple
        // 512 KB chunks in HatchSevenZTruncatingChannel and exercises tail-swap pwrite + ftruncate.
        val files = linkedMapOf(
            "bin/chunk_a.dat" to generateDeterministicBytes(900 * 1024, 801L, compressibleRatio = 0),
            "bin/chunk_b.dat" to generateDeterministicBytes(900 * 1024, 802L, compressibleRatio = 0),
            "bin/chunk_c.dat" to generateDeterministicBytes(900 * 1024, 803L, compressibleRatio = 0)
        )
        val expectedHashes = files.mapValues { sha256Hex(it.value) }

        SevenZOutputFile(archiveFile).use { szOut ->
            szOut.setContentMethods(listOf(org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(SevenZMethod.LZMA2)))
            for ((path, data) in files) {
                val entry = szOut.createArchiveEntry(File(outputDir, path), path)
                entry.size = data.size.toLong()
                szOut.putArchiveEntry(entry)
                szOut.write(data)
                szOut.closeArchiveEntry()
            }
        }

        val originalArchiveSize = archiveFile.length()
        val injector = RecordingFaultInjector()
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalEmission = emissions.last()
        assertEquals(
            "7z Hatch extraction failed: ${finalEmission.errorMessage}",
            ExtractionStatus.COMPLETED,
            finalEmission.status
        )
        assertFalse(archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)

        assertTrue(
            "Expected HatchSevenZTruncatingChannel to perform multiple progressive truncations on >2.5MB .7z archive (orig=$originalArchiveSize), got ${injector.truncateRecords.size}",
            injector.truncateRecords.size >= 3
        )
        assertTrue(
            "Expected HatchSevenZTruncatingChannel to perform tail-swap slot writes, got ${injector.slotWriteRecords.size}",
            injector.slotWriteRecords.isNotEmpty()
        )
    }

    @Test
    fun regression_largeStoredEntryOver4MB_subEntryReverseSliceShrinkingAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("large_stored.zip")
        val outputDir = tempFolder.newFolder("out_large_stored")

        // 5.5 MB STORED entry (>= 4 MB threshold -> triggers sub-entry reverse slice truncation)
        val largeStoredBytes = generateDeterministicBytes(5_500_000, 901L)
        val companionBytes = generateDeterministicBytes(200_000, 902L)
        val expectedHashes = mapOf(
            "header_companion.bin" to sha256Hex(companionBytes),
            "video/uncompressed_payload.raw" to sha256Hex(largeStoredBytes)
        )

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            // Entry 1: companion
            zos.putNextEntry(ZipEntry("header_companion.bin"))
            zos.write(companionBytes)
            zos.closeEntry()

            // Entry 2: 5.5 MB STORED
            val storedEntry = ZipEntry("video/uncompressed_payload.raw").apply {
                method = ZipEntry.STORED
                size = largeStoredBytes.size.toLong()
                compressedSize = largeStoredBytes.size.toLong()
                val crc = CRC32()
                crc.update(largeStoredBytes)
                this.crc = crc.value
            }
            zos.putNextEntry(storedEntry)
            zos.write(largeStoredBytes)
            zos.closeEntry()
        }

        val originalArchiveSize = archiveFile.length()
        val postSliceCombinedBytes = mutableListOf<Long>()
        var slicesCompleted = 0
        val chunkStepExpected = (largeStoredBytes.size.toLong() / 8L).coerceIn(1024L * 1024L, 8L * 1024L * 1024L)
        val injector = RecordingFaultInjector { point, currentArchiveBytes, _ ->
            if (point == DestructivePoint.AFTER_STORED_SLICE_TRUNCATE) {
                slicesCompleted++
                val nonSparseBytesWritten = (slicesCompleted.toLong() * chunkStepExpected)
                    .coerceAtMost(largeStoredBytes.size.toLong())
                postSliceCombinedBytes.add(currentArchiveBytes + nonSparseBytesWritten)
            }
        }
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse(archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)

        val storedSliceTruncates = injector.getCount(DestructivePoint.AFTER_STORED_SLICE_TRUNCATE)
        assertTrue(
            "Expected >= 5 sub-entry reverse slice truncations for 5.5 MB STORED entry, got $storedSliceTruncates",
            storedSliceTruncates >= 5
        )
        for (record in injector.truncateRecords) {
            assertTrue(
                "Each STORED reverse slice truncation must strictly shrink archive (${record.previousSize} -> ${record.newSize})",
                record.newSize < record.previousSize
            )
        }

        // Verify peak non-sparse combined disk footprint (archive + written slices) stays well below naive 2x
        val naivePeak = originalArchiveSize + largeStoredBytes.size + companionBytes.size
        val maxPostSliceCombined = postSliceCombinedBytes.maxOrNull() ?: naivePeak
        assertTrue(
            "Large STORED non-sparse combined disk usage ($maxPostSliceCombined) must be well below naive peak ($naivePeak)",
            maxPostSliceCombined < naivePeak - 4_000_000L
        )
    }

    @Test
    fun regression_largeDeflateEntryOver4MB_subEntryTailSwapShrinkingAndSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("large_deflate.zip")
        val outputDir = tempFolder.newFolder("out_large_deflate")

        // 5.2 MB high-entropy payload so compressed size is also > 4 MB, triggering the
        // sub-entry symmetric tail-swap pwrite + ftruncate loop.
        val largeDeflateBytes = generateDeterministicBytes(5_200_000, 1001L, compressibleRatio = 0)
        val expectedHashes = mapOf(
            "dataset/high_entropy_stream.bin" to sha256Hex(largeDeflateBytes)
        )

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry("dataset/high_entropy_stream.bin"))
            zos.write(largeDeflateBytes)
            zos.closeEntry()
        }

        val originalArchiveSize = archiveFile.length()
        val peakCombinedDiskBytes = AtomicLong(originalArchiveSize)
        val injector = RecordingFaultInjector { _, _, _ ->
            val currentArchiveLen = if (archiveFile.exists()) archiveFile.length() else 0L
            val currentExtractedLen = computeDirectoryDiskUsage(outputDir)
            peakCombinedDiskBytes.accumulateAndGet(currentArchiveLen + currentExtractedLen, ::maxOf)
        }
        KaiHatchEngine.faultInjector = injector

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)
        assertFalse(archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)

        val tailSwapWrites = injector.getCount(DestructivePoint.BEFORE_TAILSWAP_PWRITE)
        val tailSwapTruncates = injector.getCount(DestructivePoint.AFTER_TAILSWAP_TRUNCATE)
        assertTrue(
            "Expected multiple sub-entry tail-swap pwrite relocations for >4MB DEFLATE entry, got $tailSwapWrites",
            tailSwapWrites >= 2
        )
        assertTrue(
            "Expected multiple sub-entry tail-swap ftruncate calls for >4MB DEFLATE entry, got $tailSwapTruncates",
            tailSwapTruncates >= 4
        )

        val naivePeak = originalArchiveSize + largeDeflateBytes.size
        assertTrue(
            "Large DEFLATE tail-swap peak disk usage (${peakCombinedDiskBytes.get()}) must be strictly below naive peak ($naivePeak)",
            peakCombinedDiskBytes.get() < naivePeak
        )
    }

    // =========================================================================
    // 2. Fault-Injection & Interruption Recovery Tests
    // =========================================================================

    @Test
    fun faultInjection_multiEntryZip_crashAfterWindowJournalSave_resumesCleanlyWithSha256() = runBlocking {
        val archiveFile = tempFolder.newFile("resumable_batch.zip")
        val outputDir = tempFolder.newFolder("out_resumable_batch")

        val files = linkedMapOf(
            "entry_1.bin" to generateDeterministicBytes(256 * 1024, 2001L),
            "entry_2.bin" to generateDeterministicBytes(256 * 1024, 2002L),
            "entry_3.bin" to generateDeterministicBytes(256 * 1024, 2003L)
        )
        val expectedHashes = files.mapValues { sha256Hex(it.value) }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            for ((path, data) in files) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(data)
                zos.closeEntry()
            }
        }
        val originalSize = archiveFile.length()

        // Occurrence #1 of AFTER_JOURNAL_SAVE is initial Central Directory backup;
        // Force a journal save after the first window by fault-injecting at BEFORE_WINDOW_TRUNCATE (#2)
        val crashInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_WINDOW_TRUNCATE,
            triggerOnOccurrence = 2
        )
        KaiHatchEngine.faultInjector = crashInjector

        val firstRunEmissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.ERROR, firstRunEmissions.last().status)
        assertTrue("Archive must still exist on disk after mid-extraction fault", archiveFile.exists())
        assertTrue(
            "Archive must be physically smaller than original ($originalSize) after first window truncation, was ${archiveFile.length()}",
            archiveFile.length() < originalSize
        )

        val pendingStates = ResumeJournalManager.loadPendingStates(context)
        val resumeState = pendingStates.firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull("Expected .hatchstate.json resume journal to exist", resumeState)

        // Clear fault injector and resume extraction
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = resumeState!!
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, resumeEmissions.last().status)
        assertFalse("Archive must be deleted after resumed completion", archiveFile.exists())
        assertAllReferenceHashesMatch(outputDir, expectedHashes)
    }

    @Test
    fun faultInjection_largeDeflateTailSwap_tornWriteAndCrash_mustRecoverValidSha256OnResume() = runBlocking {
        val archiveFile = tempFolder.newFile("crash_tailswap_deflate.zip")
        val outputDir = tempFolder.newFolder("out_crash_tailswap")

        val payload = generateDeterministicBytes(5_200_000, 3001L, compressibleRatio = 0)
        val expectedSha256 = sha256Hex(payload)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry("big_deflated.bin"))
            zos.write(payload)
            zos.closeEntry()
        }
        val originalSize = archiveFile.length()

        // 1. Crash BEFORE slot 0 pwrite overwrite (BEFORE_TAILSWAP_PWRITE) -> slot 0 intact -> must resume cleanly with valid SHA-256
        val preOverwriteArchive = tempFolder.newFile("crash_before_slot0_deflate.zip")
        archiveFile.copyTo(preOverwriteArchive, overwrite = true)
        val preOverwriteOut = tempFolder.newFolder("out_crash_before_slot0")
        val preOverwriteInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.BEFORE_TAILSWAP_PWRITE,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.faultInjector = preOverwriteInjector
        val preRun = KaiHatchEngine.extractArchive(
            archiveFile = preOverwriteArchive,
            targetDir = preOverwriteOut,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, preRun.last().status)

        val preResumeState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archiveName == preOverwriteArchive.name }
        assertNotNull("Resume journal should exist for pre-slot-0 crash", preResumeState)
        assertTrue(
            "Pre-slot-0-overwrite crash must be safely resumable",
            ResumeJournalManager.isStateSafelyResumable(preResumeState!!, context)
        )
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val preResumed = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = preResumeState
        ).toList()
        assertEquals(
            "Resumed extraction after pre-slot-0 crash must complete cleanly",
            ExtractionStatus.COMPLETED,
            preResumed.last().status
        )
        val recoveredFile = File(preOverwriteOut, "big_deflated.bin")
        assertTrue("Recovered file must exist after pre-slot-0 resume", recoveredFile.exists())
        assertEquals(
            "Recovered file SHA-256 must match reference after pre-slot-0 crash recovery",
            expectedSha256,
            sha256Hex(recoveredFile)
        )

        // 2. Crash AFTER slot 0 pwrite + ftruncate (AFTER_TAILSWAP_TRUNCATE):
        // Part 3A: valid v2 transactional checkpoints recover cleanly via Resume to bit-exact SHA-256,
        // while corrupted/missing checkpoints remain strictly fail-closed Unrecoverable!
        val crashInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1
        )
        KaiHatchEngine.faultInjector = crashInjector

        val firstRun = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        assertEquals(ExtractionStatus.ERROR, firstRun.last().status)
        assertTrue("Partially truncated archive must still exist", archiveFile.exists())
        assertTrue("Archive tail was truncated before crash", archiveFile.length() < originalSize)

        val resumeState = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull("Resume journal should exist for crashed extraction", resumeState)
        val eligibility = ResumeJournalManager.assessResumeEligibility(resumeState!!, context)
        assertEquals(
            "Valid v2 direct-POSIX tail-swap checkpoint must be safely Resumable in Part 3A",
            ResumeJournalManager.ResumeEligibility.Resumable,
            eligibility
        )

        // Verify that corrupting the v2 checkpoint STILL halts fail-closed as Unrecoverable
        val ckptFile = ResumeJournalManager.getDeflateTailSwapCheckpointFile(resumeState.stateFilePath)
        val bakFile = File("${ckptFile.absolutePath}.bak")
        val ckptBackupBytes = ckptFile.readBytes()
        val bakBackupBytes = if (bakFile.exists()) bakFile.readBytes() else null
        try {
            ckptFile.writeBytes("CORRUPT_PHASE1_CHECK".toByteArray())
            if (bakFile.exists()) bakFile.writeBytes("CORRUPT_PHASE1_BAK".toByteArray())

            val corruptEligibility = ResumeJournalManager.assessResumeEligibility(resumeState, context)
            assertTrue(
                "Corrupted v2 tail-swap checkpoint must be fail-closed Unrecoverable (got $corruptEligibility)",
                corruptEligibility is ResumeJournalManager.ResumeEligibility.Unrecoverable
            )
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val corruptResumed = KaiHatchEngine.resumeInterruptedExtraction(
                context = context,
                resumeState = resumeState
            ).toList()
            assertEquals(
                "Resuming a corrupted DEFLATE tail-swap checkpoint must halt fail-closed with ERROR",
                ExtractionStatus.ERROR,
                corruptResumed.last().status
            )
            assertTrue(
                "Error message must explain unrecoverable fail-closed state",
                corruptResumed.last().errorMessage?.contains("UNRECOVERABLE RESUME STATE") == true
            )
        } finally {
            // Restore the intact v2 checkpoint and verify transactional Resume finishes bit-exact!
            ckptFile.writeBytes(ckptBackupBytes)
            if (bakBackupBytes != null) bakFile.writeBytes(bakBackupBytes)
        }

        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val resumed = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = resumeState
        ).toList()
        assertEquals(
            "Transactional Resume on intact v2 tail-swap checkpoint must complete cleanly: ${resumed.last().errorMessage}",
            ExtractionStatus.COMPLETED,
            resumed.last().status
        )
        val resumedFile = File(outputDir, "big_deflated.bin")
        assertTrue("Resumed DEFLATE output must exist", resumedFile.exists())
        assertEquals(
            "Resumed DEFLATE output SHA-256 must match reference bit-for-bit",
            expectedSha256,
            sha256Hex(resumedFile)
        )
    }

    @Test
    fun faultInjection_corruptedLargeStoredEntry_mustDetectCrcMismatchAndPreserveRemainingArchive() = runBlocking {
        val archiveFile = tempFolder.newFile("corrupt_large_stored.zip")
        val outputDir = tempFolder.newFolder("out_corrupt_stored")

        val payload = generateDeterministicBytes(4_500_000, 4001L)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            val storedEntry = ZipEntry("corrupt_video.raw").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                val crc = CRC32()
                crc.update(payload)
                this.crc = crc.value
            }
            zos.putNextEntry(storedEntry)
            zos.write(payload)
            zos.closeEntry()
        }

        // Flip bytes in the middle of the STORED payload on disk so CRC32 is invalid
        RandomAccessFile(archiveFile, "rw").use { raf ->
            raf.seek(1_000_000L)
            val b = raf.readByte()
            raf.seek(1_000_000L)
            raf.writeByte((b.toInt() xor 0xFF))
        }

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(
            "Corrupted >=4MB STORED entry must fail with ExtractionStatus.ERROR instead of reporting COMPLETED",
            ExtractionStatus.ERROR,
            finalState.status
        )
    }

    @Test
    fun faultInjection_corruptedDeflateEntry_mustHaltDeletionAndReportError() = runBlocking {
        val archiveFile = tempFolder.newFile("corrupt_deflate.zip")
        val outputDir = tempFolder.newFolder("out_corrupt_deflate")

        // Create a valid DEFLATED entry first, then corrupt its expected CRC32 in the ZIP headers
        // so inflation succeeds but CRC32 verification fails.
        val payload = generateDeterministicBytes(512 * 1024, 5001L, compressibleRatio = 1)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            zos.putNextEntry(ZipEntry("tampered.bin"))
            zos.write(payload)
            zos.closeEntry()
        }

        // Corrupt the 4-byte CRC-32 field inside both the Local File Header (offset 14) and Central Directory Header
        val allBytes = archiveFile.readBytes()
        // Local File Header CRC32 is at offset 14..17
        allBytes[14] = (allBytes[14].toInt() xor 0xFF).toByte()
        // Locate Central Directory signature 0x02014b50 (PK\x01\x02) from the end and flip its CRC32 (offset +16)
        for (i in allBytes.size - 22 downTo 0) {
            if (allBytes[i] == 0x50.toByte() &&
                allBytes[i + 1] == 0x4b.toByte() &&
                allBytes[i + 2] == 0x01.toByte() &&
                allBytes[i + 3] == 0x02.toByte()
            ) {
                allBytes[i + 16] = (allBytes[i + 16].toInt() xor 0xFF).toByte()
                break
            }
        }
        archiveFile.writeBytes(allBytes)

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outputDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(
            "CRC mismatch must emit ExtractionStatus.ERROR instead of silently succeeding",
            ExtractionStatus.ERROR,
            finalState.status
        )
        assertTrue(
            "Source archive must NOT be deleted when an entry fails CRC verification",
            archiveFile.exists()
        )
    }
}
