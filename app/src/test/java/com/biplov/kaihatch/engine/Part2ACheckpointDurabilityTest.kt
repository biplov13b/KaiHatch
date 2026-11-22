package com.biplov.kaihatch.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.RestorationCheckpointState
import com.biplov.kaihatch.model.RestorationPhase
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Part 2A Fault-Injection Suite: Recovery Checkpoint Durability & Partial DEFLATE Preservation.
 *
 * Verifies:
 * 1. Interrupted checkpoint saves across every persistence stage never destroy the prior valid
 *    checkpoint and recover cleanly from atomic primary / `.bak` / `.tmp` artifacts.
 * 2. Corrupted checkpoints (bitflips, truncation, missing primary) recover from CRC32-validated
 *    backup copies, and fail closed when all copies are corrupted (never fail-open on corrupted guards).
 * 3. Full backward compatibility with existing legacy recovery journals (JSON without `payloadCrc32`,
 *    v1 TailSwapGuard, single-file inflate checkpoints without `.bak`).
 * 4. Partial DEFLATE output and recovery metadata are preserved after unexpected interruption once
 *    tail-swap mutation has started, and the state is NEVER incorrectly marked recoverable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Part2ACheckpointDurabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
        CheckpointableInflateEngine.persistenceFaultHook = null
        File(context.filesDir, "hatch_journals").deleteRecursively()
    }

    @After
    fun tearDown() {
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        ResumeJournalManager.persistenceFaultHook = null
        CheckpointableInflateEngine.persistenceFaultHook = null
        File(context.filesDir, "hatch_journals").deleteRecursively()
    }

    private fun sampleEntries(): List<ZipEntryInfo> = listOf(
        ZipEntryInfo(
            path = "dir/entry_b.bin",
            name = "entry_b.bin",
            isDirectory = false,
            compressedSize = 512_000L,
            uncompressedSize = 1_024_000L,
            crc32 = 0x11223344L,
            compressionMethod = ZipEntry.DEFLATED,
            localHeaderOffset = 1_048_576L,
            lastModified = 1_700_000_000_000L
        ),
        ZipEntryInfo(
            path = "dir/entry_a.bin",
            name = "entry_a.bin",
            isDirectory = false,
            compressedSize = 1_000_000L,
            uncompressedSize = 1_000_000L,
            crc32 = 0x55667788L,
            compressionMethod = ZipEntry.STORED,
            localHeaderOffset = 0L,
            lastModified = 1_700_000_000_000L
        )
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(65536)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun generateIncompressiblePayload(size: Int, seed: Long): ByteArray {
        val out = ByteArray(size)
        Random(seed).nextBytes(out)
        return out
    }

    private fun deflateRaw(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size / 2 + 1024)
        val buf = ByteArray(32768)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun createLargeDeflateZip(zipFile: File, entryName: String, payload: ByteArray) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(payload)
            zos.closeEntry()
        }
    }

    // =========================================================================
    // 1. Interrupted ResumeJournalManager Saves (Atomic Replace + A/B Durability)
    // =========================================================================

    @Test
    fun interruptedJournalStateAndIndexSaves_preservePriorCommittedStateAndRecoverCleanly() {
        val archiveFile = tempFolder.newFile("journal_durability.zip").apply {
            writeBytes(ByteArray(2 * 1024 * 1024) { (it % 251).toByte() })
        }
        val outDir = tempFolder.newFolder("out_journal_durability")
        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.absolutePath)
        val entries = sampleEntries()

        val stateV1 = ResumeJournalState(
            stateFilePath = stateFile.absolutePath,
            archivePath = archiveFile.absolutePath,
            archiveName = archiveFile.name,
            targetDirPath = outDir.absolutePath,
            originalArchiveBytes = archiveFile.length(),
            currentArchiveBytes = archiveFile.length(),
            bytesReclaimedSoFar = 0L,
            totalEntries = entries.size,
            completedPaths = emptySet(),
            remainingEntries = entries,
            verifiedEntryCrcs = emptyMap(),
            timestamp = 1000L
        )
        assertTrue(ResumeJournalManager.saveInitialIndex(stateFile.absolutePath, entries))
        assertTrue(ResumeJournalManager.saveState(stateV1))

        val stateV2 = stateV1.copy(
            currentArchiveBytes = 1_048_576L,
            bytesReclaimedSoFar = archiveFile.length() - 1_048_576L,
            completedPaths = setOf("dir/entry_b.bin"),
            remainingEntries = listOf(entries[1]),
            verifiedEntryCrcs = mapOf("dir/entry_b.bin" to 0x11223344L),
            timestamp = 2000L
        )

        // Stage A: Crash mid-tmp write of `.ckpt.bin` -> prior committed stateV1 remains 100% intact!
        ResumeJournalManager.persistenceFaultHook = { stage, target, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE &&
                target.name.endsWith(".ckpt.bin")
            ) {
                throw IOException("Simulated crash during .ckpt.bin partial tmp write")
            }
        }
        assertFalse(ResumeJournalManager.saveState(stateV2))
        ResumeJournalManager.persistenceFaultHook = null

        val afterPartialCkpt = ResumeJournalManager.parseStateFile(stateFile)
        assertNotNull("Prior committed journal state must survive interrupted .ckpt.bin write", afterPartialCkpt)
        assertEquals(1000L, afterPartialCkpt!!.timestamp)
        assertEquals(0, afterPartialCkpt.completedPaths.size)
        assertEquals(2, afterPartialCkpt.remainingEntries.size)

        // Stage B: Crash mid-tmp write of `.hatchstate.json` after `.ckpt.bin` replaced -> loads valid stateV2!
        ResumeJournalManager.persistenceFaultHook = { stage, target, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE &&
                target.name.endsWith(".hatchstate.json")
            ) {
                throw IOException("Simulated crash during JSON partial tmp write")
            }
        }
        assertFalse(ResumeJournalManager.saveState(stateV2))
        ResumeJournalManager.persistenceFaultHook = null

        val afterPartialJson = ResumeJournalManager.parseStateFile(stateFile)
        assertNotNull(afterPartialJson)
        assertEquals(2000L, afterPartialJson!!.timestamp)
        assertEquals(setOf("dir/entry_b.bin"), afterPartialJson.completedPaths)

        // Stage C: Crash after primary replace, before `.bak` mirror -> primary stateV2 still loads cleanly!
        val stateV3 = stateV2.copy(bytesReclaimedSoFar = stateV2.bytesReclaimedSoFar + 4096L, timestamp = 3000L)
        ResumeJournalManager.persistenceFaultHook = { stage, target, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_PRIMARY_REPLACE_BEFORE_BACKUP &&
                target.name.endsWith(".ckpt.bin")
            ) {
                throw IOException("Simulated crash before .ckpt.bin backup mirror")
            }
        }
        assertFalse(ResumeJournalManager.saveState(stateV3))
        ResumeJournalManager.persistenceFaultHook = null

        val afterPrimaryOnly = ResumeJournalManager.parseStateFile(stateFile)
        assertNotNull(afterPrimaryOnly)
        assertEquals(3000L, afterPrimaryOnly!!.timestamp)

        // Stage D: Interrupted restoration checkpoint save preserves previous valid restoration checkpoint
        val restoreV1 = RestorationCheckpointState(
            stateFilePath = stateFile.absolutePath,
            archivePath = archiveFile.absolutePath,
            sourceUriString = null,
            archiveName = archiveFile.name,
            newCentralDirOffset = 1_048_576L,
            expectedFinalArchiveSize = 1_049_000L,
            remainingEntryCount = 1,
            phase = RestorationPhase.WRITING_CENTRAL_DIRECTORY,
            timestamp = 100L
        )
        assertTrue(ResumeJournalManager.saveRestorationCheckpoint(restoreV1))

        val restoreV2 = restoreV1.copy(phase = RestorationPhase.VERIFYING_RESTORED_ARCHIVE, timestamp = 200L)
        ResumeJournalManager.persistenceFaultHook = { stage, target, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE &&
                target.name.endsWith(".restore.ckpt")
            ) {
                throw IOException("Simulated crash mid restore ckpt write")
            }
        }
        assertFalse(ResumeJournalManager.saveRestorationCheckpoint(restoreV2))
        ResumeJournalManager.persistenceFaultHook = null

        val loadedRestore = ResumeJournalManager.loadRestorationCheckpoint(stateFile.absolutePath)
        assertNotNull("Prior restoration checkpoint must survive interrupted write", loadedRestore)
        assertEquals(RestorationPhase.WRITING_CENTRAL_DIRECTORY, loadedRestore!!.phase)
    }

    // =========================================================================
    // 2. TailSwapGuard Interrupted Transition & Fail-Closed Corruption Handling
    // =========================================================================

    @Test
    fun tailSwapGuard_interruptedTransitionAndCorruption_enforcesFailClosedSafety() {
        val archiveFile = tempFolder.newFile("guard_durability.zip").apply {
            writeBytes(ByteArray(6 * 1024 * 1024) { (it % 239).toByte() })
        }
        val outDir = tempFolder.newFolder("out_guard_durability")
        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.absolutePath)
        val slot0Crc = CRC32().apply { update(archiveFile.readBytes(), 0, 1024 * 1024) }.value

        val entry = ZipEntryInfo(
            path = "big_deflate.bin",
            name = "big_deflate.bin",
            isDirectory = false,
            compressedSize = 5 * 1024 * 1024L,
            uncompressedSize = 5 * 1024 * 1024L,
            crc32 = 0xCAFEBABEL,
            compressionMethod = ZipEntry.DEFLATED,
            localHeaderOffset = 0L
        )
        val state = ResumeJournalState(
            stateFilePath = stateFile.absolutePath,
            archivePath = archiveFile.absolutePath,
            archiveName = archiveFile.name,
            targetDirPath = outDir.absolutePath,
            originalArchiveBytes = archiveFile.length(),
            currentArchiveBytes = archiveFile.length(),
            bytesReclaimedSoFar = 0L,
            totalEntries = 1,
            completedPaths = emptySet(),
            remainingEntries = listOf(entry),
            verifiedEntryCrcs = emptyMap()
        )
        assertTrue(ResumeJournalManager.saveState(state))

        val preGuard = ResumeJournalManager.TailSwapGuardState(
            entryPath = entry.path,
            entryName = entry.name,
            payloadOffset = 0L,
            compressedSize = entry.compressedSize,
            uncompressedSize = entry.uncompressedSize,
            expectedEntryCrc32 = entry.crc32,
            chunk0Len = 1024 * 1024,
            chunk0OriginalCrc32 = slot0Crc,
            decompressedChunk0Bytes = 1024 * 1024L,
            overwriteStarted = false
        )
        assertTrue(ResumeJournalManager.saveTailSwapGuard(stateFile.absolutePath, preGuard))

        // 1. Crash during partial tmp write of overwriteStarted=true -> preGuard remains intact (never deleted!)
        val mutGuard = preGuard.copy(overwriteStarted = true)
        ResumeJournalManager.persistenceFaultHook = { stage, _, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE) {
                throw IOException("Crash mid guard tmp write")
            }
        }
        assertFalse(ResumeJournalManager.saveTailSwapGuard(stateFile.absolutePath, mutGuard))
        ResumeJournalManager.persistenceFaultHook = null

        val afterPartialGuard = ResumeJournalManager.loadTailSwapGuard(stateFile.absolutePath)
        assertNotNull("Existing guard must survive interrupted tmp write", afterPartialGuard)
        assertFalse(afterPartialGuard!!.overwriteStarted)

        // 2. Crash AFTER tmp synced with overwriteStarted=true, BEFORE atomic replace -> fail-closed prefers overwriteStarted=true!
        ResumeJournalManager.persistenceFaultHook = { stage, _, _ ->
            if (stage == ResumeJournalManager.PersistenceFaultStage.AFTER_TMP_SYNC_BEFORE_REPLACE) {
                throw IOException("Crash before guard atomic replace")
            }
        }
        assertFalse(ResumeJournalManager.saveTailSwapGuard(stateFile.absolutePath, mutGuard))
        ResumeJournalManager.persistenceFaultHook = null

        val afterSyncedTmpGuard = ResumeJournalManager.loadTailSwapGuard(stateFile.absolutePath)
        assertNotNull(afterSyncedTmpGuard)
        assertTrue(
            "Fail-closed guard loader must prefer synced overwriteStarted=true over older overwriteStarted=false",
            afterSyncedTmpGuard!!.overwriteStarted
        )
        assertTrue(
            ResumeJournalManager.assessResumeEligibility(state, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )

        // 3. Complete save of overwriteStarted=true, then corrupt primary `.tailswap.guard` -> recovers from `.bak`!
        assertTrue(ResumeJournalManager.saveTailSwapGuard(stateFile.absolutePath, mutGuard))
        val guardFile = File("${stateFile.absolutePath}.tailswap.guard")
        val guardBak = File("${guardFile.absolutePath}.bak")
        assertTrue(guardFile.exists() && guardBak.exists())

        val corruptedPrimary = guardFile.readBytes().apply { this[10] = (this[10].toInt() xor 0xFF).toByte() }
        guardFile.writeBytes(corruptedPrimary)

        val recoveredGuard = ResumeJournalManager.loadTailSwapGuard(stateFile.absolutePath)
        assertNotNull("Corrupted primary guard must recover from validated .bak", recoveredGuard)
        assertTrue(recoveredGuard!!.overwriteStarted)
        assertTrue(
            ResumeJournalManager.assessResumeEligibility(state, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertTrue(
            ResumeJournalManager.assessRestoreEligibility(state, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )

        // 4. Corrupt BOTH primary AND `.bak` guard files -> must FAIL CLOSED (Unrecoverable), NEVER fail-open!
        guardBak.writeBytes(ByteArray(64) { 0x5A.toByte() })
        assertNull(ResumeJournalManager.loadTailSwapGuard(stateFile.absolutePath))
        assertTrue(ResumeJournalManager.hasCorruptedTailSwapGuard(stateFile.absolutePath))
        assertTrue(
            "Corrupted tail-swap guard with no valid backup MUST be Unrecoverable for resume",
            ResumeJournalManager.assessResumeEligibility(state, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertTrue(
            "Corrupted tail-swap guard with no valid backup MUST be Unrecoverable for restore",
            ResumeJournalManager.assessRestoreEligibility(state, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )
    }

    // =========================================================================
    // 3. Corrupted Checkpoints Fallback & Fail-Closed Rejection + Legacy Compat
    // =========================================================================

    @Test
    fun corruptedJournalFiles_recoverFromValidatedBackupOrChecksummedJson_andRejectAllCorrupt() {
        val archiveFile = tempFolder.newFile("corrupt_fallback.zip").apply {
            writeBytes(ByteArray(2 * 1024 * 1024) { (it % 199).toByte() })
        }
        val outDir = tempFolder.newFolder("out_corrupt_fallback")
        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.absolutePath)
        val entries = sampleEntries()
        val state = ResumeJournalState(
            stateFilePath = stateFile.absolutePath,
            archivePath = archiveFile.absolutePath,
            archiveName = archiveFile.name,
            targetDirPath = outDir.absolutePath,
            originalArchiveBytes = archiveFile.length(),
            currentArchiveBytes = 1_048_576L,
            bytesReclaimedSoFar = archiveFile.length() - 1_048_576L,
            totalEntries = entries.size,
            completedPaths = setOf("dir/entry_b.bin"),
            remainingEntries = listOf(entries[1]),
            verifiedEntryCrcs = mapOf("dir/entry_b.bin" to 0x11223344L),
            timestamp = 9999L
        )
        assertTrue(ResumeJournalManager.saveInitialIndex(stateFile.absolutePath, entries))
        assertTrue(ResumeJournalManager.saveState(state))

        val ckptFile = File("${stateFile.absolutePath}.ckpt.bin")
        val ckptBak = File("${ckptFile.absolutePath}.bak")
        val indexFile = File("${stateFile.absolutePath}.index.bin")
        val jsonBak = File("${stateFile.absolutePath}.bak")

        // A. Corrupt primary `.index.bin` and primary `.ckpt.bin` -> recovers via `.bak` files!
        indexFile.writeBytes(indexFile.readBytes().apply { this[16] = (this[16].toInt() xor 0xFF).toByte() })
        ckptFile.writeBytes(ckptFile.readBytes().apply { this[20] = (this[20].toInt() xor 0xFF).toByte() })

        val recoveredFromBak = ResumeJournalManager.parseStateFile(stateFile)
        assertNotNull("Must recover from .ckpt.bin.bak and .index.bin.bak", recoveredFromBak)
        assertEquals(9999L, recoveredFromBak!!.timestamp)
        assertEquals(1, recoveredFromBak.remainingEntries.size)

        // B. Delete primary `.hatchstate.json` -> `loadPendingStates` still discovers journal via `.ckpt.bin.bak` / `.json.bak`!
        assertTrue(stateFile.delete())
        val pendingAfterJsonMissing = ResumeJournalManager.loadPendingStates(context)
        assertEquals(1, pendingAfterJsonMissing.size)
        assertEquals(stateFile.absolutePath, pendingAfterJsonMissing.single().stateFilePath)

        // C. Corrupt BOTH `.ckpt.bin` and `.ckpt.bin.bak`, restore `.hatchstate.json` with a syntactically valid
        //    but tampered field -> rejected by `payloadCrc32`, recovers intact copy from `.hatchstate.json.bak`!
        ckptBak.writeBytes(ByteArray(32) { 0xAA.toByte() })
        val validJsonText = jsonBak.readText(Charsets.UTF_8)
        assertTrue("New JSON state must embed payloadCrc32", validJsonText.contains("\"payloadCrc32\":"))
        val tamperedJsonText = validJsonText.replace("\"timestamp\":9999", "\"timestamp\":8888")
        stateFile.writeText(tamperedJsonText, Charsets.UTF_8)

        val recoveredFromJsonBak = ResumeJournalManager.parseStateFile(stateFile)
        assertNotNull("Tampered JSON must be rejected by payloadCrc32 and fall back to .hatchstate.json.bak", recoveredFromJsonBak)
        assertEquals(9999L, recoveredFromJsonBak!!.timestamp)

        // D. Corrupt `.hatchstate.json.bak` too -> ALL copies corrupted -> must fail closed (null)!
        jsonBak.writeText(tamperedJsonText, Charsets.UTF_8)
        assertNull("When all checkpoint copies fail validation, parseStateFile must return null", ResumeJournalManager.parseStateFile(stateFile))
    }

    @Test
    fun legacyJournalsWithoutChecksumOrBackups_remainFullyCompatible() {
        val archiveFile = tempFolder.newFile("legacy_compat.zip").apply {
            writeBytes(ByteArray(2 * 1024 * 1024) { (it % 127).toByte() })
        }
        val outDir = tempFolder.newFolder("out_legacy_compat")
        val stateFile = ResumeJournalManager.getStateFileForArchive(context, archiveFile.absolutePath)

        // 1. Legacy `.hatchstate.json` without `payloadCrc32`, without `.ckpt.bin`, without `.bak`
        val legacyJson = """
            {
              "stateFilePath": "${stateFile.absolutePath.replace("\\", "\\\\")}",
              "archivePath": "${archiveFile.absolutePath.replace("\\", "\\\\")}",
              "sourceUriString": "",
              "archiveName": "legacy_compat.zip",
              "targetDirPath": "${outDir.absolutePath.replace("\\", "\\\\")}",
              "originalArchiveBytes": ${archiveFile.length()},
              "currentArchiveBytes": ${archiveFile.length()},
              "bytesReclaimedSoFar": 0,
              "totalEntries": 1,
              "timestamp": 123456,
              "completedPaths": [],
              "verifiedEntryCrcs": {},
              "remainingEntries": [
                {
                  "path": "legacy.bin",
                  "name": "legacy.bin",
                  "isDirectory": false,
                  "compressedSize": 1000,
                  "uncompressedSize": 2000,
                  "crc32": 12345,
                  "compressionMethod": 8,
                  "localHeaderOffset": 0,
                  "lastModified": 1000,
                  "isEncrypted": false,
                  "isZip64": false
                }
              ]
            }
        """.trimIndent()
        stateFile.writeText(legacyJson, Charsets.UTF_8)

        val loadedLegacy = ResumeJournalManager.parseStateFile(stateFile)
        assertNotNull("Legacy JSON journal without payloadCrc32 must still parse cleanly", loadedLegacy)
        assertEquals(123456L, loadedLegacy!!.timestamp)
        assertEquals(1, loadedLegacy.remainingEntries.size)
        assertEquals("legacy.bin", loadedLegacy.remainingEntries.single().path)

        // 2. Legacy v1 TailSwapGuardState (version=1, no overwriteStarted boolean, no `.bak`)
        val guardFile = File("${stateFile.absolutePath}.tailswap.guard")
        val crc = CRC32()
        FileOutputStream(guardFile).use { fos ->
            val cos = CheckedOutputStream(BufferedOutputStream(fos), crc)
            val dos = DataOutputStream(cos)
            dos.writeInt(0x4B485447) // "KHTG"
            dos.writeInt(1)          // legacy version 1
            dos.writeUTF("legacy.bin")
            dos.writeUTF("legacy.bin")
            dos.writeLong(0L)
            dos.writeLong(1000L)
            dos.writeLong(2000L)
            dos.writeLong(12345L)
            dos.writeInt(512)
            dos.writeLong(98765L)
            dos.writeLong(1024L)
            dos.flush()
            val footer = DataOutputStream(fos)
            footer.writeLong(crc.value)
            footer.flush()
        }
        val loadedV1Guard = ResumeJournalManager.loadTailSwapGuard(stateFile.absolutePath)
        assertNotNull("Legacy v1 TailSwapGuardState must still load cleanly", loadedV1Guard)
        assertEquals("legacy.bin", loadedV1Guard!!.entryPath)
        assertFalse(loadedV1Guard.overwriteStarted)
    }

    // =========================================================================
    // 4. CheckpointableInflateEngine Atomic Replace, Interrupted Saves & Backup
    // =========================================================================

    @Test
    fun checkpointableInflateEngine_interruptedSavesAndCorruptedPrimary_recoverCleanlyAndFailClosedWhenAllCorrupt() {
        val payload = generateIncompressiblePayload(300 * 1024, 9101L)
        val expectedSha = sha256Hex(payload)
        val compressed = deflateRaw(payload)
        val ckptFile = tempFolder.newFile("inflate_durable.ckpt")
        val outFile = tempFolder.newFile("inflate_durable.out")

        val split1 = compressed.size / 3
        val split2 = (compressed.size * 2) / 3

        val engine = CheckpointableInflateEngine()
        FileOutputStream(outFile).use { fos ->
            engine.processChunk(compressed, 0, split1, isLastChunk = false, output = fos)
            fos.fd.sync()
        }
        val outBytesAtSplit1 = engine.outputBytesWritten
        engine.saveCheckpointToFile(ckptFile)
        assertTrue(ckptFile.exists())
        assertTrue(CheckpointableInflateEngine.getBackupCheckpointFile(ckptFile).exists())

        // Advance to split2, then interrupt saveCheckpointToFile mid-tmp write -> split1 checkpoint MUST survive!
        FileOutputStream(outFile, true).use { fos ->
            engine.processChunk(compressed, split1, split2 - split1, isLastChunk = false, output = fos)
            fos.fd.sync()
        }
        val outBytesAtSplit2 = engine.outputBytesWritten
        CheckpointableInflateEngine.persistenceFaultHook = { stage, _, _ ->
            if (stage == CheckpointableInflateEngine.PersistenceFaultStage.AFTER_TMP_PARTIAL_WRITE) {
                throw IOException("Simulated crash mid inflate checkpoint tmp write")
            }
        }
        try {
            engine.saveCheckpointToFile(ckptFile)
            fail("Expected simulated IOException during partial tmp write")
        } catch (_: IOException) {
            // expected
        } finally {
            CheckpointableInflateEngine.persistenceFaultHook = null
        }

        val recoveredAtSplit1 = CheckpointableInflateEngine()
        recoveredAtSplit1.restoreFromCheckpointFile(ckptFile, outFile)
        assertEquals(
            "Interrupted partial tmp write must leave prior split1 checkpoint intact",
            outBytesAtSplit1,
            recoveredAtSplit1.outputBytesWritten
        )

        // Interrupt AFTER primary replace, BEFORE backup mirror -> split2 primary must load cleanly!
        CheckpointableInflateEngine.persistenceFaultHook = { stage, _, _ ->
            if (stage == CheckpointableInflateEngine.PersistenceFaultStage.AFTER_PRIMARY_REPLACE_BEFORE_BACKUP) {
                throw IOException("Simulated crash before inflate checkpoint backup mirror")
            }
        }
        try {
            engine.saveCheckpointToFile(ckptFile)
            fail("Expected simulated IOException before backup mirror")
        } catch (_: IOException) {
            // expected
        } finally {
            CheckpointableInflateEngine.persistenceFaultHook = null
        }

        val recoveredAtSplit2 = CheckpointableInflateEngine()
        recoveredAtSplit2.restoreFromCheckpointFile(ckptFile, outFile)
        assertEquals(outBytesAtSplit2, recoveredAtSplit2.outputBytesWritten)

        // Complete clean save at split2 so `.bak` also holds split2, then corrupt primary `.ckpt`!
        engine.saveCheckpointToFile(ckptFile)
        val corrupted = ckptFile.readBytes().apply { this[24] = (this[24].toInt() xor 0xFF).toByte() }
        ckptFile.writeBytes(corrupted)

        // Restore must automatically recover from `.bak`, truncate output to split2, finish inflating -> exact SHA-256!
        val resumedFromBak = CheckpointableInflateEngine()
        resumedFromBak.restoreFromCheckpointFile(ckptFile, outFile)
        assertEquals(outBytesAtSplit2, resumedFromBak.outputBytesWritten)

        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(resumedFromBak.outputBytesWritten)
            raf.seek(resumedFromBak.outputBytesWritten)
            FileOutputStream(raf.fd).use { fos ->
                resumedFromBak.processChunk(compressed, split2, compressed.size - split2, isLastChunk = true, output = fos)
                fos.flush()
                raf.fd.sync()
            }
        }
        assertTrue(resumedFromBak.isFinished)
        assertEquals(expectedSha, sha256Hex(outFile))

        // Corrupt BOTH primary AND `.bak` -> must fail closed with InvalidCheckpointException!
        val bakFile = CheckpointableInflateEngine.getBackupCheckpointFile(ckptFile)
        bakFile.writeBytes(ByteArray(128) { 0xCC.toByte() })
        try {
            CheckpointableInflateEngine().restoreFromCheckpointFile(ckptFile, outFile)
            fail("Expected InvalidCheckpointException when both primary and .bak checkpoints are corrupted")
        } catch (_: CheckpointableInflateEngine.InvalidCheckpointException) {
            // expected
        }
    }

    // =========================================================================
    // 5. Preserve Partial DEFLATE Output & Recovery Metadata After Post-Mutation Interruption
    // =========================================================================

    @Test
    fun postMutationDeflateInterruption_preservesPartialOutputAndMetadata_andNeverMarksRecoverable() = runBlocking {
        val payload = generateIncompressiblePayload(5_200_000, 9505L)
        val entryName = "mutated_deflate.bin"

        // Case A: Simulated crash after slot 0 pwrite + truncate (AFTER_TAILSWAP_TRUNCATE #1)
        val zipA = tempFolder.newFile("preserve_fault_a.zip")
        val outDirA = tempFolder.newFolder("out_preserve_fault_a")
        createLargeDeflateZip(zipA, entryName, payload)
        val partialOutA = File(outDirA, entryName)

        KaiHatchEngine.faultInjector = RecordingFaultInjector(
            triggerPoint = DestructivePoint.AFTER_TAILSWAP_TRUNCATE,
            triggerOnOccurrence = 1
        )
        val runA = KaiHatchEngine.extractArchive(
            archiveFile = zipA,
            targetDir = outDirA,
            isHatchMode = true,
            context = context
        ).toList()
        assertEquals(ExtractionStatus.ERROR, runA.last().status)
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        assertTrue("Partial DEFLATE output must be preserved after mid-tail-swap crash", partialOutA.exists())
        val partialLenA = partialOutA.length()
        assertTrue("Partial DEFLATE output must retain flushed prefix bytes ($partialLenA)", partialLenA >= 800_000L)

        val stateA = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == zipA.absolutePath }
        assertNotNull("Recovery journal must be preserved after mid-tail-swap crash", stateA)
        val guardA = ResumeJournalManager.loadTailSwapGuard(stateA!!.stateFilePath)
        assertNotNull("TailSwapGuardState must be preserved after mid-tail-swap crash", guardA)
        assertTrue("Guard must record overwriteStarted=true", guardA!!.overwriteStarted)

        // Restore must remain Unrecoverable for mutated tail-swap states
        assertTrue(
            "Mutated mid-tail-swap state must remain Unrecoverable for Restore",
            ResumeJournalManager.assessRestoreEligibility(stateA, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )
        // Without a valid v2 transaction checkpoint (legacy-unsafe state), Resume MUST fail closed
        // and STILL preserve partial DEFLATE output and recovery metadata!
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateA.stateFilePath)
        assertTrue(
            "Legacy mutated mid-tail-swap state without v2 checkpoint must remain Unrecoverable for Resume",
            ResumeJournalManager.assessResumeEligibility(stateA, context) is ResumeJournalManager.ResumeEligibility.Unrecoverable
        )
        assertFalse(ResumeJournalManager.isStateSafelyResumable(stateA, context))

        val resumeA = KaiHatchEngine.resumeInterruptedExtraction(context, stateA).toList()
        assertEquals(ExtractionStatus.ERROR, resumeA.last().status)
        assertTrue(
            "Rejected resume must NOT delete preserved partial DEFLATE output",
            partialOutA.exists() && partialOutA.length() == partialLenA
        )
        assertNotNull(
            "Rejected resume must preserve TailSwapGuardState",
            ResumeJournalManager.loadTailSwapGuard(stateA.stateFilePath)
        )
        ResumeJournalManager.deleteState(stateA.stateFilePath)

        // Case B: Unexpected CancellationException after tail-swap mutation has started
        val zipB = tempFolder.newFile("preserve_cancel_b.zip")
        val outDirB = tempFolder.newFolder("out_preserve_cancel_b")
        createLargeDeflateZip(zipB, entryName, payload)
        val partialOutB = File(outDirB, entryName)

        val cancelFired = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_TAILSWAP_TRUNCATE && cancelFired.compareAndSet(false, true)) {
                    throw CancellationException("Simulated unexpected coroutine cancellation after tail-swap mutation")
                }
            }
        }
        try {
            KaiHatchEngine.extractArchive(
                archiveFile = zipB,
                targetDir = outDirB,
                isHatchMode = true,
                context = context
            ).toList()
        } catch (_: CancellationException) {
            // expected
        } finally {
            KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        }

        assertTrue("Cancellation fault must have fired after mutation", cancelFired.get())
        assertTrue(
            "Partial DEFLATE output must be preserved on unexpected CancellationException after mutation",
            partialOutB.exists() && partialOutB.length() >= 800_000L
        )
        val stateB = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == zipB.absolutePath }
        assertNotNull("Recovery journal must be preserved after unexpected cancellation", stateB)
        assertNotNull("TailSwapGuardState must be preserved after unexpected cancellation", ResumeJournalManager.loadTailSwapGuard(stateB!!.stateFilePath))
        assertTrue(
            "Interrupted post-mutation state must remain Unrecoverable for Restore",
            ResumeJournalManager.assessRestoreEligibility(stateB, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateB.stateFilePath)
        assertFalse(
            "Legacy interrupted post-mutation state without v2 checkpoint must NEVER be marked safely resumable",
            ResumeJournalManager.isStateSafelyResumable(stateB, context)
        )
        ResumeJournalManager.deleteState(stateB.stateFilePath)

        // Case C: Unexpected IOException after tail-swap mutation has started
        val zipC = tempFolder.newFile("preserve_ioerr_c.zip")
        val outDirC = tempFolder.newFolder("out_preserve_ioerr_c")
        createLargeDeflateZip(zipC, entryName, payload)
        val partialOutC = File(outDirC, entryName)

        val ioFired = AtomicBoolean(false)
        KaiHatchEngine.faultInjector = object : TruncationFaultInjector {
            override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
                if (point == DestructivePoint.AFTER_TAILSWAP_TRUNCATE && ioFired.compareAndSet(false, true)) {
                    throw IOException("Simulated unexpected I/O error after tail-swap mutation")
                }
            }
        }
        val runC = KaiHatchEngine.extractArchive(
            archiveFile = zipC,
            targetDir = outDirC,
            isHatchMode = true,
            context = context
        ).toList()
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp

        assertTrue("I/O fault must have fired after mutation", ioFired.get())
        assertEquals(ExtractionStatus.ERROR, runC.last().status)
        assertTrue(
            "Partial DEFLATE output must be preserved on unexpected IOException after mutation",
            partialOutC.exists() && partialOutC.length() >= 800_000L
        )
        val stateC = ResumeJournalManager.loadPendingStates(context)
            .firstOrNull { it.archivePath == zipC.absolutePath }
        assertNotNull("Recovery journal must be preserved after unexpected I/O error", stateC)
        assertNotNull("TailSwapGuardState must be preserved after unexpected I/O error", ResumeJournalManager.loadTailSwapGuard(stateC!!.stateFilePath))
        assertTrue(
            "Interrupted post-mutation I/O error state must remain Unrecoverable for Restore",
            ResumeJournalManager.assessRestoreEligibility(stateC, context) is ResumeJournalManager.RestoreEligibility.Unrecoverable
        )
        ResumeJournalManager.clearDeflateTailSwapCheckpoint(stateC.stateFilePath)
        assertFalse(
            "Legacy interrupted post-mutation I/O error state without v2 checkpoint must NEVER be marked safely resumable",
            ResumeJournalManager.isStateSafelyResumable(stateC, context)
        )
        ResumeJournalManager.deleteState(stateC.stateFilePath)
    }
}
