package com.biplov.kaihatch.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32

/**
 * 7z Extraction Safety Regression Suite:
 * Verifies removal of the unsafe 7z `.bin`/`.iso` fallback extractor and hardcoded
 * filename heuristics (`Burnout`, `(Europe)`, `(USA)`, `iso`), accurate source-archive
 * consumption reporting when progressive 7z extraction modifies the archive before failing,
 * preservation of previously verified output files, and preservation of legitimate 7z extraction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SevenZExtractionSafetyRegressionTest {

    private lateinit var context: Context
    private lateinit var sandboxRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sandboxRoot = Files.createTempDirectory("kaihatch_phase5_7z_test_").toFile()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        sandboxRoot.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun generatePayload(seed: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        val rng = Random(seed)
        var offset = 0
        val block = ByteArray(256)
        while (offset < size) {
            rng.nextBytes(block)
            for (i in 0 until minOf(128, block.size)) {
                block[i] = ((offset + i) and 0x7F).toByte()
            }
            val copyLen = minOf(block.size, size - offset)
            System.arraycopy(block, 0, out, offset, copyLen)
            offset += copyLen
        }
        return out
    }

    private fun createSevenZArchive(
        file: File,
        entries: Map<String, ByteArray>,
        method: SevenZMethod = SevenZMethod.LZMA2
    ) {
        SevenZOutputFile(file).use { sevenZOut ->
            sevenZOut.setContentMethods(listOf(SevenZMethodConfiguration(method)))
            for ((path, data) in entries) {
                val entry: SevenZArchiveEntry = sevenZOut.createArchiveEntry(File(sandboxRoot, path), path)
                entry.size = data.size.toLong()
                sevenZOut.putArchiveEntry(entry)
                sevenZOut.write(data)
                sevenZOut.closeArchiveEntry()
            }
            sevenZOut.finish()
        }
    }

    /**
     * Recomputes NextHeaderCRC and StartHeaderCRC in a 32-byte 7z signature header after patching
     * bytes inside the unencoded NextHeader region, so that 7z header CRCs are valid and SevenZFile
     * parses the modified NextHeader directly.
     */
    private fun recomputeSevenZHeaderCrcs(archiveFile: File) {
        RandomAccessFile(archiveFile, "rw").use { raf ->
            val sigHeader = ByteArray(32)
            raf.seek(0L)
            raf.readFully(sigHeader)
            val sigBuf = ByteBuffer.wrap(sigHeader).order(ByteOrder.LITTLE_ENDIAN)
            sigBuf.position(12)
            val nextHeaderOffset = sigBuf.long
            val nextHeaderSize = sigBuf.long.toInt()
            val nextHeaderPos = 32L + nextHeaderOffset

            val nextHeaderBytes = ByteArray(nextHeaderSize)
            raf.seek(nextHeaderPos)
            raf.readFully(nextHeaderBytes)

            val newNextHeaderCrc = CRC32().apply { update(nextHeaderBytes) }.value.toInt()
            sigBuf.putInt(28, newNextHeaderCrc)

            val newStartHeaderCrc = CRC32().apply { update(sigHeader, 12, 20) }.value.toInt()
            sigBuf.putInt(8, newStartHeaderCrc)

            raf.seek(0L)
            raf.write(sigHeader)
            raf.fd.sync()
        }
    }

    // =========================================================================
    // 1. Corrupted 7z data (header & payload) never creates .bin/.iso or COMPLETED
    // =========================================================================

    @Test
    fun corrupted7zHeaderAndPayload_returnsClearErrorWithoutGeneratingFakeBinOrIsoFiles() = runBlocking {
        val archiveNames = listOf(
            "Burnout_Legends_(Europe).7z",
            "disc_image_(USA).7z",
            "corrupt_bundle.7z"
        )

        for ((idx, archiveName) in archiveNames.withIndex()) {
            val caseDir = File(sandboxRoot, "corrupt_case_$idx").apply { mkdirs() }
            val archiveFile = File(caseDir, archiveName)
            val outDir = File(caseDir, "out").apply { mkdirs() }

            createSevenZArchive(
                file = archiveFile,
                entries = linkedMapOf(
                    "real_file.txt" to generatePayload(100L + idx, 96 * 1024)
                ),
                method = SevenZMethod.LZMA2
            )
            val originalSize = archiveFile.length()

            // Corrupt either the tail NextHeader or StartHeader/PackData
            RandomAccessFile(archiveFile, "rw").use { raf ->
                if (idx % 2 == 0) {
                    // Corrupt NextHeader at the end of the 7z file (previously triggered the .iso/.bin fallback)
                    val pos = (originalSize - 24L).coerceAtLeast(32L)
                    raf.seek(pos)
                    for (i in 0 until 16) {
                        raf.write(0xFF)
                    }
                } else {
                    // Corrupt StartHeader + compressed stream bytes
                    raf.seek(20L)
                    for (i in 0 until 24) {
                        raf.write(0xA5)
                    }
                }
                raf.fd.sync()
            }

            // 1. ZipParser must return emptyList() and never fabricate .iso or .bin entries
            val parsedEntries = ZipParser.parseArchive(archiveFile)
            assertTrue(
                "ZipParser must not fabricate fake .iso/.bin entries for corrupted $archiveName, got: $parsedEntries",
                parsedEntries.isEmpty()
            )

            // 2. KaiHatchEngine extraction in Hatch Mode must fail cleanly with ERROR
            val emissions = KaiHatchEngine.extractArchive(
                archiveFile = archiveFile,
                targetDir = outDir,
                isHatchMode = true,
                context = context
            ).toList()

            val finalState = emissions.last()
            assertEquals(
                "Corrupted $archiveName must report ERROR",
                ExtractionStatus.ERROR,
                finalState.status
            )
            assertFalse("isCrcValid must be false on corrupted 7z error", finalState.isCrcValid)
            assertTrue(
                "No emission may report COMPLETED for corrupted $archiveName",
                emissions.none { it.status == ExtractionStatus.COMPLETED }
            )

            // 3. Output directory must contain ZERO .bin or .iso fallback files (and zero files overall)
            val extractedFiles = outDir.walkTopDown().filter { it.isFile }.toList()
            assertTrue(
                "No fabricated .bin/.iso or partial files should exist in output directory for $archiveName, found: $extractedFiles",
                extractedFiles.isEmpty()
            )

            // 4. Source archive must remain completely intact on disk and report INTACT
            assertTrue("Source 7z archive must remain on disk", archiveFile.exists())
            assertEquals("Source 7z archive size must be untouched", originalSize, archiveFile.length())
            val errorMsg = finalState.errorMessage ?: ""
            assertTrue(
                "Error message must report SOURCE ARCHIVE STATE: INTACT, got: $errorMsg",
                errorMsg.contains("SOURCE ARCHIVE STATE: INTACT")
            )
        }
    }

    // =========================================================================
    // 2. Unsupported 7z compression method returns clear error & preserves archive
    // =========================================================================

    @Test
    fun unsupported7zCompressionMethod_returnsClearErrorAndPreservesSourceArchive() = runBlocking {
        val archiveFile = File(sandboxRoot, "Burnout_Unsupported_Codec_(Europe).7z")
        val outDir = File(sandboxRoot, "out_unsupported").apply { mkdirs() }

        // Create a valid single-entry 7z archive with DEFLATE method (coder bytes: 0x03, 0x04, 0x01, 0x08)
        createSevenZArchive(
            file = archiveFile,
            entries = linkedMapOf(
                "payload/data.txt" to "Unsupported codec test payload".repeat(512).toByteArray()
            ),
            method = SevenZMethod.DEFLATE
        )
        val originalSize = archiveFile.length()

        // Patch the 3-byte coder method ID inside NextHeader from DEFLATE (0x04, 0x01, 0x08)
        // to an unsupported 7z compression method ID (0x04, 0xF7, 0x11) and recompute
        // NextHeaderCRC + StartHeaderCRC so the 7z header framing & CRCs remain valid.
        RandomAccessFile(archiveFile, "rw").use { raf ->
            val sigHeader = ByteArray(32)
            raf.seek(0L)
            raf.readFully(sigHeader)
            val sigBuf = ByteBuffer.wrap(sigHeader).order(ByteOrder.LITTLE_ENDIAN)
            sigBuf.position(12)
            val nextHeaderOffset = sigBuf.long
            val nextHeaderSize = sigBuf.long.toInt()
            val nextHeaderPos = 32L + nextHeaderOffset

            val nextHeader = ByteArray(nextHeaderSize)
            raf.seek(nextHeaderPos)
            raf.readFully(nextHeader)

            // Locate coderFlags=0x03 followed by DEFLATE method ID (0x04, 0x01, 0x08)
            var patched = false
            for (i in 0 until nextHeader.size - 3) {
                if (nextHeader[i] == 0x03.toByte() &&
                    nextHeader[i + 1] == 0x04.toByte() &&
                    nextHeader[i + 2] == 0x01.toByte() &&
                    nextHeader[i + 3] == 0x08.toByte()
                ) {
                    nextHeader[i + 2] = 0xF7.toByte()
                    nextHeader[i + 3] = 0x11.toByte()
                    patched = true
                    break
                }
            }
            assertTrue("Must locate and patch 7z coder method ID in NextHeader", patched)

            raf.seek(nextHeaderPos)
            raf.write(nextHeader)
            raf.fd.sync()
        }
        recomputeSevenZHeaderCrcs(archiveFile)

        // 1. ZipParser must not fabricate a fake .iso/.bin entry
        val parsed = ZipParser.parseArchive(archiveFile)
        assertTrue(
            "ZipParser must not fabricate a fake .iso/.bin entry for unsupported 7z coder: $parsed",
            parsed.none { it.path.endsWith(".iso") || it.path.endsWith(".bin") }
        )

        // 2. KaiHatchEngine must return ExtractionStatus.ERROR and preserve the archive intact
        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(ExtractionStatus.ERROR, finalState.status)
        assertFalse(finalState.isCrcValid)
        assertTrue(emissions.none { it.status == ExtractionStatus.COMPLETED })

        val extractedFiles = outDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("No misleading .bin/.iso output may be created", extractedFiles.isEmpty())
        assertEquals("Source archive must be completely preserved", originalSize, archiveFile.length())
        val errMsg = finalState.errorMessage ?: ""
        assertTrue(
            "Error message must describe the failure and report INTACT source archive, got: $errMsg",
            errMsg.contains("SOURCE ARCHIVE STATE: INTACT")
        )
    }

    // =========================================================================
    // 3. Filename-independent legitimate 7z extraction (Standard & Hatch Mode)
    // =========================================================================

    @Test
    fun filenameIndependent7zBehavior_extractsActualEntriesRegardlessOfBurnoutOrRegionInArchiveName() = runBlocking {
        val expectedEntries = linkedMapOf(
            "docs/readme.txt" to "KaiHatch legitimate 7z extraction test.\n".repeat(400).toByteArray(),
            "config/settings.json" to """{"mode":"hatch","verifyCrc":true}""".repeat(300).toByteArray(),
            "assets/track_data.dat" to generatePayload(777L, 384 * 1024)
        )
        val expectedHashes = expectedEntries.mapValues { sha256Hex(it.value) }

        val archiveNames = listOf(
            "Burnout_Dominator_(Europe).7z",
            "Disc_Backup_(USA)_iso_edition.7z",
            "normal_bundle.7z"
        )

        for ((idx, archiveName) in archiveNames.withIndex()) {
            val isHatchMode = idx != 1 // Test both Hatch Mode (idx 0, 2) and Standard Mode (idx 1)
            val caseDir = File(sandboxRoot, "legit_case_$idx").apply { mkdirs() }
            val archiveFile = File(caseDir, archiveName)
            val outDir = File(caseDir, "out").apply { mkdirs() }

            createSevenZArchive(archiveFile, expectedEntries, SevenZMethod.LZMA2)
            val originalSize = archiveFile.length()

            // Verify ZipParser returns the exact real entries regardless of filename
            val parsedEntries = ZipParser.parseArchive(archiveFile)
            assertEquals(3, parsedEntries.size)
            assertEquals(expectedEntries.keys.toList(), parsedEntries.map { it.path })

            // Verify extraction
            val emissions = KaiHatchEngine.extractArchive(
                archiveFile = archiveFile,
                targetDir = outDir,
                isHatchMode = isHatchMode,
                context = context
            ).toList()

            val finalState = emissions.last()
            assertEquals(
                "Legitimate 7z archive $archiveName must succeed: ${finalState.errorMessage}",
                ExtractionStatus.COMPLETED,
                finalState.status
            )

            // Verify exact extracted files and SHA-256 hashes (and no fabricated .iso or .bin files)
            for ((relPath, expectedSha) in expectedHashes) {
                val outFile = File(outDir, relPath)
                assertTrue("Expected real entry $relPath to be extracted from $archiveName", outFile.exists())
                assertEquals(expectedSha, sha256Hex(outFile))
            }
            val allOutFiles = outDir.walkTopDown().filter { it.isFile }.toList()
            assertEquals("Only the 3 real entries may exist in output dir", 3, allOutFiles.size)
            assertTrue(
                "No fabricated .iso or .bin file may exist",
                allOutFiles.none { it.name.endsWith(".iso") || it.name.endsWith(".bin") }
            )

            if (isHatchMode) {
                assertTrue(
                    "Hatch Mode must progressively reclaim and delete completed 7z archive $archiveName",
                    !archiveFile.exists() || archiveFile.length() == 0L
                )
            } else {
                assertTrue("Standard Mode must keep source 7z archive intact", archiveFile.exists())
                assertEquals(originalSize, archiveFile.length())
            }
        }
    }

    // =========================================================================
    // 4. Multi-chunk 7z failure after progressive modification:
    //    - Preserves previously verified files
    //    - Quarantines failed partial output
    //    - Reports PARTIALLY CONSUMED IN-PLACE (never INTACT or PAYLOAD INTACT)
    // =========================================================================

    @Test
    fun multiChunk7zFailureAfterProgressiveModification_preservesVerifiedFilesAndReportsPartiallyConsumed() = runBlocking {
        val archiveFile = File(sandboxRoot, "multi_chunk_progressive.7z")
        val outDir = File(sandboxRoot, "out_multi_chunk").apply { mkdirs() }

        // Two entries totaling ~2.7 MB using COPY method so HatchSevenZTruncatingChannel (1 MB chunks)
        // relocates and truncates chunk 0 during extraction of the first entry.
        val firstPayload = generatePayload(501L, 1500 * 1024) // 1.5 MB
        val secondPayload = generatePayload(502L, 1200 * 1024) // 1.2 MB
        val firstSha = sha256Hex(firstPayload)

        createSevenZArchive(
            file = archiveFile,
            entries = linkedMapOf(
                "01_verified_first.dat" to firstPayload,
                "02_corrupt_second.dat" to secondPayload
            ),
            method = SevenZMethod.COPY
        )
        val originalSize = archiveFile.length()
        assertTrue("Multi-chunk 7z archive must exceed 2.5 MB", originalSize > 2500 * 1024L)

        // Corrupt bytes inside the second entry's payload (around 2.0 MB offset) without touching
        // the first entry (32 .. 1.5 MB) or the tail NextHeader, so entry 1 extracts & verifies,
        // triggers progressive chunk relocation/truncation, and entry 2 fails CRC32 verification.
        RandomAccessFile(archiveFile, "rw").use { raf ->
            val corruptOffset = 32L + firstPayload.size + (256 * 1024L)
            raf.seek(corruptOffset)
            for (i in 0 until 64) {
                val b = raf.read()
                raf.seek(corruptOffset + i)
                raf.write(b xor 0xFF)
            }
            raf.fd.sync()
        }

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(ExtractionStatus.ERROR, finalState.status)
        assertFalse(finalState.isCrcValid)
        assertTrue(emissions.none { it.status == ExtractionStatus.COMPLETED })

        // 1. Previously verified first file must be preserved with exact SHA-256
        val firstOutFile = File(outDir, "01_verified_first.dat")
        assertTrue("Previously verified first file must be preserved", firstOutFile.exists())
        assertEquals(firstSha, sha256Hex(firstOutFile))

        // 2. Corrupted second file must be quarantined/removed
        val secondOutFile = File(outDir, "02_corrupt_second.dat")
        assertFalse("Corrupted second file must be quarantined/deleted", secondOutFile.exists())

        // 3. Source archive must still exist with remaining bytes (not truncated to 0),
        //    and must be smaller than originalSize because progressive extraction already shrunk it.
        assertTrue("Partially consumed 7z archive must not be deleted", archiveFile.exists())
        assertTrue(
            "Progressive extraction must have shrunk the archive before entry 2 failed (got ${archiveFile.length()} vs $originalSize)",
            archiveFile.length() in 1L until originalSize
        )

        // 4. Error message must report PARTIALLY CONSUMED IN-PLACE and never claim INTACT
        val errMsg = finalState.errorMessage ?: ""
        assertNotNull(finalState.errorMessage)
        assertTrue(
            "Error message must report PARTIALLY CONSUMED IN-PLACE, got: $errMsg",
            errMsg.contains("SOURCE ARCHIVE STATE: PARTIALLY CONSUMED IN-PLACE")
        )
        assertFalse(
            "Error message must never claim SOURCE ARCHIVE STATE: INTACT after progressive modification",
            errMsg.contains("SOURCE ARCHIVE STATE: INTACT")
        )
        assertFalse(
            "Error message must never claim SOURCE ARCHIVE STATE: PAYLOAD INTACT for modified 7z archive",
            errMsg.contains("SOURCE ARCHIVE STATE: PAYLOAD INTACT")
        )
    }
}
