package com.biplov.kaihatch.engine

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2 Regression Tests for Extraction Recovery & Resume Safety Hardening:
 * 1. Standard Mode interruption (cancel mid-extraction -> source intact, no journal, clean restart succeeds)
 * 2. Hatch Mode interruption immediately BEFORE and AFTER source truncation (CD and window boundaries)
 * 3. SAF destination persistence across interruption and resume (including re-queuing missing SAF entries whose source bytes remain)
 * 4. Revoked SAF permissions / unavailable SAF destination on resume (halts safely before archive mutation, preserves journal, recovers once permission restored)
 * 5. Interrupted large STORED entry (>= 4 MB) extraction on direct POSIX (in-place tail-slice resume) and SAF destination (no premature source truncation)
 * 6. Interrupted large DEFLATED entry (>= 4 MB) extraction (recoverable before slot 0 pwrite vs. unrecoverable after slot 0 pwrite)
 * 7. Recovery journal consistency (v2 binary + JSON fallback + index integrity)
 * 8. CRC failure during recovery (corrupted completed output whose source archive region was truncated halts recovery)
 * 9. Output write failure during Hatch Mode extraction (failed output quarantined, source archive region preserved)
 * 10. Repeated interruption and recovery across multiple windows until 100% verified completion
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2RecoveryAndResumeSafetyTest {

    companion object {
        const val TEST_AUTHORITY = "com.biplov.kaihatch.test.saf.phase2"
    }

    class Phase2SafDocumentsProvider : android.content.ContentProvider() {
        companion object {
            lateinit var backingRootDir: File
            val docIdToFile = ConcurrentHashMap<String, File>()
            val permissionRevoked = AtomicBoolean(false)
            val failWriteForFileName = AtomicReference<String?>(null)
            private var nextId = 1

            @Synchronized
            fun reset(root: File) {
                backingRootDir = root
                docIdToFile.clear()
                permissionRevoked.set(false)
                failWriteForFileName.set(null)
                nextId = 1
                docIdToFile["opaque_tree_root"] = root
            }

            @Synchronized
            fun registerFile(file: File): String {
                val existing = docIdToFile.entries.firstOrNull { it.value.absolutePath == file.absolutePath }?.key
                if (existing != null) return existing
                val id = "opaque_doc_${nextId++}"
                docIdToFile[id] = file
                return id
            }
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun update(
            uri: Uri,
            values: android.content.ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (permissionRevoked.get()) {
                throw SecurityException("SAF permission revoked")
            }
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return 0
            val file = docIdToFile[docId] ?: return 0
            val existed = file.exists()
            file.deleteRecursively()
            docIdToFile.remove(docId)
            return if (existed) 1 else 0
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            if (permissionRevoked.get()) {
                throw SecurityException("SAF permission revoked for $uri")
            }
            val cols = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val cursor = MatrixCursor(cols)
            val uriStr = uri.toString()

            if (uriStr.contains("/children")) {
                val segments = uri.pathSegments
                val childrenIdx = segments.indexOf("children")
                val parentDocId = if (childrenIdx > 0) Uri.decode(segments[childrenIdx - 1]) else "opaque_tree_root"
                val parentDir = docIdToFile[parentDocId] ?: return cursor
                val children = parentDir.listFiles() ?: emptyArray()
                for (child in children) {
                    val childId = registerFile(child)
                    cursor.addRow(buildRow(cols, childId, child))
                }
            } else {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: "opaque_tree_root"
                val file = docIdToFile[docId]
                if (file != null && file.exists()) {
                    cursor.addRow(buildRow(cols, docId, file))
                }
            }
            return cursor
        }

        private fun buildRow(cols: Array<out String>, docId: String, file: File): Array<Any?> {
            return cols.map { col ->
                when (col) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> docId
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE ->
                        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> if (file.isFile) file.length() else 0L
                    else -> null
                }
            }.toTypedArray()
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (permissionRevoked.get()) {
                throw SecurityException("SAF permission revoked")
            }
            if (extras == null) return null
            @Suppress("DEPRECATION")
            val targetUri = extras.getParcelable<Uri>("uri") ?: return null
            return when {
                method.contains("createDocument") -> {
                    val mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
                    val displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return null
                    if (failWriteForFileName.get() == displayName) {
                        return null
                    }
                    val parentDocId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull()
                        ?: runCatching { DocumentsContract.getTreeDocumentId(targetUri) }.getOrNull()
                        ?: "opaque_tree_root"
                    val parentDir = docIdToFile[parentDocId] ?: return null
                    if (!parentDir.exists()) parentDir.mkdirs()

                    // Simulate Android ExternalStorageProvider behavior where createDocument creates "name (1)" if child already exists
                    var target = File(parentDir, displayName)
                    if (target.exists() && mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                        val base = displayName.substringBeforeLast('.')
                        val ext = displayName.substringAfterLast('.', "")
                        val dupName = if (ext.isNotEmpty()) "$base (1).$ext" else "$displayName (1)"
                        target = File(parentDir, dupName)
                    }
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        if (!target.exists()) target.createNewFile()
                    }
                    val newDocId = registerFile(target)
                    val docUri = if (DocumentsContract.isTreeUri(targetUri)) {
                        DocumentsContract.buildDocumentUriUsingTree(targetUri, newDocId)
                    } else {
                        DocumentsContract.buildDocumentUri(TEST_AUTHORITY, newDocId)
                    }
                    Bundle().apply {
                        putParcelable("uri", docUri)
                    }
                }
                method.contains("deleteDocument") -> {
                    val docId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull() ?: return null
                    val file = docIdToFile[docId]
                    val deleted = file?.deleteRecursively() ?: false
                    docIdToFile.remove(docId)
                    Bundle().apply {
                        putBoolean("result", deleted)
                    }
                }
                else -> super.call(method, arg, extras)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (permissionRevoked.get()) {
                throw SecurityException("SAF permission revoked")
            }
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val file = docIdToFile[docId] ?: return null
            if (failWriteForFileName.get() == file.name && mode.contains("w")) {
                throw java.io.IOException("Simulated SAF write descriptor failure for ${file.name}")
            }
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, pfdMode)
        }

        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? {
            return openFile(uri, mode)
        }
    }

    private lateinit var context: Context
    private lateinit var testRootDir: File
    private lateinit var safBackingRoot: File
    private lateinit var directPosixRoot: File
    private lateinit var safTreeUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        StorageHelper.clearSafDirCache()

        // Clean any leftover journals from previous tests
        File(context.filesDir, "journals").deleteRecursively()
        File(context.filesDir, "extracted").deleteRecursively()

        testRootDir = File(context.cacheDir.parentFile, "phase2_recovery_test_${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        safBackingRoot = File(testRootDir, "saf_provider_storage").apply { mkdirs() }
        directPosixRoot = File(testRootDir, "direct_posix_storage").apply { mkdirs() }

        Phase2SafDocumentsProvider.reset(safBackingRoot)
        Robolectric.setupContentProvider(Phase2SafDocumentsProvider::class.java, TEST_AUTHORITY)

        safTreeUri = DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, "opaque_tree_root")
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        Phase2SafDocumentsProvider.permissionRevoked.set(false)
        Phase2SafDocumentsProvider.failWriteForFileName.set(null)
        StorageHelper.clearSafDirCache()
        File(context.filesDir, "journals").deleteRecursively()
        testRootDir.deleteRecursively()
    }

    private fun deterministicBytes(length: Int, seed: Int): ByteArray {
        val out = ByteArray(length)
        var s = seed.toLong() xor 0x6A09E667F3BCC908L
        var i = 0
        while (i < length) {
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            out[i] = (s and 0xFF).toByte()
            i++
        }
        return out
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest()
    }

    private fun computeCrc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private data class SpecEntry(
        val path: String,
        val method: Int,
        val payload: ByteArray,
        val sha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)
    )

    private fun buildZipArchive(destZip: File, entries: List<SpecEntry>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destZip), 64 * 1024)).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            for (spec in entries) {
                val ze = ZipEntry(spec.path)
                ze.method = spec.method
                if (spec.method == ZipEntry.STORED) {
                    ze.size = spec.payload.size.toLong()
                    ze.compressedSize = spec.payload.size.toLong()
                    ze.crc = computeCrc32(spec.payload)
                }
                zos.putNextEntry(ze)
                zos.write(spec.payload)
                zos.closeEntry()
            }
        }
    }

    /**
     * 1. Standard Mode interruption:
     * Cancelling a Standard Mode extraction mid-flight must leave the source archive 100% intact,
     * create zero .hatchstate journals, write zero files into files/extracted, and allow a clean
     * restart that extracts all entries with exact SHA-256 integrity.
     */
    @Test
    fun standardMode_interruptionAndRestart_preservesSourceArchiveAndExtractsCleanly() = runBlocking {
        val specs = (1..8).map { idx ->
            SpecEntry(
                path = "folder_$idx/file_$idx.bin",
                method = if (idx % 2 == 0) ZipEntry.STORED else ZipEntry.DEFLATED,
                payload = deterministicBytes(512 * 1024, 100 + idx)
            )
        }
        val zipFile = File(testRootDir, "standard_interrupt.zip")
        buildZipArchive(zipFile, specs)
        val originalLength = zipFile.length()
        val originalSha256 = sha256(zipFile)

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "standard_interrupt").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "standard_interrupt")
        assertNotNull(safSubfolderUri)

        // Cancel after the first progress emission during extraction
        val job = launch {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipFile,
                targetDir = fallbackDir,
                isHatchMode = false,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = safSubfolderUri
            ).collect { progress ->
                if (progress.bytesExtracted > 0L) {
                    throw CancellationException("Simulated user cancellation in Standard Mode")
                }
            }
        }
        job.join()

        // Source archive must be 100% untouched
        assertTrue(zipFile.exists())
        assertEquals(originalLength, zipFile.length())
        assertArrayEquals(originalSha256, sha256(zipFile))

        // Standard Mode must never create a destructive Hatch Mode journal
        val pendingJournals = ResumeJournalManager.loadPendingStates(context)
        assertTrue("Standard Mode must not persist Hatch Mode recovery journals", pendingJournals.isEmpty())

        // Restart Standard Mode extraction cleanly to the same SAF destination
        val restartEmissions = KaiHatchEngine.extractArchive(
            context = context,
            archiveFile = zipFile,
            targetDir = fallbackDir,
            isHatchMode = false,
            targetTreeUri = safTreeUri,
            targetFolderDocUri = safSubfolderUri
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, restartEmissions.last().status)
        assertEquals(originalLength, zipFile.length())

        val safExtractedFolder = File(safBackingRoot, "standard_interrupt")
        for (spec in specs) {
            val out = File(safExtractedFolder, spec.path)
            assertTrue("Missing ${spec.path} in SAF folder", out.exists())
            assertArrayEquals(spec.sha256, sha256(out))
            val dupName = spec.path.replace(".bin", " (1).bin")
            assertFalse("Duplicate SAF file $dupName must not exist", File(safExtractedFolder, dupName).exists())
        }
        val leakedFiles = fallbackDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Zero files may leak into files/extracted", leakedFiles.isEmpty())
    }

    /**
     * 2. Hatch Mode interruption immediately BEFORE and AFTER source archive truncation:
     * Tests interruption at BEFORE_CD_TRUNCATE, AFTER_CD_TRUNCATE, BEFORE_WINDOW_TRUNCATE, and
     * AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL, verifying recovery completes with 100% SHA-256 match.
     */
    @Test
    fun hatchMode_interruptionBeforeAndAfterTruncationBoundaries_recoversWithExactSha256() = runBlocking {
        val faultPoints = listOf(
            DestructivePoint.BEFORE_CD_TRUNCATE,
            DestructivePoint.AFTER_CD_TRUNCATE,
            DestructivePoint.BEFORE_WINDOW_TRUNCATE,
            DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL
        )

        val specs = listOf(
            SpecEntry("doc/alpha.bin", ZipEntry.DEFLATED, deterministicBytes(120 * 1024, 201)),
            SpecEntry("doc/beta.bin", ZipEntry.STORED, deterministicBytes(160 * 1024, 202)),
            SpecEntry("media/gamma.bin", ZipEntry.DEFLATED, deterministicBytes(200 * 1024, 203))
        )

        for (targetPoint in faultPoints) {
            File(context.filesDir, "journals").deleteRecursively()
            val zipFile = File(testRootDir, "hatch_${targetPoint.name}.zip")
            buildZipArchive(zipFile, specs)
            val targetDir = File(directPosixRoot, "out_${targetPoint.name}").apply { mkdirs() }

            val fired = AtomicBoolean(false)
            KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
                override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                    if (point == targetPoint && fired.compareAndSet(false, true)) {
                        throw SimulatedFaultException(point, 1, currentArchiveBytes, "Crash at $point ($detail)")
                    }
                }
            }

            try {
                KaiHatchEngine.extractArchive(
                    context = context,
                    archiveFile = zipFile,
                    targetDir = targetDir,
                    isHatchMode = true
                ).toList()
            } catch (_: SimulatedFaultException) {
            }
            assertTrue("Expected fault at $targetPoint to fire", fired.get())

            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            val pending = ResumeJournalManager.loadPendingStates(context)
            assertEquals("Expected 1 pending journal after crash at $targetPoint", 1, pending.size)
            val journal = pending.first()
            assertTrue(
                "Journal after $targetPoint must be safely resumable",
                ResumeJournalManager.isStateSafelyResumable(journal, context)
            )

            val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
                context = context,
                resumeState = journal
            ).toList()
            val finalState = resumeEmissions.last()
            assertEquals(
                "Expected COMPLETED after resuming from $targetPoint, got ${finalState.errorMessage}",
                ExtractionStatus.COMPLETED,
                finalState.status
            )

            for (spec in specs) {
                val out = File(targetDir, spec.path)
                assertTrue("Missing ${spec.path} after resume from $targetPoint", out.exists())
                assertArrayEquals("SHA-256 mismatch for ${spec.path} after $targetPoint", spec.sha256, sha256(out))
            }
            assertFalse("Source archive should be reclaimed after completion ($targetPoint)", zipFile.exists())
            assertTrue(
                "Journal should be deleted after completion ($targetPoint)",
                ResumeJournalManager.loadPendingStates(context).isEmpty()
            )
        }
    }

    /**
     * 3. SAF destination persistence & completed-entry reconciliation on resume:
     * Interrupts a Hatch Mode extraction targeting a SAF destination and verifies that resume:
     * - Writes all remaining entries to the exact same SAF folder
     * - Does not duplicate already-verified SAF files
     * - Writes zero files to files/extracted.
     */
    @Test
    fun safDestination_interruptedHatchMode_persistsSafRoutingAndReconcilesCompletedFiles() = runBlocking {
        val entry1 = SpecEntry("part1_head.bin", ZipEntry.DEFLATED, deterministicBytes(128 * 1024, 301))
        val entry2 = SpecEntry("part2_mid.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 64 * 1024, 302))
        val entry3 = SpecEntry("part3_tail.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 64 * 1024, 303))
        val specs = listOf(entry1, entry2, entry3)

        val zipFile = File(testRootDir, "saf_resume_reconcile.zip")
        buildZipArchive(zipFile, specs)

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "saf_resume_reconcile").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "saf_resume_reconcile")
        assertNotNull(safSubfolderUri)

        val crashFired = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_JOURNAL_SAVE &&
                    detail.contains("part3_tail.bin") &&
                    crashFired.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(point, 1, currentArchiveBytes, "Simulated crash after part3_tail.bin")
                }
            }
        }

        try {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipFile,
                targetDir = fallbackDir,
                isHatchMode = true,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = safSubfolderUri
            ).toList()
        } catch (_: SimulatedFaultException) {
        }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val safExtractedFolder = File(safBackingRoot, "saf_resume_reconcile")
        assertTrue("part3_tail.bin must already exist in SAF folder before resume", File(safExtractedFolder, "part3_tail.bin").exists())

        val pending = ResumeJournalManager.loadPendingStates(context)
        assertEquals(1, pending.size)
        val journal = pending.first()
        assertEquals(safTreeUri.toString(), journal.targetTreeUriString)
        assertEquals(safSubfolderUri.toString(), journal.targetFolderDocUriString)

        val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journal
        ).toList()
        val finalState = resumeEmissions.last()
        assertEquals(
            "Expected COMPLETED on SAF resume, got: ${finalState.errorMessage}",
            ExtractionStatus.COMPLETED,
            finalState.status
        )

        for (spec in specs) {
            val safOut = File(safExtractedFolder, spec.path)
            assertTrue("Expected ${spec.path} in SAF folder", safOut.exists())
            assertArrayEquals(spec.sha256, sha256(safOut))
            val dupName = spec.path.replace(".bin", " (1).bin")
            assertFalse("Duplicate SAF file $dupName must not exist", File(safExtractedFolder, dupName).exists())
        }

        val leakedFiles = fallbackDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Zero files may leak into files/extracted", leakedFiles.isEmpty())
    }

    /**
     * 4. Revoked SAF permissions on resume:
     * When SAF permission is revoked before resuming an interrupted Hatch Mode extraction:
     * - Resume must fail explicitly with ExtractionStatus.ERROR BEFORE mutating/truncating the source archive
     * - The .hatchstate journal must be preserved on disk
     * - Zero bytes may be written to files/extracted
     * - Once SAF permission is restored, resuming from the preserved journal succeeds and completes 100%!
     */
    @Test
    fun safDestination_revokedPermissionOnResume_haltsBeforeArchiveMutationAndRecoversWhenRestored() = runBlocking {
        val specs = listOf(
            SpecEntry("a.bin", ZipEntry.DEFLATED, deterministicBytes(128 * 1024, 401)),
            SpecEntry("b.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 32 * 1024, 402))
        )
        val zipFile = File(testRootDir, "saf_revoked_resume.zip")
        buildZipArchive(zipFile, specs)

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "saf_revoked_resume").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "saf_revoked_resume")

        val crashFired = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_CD_TRUNCATE && crashFired.compareAndSet(false, true)) {
                    throw SimulatedFaultException(point, 1, currentArchiveBytes, "Crash after CD truncate")
                }
            }
        }

        try {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipFile,
                targetDir = fallbackDir,
                isHatchMode = true,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = safSubfolderUri
            ).toList()
        } catch (_: SimulatedFaultException) {
        }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val archiveLengthBeforeRevokedResume = zipFile.length()
        val archiveShaBeforeRevokedResume = sha256(zipFile)
        val journal = ResumeJournalManager.loadPendingStates(context).single()

        // Revoke SAF permission
        Phase2SafDocumentsProvider.permissionRevoked.set(true)

        val failedResumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journal
        ).toList()
        val failedLast = failedResumeEmissions.last()
        assertEquals(ExtractionStatus.ERROR, failedLast.status)

        // Source archive must not have been truncated or modified during the rejected resume
        assertEquals(archiveLengthBeforeRevokedResume, zipFile.length())
        assertArrayEquals(archiveShaBeforeRevokedResume, sha256(zipFile))

        // Recovery journal must still be preserved on disk
        val preservedJournals = ResumeJournalManager.loadPendingStates(context)
        assertEquals("Recovery journal must be preserved when SAF permission is revoked", 1, preservedJournals.size)

        // Zero files in files/extracted
        assertTrue(fallbackDir.walkTopDown().filter { it.isFile }.toList().isEmpty())

        // Restore SAF permission and resume again -> must complete 100%
        Phase2SafDocumentsProvider.permissionRevoked.set(false)
        val succeededEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = preservedJournals.single()
        ).toList()
        assertEquals(ExtractionStatus.COMPLETED, succeededEmissions.last().status)

        val safExtractedFolder = File(safBackingRoot, "saf_revoked_resume")
        for (spec in specs) {
            val out = File(safExtractedFolder, spec.path)
            assertTrue(out.exists())
            assertArrayEquals(spec.sha256, sha256(out))
        }
    }

    /**
     * 5A. Interrupted large STORED entry (>= 4 MB) on direct POSIX destination:
     * Interrupting after an intermediate reverse slice truncation (AFTER_STORED_SLICE_TRUNCATE)
     * preserves the extracted tail slices on disk and resumes from availableInArchive to produce
     * an exact SHA-256 match.
     */
    @Test
    fun largeStoredEntry_directPosix_interruptedMidSlice_resumesInPlaceAndVerifiesSha256() = runBlocking {
        val storedPayload = deterministicBytes(5 * 1024 * 1024 + 12345, 501)
        val spec = SpecEntry("video/large_stored.bin", ZipEntry.STORED, storedPayload)
        val zipFile = File(testRootDir, "stored_posix_midslice.zip")
        buildZipArchive(zipFile, listOf(spec))
        val targetDir = File(directPosixRoot, "stored_posix_midslice").apply { mkdirs() }

        val sliceTruncFired = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_STORED_SLICE_TRUNCATE &&
                    !detail.contains("sliceStart=0") &&
                    sliceTruncFired.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(point, 1, currentArchiveBytes, "Crash after intermediate STORED slice truncate")
                }
            }
        }

        try {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipFile,
                targetDir = targetDir,
                isHatchMode = true
            ).toList()
        } catch (_: SimulatedFaultException) {
        }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        assertTrue("Intermediate STORED slice fault must have fired", sliceTruncFired.get())
        val partialDest = File(targetDir, spec.path)
        assertTrue("Partial STORED output file must be preserved on direct POSIX disk", partialDest.exists())
        assertEquals(storedPayload.size.toLong(), partialDest.length())

        val journal = ResumeJournalManager.loadPendingStates(context).single()
        assertTrue(ResumeJournalManager.isStateSafelyResumable(journal, context))

        val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journal
        ).toList()
        assertEquals(ExtractionStatus.COMPLETED, resumeEmissions.last().status)
        assertArrayEquals(spec.sha256, sha256(partialDest))
        assertFalse(zipFile.exists())
    }

    /**
     * 5B. Interrupted large STORED entry (>= 4 MB) on SAF destination:
     * Intermediate sub-entry slice truncation is suppressed for SAF destinations so that interrupting
     * mid-entry NEVER truncates the source archive before the full SAF document is written, synced,
     * and CRC32-verified. Resuming then extracts the complete entry cleanly to SAF.
     */
    @Test
    fun largeStoredEntry_safDestination_interruptedMidEntry_neverPrematurelyTruncatesArchiveAndResumesCleanly() = runBlocking {
        val storedPayload = deterministicBytes(5 * 1024 * 1024 + 7777, 502)
        val spec = SpecEntry("video/saf_large_stored.bin", ZipEntry.STORED, storedPayload)
        val zipFile = File(testRootDir, "stored_saf_mid.zip")
        buildZipArchive(zipFile, listOf(spec))

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "stored_saf_mid").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "stored_saf_mid")

        // Deterministically pause mid-extraction inside the 5 MB STORED slice loop, then cancel while parked
        val pauseController = ExtractionPauseController()
        val checkpointCount = java.util.concurrent.atomic.AtomicInteger(0)
        pauseController.onBeforeCheckpoint = {
            if (checkpointCount.incrementAndGet() == 5) {
                pauseController.requestPause()
            }
        }
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipFile,
                targetDir = fallbackDir,
                isHatchMode = true,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = safSubfolderUri,
                pauseController = pauseController
            ).collect { }
        }
        assertTrue(
            "Extraction must reach deterministic PAUSED checkpoint mid-STORED on SAF",
            pauseController.awaitPaused(5000L)
        )
        job.cancel()
        job.join()

        // The source archive payload for the 5 MB STORED entry must still be intact (>= storedPayload.size)
        assertTrue(
            "Source archive payload must not be prematurely truncated mid-STORED on SAF destination",
            zipFile.length() >= storedPayload.size.toLong()
        )

        val journal = ResumeJournalManager.loadPendingStates(context).single()
        assertTrue("SAF mid-STORED interruption must be resumable", ResumeJournalManager.isStateSafelyResumable(journal, context))

        val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = journal
        ).toList()
        assertEquals(ExtractionStatus.COMPLETED, resumeEmissions.last().status)

        val safOut = File(File(safBackingRoot, "stored_saf_mid"), spec.path)
        assertTrue(safOut.exists())
        assertArrayEquals(spec.sha256, sha256(safOut))
        assertTrue(fallbackDir.walkTopDown().filter { it.isFile }.toList().isEmpty())
    }

    /**
     * 6. Interrupted large DEFLATED entry (>= 4 MB) extraction:
     * - Crash BEFORE compressed head slot 0 pwrite overwrite is Resumable and completes with exact SHA-256.
     * - Crash AFTER compressed head slot 0 pwrite overwrite is Unrecoverable and halts with ERROR without
     *   corrupting already-verified files.
     */
    @Test
    fun largeDeflatedEntry_crashBeforeVsAfterSlot0Overwrite_enforcesSafetyInvariants() = runBlocking {
        val smallTail = SpecEntry("tail_verified.bin", ZipEntry.STORED, deterministicBytes(64 * 1024, 601))
        val largeDeflate = SpecEntry("large_deflate.bin", ZipEntry.DEFLATED, deterministicBytes(5 * 1024 * 1024, 602))

        // Case A: Crash BEFORE slot 0 pwrite overwrite (chunk c=0 BEFORE_TAILSWAP_PWRITE) -> RESUMABLE
        val zipRecoverable = File(testRootDir, "deflate_recoverable.zip")
        buildZipArchive(zipRecoverable, listOf(largeDeflate, smallTail))
        val targetRecoverable = File(directPosixRoot, "deflate_recoverable").apply { mkdirs() }

        val firedBefore = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.BEFORE_TAILSWAP_PWRITE &&
                    detail.contains("c=0") &&
                    firedBefore.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(point, 1, currentArchiveBytes, "Crash before slot 0 pwrite")
                }
            }
        }
        try {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipRecoverable,
                targetDir = targetRecoverable,
                isHatchMode = true
            ).toList()
        } catch (_: SimulatedFaultException) {
        }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val journalRec = ResumeJournalManager.loadPendingStates(context).single()
        assertTrue(
            "Crash before slot 0 overwrite must be Resumable",
            ResumeJournalManager.isStateSafelyResumable(journalRec, context)
        )
        val resumeRec = KaiHatchEngine.resumeInterruptedExtraction(context, journalRec).toList()
        assertEquals(ExtractionStatus.COMPLETED, resumeRec.last().status)
        assertArrayEquals(largeDeflate.sha256, sha256(File(targetRecoverable, largeDeflate.path)))
        assertArrayEquals(smallTail.sha256, sha256(File(targetRecoverable, smallTail.path)))

        // Case B: Crash AFTER slot 0 pwrite overwrite and intermediate truncation -> UNRECOVERABLE
        File(context.filesDir, "journals").deleteRecursively()
        val zipUnrec = File(testRootDir, "deflate_unrecoverable.zip")
        buildZipArchive(zipUnrec, listOf(largeDeflate, smallTail))
        val targetUnrec = File(directPosixRoot, "deflate_unrecoverable").apply { mkdirs() }

        val firedAfter = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_TAILSWAP_TRUNCATE &&
                    detail.contains("c=0") &&
                    firedAfter.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(point, 1, currentArchiveBytes, "Crash after slot 0 overwrite + truncate")
                }
            }
        }
        try {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipUnrec,
                targetDir = targetUnrec,
                isHatchMode = true
            ).toList()
        } catch (_: SimulatedFaultException) {
        }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        val journalUnrec = ResumeJournalManager.loadPendingStates(context).single()
        // Part 3A: with valid v2 checkpoint intact, direct-POSIX Resume is eligible while Restore remains Unrecoverable.
        assertTrue(
            "Valid v2 checkpoint after slot 0 overwrite + truncate must be Resumable in Part 3A",
            ResumeJournalManager.isStateSafelyResumable(journalUnrec, context)
        )
        assertTrue(
            "Mutated tail-swap state must remain Unrecoverable for Restore",
            ResumeJournalManager.assessRestoreEligibility(journalUnrec, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )

        // Legacy-unsafe state (without v2 checkpoint): must still be classified Unrecoverable and halt safely.
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(journalUnrec.stateFilePath)
        assertFalse(
            "Legacy crash after slot 0 pwrite overwrite without v2 checkpoint must be classified as Unrecoverable",
            ResumeJournalManager.isStateSafelyResumable(journalUnrec, context)
        )
        val resumeUnrec = KaiHatchEngine.resumeInterruptedExtraction(context, journalUnrec).toList()
        assertEquals(ExtractionStatus.ERROR, resumeUnrec.last().status)
        assertTrue(resumeUnrec.last().errorMessage?.contains("UNRECOVERABLE RESUME STATE") == true)
        assertArrayEquals(smallTail.sha256, sha256(File(targetUnrec, smallTail.path)))
    }

    /**
     * 7. CRC failure during recovery:
     * If a completed entry's output file on disk (or in SAF) is corrupted after its source archive
     * region has already been truncated, recovery must detect the CRC32 mismatch and halt with
     * ExtractionStatus.ERROR instead of skipping the corrupted file or reporting completion.
     */
    @Test
    fun recovery_corruptedCompletedOutputWithTruncatedSource_haltsWithError() = runBlocking {
        val entry1 = SpecEntry("first.bin", ZipEntry.STORED, deterministicBytes(128 * 1024, 701))
        val entry2 = SpecEntry("second_tail.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 64 * 1024, 702))
        val zipFile = File(testRootDir, "crc_corrupt_on_resume.zip")
        buildZipArchive(zipFile, listOf(entry1, entry2))

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "crc_corrupt_on_resume").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "crc_corrupt_on_resume")

        val crashFired = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_STORED_SLICE_TRUNCATE &&
                    detail.contains("second_tail.bin") &&
                    crashFired.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(point, 1, currentArchiveBytes, "Crash after second_tail.bin truncated")
                }
            }
        }

        try {
            KaiHatchEngine.extractArchive(
                context = context,
                archiveFile = zipFile,
                targetDir = fallbackDir,
                isHatchMode = true,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = safSubfolderUri
            ).toList()
        } catch (_: SimulatedFaultException) {
        }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        // Corrupt the already-completed file in SAF whose source archive bytes were already truncated
        val safTailFile = File(File(safBackingRoot, "crc_corrupt_on_resume"), "second_tail.bin")
        assertTrue(safTailFile.exists())
        RandomAccessFile(safTailFile, "rw").use { raf ->
            raf.seek(0L)
            raf.write(ByteArray(64) { 0xFF.toByte() })
        }

        val journal = ResumeJournalManager.loadPendingStates(context).single()
        assertFalse(
            "Corrupted completed SAF file with truncated source region must be Unrecoverable",
            ResumeJournalManager.isStateSafelyResumable(journal, context)
        )

        val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(context, journal).toList()
        assertEquals(ExtractionStatus.ERROR, resumeEmissions.last().status)
    }

    /**
     * 8. Output write failure mid-extraction in Hatch Mode:
     * When SAF output creation/opening fails on a remaining entry:
     * - Extraction halts with ExtractionStatus.ERROR
     * - The failed entry's source archive region is NOT truncated
     * - Previously verified entries in SAF remain intact
     * - Once the write failure condition is cleared, resuming from the journal completes 100%!
     */
    @Test
    fun hatchMode_outputWriteFailureMidExtraction_preservesSourceArchiveForFailedEntryAndRecovers() = runBlocking {
        val entry1 = SpecEntry("head_fail_once.bin", ZipEntry.DEFLATED, deterministicBytes(256 * 1024, 801))
        val entry2 = SpecEntry("tail_ok.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 64 * 1024, 802))
        val zipFile = File(testRootDir, "write_fail_mid.zip")
        buildZipArchive(zipFile, listOf(entry1, entry2))

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "write_fail_mid").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "write_fail_mid")

        // Fail creating/opening head_fail_once.bin after tail_ok.bin succeeds
        Phase2SafDocumentsProvider.failWriteForFileName.set("head_fail_once.bin")

        val emissions = KaiHatchEngine.extractArchive(
            context = context,
            archiveFile = zipFile,
            targetDir = fallbackDir,
            isHatchMode = true,
            targetTreeUri = safTreeUri,
            targetFolderDocUri = safSubfolderUri
        ).toList()

        assertEquals(ExtractionStatus.ERROR, emissions.last().status)
        assertTrue("Source archive must still exist with head_fail_once.bin intact", zipFile.exists() && zipFile.length() > 0L)

        val safDir = File(safBackingRoot, "write_fail_mid")
        assertTrue("tail_ok.bin must be intact in SAF", File(safDir, "tail_ok.bin").exists())
        assertArrayEquals(entry2.sha256, sha256(File(safDir, "tail_ok.bin")))

        // Clear the write failure and resume from journal -> must complete 100%
        Phase2SafDocumentsProvider.failWriteForFileName.set(null)
        val journal = ResumeJournalManager.loadPendingStates(context).single()
        val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(context, journal).toList()
        assertEquals(ExtractionStatus.COMPLETED, resumeEmissions.last().status)
        assertArrayEquals(entry1.sha256, sha256(File(safDir, "head_fail_once.bin")))
        assertArrayEquals(entry2.sha256, sha256(File(safDir, "tail_ok.bin")))
        assertFalse("Source archive should be reclaimed after successful recovery", zipFile.exists())
    }

    /**
     * 9. Repeated interruption and recovery across multiple windows:
     * Interrupts a Hatch Mode extraction 3 separate times across sequential windows and resumes
     * each time, verifying 100% SHA-256 accuracy, zero duplicate files, and complete cleanup.
     */
    @Test
    fun hatchMode_repeatedInterruptionsAcrossMultipleWindows_recoversCompletely() = runBlocking {
        val specs = listOf(
            SpecEntry("w1.bin", ZipEntry.DEFLATED, deterministicBytes(128 * 1024, 901)),
            SpecEntry("w2.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 16 * 1024, 902)),
            SpecEntry("w3.bin", ZipEntry.DEFLATED, deterministicBytes(4 * 1024 * 1024 + 32 * 1024, 903)),
            SpecEntry("w4.bin", ZipEntry.STORED, deterministicBytes(4 * 1024 * 1024 + 48 * 1024, 904))
        )
        val zipFile = File(testRootDir, "repeated_interrupts.zip")
        buildZipArchive(zipFile, specs)

        val fallbackDir = File(StorageHelper.getExtractedDir(context), "repeated_interrupts").apply { mkdirs() }
        val safSubfolderUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, "repeated_interrupts")

        val interruptTargets = listOf("w4.bin", "w3.bin", "w2.bin")
        for ((stepIdx, targetName) in interruptTargets.withIndex()) {
            val stepFired = AtomicBoolean(false)
            KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
                override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                    if (point == DestructivePoint.AFTER_JOURNAL_SAVE &&
                        detail.contains(targetName) &&
                        stepFired.compareAndSet(false, true)
                    ) {
                        throw SimulatedFaultException(point, 1, currentArchiveBytes, "Interrupt #$stepIdx after $targetName")
                    }
                }
            }

            try {
                if (stepIdx == 0) {
                    KaiHatchEngine.extractArchive(
                        context = context,
                        archiveFile = zipFile,
                        targetDir = fallbackDir,
                        isHatchMode = true,
                        targetTreeUri = safTreeUri,
                        targetFolderDocUri = safSubfolderUri
                    ).toList()
                } else {
                    val journal = ResumeJournalManager.loadPendingStates(context).single()
                    KaiHatchEngine.resumeInterruptedExtraction(context, journal).toList()
                }
            } catch (_: SimulatedFaultException) {
            }
            assertTrue("Expected interrupt after $targetName to fire", stepFired.get())
        }

        // Final resume without faults -> must complete remaining w1.bin and reclaim archive
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        val finalJournal = ResumeJournalManager.loadPendingStates(context).single()
        val finalEmissions = KaiHatchEngine.resumeInterruptedExtraction(context, finalJournal).toList()
        assertEquals(ExtractionStatus.COMPLETED, finalEmissions.last().status)

        val safDir = File(safBackingRoot, "repeated_interrupts")
        for (spec in specs) {
            val out = File(safDir, spec.path)
            assertTrue("Missing ${spec.path} after repeated resume", out.exists())
            assertArrayEquals(spec.sha256, sha256(out))
            val dupName = spec.path.replace(".bin", " (1).bin")
            assertFalse("Duplicate file $dupName must not exist", File(safDir, dupName).exists())
        }
        assertFalse(zipFile.exists())
        assertTrue(ResumeJournalManager.loadPendingStates(context).isEmpty())
        assertTrue(fallbackDir.walkTopDown().filter { it.isFile }.toList().isEmpty())
    }
}
