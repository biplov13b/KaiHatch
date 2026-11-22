package com.biplov.kaihatch.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.SafRecoveryOutputOpenResult
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.ui.RecoveryCardKind
import com.biplov.kaihatch.ui.resolveRecoveryCardState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Comprehensive verification suite for Local Storage & Crash-Recovery Compatibility:
 * - Optional All Files Access verified direct-POSIX upgrade for Internal Storage & Downloads
 * - Strict URI-to-file identity check (rejects spoofed/mismatched content)
 * - Permission denial fallback to verified SAF without error
 * - End-to-end SAF "rw" process-death Resume across PREPARED / RELOCATED_TRUNCATE_PENDING / COMMITTED
 * - Partial SAF output reopen without deleting checkpointed prefix + uncommitted trailing byte trim
 * - End-to-end SAF "rw" process-death Restore Remaining ZIP (simulated removable storage)
 * - Fail-closed handling for read-only providers, non-seekable pipes, failed fsync, corrupted prefix, and truncated output
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalStorageCrashRecoveryCompatibilityTest {

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
        const val REMOVABLE_SAF_AUTHORITY = "com.biplov.kaihatch.test.removable.saf"
    }

    /**
     * In-process DocumentsProvider simulating an external/removable volume (e.g., SD card or USB OTG)
     * with opaque `9016-4EF8:` volume IDs where direct POSIX resolution returns `null`, exercising the
     * pure SAF `ParcelFileDescriptor("rw")` transactional path.
     */
    class RemovableSafDocumentsProvider : ContentProvider() {
        companion object {
            lateinit var backingRootDir: File
            val docIdToFile = ConcurrentHashMap<String, File>()
            val readOnlyMode = AtomicBoolean(false)
            val failOpenRwWithNull = AtomicBoolean(false)
            private var nextId = 1

            @Synchronized
            fun reset(root: File) {
                backingRootDir = root
                docIdToFile.clear()
                readOnlyMode.set(false)
                failOpenRwWithNull.set(false)
                nextId = 1
                docIdToFile["9016-4EF8:root"] = root
            }

            @Synchronized
            fun registerFile(file: File, explicitId: String? = null): String {
                if (explicitId != null) {
                    docIdToFile[explicitId] = file
                    return explicitId
                }
                val existing = docIdToFile.entries.firstOrNull { it.value.absolutePath == file.absolutePath }?.key
                if (existing != null) return existing
                val id = "9016-4EF8:doc_${nextId++}"
                docIdToFile[id] = file
                return id
            }
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

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
                val parent = docIdToFile[parentDocId] ?: return cursor
                val children = parent.listFiles() ?: emptyArray()
                for (child in children) {
                    val childId = registerFile(child)
                    cursor.addRow(buildRow(cols, childId, child))
                }
            } else {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                    ?: return cursor
                val file = docIdToFile[docId] ?: return cursor
                if (file.exists()) {
                    cursor.addRow(buildRow(cols, docId, file))
                }
            }
            return cursor
        }

        private fun buildRow(cols: Array<out String>, docId: String, file: File): Array<Any?> {
            val mime = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
            return Array(cols.size) { i ->
                when (cols[i]) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> docId
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                    DocumentsContract.Document.COLUMN_SIZE -> if (file.isFile) file.length() else 0L
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    DocumentsContract.Document.COLUMN_FLAGS ->
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            (if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0)
                    else -> null
                }
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (extras == null) return null
            @Suppress("DEPRECATION")
            val targetUri = extras.getParcelable<Uri>("uri") ?: return null
            return when (method) {
                "android:createDocument" -> {
                    val mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return null
                    val parentDocId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull()
                        ?: runCatching { DocumentsContract.getTreeDocumentId(targetUri) }.getOrNull()
                        ?: return null
                    val parent = docIdToFile[parentDocId] ?: return null
                    val target = File(parent, displayName)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!target.exists() && !target.mkdirs()) return null
                    } else {
                        target.parentFile?.mkdirs()
                        if (target.exists()) return null
                        if (!target.createNewFile()) return null
                    }
                    val newDocId = registerFile(target)
                    val createdUri = if (DocumentsContract.isTreeUri(targetUri)) {
                        DocumentsContract.buildDocumentUriUsingTree(targetUri, newDocId)
                    } else {
                        DocumentsContract.buildDocumentUri(REMOVABLE_SAF_AUTHORITY, newDocId)
                    }
                    Bundle().apply { putParcelable("uri", createdUri) }
                }
                "android:deleteDocument" -> {
                    val docId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull() ?: return null
                    val file = docIdToFile[docId] ?: return null
                    file.deleteRecursively()
                    docIdToFile.remove(docId)
                    Bundle()
                }
                else -> super.call(method, arg, extras)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode.contains("w")) {
                if (readOnlyMode.get()) {
                    throw SecurityException("Simulated read-only SAF provider denies '$mode' access")
                }
                if (failOpenRwWithNull.get()) {
                    return null
                }
            }
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val file = docIdToFile[docId] ?: return null
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, pfdMode)
        }
    }

    /**
     * In-process provider registered for `com.android.externalstorage.documents` and
     * `com.android.providers.downloads.documents` to test verified direct-POSIX upgrade & identity checks.
     */
    class SimulatedLocalStorageDocumentsProvider : ContentProvider() {
        companion object {
            val uriToBackingFile = ConcurrentHashMap<String, File>()

            fun reset() {
                uriToBackingFile.clear()
            }
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val f = uriToBackingFile[uri.toString()] ?: return null
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
        }
    }

    private lateinit var context: Context
    private lateinit var tempRoot: File
    private lateinit var simulatedExternalRoot: File
    private lateinit var removableSafRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempRoot = File(context.cacheDir, "local_storage_compat_test_${System.nanoTime()}").apply { mkdirs() }
        simulatedExternalRoot = File(tempRoot, "emulated_0").apply { mkdirs() }
        removableSafRoot = File(tempRoot, "removable_sd_9016").apply { mkdirs() }

        StorageHelper.clearSafDirCache()
        StorageHelper.allFilesAccessOverrideForTest = null
        StorageHelper.forceFsyncFailureForTest = null
        StorageHelper.externalStorageDirOverrideForTest = simulatedExternalRoot
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        RemovableSafDocumentsProvider.reset(removableSafRoot)
        SimulatedLocalStorageDocumentsProvider.reset()

        Robolectric.buildContentProvider(RemovableSafDocumentsProvider::class.java)
            .create(ProviderInfo().apply { authority = REMOVABLE_SAF_AUTHORITY; exported = true })
        Robolectric.buildContentProvider(SimulatedLocalStorageDocumentsProvider::class.java)
            .create(ProviderInfo().apply { authority = EXTERNAL_STORAGE_AUTHORITY; exported = true })
        Robolectric.buildContentProvider(SimulatedLocalStorageDocumentsProvider::class.java)
            .create(ProviderInfo().apply { authority = DOWNLOADS_AUTHORITY; exported = true })

        clearAllPendingJournals()
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        StorageHelper.allFilesAccessOverrideForTest = null
        StorageHelper.forceFsyncFailureForTest = null
        StorageHelper.externalStorageDirOverrideForTest = null
        StorageHelper.clearSafDirCache()
        clearAllPendingJournals()
        tempRoot.deleteRecursively()
    }

    private fun clearAllPendingJournals() {
        ResumeJournalManager.loadPendingStates(context).forEach { state ->
            ResumeJournalManager.clearDeflateTailSwapCheckpoint(state.stateFilePath)
            ResumeJournalManager.clearTailSwapGuard(state.stateFilePath)
            ResumeJournalManager.clearRestorationCheckpoint(state.stateFilePath)
            ResumeJournalManager.deleteState(state.stateFilePath)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createMultiEntryLargeDeflateZip(
        targetZip: File,
        largeDeflateBytes: Int = 6_250_000
    ): Map<String, ByteArray> {
        val rng = Random(20260401L)
        val small1 = ByteArray(24 * 1024).also { rng.nextBytes(it) }
        val small2 = "KaiHatch local storage compatibility test payload.\n".repeat(400).toByteArray(Charsets.UTF_8)
        // Semi-compressible deterministic payload (~4.2 MB compressed -> 5 chunks under real production threshold)
        val largePayload = ByteArray(largeDeflateBytes)
        var pos = 0
        var block = 0
        while (pos < largePayload.size) {
            val chunk = minOf(4096, largePayload.size - pos)
            if (block % 3 == 0) {
                val repeated = ((block * 31) and 0xFF).toByte()
                largePayload.fill(repeated, pos, pos + chunk)
            } else {
                val tmp = ByteArray(chunk)
                rng.nextBytes(tmp)
                System.arraycopy(tmp, 0, largePayload, pos, chunk)
            }
            pos += chunk
            block++
        }

        val entries = linkedMapOf(
            "docs/alpha.bin" to small1,
            "docs/readme.txt" to small2,
            "assets/large_deflated.bin" to largePayload
        )

        targetZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(targetZip)).use { zos ->
            zos.setLevel(6)
            for ((path, data) in entries) {
                val ze = ZipEntry(path)
                ze.method = ZipEntry.DEFLATED
                zos.putNextEntry(ze)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return entries
    }

    // -----------------------------------------------------------------------------------------
    // 1. Internal Storage & Downloads: Verified Direct-POSIX Upgrade + Spoofing Rejection
    // -----------------------------------------------------------------------------------------

    @Test
    fun internalStorageAndDownloads_upgradeToVerifiedDirectPosix_andRejectSpoofedIdentity() {
        val downloadsDir = File(simulatedExternalRoot, "Download").apply { mkdirs() }
        val realArchive = File(downloadsDir, "game_pack.zip")
        createMultiEntryLargeDeflateZip(realArchive, largeDeflateBytes = 128 * 1024)

        val primaryUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:Download/game_pack.zip"
        )
        val rawDownloadsUri = DocumentsContract.buildDocumentUri(
            DOWNLOADS_AUTHORITY,
            "raw:${realArchive.absolutePath}"
        )
        SimulatedLocalStorageDocumentsProvider.uriToBackingFile[primaryUri.toString()] = realArchive
        SimulatedLocalStorageDocumentsProvider.uriToBackingFile[rawDownloadsUri.toString()] = realArchive

        // 1a. When All Files Access is DENIED on Android 11+, NEVER upgrade content:// URI to direct File!
        StorageHelper.allFilesAccessOverrideForTest = false
        assertNull(
            "Must NOT upgrade primary: URI to direct File when All Files Access is denied",
            StorageHelper.tryResolveVerifiedDirectSourceFile(context, primaryUri)
        )
        assertNull(
            "Must NOT upgrade Downloads raw: URI to direct File when All Files Access is denied",
            StorageHelper.tryResolveVerifiedDirectSourceFile(context, rawDownloadsUri)
        )

        // 1b. When All Files Access is GRANTED, verify identity + rw + fsync -> upgrades cleanly to File!
        StorageHelper.allFilesAccessOverrideForTest = true
        val resolvedPrimary = StorageHelper.tryResolveVerifiedDirectSourceFile(context, primaryUri)
        assertNotNull("Expected primary:Download URI to upgrade to verified direct File", resolvedPrimary)
        assertEquals(realArchive.canonicalPath, resolvedPrimary!!.canonicalPath)

        val resolvedDownloads = StorageHelper.tryResolveVerifiedDirectSourceFile(context, rawDownloadsUri)
        assertNotNull("Expected Downloads raw: URI to upgrade to verified direct File", resolvedDownloads)
        assertEquals(realArchive.canonicalPath, resolvedDownloads!!.canonicalPath)

        // 1c. Identity spoofing check: URI claims primary:Download/spoofed.zip, but provider serves a DIFFERENT file of identical size!
        val decoyOnDisk = File(downloadsDir, "spoofed.zip").apply {
            writeBytes(ByteArray(32 * 1024) { 0x41 })
        }
        val differentRealTarget = File(tempRoot, "actual_other_file.zip").apply {
            writeBytes(ByteArray(32 * 1024) { 0x5A })
        }
        val spoofedUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:Download/spoofed.zip"
        )
        SimulatedLocalStorageDocumentsProvider.uriToBackingFile[spoofedUri.toString()] = differentRealTarget
        assertNull(
            "Spoofed URI whose descriptor bytes do not match candidate path MUST be rejected!",
            StorageHelper.tryResolveVerifiedDirectSourceFile(context, spoofedUri)
        )
        assertTrue(decoyOnDisk.exists())

        // 1d. Destination direct-POSIX probe honors strict fsync verification
        val downloadsTreeUri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Download")
        val directSubfolder = StorageHelper.tryResolveDirectWritableSubfolder(downloadsTreeUri, "game_pack_extracted")
        assertNotNull("Expected primary:Download tree to resolve writable direct subfolder", directSubfolder)
        assertTrue(directSubfolder!!.canonicalPath.startsWith(downloadsDir.canonicalPath))

        StorageHelper.forceFsyncFailureForTest = { true }
        assertNull(
            "Direct subfolder probe MUST fail closed when fsync fails",
            StorageHelper.tryResolveDirectWritableSubfolder(downloadsTreeUri, "fsync_fail_extracted")
        )
        assertNull(
            "Direct source upgrade MUST fail closed when fsync fails",
            StorageHelper.tryResolveVerifiedDirectSourceFile(context, primaryUri)
        )
    }

    // -----------------------------------------------------------------------------------------
    // 2. End-to-End SAF "rw" Process-Death Resume across PREPARED / RELOCATED_TRUNCATE_PENDING / COMMITTED
    //    (Simulating Removable Storage / SD Card / USB OTG + Uncommitted Trailing Output Trim)
    // -----------------------------------------------------------------------------------------

    @Test
    fun safRemovableStorage_transactionalDeflateResume_acrossAllPhases_andPartialOutputTrim() = runBlocking {
        val crashPhases = listOf(
            DestructivePoint.AFTER_TAILSWAP_PREPARED_CKPT to ResumeJournalManager.DeflateTailSwapTxPhase.PREPARED,
            DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE to ResumeJournalManager.DeflateTailSwapTxPhase.RELOCATED_TRUNCATE_PENDING,
            DestructivePoint.AFTER_TAILSWAP_COMMITTED_CKPT to ResumeJournalManager.DeflateTailSwapTxPhase.COMMITTED
        )

        for ((crashPoint, expectedPhase) in crashPhases) {
            StorageHelper.allFilesAccessOverrideForTest = false
            StorageHelper.clearSafDirCache()
            clearAllPendingJournals()

            val phaseRoot = File(removableSafRoot, "phase_${expectedPhase.name}").apply { mkdirs() }
            RemovableSafDocumentsProvider.reset(phaseRoot)

            val sourceZipFile = File(phaseRoot, "removable_archive.zip")
            val expectedPayloads = createMultiEntryLargeDeflateZip(sourceZipFile)
            val sourceDocId = RemovableSafDocumentsProvider.registerFile(sourceZipFile, "9016-4EF8:removable_archive.zip")
            val sourceSafUri = DocumentsContract.buildDocumentUri(REMOVABLE_SAF_AUTHORITY, sourceDocId)

            val destTreeUri = DocumentsContract.buildTreeDocumentUri(REMOVABLE_SAF_AUTHORITY, "9016-4EF8:root")
            val destSubfolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, destTreeUri, "unpacked_${expectedPhase.name}")!!
            // Fallback targetDir inside context.filesDir guarantees isDirectPosixDestination == false (pure SAF destination!)
            val fallbackTargetDir = File(context.filesDir, "extracted/unpacked_${expectedPhase.name}")
            assertFalse(
                "Test must exercise pure SAF destination (not direct POSIX)",
                StorageHelper.isDirectPosixDestination(context, fallbackTargetDir, destTreeUri)
            )

            // Parse entries from SAF URI
            val entries = context.contentResolver.openFileDescriptor(sourceSafUri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    ZipParser.parseZipChannel(fis.channel)
                }
            }

            // Inject simulated process death at the target transactional phase (chunk 1)
            val crashed = AtomicBoolean(false)
            KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
                override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                    if (point == crashPoint && detail.contains("c=1") && crashed.compareAndSet(false, true)) {
                        throw SimulatedFaultException(
                            point = point,
                            occurrence = 1,
                            archiveBytesAtFault = currentArchiveBytes,
                            message = "Simulated process death at $point ($detail)"
                        )
                    }
                }
            }

            val crashRun = KaiHatchEngine.extractSafUriZeroCopy(
                context = context,
                uri = sourceSafUri,
                archiveName = sourceZipFile.name,
                entries = entries,
                targetDir = fallbackTargetDir,
                isHatchMode = true,
                targetTreeUri = destTreeUri,
                targetFolderDocUri = destSubfolderDocUri
            ).toList()
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
            assertTrue(
                "Expected simulated crash to fire at $crashPoint (lastStatus=${crashRun.lastOrNull()?.status}, err=${crashRun.lastOrNull()?.errorMessage}, logs=${crashRun.lastOrNull()?.journalLogs?.takeLast(5)})",
                crashed.get()
            )
            assertEquals("Expected ERROR status on simulated crash at $crashPoint", ExtractionStatus.ERROR, crashRun.last().status)

            // Verify durable journal + v2 checkpoint exist and match expected phase
            val pendingStates = ResumeJournalManager.loadPendingStates(context)
            assertEquals("Expected exactly 1 pending journal for $expectedPhase", 1, pendingStates.size)
            val resumeState = pendingStates.single()
            assertEquals(sourceSafUri.toString(), resumeState.sourceUriString)
            assertTrue("SAF source journal must have empty archivePath", resumeState.archivePath.isEmpty())

            val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(resumeState.stateFilePath)
            assertNotNull("Expected v2 DeflateTailSwapCheckpoint for $expectedPhase", ckpt)
            assertEquals(expectedPhase, ckpt!!.txPhase)
            assertTrue("Checkpointed output offset should be > 0", ckpt.outputBytesWritten > 0L)

            // Append uncommitted trailing garbage to the partial SAF output file to prove recovery
            // reopens WITHOUT deleting checkpointed prefix and safely trims + fsyncs trailing bytes!
            val partialSafOutputFile = File(phaseRoot, "unpacked_${expectedPhase.name}/assets/large_deflated.bin")
            assertTrue("Partial SAF output file must exist on removable storage", partialSafOutputFile.exists())
            val trailingGarbage = ByteArray(1379) { 0xEE.toByte() }
            FileOutputStream(partialSafOutputFile, true).use { it.write(trailingGarbage) }
            assertEquals(ckpt.outputBytesWritten + trailingGarbage.size, partialSafOutputFile.length())

            // Verify eligibility accepts verified SAF "rw" source + SAF "rw" destination
            val resumeEligibility = ResumeJournalManager.assessResumeEligibility(resumeState, context)
            assertTrue(
                "Expected Resumable on verified SAF storage for $expectedPhase, got $resumeEligibility",
                resumeEligibility is ResumeJournalManager.ResumeEligibility.Resumable
            )
            val restoreEligibility = ResumeJournalManager.assessRestoreEligibility(resumeState, context)
            assertTrue(
                "Expected Restorable on verified SAF storage for $expectedPhase, got $restoreEligibility",
                restoreEligibility is ResumeJournalManager.RestoreEligibility.Restorable
            )
            val cardState = resolveRecoveryCardState(resumeState, resumeEligibility, restoreEligibility)
            assertEquals(RecoveryCardKind.BOTH_AVAILABLE, cardState.kind)
            assertTrue(cardState.showResumeButton)
            assertTrue(cardState.showRestoreButton)

            // Resume through completion via pure SAF "rw" path
            val resumeEmissions = KaiHatchEngine.resumeInterruptedExtraction(
                context = context,
                resumeState = resumeState
            ).toList()

            val finalProgress = resumeEmissions.last()
            assertEquals(
                "Resume should complete cleanly on SAF storage for $expectedPhase (error=${finalProgress.errorMessage})",
                ExtractionStatus.COMPLETED,
                finalProgress.status
            )
            assertTrue("Final CRC validation must pass", finalProgress.isCrcValid)

            // Verify bit-exact SHA-256 for every extracted file in removable SAF storage
            val extractedBase = File(phaseRoot, "unpacked_${expectedPhase.name}")
            for ((relPath, expectedBytes) in expectedPayloads) {
                val outFile = File(extractedBase, relPath)
                assertTrue("Extracted SAF file '$relPath' must exist", outFile.exists())
                assertEquals("Extracted SAF file '$relPath' size mismatch", expectedBytes.size.toLong(), outFile.length())
                assertEquals("Extracted SAF file '$relPath' SHA-256 mismatch", sha256(expectedBytes), sha256(outFile))
            }

            // Confirm no duplicate ZIPs were created and no files leaked into internal app storage
            val extraZips = phaseRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".zip") }.toList()
            assertTrue("Source ZIP should be deleted upon completion with zero duplicate ZIPs", extraZips.isEmpty())
            assertFalse("Must never leak extracted files into internal fallback dir", fallbackTargetDir.exists())
            assertTrue("Pending journals must be cleaned up on completion", ResumeJournalManager.loadPendingStates(context).isEmpty())
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3. End-to-End SAF "rw" Process-Death Restore Remaining ZIP (Simulated Removable Storage)
    // -----------------------------------------------------------------------------------------

    @Test
    fun safRemovableStorage_restoreRemainingZip_finishesOnlyInterruptedEntry_andRebuildsValidZip() = runBlocking {
        StorageHelper.allFilesAccessOverrideForTest = false
        val restoreRoot = File(removableSafRoot, "restore_saf_case").apply { mkdirs() }
        RemovableSafDocumentsProvider.reset(restoreRoot)

        val sourceZipFile = File(restoreRoot, "usb_otg_archive.zip")
        val expectedPayloads = createMultiEntryLargeDeflateZip(sourceZipFile)
        val sourceDocId = RemovableSafDocumentsProvider.registerFile(sourceZipFile, "9016-4EF8:usb_otg_archive.zip")
        val sourceSafUri = DocumentsContract.buildDocumentUri(REMOVABLE_SAF_AUTHORITY, sourceDocId)

        val destTreeUri = DocumentsContract.buildTreeDocumentUri(REMOVABLE_SAF_AUTHORITY, "9016-4EF8:root")
        val destSubfolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, destTreeUri, "restore_out")!!
        val fallbackTargetDir = File(context.filesDir, "extracted/restore_out")

        val entries = context.contentResolver.openFileDescriptor(sourceSafUri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                ZipParser.parseZipChannel(fis.channel)
            }
        }

        // Crash during chunk 1 relocation on `assets/large_deflated.bin` (processed first in reverse tail order)
        val crashed = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE &&
                    detail.contains("c=1") &&
                    crashed.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(
                        point = point,
                        occurrence = 1,
                        archiveBytesAtFault = currentArchiveBytes,
                        message = "Simulated crash before ftruncate"
                    )
                }
            }
        }

        val crashRun = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = sourceSafUri,
            archiveName = sourceZipFile.name,
            entries = entries,
            targetDir = fallbackTargetDir,
            isHatchMode = true,
            targetTreeUri = destTreeUri,
            targetFolderDocUri = destSubfolderDocUri
        ).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertTrue("Expected simulated crash to fire", crashed.get())
        assertEquals(ExtractionStatus.ERROR, crashRun.last().status)

        val resumeState = ResumeJournalManager.loadPendingStates(context).single()
        val restoreElig = ResumeJournalManager.assessRestoreEligibility(resumeState, context)
        assertTrue("Expected Restorable, got $restoreElig", restoreElig is ResumeJournalManager.RestoreEligibility.Restorable)
        val restorable = restoreElig as ResumeJournalManager.RestoreEligibility.Restorable
        assertEquals("assets/large_deflated.bin", restorable.pendingDeflateTailSwapEntryPath)
        assertEquals(2, restorable.remainingCount)

        // Execute PUBLIC Restore Remaining ZIP on pure SAF storage
        val restoreEmissions = KaiHatchEngine.restoreInterruptedZipArchive(
            context = context,
            resumeState = resumeState
        ).toList()
        assertTrue("Restore must emit FINALIZING status", restoreEmissions.any { it.status == ExtractionStatus.FINALIZING })
        val finalRestore = restoreEmissions.last()
        assertEquals(
            "SAF Restore Remaining ZIP must conclude with CANCELLED (error=${finalRestore.errorMessage})",
            ExtractionStatus.CANCELLED,
            finalRestore.status
        )

        // 1. ONLY the interrupted entry `assets/large_deflated.bin` should be extracted in SAF destination
        val extractedBase = File(restoreRoot, "restore_out")
        val finishedLarge = File(extractedBase, "assets/large_deflated.bin")
        assertTrue("Interrupted entry must be finished and verified", finishedLarge.exists())
        assertEquals(
            sha256(expectedPayloads.getValue("assets/large_deflated.bin")),
            sha256(finishedLarge)
        )
        assertFalse("Unrelated remaining entry docs/alpha.bin must NOT be extracted", File(extractedBase, "docs/alpha.bin").exists())
        assertFalse("Unrelated remaining entry docs/readme.txt must NOT be extracted", File(extractedBase, "docs/readme.txt").exists())

        // 2. Original SAF archive file must open cleanly in standard java.util.zip.ZipFile with exact remaining entries
        assertTrue("Original SAF archive must remain in place", sourceZipFile.exists())
        val allZipFiles = restoreRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".zip") }.toList()
        assertEquals("Must NOT create a second ZIP file", listOf(sourceZipFile.canonicalPath), allZipFiles.map { it.canonicalPath })

        ZipFile(sourceZipFile).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("docs/alpha.bin", "docs/readme.txt"), names)
            for (remName in names) {
                val actualBytes = zip.getInputStream(zip.getEntry(remName)).readBytes()
                assertArrayEquals("Remaining entry $remName bytes mismatch", expectedPayloads.getValue(remName), actualBytes)
            }
        }
        assertTrue("Journals must be cleared only after restored ZIP validation succeeds", ResumeJournalManager.loadPendingStates(context).isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // 4. Storage-Provider Failures: Read-Only, Non-Seekable Pipe, Failed fsync, Corrupted/Truncated Partial Output
    // -----------------------------------------------------------------------------------------

    @Test
    fun storageProviderFailures_failClosedAndPreserveData() = runBlocking {
        // 4a. Non-seekable stream/pipe descriptor (ESPIPE) and read-only PFD MUST be rejected by probeTransactableFileDescriptor
        val probeTargetFile = File(tempRoot, "probe_ro_check.bin").apply { writeBytes(ByteArray(4096) { 0x33 }) }
        ParcelFileDescriptor.open(probeTargetFile, ParcelFileDescriptor.MODE_READ_ONLY).use { roPfd ->
            FileInputStream(roPfd.fileDescriptor).use { fis ->
                val roProbe = StorageHelper.probeTransactableFileDescriptor(
                    fd = roPfd.fileDescriptor,
                    channel = fis.channel,
                    requireWriteAndTruncate = true
                )
                assertFalse("Read-only PFD MUST be rejected for transactional write/truncate", roProbe.isSupported)
                assertNotNull(roProbe.reason)
            }
            val nonSeekableChannel = object : java.nio.channels.FileChannel() {
                override fun read(dst: java.nio.ByteBuffer?): Int = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun read(dsts: Array<out java.nio.ByteBuffer>?, offset: Int, length: Int): Long = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun write(src: java.nio.ByteBuffer?): Int = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun write(srcs: Array<out java.nio.ByteBuffer>?, offset: Int, length: Int): Long = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun position(): Long = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun position(newPosition: Long): java.nio.channels.FileChannel = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun size(): Long = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun truncate(size: Long): java.nio.channels.FileChannel = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun force(metaData: Boolean) = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun transferTo(position: Long, count: Long, target: java.nio.channels.WritableByteChannel?): Long = 0L
                override fun transferFrom(src: java.nio.channels.ReadableByteChannel?, position: Long, count: Long): Long = 0L
                override fun read(dst: java.nio.ByteBuffer?, position: Long): Int = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun write(src: java.nio.ByteBuffer?, position: Long): Int = throw java.io.IOException("Illegal seek (ESPIPE)")
                override fun map(mode: MapMode?, position: Long, size: Long): java.nio.MappedByteBuffer = throw UnsupportedOperationException()
                override fun lock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock = throw UnsupportedOperationException()
                override fun tryLock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock? = null
                override fun implCloseChannel() {}
            }
            val pipeProbe = StorageHelper.probeTransactableFileDescriptor(
                fd = roPfd.fileDescriptor,
                channel = nonSeekableChannel,
                requireWriteAndTruncate = true
            )
            assertFalse("Non-seekable pipe/stream descriptor MUST be rejected", pipeProbe.isSupported)
            assertNotNull(pipeProbe.reason)
        }

        // Prepare an interrupted SAF tail-swap state to test provider failures against it
        StorageHelper.allFilesAccessOverrideForTest = false
        val failRoot = File(removableSafRoot, "provider_failures").apply { mkdirs() }
        RemovableSafDocumentsProvider.reset(failRoot)

        val sourceZipFile = File(failRoot, "fail_cases.zip")
        val expectedPayloads = createMultiEntryLargeDeflateZip(sourceZipFile)
        val sourceDocId = RemovableSafDocumentsProvider.registerFile(sourceZipFile, "9016-4EF8:fail_cases.zip")
        val sourceSafUri = DocumentsContract.buildDocumentUri(REMOVABLE_SAF_AUTHORITY, sourceDocId)

        val destTreeUri = DocumentsContract.buildTreeDocumentUri(REMOVABLE_SAF_AUTHORITY, "9016-4EF8:root")
        val destSubfolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, destTreeUri, "fail_out")!!
        val fallbackTargetDir = File(context.filesDir, "extracted/fail_out")

        val entries = context.contentResolver.openFileDescriptor(sourceSafUri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                ZipParser.parseZipChannel(fis.channel)
            }
        }

        // 4b. Live extraction with failing fsync MUST fail upfront BEFORE mutating source archive
        val originalSourceSha = sha256(sourceZipFile)
        val originalSourceLen = sourceZipFile.length()
        StorageHelper.forceFsyncFailureForTest = { true }
        val liveFsyncFailEmissions = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = sourceSafUri,
            archiveName = sourceZipFile.name,
            entries = entries,
            targetDir = fallbackTargetDir,
            isHatchMode = true,
            targetTreeUri = destTreeUri,
            targetFolderDocUri = destSubfolderDocUri
        ).toList()
        StorageHelper.forceFsyncFailureForTest = null
        assertEquals(ExtractionStatus.ERROR, liveFsyncFailEmissions.last().status)
        assertTrue(
            "Error must clearly explain fsync/capability requirement: ${liveFsyncFailEmissions.last().errorMessage}",
            liveFsyncFailEmissions.last().errorMessage.orEmpty().contains("fsync", ignoreCase = true)
        )
        assertEquals("Source archive must remain completely untouched when fsync fails upfront", originalSourceLen, sourceZipFile.length())
        assertEquals(originalSourceSha, sha256(sourceZipFile))

        // Now create a real interrupted state at PREPARED phase (chunk 1)
        val crashed = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_TAILSWAP_PREPARED_CKPT &&
                    detail.contains("c=1") &&
                    crashed.compareAndSet(false, true)
                ) {
                    throw SimulatedFaultException(
                        point = point,
                        occurrence = 1,
                        archiveBytesAtFault = currentArchiveBytes,
                        message = "Crash at PREPARED c=1"
                    )
                }
            }
        }
        val crashRun = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = sourceSafUri,
            archiveName = sourceZipFile.name,
            entries = entries,
            targetDir = fallbackTargetDir,
            isHatchMode = true,
            targetTreeUri = destTreeUri,
            targetFolderDocUri = destSubfolderDocUri
        ).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        assertTrue("Expected simulated crash at PREPARED c=1", crashed.get())
        assertEquals(ExtractionStatus.ERROR, crashRun.last().status)

        val resumeState = ResumeJournalManager.loadPendingStates(context).single()
        val ckpt = ResumeJournalManager.loadDeflateTailSwapCheckpoint(resumeState.stateFilePath)!!
        val partialSafOutputFile = File(failRoot, "fail_out/assets/large_deflated.bin")
        val savedValidPartialBytes = partialSafOutputFile.readBytes()

        // 4c. Read-only provider denies "rw" -> MUST fail closed as Unrecoverable + classified as storage limitation
        RemovableSafDocumentsProvider.readOnlyMode.set(true)
        val roResumeElig = ResumeJournalManager.assessResumeEligibility(resumeState, context)
        val roRestoreElig = ResumeJournalManager.assessRestoreEligibility(resumeState, context)
        assertTrue("Read-only SAF provider must be Unrecoverable for Resume", roResumeElig is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        assertTrue("Read-only SAF provider must be Unrecoverable for Restore", roRestoreElig is ResumeJournalManager.RestoreEligibility.Unrecoverable)
        val roCard = resolveRecoveryCardState(resumeState, roResumeElig, roRestoreElig)
        assertEquals(RecoveryCardKind.UNAVAILABLE, roCard.kind)
        assertTrue("Card must surface storage/SAF limitation notice", roCard.isSafDirectPosixLimitation)
        RemovableSafDocumentsProvider.readOnlyMode.set(false)

        // 4d. Failed fsync during eligibility AND during recovery -> MUST fail closed and preserve checkpoint & partial output!
        StorageHelper.forceFsyncFailureForTest = { true }
        val fsyncResumeElig = ResumeJournalManager.assessResumeEligibility(resumeState, context)
        assertTrue("Failed fsync must mark Resume Unrecoverable", fsyncResumeElig is ResumeJournalManager.ResumeEligibility.Unrecoverable)
        val fsyncRecoveryAttempt = KaiHatchEngine.resumeInterruptedExtraction(context, resumeState).toList()
        assertEquals(ExtractionStatus.ERROR, fsyncRecoveryAttempt.last().status)
        assertTrue("Partial output file MUST be preserved after failed fsync recovery", partialSafOutputFile.exists())
        assertNotNull("Checkpoint MUST be preserved after failed fsync recovery", ResumeJournalManager.loadDeflateTailSwapCheckpoint(resumeState.stateFilePath))
        StorageHelper.forceFsyncFailureForTest = null

        // 4e. Corrupted partial SAF output prefix byte -> prefix CRC32 mismatch MUST fail closed
        val corruptedBytes = savedValidPartialBytes.copyOf()
        corruptedBytes[0] = (corruptedBytes[0].toInt() xor 0xFF).toByte()
        partialSafOutputFile.writeBytes(corruptedBytes)
        val corruptOpen = StorageHelper.openExistingSafOutputForRecovery(
            context = context,
            treeUri = destTreeUri,
            rootFolderDocUri = destSubfolderDocUri,
            relativePath = ckpt.entryPath,
            expectedOffset = ckpt.outputBytesWritten,
            expectedPrefixCrc32 = ckpt.runningCrc32
        )
        assertTrue("Corrupted prefix CRC32 must fail openExistingSafOutputForRecovery", corruptOpen is SafRecoveryOutputOpenResult.Failed)
        val corruptElig = ResumeJournalManager.assessResumeEligibility(resumeState, context)
        assertTrue("Corrupted partial SAF output must be Unrecoverable", corruptElig is ResumeJournalManager.ResumeEligibility.Unrecoverable)

        // 4f. Truncated partial SAF output shorter than checkpoint offset -> MUST fail closed
        partialSafOutputFile.writeBytes(savedValidPartialBytes.copyOf((ckpt.outputBytesWritten / 2L).toInt()))
        val truncOpen = StorageHelper.openExistingSafOutputForRecovery(
            context = context,
            treeUri = destTreeUri,
            rootFolderDocUri = destSubfolderDocUri,
            relativePath = ckpt.entryPath,
            expectedOffset = ckpt.outputBytesWritten,
            expectedPrefixCrc32 = ckpt.runningCrc32
        )
        assertTrue("Truncated partial SAF output must fail openExistingSafOutputForRecovery", truncOpen is SafRecoveryOutputOpenResult.Failed)

        // 4g. Restore valid partial output -> recovery STILL succeeds cleanly (proving earlier failures caused zero corruption!)
        partialSafOutputFile.writeBytes(savedValidPartialBytes)
        val finalCleanResume = KaiHatchEngine.resumeInterruptedExtraction(context, resumeState).toList()
        assertEquals(ExtractionStatus.COMPLETED, finalCleanResume.last().status)
        for ((relPath, expectedBytes) in expectedPayloads) {
            val outFile = File(failRoot, "fail_out/$relPath")
            assertEquals(sha256(expectedBytes), sha256(outFile))
        }
    }
}
