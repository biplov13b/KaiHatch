package com.biplov.kaihatch

import com.biplov.kaihatch.engine.HatchCompressEngine
import com.biplov.kaihatch.engine.KaiHatchEngine
import com.biplov.kaihatch.engine.SpaceLabEngine
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.CompressionSettings
import com.biplov.kaihatch.model.CompressionStatus
import com.biplov.kaihatch.model.ExtractionStatus
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

        val textPreview = com.biplov.kaihatch.data.StorageHelper.readZipEntryPreview(zipFile, "notes/readme.txt")
        assertTrue(!textPreview.isBinary)
        assertTrue(textPreview.contentPreview.contains("Previewable text inside ZIP"))

        val binPreview = com.biplov.kaihatch.data.StorageHelper.readZipEntryPreview(zipFile, "bin/header.bin")
        assertTrue(binPreview.isBinary)
        assertTrue(binPreview.contentPreview.contains("HEX / ASCII INSPECTOR"))

        val singleOutDir = File(root, "single_out")
        val singleExtracted = com.biplov.kaihatch.data.StorageHelper.extractSingleZipEntry(zipFile, "notes/readme.txt", singleOutDir)
        assertNotNull(singleExtracted)
        assertEquals("Previewable text inside ZIP", singleExtracted!!.readText())
    }

    @Test
    fun testExtractedFolderAutoExpansionTargetsExactOutputFolder() = runBlocking {
        val root = tempFolder.newFolder("extracted_folder_selection_test")

        // 1. Existing folder: Demo_Archive_extracted
        val demoDir = File(root, "Demo_Archive_extracted").apply { mkdirs() }
        File(demoDir, "demo.txt").writeText("Previously extracted demo archive")

        // Similar folder name: A (1)_extracted
        val similarDir = File(root, "A (1)_extracted").apply { mkdirs() }
        File(similarDir, "a1.txt").writeText("Previously extracted A (1)")

        // Newly extracted archive: A (10).zip -> A (10)_extracted
        val a10Zip = File(root, "A (10).zip")
        ZipOutputStream(FileOutputStream(a10Zip)).use { zos ->
            zos.putNextEntry(ZipEntry("inside_a10.txt"))
            zos.write("Unpacked from A (10).zip".toByteArray())
            zos.closeEntry()
        }

        val a10Dir = File(root, "A (10)_extracted")
        val progressList = KaiHatchEngine.extractArchive(
            archiveFile = a10Zip,
            targetDir = a10Dir,
            isHatchMode = false
        ).toList()
        val finalProgress = progressList.last()
        assertEquals(ExtractionStatus.COMPLETED, finalProgress.status)
        assertTrue(File(a10Dir, "inside_a10.txt").exists())

        val demoEntry = com.biplov.kaihatch.data.ExtractedFolderEntry(
            id = "primary:Download/Demo_Archive_extracted",
            name = "Demo_Archive_extracted",
            isSaf = true,
            localFile = demoDir,
            totalSizeBytes = 33L
        )
        val similarEntry = com.biplov.kaihatch.data.ExtractedFolderEntry(
            id = "primary:Download/A (1)_extracted",
            name = "A (1)_extracted",
            isSaf = true,
            localFile = similarDir,
            totalSizeBytes = 25L
        )
        val a10Entry = com.biplov.kaihatch.data.ExtractedFolderEntry(
            id = "primary:Download/A (10)_extracted",
            name = "A (10)_extracted",
            isSaf = true,
            localFile = a10Dir,
            totalSizeBytes = 24L
        )

        val completedTarget = com.biplov.kaihatch.ui.CompletedExtractionTarget(
            completionId = 2L,
            folderDocumentId = "primary:Download/A (10)_extracted",
            canonicalDirPath = a10Dir.canonicalPath,
            absoluteDirPath = a10Dir.absolutePath,
            outputDirectory = finalProgress.outputDirectory,
            folderName = a10Dir.name
        )

        // 2. Before folder-list refresh completes (when only Demo_Archive_extracted and A (1)_extracted are present):
        // Must return null and NEVER fall back to Demo_Archive_extracted (firstOrNull).
        val beforeRefresh = com.biplov.kaihatch.ui.screens.resolveTargetExtractedFolder(
            extractedFolders = listOf(demoEntry, similarEntry),
            completedTarget = completedTarget,
            fallbackOutputDirectory = finalProgress.outputDirectory
        )
        org.junit.Assert.assertNull(beforeRefresh)

        // 3. After folder-list refresh completes across all list positions (first, middle, last):
        val listOrderings = listOf(
            listOf(demoEntry, a10Entry),                 // Exact bug report scenario: Demo first, A (10) second
            listOf(demoEntry, a10Entry, similarEntry),   // Target in middle
            listOf(demoEntry, similarEntry, a10Entry),   // Target last
            listOf(a10Entry, demoEntry, similarEntry)    // Target first
        )

        for (folders in listOrderings) {
            val matched = com.biplov.kaihatch.ui.screens.resolveTargetExtractedFolder(
                extractedFolders = folders,
                completedTarget = completedTarget,
                fallbackOutputDirectory = finalProgress.outputDirectory
            )
            assertNotNull(matched)
            assertEquals("primary:Download/A (10)_extracted", matched!!.id)
            assertEquals("A (10)_extracted", matched.name)

            assertTrue(com.biplov.kaihatch.ui.screens.isExtractedFolderExpanded(a10Entry, matched.id, folders))
            assertEquals(false, com.biplov.kaihatch.ui.screens.isExtractedFolderExpanded(demoEntry, matched.id, folders))
            assertEquals(false, com.biplov.kaihatch.ui.screens.isExtractedFolderExpanded(similarEntry, matched.id, folders))
        }
    }
}
