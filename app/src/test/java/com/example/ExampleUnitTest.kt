package com.example

import com.example.engine.HatchCompressEngine
import com.example.engine.KaiHatchEngine
import com.example.engine.SpaceLabEngine
import com.example.engine.ZipParser
import com.example.model.ArchiveFormat
import com.example.model.CompressionSettings
import com.example.model.CompressionStatus
import com.example.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExampleUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testZipParserAndHatchInPlaceExtraction() = runBlocking {
        val root = tempFolder.newFolder("hatch_test")
        val zipFile = File(root, "test_archive.zip")

        // 1. Create a zip archive with multiple entries
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry1 = ZipEntry("doc1.txt")
            zos.putNextEntry(entry1)
            zos.write("KaiHatch progressive in-place extractor for mobile".toByteArray())
            zos.closeEntry()

            val entry2 = ZipEntry("nested/doc2.txt")
            zos.putNextEntry(entry2)
            zos.write("Saves up to 50% duplicate disk space during large file unpacks!".toByteArray())
            zos.closeEntry()

            val entry3 = ZipEntry("binary.dat")
            zos.putNextEntry(entry3)
            zos.write(ByteArray(4096) { it.toByte() })
            zos.closeEntry()
        }

        assertTrue(zipFile.exists())
        val initialSize = zipFile.length()
        assertTrue(initialSize > 0)

        // 2. Parse Central Directory
        val entries = ZipParser.parseArchive(zipFile)
        assertEquals(3, entries.size)
        val names = entries.map { it.path }
        assertTrue(names.contains("doc1.txt"))
        assertTrue(names.contains("nested/doc2.txt"))
        assertTrue(names.contains("binary.dat"))

        // 3. Test Storage Summary Calculation
        val summary = SpaceLabEngine.computeArchiveSummary(zipFile, 1024L * 1024L * 100L)
        assertNotNull(summary)
        assertTrue(summary.standardPeakSpaceRequired > summary.hatchPeakSpaceRequired)
        assertTrue(summary.spaceSavedWithHatch > 0)

        // 4. Test Hatch In-Place Reverse Truncation Extraction
        val outputDir = File(root, "extracted_output")
        val progressList = KaiHatchEngine.extractArchive(zipFile, outputDir, isHatchMode = true).toList()

        val lastProgress = progressList.last()
        assertEquals(ExtractionStatus.COMPLETED, lastProgress.status)
        assertEquals(3, lastProgress.completedEntries)
        assertTrue(lastProgress.isCrcValid)

        // Verify extracted files exist and match content
        val extractedFile1 = File(outputDir, "doc1.txt")
        assertTrue(extractedFile1.exists())
        assertEquals("KaiHatch progressive in-place extractor for mobile", extractedFile1.readText())

        val extractedFile2 = File(outputDir, "nested/doc2.txt")
        assertTrue(extractedFile2.exists())
        assertEquals("Saves up to 50% duplicate disk space during large file unpacks!", extractedFile2.readText())

        val extractedFile3 = File(outputDir, "binary.dat")
        assertTrue(extractedFile3.exists())
        assertEquals(4096, extractedFile3.length())

        // In Hatch mode, the original zip file was truncated down to 0 and deleted
        assertTrue(!zipFile.exists() || zipFile.length() == 0L)
    }

    @Test
    fun testEncryptedZipAndTarInPlaceRoundTrip() = runBlocking {
        val root = tempFolder.newFolder("encrypted_and_tar_test")
        val srcFile1 = File(root, "secret_note.txt").apply {
            writeText("Top secret encrypted payload inside ZipCrypto container!")
        }
        val srcFile2 = File(root, "tar_payload.txt").apply {
            writeText("POSIX UStar TAR reverse block truncation test payload!")
        }

        // 1. Test Password-Protected ZIP Compression & In-Place Extraction
        val encryptedZip = File(root, "encrypted.zip")
        val zipCompressList = HatchCompressEngine.compressFiles(
            itemsToCompress = listOf(srcFile1),
            targetArchiveFile = encryptedZip,
            settings = CompressionSettings(
                archiveName = "encrypted.zip",
                format = ArchiveFormat.ZIP,
                password = "kaihatch_password_123"
            )
        ).toList()
        assertEquals(CompressionStatus.COMPLETED, zipCompressList.last().status)
        assertTrue(encryptedZip.exists())

        val parsedEnc = ZipParser.parseArchive(encryptedZip)
        assertEquals(1, parsedEnc.size)
        assertTrue(parsedEnc.first().isEncrypted)

        val encOutDir = File(root, "enc_out")
        val encExtractList = KaiHatchEngine.extractArchive(
            archiveFile = encryptedZip,
            targetDir = encOutDir,
            isHatchMode = true,
            password = "kaihatch_password_123"
        ).toList()
        assertEquals(ExtractionStatus.COMPLETED, encExtractList.last().status)
        assertEquals(
            "Top secret encrypted payload inside ZipCrypto container!",
            File(encOutDir, "secret_note.txt").readText()
        )

        // 2. Test TAR Creation & Reverse 512-byte Block Truncation Extraction
        val tarFile = File(root, "bundle.tar")
        val tarCompressList = HatchCompressEngine.compressFiles(
            itemsToCompress = listOf(srcFile1, srcFile2),
            targetArchiveFile = tarFile,
            settings = CompressionSettings(
                archiveName = "bundle.tar",
                format = ArchiveFormat.TAR
            )
        ).toList()
        assertEquals(CompressionStatus.COMPLETED, tarCompressList.last().status)
        assertTrue(tarFile.exists())

        val tarOutDir = File(root, "tar_out")
        val tarExtractList = KaiHatchEngine.extractArchive(
            archiveFile = tarFile,
            targetDir = tarOutDir,
            isHatchMode = true
        ).toList()
        assertEquals(ExtractionStatus.COMPLETED, tarExtractList.last().status)
        assertEquals(
            "POSIX UStar TAR reverse block truncation test payload!",
            File(tarOutDir, "tar_payload.txt").readText()
        )
        assertTrue(!tarFile.exists() || tarFile.length() == 0L)
    }

    @Test
    fun testFilePreviewAndSingleEntryExtraction() {
        val root = tempFolder.newFolder("preview_test")
        val zipFile = File(root, "preview_archive.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("notes/readme.txt"))
            zos.write("Previewable text inside ZIP".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("bin/header.bin"))
            zos.write(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x7F, 0x45, 0x4C, 0x46))
            zos.closeEntry()
        }

        val textPreview = com.example.data.StorageHelper.readZipEntryPreview(zipFile, "notes/readme.txt")
        assertTrue(!textPreview.isBinary)
        assertTrue(textPreview.contentPreview.contains("Previewable text inside ZIP"))

        val binPreview = com.example.data.StorageHelper.readZipEntryPreview(zipFile, "bin/header.bin")
        assertTrue(binPreview.isBinary)
        assertTrue(binPreview.contentPreview.contains("HEX / ASCII INSPECTOR"))

        val singleOutDir = File(root, "single_out")
        val singleExtracted = com.example.data.StorageHelper.extractSingleZipEntry(zipFile, "notes/readme.txt", singleOutDir)
        assertNotNull(singleExtracted)
        assertEquals("Previewable text inside ZIP", singleExtracted!!.readText())
    }
}
