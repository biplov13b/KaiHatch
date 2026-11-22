package com.biplov.kaihatch.engine

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression Tests for Extraction Destination Routing:
 * - Direct filesystem destinations
 * - SAF document-tree destinations (when direct POSIX path resolution falls back to files/extracted)
 * - Large STORED ZIP entries (>= 4 MB) in Hatch Mode & Standard Mode
 * - Large DEFLATED ZIP entries (>= 4 MB) in Hatch Mode & Standard Mode
 * - ZIP64 archives (explicit 0x0001 64-bit extended information headers + EOCD64)
 * - Mixed-compression archives (small DEFLATED + large STORED + nested folders, matching the 64 GB / 27 MB bug)
 * - Extraction failures when SAF destination fails (verifying zero silent fallback to files/extracted)
 * - Protection of pre-existing legacy user files in files/extracted
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestinationRoutingRegressionTest {

    companion object {
        const val TEST_AUTHORITY = "com.biplov.kaihatch.test.saf.dest"
    }

    /**
     * Full in-process DocumentsProvider backed by a separate physical directory (`safBackingRoot`)
     * completely outside `context.filesDir` and `context.cacheDir`, using opaque document IDs
     * (`tree_root`, `doc_1`, ...) so `StorageHelper.tryResolveDirectWritableSubfolder` returns `null`
     * just like non-primary/SD-card/custom SAF providers or FUSE-restricted folders on Android.
     */
    class TestSafTreeDocumentsProvider : android.content.ContentProvider() {
        companion object {
            lateinit var backingRootDir: File
            val docIdToFile = ConcurrentHashMap<String, File>()
            val failCreateDocument = AtomicBoolean(false)
            val failOpenDescriptor = AtomicBoolean(false)
            private var nextId = 1

            @Synchronized
            fun reset(root: File) {
                backingRootDir = root
                docIdToFile.clear()
                failCreateDocument.set(false)
                failOpenDescriptor.set(false)
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
            val mime = if (file.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                "application/octet-stream"
            }
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

        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
            if (extras == null) return null
            @Suppress("DEPRECATION")
            val targetUri = extras.getParcelable<Uri>("uri") ?: return null
            return when (method) {
                "android:createDocument" -> {
                    val mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return null
                    if (failCreateDocument.get() && mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                        return null
                    }
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
                        DocumentsContract.buildDocumentUri(TEST_AUTHORITY, newDocId)
                    }
                    android.os.Bundle().apply {
                        putParcelable("uri", createdUri)
                    }
                }
                "android:deleteDocument" -> {
                    val docId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull() ?: return null
                    val file = docIdToFile[docId] ?: return null
                    file.deleteRecursively()
                    docIdToFile.remove(docId)
                    android.os.Bundle()
                }
                else -> super.call(method, arg, extras)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (failOpenDescriptor.get()) {
                throw java.io.IOException("Simulated SAF openFile descriptor failure")
            }
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val file = docIdToFile[docId] ?: return null
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, pfdMode)
        }
    }

    private lateinit var context: Context
    private lateinit var tempWorkspace: File
    private lateinit var safBackingRoot: File
    private lateinit var directPosixRoot: File
    private lateinit var safTreeUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StorageHelper.clearSafDirCache()

        tempWorkspace = File(System.getProperty("java.io.tmpdir"), "kaihatch_phase1_routing_${System.nanoTime()}").apply {
            mkdirs()
        }
        safBackingRoot = File(tempWorkspace, "saf_external_volume").apply { mkdirs() }
        directPosixRoot = File(tempWorkspace, "direct_posix_volume").apply { mkdirs() }

        // Clean app-private files/extracted before each test
        StorageHelper.getExtractedDir(context).deleteRecursively()
        StorageHelper.getExtractedDir(context).mkdirs()

        TestSafTreeDocumentsProvider.reset(safBackingRoot)
        val info = ProviderInfo().apply {
            authority = TEST_AUTHORITY
            exported = true
            grantUriPermissions = true
        }
        Robolectric.buildContentProvider(TestSafTreeDocumentsProvider::class.java).create(info)

        safTreeUri = DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, "opaque_tree_root")
    }

    @After
    fun tearDown() {
        StorageHelper.clearSafDirCache()
        StorageHelper.getExtractedDir(context).deleteRecursively()
        tempWorkspace.deleteRecursively()
    }

    private fun deterministicBytes(size: Int, seed: Int): ByteArray {
        val out = ByteArray(size)
        var s = seed.toLong() xor 0x6C8E9CF570932BD5L
        var i = 0
        while (i < size) {
            s = s xor (s ushr 12)
            s = s xor (s shl 25)
            s = s xor (s ushr 27)
            val v = s * 0x2545F4914F6CDD1DL
            val rem = (size - i).coerceAtMost(8)
            for (b in 0 until rem) {
                out[i + b] = ((v ushr (b * 8)) and 0xFF).toByte()
            }
            i += rem
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SpecEntry(
        val path: String,
        val bytes: ByteArray,
        val stored: Boolean,
        val isDirectory: Boolean = false
    )

    private fun createZipArchive(dest: File, entries: List<SpecEntry>): Map<String, String> {
        val hashes = LinkedHashMap<String, String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
            for (item in entries) {
                if (item.isDirectory) {
                    val dirPath = if (item.path.endsWith("/")) item.path else "${item.path}/"
                    val ze = ZipEntry(dirPath).apply {
                        method = ZipEntry.STORED
                        size = 0L
                        compressedSize = 0L
                        crc = 0L
                    }
                    zos.putNextEntry(ze)
                    zos.closeEntry()
                } else {
                    val ze = ZipEntry(item.path)
                    if (item.stored) {
                        val crc = CRC32().apply { update(item.bytes) }.value
                        ze.method = ZipEntry.STORED
                        ze.size = item.bytes.size.toLong()
                        ze.compressedSize = item.bytes.size.toLong()
                        ze.crc = crc
                    } else {
                        ze.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(ze)
                    zos.write(item.bytes)
                    zos.closeEntry()
                    hashes[item.path] = sha256(item.bytes)
                }
            }
        }
        return hashes
    }

    /**
     * Builds an explicit ZIP64 archive containing a STORED entry and a DEFLATED entry with
     * `0xFFFFFFFF` sentinel fields, `0x0001` ZIP64 extended information extra fields,
     * ZIP64 End of Central Directory Record (`0x06064b50`), and ZIP64 EOCD Locator (`0x07064b50`).
     */
    private fun createExplicitZip64Archive(dest: File, entries: List<SpecEntry>): Map<String, String> {
        val hashes = LinkedHashMap<String, String>()
        data class WrittenEntryMeta(
            val pathBytes: ByteArray,
            val method: Int,
            val crc32: Long,
            val compSize: Long,
            val uncompSize: Long,
            val localHeaderOffset: Long
        )
        val written = ArrayList<WrittenEntryMeta>()

        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(0L)
            for (item in entries) {
                val offset = raf.filePointer
                val pathBytes = item.path.toByteArray(Charsets.UTF_8)
                val crc = CRC32().apply { update(item.bytes) }.value
                val payloadBytes = if (item.stored) {
                    item.bytes
                } else {
                    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                    val baos = ByteArrayOutputStream()
                    try {
                        deflater.setInput(item.bytes)
                        deflater.finish()
                        val buf = ByteArray(16 * 1024)
                        while (!deflater.finished()) {
                            val n = deflater.deflate(buf)
                            if (n > 0) baos.write(buf, 0, n)
                        }
                    } finally {
                        deflater.end()
                    }
                    baos.toByteArray()
                }
                val method = if (item.stored) 0 else 8
                val uncompSize = item.bytes.size.toLong()
                val compSize = payloadBytes.size.toLong()

                // Local File Header with 0x0001 ZIP64 Extra Field (20 bytes: headerId=0x0001, dataSize=16, uncomp8, comp8)
                val lfhExtra = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(0x0001.toShort())
                    putShort(16.toShort())
                    putLong(uncompSize)
                    putLong(compSize)
                }.array()

                val lfh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(0x04034b50)
                    putShort(45.toShort()) // version needed 4.5 (ZIP64)
                    putShort(0.toShort())
                    putShort(method.toShort())
                    putShort(0.toShort())
                    putShort(0.toShort())
                    putInt(crc.toInt())
                    putInt(0xFFFFFFFF.toInt()) // ZIP64 sentinel
                    putInt(0xFFFFFFFF.toInt()) // ZIP64 sentinel
                    putShort(pathBytes.size.toShort())
                    putShort(lfhExtra.size.toShort())
                }.array()

                raf.write(lfh)
                raf.write(pathBytes)
                raf.write(lfhExtra)
                raf.write(payloadBytes)

                written.add(
                    WrittenEntryMeta(
                        pathBytes = pathBytes,
                        method = method,
                        crc32 = crc,
                        compSize = compSize,
                        uncompSize = uncompSize,
                        localHeaderOffset = offset
                    )
                )
                hashes[item.path] = sha256(item.bytes)
            }

            val cdOffset = raf.filePointer
            for (w in written) {
                val cdExtra = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(0x0001.toShort())
                    putShort(24.toShort())
                    putLong(w.uncompSize)
                    putLong(w.compSize)
                    putLong(w.localHeaderOffset)
                }.array()

                val cdfh = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(0x02014b50)
                    putShort(45.toShort())
                    putShort(45.toShort())
                    putShort(0.toShort())
                    putShort(w.method.toShort())
                    putShort(0.toShort())
                    putShort(0.toShort())
                    putInt(w.crc32.toInt())
                    putInt(0xFFFFFFFF.toInt())
                    putInt(0xFFFFFFFF.toInt())
                    putShort(w.pathBytes.size.toShort())
                    putShort(cdExtra.size.toShort())
                    putShort(0.toShort())
                    putShort(0.toShort())
                    putShort(0.toShort())
                    putInt(0)
                    putInt(0xFFFFFFFF.toInt())
                }.array()

                raf.write(cdfh)
                raf.write(w.pathBytes)
                raf.write(cdExtra)
            }
            val cdSize = raf.filePointer - cdOffset
            val eocd64Offset = raf.filePointer

            // ZIP64 End of Central Directory Record (56 bytes)
            val eocd64 = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x06064b50)
                putLong(44L)
                putShort(45.toShort())
                putShort(45.toShort())
                putInt(0)
                putInt(0)
                putLong(written.size.toLong())
                putLong(written.size.toLong())
                putLong(cdSize)
                putLong(cdOffset)
            }.array()
            raf.write(eocd64)

            // ZIP64 End of Central Directory Locator (20 bytes)
            val locator64 = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x07064b50)
                putInt(0)
                putLong(eocd64Offset)
                putInt(1)
            }.array()
            raf.write(locator64)

            // Standard EOCD (22 bytes) with ZIP64 sentinels
            val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x06054b50)
                putShort(0.toShort())
                putShort(0.toShort())
                putShort(0xFFFF.toShort())
                putShort(0xFFFF.toShort())
                putInt(0xFFFFFFFF.toInt())
                putInt(0xFFFFFFFF.toInt())
                putShort(0.toShort())
            }.array()
            raf.write(eocd)
        }
        return hashes
    }

    /**
     * Requirement 1: Verify destination classification (`isDirectPosixDestination` / `isAppInternalFallbackDir`).
     * Ensures an internal fallback directory (`context.filesDir/extracted` or `context.cacheDir`) is NEVER
     * classified as a direct POSIX destination when `targetTreeUri != null`.
     */
    @Test
    fun destinationClassification_neverTreatsInternalFallbackAsDirectPosixWhenSafSelected() {
        val internalExtractedFallback = File(StorageHelper.getExtractedDir(context), "archive_extracted")
        val internalCacheFallback = File(context.cacheDir, "archive_extracted")
        val externalDirectDir = File(directPosixRoot, "archive_extracted")

        assertTrue(StorageHelper.isAppInternalFallbackDir(context, internalExtractedFallback))
        assertTrue(StorageHelper.isAppInternalFallbackDir(context, internalCacheFallback))
        assertFalse(StorageHelper.isAppInternalFallbackDir(context, externalDirectDir))

        // When targetTreeUri != null and targetDir is an internal fallback directory -> MUST be false!
        assertFalse(
            "Internal files/extracted fallback must NOT be classified as direct POSIX when SAF tree is selected",
            StorageHelper.isDirectPosixDestination(context, internalExtractedFallback, safTreeUri)
        )
        assertFalse(
            "Internal cacheDir fallback must NOT be classified as direct POSIX when SAF tree is selected",
            StorageHelper.isDirectPosixDestination(context, internalCacheFallback, safTreeUri)
        )

        // When targetTreeUri != null and targetDir is a real external POSIX folder -> true
        assertTrue(
            StorageHelper.isDirectPosixDestination(context, externalDirectDir, safTreeUri)
        )

        // When targetTreeUri == null (explicit local extraction) -> true
        assertTrue(
            StorageHelper.isDirectPosixDestination(context, externalDirectDir, null)
        )
    }

    /**
     * Requirement 2 & 4: Large STORED ZIP entry (>= 4 MB) extracted to SAF document-tree destination
     * in Hatch Mode when direct POSIX resolution falls back to `context.filesDir/extracted`.
     * Verifies 100% of extracted bytes land in the SAF destination and 0 files are created in `files/extracted`.
     */
    @Test
    fun largeStoredEntry_hatchModeSafDestination_writesOnlyToSafAndZeroBytesToInternalStorage() = runBlocking {
        val storedBytes = deterministicBytes(4 * 1024 * 1024 + 512 * 1024, seed = 101) // 4.5 MB STORED (>= 4 MB threshold)
        val archiveFile = File(tempWorkspace, "large_stored_hatch.zip")
        val expectedHashes = createZipArchive(
            archiveFile,
            listOf(SpecEntry("video/cutscene_hd.bin", storedBytes, stored = true))
        )

        val subfolderName = "large_stored_hatch_extracted"
        val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
        assertNotNull("Expected SAF subfolder URI to be created", targetFolderDocUri)

        // Simulate tryResolveDirectWritableSubfolder returning null -> fallback targetDir in files/extracted
        val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = fallbackTargetDir,
            isHatchMode = true,
            context = context,
            targetTreeUri = safTreeUri,
            targetFolderDocUri = targetFolderDocUri
        ).toList()

        val terminal = emissions.last()
        assertEquals("Expected COMPLETED but got ${terminal.errorMessage}", ExtractionStatus.COMPLETED, terminal.status)
        assertTrue("Source archive must be consumed/deleted in Hatch Mode", !archiveFile.exists() || archiveFile.length() == 0L)

        // Verify output exists in SAF backing volume with exact SHA-256
        val safExtractedFile = File(safBackingRoot, "$subfolderName/video/cutscene_hd.bin")
        assertTrue("Large STORED entry must be written to SAF destination", safExtractedFile.exists())
        assertEquals(storedBytes.size.toLong(), safExtractedFile.length())
        assertEquals(expectedHashes["video/cutscene_hd.bin"], sha256File(safExtractedFile))

        // Verify ZERO files/folders were created in app-private files/extracted
        assertFalse(
            "Fallback directory in context.filesDir/extracted must NOT be created",
            fallbackTargetDir.exists()
        )
        val internalExtractedFiles = StorageHelper.getExtractedDir(context).walkTopDown().filter { it.isFile }.toList()
        assertTrue(
            "Expected zero files in context.filesDir/extracted, found: $internalExtractedFiles",
            internalExtractedFiles.isEmpty()
        )
    }

    /**
     * Requirement 2 & 4: Large DEFLATED ZIP entry (>= 4 MB) extracted to SAF document-tree destination
     * in Hatch Mode when direct POSIX resolution falls back to `context.filesDir/extracted`.
     */
    @Test
    fun largeDeflatedEntry_hatchModeSafDestination_writesOnlyToSafAndZeroBytesToInternalStorage() = runBlocking {
        val deflatedBytes = deterministicBytes(4 * 1024 * 1024 + 384 * 1024, seed = 202) // ~4.38 MB uncompressed
        val archiveFile = File(tempWorkspace, "large_deflated_hatch.zip")
        val expectedHashes = createZipArchive(
            archiveFile,
            listOf(SpecEntry("assets/pack_main.dat", deflatedBytes, stored = false))
        )

        val subfolderName = "large_deflated_hatch_extracted"
        val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
        val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = fallbackTargetDir,
            isHatchMode = true,
            context = context,
            targetTreeUri = safTreeUri,
            targetFolderDocUri = targetFolderDocUri
        ).toList()

        val terminal = emissions.last()
        assertEquals("Expected COMPLETED: ${terminal.errorMessage}", ExtractionStatus.COMPLETED, terminal.status)

        val safExtractedFile = File(safBackingRoot, "$subfolderName/assets/pack_main.dat")
        assertTrue("Large DEFLATED entry must be written to SAF destination", safExtractedFile.exists())
        assertEquals(deflatedBytes.size.toLong(), safExtractedFile.length())
        assertEquals(expectedHashes["assets/pack_main.dat"], sha256File(safExtractedFile))

        assertFalse(
            "Fallback directory in context.filesDir/extracted must NOT exist",
            fallbackTargetDir.exists()
        )
    }

    /**
     * Requirement 2 & 4: Mixed-compression archive (matching the reported 64 GB / 27 MB bug):
     * Contains small DEFLATED entries, nested directories, and a large STORED entry (>= 4 MB)
     * extracted in both Hatch Mode and Standard Mode to a SAF destination.
     * Verifies ALL entries (both small DEFLATED and large STORED) land in the SAF destination
     * and never split across SAF and `context.filesDir/extracted`.
     */
    @Test
    fun mixedCompressionArchive_safDestination_neverSplitsOutputBetweenSafAndInternalStorage() = runBlocking {
        val smallDeflated1 = deterministicBytes(120 * 1024, seed = 301)
        val smallDeflated2 = deterministicBytes(95 * 1024, seed = 302)
        val largeStored = deterministicBytes(4 * 1024 * 1024 + 256 * 1024, seed = 303) // 4.25 MB STORED

        for (isHatchMode in listOf(false, true)) {
            val modeTag = if (isHatchMode) "hatch" else "standard"
            val archiveFile = File(tempWorkspace, "mixed_$modeTag.zip")
            val expectedHashes = createZipArchive(
                archiveFile,
                listOf(
                    SpecEntry("docs/", ByteArray(0), stored = true, isDirectory = true),
                    SpecEntry("docs/readme.txt", smallDeflated1, stored = false),
                    SpecEntry("config/settings.json", smallDeflated2, stored = false),
                    SpecEntry("media/world_map.pak", largeStored, stored = true)
                )
            )

            val subfolderName = "mixed_${modeTag}_extracted"
            val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
            val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

            val emissions = KaiHatchEngine.extractArchive(
                archiveFile = archiveFile,
                targetDir = fallbackTargetDir,
                isHatchMode = isHatchMode,
                context = context,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = targetFolderDocUri
            ).toList()

            val terminal = emissions.last()
            assertEquals("Mode=$modeTag failed: ${terminal.errorMessage}", ExtractionStatus.COMPLETED, terminal.status)

            // Verify every file & directory was written to the SAF destination
            val outRoot = File(safBackingRoot, subfolderName)
            assertTrue("Expected docs/ directory in SAF destination ($modeTag)", File(outRoot, "docs").isDirectory)
            for ((relPath, expectedSha) in expectedHashes) {
                val safFile = File(outRoot, relPath)
                assertTrue("Missing '$relPath' in SAF destination ($modeTag)", safFile.exists())
                assertEquals("SHA-256 mismatch for '$relPath' ($modeTag)", expectedSha, sha256File(safFile))
            }

            // Verify zero files in context.filesDir/extracted
            assertFalse(
                "Internal fallback dir must not exist in mode=$modeTag",
                fallbackTargetDir.exists()
            )
            val leakedFiles = StorageHelper.getExtractedDir(context).walkTopDown().filter { it.isFile }.toList()
            assertTrue("Leaked internal files in mode=$modeTag: $leakedFiles", leakedFiles.isEmpty())
        }
    }

    /**
     * Requirement 2 & 4: Explicit ZIP64 archive extracted to SAF destination in Hatch Mode and Standard Mode.
     */
    @Test
    fun zip64Archive_safDestination_routesAllEntriesToSafInBothModes() = runBlocking {
        val storedZip64Payload = deterministicBytes(4 * 1024 * 1024 + 128 * 1024, seed = 401)
        val deflatedZip64Payload = deterministicBytes(256 * 1024, seed = 402)

        for (isHatchMode in listOf(false, true)) {
            val modeTag = if (isHatchMode) "hatch64" else "std64"
            val archiveFile = File(tempWorkspace, "archive_$modeTag.zip")
            val expectedHashes = createExplicitZip64Archive(
                archiveFile,
                listOf(
                    SpecEntry("zip64/header_meta.bin", deflatedZip64Payload, stored = false),
                    SpecEntry("zip64/large_stored64.bin", storedZip64Payload, stored = true)
                )
            )

            val subfolderName = "archive_${modeTag}_extracted"
            val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
            val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

            val emissions = KaiHatchEngine.extractArchive(
                archiveFile = archiveFile,
                targetDir = fallbackTargetDir,
                isHatchMode = isHatchMode,
                context = context,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = targetFolderDocUri
            ).toList()

            val terminal = emissions.last()
            assertEquals("ZIP64 mode=$modeTag failed: ${terminal.errorMessage}", ExtractionStatus.COMPLETED, terminal.status)

            val outRoot = File(safBackingRoot, subfolderName)
            for ((relPath, expectedSha) in expectedHashes) {
                val safFile = File(outRoot, relPath)
                assertTrue("Missing ZIP64 entry '$relPath' in SAF destination ($modeTag)", safFile.exists())
                assertEquals("ZIP64 SHA-256 mismatch for '$relPath' ($modeTag)", expectedSha, sha256File(safFile))
            }

            assertFalse("Internal fallback dir must not exist for ZIP64 ($modeTag)", fallbackTargetDir.exists())
        }
    }

    /**
     * Requirement 4: Direct filesystem destination routing works for both Standard Mode and Hatch Mode.
     */
    @Test
    fun directFilesystemDestination_extractsCorrectlyWithoutCreatingInternalFallbackFiles() = runBlocking {
        val payload1 = deterministicBytes(128 * 1024, seed = 501)
        val payload2 = deterministicBytes(4 * 1024 * 1024 + 64 * 1024, seed = 502)
        val archiveFile = File(tempWorkspace, "direct_posix.zip")
        val expectedHashes = createZipArchive(
            archiveFile,
            listOf(
                SpecEntry("sub/small.bin", payload1, stored = false),
                SpecEntry("sub/large_stored.bin", payload2, stored = true)
            )
        )

        val directTargetDir = File(directPosixRoot, "direct_posix_extracted")
        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = directTargetDir,
            isHatchMode = true,
            context = context,
            targetTreeUri = null,
            targetFolderDocUri = null
        ).toList()

        val terminal = emissions.last()
        assertEquals(ExtractionStatus.COMPLETED, terminal.status)

        for ((relPath, expectedSha) in expectedHashes) {
            val f = File(directTargetDir, relPath)
            assertTrue("Expected direct POSIX output file $relPath", f.exists())
            assertEquals(expectedSha, sha256File(f))
        }

        val leakedFiles = StorageHelper.getExtractedDir(context).walkTopDown().filter { it.isFile }.toList()
        assertTrue("Expected zero files in context.filesDir/extracted, found $leakedFiles", leakedFiles.isEmpty())
    }

    /**
     * Requirement 2 & 4: Extraction failure when SAF destination rejects file creation or descriptor open.
     * Verifies that extraction fails explicitly with `ExtractionStatus.ERROR` and NEVER silently redirects
     * output into `context.filesDir/extracted`.
     */
    @Test
    fun safDestinationFailure_emitsExplicitErrorAndNeverSilentlyRedirectsToInternalStorage() = runBlocking {
        val smallDeflated = deterministicBytes(64 * 1024, seed = 601)
        val largeStored = deterministicBytes(4 * 1024 * 1024 + 64 * 1024, seed = 602)

        for ((caseName, entrySpec) in listOf(
            "small_deflated" to SpecEntry("fail/small.bin", smallDeflated, stored = false),
            "large_stored" to SpecEntry("fail/large_stored.bin", largeStored, stored = true)
        )) {
            val archiveFile = File(tempWorkspace, "fail_$caseName.zip")
            createZipArchive(archiveFile, listOf(entrySpec))

            val subfolderName = "fail_${caseName}_extracted"
            val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
            val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

            // Force SAF document creation for files to fail
            TestSafTreeDocumentsProvider.failCreateDocument.set(true)
            try {
                val emissions = KaiHatchEngine.extractArchive(
                    archiveFile = archiveFile,
                    targetDir = fallbackTargetDir,
                    isHatchMode = true,
                    context = context,
                    targetTreeUri = safTreeUri,
                    targetFolderDocUri = targetFolderDocUri
                ).toList()

                val terminal = emissions.last()
                assertEquals(
                    "Expected ExtractionStatus.ERROR when SAF output creation fails ($caseName)",
                    ExtractionStatus.ERROR,
                    terminal.status
                )
                assertNotNull(terminal.errorMessage)

                // CRITICAL: Verify NO silent fallback file was written to context.filesDir/extracted!
                assertFalse(
                    "Fallback directory in context.filesDir/extracted must NOT exist on SAF failure ($caseName)",
                    fallbackTargetDir.exists()
                )
                val leakedFiles = StorageHelper.getExtractedDir(context).walkTopDown().filter { it.isFile }.toList()
                assertTrue(
                    "Zero files may be written to context.filesDir/extracted on SAF failure ($caseName), found: $leakedFiles",
                    leakedFiles.isEmpty()
                )
            } finally {
                TestSafTreeDocumentsProvider.failCreateDocument.set(false)
            }
        }
    }

    /**
     * Requirement 5: Protect existing user data in `files/extracted`.
     * Pre-existing files in `context.filesDir/extracted` must remain untouched during new SAF extractions.
     */
    @Test
    fun existingLegacyFilesInInternalExtractedDir_arePreservedUntouched() = runBlocking {
        val legacyDir = File(StorageHelper.getExtractedDir(context), "legacy_user_archive_extracted").apply { mkdirs() }
        val legacyFile = File(legacyDir, "precious_user_data.bin")
        val legacyContent = deterministicBytes(8192, seed = 777)
        legacyFile.writeBytes(legacyContent)

        val archiveFile = File(tempWorkspace, "new_archive.zip")
        val payload = deterministicBytes(32 * 1024, seed = 778)
        createZipArchive(archiveFile, listOf(SpecEntry("new_file.bin", payload, stored = false)))

        val subfolderName = "new_archive_extracted"
        val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
        val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = fallbackTargetDir,
            isHatchMode = false,
            context = context,
            targetTreeUri = safTreeUri,
            targetFolderDocUri = targetFolderDocUri
        ).toList()

        assertEquals(ExtractionStatus.COMPLETED, emissions.last().status)

        // Legacy file must still exist and match byte-for-byte
        assertTrue("Pre-existing file in files/extracted must not be deleted", legacyFile.exists())
        assertArrayEquals(legacyContent, legacyFile.readBytes())
        // New extraction must be in SAF, not in files/extracted/new_archive_extracted
        assertTrue(File(safBackingRoot, "$subfolderName/new_file.bin").exists())
        assertFalse(fallbackTargetDir.exists())
    }

    /**
     * Requirement 2 & 4: Resumed interrupted Hatch Mode extraction with a SAF destination
     * preserves `targetTreeUriString` and `targetFolderDocUriString` in `ResumeJournalState`
     * and writes all remaining entries to the SAF destination rather than `context.filesDir/extracted`.
     */
    @Test
    fun resumeInterruptedExtraction_safDestination_preservesSafRoutingAndNeverWritesToInternalStorage() = runBlocking {
        val payload1 = deterministicBytes(64 * 1024, seed = 881)
        val payload2 = deterministicBytes(96 * 1024, seed = 882)
        val archiveFile = File(tempWorkspace, "resume_saf_routing.zip")
        val expectedHashes = createZipArchive(
            archiveFile,
            listOf(
                SpecEntry("part1/first.bin", payload1, stored = false),
                SpecEntry("part2/second.bin", payload2, stored = true)
            )
        )

        val subfolderName = "resume_saf_routing_extracted"
        val targetFolderDocUri = StorageHelper.getOrCreateDocumentSubfolder(context, safTreeUri, subfolderName)
        val fallbackTargetDir = File(StorageHelper.getExtractedDir(context), subfolderName)

        // Fault inject right after the first entry window is truncated so a resume journal checkpoint is saved
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            private var windowTruncates = 0
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.BEFORE_WINDOW_TRUNCATE) {
                    windowTruncates++
                    if (windowTruncates == 1) {
                        throw SimulatedFaultException(point, 1, currentArchiveBytes, "Simulated crash after first SAF entry")
                    }
                }
            }
        }

        try {
            val firstRun = KaiHatchEngine.extractArchive(
                archiveFile = archiveFile,
                targetDir = fallbackTargetDir,
                isHatchMode = true,
                context = context,
                targetTreeUri = safTreeUri,
                targetFolderDocUri = targetFolderDocUri
            ).toList()

            assertEquals(ExtractionStatus.ERROR, firstRun.last().status)
        } finally {
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        }

        val pendingStates = ResumeJournalManager.loadPendingStates(context)
        val savedState = pendingStates.firstOrNull { it.archiveName == archiveFile.name }
        assertNotNull("Expected saved ResumeJournalState for interrupted SAF extraction", savedState)
        assertEquals(safTreeUri.toString(), savedState!!.targetTreeUriString)
        assertEquals(targetFolderDocUri.toString(), savedState.targetFolderDocUriString)

        val resumedEmissions = KaiHatchEngine.resumeInterruptedExtraction(
            context = context,
            resumeState = savedState
        ).toList()

        val resumedTerminal = resumedEmissions.last()
        assertEquals("Expected resumed SAF extraction to complete: ${resumedTerminal.errorMessage}", ExtractionStatus.COMPLETED, resumedTerminal.status)

        val outRoot = File(safBackingRoot, subfolderName)
        for ((relPath, expectedSha) in expectedHashes) {
            val safFile = File(outRoot, relPath)
            assertTrue("Expected '$relPath' in SAF destination after resume", safFile.exists())
            assertEquals(expectedSha, sha256File(safFile))
        }

        assertFalse("Internal fallback dir must NOT exist after SAF resume", fallbackTargetDir.exists())
    }
}
