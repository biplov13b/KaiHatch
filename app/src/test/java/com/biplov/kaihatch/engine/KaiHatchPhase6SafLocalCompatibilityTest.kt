package com.biplov.kaihatch.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ResumeJournalState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 6 Regression Suite: Local SAF Storage Compatibility & Three Targeted Fixes:
 * 1. Persistable URI permission handling (`StorageHelper.takePersistablePermission`).
 * 2. Prevent Hatch Mode from silently switching to Standard Mode when writable access or truncation is unavailable.
 * 3. SAF resume eligibility checks validate actual source descriptor size and journal `slot0Crc` before permitting recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KaiHatchPhase6SafLocalCompatibilityTest {

    enum class ProviderMode {
        READ_WRITE_FILE,
        READ_ONLY_FILE,
        NON_TRUNCATABLE_PIPE
    }

    class LocalTestSafProvider : ContentProvider() {
        companion object {
            const val AUTHORITY = "com.biplov.kaihatch.test.safprovider"
            val fileRegistry = HashMap<String, File>()
            val modeRegistry = HashMap<String, ProviderMode>()

            fun registerFile(id: String, file: File, mode: ProviderMode): Uri {
                fileRegistry[id] = file
                modeRegistry[id] = mode
                return Uri.parse("content://$AUTHORITY/$id")
            }

            fun clear() {
                fileRegistry.clear()
                modeRegistry.clear()
            }
        }

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val id = uri.lastPathSegment ?: throw FileNotFoundException("Missing segment in $uri")
            val backingFile = fileRegistry[id] ?: throw FileNotFoundException("No backing file for $uri")
            val providerMode = modeRegistry[id] ?: ProviderMode.READ_WRITE_FILE

            return when (providerMode) {
                ProviderMode.READ_WRITE_FILE -> {
                    val pfdMode = if (mode.contains("w")) {
                        ParcelFileDescriptor.MODE_READ_WRITE
                    } else {
                        ParcelFileDescriptor.MODE_READ_ONLY
                    }
                    ParcelFileDescriptor.open(backingFile, pfdMode)
                }
                ProviderMode.READ_ONLY_FILE -> {
                    if (mode.contains("w")) {
                        throw SecurityException("Write access denied for read-only URI: $uri")
                    }
                    ParcelFileDescriptor.open(backingFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                ProviderMode.NON_TRUNCATABLE_PIPE -> {
                    // Simulates a provider that returns a non-null ParcelFileDescriptor for "rw",
                    // but whose underlying kernel fd is read-only / non-truncatable (ftruncate fails with EINVAL/EBADF)
                    ParcelFileDescriptor.open(backingFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment ?: return 0
            val f = fileRegistry[id] ?: return 0
            return if (f.exists() && f.delete()) 1 else 0
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun getType(uri: Uri): String = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private lateinit var context: Context
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val providerInfo = ProviderInfo().apply {
            authority = LocalTestSafProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
        }
        Robolectric.buildContentProvider(LocalTestSafProvider::class.java).create(providerInfo)

        tempRoot = File(context.cacheDir, "phase6_saf_tests_${System.nanoTime()}").apply { mkdirs() }
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        LocalTestSafProvider.clear()
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        LocalTestSafProvider.clear()
        tempRoot.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String = sha256Hex(file.readBytes())

    private fun createTestZip(
        zipFile: File,
        entries: List<Pair<String, ByteArray>>,
        stored: Boolean = false
    ): Map<String, String> {
        val expectedHashes = LinkedHashMap<String, String>()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((name, data) in entries) {
                expectedHashes[name] = sha256Hex(data)
                val ze = ZipEntry(name)
                if (stored) {
                    ze.method = ZipEntry.STORED
                    ze.size = data.size.toLong()
                    ze.compressedSize = data.size.toLong()
                    val crc = CRC32()
                    crc.update(data)
                    ze.crc = crc.value
                } else {
                    ze.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(ze)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return expectedHashes
    }

    /**
     * Verifies that existing local SAF progressive in-place extraction works end-to-end on a
     * writable SAF URI (like a file selected from Downloads), progressively shrinking the original
     * file on disk and producing exact SHA-256 output files.
     */
    @Test
    fun writableLocalSafUri_hatchMode_progressivelyShrinksArchiveAndMatchesSha256() = runBlocking {
        val rng = Random(6001L)
        val entriesData = (1..6).map { idx ->
            val payload = ByteArray(180 * 1024).also { rng.nextBytes(it) }
            "folder/item_$idx.bin" to payload
        }
        val archiveFile = File(tempRoot, "downloads_local.zip")
        val expectedHashes = createTestZip(archiveFile, entriesData, stored = false)
        val initialArchiveSize = archiveFile.length()
        assertTrue(initialArchiveSize > 500_000L)

        val uri = LocalTestSafProvider.registerFile("downloads_local", archiveFile, ProviderMode.READ_WRITE_FILE)
        val parsedEntries = ZipParser.parseSafUri(context, uri, archiveFile.name)
        assertEquals(6, parsedEntries.size)

        val observedSizes = ArrayList<Long>()
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPhysicalTruncate(point: DestructivePoint, previousSize: Long, newSize: Long) {
                observedSizes.add(newSize)
            }
        }

        val targetDir = File(tempRoot, "out_writable_saf").apply { mkdirs() }
        val emissions = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = uri,
            archiveName = archiveFile.name,
            entries = parsedEntries,
            targetDir = targetDir,
            isHatchMode = true
        ).toList()

        val terminal = emissions.last()
        assertEquals("Expected COMPLETED but got ${terminal.errorMessage}", ExtractionStatus.COMPLETED, terminal.status)
        assertTrue("Expected progressive physical truncations on SAF file", observedSizes.isNotEmpty())
        assertTrue("Expected SAF archive to shrink to 0 bytes", !archiveFile.exists() || archiveFile.length() == 0L)

        for ((relPath, expectedSha) in expectedHashes) {
            val outFile = File(targetDir, relPath)
            assertTrue("Missing extracted file $relPath", outFile.exists())
            assertEquals("SHA-256 mismatch for $relPath", expectedSha, sha256File(outFile))
        }
    }

    /**
     * Fix 2 Verification:
     * When Hatch Mode is requested on a read-only SAF URI, KaiHatchEngine must NOT silently
     * downgrade to Standard Mode. It must return ExtractionStatus.ERROR with a clear message and
     * leave the source archive untouched, while Standard Mode (`isHatchMode = false`) continues to work.
     */
    @Test
    fun readOnlySafUri_hatchModeReturnsClearErrorWithoutSilentFallback_standardModeSucceeds() = runBlocking {
        val rng = Random(6002L)
        val entriesData = listOf(
            "alpha.bin" to ByteArray(64 * 1024).also { rng.nextBytes(it) },
            "beta.bin" to ByteArray(64 * 1024).also { rng.nextBytes(it) }
        )
        val archiveFile = File(tempRoot, "readonly_source.zip")
        val expectedHashes = createTestZip(archiveFile, entriesData)
        val originalSize = archiveFile.length()
        val originalArchiveSha = sha256File(archiveFile)

        val uri = LocalTestSafProvider.registerFile("readonly_source", archiveFile, ProviderMode.READ_ONLY_FILE)
        val parsedEntries = ZipParser.parseSafUri(context, uri, archiveFile.name)
        assertEquals(2, parsedEntries.size)

        // 1. Hatch Mode on read-only URI must fail fast with ERROR (never silently switch to Standard Mode)
        val hatchTargetDir = File(tempRoot, "out_readonly_hatch").apply { mkdirs() }
        val hatchEmissions = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = uri,
            archiveName = archiveFile.name,
            entries = parsedEntries,
            targetDir = hatchTargetDir,
            isHatchMode = true
        ).toList()

        val hatchFinal = hatchEmissions.last()
        assertEquals(ExtractionStatus.ERROR, hatchFinal.status)
        assertNotNull(hatchFinal.errorMessage)
        assertTrue(
            "Error message should explain writable access requirement: ${hatchFinal.errorMessage}",
            hatchFinal.errorMessage!!.contains("writable access", ignoreCase = true) ||
                hatchFinal.errorMessage!!.contains("Standard Mode", ignoreCase = true)
        )
        assertEquals("Source archive size must remain untouched", originalSize, archiveFile.length())
        assertEquals("Source archive SHA-256 must remain untouched", originalArchiveSha, sha256File(archiveFile))
        assertFalse("No files should be extracted on silent fallback", File(hatchTargetDir, "alpha.bin").exists())

        // 2. Standard Mode on the same read-only URI must succeed without modifying the source archive
        val stdTargetDir = File(tempRoot, "out_readonly_std").apply { mkdirs() }
        val stdEmissions = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = uri,
            archiveName = archiveFile.name,
            entries = parsedEntries,
            targetDir = stdTargetDir,
            isHatchMode = false
        ).toList()

        val stdFinal = stdEmissions.last()
        assertEquals(ExtractionStatus.COMPLETED, stdFinal.status)
        assertFalse(stdFinal.isHatchMode)
        assertEquals(originalSize, archiveFile.length())
        for ((relPath, expectedSha) in expectedHashes) {
            val outFile = File(stdTargetDir, relPath)
            assertTrue(outFile.exists())
            assertEquals(expectedSha, sha256File(outFile))
        }
    }

    /**
     * Fix 2 Verification (Non-truncatable descriptor):
     * When a SAF provider returns a descriptor that opens for "rw" but does not support ftruncate
     * (such as a pipe/stream descriptor), Hatch Mode must fail with a clear error rather than
     * silently downgrading to Standard Mode.
     */
    @Test
    fun nonTruncatableSafDescriptor_hatchModeReturnsClearErrorWithoutSilentFallback() = runBlocking {
        val entriesData = listOf("doc.txt" to "Non-truncatable SAF pipe test payload".toByteArray())
        val archiveFile = File(tempRoot, "pipe_backed.zip")
        createTestZip(archiveFile, entriesData)
        val parsedEntries = ZipParser.parseArchive(archiveFile)

        val uri = LocalTestSafProvider.registerFile("pipe_source", archiveFile, ProviderMode.NON_TRUNCATABLE_PIPE)
        val outDir = File(tempRoot, "out_pipe_hatch").apply { mkdirs() }

        val emissions = KaiHatchEngine.extractSafUriZeroCopy(
            context = context,
            uri = uri,
            archiveName = archiveFile.name,
            entries = parsedEntries,
            targetDir = outDir,
            isHatchMode = true
        ).toList()

        val terminal = emissions.last()
        assertEquals(ExtractionStatus.ERROR, terminal.status)
        assertNotNull(terminal.errorMessage)
        assertTrue(
            "Error message must report truncation/Hatch failure: ${terminal.errorMessage}",
            terminal.errorMessage!!.contains("truncat", ignoreCase = true) ||
                terminal.errorMessage!!.contains("Hatch Mode", ignoreCase = true)
        )
        assertFalse(File(outDir, "doc.txt").exists())
    }

    /**
     * Fix 3 Verification:
     * ResumeJournalManager.assessResumeEligibility(state, context) and KaiHatchEngine.resumeInterruptedExtraction
     * must validate the actual SAF source descriptor size and journal `slot0Crc` before permitting recovery.
     */
    @Test
    fun safResumeEligibility_validatesActualDescriptorSizeAndSlot0Crc_andRecoversValidSafState() = runBlocking {
        val rng = Random(6003L)
        // Create a large DEFLATE entry (~4.5 MB uncompressed) so tail-swap guard is exercised
        val largePayload = ByteArray(4_600_000)
        for (i in largePayload.indices step 4096) {
            val block = ByteArray(256).also { rng.nextBytes(it) }
            System.arraycopy(block, 0, largePayload, i, minOf(256, largePayload.size - i))
        }
        val archiveFile = File(tempRoot, "saf_resume_guard.zip")
        val expectedHashes = createTestZip(archiveFile, listOf("large_entry.bin" to largePayload), stored = false)
        val originalSize = archiveFile.length()

        val uri = LocalTestSafProvider.registerFile("saf_resume_guard", archiveFile, ProviderMode.READ_WRITE_FILE)
        val entries = ZipParser.parseSafUri(context, uri, archiveFile.name)
        assertEquals(1, entries.size)
        val entry = entries.first()

        val targetDir = File(tempRoot, "out_saf_resume").apply { mkdirs() }
        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.name)

        // Resolve payload offset & original slot 0 CRC32 from the archive
        val localHdr = ByteArray(30)
        RandomAccessFile(archiveFile, "r").use { raf ->
            raf.seek(entry.localHeaderOffset)
            raf.readFully(localHdr)
        }
        val fnLen = ((localHdr[27].toInt() and 0xFF) shl 8) or (localHdr[26].toInt() and 0xFF)
        val efLen = ((localHdr[29].toInt() and 0xFF) shl 8) or (localHdr[28].toInt() and 0xFF)
        val payloadOffset = entry.localHeaderOffset + 30L + fnLen + efLen
        val chunk0Len = (entry.compressedSize / 8L).coerceIn(1024L, 1024L * 1024L).toInt().coerceAtMost(entry.compressedSize.toInt())

        val slot0OrigBytes = ByteArray(chunk0Len)
        RandomAccessFile(archiveFile, "r").use { raf ->
            raf.seek(payloadOffset)
            raf.readFully(slot0OrigBytes)
        }
        val origSlot0Crc = CRC32().apply { update(slot0OrigBytes) }.value

        // Save initial index + SAF journal state (archivePath = "", sourceUriString = uri.toString())
        ResumeJournalManager.saveInitialIndex(stateFile.absolutePath, entries)
        val safState = ResumeJournalState(
            stateFilePath = stateFile.absolutePath,
            archivePath = "",
            sourceUriString = uri.toString(),
            archiveName = archiveFile.name,
            targetDirPath = targetDir.absolutePath,
            originalArchiveBytes = originalSize,
            currentArchiveBytes = originalSize,
            bytesReclaimedSoFar = 0L,
            totalEntries = 1,
            completedPaths = emptySet(),
            remainingEntries = entries
        )
        ResumeJournalManager.saveState(safState)
        ResumeJournalManager.saveTailSwapGuard(
            stateFile.absolutePath,
            ResumeJournalManager.TailSwapGuardState(
                entryPath = entry.path,
                entryName = entry.name,
                payloadOffset = payloadOffset,
                compressedSize = entry.compressedSize,
                uncompressedSize = entry.uncompressedSize,
                expectedEntryCrc32 = entry.crc32,
                chunk0Len = chunk0Len,
                chunk0OriginalCrc32 = origSlot0Crc,
                overwriteStarted = false,
                decompressedChunk0Bytes = 0L
            )
        )

        // Subcase 1: Intact slot0Crc and intact physical descriptor size -> Resumable!
        val eligIntact = ResumeJournalManager.assessResumeEligibility(safState, context)
        assertTrue(
            "Expected Resumable when SAF descriptor size and slot0Crc are intact, got $eligIntact",
            eligIntact is ResumeJournalManager.ResumeEligibility.Resumable
        )

        // Subcase 2: Mutate 4 bytes in slot 0 of the backing SAF file (simulating pwrite overwrite before crash)
        RandomAccessFile(archiveFile, "rw").use { raf ->
            raf.seek(payloadOffset)
            raf.write(byteArrayOf((slot0OrigBytes[0].toInt() xor 0xFF).toByte(), 0x55, 0xAA.toByte(), 0x33))
        }
        val eligCorruptedSlot0 = ResumeJournalManager.assessResumeEligibility(safState, context)
        assertTrue(
            "Expected Unrecoverable when SAF descriptor slot0Crc is modified",
            eligCorruptedSlot0 is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        val unrecReason = (eligCorruptedSlot0 as ResumeJournalManager.ResumeEligibility.Unrecoverable).reason
        assertTrue(
            "Reason should mention slot 0 overwrite: $unrecReason",
            unrecReason.contains("slot 0", ignoreCase = true)
        )

        // Verify resumeInterruptedExtraction rejects the corrupted SAF state with ERROR
        val failEmissions = KaiHatchEngine.resumeInterruptedExtraction(context, safState).toList()
        assertEquals(ExtractionStatus.ERROR, failEmissions.last().status)

        // Restore original slot 0 bytes, then test Subcase 3: physical SAF file truncated smaller than journal's currentArchiveBytes
        RandomAccessFile(archiveFile, "rw").use { raf ->
            raf.seek(payloadOffset)
            raf.write(slot0OrigBytes, 0, 4)
        }
        val backupBytes = archiveFile.readBytes()
        RandomAccessFile(archiveFile, "rw").use { raf ->
            raf.setLength(payloadOffset + entry.compressedSize / 2L)
        }
        val eligTruncated = ResumeJournalManager.assessResumeEligibility(safState, context)
        assertTrue(
            "Expected Unrecoverable when actual SAF descriptor size is smaller than required payload end",
            eligTruncated is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )

        // Restore full SAF archive bytes and verify that resumeInterruptedExtraction succeeds and shrinks SAF archive to 0
        archiveFile.writeBytes(backupBytes)
        val okEmissions = KaiHatchEngine.resumeInterruptedExtraction(context, safState).toList()
        val okTerminal = okEmissions.last()
        assertEquals("Expected COMPLETED on valid SAF resume: ${okTerminal.errorMessage}", ExtractionStatus.COMPLETED, okTerminal.status)
        assertTrue(!archiveFile.exists() || archiveFile.length() == 0L)
        val extractedFile = File(targetDir, "large_entry.bin")
        assertTrue(extractedFile.exists())
        assertEquals(expectedHashes["large_entry.bin"], sha256File(extractedFile))
    }

    /**
     * Fix 1 Verification:
     * StorageHelper.takePersistablePermission requests read+write persistable URI permissions
     * without throwing when invoked on a picker URI.
     */
    @Test
    fun takePersistablePermission_invokesContentResolverWithoutCrashing() {
        val dummyFile = File(tempRoot, "perm_test.zip").apply { writeBytes(ByteArray(64)) }
        val uri = LocalTestSafProvider.registerFile("perm_test", dummyFile, ProviderMode.READ_WRITE_FILE)
        StorageHelper.takePersistablePermission(context, uri)
        // Also verify fallback on a URI that only supports read permission or throws SecurityException
        val roUri = LocalTestSafProvider.registerFile("perm_ro", dummyFile, ProviderMode.READ_ONLY_FILE)
        StorageHelper.takePersistablePermission(context, roUri)
        assertTrue(dummyFile.exists())
    }
}
