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

    @Test
    fun testAppLanguageAndAccentModels() {
        assertEquals("", com.biplov.kaihatch.model.AppLanguage.SYSTEM.tag)
        assertEquals("System Default", com.biplov.kaihatch.model.AppLanguage.SYSTEM.label)
        assertEquals("System Default", com.biplov.kaihatch.model.AppLanguage.SYSTEM.nativeName)
        assertEquals("en", com.biplov.kaihatch.model.AppLanguage.ENGLISH.tag)
        assertEquals("English", com.biplov.kaihatch.model.AppLanguage.ENGLISH.label)
        assertEquals("English", com.biplov.kaihatch.model.AppLanguage.ENGLISH.nativeName)
        assertEquals("es", com.biplov.kaihatch.model.AppLanguage.SPANISH.tag)
        assertEquals("Español", com.biplov.kaihatch.model.AppLanguage.SPANISH.nativeName)
        assertEquals("fr", com.biplov.kaihatch.model.AppLanguage.FRENCH.tag)
        assertEquals("Français", com.biplov.kaihatch.model.AppLanguage.FRENCH.nativeName)
        assertEquals("de", com.biplov.kaihatch.model.AppLanguage.GERMAN.tag)
        assertEquals("Deutsch", com.biplov.kaihatch.model.AppLanguage.GERMAN.nativeName)
        assertEquals("it", com.biplov.kaihatch.model.AppLanguage.ITALIAN.tag)
        assertEquals("Italiano", com.biplov.kaihatch.model.AppLanguage.ITALIAN.nativeName)
        assertEquals("pt-BR", com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_BR.tag)
        assertEquals("Português (Brasil)", com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_BR.nativeName)
        assertEquals("pt-PT", com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_PT.tag)
        assertEquals("Português (Portugal)", com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_PT.nativeName)
        assertEquals("nl", com.biplov.kaihatch.model.AppLanguage.DUTCH.tag)
        assertEquals("Nederlands", com.biplov.kaihatch.model.AppLanguage.DUTCH.nativeName)
        assertEquals("ru", com.biplov.kaihatch.model.AppLanguage.RUSSIAN.tag)
        assertEquals("Русский", com.biplov.kaihatch.model.AppLanguage.RUSSIAN.nativeName)
        assertEquals("uk", com.biplov.kaihatch.model.AppLanguage.UKRAINIAN.tag)
        assertEquals("Українська", com.biplov.kaihatch.model.AppLanguage.UKRAINIAN.nativeName)
        assertEquals("pl", com.biplov.kaihatch.model.AppLanguage.POLISH.tag)
        assertEquals("Polski", com.biplov.kaihatch.model.AppLanguage.POLISH.nativeName)
        assertEquals("cs", com.biplov.kaihatch.model.AppLanguage.CZECH.tag)
        assertEquals("Čeština", com.biplov.kaihatch.model.AppLanguage.CZECH.nativeName)
        assertEquals("ro", com.biplov.kaihatch.model.AppLanguage.ROMANIAN.tag)
        assertEquals("Română", com.biplov.kaihatch.model.AppLanguage.ROMANIAN.nativeName)
        assertEquals("hu", com.biplov.kaihatch.model.AppLanguage.HUNGARIAN.tag)
        assertEquals("Magyar", com.biplov.kaihatch.model.AppLanguage.HUNGARIAN.nativeName)
        assertEquals("el", com.biplov.kaihatch.model.AppLanguage.GREEK.tag)
        assertEquals("Ελληνικά", com.biplov.kaihatch.model.AppLanguage.GREEK.nativeName)
        assertEquals("tr", com.biplov.kaihatch.model.AppLanguage.TURKISH.tag)
        assertEquals("Türkçe", com.biplov.kaihatch.model.AppLanguage.TURKISH.nativeName)
        assertEquals("ja", com.biplov.kaihatch.model.AppLanguage.JAPANESE.tag)
        assertEquals("日本語", com.biplov.kaihatch.model.AppLanguage.JAPANESE.nativeName)
        assertEquals("ko", com.biplov.kaihatch.model.AppLanguage.KOREAN.tag)
        assertEquals("한국어", com.biplov.kaihatch.model.AppLanguage.KOREAN.nativeName)
        assertEquals("zh-CN", com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED.tag)
        assertEquals("简体中文", com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED.nativeName)
        assertEquals("zh-TW", com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL.tag)
        assertEquals("繁體中文", com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL.nativeName)
        assertEquals("id", com.biplov.kaihatch.model.AppLanguage.INDONESIAN.tag)
        assertEquals("Bahasa Indonesia", com.biplov.kaihatch.model.AppLanguage.INDONESIAN.nativeName)
        assertEquals("ms", com.biplov.kaihatch.model.AppLanguage.MALAY.tag)
        assertEquals("Bahasa Melayu", com.biplov.kaihatch.model.AppLanguage.MALAY.nativeName)
        assertEquals("vi", com.biplov.kaihatch.model.AppLanguage.VIETNAMESE.tag)
        assertEquals("Tiếng Việt", com.biplov.kaihatch.model.AppLanguage.VIETNAMESE.nativeName)
        assertEquals("th", com.biplov.kaihatch.model.AppLanguage.THAI.tag)
        assertEquals("ไทย", com.biplov.kaihatch.model.AppLanguage.THAI.nativeName)
        assertEquals("fil", com.biplov.kaihatch.model.AppLanguage.FILIPINO.tag)
        assertEquals("Filipino", com.biplov.kaihatch.model.AppLanguage.FILIPINO.nativeName)
        assertEquals("ar", com.biplov.kaihatch.model.AppLanguage.ARABIC.tag)
        assertEquals("العربية", com.biplov.kaihatch.model.AppLanguage.ARABIC.nativeName)
        assertTrue(com.biplov.kaihatch.model.AppLanguage.ARABIC.isRtl)
        assertEquals("he", com.biplov.kaihatch.model.AppLanguage.HEBREW.tag)
        assertEquals("עברית", com.biplov.kaihatch.model.AppLanguage.HEBREW.nativeName)
        assertTrue(com.biplov.kaihatch.model.AppLanguage.HEBREW.isRtl)
        assertEquals("fa", com.biplov.kaihatch.model.AppLanguage.PERSIAN.tag)
        assertEquals("فارسی", com.biplov.kaihatch.model.AppLanguage.PERSIAN.nativeName)
        assertTrue(com.biplov.kaihatch.model.AppLanguage.PERSIAN.isRtl)
        assertEquals("sv", com.biplov.kaihatch.model.AppLanguage.SWEDISH.tag)
        assertEquals("Svenska", com.biplov.kaihatch.model.AppLanguage.SWEDISH.nativeName)
        assertEquals("da", com.biplov.kaihatch.model.AppLanguage.DANISH.tag)
        assertEquals("Dansk", com.biplov.kaihatch.model.AppLanguage.DANISH.nativeName)
        assertEquals("nb", com.biplov.kaihatch.model.AppLanguage.NORWEGIAN_BOKMAL.tag)
        assertEquals("Norsk bokmål", com.biplov.kaihatch.model.AppLanguage.NORWEGIAN_BOKMAL.nativeName)
        assertEquals("fi", com.biplov.kaihatch.model.AppLanguage.FINNISH.tag)
        assertEquals("Suomi", com.biplov.kaihatch.model.AppLanguage.FINNISH.nativeName)
        assertEquals(false, com.biplov.kaihatch.model.AppLanguage.ENGLISH.isRtl)

        assertEquals(com.biplov.kaihatch.model.AppLanguage.SYSTEM, com.biplov.kaihatch.model.AppLanguage.fromSavedValue(null))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.SYSTEM, com.biplov.kaihatch.model.AppLanguage.fromSavedValue(""))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.SYSTEM, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("SYSTEM"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.ENGLISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ENGLISH"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.ENGLISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("en"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.ENGLISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("en-US"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.SPANISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("es-ES"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.FRENCH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("fr-FR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.GERMAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("de-DE"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.ITALIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("it-IT"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_BR, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt-BR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_BR, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt_BR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_BR, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_PT, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt-PT"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_PT, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt_PT"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_PT, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt-AO"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PORTUGUESE_PT, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pt-MZ"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.DUTCH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("nl-NL"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.RUSSIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ru-RU"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.UKRAINIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("uk-UA"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.POLISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("pl-PL"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CZECH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("cs-CZ"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.ROMANIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ro-RO"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.HUNGARIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("hu-HU"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.GREEK, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("el-GR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.TURKISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("tr-TR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.JAPANESE, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ja-JP"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.KOREAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ko-KR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-CN"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-Hans-CN"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-Hans-SG"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-TW"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-Hant-TW"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-Hant-HK"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-HK"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("zh-MO"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.INDONESIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("id-ID"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.INDONESIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("in-ID"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.MALAY, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ms-MY"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.VIETNAMESE, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("vi-VN"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.THAI, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("th-TH"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.FILIPINO, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("fil-PH"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.FILIPINO, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("tl-PH"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.ARABIC, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("ar-SA"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.HEBREW, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("he-IL"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.HEBREW, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("iw-IL"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.PERSIAN, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("fa-IR"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.SWEDISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("sv-SE"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.DANISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("da-DK"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.NORWEGIAN_BOKMAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("nb-NO"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.NORWEGIAN_BOKMAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("no-NO"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.NORWEGIAN_BOKMAL, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("nn-NO"))
        assertEquals(com.biplov.kaihatch.model.AppLanguage.FINNISH, com.biplov.kaihatch.model.AppLanguage.fromSavedValue("fi-FI"))

        // Unsupported languages return null from matchSupportedLanguage
        org.junit.Assert.assertNull(com.biplov.kaihatch.model.AppLanguage.matchSupportedLanguage("hi-IN"))
        org.junit.Assert.assertNull(com.biplov.kaihatch.model.AppLanguage.matchSupportedLanguage("sw-KE"))
        org.junit.Assert.assertNull(com.biplov.kaihatch.model.AppLanguage.matchSupportedLanguage("bn-BD"))
        org.junit.Assert.assertNull(com.biplov.kaihatch.model.AppLanguage.matchSupportedLanguage(""))
        org.junit.Assert.assertNull(com.biplov.kaihatch.model.AppLanguage.matchSupportedLanguage(null))

        // Picker list visibility when device language is supported vs unsupported
        assertEquals(33, com.biplov.kaihatch.model.AppLanguage.filterByQuery("", includeSystemDefault = true).size)
        assertEquals(32, com.biplov.kaihatch.model.AppLanguage.filterByQuery("", includeSystemDefault = false).size)
        assertEquals(
            false,
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("", includeSystemDefault = false)
                .contains(com.biplov.kaihatch.model.AppLanguage.SYSTEM)
        )
        assertTrue(
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("system", includeSystemDefault = false).isEmpty()
        )

        // Default language resolution & manual selection preservation
        // 1. Fresh install with supported device language -> SYSTEM
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.SYSTEM,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = false,
                savedPreferenceRaw = null,
                systemLanguageTag = "es-MX"
            )
        )
        // 2. Fresh install with unsupported device language -> ENGLISH
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.ENGLISH,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = false,
                savedPreferenceRaw = null,
                systemLanguageTag = "hi-IN"
            )
        )
        // 3. Device language later becomes supported (without manual override) -> SYSTEM available & active
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.SYSTEM,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = false,
                savedPreferenceRaw = null,
                systemLanguageTag = "ja-JP"
            )
        )
        // 4. Existing user's manually selected language is NEVER overridden (whether device is supported or unsupported)
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.SPANISH,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = true,
                savedPreferenceRaw = "SPANISH",
                systemLanguageTag = "hi-IN"
            )
        )
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.SPANISH,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = true,
                savedPreferenceRaw = "SPANISH",
                systemLanguageTag = "de-DE"
            )
        )
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.ENGLISH,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = true,
                savedPreferenceRaw = "ENGLISH",
                systemLanguageTag = "fr-FR"
            )
        )
        // 5. User who selected SYSTEM falls back to ENGLISH if device becomes unsupported, and returns to SYSTEM when supported
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.ENGLISH,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = true,
                savedPreferenceRaw = "SYSTEM",
                systemLanguageTag = "hi-IN"
            )
        )
        assertEquals(
            com.biplov.kaihatch.model.AppLanguage.SYSTEM,
            com.biplov.kaihatch.data.LanguageHelper.resolveEffectiveLanguageForState(
                hasUserSavedPreference = true,
                savedPreferenceRaw = "SYSTEM",
                systemLanguageTag = "pt-PT"
            )
        )

        // LanguageHelper testing override hook verification
        try {
            com.biplov.kaihatch.data.LanguageHelper.systemLanguageTagOverrideForTesting = "hi-IN"
            assertEquals(false, com.biplov.kaihatch.data.LanguageHelper.isDeviceLanguageSupported())
            com.biplov.kaihatch.data.LanguageHelper.systemLanguageTagOverrideForTesting = "zh-Hant-HK"
            assertTrue(com.biplov.kaihatch.data.LanguageHelper.isDeviceLanguageSupported())
            assertEquals(
                com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL,
                com.biplov.kaihatch.data.LanguageHelper.matchDeviceSystemLanguage()
            )
        } finally {
            com.biplov.kaihatch.data.LanguageHelper.systemLanguageTagOverrideForTesting = null
        }

        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.ENGLISH),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("english")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.SPANISH),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("español")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.JAPANESE),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("日本語")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.KOREAN),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("한국어")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.CHINESE_SIMPLIFIED),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("简体中文")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.CHINESE_TRADITIONAL),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("繁體中文")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.ARABIC),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("العربية")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.HEBREW),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("עברית")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.PERSIAN),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("فارسی")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.NORWEGIAN_BOKMAL),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("Norsk bokmål")
        )
        assertEquals(
            listOf(com.biplov.kaihatch.model.AppLanguage.SYSTEM),
            com.biplov.kaihatch.model.AppLanguage.filterByQuery("system")
        )
        assertTrue(com.biplov.kaihatch.model.AppLanguage.filterByQuery("nonexistent_lang").isEmpty())

        assertEquals("Oceanic Sky", com.biplov.kaihatch.model.AppAccentPalette.OCEAN.label)
        assertEquals("Signature KaiHatch", com.biplov.kaihatch.model.AppAccentPalette.OCEAN.subtitle)
        assertEquals("Fresh mint", com.biplov.kaihatch.model.AppAccentPalette.EMERALD.subtitle)
    }

    @Test
    fun testAllLocaleStringsCompleteAndPlaceholdersMatch() {
        val resRoot = listOf(
            File("src/main/res"),
            File("app/src/main/res")
        ).firstOrNull { it.exists() }
        assertNotNull("Could not locate src/main/res directory", resRoot)

        fun parseStringMap(file: File): Map<String, String> {
            assertTrue("Missing file: ${file.path}", file.exists())
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
            val nodes = doc.getElementsByTagName("string")
            val result = linkedMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val elem = nodes.item(i) as org.w3c.dom.Element
                val key = elem.getAttribute("name")
                val value = elem.textContent
                result[key] = value
            }
            return result
        }

        val placeholderRegex = Regex("""%(\d+\$[sd]|%)""")
        fun extractPlaceholders(text: String): Set<String> =
            placeholderRegex.findAll(text).map { it.value }.toSet()

        val baseMap = parseStringMap(File(resRoot!!, "values/strings.xml"))
        assertTrue("Expected >380 strings in base strings.xml", baseMap.size > 380)

        val locales = listOf(
            "values-es",
            "values-fr",
            "values-de",
            "values-it",
            "values-pt-rBR",
            "values-pt-rPT",
            "values-nl",
            "values-ru",
            "values-uk",
            "values-pl",
            "values-cs",
            "values-ro",
            "values-hu",
            "values-el",
            "values-tr",
            "values-ja",
            "values-ko",
            "values-zh-rCN",
            "values-zh-rTW",
            "values-in",
            "values-ms",
            "values-vi",
            "values-th",
            "values-b+fil",
            "values-tl",
            "values-ar",
            "values-iw",
            "values-fa",
            "values-sv",
            "values-da",
            "values-nb",
            "values-fi"
        )
        for (localeDir in locales) {
            val localeMap = parseStringMap(File(resRoot, "$localeDir/strings.xml"))
            val missingKeys = baseMap.keys - localeMap.keys
            val extraKeys = localeMap.keys - baseMap.keys
            assertTrue("Missing keys in $localeDir: $missingKeys", missingKeys.isEmpty())
            assertTrue("Extra keys in $localeDir: $extraKeys", extraKeys.isEmpty())

            for ((key, baseVal) in baseMap) {
                val locVal = localeMap.getValue(key)
                assertTrue("Empty translation for $key in $localeDir", locVal.isNotBlank())
                assertEquals(
                    "Placeholder mismatch for key '$key' in $localeDir",
                    extractPlaceholders(baseVal),
                    extractPlaceholders(locVal)
                )
            }
        }
    }

    @Test
    fun `test extracted directory tree hierarchy sorting lazy expansion duplicate filenames and 10k files`() {
        val rootDir = File(System.getProperty("java.io.tmpdir"), "kaihatch_tree_test_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // Create mixed root files and subfolders (including an empty subfolder and duplicate filenames across directories)
            File(rootDir, "zebra.txt").writeText("Zebra root file")
            File(rootDir, "alpha.txt").writeText("Alpha root file")

            val docsDir = File(rootDir, "docs").apply { mkdirs() }
            val assetsDir = File(rootDir, "Assets").apply { mkdirs() }
            val emptyDir = File(rootDir, "empty_folder").apply { mkdirs() }

            // Duplicate filenames in different directories
            File(docsDir, "readme.md").writeText("Docs readme content")
            File(assetsDir, "readme.md").writeText("Assets readme content")
            val nestedDeepDir = File(docsDir, "guides").apply { mkdirs() }
            File(nestedDeepDir, "setup.txt").writeText("Deep setup guide")

            // 1. Direct children of rootDir must contain ONLY level-1 entries, sorted folders first then files alphabetically
            val rootChildren = com.biplov.kaihatch.data.StorageHelper.listLocalDirectoryDirectChildren(rootDir)
            assertEquals(5, rootChildren.size)
            assertEquals(listOf("Assets", "docs", "empty_folder", "alpha.txt", "zebra.txt"), rootChildren.map { it.name })
            assertEquals(listOf(true, true, true, false, false), rootChildren.map { it.isDirectory })

            val rootFolderEntry = com.biplov.kaihatch.data.ExtractedFolderEntry(
                id = rootDir.absolutePath,
                name = rootDir.name,
                isSaf = false,
                localFile = rootDir,
                totalSizeBytes = 100L,
                children = rootChildren,
                totalFileCount = 5
            )

            // 2. Immediately after extraction, expandedSubfolderIds is empty -> only root children are visible (nested folders collapsed)
            val initialNodes = com.biplov.kaihatch.ui.screens.buildVisibleExtractedTreeNodes(
                rootFolder = rootFolderEntry,
                expandedSubfolderIds = emptySet(),
                subfolderCache = emptyMap()
            )
            assertEquals(5, initialNodes.size)
            assertTrue(initialNodes.all { it.depth == 0 })

            // 3. Expand "docs" and "Assets" independently and verify duplicate filenames ("readme.md") have unique keys & IDs
            val docsEntry = rootChildren.first { it.name == "docs" }
            val assetsEntry = rootChildren.first { it.name == "Assets" }
            val emptyFolderEntry = rootChildren.first { it.name == "empty_folder" }

            val docsChildren = com.biplov.kaihatch.data.StorageHelper.listLocalDirectoryDirectChildren(
                dir = docsDir,
                parentRelativePath = docsEntry.relativePath
            )
            // Inside docs: "guides" (folder) first, then "readme.md" (file)
            assertEquals(listOf("guides", "readme.md"), docsChildren.map { it.name })
            assertEquals(listOf(true, false), docsChildren.map { it.isDirectory })

            val assetsChildren = com.biplov.kaihatch.data.StorageHelper.listLocalDirectoryDirectChildren(
                dir = assetsDir,
                parentRelativePath = assetsEntry.relativePath
            )
            val emptyChildren = com.biplov.kaihatch.data.StorageHelper.listLocalDirectoryDirectChildren(
                dir = emptyDir,
                parentRelativePath = emptyFolderEntry.relativePath
            )
            assertTrue(emptyChildren.isEmpty())

            val cache = mapOf(
                docsEntry.id to docsChildren,
                assetsEntry.id to assetsChildren,
                emptyFolderEntry.id to emptyChildren
            )

            val expandedNodes = com.biplov.kaihatch.ui.screens.buildVisibleExtractedTreeNodes(
                rootFolder = rootFolderEntry,
                expandedSubfolderIds = setOf(docsEntry.id, assetsEntry.id, emptyFolderEntry.id),
                subfolderCache = cache
            )

            // Verify all uniqueKeys are distinct even with two "readme.md" files in different folders
            val uniqueKeys = expandedNodes.map { it.uniqueKey }
            assertEquals(uniqueKeys.size, uniqueKeys.toSet().size)

            val readmeNodes = expandedNodes.filter { it.entry?.name == "readme.md" }
            assertEquals(2, readmeNodes.size)
            assertTrue(readmeNodes[0].entry!!.id != readmeNodes[1].entry!!.id)
            assertTrue(readmeNodes[0].uniqueKey != readmeNodes[1].uniqueKey)
            assertTrue(readmeNodes.all { it.depth == 1 })

            // Verify empty_folder produces an indented EMPTY_SUBFOLDER_NOTICE node at depth 1
            val emptyNoticeNode = expandedNodes.firstOrNull {
                it.nodeType == com.biplov.kaihatch.ui.screens.TreeNodeType.EMPTY_SUBFOLDER_NOTICE
            }
            assertNotNull(emptyNoticeNode)
            assertEquals(1, emptyNoticeNode!!.depth)
            assertEquals(emptyFolderEntry.id, emptyNoticeNode.parentSubfolderId)

            // Verify "guides" nested inside "docs" is still collapsed (so "setup.txt" is not yet in visible nodes)
            assertTrue(expandedNodes.none { it.entry?.name == "setup.txt" })

            // 4. Verify 10,000+ files across nested directories build & sort smoothly
            val largeSubfolderEntries = (1..10_500).map { idx ->
                com.biplov.kaihatch.data.ExtractedFileEntry(
                    id = "${rootDir.absolutePath}/large_batch/file_$idx.dat",
                    name = "file_%05d.dat".format(idx),
                    sizeBytes = 64L,
                    mimeType = "application/octet-stream",
                    isDirectory = false,
                    relativePath = "large_batch/file_%05d.dat".format(idx),
                    parentId = "${rootDir.absolutePath}/large_batch"
                )
            }
            val largeFolder = com.biplov.kaihatch.data.ExtractedFolderEntry(
                id = "${rootDir.absolutePath}/large_batch",
                name = "large_batch_extracted",
                isSaf = false,
                totalSizeBytes = 10_500L * 64L,
                children = largeSubfolderEntries,
                totalFileCount = 10_500
            )
            val largeVisible = com.biplov.kaihatch.ui.screens.buildVisibleExtractedTreeNodes(
                rootFolder = largeFolder,
                expandedSubfolderIds = emptySet(),
                subfolderCache = emptyMap()
            )
            assertEquals(10_500, largeVisible.size)
            assertEquals("file_00001.dat", largeVisible.first().entry?.name)
            assertEquals("file_10500.dat", largeVisible.last().entry?.name)

            // 5. Verify collapsing a 10,500-file folder bounds closing preview nodes to visible viewport max (16)
            // and marks them as isClosing = true so reverse slide/fade animations never compose large subtrees
            val closingLargeVisible = com.biplov.kaihatch.ui.screens.buildVisibleExtractedTreeNodes(
                rootFolder = largeFolder,
                expandedSubfolderIds = emptySet(),
                subfolderCache = emptyMap(),
                isRootClosing = true
            )
            assertEquals(16, closingLargeVisible.size)
            assertTrue(closingLargeVisible.all { it.isClosing })

            // 6. Verify collapsing a subfolder marks its children as isClosing = true while keeping sibling folders unaffected
            val subfolderClosingNodes = com.biplov.kaihatch.ui.screens.buildVisibleExtractedTreeNodes(
                rootFolder = rootFolderEntry,
                expandedSubfolderIds = setOf(assetsEntry.id),
                subfolderCache = cache,
                closingSubfolderIds = setOf(docsEntry.id)
            )
            val docsChildClosingNodes = subfolderClosingNodes.filter { it.parentFolderId == docsEntry.id }
            assertEquals(2, docsChildClosingNodes.size)
            assertTrue(docsChildClosingNodes.all { it.isClosing })
            val assetsChildOpenNodes = subfolderClosingNodes.filter { it.parentFolderId == assetsEntry.id }
            assertEquals(1, assetsChildOpenNodes.size)
            assertTrue(assetsChildOpenNodes.none { it.isClosing })
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
