package com.biplov.kaihatch.engine

import com.biplov.kaihatch.model.VerificationLevel
import com.biplov.kaihatch.model.VerificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifyBeforeHatchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createValidMixedZip(file: File): ByteArray {
        val random = Random(99)
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            // 1. STORED entry
            val storedPayload = ByteArray(32 * 1024).also { random.nextBytes(it) }
            val crc = CRC32().apply { update(storedPayload) }.value
            val storedEntry = ZipEntry("raw/frame_stored.bin").apply {
                method = ZipEntry.STORED
                size = storedPayload.size.toLong()
                compressedSize = storedPayload.size.toLong()
                this.crc = crc
            }
            zos.putNextEntry(storedEntry)
            zos.write(storedPayload)
            zos.closeEntry()

            // 2. DEFLATED entry
            val deflatedPayload = ("KaiHatch Phase 2 Verify Before Hatch payload line\n".repeat(1200))
                .toByteArray(Charsets.UTF_8)
            val deflatedEntry = ZipEntry("logs/engine_deflated.txt").apply {
                method = ZipEntry.DEFLATED
            }
            zos.putNextEntry(deflatedEntry)
            zos.write(deflatedPayload)
            zos.closeEntry()
        }
        return file.readBytes()
    }

    @Test
    fun `valid ZIP distinguishes Quick Check structural pass from Full Verify integrity pass without modifying source`() = runBlocking {
        val zipFile = tempFolder.newFile("valid_mixed.zip")
        val originalBytes = createValidMixedZip(zipFile)

        val quickResult = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.QUICK_CHECK
        ).last()

        assertEquals(VerificationStatus.PASSED_STRUCTURAL, quickResult.status)
        assertEquals(2, quickResult.structuralCheckedEntries)
        assertEquals(0, quickResult.crcVerifiedEntries)
        assertNotNull(quickResult.sourceFingerprint)
        assertArrayEquals("Source archive must remain byte-for-byte untouched after Quick Check", originalBytes, zipFile.readBytes())

        val fullResult = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.FULL_VERIFY
        ).last()

        assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, fullResult.status)
        assertEquals(2, fullResult.structuralCheckedEntries)
        assertEquals(2, fullResult.crcVerifiedEntries)
        assertNotNull(fullResult.sourceFingerprint)
        assertArrayEquals("Source archive must remain byte-for-byte untouched after Full Verify", originalBytes, zipFile.readBytes())
    }

    @Test
    fun `corrupted payload inside valid ZIP structure passes Quick Check but fails Full Verify`() = runBlocking {
        val zipFile = tempFolder.newFile("payload_corrupted.zip")
        val originalBytes = createValidMixedZip(zipFile)

        // Corrupt 4 bytes inside the first entry's STORED payload (offset 64, well after the 30B+name Local File Header)
        val corruptedBytes = originalBytes.copyOf()
        for (i in 64 until 68) {
            corruptedBytes[i] = (corruptedBytes[i].toInt() xor 0xFF).toByte()
        }
        zipFile.writeBytes(corruptedBytes)

        // 1. Quick Check only validates structure, headers, and offset spans -> passes structurally
        val quickResult = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.QUICK_CHECK
        ).last()
        assertEquals(VerificationStatus.PASSED_STRUCTURAL, quickResult.status)

        // 2. Full Verify streams payloads and verifies CRC32 -> catches corruption in raw/frame_stored.bin
        val fullResult = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.FULL_VERIFY
        ).last()
        assertEquals(VerificationStatus.FAILED, fullResult.status)
        assertEquals("raw/frame_stored.bin", fullResult.corruptedEntryPath)
        assertArrayEquals("Corrupted source archive must not be modified or truncated by failed verification", corruptedBytes, zipFile.readBytes())
    }

    @Test
    fun `corrupted Local File Header signature fails both Quick Check and Full Verify`() = runBlocking {
        val zipFile = tempFolder.newFile("header_corrupted.zip")
        val originalBytes = createValidMixedZip(zipFile)

        // Corrupt the first Local File Header signature at offset 0
        val corrupted = originalBytes.copyOf()
        corrupted[0] = 0x00
        corrupted[1] = 0x00
        zipFile.writeBytes(corrupted)

        val quickResult = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.QUICK_CHECK
        ).last()
        assertEquals(VerificationStatus.FAILED, quickResult.status)

        val fullResult = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.FULL_VERIFY
        ).last()
        assertEquals(VerificationStatus.FAILED, fullResult.status)
    }

    @Test
    fun `ZIP64 archive passes Quick Check and Full Verify and detects corrupted ZIP64 EOCD record`() = runBlocking {
        val zip64File = tempFolder.newFile("archive_zip64.zip")
        ZipArchiveOutputStream(zip64File).use { zaos ->
            zaos.setUseZip64(Zip64Mode.Always)
            for (idx in 0 until 3) {
                val entry = ZipArchiveEntry("zip64_dir/chunk_$idx.dat")
                val data = ("ZIP64 payload block $idx - ".repeat(1024)).toByteArray(Charsets.UTF_8)
                zaos.putArchiveEntry(entry)
                zaos.write(data)
                zaos.closeArchiveEntry()
            }
            zaos.finish()
        }

        val quickState = KaiHatchEngine.verifyArchive(
            archiveFile = zip64File,
            level = VerificationLevel.QUICK_CHECK
        ).last()
        assertEquals(VerificationStatus.PASSED_STRUCTURAL, quickState.status)
        assertEquals(3, quickState.structuralCheckedEntries)

        val fullState = KaiHatchEngine.verifyArchive(
            archiveFile = zip64File,
            level = VerificationLevel.FULL_VERIFY
        ).last()
        assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, fullState.status)
        assertEquals(3, fullState.crcVerifiedEntries)

        // Corrupt the ZIP64 EOCD Record signature (0x06064b50) and verify Quick Check catches it
        val bytes = zip64File.readBytes()
        var corruptedRecord = false
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x50.toByte() &&
                bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x06.toByte() &&
                bytes[i + 3] == 0x06.toByte()
            ) {
                bytes[i + 2] = 0xFF.toByte()
                corruptedRecord = true
                break
            }
        }
        assertTrue("ZIP64 EOCD Record signature should be present in test archive", corruptedRecord)
        zip64File.writeBytes(bytes)

        val corruptedZip64Result = KaiHatchEngine.verifyArchive(
            archiveFile = zip64File,
            level = VerificationLevel.QUICK_CHECK
        ).last()
        assertEquals(VerificationStatus.FAILED, corruptedZip64Result.status)
    }

    @Test
    fun `cancelling Full Verify mid-stream stops cleanly without modifying source archive`() = runBlocking {
        val largeZip = tempFolder.newFile("cancellable.zip")
        val random = Random(7)
        val chunk = ByteArray(256 * 1024).also { random.nextBytes(it) }
        ZipOutputStream(FileOutputStream(largeZip)).use { zos ->
            for (i in 0 until 40) {
                val crc = CRC32().apply { update(chunk) }.value
                val entry = ZipEntry("stream/block_$i.bin").apply {
                    method = ZipEntry.STORED
                    size = chunk.size.toLong()
                    compressedSize = chunk.size.toLong()
                    this.crc = crc
                }
                zos.putNextEntry(entry)
                zos.write(chunk)
                zos.closeEntry()
            }
        }
        val expectedLength = largeZip.length()
        val fpBefore = KaiHatchEngine.captureSourceFingerprint(largeZip)
        assertNotNull(fpBefore)

        var sawRunningEmission = false
        var caughtCancellation = false
        val job = launch {
            try {
                KaiHatchEngine.verifyArchive(
                    archiveFile = largeZip,
                    level = VerificationLevel.FULL_VERIFY
                ).collect { state ->
                    if (state.status == VerificationStatus.RUNNING) {
                        sawRunningEmission = true
                        delay(25)
                    }
                }
            } catch (ce: CancellationException) {
                caughtCancellation = true
                throw ce
            }
        }

        delay(15)
        job.cancelAndJoin()

        assertTrue("Verification should emit RUNNING before cancellation", sawRunningEmission)
        assertTrue("Coroutine cancellation should propagate cleanly", caughtCancellation)
        assertEquals("Source archive length must not change after cancellation", expectedLength, largeZip.length())
        assertFalse(
            "Source archive fingerprint must remain unchanged after cancellation",
            KaiHatchEngine.hasSourceChangedSinceVerification(fpBefore!!, archiveFile = largeZip)
        )
    }

    @Test
    fun `unsupported formats and methods report UNSUPPORTED rather than false success`() = runBlocking {
        // 1. RAR archive -> UNSUPPORTED
        val rarFile = tempFolder.newFile("unsupported.rar")
        rarFile.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0x00, 0x00))

        val rarQuick = KaiHatchEngine.verifyArchive(rarFile, VerificationLevel.QUICK_CHECK).last()
        assertEquals(VerificationStatus.UNSUPPORTED, rarQuick.status)

        val rarFull = KaiHatchEngine.verifyArchive(rarFile, VerificationLevel.FULL_VERIFY).last()
        assertEquals(VerificationStatus.UNSUPPORTED, rarFull.status)

        // 2. Uncompressed TAR archive -> Quick Check passes structural header checksums, Full Verify reports UNSUPPORTED
        val tarFile = tempFolder.newFile("sample.tar")
        TarArchiveOutputStream(FileOutputStream(tarFile)).use { tos ->
            val payload = "TAR payload without per-file CRC32".toByteArray(Charsets.UTF_8)
            val entry = TarArchiveEntry("docs/readme.txt").apply {
                size = payload.size.toLong()
            }
            tos.putArchiveEntry(entry)
            tos.write(payload)
            tos.closeArchiveEntry()
            tos.finish()
        }

        val tarQuick = KaiHatchEngine.verifyArchive(tarFile, VerificationLevel.QUICK_CHECK).last()
        assertEquals(VerificationStatus.PASSED_STRUCTURAL, tarQuick.status)

        val tarFull = KaiHatchEngine.verifyArchive(tarFile, VerificationLevel.FULL_VERIFY).last()
        assertEquals(VerificationStatus.UNSUPPORTED, tarFull.status)

        // 3. ZIP with unsupported compression method (WinZip AES method 99) -> UNSUPPORTED
        val aesZip = tempFolder.newFile("unsupported_aes99.zip")
        createValidMixedZip(aesZip)
        // Patch the first Central Directory entry's compressionMethod field to 99 (WinZip AES)
        val raw = aesZip.readBytes()
        for (i in raw.size - 22 downTo 0) {
            // Find Central Directory Header signature 0x02014b50
            if (raw[i] == 0x50.toByte() &&
                raw[i + 1] == 0x4b.toByte() &&
                raw[i + 2] == 0x01.toByte() &&
                raw[i + 3] == 0x02.toByte()
            ) {
                ByteBuffer.wrap(raw, i + 10, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(99.toShort())
                break
            }
        }
        aesZip.writeBytes(raw)

        val aesFull = KaiHatchEngine.verifyArchive(aesZip, VerificationLevel.FULL_VERIFY).last()
        assertEquals(VerificationStatus.UNSUPPORTED, aesFull.status)
    }

    @Test
    fun `source modification between verification and extraction is reliably detected`() = runBlocking {
        val zipFile = tempFolder.newFile("mutable_source.zip")
        createValidMixedZip(zipFile)

        val verifyState = KaiHatchEngine.verifyArchive(
            archiveFile = zipFile,
            level = VerificationLevel.FULL_VERIFY
        ).last()
        assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, verifyState.status)
        val fp = verifyState.sourceFingerprint
        assertNotNull(fp)

        // Untouched source -> false
        assertFalse(KaiHatchEngine.hasSourceChangedSinceVerification(fp!!, archiveFile = zipFile))

        // Mutate 1 byte in the archive while preserving original timestamp to test head/tail CRC32 digest detection
        val originalLastMod = zipFile.lastModified()
        RandomAccessFile(zipFile, "rw").use { raf ->
            raf.seek(16L)
            val b = raf.read()
            raf.seek(16L)
            raf.write(b xor 0xFF)
        }
        zipFile.setLastModified(originalLastMod)

        assertTrue(
            "Head/tail CRC32 fingerprint must detect in-place byte mutation even if timestamp is unchanged",
            KaiHatchEngine.hasSourceChangedSinceVerification(fp, archiveFile = zipFile)
        )
    }

    @Test
    fun `Quick Check and Full Verify pill statuses remain independent and invalidate when source is modified`() {
        // 1. Completing Quick Check marks only QUICK_CHECK as PASSED_STRUCTURAL, leaving FULL_VERIFY IDLE
        val afterQuick = com.biplov.kaihatch.model.ArchiveVerificationState(
            archiveName = "sample.zip",
            level = VerificationLevel.QUICK_CHECK,
            status = VerificationStatus.PASSED_STRUCTURAL,
            quickCheckStatus = VerificationStatus.PASSED_STRUCTURAL,
            fullVerifyStatus = VerificationStatus.IDLE
        )
        assertEquals(VerificationStatus.PASSED_STRUCTURAL, afterQuick.pillStatusFor(VerificationLevel.QUICK_CHECK))
        assertEquals(VerificationStatus.IDLE, afterQuick.pillStatusFor(VerificationLevel.FULL_VERIFY))

        // 2. Completing Full Verify independently without Quick Check marks only FULL_VERIFY as PASSED_FULL_INTEGRITY
        val onlyFull = com.biplov.kaihatch.model.ArchiveVerificationState(
            archiveName = "sample.zip",
            level = VerificationLevel.FULL_VERIFY,
            status = VerificationStatus.PASSED_FULL_INTEGRITY,
            quickCheckStatus = VerificationStatus.IDLE,
            fullVerifyStatus = VerificationStatus.PASSED_FULL_INTEGRITY
        )
        assertEquals(VerificationStatus.IDLE, onlyFull.pillStatusFor(VerificationLevel.QUICK_CHECK))
        assertEquals(VerificationStatus.PASSED_FULL_INTEGRITY, onlyFull.pillStatusFor(VerificationLevel.FULL_VERIFY))

        // 3. When source is modified after verification, outdated success indicators are invalidated to IDLE
        val invalidated = afterQuick.copy(
            fullVerifyStatus = VerificationStatus.PASSED_FULL_INTEGRITY,
            isSourceModifiedSinceVerify = true
        )
        assertEquals(VerificationStatus.IDLE, invalidated.pillStatusFor(VerificationLevel.QUICK_CHECK))
        assertEquals(VerificationStatus.IDLE, invalidated.pillStatusFor(VerificationLevel.FULL_VERIFY))
    }
}

