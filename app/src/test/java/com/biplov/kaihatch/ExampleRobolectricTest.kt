package com.biplov.kaihatch

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.engine.KaiHatchEngine
import com.biplov.kaihatch.engine.SpaceLabEngine
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.AppAccentPalette
import com.biplov.kaihatch.model.AppThemeMode
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ZipEntryInfo
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.ScreenTab
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("KaiHatch", appName)
    }

    @Test
    fun `test settings accent palette and theme mode persistence and archive previewer`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HatchViewModel(app)

        // 1. Test Settings navigation & activePageTab preservation for HorizontalPager
        assertEquals(ScreenTab.EXTRACT, viewModel.currentTab.value)
        assertEquals(ScreenTab.EXTRACT, viewModel.activePageTab.value)
        viewModel.switchTab(ScreenTab.SPACE_LAB)
        assertEquals(ScreenTab.SPACE_LAB, viewModel.currentTab.value)
        assertEquals(ScreenTab.SPACE_LAB, viewModel.activePageTab.value)
        viewModel.openSettings()
        assertEquals(ScreenTab.SETTINGS, viewModel.currentTab.value)
        assertEquals(ScreenTab.SPACE_LAB, viewModel.activePageTab.value)
        viewModel.closeSettings()
        assertEquals(ScreenTab.SPACE_LAB, viewModel.currentTab.value)
        assertEquals(ScreenTab.SPACE_LAB, viewModel.activePageTab.value)
        viewModel.switchTab(ScreenTab.EXTRACT)

        // 1b. Test Progressive Blur & Swipe Tabs preferences
        assertTrue(viewModel.isBlurEnabled.value)
        assertTrue(viewModel.isSwipeTabsEnabled.value)
        viewModel.setBlurEnabled(false)
        assertEquals(false, viewModel.isBlurEnabled.value)
        viewModel.setBlurEnabled(true)
        assertEquals(true, viewModel.isBlurEnabled.value)

        // 2. Test Unified Accent Color & Theme Mode selection
        viewModel.setAccentPalette(AppAccentPalette.VIOLET)
        assertEquals(AppAccentPalette.VIOLET, viewModel.accentPalette.value)

        viewModel.setThemeMode(AppThemeMode.LIGHT)
        assertEquals(AppThemeMode.LIGHT, viewModel.themeMode.value)

        // 3. Test FileChannel-based Archive Previewer entry preview
        val zipFile = File(app.cacheDir, "preview_channel_test.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("hello_kaihatch.txt"))
            zos.write("Archive Previewer FileChannel test content!".toByteArray())
            zos.closeEntry()
        }

        val entries = ZipParser.parseArchive(zipFile)
        assertEquals(1, entries.size)

        val preview = StorageHelper.readArchiveEntryPreview(
            context = app,
            archiveFile = zipFile,
            safUri = null,
            entry = entries.first()
        )
        assertTrue(!preview.isBinary)
        assertTrue(preview.contentPreview.contains("Archive Previewer FileChannel test content!"))

        val extractedSingle = StorageHelper.extractSingleArchiveEntry(
            context = app,
            archiveFile = zipFile,
            safUri = null,
            entry = entries.first(),
            destDir = app.cacheDir
        )
        assertNotNull(extractedSingle)
        assertEquals("Archive Previewer FileChannel test content!", extractedSingle!!.readText())
    }

    @Test
    fun `test deselect archive and persistent folder permission grant and remove`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HatchViewModel(app)

        val zipFile = File(app.cacheDir, "deselect_test.apk")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dex-payload".toByteArray())
            zos.closeEntry()
        }

        // Select APK archive and verify it is selected
        viewModel.selectArchive(zipFile)
        assertEquals(zipFile, viewModel.selectedArchiveFile.value)

        // Deselect archive and verify all selection state is cleared
        viewModel.deselectArchive()
        assertNull(viewModel.selectedArchiveFile.value)
        assertNull(viewModel.selectedSafUri.value)
        assertNull(viewModel.archiveSummary.value)

        // Test Persistent Folder Access grant, display info, and removal
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FArchives")
        viewModel.onFolderPermissionGranted(treeUri)
        val granted = viewModel.grantedFolder.value
        assertNotNull(granted)
        assertEquals("Archives", granted!!.displayName)
        assertEquals("Internal Storage / Download / Archives", granted.readablePath)

        viewModel.removeGrantedFolder()
        assertNull(viewModel.grantedFolder.value)

        // Verify consistent display rounding so 698.70 MB - 384.06 MB displays 314.64 MB (never 314.65 MB)
        val stdBytes = (698.704 * 1024.0 * 1024.0).toLong()
        val hatchBytes = (384.056 * 1024.0 * 1024.0).toLong()
        assertEquals("698.70 MB", StorageHelper.formatBytes(stdBytes))
        assertEquals("384.06 MB", StorageHelper.formatBytes(hatchBytes))
        assertEquals("314.64 MB", StorageHelper.formatConsistentSavedBytes(stdBytes, hatchBytes))
    }

    @Test
    fun `test 2_5GB APK stored sub-entry chunk truncation telemetry and 7z archive support`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // 1. Simulate a 2.5GB APK where 2.467GB is a single uncompressed STORED asset/OBB entry
        // and 33MB is classes.dex + resources.arsc
        val apkSize = 2_500_000_000L
        val dexSize = 33_000_000L
        val bigStoredAssetSize = 2_467_000_000L

        val simulatedApkEntries = listOf(
            ZipEntryInfo(
                name = "classes.dex",
                path = "classes.dex",
                compressedSize = dexSize,
                uncompressedSize = dexSize,
                localHeaderOffset = 0L,
                compressionMethod = 8, // DEFLATED
                isDirectory = false,
                crc32 = 123L
            ),
            ZipEntryInfo(
                name = "main.obb",
                path = "assets/main.obb",
                compressedSize = bigStoredAssetSize,
                uncompressedSize = bigStoredAssetSize,
                localHeaderOffset = dexSize,
                compressionMethod = 0, // STORED
                isDirectory = false,
                crc32 = 456L
            )
        )

        val standardPeak = apkSize + dexSize + bigStoredAssetSize // 5.0 GB
        val hatchPeak = SpaceLabEngine.computeHatchTimelinePeakBytes(
            fileSizeBytes = apkSize,
            uncompressedSizeBytes = dexSize + bigStoredAssetSize,
            entries = simulatedApkEntries,
            format = ArchiveFormat.APK
        )
        val savedBytes = standardPeak - hatchPeak
        // With 16MB sub-entry reverse chunk truncation on STORED entries, Hatch saves >2.4GB instead of only 33MB!
        assertTrue("Expected >2.4GB saved for STORED APK, but got $savedBytes", savedBytes > 2_400_000_000L)

        // 1b. Simulate a 2.5GB APK where the 2.467GB entry is DEFLATED (compressionMethod = 8)
        // after a 33MB classes.dex entry (previously reported only 33MB less than standard extraction)
        val deflatedApkEntries = listOf(
            simulatedApkEntries[0],
            simulatedApkEntries[1].copy(compressionMethod = 8) // DEFLATED
        )
        val deflatedHatchPeak = SpaceLabEngine.computeHatchTimelinePeakBytes(
            fileSizeBytes = apkSize,
            uncompressedSizeBytes = dexSize + bigStoredAssetSize,
            entries = deflatedApkEntries,
            format = ArchiveFormat.APK
        )
        val deflatedSavedBytes = standardPeak - deflatedHatchPeak
        assertTrue("Expected >2.4GB saved for DEFLATED 2.5GB APK, but got $deflatedSavedBytes", deflatedSavedBytes > 2_400_000_000L)

        // 1c. Verify CommonsCompressBufferStrategy adaptive buffers and BufferedSeekableByteChannel
        val optimalBuf = com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.borrowIoBuffer(apkSize)
        assertTrue(optimalBuf.size >= com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.MIN_BUFFER_BYTES)
        val sampleApk = File(app.cacheDir, "commons_compress_buffered.apk")
        ZipOutputStream(FileOutputStream(sampleApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest package=\"com.biplov.kaihatch\"/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libengine.so"))
            zos.write(ByteArray(32 * 1024) { (it % 251).toByte() })
            zos.closeEntry()
        }
        java.io.RandomAccessFile(sampleApk, "r").use { raf ->
            val ccEntries = com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.parseZipWithCommonsCompress(
                raf.channel,
                sampleApk.name
            )
            assertEquals(2, ccEntries.size)
            assertEquals(32 * 1024L, ccEntries.last().uncompressedSize)
        }

        // 2. Test .7z recognition, parsing, and extraction
        val sevenZFile = File(app.cacheDir, "archive_bundle.7z")
        SevenZOutputFile(sevenZFile).use { sevenZOut ->
            sevenZOut.setContentMethods(listOf(org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(SevenZMethod.LZMA2)))
            val payloadFile = File(app.cacheDir, "payload.txt")
            payloadFile.writeText("Hello from 7-Zip LZMA2 archive inside KaiHatch!")
            val entry = sevenZOut.createArchiveEntry(payloadFile, "folder/payload.txt")
            sevenZOut.putArchiveEntry(entry as org.apache.commons.compress.archivers.ArchiveEntry)
            sevenZOut.write(payloadFile.readBytes())
            sevenZOut.closeArchiveEntry()
        }

        assertEquals(ArchiveFormat.SEVEN_Z, ZipParser.detectFormat(sevenZFile.name))
        val parsed7zEntries = ZipParser.parseArchive(sevenZFile)
        assertEquals(1, parsed7zEntries.size)
        assertEquals("payload.txt", parsed7zEntries.first().name)

        val outDir = File(app.cacheDir, "extracted_7z_out").apply { deleteRecursively(); mkdirs() }
        val finalProgress = KaiHatchEngine.extractArchive(
            archiveFile = sevenZFile,
            targetDir = outDir,
            isHatchMode = false,
            context = app
        ).last()
        assertEquals(ExtractionStatus.COMPLETED, finalProgress.status)
        val extractedPayload = File(outDir, "folder/payload.txt")
        assertTrue(extractedPayload.exists())
        assertEquals("Hello from 7-Zip LZMA2 archive inside KaiHatch!", extractedPayload.readText())

        // 3. Test openDirectSevenZFile and NonClosingSeekableByteChannel directly
        java.io.RandomAccessFile(sevenZFile, "r").use { raf ->
            val direct7z = com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.openDirectSevenZFile(
                raf.channel,
                sevenZFile.name
            )
            direct7z.use { d7z ->
                val meta = d7z.entries.toList()
                assertEquals(1, meta.size)
                val directEntry = d7z.nextEntry
                assertNotNull(directEntry)
                val readBuf = ByteArray(128)
                val readBytes = d7z.read(readBuf, 0, readBuf.size)
                assertTrue(readBytes > 0)
                val text = String(readBuf, 0, readBytes)
                assertEquals("Hello from 7-Zip LZMA2 archive inside KaiHatch!", text)
            }
        }

        // 4. Test detectSevenZHeaderEncryption
        java.io.RandomAccessFile(sevenZFile, "r").use { raf ->
            val isEncrypted = ZipParser.detectSevenZHeaderEncryption(raf.channel)
            assertEquals(false, isEncrypted)
        }

        // 5. Test ExtractionProgress error diagnostic journal logs
        val errProgress = com.biplov.kaihatch.model.ExtractionProgress(
            archiveName = "corrupt.7z",
            status = ExtractionStatus.ERROR,
            errorMessage = "7z extraction failed: Compressed data is corrupt",
            journalLogs = listOf(
                "[12:00:00] KAIHATCH 7Z ENGINE: 64-Bit Zero-Copy LZMA2 Stream (5.62 GB)",
                "[12:00:01] 7Z ARCHIVE OPENED: 1 entries",
                "[12:00:02] FATAL 7Z ERROR: [CorruptedInputException] Compressed data is corrupt"
            )
        )
        assertEquals(3, errProgress.journalLogs.size)
        assertTrue(errProgress.journalLogs.last().contains("CorruptedInputException"))
    }

    @Test
    fun `test in place extraction live shrinking`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val zipFile = File(app.cacheDir, "live_shrink_test.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("file1.txt"))
            zos.write(ByteArray(100 * 1024) { 1 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("file2.txt"))
            zos.write(ByteArray(200 * 1024) { 2 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("file3.txt"))
            zos.write(ByteArray(300 * 1024) { 3 })
            zos.closeEntry()
        }
        val origLen = zipFile.length()
        val outDir = File(app.cacheDir, "live_shrink_out").apply { deleteRecursively(); mkdirs() }
        val updates = mutableListOf<com.biplov.kaihatch.model.ExtractionProgress>()
        KaiHatchEngine.extractArchive(
            archiveFile = zipFile,
            targetDir = outDir,
            isHatchMode = true,
            context = app
        ).collect {
            updates.add(it)
        }
        assertEquals(ExtractionStatus.COMPLETED, updates.last().status)
        assertEquals(origLen, updates.last().bytesReclaimed)
        assertEquals(0L, updates.last().currentArchiveBytesOnDisk)

        // Verify empty 0-byte extracted folders are automatically purged
        val emptyGhostDir = File(StorageHelper.getExtractedDir(app), "zzz_extracted").apply { mkdirs() }
        assertTrue(emptyGhostDir.exists())
        val listedFolders = StorageHelper.listLocalExtractedFolders(app)
        assertTrue(listedFolders.none { it.name == "zzz_extracted" })
        assertTrue(!emptyGhostDir.exists())
    }

    @Test
    fun `test multi threaded parallel compression and extraction in standard and hatch modes`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val srcDir = File(app.cacheDir, "mt_src_files").apply { deleteRecursively(); mkdirs() }

        // Create 20 small/medium files + 1 large 3.2MB file (>2MB triggers parallel 1MB SYNC_FLUSH chunked deflate)
        val inputFiles = mutableListOf<File>()
        for (i in 1..20) {
            val f = File(srcDir, "small_item_$i.txt")
            f.writeText("Parallel small entry #$i -> ".repeat(400))
            inputFiles.add(f)
        }
        val largeBytes = ByteArray(3 * 1024 * 1024 + 256 * 1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val largeFile = File(srcDir, "large_chunked_payload.bin").apply { writeBytes(largeBytes) }
        inputFiles.add(largeFile)

        val compressedZip = File(app.cacheDir, "mt_parallel_archive.zip").apply { delete() }
        val compressResult = com.biplov.kaihatch.engine.HatchCompressEngine.compressFiles(
            itemsToCompress = inputFiles,
            targetArchiveFile = compressedZip,
            settings = com.biplov.kaihatch.model.CompressionSettings(
                archiveName = "mt_parallel_archive",
                format = ArchiveFormat.ZIP,
                compressionLevel = 6
            )
        ).last()

        assertEquals(com.biplov.kaihatch.model.CompressionStatus.COMPLETED, compressResult.status)
        assertTrue(compressedZip.exists() && compressedZip.length() > 0L)

        // 1. Test Multi-Threaded Standard Extraction
        val stdOutDir = File(app.cacheDir, "mt_std_out").apply { deleteRecursively(); mkdirs() }
        val stdResult = KaiHatchEngine.extractArchive(
            archiveFile = compressedZip,
            targetDir = stdOutDir,
            isHatchMode = false,
            context = app
        ).last()
        assertEquals(ExtractionStatus.COMPLETED, stdResult.status)
        assertEquals(21, stdResult.completedEntries)
        val extractedLargeStd = File(stdOutDir, "large_chunked_payload.bin")
        assertTrue(extractedLargeStd.exists())
        assertTrue(largeBytes.contentEquals(extractedLargeStd.readBytes()))

        // 2. Test Multi-Threaded KaiHatch In-Place Windowed Extraction
        val hatchOutDir = File(app.cacheDir, "mt_hatch_out").apply { deleteRecursively(); mkdirs() }
        val origZipSize = compressedZip.length()
        val hatchResult = KaiHatchEngine.extractArchive(
            archiveFile = compressedZip,
            targetDir = hatchOutDir,
            isHatchMode = true,
            context = app
        ).last()
        assertEquals(ExtractionStatus.COMPLETED, hatchResult.status)
        assertEquals(origZipSize, hatchResult.bytesReclaimed)
        assertEquals(0L, hatchResult.currentArchiveBytesOnDisk)
        assertTrue(!compressedZip.exists())
        val extractedLargeHatch = File(hatchOutDir, "large_chunked_payload.bin")
        assertTrue(extractedLargeHatch.exists())
        assertTrue(largeBytes.contentEquals(extractedLargeHatch.readBytes()))
        for (i in 1..20) {
            val f = File(hatchOutDir, "small_item_$i.txt")
            assertTrue(f.exists())
            assertEquals("Parallel small entry #$i -> ".repeat(400), f.readText())
        }
    }

    @Test
    fun `test 9 file mixed archive peak telemetry and live progressive ftruncate before final file`() = runBlocking {
        // 1. Verify SpaceLabEngine peak calculation for a 328.4 MB archive with 9 files (5 small screenshots + 4 large videos)
        val mb = 1024L * 1024L
        val totalArchiveBytes = 328L * mb + 400L * 1024L
        val entries9 = listOf(
            com.biplov.kaihatch.model.ZipEntryInfo("shot1.png", "shot1.png", false, 500 * 1024L, 500 * 1024L, 1L, 0, 0L),
            com.biplov.kaihatch.model.ZipEntryInfo("shot2.png", "shot2.png", false, 500 * 1024L, 500 * 1024L, 2L, 0, 500 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("shot3.png", "shot3.png", false, 500 * 1024L, 500 * 1024L, 3L, 0, 1000 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("shot4.png", "shot4.png", false, 500 * 1024L, 500 * 1024L, 4L, 0, 1500 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("shot5.png", "shot5.png", false, 400 * 1024L, 400 * 1024L, 5L, 0, 2000 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("vid1.mp4", "vid1.mp4", false, 81 * mb, 81 * mb, 6L, 8, 2400 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("vid2.mp4", "vid2.mp4", false, 82 * mb, 82 * mb, 7L, 8, 83 * mb + 400 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("vid3.mp4", "vid3.mp4", false, 81 * mb, 81 * mb, 8L, 8, 165 * mb + 400 * 1024L),
            com.biplov.kaihatch.model.ZipEntryInfo("vid4.mp4", "vid4.mp4", false, 82 * mb, 82 * mb, 9L, 8, 246 * mb + 400 * 1024L)
        )
        val totalUncomp = entries9.sumOf { it.uncompressedSize }
        val peakZip = SpaceLabEngine.computeHatchTimelinePeakBytes(
            fileSizeBytes = totalArchiveBytes,
            uncompressedSizeBytes = totalUncomp,
            entries = entries9,
            format = ArchiveFormat.ZIP
        )
        // Peak must be ~344.4 MB (archive + 16MB active window), never inflated to 500+ MB
        assertTrue("Expected peak <= 350MB but got ${peakZip / mb}MB", peakZip <= 350L * mb)

        // 2. Verify live progressive ftruncate during 9-file extraction (both STORED .mp4 and DEFLATED .bin >= 4MB)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src9Dir = File(app.cacheDir, "nine_files_src").apply { deleteRecursively(); mkdirs() }
        val filesToPack = mutableListOf<File>()
        for (i in 1..6) {
            val f = File(src9Dir, "screen_$i.png")
            f.writeBytes(ByteArray(64 * 1024) { idx -> (idx + i).toByte() })
            filesToPack.add(f)
        }
        val storedVideoBytes = ByteArray(4 * 1024 * 1024 + 512 * 1024) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val storedVideoFile = File(src9Dir, "clip_7.mp4").apply { writeBytes(storedVideoBytes) }
        filesToPack.add(storedVideoFile)

        // Incompressible pseudo-random payload > 4MB compressed with DEFLATED (.bin) to test symmetric tail-swap inflate ftruncate
        val rng = java.util.Random(42L)
        val deflatedLargeBytes = ByteArray(4 * 1024 * 1024 + 512 * 1024).also { rng.nextBytes(it) }
        val deflatedLargeFile1 = File(src9Dir, "recording_8.bin").apply { writeBytes(deflatedLargeBytes) }
        val deflatedLargeFile2 = File(src9Dir, "recording_9.bin").apply { writeBytes(deflatedLargeBytes) }
        filesToPack.add(deflatedLargeFile1)
        filesToPack.add(deflatedLargeFile2)

        val nineZip = File(app.cacheDir, "zzz_9files.zip").apply { delete() }
        val packRes = com.biplov.kaihatch.engine.HatchCompressEngine.compressFiles(
            itemsToCompress = filesToPack,
            targetArchiveFile = nineZip,
            settings = com.biplov.kaihatch.model.CompressionSettings(
                archiveName = "zzz_9files",
                format = ArchiveFormat.ZIP,
                compressionLevel = 6
            )
        ).last()
        assertEquals(com.biplov.kaihatch.model.CompressionStatus.COMPLETED, packRes.status)
        val initialZipLen = nineZip.length()
        assertTrue(initialZipLen > 12L * 1024L * 1024L)

        val nineOutDir = File(app.cacheDir, "nine_files_out").apply { deleteRecursively(); mkdirs() }
        val emissions = mutableListOf<com.biplov.kaihatch.model.ExtractionProgress>()
        KaiHatchEngine.extractArchive(
            archiveFile = nineZip,
            targetDir = nineOutDir,
            isHatchMode = true,
            context = app
        ).collect { emissions.add(it) }

        val finalState = emissions.last()
        assertEquals(ExtractionStatus.COMPLETED, finalState.status)
        assertEquals(0L, finalState.currentArchiveBytesOnDisk)
        assertEquals(initialZipLen, finalState.bytesReclaimed)

        // Verify progressive intermediate ftruncate occurred well before reaching 8/9 files!
        val midStatesBeforeFile8 = emissions.filter {
            it.status != ExtractionStatus.COMPLETED &&
                    it.completedEntries in 1..7 &&
                    it.currentArchiveBytesOnDisk in 1L until initialZipLen &&
                    it.bytesReclaimed > 0L
        }
        assertTrue("Expected live progressive ftruncate before file 8/9", midStatesBeforeFile8.isNotEmpty())

        // Verify byte-exact integrity of all 9 files including the tail-swapped DEFLATED entries
        assertTrue(storedVideoBytes.contentEquals(File(nineOutDir, "clip_7.mp4").readBytes()))
        assertTrue(deflatedLargeBytes.contentEquals(File(nineOutDir, "recording_8.bin").readBytes()))
        assertTrue(deflatedLargeBytes.contentEquals(File(nineOutDir, "recording_9.bin").readBytes()))
    }

    @Test
    fun `test mandatory persistent folder check on extract click and automatic start after folder selection`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HatchViewModel(app)

        // Ensure no persistent folder is selected initially
        viewModel.removeGrantedFolder()
        assertNull(viewModel.grantedFolder.value)
        assertEquals(false, viewModel.showPersistentFolderPrompt.value)

        // Create and select a test archive
        val testZip = File(app.cacheDir, "auto_proceed_test.zip")
        ZipOutputStream(FileOutputStream(testZip)).use { zos ->
            zos.putNextEntry(ZipEntry("doc1.txt"))
            zos.write("Persistent folder check payload #1".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("doc2.txt"))
            zos.write("Persistent folder check payload #2".toByteArray())
            zos.closeEntry()
        }
        viewModel.setHatchMode(false)
        viewModel.selectArchive(testZip)

        // Wait for archive summary to load
        var waitTicks = 0
        while (viewModel.archiveSummary.value == null && waitTicks < 40) {
            kotlinx.coroutines.delay(25)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            waitTicks++
        }
        assertNotNull(viewModel.archiveSummary.value)

        // 1. Click Extract when Persistent Folder is NOT selected:
        // Must NOT start extraction yet, and must prompt the user to select a persistent folder first
        viewModel.startExtraction()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(true, viewModel.showPersistentFolderPrompt.value)
        assertEquals(ExtractionStatus.IDLE, viewModel.extractionProgress.value.status)

        // 2. Simulate user selecting a persistent folder from the prompt:
        // Must save the folder, dismiss the prompt, and automatically start & complete extraction!
        val persistentDir = File(app.cacheDir, "UserSelectedFolder_K").apply { deleteRecursively(); mkdirs() }
        val treeUri = Uri.fromFile(persistentDir)
        viewModel.onFolderPermissionGranted(treeUri)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(false, viewModel.showPersistentFolderPrompt.value)
        assertNotNull(viewModel.grantedFolder.value)

        waitTicks = 0
        while (viewModel.extractionProgress.value.status != ExtractionStatus.COMPLETED && waitTicks < 60) {
            kotlinx.coroutines.delay(25)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            waitTicks++
        }
        assertEquals(ExtractionStatus.COMPLETED, viewModel.extractionProgress.value.status)
        assertEquals(2, viewModel.extractionProgress.value.completedEntries)

        // Wait briefly for refreshLocalFiles() to populate extractedFolders
        waitTicks = 0
        while (viewModel.extractedFolders.value.isEmpty() && waitTicks < 40) {
            kotlinx.coroutines.delay(25)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            waitTicks++
        }
        assertTrue(viewModel.extractedFolders.value.any { it.name == "auto_proceed_test_extracted" && it.children.size == 2 })

        // 3. When Persistent Folder IS already selected, clicking Extract starts immediately without prompting
        viewModel.selectArchive(testZip)
        waitTicks = 0
        while (viewModel.archiveSummary.value == null && waitTicks < 40) {
            kotlinx.coroutines.delay(25)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            waitTicks++
        }
        viewModel.startExtraction()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(false, viewModel.showPersistentFolderPrompt.value)
    }

    @Test
    fun `test first launch onboarding state migration and extracted file external open resolution`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settingsPrefs = app.getSharedPreferences("kaihatch_settings", Context.MODE_PRIVATE)
        val historyPrefs = app.getSharedPreferences("kaihatch_history", Context.MODE_PRIVATE)

        // 1. Fresh install state: no preferences or history exist -> onboarding must be shown
        settingsPrefs.edit().clear().commit()
        historyPrefs.edit().clear().commit()

        val freshVm = HatchViewModel(app)
        assertEquals(false, freshVm.hasCompletedOnboarding.value)

        // Selecting theme and destination during onboarding updates shared Settings state immediately
        freshVm.setThemeMode(AppThemeMode.LIGHT)
        assertEquals(AppThemeMode.LIGHT, freshVm.themeMode.value)
        assertEquals(AppThemeMode.LIGHT.name, settingsPrefs.getString("theme_mode", null))

        val destUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        freshVm.onFolderPermissionGranted(destUri)
        assertNotNull(freshVm.grantedFolder.value)
        assertEquals("Download", freshVm.grantedFolder.value?.displayName)

        // Completing or skipping onboarding persists completion across restarts
        freshVm.completeOnboarding()
        assertEquals(true, freshVm.hasCompletedOnboarding.value)
        assertEquals(true, settingsPrefs.getBoolean(HatchViewModel.KEY_ONBOARDING_COMPLETED, false))

        val restartedVm = HatchViewModel(app)
        assertEquals(true, restartedVm.hasCompletedOnboarding.value)
        assertEquals(AppThemeMode.LIGHT, restartedVm.themeMode.value)

        // 2. Existing user upgrade state: prior preferences exist without KEY_ONBOARDING_COMPLETED
        settingsPrefs.edit().clear()
            .putString("theme_mode", AppThemeMode.SYSTEM.name)
            .commit()
        val upgradedVm = HatchViewModel(app)
        assertEquals(true, upgradedVm.hasCompletedOnboarding.value)
        assertEquals(AppThemeMode.SYSTEM, upgradedVm.themeMode.value)

        // 3. Extracted-file Open URI & MIME resolution
        val extractedSample = File(app.cacheDir, "sample_clip.mp4").apply {
            writeBytes("mp4-test-payload".toByteArray())
        }
        val contentUri = StorageHelper.resolveOpenContentUri(
            context = app,
            file = extractedSample,
            uri = null
        )
        assertNotNull(contentUri)
        assertEquals("content", contentUri!!.scheme)

        // Even if a raw file:// URI is passed, resolveOpenContentUri converts it to a FileProvider content:// URI
        val convertedFromFileUri = StorageHelper.resolveOpenContentUri(
            context = app,
            file = null,
            uri = Uri.fromFile(extractedSample)
        )
        assertNotNull(convertedFromFileUri)
        assertEquals("content", convertedFromFileUri!!.scheme)

        // Missing file returns null instead of crashing or returning a dead URI
        val missingFile = File(app.cacheDir, "non_existent_extracted_file.mp4").apply { delete() }
        assertNull(StorageHelper.resolveOpenContentUri(app, missingFile, null))

        // Verify accurate MIME types and safe fallback for unknown extensions
        assertEquals("video/mp4", StorageHelper.resolveOpenIntentMimeType("clip.mp4"))
        assertEquals("video/x-matroska", StorageHelper.resolveOpenIntentMimeType("movie.mkv"))
        assertEquals("audio/wav", StorageHelper.resolveOpenIntentMimeType("sound.wav"))
        assertEquals("audio/flac", StorageHelper.resolveOpenIntentMimeType("track.flac"))
        assertEquals("application/pdf", StorageHelper.resolveOpenIntentMimeType("manual.pdf"))
        assertEquals("*/*", StorageHelper.resolveOpenIntentMimeType("README_NO_EXT"))
        assertEquals("*/*", StorageHelper.resolveOpenIntentMimeType("archive.unknownext123"))
    }

    @Test
    fun `test status bar and navigation bar icon appearance across Light Dark and System theme modes`() {
        val lightSurfaceBackground = androidx.compose.ui.graphics.Color(0xFFF1F3F7)
        val darkSurfaceBackground = androidx.compose.ui.graphics.Color(0xFF0A0F18)

        // 1. Light Mode: isDark = false, light background -> dark status bar icons (isAppearanceLightStatusBars = true)
        val isDarkInLightMode = com.biplov.kaihatch.ui.theme.resolveIsDarkTheme(AppThemeMode.LIGHT, systemInDarkTheme = true)
        assertEquals(false, isDarkInLightMode)
        assertEquals(true, com.biplov.kaihatch.ui.theme.shouldUseDarkSystemBarIcons(isDarkInLightMode, lightSurfaceBackground))

        // 2. Dark Mode: isDark = true, dark background -> light status bar icons (isAppearanceLightStatusBars = false)
        val isDarkInDarkMode = com.biplov.kaihatch.ui.theme.resolveIsDarkTheme(AppThemeMode.DARK, systemInDarkTheme = false)
        assertEquals(true, isDarkInDarkMode)
        assertEquals(false, com.biplov.kaihatch.ui.theme.shouldUseDarkSystemBarIcons(isDarkInDarkMode, darkSurfaceBackground))

        // 3. System Mode: follows currently active system theme
        val isDarkInSystemLight = com.biplov.kaihatch.ui.theme.resolveIsDarkTheme(AppThemeMode.SYSTEM, systemInDarkTheme = false)
        assertEquals(false, isDarkInSystemLight)
        assertEquals(true, com.biplov.kaihatch.ui.theme.shouldUseDarkSystemBarIcons(isDarkInSystemLight, lightSurfaceBackground))

        val isDarkInSystemDark = com.biplov.kaihatch.ui.theme.resolveIsDarkTheme(AppThemeMode.SYSTEM, systemInDarkTheme = true)
        assertEquals(true, isDarkInSystemDark)
        assertEquals(false, com.biplov.kaihatch.ui.theme.shouldUseDarkSystemBarIcons(isDarkInSystemDark, darkSurfaceBackground))

        // 4. Verify WindowInsetsControllerCompat updates immediately on an Activity window when switching modes
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val window = activity.window
            val decorView = window.decorView
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, decorView)

            // Switch to Light Mode -> dark status/nav bar icons
            com.biplov.kaihatch.ui.theme.applySystemBarAppearance(
                window = window,
                view = decorView,
                useDarkIcons = com.biplov.kaihatch.ui.theme.shouldUseDarkSystemBarIcons(false, lightSurfaceBackground)
            )
            assertEquals(true, insetsController.isAppearanceLightStatusBars)
            assertEquals(true, insetsController.isAppearanceLightNavigationBars)

            // Switch to Dark Mode -> light status/nav bar icons
            com.biplov.kaihatch.ui.theme.applySystemBarAppearance(
                window = window,
                view = decorView,
                useDarkIcons = com.biplov.kaihatch.ui.theme.shouldUseDarkSystemBarIcons(true, darkSurfaceBackground)
            )
            assertEquals(false, insetsController.isAppearanceLightStatusBars)
            assertEquals(false, insetsController.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun `test settings about card external links and verified open source licenses`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // 1. Verify exact destination URLs and email address
        assertEquals("https://github.com/biplov13b/KaiHatch", com.biplov.kaihatch.ui.screens.ABOUT_WEBSITE_URL)
        assertEquals("biplovbiswas13@gmail.com", com.biplov.kaihatch.ui.screens.ABOUT_EMAIL_ADDRESS)
        assertEquals("https://t.me/KaiHatch", com.biplov.kaihatch.ui.screens.ABOUT_TELEGRAM_URL)
        assertEquals("https://ko-fi.com/biplov13b", com.biplov.kaihatch.ui.screens.ABOUT_KOFI_URL)

        // 2. Verify Intent construction & dispatch for Website, Email, Telegram, and Ko-fi
        val websiteIntent = com.biplov.kaihatch.ui.screens.createExternalUrlIntent(com.biplov.kaihatch.ui.screens.ABOUT_WEBSITE_URL)
        assertEquals(android.content.Intent.ACTION_VIEW, websiteIntent.action)
        assertEquals(Uri.parse("https://github.com/biplov13b/KaiHatch"), websiteIntent.data)

        val emailIntent = com.biplov.kaihatch.ui.screens.createEmailContactIntent(com.biplov.kaihatch.ui.screens.ABOUT_EMAIL_ADDRESS)
        assertEquals(android.content.Intent.ACTION_SENDTO, emailIntent.action)
        assertEquals(Uri.parse("mailto:biplovbiswas13@gmail.com"), emailIntent.data)

        val telegramIntent = com.biplov.kaihatch.ui.screens.createExternalUrlIntent(com.biplov.kaihatch.ui.screens.ABOUT_TELEGRAM_URL)
        assertEquals(android.content.Intent.ACTION_VIEW, telegramIntent.action)
        assertEquals(Uri.parse("https://t.me/KaiHatch"), telegramIntent.data)

        val kofiIntent = com.biplov.kaihatch.ui.screens.createExternalUrlIntent(com.biplov.kaihatch.ui.screens.ABOUT_KOFI_URL)
        assertEquals(android.content.Intent.ACTION_VIEW, kofiIntent.action)
        assertEquals(Uri.parse("https://ko-fi.com/biplov13b"), kofiIntent.data)

        assertTrue(com.biplov.kaihatch.ui.screens.openExternalUrl(app, com.biplov.kaihatch.ui.screens.ABOUT_WEBSITE_URL))
        assertTrue(com.biplov.kaihatch.ui.screens.openEmailContact(app, com.biplov.kaihatch.ui.screens.ABOUT_EMAIL_ADDRESS))
        assertTrue(com.biplov.kaihatch.ui.screens.openExternalUrl(app, com.biplov.kaihatch.ui.screens.ABOUT_TELEGRAM_URL))
        assertTrue(com.biplov.kaihatch.ui.screens.openExternalUrl(app, com.biplov.kaihatch.ui.screens.ABOUT_KOFI_URL))

        // 3. Verify Open-source licenses notices list matches verified project dependencies
        val notices = com.biplov.kaihatch.ui.screens.VERIFIED_THIRD_PARTY_NOTICES
        assertEquals(12, notices.size)
        assertTrue(notices.any { it.name == "Apache Commons Compress" && it.version == "1.26.2" })
        assertTrue(notices.any { it.name == "XZ for Java" && it.license.contains("0BSD") })
        assertTrue(notices.any { it.name == "AndroidX Jetpack Compose" && it.version == "2026.03.00" })
    }

    @Test
    fun `test extracted folder auto-expansion targets exact completed folder and never falls back to first folder`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val extractedRoot = StorageHelper.getExtractedDir(app)
        extractedRoot.deleteRecursively()
        extractedRoot.mkdirs()

        // 1. Pre-existing extracted folder: Demo_Archive_extracted
        val demoDir = File(extractedRoot, "Demo_Archive_extracted").apply { mkdirs() }
        File(demoDir, "demo_readme.txt").writeText("Previously extracted demo content")

        // Similar-name folder to test disambiguation: A (1)_extracted
        val similarDir = File(extractedRoot, "A (1)_extracted").apply { mkdirs() }
        File(similarDir, "other.txt").writeText("Other archive content")

        // Target newly extracted folder: A (10)_extracted
        val a10Dir = File(extractedRoot, "A (10)_extracted")

        val demoEntry = com.biplov.kaihatch.data.ExtractedFolderEntry(
            id = "primary:Download/Demo_Archive_extracted",
            name = "Demo_Archive_extracted",
            isSaf = true,
            localFile = demoDir,
            safUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2FDemo_Archive_extracted"),
            totalSizeBytes = 33L,
            children = listOf(
                com.biplov.kaihatch.data.ExtractedFileEntry(
                    id = "primary:Download/Demo_Archive_extracted/demo_readme.txt",
                    name = "demo_readme.txt",
                    sizeBytes = 33L,
                    mimeType = "text/plain",
                    localFile = File(demoDir, "demo_readme.txt"),
                    isSaf = true
                )
            )
        )

        val similarEntry = com.biplov.kaihatch.data.ExtractedFolderEntry(
            id = "primary:Download/A (1)_extracted",
            name = "A (1)_extracted",
            isSaf = true,
            localFile = similarDir,
            safUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2FA%20(1)_extracted"),
            totalSizeBytes = 21L,
            children = emptyList()
        )

        val a10DocUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2FA%20(10)_extracted")
        val a10Target = com.biplov.kaihatch.ui.CompletedExtractionTarget(
            completionId = 1L,
            folderDocUriString = a10DocUri.toString(),
            folderDocumentId = "primary:Download/A (10)_extracted",
            canonicalDirPath = a10Dir.canonicalPath,
            absoluteDirPath = a10Dir.absolutePath,
            outputDirectory = "Download/A (10)_extracted",
            folderName = "A (10)_extracted"
        )

        // 2. Simulate pre-refresh race window where only Demo_Archive_extracted is in the list:
        // Must return null and NEVER fall back to Demo_Archive_extracted (firstOrNull).
        val preRefreshMatch = com.biplov.kaihatch.ui.screens.resolveTargetExtractedFolder(
            extractedFolders = listOf(demoEntry, similarEntry),
            completedTarget = a10Target,
            fallbackOutputDirectory = "Download/A (10)_extracted"
        )
        assertNull("Must not fall back to first folder before target folder is in list", preRefreshMatch)

        // 3. Once A (10)_extracted is present (tested at last, middle, and first positions):
        a10Dir.mkdirs()
        val a10File = File(a10Dir, "payload_a10.txt").apply { writeText("Unpacked from A (10).zip") }
        val a10Entry = com.biplov.kaihatch.data.ExtractedFolderEntry(
            id = "primary:Download/A (10)_extracted",
            name = "A (10)_extracted",
            isSaf = true,
            localFile = a10Dir,
            safUri = a10DocUri,
            totalSizeBytes = a10File.length(),
            children = listOf(
                com.biplov.kaihatch.data.ExtractedFileEntry(
                    id = "primary:Download/A (10)_extracted/payload_a10.txt",
                    name = "payload_a10.txt",
                    sizeBytes = a10File.length(),
                    mimeType = "text/plain",
                    localFile = a10File,
                    isSaf = true
                )
            )
        )

        val orderings = listOf(
            listOf(demoEntry, a10Entry),                 // Exact reported scenario: Demo first, A (10) second
            listOf(demoEntry, a10Entry, similarEntry),   // Target in middle
            listOf(demoEntry, similarEntry, a10Entry),   // Target last
            listOf(a10Entry, demoEntry, similarEntry)    // Target first
        )

        for (folders in orderings) {
            val matched = com.biplov.kaihatch.ui.screens.resolveTargetExtractedFolder(
                extractedFolders = folders,
                completedTarget = a10Target,
                fallbackOutputDirectory = "Download/A (10)_extracted"
            )
            assertNotNull(matched)
            assertEquals("primary:Download/A (10)_extracted", matched!!.id)
            assertEquals("A (10)_extracted", matched.name)

            val expandedId = matched.id
            assertTrue(com.biplov.kaihatch.ui.screens.isExtractedFolderExpanded(a10Entry, expandedId, folders))
            assertEquals(false, com.biplov.kaihatch.ui.screens.isExtractedFolderExpanded(demoEntry, expandedId, folders))
            assertEquals(false, com.biplov.kaihatch.ui.screens.isExtractedFolderExpanded(similarEntry, expandedId, folders))
        }

        // 4. End-to-end ViewModel extraction test for A (10).zip when Demo_Archive_extracted already exists
        a10Dir.deleteRecursively()
        val archivesDir = StorageHelper.getArchivesDir(app).apply { mkdirs() }
        val a10Zip = File(archivesDir, "A (10).zip")
        ZipOutputStream(FileOutputStream(a10Zip)).use { zos ->
            zos.putNextEntry(ZipEntry("hello_a10.txt"))
            zos.write("Hello from A (10).zip!".toByteArray())
            zos.closeEntry()
        }

        val viewModel = HatchViewModel(app)
        viewModel.setHatchMode(false)
        viewModel.selectArchive(a10Zip)

        // Wait for archive summary inspection to complete on Dispatchers.Main + Dispatchers.IO
        var waitTicks = 0
        while (viewModel.archiveSummary.value == null && waitTicks < 100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
            waitTicks++
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertNotNull(viewModel.archiveSummary.value)

        viewModel.startExtraction(requirePersistentFolder = false)

        waitTicks = 0
        while (viewModel.extractionProgress.value.status != ExtractionStatus.COMPLETED && waitTicks < 150) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
            waitTicks++
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(ExtractionStatus.COMPLETED, viewModel.extractionProgress.value.status)

        // Verify that at the exact moment COMPLETED is observed, lastCompletedTarget and extractedFolders are already populated
        val completedTarget = viewModel.lastCompletedTarget.value
        assertNotNull(completedTarget)
        assertEquals("A (10)_extracted", completedTarget!!.folderName)

        val currentFolders = viewModel.extractedFolders.value
        assertTrue(currentFolders.any { it.name == "Demo_Archive_extracted" })
        assertTrue(currentFolders.any { it.name == "A (10)_extracted" })

        val resolvedAfterExtract = com.biplov.kaihatch.ui.screens.resolveTargetExtractedFolder(
            extractedFolders = currentFolders,
            completedTarget = completedTarget,
            fallbackOutputDirectory = viewModel.extractionProgress.value.outputDirectory
        )
        assertNotNull(resolvedAfterExtract)
        assertEquals("A (10)_extracted", resolvedAfterExtract!!.name)
    }
}





