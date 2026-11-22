package com.biplov.kaihatch.engine

import android.app.Application
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
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.VerificationLevel
import com.biplov.kaihatch.model.VerificationStatus
import com.biplov.kaihatch.ui.HatchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticZipRestorationTest {

    companion object {
        const val RESTORATION_SAF_AUTHORITY = "com.biplov.kaihatch.test.saf.restoration"
    }

    class RestorationTestSafDocumentsProvider : android.content.ContentProvider() {
        companion object {
            lateinit var backingRootDir: File
            val docIdToFile = ConcurrentHashMap<String, File>()
            private var nextId = 1

            @Synchronized
            fun reset(root: File) {
                backingRootDir = root
                docIdToFile.clear()
                nextId = 1
                docIdToFile["p3_tree_root"] = root
            }

            @Synchronized
            fun registerFile(file: File): String {
                val existing = docIdToFile.entries.firstOrNull { it.value.absolutePath == file.absolutePath }?.key
                if (existing != null) return existing
                val id = "p3_doc_${nextId++}"
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
                val parentDocId = if (childrenIdx > 0) Uri.decode(segments[childrenIdx - 1]) else "p3_tree_root"
                val parentDir = docIdToFile[parentDocId] ?: return cursor
                val children = parentDir.listFiles() ?: emptyArray()
                for (child in children) {
                    val childId = registerFile(child)
                    cursor.addRow(buildRow(cols, childId, child))
                }
            } else {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: "p3_tree_root"
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
            if (extras == null) return null
            @Suppress("DEPRECATION")
            val targetUri = extras.getParcelable<Uri>("uri") ?: return null
            return when {
                method.contains("createDocument") -> {
                    val mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
                    val displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return null
                    val parentDocId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull()
                        ?: runCatching { DocumentsContract.getTreeDocumentId(targetUri) }.getOrNull()
                        ?: "p3_tree_root"
                    val parentDir = docIdToFile[parentDocId] ?: return null
                    if (!parentDir.exists()) parentDir.mkdirs()

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
                        DocumentsContract.buildDocumentUri(RESTORATION_SAF_AUTHORITY, newDocId)
                    }
                    Bundle().apply { putParcelable("uri", docUri) }
                }
                method.contains("deleteDocument") -> {
                    val docId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull() ?: return null
                    val file = docIdToFile[docId]
                    val deleted = file?.deleteRecursively() ?: false
                    docIdToFile.remove(docId)
                    Bundle().apply { putBoolean("result", deleted) }
                }
                else -> super.call(method, arg, extras)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val file = docIdToFile[docId] ?: return null
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

    private fun deterministicBytes(seed: Long, size: Int): ByteArray {
        val rng = Random(seed)
        return ByteArray(size).also { rng.nextBytes(it) }
    }

    private fun compressibleBytes(seed: Long, size: Int): ByteArray {
        val rng = Random(seed)
        val pattern = ByteArray(256).also { rng.nextBytes(it) }
        val out = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val copyLen = minOf(pattern.size, size - pos)
            System.arraycopy(pattern, 0, out, pos, copyLen)
            if (pos % 1024 == 0 && pos < size) {
                out[pos] = (pos and 0xFF).toByte()
            }
            pos += copyLen
        }
        return out
    }

    private fun buildZipArchive(
        targetFile: File,
        entries: List<Triple<String, ByteArray, Int>>, // path, bytes, method (ZipEntry.STORED or DEFLATED)
        zip64Mode: Zip64Mode = Zip64Mode.AsNeeded
    ) {
        ZipArchiveOutputStream(targetFile).use { zos ->
            zos.setUseZip64(zip64Mode)
            for ((path, payload, method) in entries) {
                val zae = ZipArchiveEntry(path)
                zae.method = method
                if (method == ZipEntry.STORED) {
                    val crc = CRC32().apply { update(payload) }.value
                    zae.size = payload.size.toLong()
                    zae.compressedSize = payload.size.toLong()
                    zae.crc = crc
                }
                zos.putArchiveEntry(zae)
                zos.write(payload)
                zos.closeArchiveEntry()
            }
        }
    }

    @Test
    fun stopBeforeAnyBytesReclaimedStopsNormallyWithoutRewritingZip() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_no_reclaim_${System.nanoTime()}").apply { mkdirs() }
        try {
            val e1 = deterministicBytes(101L, 64 * 1024)
            val e2 = deterministicBytes(102L, 64 * 1024)
            val zipFile = File(testDir, "untouched.zip")
            buildZipArchive(
                zipFile,
                listOf(
                    Triple("first.bin", e1, ZipEntry.DEFLATED),
                    Triple("second.bin", e2, ZipEntry.STORED)
                )
            )
            val originalZipBytes = zipFile.readBytes()
            val outDir = File(testDir, "out").apply { mkdirs() }

            val pauseController = ExtractionPauseController()
            var cancelledAtFirstCheckpoint = false
            pauseController.onBeforeCheckpoint = {
                if (!cancelledAtFirstCheckpoint) {
                    cancelledAtFirstCheckpoint = true
                    pauseController.cancel()
                }
            }

            val emissions = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir,
                isHatchMode = true,
                context = app,
                pauseController = pauseController
            ).toList()

            val last = emissions.last()
            assertEquals(ExtractionStatus.CANCELLED, last.status)
            assertEquals(0L, last.bytesReclaimed)
            assertArrayEquals(
                "Original ZIP must remain byte-for-byte identical when no bytes were reclaimed",
                originalZipBytes,
                zipFile.readBytes()
            )
            val pending = ResumeJournalManager.loadPendingStates(app)
            assertTrue(
                "Recovery journal must be cleaned up when no archive bytes were reclaimed",
                pending.none { it.archivePath == zipFile.absolutePath }
            )
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun stopMidExtractionRestoresValidZipInPlaceAndPreservesExtractedFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_stop_mid_${System.nanoTime()}").apply { mkdirs() }
        try {
            val entries = listOf(
                Triple("01_head_stored.bin", deterministicBytes(201L, 128 * 1024), ZipEntry.STORED),
                Triple("02_head_deflated.bin", compressibleBytes(202L, 192 * 1024), ZipEntry.DEFLATED),
                Triple("03_mid_stored.bin", deterministicBytes(203L, 96 * 1024), ZipEntry.STORED),
                Triple("04_tail_deflated.bin", compressibleBytes(204L, 160 * 1024), ZipEntry.DEFLATED),
                Triple("05_tail_stored.bin", deterministicBytes(205L, 128 * 1024), ZipEntry.STORED)
            )
            val zipFile = File(testDir, "multi_stage.zip")
            buildZipArchive(zipFile, entries)
            val initialSize = zipFile.length()
            val outDir1 = File(testDir, "out_first_run").apply { mkdirs() }

            val pauseController = ExtractionPauseController()
            val stopTriggered = AtomicBoolean(false)
            val emissions = CopyOnWriteArrayList<ExtractionProgress>()

            KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir1,
                isHatchMode = true,
                context = app,
                pauseController = pauseController
            ).collect { progress ->
                emissions.add(progress)
                if (progress.completedEntries >= 2 && stopTriggered.compareAndSet(false, true)) {
                    pauseController.cancel()
                }
            }

            assertTrue(
                "Must emit FINALIZING ('Finalizing remaining archive…') during cooperative stop",
                emissions.any {
                    it.status == ExtractionStatus.FINALIZING &&
                        it.currentFileName.contains("Finalizing remaining archive")
                }
            )
            val terminal = emissions.last()
            assertEquals("Terminal status must be CANCELLED after successful restoration", ExtractionStatus.CANCELLED, terminal.status)

            // No second ZIP file created in testDir
            val zipFilesInDir = testDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }.orEmpty()
            assertEquals("Must restore in the original file without creating a second ZIP", 1, zipFilesInDir.size)
            assertEquals(zipFile.absolutePath, zipFilesInDir.single().absolutePath)
            assertTrue("Restored ZIP must be smaller than original archive", zipFile.length() < initialSize)

            // Recovery journal must be cleaned up after verified restoration
            val pendingAfterStop = ResumeJournalManager.loadPendingStates(app)
            assertTrue(
                "Recovery journal must be deleted after verified restoration",
                pendingAfterStop.none { it.archivePath == zipFile.absolutePath }
            )

            // Verify the restored ZIP passes FULL_VERIFY (EOCD + Central Directory + Local Headers + CRC32)
            val verifyStates = ArchiveVerifierEngine.verifyArchive(
                archiveFile = zipFile,
                level = VerificationLevel.FULL_VERIFY,
                context = app
            ).toList()
            assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, verifyStates.last().status)

            // Extract the restored remaining ZIP to a second output directory and verify all 5 files across both runs!
            val outDir2 = File(testDir, "out_second_run").apply { mkdirs() }
            val secondRun = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir2,
                isHatchMode = false,
                context = app
            ).toList()
            assertEquals(ExtractionStatus.COMPLETED, secondRun.last().status)

            for ((path, expectedBytes, _) in entries) {
                val f1 = File(outDir1, path)
                val f2 = File(outDir2, path)
                assertTrue(
                    "Entry $path must exist either in first run output or in restored ZIP extraction",
                    f1.exists() || f2.exists()
                )
                if (f1.exists()) {
                    assertArrayEquals("Extracted file $path in run 1 must match original bytes", expectedBytes, f1.readBytes())
                }
                if (f2.exists()) {
                    assertArrayEquals("Restored ZIP entry $path in run 2 must match original bytes", expectedBytes, f2.readBytes())
                }
            }
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun stopDuringLargeStoredAndDeflatedEntriesCompletesSafeBoundaryAndRestoresZip64() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_large_zip64_${System.nanoTime()}").apply { mkdirs() }
        try {
            val head1 = deterministicBytes(301L, 128 * 1024)
            val head2 = compressibleBytes(302L, 256 * 1024)
            val largeTailStored = deterministicBytes(303L, 5 * 1024 * 1024) // >= 4 MB triggers sub-entry slice truncation
            val zipFile = File(testDir, "large_zip64.zip")
            buildZipArchive(
                targetFile = zipFile,
                entries = listOf(
                    Triple("01_head.bin", head1, ZipEntry.STORED),
                    Triple("02_head_deflate.bin", head2, ZipEntry.DEFLATED),
                    Triple("03_large_tail.bin", largeTailStored, ZipEntry.STORED)
                ),
                zip64Mode = Zip64Mode.Always
            )

            val outDir = File(testDir, "out").apply { mkdirs() }
            val pauseController = ExtractionPauseController()
            val stopRequested = AtomicBoolean(false)
            val statuses = CopyOnWriteArrayList<ExtractionStatus>()

            // Trigger cooperative stop right after the first intermediate slice of 03_large_tail.bin is truncated
            val injector = RecordingFaultInjector(
                onStepCallback = { point, _, _ ->
                    if (point == DestructivePoint.AFTER_STORED_SLICE_TRUNCATE &&
                        stopRequested.compareAndSet(false, true)
                    ) {
                        pauseController.cancel()
                    }
                }
            )

            KaiHatchEngine.faultInjector = injector
            try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipFile,
                    targetDir = outDir,
                    isHatchMode = true,
                    context = app,
                    pauseController = pauseController
                ).collect { progress ->
                    statuses.add(progress.status)
                }
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }

            assertTrue("Must transition through FINALIZING", statuses.contains(ExtractionStatus.FINALIZING))
            assertEquals("Must finish with CANCELLED after restoring remaining ZIP64", ExtractionStatus.CANCELLED, statuses.last())

            // 03_large_tail.bin had already mutated the archive when Stop was requested, so it must finish to its safe boundary
            val extractedLarge = File(outDir, "03_large_tail.bin")
            assertTrue("In-flight mutated large STORED entry must complete cleanly to safe boundary", extractedLarge.exists())
            assertArrayEquals(largeTailStored, extractedLarge.readBytes())

            // Remaining 2 head entries must be intact inside the restored ZIP64 archive
            val parsedRemaining = RandomAccessFile(zipFile, "r").use { raf ->
                ZipParser.parseZipChannel(raf.channel)
            }
            assertEquals(2, parsedRemaining.size)
            assertTrue("Restored archive must preserve ZIP64 metadata", parsedRemaining.all { it.isZip64 })

            val verifyStates = ArchiveVerifierEngine.verifyArchive(
                archiveFile = zipFile,
                level = VerificationLevel.FULL_VERIFY,
                context = app
            ).toList()
            assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, verifyStates.last().status)

            val outDirRestored = File(testDir, "out_restored").apply { mkdirs() }
            val extractRestored = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDirRestored,
                isHatchMode = true,
                context = app
            ).toList()
            assertEquals(ExtractionStatus.COMPLETED, extractRestored.last().status)
            assertArrayEquals(head1, File(outDirRestored, "01_head.bin").readBytes())
            assertArrayEquals(head2, File(outDirRestored, "02_head_deflate.bin").readBytes())
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun crashRecoveryAndInterruptedRestorationCheckpointsRetryIdempotently() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_crash_restore_${System.nanoTime()}").apply { mkdirs() }
        try {
            val entries = listOf(
                Triple("alpha.bin", deterministicBytes(401L, 90 * 1024), ZipEntry.STORED),
                Triple("beta.bin", compressibleBytes(402L, 140 * 1024), ZipEntry.DEFLATED),
                Triple("gamma.bin", deterministicBytes(403L, 110 * 1024), ZipEntry.STORED),
                Triple("delta.bin", compressibleBytes(404L, 130 * 1024), ZipEntry.DEFLATED)
            )
            val zipFile = File(testDir, "crash_recovery.zip")
            buildZipArchive(zipFile, entries)
            val outDir = File(testDir, "out").apply { mkdirs() }

            // 1. Simulate sudden process crash after first window truncation (delta.bin extracted)
            val crashExtractionInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.faultInjector = crashExtractionInjector
            val extractEmissions = try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipFile,
                    targetDir = outDir,
                    isHatchMode = true,
                    context = app
                ).toList()
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }
            assertEquals("Simulated extraction crash must emit ERROR", ExtractionStatus.ERROR, extractEmissions.last().status)
            assertTrue(
                "Simulated extraction crash point must have fired",
                crashExtractionInjector.getCount(DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL) >= 1
            )

            // 2. Verify recovery journal is preserved and eligible for BOTH Resume and Restore Remaining ZIP
            val pendingStates = ResumeJournalManager.loadPendingStates(app)
            val state = pendingStates.firstOrNull { it.archivePath == zipFile.absolutePath }
            assertNotNull("Recovery journal state must exist after crash", state)
            assertTrue("State must be safely resumable", ResumeJournalManager.isStateSafelyResumable(state!!, app))
            val restoreEligibility = ResumeJournalManager.assessRestoreEligibility(state, app)
            assertTrue(
                "State must be eligible for Restore Remaining ZIP",
                restoreEligibility is ResumeJournalManager.RestoreEligibility.Restorable
            )

            // 3. Simulate crashes at every stage of restoration and verify idempotent retry!
            val restoreFaultPoints = listOf(
                DestructivePoint.BEFORE_RESTORE_CD_WRITE,
                DestructivePoint.MID_RESTORE_CD_WRITE,
                DestructivePoint.AFTER_RESTORE_CD_WRITE_BEFORE_SYNC,
                DestructivePoint.AFTER_RESTORE_VERIFY_BEFORE_CLEANUP
            )

            for (faultPoint in restoreFaultPoints) {
                val currentState = ResumeJournalManager.loadPendingStates(app)
                    .first { it.archivePath == zipFile.absolutePath }
                val restoreFaultInjector = RecordingFaultInjector(
                    triggerPoint = faultPoint,
                    triggerOnOccurrence = 1
                )
                var restoreCrashed = false
                try {
                    KaiHatchEngine.restoreInterruptedZipArchive(
                        context = app,
                        resumeState = currentState,
                        faultInjector = restoreFaultInjector
                    ).toList()
                } catch (_: SimulatedFaultException) {
                    restoreCrashed = true
                }
                assertTrue("Simulated restoration fault at $faultPoint must trigger", restoreCrashed)
                assertTrue(
                    "Restoration checkpoint (.restore.ckpt) must exist after crash at $faultPoint",
                    ResumeJournalManager.hasPendingRestorationCheckpoint(currentState.stateFilePath)
                )
            }

            // 4. Now complete the interrupted restoration without faults
            val stateForRetry = ResumeJournalManager.loadPendingStates(app)
                .first { it.archivePath == zipFile.absolutePath }
            assertTrue("Pending state must flag hasPendingRestoration", stateForRetry.hasPendingRestoration)

            val retryEmissions = KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = stateForRetry
            ).toList()
            assertEquals(ExtractionStatus.FINALIZING, retryEmissions.first().status)
            assertEquals(ExtractionStatus.CANCELLED, retryEmissions.last().status)

            // 5. Verify recovery journal and restoration checkpoint are now cleaned up
            assertFalse(ResumeJournalManager.hasPendingRestorationCheckpoint(stateForRetry.stateFilePath))
            assertTrue(
                ResumeJournalManager.loadPendingStates(app).none { it.archivePath == zipFile.absolutePath }
            )

            // 6. Verify the restored ZIP has the 3 remaining entries (alpha, beta, gamma) and extracts cleanly
            val parsed = RandomAccessFile(zipFile, "r").use { raf ->
                ZipParser.parseZipChannel(raf.channel)
            }
            assertEquals(3, parsed.size)
            val outDirRestored = File(testDir, "out_restored").apply { mkdirs() }
            val finalExtract = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDirRestored,
                isHatchMode = false,
                context = app
            ).toList()
            assertEquals(ExtractionStatus.COMPLETED, finalExtract.last().status)
            assertArrayEquals(entries[0].second, File(outDirRestored, "alpha.bin").readBytes())
            assertArrayEquals(entries[1].second, File(outDirRestored, "beta.bin").readBytes())
            assertArrayEquals(entries[2].second, File(outDirRestored, "gamma.bin").readBytes())
            assertArrayEquals(entries[3].second, File(outDir, "delta.bin").readBytes())
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedFormatsCorruptedStatesAndInsufficientStorageSafely() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_safety_reject_${System.nanoTime()}").apply { mkdirs() }
        try {
            val e1 = deterministicBytes(501L, 64 * 1024)
            val e2 = deterministicBytes(502L, 64 * 1024)
            val zipFile = File(testDir, "safety.zip")
            buildZipArchive(
                zipFile,
                listOf(
                    Triple("head.bin", e1, ZipEntry.STORED),
                    Triple("tail.bin", e2, ZipEntry.STORED)
                )
            )
            val outDir = File(testDir, "out").apply { mkdirs() }

            // Crash after CD truncate so 2 entries remain but CD is truncated
            val crashCdInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_CD_TRUNCATE,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.faultInjector = crashCdInjector
            try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipFile,
                    targetDir = outDir,
                    isHatchMode = true,
                    context = app
                ).toList()
            } catch (_: SimulatedFaultException) {
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }

            val savedState = ResumeJournalManager.loadPendingStates(app)
                .first { it.archivePath == zipFile.absolutePath }
            val bytesBeforeReject = zipFile.readBytes()

            // 1. Insufficient disk space must be rejected before modifying a single byte of the archive
            val lowSpaceEmissions = KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = savedState,
                simulatedAvailableDiskBytes = 8L // Only 8 bytes free
            ).toList()
            assertEquals("Must report ERROR when free space is insufficient", ExtractionStatus.ERROR, lowSpaceEmissions.last().status)
            assertArrayEquals("Archive bytes must not be modified on insufficient storage", bytesBeforeReject, zipFile.readBytes())
            assertTrue(
                "Recovery journal must be preserved when restoration fails due to insufficient space",
                ResumeJournalManager.loadPendingStates(app).any { it.archivePath == zipFile.absolutePath }
            )

            // 2. Unsupported format (e.g. .7z / .tar) must be rejected without modifying the file
            val unsupportedState = savedState.copy(archiveName = "unsupported.7z")
            val eligibility7z = ResumeJournalManager.assessRestoreEligibility(unsupportedState, app)
            assertTrue(eligibility7z is ResumeJournalManager.RestoreEligibility.UnsupportedFormat)
            val unsupportedEmissions = KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = unsupportedState
            ).toList()
            assertEquals(ExtractionStatus.ERROR, unsupportedEmissions.last().status)
            assertArrayEquals("Archive bytes must not be modified for unsupported format", bytesBeforeReject, zipFile.readBytes())

            // 3. Corrupted state (truncated into remaining head entry payload) must be rejected safely
            RandomAccessFile(zipFile, "rw").use { raf ->
                raf.setLength(20L) // Truncate inside the first Local File Header
            }
            val corruptedEligibility = ResumeJournalManager.assessRestoreEligibility(savedState, app)
            assertTrue(corruptedEligibility is ResumeJournalManager.RestoreEligibility.Unrecoverable)
            val corruptedEmissions = KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = savedState
            ).toList()
            assertEquals(ExtractionStatus.ERROR, corruptedEmissions.last().status)
            assertEquals(20L, zipFile.length())

            ResumeJournalManager.deleteState(savedState.stateFilePath)
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun parallelWindowOver16EntriesStopRestoresAllUnextractedEntriesSafely() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_parallel24_${System.nanoTime()}").apply { mkdirs() }
        try {
            // 24 entries forces Case 3 (Multi-Threaded Parallel Window, maxWindowEntries in 2..6)
            val entries = (1..24).map { i ->
                val name = "item_%02d.bin".format(i)
                val payload = if (i % 2 == 0) {
                    compressibleBytes(1000L + i, 48 * 1024 + i * 313)
                } else {
                    deterministicBytes(1000L + i, 40 * 1024 + i * 197)
                }
                val method = if (i % 2 == 0) ZipEntry.DEFLATED else ZipEntry.STORED
                Triple(name, payload, method)
            }
            val zipFile = File(testDir, "parallel_24.zip")
            buildZipArchive(zipFile, entries)
            val originalSize = zipFile.length()
            val outDir1 = File(testDir, "out_run1").apply { mkdirs() }

            val pauseController = ExtractionPauseController()
            val checkpointCounter = AtomicInteger(0)
            val stopTriggered = AtomicBoolean(false)
            val statuses = CopyOnWriteArrayList<ExtractionStatus>()

            // Trigger cooperative Stop mid-way through parallel Case 3 execution after some tail truncations
            pauseController.onBeforeCheckpoint = {
                if (checkpointCounter.incrementAndGet() >= 25 && stopTriggered.compareAndSet(false, true)) {
                    pauseController.cancel()
                }
            }

            KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir1,
                isHatchMode = true,
                context = app,
                pauseController = pauseController
            ).collect { progress ->
                statuses.add(progress.status)
            }

            assertTrue("Parallel Stop must emit FINALIZING", statuses.contains(ExtractionStatus.FINALIZING))
            assertEquals("Parallel Stop must terminate with CANCELLED", ExtractionStatus.CANCELLED, statuses.last())
            assertTrue("Archive must have reclaimed bytes and shrunk in-place", zipFile.length() < originalSize)

            // Verify restored ZIP passes FULL_VERIFY and independent java.util.zip.ZipFile validation
            val verifyStates = ArchiveVerifierEngine.verifyArchive(
                archiveFile = zipFile,
                level = VerificationLevel.FULL_VERIFY,
                context = app
            ).toList()
            assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, verifyStates.last().status)

            val restoredEntryNames = ZipFile(zipFile).use { zf ->
                zf.entries().asSequence().map { it.name }.toSet()
            }
            assertTrue("Restored archive must contain remaining unextracted entries", restoredEntryNames.isNotEmpty())
            assertTrue("Restored archive must contain fewer entries than original 24", restoredEntryNames.size < entries.size)

            // Extract restored ZIP and verify all 24 entries exist with exact original bytes across Run 1 + Run 2
            val outDir2 = File(testDir, "out_run2").apply { mkdirs() }
            val run2 = KaiHatchEngine.extractArchive(
                archiveFile = zipFile,
                targetDir = outDir2,
                isHatchMode = false,
                context = app
            ).toList()
            assertEquals(ExtractionStatus.COMPLETED, run2.last().status)

            for ((path, expectedBytes, _) in entries) {
                val f1 = File(outDir1, path)
                val f2 = File(outDir2, path)
                assertTrue("Entry $path must exist either in run 1 or restored run 2", f1.exists() || f2.exists())
                if (f1.exists()) {
                    assertArrayEquals("Run 1 bytes mismatch for $path", expectedBytes, f1.readBytes())
                }
                if (f2.exists()) {
                    assertArrayEquals("Run 2 bytes mismatch for $path", expectedBytes, f2.readBytes())
                }
            }
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun stopDuringLargeDeflatedEntryRespectsBeforeAndAfterTailSwapSafeBoundaries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_large_deflate_${System.nanoTime()}").apply { mkdirs() }
        try {
            val headBytes = deterministicBytes(701L, 128 * 1024)
            // Random bytes do not compress well -> compressedSize > 4 MB -> triggers totalChunks > 1 (canSwapInPlace)
            val largeDeflateBytes = deterministicBytes(702L, 5 * 1024 * 1024)

            // Case A: Stop BEFORE tail-swap relocation (allowAbortCurrentEntry == true) -> large DEFLATED stays intact
            val zipBefore = File(testDir, "large_deflate_before_swap.zip")
            buildZipArchive(
                zipBefore,
                listOf(
                    Triple("01_head.bin", headBytes, ZipEntry.STORED),
                    Triple("02_large_deflate.bin", largeDeflateBytes, ZipEntry.DEFLATED)
                )
            )
            val outBefore = File(testDir, "out_before").apply { mkdirs() }
            val ctrlBefore = ExtractionPauseController()
            val stopBeforeTriggered = AtomicBoolean(false)
            val preSwapDone = AtomicBoolean(false)
            val injectorBefore = RecordingFaultInjector(
                onStepCallback = { point, _, _ ->
                    if (point == DestructivePoint.AFTER_CD_TRUNCATE) {
                        preSwapDone.set(true)
                    }
                }
            )
            val cpCountBefore = AtomicInteger(0)
            ctrlBefore.onBeforeCheckpoint = {
                if (preSwapDone.get() && cpCountBefore.incrementAndGet() >= 2 &&
                    stopBeforeTriggered.compareAndSet(false, true)
                ) {
                    ctrlBefore.cancel()
                }
            }
            KaiHatchEngine.faultInjector = injectorBefore
            val emissionsBefore = try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipBefore,
                    targetDir = outBefore,
                    isHatchMode = true,
                    context = app,
                    pauseController = ctrlBefore
                ).toList()
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }
            assertEquals(ExtractionStatus.CANCELLED, emissionsBefore.last().status)
            assertFalse(
                "Partial large DEFLATED output must be quarantined when stopped before tail-swap mutation",
                File(outBefore, "02_large_deflate.bin").exists()
            )
            assertEquals(0, injectorBefore.getCount(DestructivePoint.BEFORE_TAILSWAP_PWRITE))

            val remainingBefore = ZipFile(zipBefore).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
            assertEquals(listOf("01_head.bin", "02_large_deflate.bin"), remainingBefore)
            assertEquals(
                VerificationStatus.PASSED_FULL_INTEGRITY,
                ArchiveVerifierEngine.verifyArchive(zipBefore, VerificationLevel.FULL_VERIFY, context = app).toList().last().status
            )

            // Case B: Stop AFTER tail-swap pwrite starts (allowAbortCurrentEntry == false) -> finishes large DEFLATED to safe boundary and restores head
            val zipAfter = File(testDir, "large_deflate_after_swap.zip")
            buildZipArchive(
                zipAfter,
                listOf(
                    Triple("01_head.bin", headBytes, ZipEntry.STORED),
                    Triple("02_large_deflate.bin", largeDeflateBytes, ZipEntry.DEFLATED)
                )
            )
            val outAfter = File(testDir, "out_after").apply { mkdirs() }
            val ctrlAfter = ExtractionPauseController()
            val stopAfterTriggered = AtomicBoolean(false)
            val injectorAfter = RecordingFaultInjector(
                onStepCallback = { point, _, _ ->
                    if (point == DestructivePoint.AFTER_TAILSWAP_TRUNCATE &&
                        stopAfterTriggered.compareAndSet(false, true)
                    ) {
                        ctrlAfter.cancel()
                    }
                }
            )
            KaiHatchEngine.faultInjector = injectorAfter
            val emissionsAfter = try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipAfter,
                    targetDir = outAfter,
                    isHatchMode = true,
                    context = app,
                    pauseController = ctrlAfter
                ).toList()
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }
            assertTrue("Must emit FINALIZING", emissionsAfter.any { it.status == ExtractionStatus.FINALIZING })
            assertEquals(ExtractionStatus.CANCELLED, emissionsAfter.last().status)
            val extractedLarge = File(outAfter, "02_large_deflate.bin")
            assertTrue("In-flight mutated DEFLATED entry must complete cleanly to safe boundary", extractedLarge.exists())
            assertArrayEquals(largeDeflateBytes, extractedLarge.readBytes())

            val outAfterRestored = File(testDir, "out_after_restored").apply { mkdirs() }
            val restoredExtract = KaiHatchEngine.extractArchive(
                archiveFile = zipAfter,
                targetDir = outAfterRestored,
                isHatchMode = false,
                context = app
            ).toList()
            assertEquals(ExtractionStatus.COMPLETED, restoredExtract.last().status)
            assertArrayEquals(headBytes, File(outAfterRestored, "01_head.bin").readBytes())
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun safSourceAndDestinationStopRestoreAndCrashRecoveryWorkEndToEnd() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_saf_e2e_${System.nanoTime()}").apply { mkdirs() }
        val safRoot = File(testDir, "saf_root").apply { mkdirs() }
        RestorationTestSafDocumentsProvider.reset(safRoot)
        Robolectric.setupContentProvider(RestorationTestSafDocumentsProvider::class.java, RESTORATION_SAF_AUTHORITY)
        StorageHelper.clearSafDirCache()

        try {
            val treeUri = DocumentsContract.buildTreeDocumentUri(RESTORATION_SAF_AUTHORITY, "p3_tree_root")
            val entries = listOf(
                Triple("01_first.bin", deterministicBytes(801L, 96 * 1024), ZipEntry.STORED),
                Triple("02_second.bin", compressibleBytes(802L, 128 * 1024), ZipEntry.DEFLATED),
                Triple("03_third.bin", deterministicBytes(803L, 112 * 1024), ZipEntry.STORED),
                Triple("04_fourth.bin", compressibleBytes(804L, 144 * 1024), ZipEntry.DEFLATED)
            )

            // 1. SAF zero-copy Stop -> In-place Restore to SAF destination
            val safZipStop = File(safRoot, "saf_stop.zip")
            buildZipArchive(safZipStop, entries)
            val safZipDocId = RestorationTestSafDocumentsProvider.registerFile(safZipStop)
            val safZipUri = DocumentsContract.buildDocumentUri(RESTORATION_SAF_AUTHORITY, safZipDocId)
            val safOutSubfolder = StorageHelper.getOrCreateDocumentSubfolder(app, treeUri, "saf_stop_out")!!
            val fallbackDir = File(testDir, "fallback_stop").apply { mkdirs() }
            val originalSafSize = safZipStop.length()

            val pauseController = ExtractionPauseController()
            val stopRequested = AtomicBoolean(false)
            val emissions = CopyOnWriteArrayList<ExtractionProgress>()
            val safStopEntries = ZipParser.parseSafUri(app, safZipUri, safZipStop.name)
            KaiHatchEngine.extractSafUriZeroCopy(
                context = app,
                uri = safZipUri,
                archiveName = safZipStop.name,
                entries = safStopEntries,
                targetDir = fallbackDir,
                isHatchMode = true,
                targetTreeUri = treeUri,
                targetFolderDocUri = safOutSubfolder,
                pauseController = pauseController
            ).collect { progress ->
                emissions.add(progress)
                if (progress.completedEntries >= 2 && stopRequested.compareAndSet(false, true)) {
                    pauseController.cancel()
                }
            }

            assertTrue(emissions.any { it.status == ExtractionStatus.FINALIZING })
            assertEquals(ExtractionStatus.CANCELLED, emissions.last().status)
            assertTrue("SAF ZIP must shrink in-place", safZipStop.length() < originalSafSize)
            assertEquals(
                VerificationStatus.PASSED_FULL_INTEGRITY,
                ArchiveVerifierEngine.verifyArchive(safZipStop, VerificationLevel.FULL_VERIFY, context = app).toList().last().status
            )

            // Extract restored SAF ZIP via Standard Mode into a fresh dir and verify all 4 entries
            val verifyOutDir = File(testDir, "saf_stop_verify_restored").apply { mkdirs() }
            val restoredExtract = KaiHatchEngine.extractArchive(
                archiveFile = safZipStop,
                targetDir = verifyOutDir,
                isHatchMode = false,
                context = app
            ).toList()
            assertEquals(ExtractionStatus.COMPLETED, restoredExtract.last().status)
            val safActualOutDir = File(safRoot, "saf_stop_out")
            for ((path, expectedBytes, _) in entries) {
                val fSaf = File(safActualOutDir, path)
                val fRestored = File(verifyOutDir, path)
                assertTrue("Entry $path must exist either in SAF out or restored ZIP", fSaf.exists() || fRestored.exists())
                if (fSaf.exists()) assertArrayEquals(expectedBytes, fSaf.readBytes())
                if (fRestored.exists()) assertArrayEquals(expectedBytes, fRestored.readBytes())
            }

            // 2. Crash recovery with SAF source + SAF destination + corrupt partial SAF output cleanup
            val safZipCrash = File(safRoot, "saf_crash.zip")
            buildZipArchive(safZipCrash, entries)
            val crashDocId = RestorationTestSafDocumentsProvider.registerFile(safZipCrash)
            val crashUri = DocumentsContract.buildDocumentUri(RESTORATION_SAF_AUTHORITY, crashDocId)
            val safCrashOutSubfolder = StorageHelper.getOrCreateDocumentSubfolder(app, treeUri, "saf_crash_out")!!
            val crashFallbackDir = File(testDir, "fallback_crash").apply { mkdirs() }

            val crashInjector = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.faultInjector = crashInjector
            val crashEntries = ZipParser.parseSafUri(app, crashUri, safZipCrash.name)
            val crashEmissions = try {
                KaiHatchEngine.extractSafUriZeroCopy(
                    context = app,
                    uri = crashUri,
                    archiveName = safZipCrash.name,
                    entries = crashEntries,
                    targetDir = crashFallbackDir,
                    isHatchMode = true,
                    targetTreeUri = treeUri,
                    targetFolderDocUri = safCrashOutSubfolder
                ).toList()
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }
            assertEquals(ExtractionStatus.ERROR, crashEmissions.last().status)

            // Plant a corrupt partial SAF output for unextracted 01_first.bin to verify SAF cleanup
            val partialSafFile = File(File(safRoot, "saf_crash_out"), "01_first.bin")
            partialSafFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            RestorationTestSafDocumentsProvider.registerFile(partialSafFile)

            val pendingState = ResumeJournalManager.loadPendingStates(app)
                .firstOrNull { it.archiveName == safZipCrash.name }
            assertNotNull("SAF crash journal must be persisted", pendingState)
            val restoreEligibility = ResumeJournalManager.assessRestoreEligibility(pendingState!!, app)
            assertTrue(
                "SAF crash state must be Restorable (got $restoreEligibility)",
                restoreEligibility is ResumeJournalManager.RestoreEligibility.Restorable
            )

            val restoreEmissions = KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = pendingState
            ).toList()
            assertEquals(ExtractionStatus.CANCELLED, restoreEmissions.last().status)
            assertFalse("Corrupt partial SAF file for remaining entry must be removed", partialSafFile.exists())
            assertEquals(
                VerificationStatus.PASSED_FULL_INTEGRITY,
                ArchiveVerifierEngine.verifyArchive(safZipCrash, VerificationLevel.FULL_VERIFY, context = app).toList().last().status
            )
        } finally {
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            StorageHelper.clearSafDirCache()
            File(app.filesDir, "journals").deleteRecursively()
            testDir.deleteRecursively()
        }
    }

    @Test
    fun pauseResumeThenStopRestoresRemainingZipAndPreservesViewModelFinalizingGuards() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_pause_resume_stop_${System.nanoTime()}").apply { mkdirs() }
        try {
            val entries = listOf(
                Triple("01_a.bin", deterministicBytes(901L, 128 * 1024), ZipEntry.STORED),
                Triple("02_b.bin", compressibleBytes(902L, 192 * 1024), ZipEntry.DEFLATED),
                Triple("03_c.bin", deterministicBytes(903L, 160 * 1024), ZipEntry.STORED),
                Triple("04_d.bin", compressibleBytes(904L, 144 * 1024), ZipEntry.DEFLATED)
            )
            val zipFile = File(testDir, "pause_resume_stop.zip")
            buildZipArchive(zipFile, entries)
            val outDir = File(testDir, "out").apply { mkdirs() }

            // 1. Engine flow: Pause -> Resume -> Stop -> Restore
            val pauseController = ExtractionPauseController()
            val cpCount = AtomicInteger(0)
            val pauseTriggered = AtomicBoolean(false)
            val stopTriggered = AtomicBoolean(false)
            val completedAfterResume = AtomicInteger(0)
            val statuses = CopyOnWriteArrayList<ExtractionStatus>()

            pauseController.onBeforeCheckpoint = {
                val c = cpCount.incrementAndGet()
                if (c == 4 && pauseTriggered.compareAndSet(false, true)) {
                    pauseController.requestPause()
                }
            }

            val job = launch(Dispatchers.Default) {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipFile,
                    targetDir = outDir,
                    isHatchMode = true,
                    context = app,
                    pauseController = pauseController
                ).collect { p ->
                    statuses.add(p.status)
                    if (pauseController.pauseAcknowledgeCount >= 1 && p.completedEntries >= 2) {
                        completedAfterResume.set(p.completedEntries)
                        if (stopTriggered.compareAndSet(false, true)) {
                            pauseController.cancel()
                        }
                    }
                }
            }

            assertTrue("Must reach PAUSED before resuming", pauseController.awaitPaused(5000L))
            val lenWhilePaused = zipFile.length()
            delay(80)
            assertEquals("Archive size must remain frozen while PAUSED", lenWhilePaused, zipFile.length())

            assertTrue(pauseController.resume())
            job.join()

            assertTrue("Must observe PAUSED", statuses.contains(ExtractionStatus.PAUSED))
            assertTrue("Must observe FINALIZING", statuses.contains(ExtractionStatus.FINALIZING))
            assertEquals("Must end with CANCELLED", ExtractionStatus.CANCELLED, statuses.last())
            assertEquals(
                VerificationStatus.PASSED_FULL_INTEGRITY,
                ArchiveVerifierEngine.verifyArchive(zipFile, VerificationLevel.FULL_VERIFY, context = app).toList().last().status
            )

            // 2. ViewModel FINALIZING lockout & CANCELLED status/logs preservation check
            val viewModel = HatchViewModel(app)
            val progressField = HatchViewModel::class.java.getDeclaredField("_extractionProgress").apply { isAccessible = true }
            val selectedFileField = HatchViewModel::class.java.getDeclaredField("_selectedArchiveFile").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val vmProgressFlow = progressField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ExtractionProgress>
            @Suppress("UNCHECKED_CAST")
            val vmSelectedFileFlow = selectedFileField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<File?>

            vmSelectedFileFlow.value = zipFile
            vmProgressFlow.value = ExtractionProgress(
                archiveName = zipFile.name,
                isHatchMode = true,
                status = ExtractionStatus.FINALIZING,
                currentFileName = "Finalizing remaining archive…"
            )

            // Conflicting actions must be ignored while FINALIZING
            viewModel.deselectArchive()
            viewModel.startExtraction(requirePersistentFolder = false)
            assertEquals(zipFile, viewModel.selectedArchiveFile.value)
            assertEquals(ExtractionStatus.FINALIZING, viewModel.extractionProgress.value.status)

            // Simulate handleExtractionProgressUpdate receiving terminal CANCELLED from Hatch Stop
            val handleMethod = HatchViewModel::class.java.getDeclaredMethod(
                "handleExtractionProgressUpdate",
                ExtractionProgress::class.java,
                String::class.java,
                File::class.java,
                Uri::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                kotlin.coroutines.Continuation::class.java
            ).apply { isAccessible = true }

            val terminalRestoreProgress = ExtractionProgress(
                archiveName = zipFile.name,
                isHatchMode = true,
                status = ExtractionStatus.CANCELLED,
                totalEntries = 4,
                completedEntries = 2,
                currentFileName = "Restored remaining archive (2 entries)",
                originalArchiveBytes = 1024L,
                currentArchiveBytesOnDisk = zipFile.length(),
                bytesReclaimed = 512L,
                journalLogs = listOf("[RESTORE] Restored valid ZIP archive in-place (2 remaining entries)")
            )
            val invoker = launch(Dispatchers.Main.immediate) {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                    handleMethod.invoke(
                        viewModel,
                        terminalRestoreProgress,
                        zipFile.name,
                        outDir,
                        null,
                        1024L,
                        true,
                        System.currentTimeMillis(),
                        cont
                    )
                }
            }
            invoker.join()
            delay(250)

            assertEquals(
                "Terminal CANCELLED status must be preserved after archive summary refresh",
                ExtractionStatus.CANCELLED,
                viewModel.extractionProgress.value.status
            )
            assertTrue(
                "Restoration journal log must be preserved after Stop refresh",
                viewModel.extractionProgress.value.journalLogs.any { it.contains("[RESTORE]") }
            )
        } finally {
            File(app.filesDir, "journals").deleteRecursively()
            testDir.deleteRecursively()
        }
    }

    @Test
    fun restoredZipWithTrailingDirectoryAndZip64DataDescriptorsIsValidInStandardZipReaders() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "p3_dir_dd_zip64_${System.nanoTime()}").apply { mkdirs() }
        try {
            val f1Bytes = deterministicBytes(1001L, 64 * 1024)
            val f2Bytes = compressibleBytes(1002L, 128 * 1024)
            val f3Bytes = compressibleBytes(1003L, 96 * 1024)
            val f4Bytes = deterministicBytes(1004L, 64 * 1024)

            // Part 1: Streaming ZipArchiveOutputStream with Zip64Mode.Always writes bit 3 (0x0008) 24-byte
            // ZIP64 Data Descriptors; also include mid-archive and trailing directory entries!
            val zipTrailDir = File(testDir, "zip64_trailing_dir.zip")
            BufferedOutputStream(FileOutputStream(zipTrailDir)).use { bos ->
                ZipArchiveOutputStream(bos).use { zos ->
                    zos.setUseZip64(Zip64Mode.Always)

                    val entries = listOf(
                        "01_first.bin" to f1Bytes,
                        "dir_mid/" to null,
                        "02_deflated_dd.bin" to f2Bytes,
                        "03_tail_file.bin" to f3Bytes,
                        "dir_trailing/" to null
                    )
                    for ((name, payload) in entries) {
                        val zae = ZipArchiveEntry(name)
                        if (payload == null) {
                            zae.method = ZipEntry.STORED
                            zae.size = 0L
                            zae.compressedSize = 0L
                            zae.crc = 0L
                            zos.putArchiveEntry(zae)
                            zos.closeArchiveEntry()
                        } else {
                            zae.method = ZipEntry.DEFLATED
                            zos.putArchiveEntry(zae)
                            zos.write(payload)
                            zos.closeArchiveEntry()
                        }
                    }
                }
            }

            // Simulate crash right after pre_cd_truncate BEFORE any entry (including dir_trailing/) is extracted
            val outCrashDir = File(testDir, "out_crash_dir").apply { mkdirs() }
            val crashAfterCd = RecordingFaultInjector(
                triggerPoint = DestructivePoint.AFTER_CD_TRUNCATE,
                triggerOnOccurrence = 1
            )
            KaiHatchEngine.faultInjector = crashAfterCd
            try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipTrailDir,
                    targetDir = outCrashDir,
                    isHatchMode = true,
                    context = app
                ).toList()
            } catch (_: SimulatedFaultException) {
            } finally {
                KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            }

            val savedState = ResumeJournalManager.loadPendingStates(app)
                .first { it.archivePath == zipTrailDir.absolutePath }
            val eligibility = ResumeJournalManager.assessRestoreEligibility(savedState, app)
            assertTrue(
                "Trailing directory must NOT be truncated by pre_cd_truncate (got $eligibility)",
                eligibility is ResumeJournalManager.RestoreEligibility.Restorable
            )

            val restoredDirArchive = KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = savedState
            ).toList()
            assertEquals(ExtractionStatus.CANCELLED, restoredDirArchive.last().status)

            // Verify restored ZIP (including dir_mid/, dir_trailing/, and 24-byte DD entries) via BOTH java.util.zip.ZipFile & ZipArchiveInputStream!
            ZipFile(zipTrailDir).use { zf ->
                val map = zf.entries().asSequence().associateBy { it.name }
                assertTrue(map.containsKey("dir_mid/"))
                assertTrue(map["dir_mid/"]!!.isDirectory)
                assertTrue(map.containsKey("dir_trailing/"))
                assertTrue(map["dir_trailing/"]!!.isDirectory)
                assertArrayEquals(f1Bytes, zf.getInputStream(map["01_first.bin"]!!).readBytes())
                assertArrayEquals(f2Bytes, zf.getInputStream(map["02_deflated_dd.bin"]!!).readBytes())
                assertArrayEquals(f3Bytes, zf.getInputStream(map["03_tail_file.bin"]!!).readBytes())
            }
            ZipArchiveInputStream(FileInputStream(zipTrailDir)).use { zais ->
                val seen = mutableMapOf<String, ByteArray?>()
                while (true) {
                    val ze = zais.nextEntry ?: break
                    seen[ze.name] = if (ze.isDirectory) null else zais.readBytes()
                }
                assertTrue(seen.containsKey("dir_trailing/"))
                assertArrayEquals(f1Bytes, seen["01_first.bin"])
                assertArrayEquals(f2Bytes, seen["02_deflated_dd.bin"])
                assertArrayEquals(f3Bytes, seen["03_tail_file.bin"])
            }

            // Part 2: Mid-extraction Stop leaving a ZIP64 24-byte DD DEFLATED entry as the FINAL remaining entry
            val zipStopDd = File(testDir, "zip64_dd_mid_stop.zip")
            BufferedOutputStream(FileOutputStream(zipStopDd)).use { bos ->
                ZipArchiveOutputStream(bos).use { zos ->
                    zos.setUseZip64(Zip64Mode.Always)
                    for ((name, payload) in listOf(
                        "01_dd.bin" to f1Bytes,
                        "dir_keep/" to null,
                        "02_dd_last_remaining.bin" to f2Bytes,
                        "03_extract_first.bin" to f3Bytes,
                        "04_extract_first_b.bin" to f4Bytes
                    )) {
                        val zae = ZipArchiveEntry(name)
                        if (payload == null) {
                            zae.method = ZipEntry.STORED
                            zae.size = 0L
                            zae.compressedSize = 0L
                            zae.crc = 0L
                            zos.putArchiveEntry(zae)
                            zos.closeArchiveEntry()
                        } else {
                            zae.method = ZipEntry.DEFLATED
                            zos.putArchiveEntry(zae)
                            zos.write(payload)
                            zos.closeArchiveEntry()
                        }
                    }
                }
            }

            val outStopDd = File(testDir, "out_stop_dd").apply { mkdirs() }
            val pauseCtrl = ExtractionPauseController()
            val stopped = AtomicBoolean(false)
            val stopStatuses = CopyOnWriteArrayList<ExtractionStatus>()
            val prevInjector = KaiHatchEngine.faultInjector
            KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
                override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                    if (point == DestructivePoint.AFTER_JOURNAL_SAVE &&
                        File(outStopDd, "03_extract_first.bin").exists() &&
                        File(outStopDd, "04_extract_first_b.bin").exists() &&
                        stopped.compareAndSet(false, true)
                    ) {
                        pauseCtrl.cancel()
                    }
                }
            }
            try {
                KaiHatchEngine.extractArchive(
                    archiveFile = zipStopDd,
                    targetDir = outStopDd,
                    isHatchMode = true,
                    context = app,
                    pauseController = pauseCtrl
                ).collect { p ->
                    stopStatuses.add(p.status)
                }
            } finally {
                KaiHatchEngine.faultInjector = prevInjector
            }
            assertTrue("Deterministic cancellation must have triggered after first 2 entries", stopped.get())
            assertEquals(ExtractionStatus.CANCELLED, stopStatuses.last())

            // Independent reader verification (ZipFile + ZipArchiveInputStream + FULL_VERIFY)
            assertEquals(
                VerificationStatus.PASSED_FULL_INTEGRITY,
                ArchiveVerifierEngine.verifyArchive(zipStopDd, VerificationLevel.FULL_VERIFY, context = app).toList().last().status
            )
            val expectedContent = mapOf(
                "01_dd.bin" to f1Bytes,
                "02_dd_last_remaining.bin" to f2Bytes,
                "03_extract_first.bin" to f3Bytes,
                "04_extract_first_b.bin" to f4Bytes
            )
            val zfEntries = ZipFile(zipStopDd).use { zf ->
                zf.entries().asSequence().associate { it.name to if (it.isDirectory) null else zf.getInputStream(it).readBytes() }
            }
            assertTrue("dir_keep/ must be preserved", zfEntries.containsKey("dir_keep/"))
            assertTrue("02_dd_last_remaining.bin must remain in restored ZIP", zfEntries.containsKey("02_dd_last_remaining.bin"))
            ZipArchiveInputStream(FileInputStream(zipStopDd)).use { zais ->
                while (true) {
                    val ze = zais.nextEntry ?: break
                    if (!ze.isDirectory) {
                        val expected = expectedContent[ze.name]!!
                        assertArrayEquals("ZipArchiveInputStream payload mismatch for ${ze.name}", expected, zais.readBytes())
                    }
                }
            }
            for ((name, bytes) in expectedContent) {
                val extracted = File(outStopDd, name)
                val inRestored = zfEntries[name]
                assertTrue("Entry $name must be either extracted or in restored ZIP", extracted.exists() || inRestored != null)
                if (extracted.exists()) assertArrayEquals(bytes, extracted.readBytes())
                if (inRestored != null) assertArrayEquals(bytes, inRestored)
            }

            // Part 3: Standard java.util.zip.ZipOutputStream (16-byte DD, bit 0x0008) + trailing directory verified with BOTH java.util.zip.ZipFile & java.util.zip.ZipInputStream
            val zipStdDd = File(testDir, "std_dd_mid_stop.zip")
            BufferedOutputStream(FileOutputStream(zipStdDd)).use { bos ->
                ZipOutputStream(bos).use { zos ->
                    for ((name, payload) in listOf(
                        "01_std.bin" to f1Bytes,
                        "dir_tail_keep/" to null,
                        "02_std_extract.bin" to f3Bytes,
                        "03_std_extract.bin" to f4Bytes
                    )) {
                        val ze = ZipEntry(name)
                        zos.putNextEntry(ze)
                        if (payload != null) zos.write(payload)
                        zos.closeEntry()
                    }
                }
            }
            val outStdDd = File(testDir, "out_std_dd").apply { mkdirs() }
            val stdCtrl = ExtractionPauseController()
            val stdStopped = AtomicBoolean(false)
            val stdStatuses = CopyOnWriteArrayList<ExtractionStatus>()
            KaiHatchEngine.extractArchive(
                archiveFile = zipStdDd,
                targetDir = outStdDd,
                isHatchMode = true,
                context = app,
                pauseController = stdCtrl
            ).collect { p ->
                stdStatuses.add(p.status)
                if (p.completedEntries >= 1 && stdStopped.compareAndSet(false, true)) {
                    stdCtrl.cancel()
                }
            }
            assertEquals(ExtractionStatus.CANCELLED, stdStatuses.last())
            val stdExpected = mapOf(
                "01_std.bin" to f1Bytes,
                "02_std_extract.bin" to f3Bytes,
                "03_std_extract.bin" to f4Bytes
            )
            val stdZf = ZipFile(zipStdDd).use { zf ->
                zf.entries().asSequence().associate { it.name to if (it.isDirectory) null else zf.getInputStream(it).readBytes() }
            }
            assertTrue("dir_tail_keep/ must be preserved in std DD restore", stdZf.containsKey("dir_tail_keep/"))
            ZipInputStream(FileInputStream(zipStdDd)).use { zis ->
                while (true) {
                    val ze = zis.nextEntry ?: break
                    if (!ze.isDirectory) {
                        assertArrayEquals("ZipInputStream mismatch for ${ze.name}", stdExpected[ze.name]!!, zis.readBytes())
                    }
                }
            }
        } finally {
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            File(app.filesDir, "journals").deleteRecursively()
            testDir.deleteRecursively()
        }
    }
}
