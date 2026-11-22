package com.biplov.kaihatch.engine

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.model.ExtractionStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32

/**
 * Tests for 7z LZMA/LZMA2 adaptive decoder memory budgeting, large dictionary support (~256 MiB / 262,166 KB),
 * low-memory protection, and clear user-facing diagnostics when an archive's dictionary exceeds device heap limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SevenZMemoryBudgetAndLzma2Test {

    private lateinit var context: Context
    private lateinit var sandboxRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sandboxRoot = Files.createTempDirectory("kaihatch_7z_mem_budget_test_").toFile()
        CommonsCompressBufferStrategy.memorySnapshotOverride = null
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
    }

    @After
    fun tearDown() {
        CommonsCompressBufferStrategy.memorySnapshotOverride = null
        KaiHatchEngine.faultInjector = TruncationFaultInjector.NoOp
        sandboxRoot.deleteRecursively()
    }

    private fun generatePayload(seed: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        val rng = Random(seed)
        var offset = 0
        val block = ByteArray(256)
        while (offset < size) {
            rng.nextBytes(block)
            for (i in 0 until minOf(128, block.size)) {
                block[i] = ((offset + i) and 0x7F).toByte()
            }
            val copyLen = minOf(block.size, size - offset)
            System.arraycopy(block, 0, out, offset, copyLen)
            offset += copyLen
        }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createSevenZArchive(
        file: File,
        entries: Map<String, ByteArray>,
        method: SevenZMethod = SevenZMethod.LZMA2
    ) {
        SevenZOutputFile(file).use { sevenZOut ->
            sevenZOut.setContentMethods(listOf(SevenZMethodConfiguration(method)))
            for ((path, data) in entries) {
                val entry: SevenZArchiveEntry = sevenZOut.createArchiveEntry(File(sandboxRoot, path), path)
                entry.size = data.size.toLong()
                sevenZOut.putArchiveEntry(entry)
                sevenZOut.write(data)
                sevenZOut.closeArchiveEntry()
            }
            sevenZOut.finish()
        }
    }

    /**
     * Patches the 1-byte LZMA2 dictionary size property inside the unencoded 7z NextHeader
     * and recomputes NextHeaderCRC and StartHeaderCRC so SevenZFile sees the target LZMA2
     * dictionary requirement (e.g. propByte = 32 -> 256 MiB dictionary -> 262,166 KB required).
     */
    private fun patchLzma2DictionaryPropertyInSevenZHeader(archiveFile: File, lzma2PropByte: Int) {
        RandomAccessFile(archiveFile, "rw").use { raf ->
            val sigHeader = ByteArray(32)
            raf.seek(0L)
            raf.readFully(sigHeader)
            val sigBuf = ByteBuffer.wrap(sigHeader).order(ByteOrder.LITTLE_ENDIAN)
            sigBuf.position(12)
            val nextHeaderOffset = sigBuf.long
            val nextHeaderSize = sigBuf.long.toInt()
            val nextHeaderPos = 32L + nextHeaderOffset

            val nextHeaderBytes = ByteArray(nextHeaderSize)
            raf.seek(nextHeaderPos)
            raf.readFully(nextHeaderBytes)

            // Locate coder ID 0x21 (LZMA2) followed by properties size 0x01 and the 1-byte dictionary prop
            var patched = false
            for (i in 0 until nextHeaderBytes.size - 3) {
                if (nextHeaderBytes[i] == 0x21.toByte() && nextHeaderBytes[i + 1] == 0x01.toByte()) {
                    nextHeaderBytes[i + 2] = (lzma2PropByte and 0xFF).toByte()
                    patched = true
                    break
                }
            }
            assertTrue("Expected to find LZMA2 coder property in 7z NextHeader", patched)

            raf.seek(nextHeaderPos)
            raf.write(nextHeaderBytes)

            val newNextHeaderCrc = CRC32().apply { update(nextHeaderBytes) }.value.toInt()
            sigBuf.putInt(28, newNextHeaderCrc)

            val newStartHeaderCrc = CRC32().apply { update(sigHeader, 12, 20) }.value.toInt()
            sigBuf.putInt(8, newStartHeaderCrc)

            raf.seek(0L)
            raf.write(sigHeader)
            raf.fd.sync()
        }
    }

    @Test
    fun manifestDeclaresLargeHeapFor256MiBLzma2Dictionaries() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val flags = app.applicationInfo.flags
        assertTrue(
            "Application must declare android:largeHeap=\"true\" so 256 MiB LZMA2 dictionaries are not blocked by the 256 MiB default heap cap",
            (flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        )
    }

    @Test
    fun adaptiveBudget_allows256MiBDictionaryOn384MiBAnd512MiBHeaps_andRejectsOn256MiBHeap() {
        val required256MiBDictKb = 262_166 // Exact requirement from Need for Speed - Most Wanted - Black Edition (USA).7z

        // 1. 512 MiB max heap with 24 MiB existing app allocations
        val budget512 = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
            snapshot = CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 512L * 1024L * 1024L,
                usedHeapBytes = 24L * 1024L * 1024L
            ),
            includeTruncatingChannelReserve = true
        )
        assertTrue(
            "512 MiB heap with 24 MiB used must support 262,166 KB LZMA2 dictionary, got ${budget512.safeDecoderLimitKb} KB",
            budget512.safeDecoderLimitKb >= required256MiBDictKb
        )
        assertTrue(
            "Budget must reserve at least 32 MiB for UI/GC/IO headroom",
            budget512.reservedHeadroomBytes >= 32L * 1024L * 1024L
        )

        // 2. 384 MiB max heap with 24 MiB existing app allocations
        val budget384 = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
            snapshot = CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 384L * 1024L * 1024L,
                usedHeapBytes = 24L * 1024L * 1024L
            ),
            includeTruncatingChannelReserve = true
        )
        assertTrue(
            "384 MiB heap with 24 MiB used must support 262,166 KB LZMA2 dictionary, got ${budget384.safeDecoderLimitKb} KB",
            budget384.safeDecoderLimitKb >= required256MiBDictKb
        )

        // 3. 256 MiB max heap with 16 MiB existing allocations (cannot physically fit a 256 MiB dictionary)
        val budget256 = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
            snapshot = CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 256L * 1024L * 1024L,
                usedHeapBytes = 16L * 1024L * 1024L
            ),
            includeTruncatingChannelReserve = true
        )
        assertTrue(
            "256 MiB max heap must set safe limit below 262,166 KB to prevent OutOfMemoryError, got ${budget256.safeDecoderLimitKb} KB",
            budget256.safeDecoderLimitKb < required256MiBDictKb
        )

        // 4. 512 MiB max heap but high existing app allocations (290 MiB used -> only 222 MiB free heap)
        val budgetHighUsed = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
            snapshot = CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 512L * 1024L * 1024L,
                usedHeapBytes = 290L * 1024L * 1024L
            ),
            includeTruncatingChannelReserve = true
        )
        assertTrue(
            "When existing app allocations leave only 222 MiB free heap, safe limit must stay below 222 MiB",
            budgetHighUsed.safeDecoderLimitKb < 222 * 1024
        )
    }

    @Test
    fun adaptiveBudget_neverAssumesFreeSystemRamEqualsAvailableJavaHeap_andEnforcesGlobalCap() {
        // 8 GB free system RAM, but only 256 MiB Java heap
        val budget = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
            snapshot = CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 256L * 1024L * 1024L,
                usedHeapBytes = 20L * 1024L * 1024L,
                systemAvailableRamBytes = 8L * 1024L * 1024L * 1024L,
                systemThresholdBytes = 256L * 1024L * 1024L,
                isSystemLowMemory = false
            ),
            includeTruncatingChannelReserve = false
        )
        assertTrue(
            "Safe decoder limit (${budget.safeDecoderLimitBytes}) must never exceed available Java heap (${budget.availableHeapBytes}) even with 8 GB free system RAM",
            budget.safeDecoderLimitBytes <= budget.availableHeapBytes - CommonsCompressBufferStrategy.MIN_BASE_HEAP_RESERVE_BYTES
        )

        // Huge 4 GB heap must still be bounded by MAX_SEVEN_Z_MEMORY_LIMIT_KB (never unrestricted)
        val hugeHeapBudget = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
            snapshot = CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 4L * 1024L * 1024L * 1024L,
                usedHeapBytes = 32L * 1024L * 1024L
            )
        )
        assertEquals(
            CommonsCompressBufferStrategy.MAX_SEVEN_Z_MEMORY_LIMIT_KB,
            hugeHeapBudget.safeDecoderLimitKb
        )
    }

    @Test
    fun lowMemoryCondition_256MiBDictionaryArchive_reportsRequiredVsConfiguredLimitAndPreservesSourceArchive() = runBlocking {
        val archiveFile = File(sandboxRoot, "Need for Speed - Most Wanted - Black Edition (USA).7z")
        val outDir = File(sandboxRoot, "nfs_out").apply { mkdirs() }
        val payload = generatePayload(777L, 64 * 1024)

        createSevenZArchive(
            file = archiveFile,
            entries = mapOf("Need for Speed - Most Wanted - Black Edition (USA).iso" to payload),
            method = SevenZMethod.LZMA2
        )
        // Prop byte 32 = 256 MiB LZMA2 dictionary -> requires 262,166 KB in Commons Compress
        patchLzma2DictionaryPropertyInSevenZHeader(archiveFile, lzma2PropByte = 32)
        val originalArchiveBytes = archiveFile.length()
        val originalArchiveHash = sha256Hex(archiveFile.readBytes())

        // Simulate constrained 256 MiB heap where safe decoder limit is ~200 MiB (< 262,166 KB)
        CommonsCompressBufferStrategy.memorySnapshotOverride = {
            CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 256L * 1024L * 1024L,
                usedHeapBytes = 20L * 1024L * 1024L
            )
        }

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outDir,
            isHatchMode = true,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(ExtractionStatus.ERROR, finalState.status)
        val errMsg = finalState.errorMessage.orEmpty()
        assertTrue(
            "Error message must report required 256 MiB LZMA2 decoder memory (262248 KB), got: $errMsg",
            errMsg.contains("262248 KB") || errMsg.contains("262166 KB")
        )
        assertTrue(
            "Error message must report configured device heap limit (204800 KB), got: $errMsg",
            errMsg.contains("safe Java heap limit of 204800 KB")
        )
        assertTrue(
            "Error message must confirm source archive is intact, got: $errMsg",
            errMsg.contains("SOURCE ARCHIVE STATE: INTACT")
        )
        assertEquals(originalArchiveBytes, archiveFile.length())
        assertEquals(originalArchiveHash, sha256Hex(archiveFile.readBytes()))
        assertFalse(
            "No partial output file should remain after MemoryLimitException",
            File(outDir, "Need for Speed - Most Wanted - Black Edition (USA).iso").exists()
        )

        // Also verify direct formatting of the exact 262166 KB LZMA1 / 190170 KB limit from the reported stack trace
        val formattedExact = KaiHatchEngine.formatSevenZMemoryLimitError(
            archiveName = "Need for Speed - Most Wanted - Black Edition (USA).7z",
            memEx = org.apache.commons.compress.MemoryLimitException(262166, 190170),
            budget = CommonsCompressBufferStrategy.computeSevenZMemoryBudget(
                CommonsCompressBufferStrategy.MemorySnapshot(
                    maxHeapBytes = 256L * 1024L * 1024L,
                    usedHeapBytes = 8_805_717L
                )
            ),
            stateDesc = "SOURCE ARCHIVE STATE: INTACT"
        )
        assertTrue(formattedExact.contains("262166 KB"))
        assertTrue(formattedExact.contains("190170 KB"))
    }

    @Test
    fun sufficientHeapCapacity_extractsLargeLzma2DictionaryArchiveSuccessfully() = runBlocking {
        val archiveFile = File(sandboxRoot, "Need for Speed - Most Wanted - Black Edition (USA).7z")
        val outDir = File(sandboxRoot, "nfs_success_out").apply { mkdirs() }
        val payload = generatePayload(999L, 128 * 1024)
        val expectedPayloadHash = sha256Hex(payload)

        createSevenZArchive(
            file = archiveFile,
            entries = mapOf("Need for Speed - Most Wanted - Black Edition (USA).iso" to payload),
            method = SevenZMethod.LZMA2
        )

        // Use 64 MiB LZMA2 dictionary (propByte = 28) or 256 MiB (propByte = 32) depending on test JVM maxMemory
        val jvmFreeHeap = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        val propByte = if (jvmFreeHeap >= 380L * 1024L * 1024L) 32 else 28 // 32 = 256 MiB dict, 28 = 64 MiB dict
        patchLzma2DictionaryPropertyInSevenZHeader(archiveFile, lzma2PropByte = propByte)

        // Simulate a 512 MiB Android largeHeap with 24 MiB used
        CommonsCompressBufferStrategy.memorySnapshotOverride = {
            CommonsCompressBufferStrategy.MemorySnapshot(
                maxHeapBytes = 512L * 1024L * 1024L,
                usedHeapBytes = 24L * 1024L * 1024L
            )
        }

        val emissions = KaiHatchEngine.extractArchive(
            archiveFile = archiveFile,
            targetDir = outDir,
            isHatchMode = false,
            context = context
        ).toList()

        val finalState = emissions.last()
        assertEquals(
            "Expected COMPLETED on 512 MiB heap budget, but got ${finalState.status}: ${finalState.errorMessage}",
            ExtractionStatus.COMPLETED,
            finalState.status
        )
        val extractedFile = File(outDir, "Need for Speed - Most Wanted - Black Edition (USA).iso")
        assertTrue("Extracted ISO must exist", extractedFile.exists())
        assertEquals(payload.size.toLong(), extractedFile.length())
        assertEquals(expectedPayloadHash, sha256Hex(extractedFile.readBytes()))
    }
}
