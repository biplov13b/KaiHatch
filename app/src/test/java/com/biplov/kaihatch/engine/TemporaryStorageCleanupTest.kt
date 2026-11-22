package com.biplov.kaihatch.engine

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ArchiveJobRunner
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.CompressionProgress
import com.biplov.kaihatch.model.CompressionSettings
import com.biplov.kaihatch.model.CompressionStatus
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.ui.HatchViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random
import java.util.zip.ZipFile

/**
 * Storage Lifecycle & Cleanup Regression Suite:
 * Validates temporary storage lifecycle (`cacheDir/compress_staging` and `cacheDir/compress_temp`),
 * ownership-based cleanup, active-file protection, SAF output failure handling, and strict
 * preservation of extraction recovery journals and legacy `files/extracted` data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryStorageCleanupTest {

    private lateinit var context: Application
    private lateinit var testRootDir: File
    private lateinit var safBackingRoot: File

    companion object {
        const val AUTHORITY = "com.biplov.kaihatch.cleanup.saf.documents"
        const val TREE_DOC_ID = "cleanup_tree_root"
    }

    class CleanupTestSafDocumentsProvider : ContentProvider() {
        companion object {
            var rootDir: File? = null
            var failCreateDocument: Boolean = false
            var failWriteStream: Boolean = false
            var createdDocCount: Int = 0
            var deletedDocCount: Int = 0

            fun reset(root: File) {
                rootDir = root
                failCreateDocument = false
                failWriteStream = false
                createdDocCount = 0
                deletedDocCount = 0
            }
        }

        override fun onCreate(): Boolean = true

        private fun fileForDocId(docId: String): File {
            val base = rootDir ?: throw IllegalStateException("SAF rootDir not initialized")
            if (docId == TREE_DOC_ID) return base
            val rel = docId.removePrefix("$TREE_DOC_ID/").trimStart('/')
            return File(base, rel)
        }

        private fun docIdForFile(file: File): String {
            val base = rootDir ?: throw IllegalStateException("SAF rootDir not initialized")
            val rel = file.relativeTo(base).path.replace('\\', '/').trim('/')
            return if (rel.isEmpty()) TREE_DOC_ID else "$TREE_DOC_ID/$rel"
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cols = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val cursor = MatrixCursor(cols)
            val uriStr = uri.toString()

            if (uriStr.contains("/children")) {
                val parentDocId = DocumentsContract.getDocumentId(uri)
                val parentDir = fileForDocId(parentDocId)
                parentDir.listFiles()?.forEach { child ->
                    addRow(cursor, cols, child)
                }
            } else {
                val docId = try {
                    DocumentsContract.getDocumentId(uri)
                } catch (_: Throwable) {
                    DocumentsContract.getTreeDocumentId(uri)
                }
                val target = fileForDocId(docId)
                if (target.exists()) {
                    addRow(cursor, cols, target)
                }
            }
            return cursor
        }

        private fun addRow(cursor: MatrixCursor, cols: Array<out String>, file: File) {
            val row = Array<Any?>(cols.size) { i ->
                when (cols[i]) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> docIdForFile(file)
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE ->
                        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> if (file.isDirectory) 0L else file.length()
                    else -> null
                }
            }
            cursor.addRow(row)
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:createDocument" && extras != null) {
                if (failCreateDocument) {
                    throw SecurityException("Simulated SAF createDocument failure")
                }
                val parentUri = extras.getParcelable<Uri>("uri") ?: return null
                val mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
                val displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return null
                val parentDocId = try {
                    DocumentsContract.getDocumentId(parentUri)
                } catch (_: Throwable) {
                    DocumentsContract.getTreeDocumentId(parentUri)
                }
                val parentFile = fileForDocId(parentDocId)
                if (!parentFile.exists()) parentFile.mkdirs()
                val target = File(parentFile, displayName)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    if (!target.exists()) target.createNewFile()
                    createdDocCount++
                }
                val newDocId = docIdForFile(target)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, newDocId)
                return Bundle().apply {
                    putParcelable("uri", docUri)
                }
            }
            if (method == "android:deleteDocument" && extras != null) {
                val docUri = extras.getParcelable<Uri>("uri") ?: return null
                val docId = DocumentsContract.getDocumentId(docUri)
                val target = fileForDocId(docId)
                val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
                if (deleted) deletedDocCount++
                return Bundle().apply { putBoolean("result", deleted) }
            }
            return super.call(method, arg, extras)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failWriteStream && mode.contains("w")) {
                throw java.io.IOException("Simulated SAF output stream failure")
            }
            val docId = DocumentsContract.getDocumentId(uri)
            val file = fileForDocId(docId)
            val pfdMode = when {
                mode.contains("rw") -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
                mode.contains("w") -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                else -> ParcelFileDescriptor.MODE_READ_ONLY
            }
            if (!file.exists() && mode.contains("w")) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            return ParcelFileDescriptor.open(file, pfdMode)
        }

        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            return openFile(uri, mode)
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testRootDir = File(context.cacheDir, "phase3_test_${System.nanoTime()}").apply { mkdirs() }
        safBackingRoot = File(testRootDir, "saf_provider_root").apply { mkdirs() }
        CleanupTestSafDocumentsProvider.reset(safBackingRoot)

        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
        }
        Robolectric.buildContentProvider(CleanupTestSafDocumentsProvider::class.java).create(info)

        // Ensure clean compression temp & staging directories before each test
        StorageHelper.getTempDir(context).listFiles()?.forEach { it.deleteRecursively() }
        StorageHelper.getCompressStagingDir(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        StorageHelper.getTempDir(context).listFiles()?.forEach {
            StorageHelper.unregisterActiveCompressionFile(it)
            it.deleteRecursively()
        }
        StorageHelper.getCompressStagingDir(context).listFiles()?.forEach {
            StorageHelper.unregisterActiveCompressionFile(it)
            it.deleteRecursively()
        }
        testRootDir.deleteRecursively()
    }

    private fun buildSafTreeUri(): Uri {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_DOC_ID)
    }

    /**
     * 1. Successful SAF-backed compression cleans up both `cacheDir/compress_temp`
     *    and `cacheDir/compress_staging` while writing a valid archive to the SAF folder.
     */
    @Test
    fun successfulSafCompression_cleansUpTempArchiveAndStagedInputs() = runBlocking {
        val treeUri = buildSafTreeUri()
        val stagingDir = StorageHelper.getCompressStagingDir(context)
        val tempDir = StorageHelper.getTempDir(context)

        val stagedFile1 = File(stagingDir, "staged_alpha.txt").apply {
            writeBytes("Alpha staged payload ".repeat(200).toByteArray())
        }
        val stagedSubfolder = File(stagingDir, "staged_folder").apply { mkdirs() }
        val stagedFile2 = File(stagedSubfolder, "nested_beta.bin").apply {
            val bytes = ByteArray(32 * 1024)
            Random(42L).nextBytes(bytes)
            writeBytes(bytes)
        }
        val localUserFile = File(testRootDir, "user_keep.txt").apply {
            writeBytes("User local file that must never be deleted".toByteArray())
        }

        val items = listOf(stagedFile1, stagedSubfolder, localUserFile)
        items.forEach { StorageHelper.registerActiveCompressionFile(it) }

        val target = StorageHelper.prepareArchiveTargetInGrantedFolder(
            context = context,
            treeUri = treeUri,
            fileName = "Bundle_Clean.zip"
        )
        assertTrue("Expected SAF streaming target", target.requiresSafStreamOnFinish)
        assertTrue("Working file must be inside compress_temp", StorageHelper.isCompressionTempFile(context, target.workingFile))

        var lastStatus: CompressionStatus? = null
        HatchCompressEngine.compressFiles(
            itemsToCompress = items,
            targetArchiveFile = target.workingFile,
            settings = CompressionSettings(archiveName = "Bundle_Clean.zip", format = ArchiveFormat.ZIP)
        ).collect { progress ->
            lastStatus = progress.status
        }
        assertEquals(CompressionStatus.COMPLETED, lastStatus)
        assertTrue("Temp working archive should exist prior to finalize", target.workingFile.exists())

        val finalBytes = StorageHelper.finalizeArchiveTargetInGrantedFolder(context, target)
        assertTrue("Finalized archive size must be > 0", finalBytes > 0L)

        // Simulate ViewModel finally cleanup of staged items
        val cleanedStaged = StorageHelper.cleanupStagedCompressionItems(context, items, "test_completion")
        assertEquals("Both staged items (file + folder) must be cleaned up", 2, cleanedStaged)

        // Verify compress_temp and compress_staging are completely empty
        assertFalse("Temp working file must be deleted after finalize", target.workingFile.exists())
        assertEquals("compress_temp must be empty", 0, tempDir.listFiles()?.size ?: 0)
        assertEquals("compress_staging must be empty", 0, stagingDir.listFiles()?.size ?: 0)

        // Verify non-staged user file was preserved
        assertTrue("Non-staged user file must remain untouched", localUserFile.exists())

        // Verify final archive in SAF folder is valid and contains all 3 entries
        val safArchive = File(safBackingRoot, "Bundle_Clean.zip")
        assertTrue("SAF output archive must exist", safArchive.exists())
        assertEquals(finalBytes, safArchive.length())
        ZipFile(safArchive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("staged_alpha.txt"))
            assertTrue(names.contains("staged_folder/nested_beta.bin"))
            assertTrue(names.contains("user_keep.txt"))
        }
    }

    /**
     * 2. Cancelling compression mid-flight deletes the partial temporary archive in `cacheDir/compress_temp`
     *    and leaves no orphaned temp files.
     */
    @Test
    fun cancelledCompression_deletesPartialTempArchiveAndSafDocument() = runBlocking {
        val treeUri = buildSafTreeUri()
        val tempDir = StorageHelper.getTempDir(context)

        // Create several 1 MB input files so compression takes multiple batches
        val inputs = (1..6).map { idx ->
            File(testRootDir, "cancel_input_$idx.bin").apply {
                val buf = ByteArray(1024 * 1024)
                Random(100L + idx).nextBytes(buf)
                writeBytes(buf)
            }
        }

        val target = StorageHelper.prepareArchiveTargetInGrantedFolder(
            context = context,
            treeUri = treeUri,
            fileName = "Cancelled_Job.7z"
        )

        val job = launch(Dispatchers.IO) {
            try {
                HatchCompressEngine.compressFiles(
                    itemsToCompress = inputs,
                    targetArchiveFile = target.workingFile,
                    settings = CompressionSettings(
                        archiveName = "Cancelled_Job.7z",
                        format = ArchiveFormat.SEVEN_Z,
                        compressionLevel = 5
                    )
                ).collect { progress ->
                    if (progress.status == CompressionStatus.COMPRESSING) {
                        throw CancellationException("User cancelled compression")
                    }
                }
            } finally {
                StorageHelper.cleanupCompressionFile(
                    context = context,
                    file = target.workingFile,
                    reason = "test_cancelled_finally",
                    forceUnregister = true
                )
                target.documentUri?.let { StorageHelper.deleteSafDocument(context, it) }
            }
        }
        job.join()

        assertFalse("Partial temp archive must be deleted on cancellation", target.workingFile.exists())
        assertEquals("compress_temp must be empty after cancellation", 0, tempDir.listFiles()?.size ?: 0)
        assertFalse("Partial SAF document must be deleted on cancellation", File(safBackingRoot, "Cancelled_Job.7z").exists())
    }

    /**
     * 3. Compression exception during archive creation deletes incomplete `targetArchiveFile`
     *    and emits `CompressionStatus.ERROR` without leaving orphaned partial archives.
     */
    @Test
    fun compressionException_deletesIncompleteArchiveAndReportsError() = runBlocking {
        val tempDir = StorageHelper.getTempDir(context)
        val validFile = File(testRootDir, "valid.txt").apply {
            writeBytes("Valid content before broken input".repeat(100).toByteArray())
        }
        val brokenLinkPath = java.nio.file.Files.createSymbolicLink(
            File(testRootDir, "broken_symlink.bin").toPath(),
            File(testRootDir, "non_existent_target_${System.nanoTime()}").toPath()
        )
        val brokenLinkFile = brokenLinkPath.toFile()

        val failingTarget = File(tempDir, "Failing_BrokenLink.tar")
        val errorEvents = mutableListOf<CompressionProgress>()
        HatchCompressEngine.compressFiles(
            itemsToCompress = listOf(validFile, brokenLinkFile),
            targetArchiveFile = failingTarget,
            settings = CompressionSettings(archiveName = "Failing_BrokenLink.tar", format = ArchiveFormat.TAR)
        ).collect {
            errorEvents.add(it)
        }

        assertTrue(
            "Expected CompressionStatus.ERROR when an input fails during compression",
            errorEvents.any { it.status == CompressionStatus.ERROR }
        )
        assertFalse(
            "Incomplete targetArchiveFile must be deleted when compression throws an exception",
            failingTarget.exists()
        )
    }

    /**
     * 4. SAF output failure:
     *    - Case A: `DocumentsContract.createDocument` fails -> `prepareArchiveTargetInGrantedFolder`
     *      throws `IOException` immediately instead of silently writing to `cacheDir/compress_temp`
     *      with `requiresSafStreamOnFinish = false`.
     *    - Case B: `exportFileToUri` fails during `finalizeArchiveTargetInGrantedFolder` ->
     *      deletes `workingFile` in `cacheDir/compress_temp`, deletes the empty SAF document, and throws `IOException`.
     */
    @Test
    fun safOutputFailure_neverStrandsArchiveInCompressTemp() {
        val treeUri = buildSafTreeUri()
        val tempDir = StorageHelper.getTempDir(context)

        // Case A: createDocument fails
        CleanupTestSafDocumentsProvider.failCreateDocument = true
        var createFailed = false
        try {
            StorageHelper.prepareArchiveTargetInGrantedFolder(
                context = context,
                treeUri = treeUri,
                fileName = "Should_Not_Be_Created.zip"
            )
        } catch (e: java.io.IOException) {
            createFailed = true
        }
        assertTrue("prepareArchiveTargetInGrantedFolder must fail explicitly when SAF createDocument fails", createFailed)
        assertEquals("compress_temp must remain empty when SAF createDocument fails", 0, tempDir.listFiles()?.size ?: 0)

        // Case B: createDocument succeeds, compression writes temp file, then SAF stream export fails
        CleanupTestSafDocumentsProvider.failCreateDocument = false
        val target = StorageHelper.prepareArchiveTargetInGrantedFolder(
            context = context,
            treeUri = treeUri,
            fileName = "Export_Failure.zip"
        )
        target.workingFile.writeBytes("Simulated compressed archive payload".repeat(256).toByteArray())
        assertTrue(target.workingFile.exists())

        CleanupTestSafDocumentsProvider.failWriteStream = true
        var finalizeFailed = false
        try {
            StorageHelper.finalizeArchiveTargetInGrantedFolder(context, target)
        } catch (e: java.io.IOException) {
            finalizeFailed = true
        }
        assertTrue("finalizeArchiveTargetInGrantedFolder must throw IOException when SAF export fails", finalizeFailed)
        assertFalse("Temp working file must be deleted in finally even when SAF export fails", target.workingFile.exists())
        assertEquals("compress_temp must be empty after failed SAF export", 0, tempDir.listFiles()?.size ?: 0)
        assertFalse("Partial SAF document must be deleted when SAF export fails", File(safBackingRoot, "Export_Failure.zip").exists())
    }

    /**
     * 5. Interrupted compression & abandoned-file detection:
     *    `cleanupAbandonedCompressionTempFiles` reclaims orphaned files in `compress_temp` and
     *    `compress_staging` when idle, while preserving items still queued in `queuedItems`.
     */
    @Test
    fun abandonedCompressionSweep_reclaimsOrphanedTempAndStagingFilesWhilePreservingQueuedItems() {
        val tempDir = StorageHelper.getTempDir(context)
        val stagingDir = StorageHelper.getCompressStagingDir(context)

        val orphanTempArchive = File(tempDir, "abandoned_crash.zip").apply {
            writeBytes(ByteArray(64 * 1024))
        }
        val orphanStagedFile = File(stagingDir, "abandoned_staged.bin").apply {
            writeBytes(ByteArray(32 * 1024))
        }
        val orphanStagedFolder = File(stagingDir, "abandoned_folder").apply {
            mkdirs()
            File(this, "inner.txt").writeBytes("orphaned".toByteArray())
        }
        val queuedStagedFile = File(stagingDir, "currently_queued.txt").apply {
            writeBytes("Still in user queue".toByteArray())
        }

        val reclaimed = StorageHelper.cleanupAbandonedCompressionTempFiles(
            context = context,
            isCompressionRunning = false,
            queuedItems = listOf(queuedStagedFile)
        )

        assertEquals("Should reclaim 3 abandoned items (1 temp archive + 2 staged items)", 3, reclaimed)
        assertFalse(orphanTempArchive.exists())
        assertFalse(orphanStagedFile.exists())
        assertFalse(orphanStagedFolder.exists())
        assertTrue("Queued staged item must be preserved", queuedStagedFile.exists())
    }

    /**
     * 6. Repeated compression operations:
     *    Running 5 sequential SAF-backed compression runs leaves 0 bytes in `compress_temp` and `compress_staging`.
     */
    @Test
    fun repeatedSafCompressionOperations_doNotAccumulateTemporaryStorage() = runBlocking {
        val treeUri = buildSafTreeUri()
        val tempDir = StorageHelper.getTempDir(context)
        val stagingDir = StorageHelper.getCompressStagingDir(context)

        for (i in 1..5) {
            val stagedInput = File(stagingDir, "iter_${i}_payload.bin").apply {
                val bytes = ByteArray(48 * 1024)
                Random(i.toLong()).nextBytes(bytes)
                writeBytes(bytes)
            }
            StorageHelper.registerActiveCompressionFile(stagedInput)
            val archiveName = "Repeated_Run_$i.zip"
            val target = StorageHelper.prepareArchiveTargetInGrantedFolder(context, treeUri, archiveName)

            HatchCompressEngine.compressFiles(
                itemsToCompress = listOf(stagedInput),
                targetArchiveFile = target.workingFile,
                settings = CompressionSettings(archiveName = archiveName, format = ArchiveFormat.ZIP)
            ).collect {}

            val outBytes = StorageHelper.finalizeArchiveTargetInGrantedFolder(context, target)
            assertTrue(outBytes > 0L)
            StorageHelper.cleanupStagedCompressionItems(context, listOf(stagedInput), "iter_$i")

            assertEquals("compress_temp must be empty after run $i", 0, tempDir.listFiles()?.size ?: 0)
            assertEquals("compress_staging must be empty after run $i", 0, stagingDir.listFiles()?.size ?: 0)
            assertTrue("SAF output $archiveName must exist", File(safBackingRoot, archiveName).exists())
        }
    }

    /**
     * 7. Large temporary archive handling (> 2 MB multi-chunk parallel deflate entry):
     *    Streams large temporary archive to SAF and reclaims 100% of `compress_temp`.
     */
    @Test
    fun largeTemporaryArchive_streamsToSafAndReclaimsAllTempStorage() = runBlocking {
        val treeUri = buildSafTreeUri()
        val tempDir = StorageHelper.getTempDir(context)

        val largeInput = File(testRootDir, "large_payload_3mb.bin").apply {
            val bytes = ByteArray(3 * 1024 * 1024 + 12345)
            Random(777L).nextBytes(bytes)
            writeBytes(bytes)
        }

        val target = StorageHelper.prepareArchiveTargetInGrantedFolder(
            context = context,
            treeUri = treeUri,
            fileName = "Large_Streamed.zip"
        )

        HatchCompressEngine.compressFiles(
            itemsToCompress = listOf(largeInput),
            targetArchiveFile = target.workingFile,
            settings = CompressionSettings(archiveName = "Large_Streamed.zip", format = ArchiveFormat.ZIP)
        ).collect {}

        val outBytes = StorageHelper.finalizeArchiveTargetInGrantedFolder(context, target)
        assertTrue("Expected > 2.5 MB finalized SAF archive", outBytes > 2500 * 1024L)
        assertEquals("compress_temp must be 0 files after large SAF compression", 0, tempDir.listFiles()?.size ?: 0)

        val safFile = File(safBackingRoot, "Large_Streamed.zip")
        assertEquals(outBytes, safFile.length())
        ZipFile(safFile).use { zip ->
            val entry = zip.getEntry("large_payload_3mb.bin")
            assertNotNull(entry)
            assertEquals(largeInput.length(), entry.size)
            assertArrayEquals(largeInput.readBytes(), zip.getInputStream(entry).readBytes())
        }
    }

    /**
     * 8. Active-file protection:
     *    `cleanupCompressionFile` and `cleanupAbandonedCompressionTempFiles` never delete files
     *    registered to an active compression operation or while `isCompressionRunning == true`.
     */
    @Test
    fun activeFileProtection_preventsDeletionOfInUseCompressionFiles() {
        val tempDir = StorageHelper.getTempDir(context)
        val stagingDir = StorageHelper.getCompressStagingDir(context)

        val activeTempArchive = File(tempDir, "in_progress_archive.zip").apply {
            writeBytes("actively being written".toByteArray())
        }
        val activeStagedDir = File(stagingDir, "active_staged_dir").apply { mkdirs() }
        val activeNestedFile = File(activeStagedDir, "nested_active.txt").apply {
            writeBytes("actively being read".toByteArray())
        }

        StorageHelper.registerActiveCompressionFile(activeTempArchive)
        StorageHelper.registerActiveCompressionFile(activeStagedDir)

        try {
            assertFalse(
                "cleanupCompressionFile must refuse to delete active temp archive",
                StorageHelper.cleanupCompressionFile(context, activeTempArchive, "attempt_while_active")
            )
            assertFalse(
                "cleanupCompressionFile must refuse to delete child of active staged directory",
                StorageHelper.cleanupCompressionFile(context, activeNestedFile, "attempt_nested_while_active")
            )
            assertEquals(
                "Sweep must return 0 when isCompressionRunning is true",
                0,
                StorageHelper.cleanupAbandonedCompressionTempFiles(context, isCompressionRunning = true)
            )
            assertEquals(
                "Sweep must skip registered active files even if isCompressionRunning is false",
                0,
                StorageHelper.cleanupAbandonedCompressionTempFiles(context, isCompressionRunning = false)
            )
            assertTrue(activeTempArchive.exists())
            assertTrue(activeNestedFile.exists())
        } finally {
            StorageHelper.unregisterActiveCompressionFile(activeTempArchive)
            StorageHelper.unregisterActiveCompressionFile(activeStagedDir)
        }
    }

    /**
     * 9. Recovery-file and legacy `files/extracted` preservation:
     *    Phase 3 cleanup helpers must NEVER delete extraction recovery journals (`files/hatch_journals`),
     *    tail-swap guard files, user archives (`files/archives`), or legacy files in `files/extracted`.
     */
    @Test
    fun recoveryAndLegacyExtractedFiles_areStrictlyProtectedFromCleanup() {
        // 1. Legacy file in files/extracted
        val legacyExtractedFolder = File(StorageHelper.getExtractedDir(context), "Legacy_User_Folder_extracted").apply {
            mkdirs()
        }
        val legacyFile = File(legacyExtractedFolder, "important_save.dat").apply {
            writeBytes("Legacy extracted user data that must never be deleted".toByteArray())
        }

        // 2. Active crash-recovery journal in files/hatch_journals
        val dummyArchive = File(testRootDir, "Recoverable_Archive.zip").apply {
            writeBytes(ByteArray(8192))
        }
        val journalFile = ResumeJournalManager.getStateFileForArchive(context, dummyArchive.absolutePath)
        val journalState = ResumeJournalState(
            stateFilePath = journalFile.absolutePath,
            archivePath = dummyArchive.absolutePath,
            archiveName = dummyArchive.name,
            targetDirPath = File(testRootDir, "recover_out").apply { mkdirs() }.absolutePath,
            originalArchiveBytes = 8192L,
            currentArchiveBytes = 8192L,
            bytesReclaimedSoFar = 0L,
            totalEntries = 2,
            completedPaths = setOf("part1.bin"),
            remainingEntries = emptyList()
        )
        assertTrue(ResumeJournalManager.saveState(journalState))
        assertTrue("Journal file must exist on disk", journalFile.exists())

        // 3. Attempt direct cleanup and abandoned sweep
        assertFalse(
            "Legacy file in files/extracted must be rejected by cleanupCompressionFile",
            StorageHelper.cleanupCompressionFile(context, legacyFile, "test_protect_legacy", forceUnregister = true)
        )
        assertFalse(
            "Recovery journal must be rejected by cleanupCompressionFile",
            StorageHelper.cleanupCompressionFile(context, journalFile, "test_protect_journal", forceUnregister = true)
        )

        StorageHelper.cleanupAbandonedCompressionTempFiles(context, isCompressionRunning = false)

        assertTrue("Legacy file in files/extracted must remain intact", legacyFile.exists())
        assertTrue("Recovery journal in files/hatch_journals must remain intact", journalFile.exists())

        // Clean up test artifacts
        legacyExtractedFolder.deleteRecursively()
        ResumeJournalManager.deleteState(journalFile.absolutePath)
    }

    /**
     * 10. Queue removal (`removeItemToCompress` and `clearItemsToCompress`):
     *     Removing or clearing staged items from `HatchViewModel` deletes their copies in
     *     `cacheDir/compress_staging` while leaving regular local user files intact.
     */
    @Test
    fun viewModelQueueRemoval_deletesStagedFilesWhilePreservingUserFiles() {
        val stagingDir = StorageHelper.getCompressStagingDir(context)
        val stagedItem1 = File(stagingDir, "queued_staged_1.txt").apply {
            writeBytes("Staged item 1".toByteArray())
        }
        val stagedItem2 = File(stagingDir, "queued_staged_2.txt").apply {
            writeBytes("Staged item 2".toByteArray())
        }
        val userLocalItem = File(testRootDir, "user_local_photo.jpg").apply {
            writeBytes("User photo outside staging".toByteArray())
        }

        val viewModel = HatchViewModel(context)
        viewModel.addItemToCompress(stagedItem1)
        viewModel.addItemToCompress(stagedItem2)
        viewModel.addItemToCompress(userLocalItem)
        assertEquals(3, viewModel.itemsToCompress.value.size)

        // Remove single staged item -> deleted from compress_staging
        viewModel.removeItemToCompress(stagedItem1)
        assertFalse("Removed staged item 1 must be deleted from compress_staging", stagedItem1.exists())
        assertTrue("Remaining staged item 2 must still exist", stagedItem2.exists())
        assertTrue("User local item must still exist", userLocalItem.exists())

        // Clear remaining items -> stagedItem2 deleted, userLocalItem preserved
        viewModel.clearItemsToCompress()
        assertTrue(viewModel.itemsToCompress.value.isEmpty())
        assertFalse("Cleared staged item 2 must be deleted from compress_staging", stagedItem2.exists())
        assertTrue("User local item outside compress_staging must never be deleted", userLocalItem.exists())
    }
}
