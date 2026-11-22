package com.biplov.kaihatch.engine

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import com.biplov.kaihatch.model.ZipEntryInfo
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.Zip64ExtendedInformationExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32

/**
 * Memory-efficient buffering and streaming strategy for Apache Commons Compress (`commons-compress`)
 * and large 64-bit archive channels (such as 2GB+ APKs and 6GB+ .7z archives).
 *
 * Key guarantees:
 * 1. Bounded heap footprint: Reuses thread-local I/O buffers sized adaptively (16 KB – 256 KB)
 *    according to JVM heap headroom, preventing per-entry `ByteArray` churn across 50,000+ APK entries.
 * 2. Read-ahead windowing (`BufferedSeekableByteChannel`): Coalesces thousands of tiny Central Directory
 *    and 7z header reads from `commons-compress` into 128 KB positional window reads (`pread`), while
 *    bypassing the window for bulk multi-megabyte payload transfers.
 * 3. Accurate 64-bit size & offset resolution: Resolves `ArchiveEntry.SIZE_UNKNOWN (-1L)`, unsigned
 *    32-bit `0xFFFFFFFFL` boundaries (>2GB signed Int overflow and >4GB Zip64), and Central Directory
 *    vs. Local Header discrepancies in large APKs.
 */
object CommonsCompressBufferStrategy {

    const val MIN_BUFFER_BYTES = 64 * 1024               // 64 KB (low-memory / small-entry fallback)
    const val DEFAULT_BUFFER_BYTES = 256 * 1024          // 256 KB (high-throughput standard streaming)
    const val LARGE_STREAM_BUFFER_BYTES = 1024 * 1024    // 1 MB (bulk streaming for >=1MB archives/entries)
    const val CHANNEL_READ_AHEAD_BYTES = 256 * 1024      // 256 KB sliding window for SeekableByteChannel
    const val SUB_ENTRY_WINDOW_BYTES = 16L * 1024L * 1024L // 16 MB sliding window for in-place chunk truncation

    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val TWO_GIGABYTES = 2L * 1024L * 1024L * 1024L

    private val threadLocalIoBuffer = ThreadLocal<ByteArray>()
    private val threadLocalInflateBuffer = ThreadLocal<ByteArray>()
    private val threadLocalHeaderScratch = ThreadLocal<ByteArray>()

    /**
     * Inspects current JVM heap headroom and returns whether memory pressure is elevated.
     */
    fun isLowMemoryPressure(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory().coerceAtLeast(1L)
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val freeHeadroom = (maxMem - usedMem).coerceAtLeast(0L)
        return freeHeadroom < 24L * 1024L * 1024L || (usedMem.toDouble() / maxMem.toDouble()) > 0.80
    }

    /**
     * Computes an adaptive buffer size (64 KB, 256 KB, or 1 MB) based on stream size and JVM heap headroom.
     */
    fun computeOptimalBufferSize(streamSizeHint: Long = -1L): Int {
        if (isLowMemoryPressure()) {
            return MIN_BUFFER_BYTES
        }
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory().coerceAtLeast(1L)
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val freeHeadroom = (maxMem - usedMem).coerceAtLeast(0L)

        return when {
            streamSizeHint in 1L..(64L * 1024L) -> MIN_BUFFER_BYTES
            (streamSizeHint >= 1024L * 1024L || streamSizeHint < 0L) &&
                    freeHeadroom >= 24L * 1024L * 1024L -> LARGE_STREAM_BUFFER_BYTES
            else -> DEFAULT_BUFFER_BYTES
        }
    }

    /**
     * Borrows a reusable thread-local primary I/O buffer sized for the given stream hint.
     * Avoids allocating a new `ByteArray` on every entry when extracting 50,000+ APK files.
     */
    fun borrowIoBuffer(streamSizeHint: Long = -1L): ByteArray {
        val targetSize = computeOptimalBufferSize(streamSizeHint)
        val existing = threadLocalIoBuffer.get()
        if (existing != null && existing.size >= targetSize) {
            return existing
        }
        val allocated = ByteArray(targetSize)
        threadLocalIoBuffer.set(allocated)
        return allocated
    }

    /**
     * Borrows a reusable thread-local secondary decompression buffer (never aliases [borrowIoBuffer]).
     */
    fun borrowInflateBuffer(streamSizeHint: Long = -1L): ByteArray {
        val targetSize = computeOptimalBufferSize(streamSizeHint)
        val existing = threadLocalInflateBuffer.get()
        if (existing != null && existing.size >= targetSize) {
            return existing
        }
        val allocated = ByteArray(targetSize)
        threadLocalInflateBuffer.set(allocated)
        return allocated
    }

    /**
     * Borrows a reusable scratch buffer for reading Central Directory filenames and extra fields.
     */
    fun borrowHeaderScratch(requiredBytes: Int): ByteArray {
        val safeCap = requiredBytes.coerceIn(256, 65536)
        val existing = threadLocalHeaderScratch.get()
        if (existing != null && existing.size >= safeCap) {
            return existing
        }
        val nextSize = safeCap.coerceAtLeast(4096)
        val allocated = ByteArray(nextSize)
        threadLocalHeaderScratch.set(allocated)
        return allocated
    }

    /**
     * Clears thread-local buffers if low memory occurs.
     */
    fun onLowMemoryCleanup() {
        threadLocalIoBuffer.remove()
        threadLocalInflateBuffer.remove()
        threadLocalHeaderScratch.remove()
    }

    const val MIN_SEVEN_Z_MEMORY_LIMIT_KB = 8 * 1024               // 8 MiB floor
    const val MAX_SEVEN_Z_MEMORY_LIMIT_KB = 768 * 1024             // 768 MiB ceiling (never unrestricted)
    const val MIN_BASE_HEAP_RESERVE_BYTES = 32L * 1024L * 1024L    // 32 MiB minimum UI/GC/IO reserve
    const val MAX_BASE_HEAP_RESERVE_BYTES = 56L * 1024L * 1024L    // 56 MiB cap on base UI/GC/IO reserve
    const val TRUNCATING_COMPACT_RESERVE_BYTES = 4L * 1024L * 1024L // 4 MiB reserve for 3x1MB swap chunks
    const val TRUNCATING_LARGE_RESERVE_BYTES = 12L * 1024L * 1024L  // 12 MiB reserve for 3x4MB swap chunks

    /**
     * Snapshot of JVM/ART heap and optional OS memory metrics for computing a safe 7z decoder budget.
     */
    data class MemorySnapshot(
        val maxHeapBytes: Long,
        val usedHeapBytes: Long,
        val systemAvailableRamBytes: Long? = null,
        val systemThresholdBytes: Long? = null,
        val isSystemLowMemory: Boolean = false
    )

    /**
     * Detailed breakdown of the computed 7z LZMA/LZMA2 decoder memory budget.
     */
    data class SevenZMemoryBudget(
        val maxHeapBytes: Long,
        val usedHeapBytes: Long,
        val availableHeapBytes: Long,
        val systemAvailableRamBytes: Long? = null,
        val systemThresholdBytes: Long? = null,
        val isSystemLowMemory: Boolean = false,
        val reservedHeadroomBytes: Long,
        val safeDecoderLimitKb: Int
    ) {
        val safeDecoderLimitBytes: Long
            get() = safeDecoderLimitKb.toLong() * 1024L
    }

    /**
     * Optional test hook allowing deterministic simulation of different Android heap capacities,
     * existing app allocations, and OS low-memory conditions in unit/Robolectric tests.
     */
    @Volatile
    var memorySnapshotOverride: (() -> MemorySnapshot)? = null

    /**
     * Releases thread-local I/O scratch buffers and, if uncollected garbage is occupying a notable
     * share of the heap, triggers a GC hint before measuring available heap headroom for 7z extraction.
     */
    fun prepareHeapForSevenZExtraction(
        context: Context? = null,
        includeTruncatingChannelReserve: Boolean = false
    ): SevenZMemoryBudget {
        onLowMemoryCleanup()
        if (memorySnapshotOverride == null) {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory().coerceAtLeast(1L)
            val usedBeforeGc = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
            val freeBeforeGc = (maxMem - usedBeforeGc).coerceAtLeast(0L)
            if (usedBeforeGc > 32L * 1024L * 1024L || freeBeforeGc < 300L * 1024L * 1024L) {
                runtime.gc()
            }
        }
        return computeSevenZMemoryBudget(
            context = context,
            includeTruncatingChannelReserve = includeTruncatingChannelReserve
        )
    }

    /**
     * Captures the current Android ART heap state and optional [ActivityManager.MemoryInfo] metrics.
     */
    fun captureMemorySnapshot(context: Context? = null): MemorySnapshot {
        memorySnapshotOverride?.let { return it.invoke() }

        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory().coerceAtLeast(16L * 1024L * 1024L)
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceIn(0L, maxHeap)

        var sysAvail: Long? = null
        var sysThreshold: Long? = null
        var sysLowMem = false

        if (context != null) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    if (memInfo.availMem > 0L) {
                        sysAvail = memInfo.availMem
                    }
                    if (memInfo.threshold > 0L) {
                        sysThreshold = memInfo.threshold
                    }
                    sysLowMem = memInfo.lowMemory
                }
            } catch (_: Throwable) {
            }
        }

        return MemorySnapshot(
            maxHeapBytes = maxHeap,
            usedHeapBytes = usedHeap,
            systemAvailableRamBytes = sysAvail,
            systemThresholdBytes = sysThreshold,
            isSystemLowMemory = sysLowMem
        )
    }

    /**
     * Computes a safe, adaptive 7z LZMA/LZMA2 decoder memory budget based on:
     * 1. The Android ART Java heap ceiling (`maxHeapBytes`) minus live app allocations (`usedHeapBytes`),
     *    because `org.tukaani.xz` allocates its dictionary `byte[]` directly on the Java heap (never
     *    assuming that free system RAM equals available Java heap).
     * 2. A dedicated safety reserve (`reservedHeadroomBytes`) for UI, ART runtime, GC headroom,
     *    streaming I/O buffers, and [HatchSevenZTruncatingChannel] swap buffers.
     * 3. Optional OS physical RAM pressure (`systemAvailableRamBytes` / `isSystemLowMemory`), which
     *    can only *reduce* the budget when physical RAM is critically low, never increase it beyond
     *    the available Java heap.
     */
    fun computeSevenZMemoryBudget(
        snapshot: MemorySnapshot,
        includeTruncatingChannelReserve: Boolean = false
    ): SevenZMemoryBudget {
        val maxHeap = snapshot.maxHeapBytes.coerceAtLeast(16L * 1024L * 1024L)
        val usedHeap = snapshot.usedHeapBytes.coerceIn(0L, maxHeap)
        val availableHeap = (maxHeap - usedHeap).coerceAtLeast(0L)

        // Never assume free system RAM increases available Java heap; only clamp downward if OS physical
        // RAM above half the LMK threshold is smaller than remaining Java heap.
        val effectiveAvailableBytes = if (snapshot.systemAvailableRamBytes != null && snapshot.systemAvailableRamBytes > 0L) {
            val lmkGuard = (snapshot.systemThresholdBytes ?: 0L).coerceAtLeast(0L) / 2L
            val usableSystemRam = (snapshot.systemAvailableRamBytes - lmkGuard).coerceAtLeast(0L)
            minOf(availableHeap, usableSystemRam)
        } else {
            availableHeap
        }

        val baseReserve = (maxHeap / 10L).coerceIn(MIN_BASE_HEAP_RESERVE_BYTES, MAX_BASE_HEAP_RESERVE_BYTES)
        val truncatingReserve = if (includeTruncatingChannelReserve) {
            if (maxHeap <= 512L * 1024L * 1024L) {
                TRUNCATING_COMPACT_RESERVE_BYTES
            } else {
                TRUNCATING_LARGE_RESERVE_BYTES
            }
        } else {
            0L
        }
        val lowMemPenalty = if (snapshot.isSystemLowMemory) 16L * 1024L * 1024L else 0L
        val totalReserve = baseReserve + truncatingReserve + lowMemPenalty

        val rawBudgetBytes = (effectiveAvailableBytes - totalReserve).coerceAtLeast(0L)
        val hardHeapCeilingBytes = (effectiveAvailableBytes - 8L * 1024L * 1024L)
            .coerceAtLeast(MIN_SEVEN_Z_MEMORY_LIMIT_KB.toLong() * 1024L)
        val clampedBudgetBytes = minOf(rawBudgetBytes, hardHeapCeilingBytes)
        val safeKb = (clampedBudgetBytes / 1024L)
            .toInt()
            .coerceIn(MIN_SEVEN_Z_MEMORY_LIMIT_KB, MAX_SEVEN_Z_MEMORY_LIMIT_KB)

        return SevenZMemoryBudget(
            maxHeapBytes = maxHeap,
            usedHeapBytes = usedHeap,
            availableHeapBytes = availableHeap,
            systemAvailableRamBytes = snapshot.systemAvailableRamBytes,
            systemThresholdBytes = snapshot.systemThresholdBytes,
            isSystemLowMemory = snapshot.isSystemLowMemory,
            reservedHeadroomBytes = totalReserve,
            safeDecoderLimitKb = safeKb
        )
    }

    fun computeSevenZMemoryBudget(
        context: Context? = null,
        includeTruncatingChannelReserve: Boolean = false
    ): SevenZMemoryBudget {
        return computeSevenZMemoryBudget(
            snapshot = captureMemorySnapshot(context),
            includeTruncatingChannelReserve = includeTruncatingChannelReserve
        )
    }

    /**
     * Computes a safe memory limit in KB for Apache Commons Compress `SevenZFileOptions` so large
     * LZMA/LZMA2 dictionaries (such as 256 MiB dictionaries requiring ~262,166 KB) can be extracted
     * on devices with sufficient heap headroom while preventing `OutOfMemoryError` on constrained heaps.
     */
    fun computeSafeSevenZMemoryLimitKb(
        context: Context? = null,
        includeTruncatingChannelReserve: Boolean = false
    ): Int {
        return computeSevenZMemoryBudget(
            context = context,
            includeTruncatingChannelReserve = includeTruncatingChannelReserve
        ).safeDecoderLimitKb
    }

    /**
     * Builds memory-safe `SevenZFileOptions` for .7z archives.
     * Note: [tryRecoverBrokenArchives] defaults to false to avoid misinterpreting valid multi-gigabyte
     * LZMA2 streams as damaged during decompression.
     */
    fun buildSevenZOptions(
        tryRecover: Boolean = false,
        maxMemoryLimitKb: Int = computeSafeSevenZMemoryLimitKb()
    ): SevenZFileOptions {
        return SevenZFileOptions.builder()
            .withMaxMemoryLimitInKb(maxMemoryLimitKb.coerceIn(MIN_SEVEN_Z_MEMORY_LIMIT_KB, MAX_SEVEN_Z_MEMORY_LIMIT_KB))
            .withTryToRecoverBrokenArchives(tryRecover)
            .build()
    }

    /**
     * Non-closing [SeekableByteChannel] wrapping a native [FileChannel].
     * Direct channel delegation eliminates intermediate sliding-window buffering and position desync
     * during multi-gigabyte LZMA/LZMA2 decompression streams.
     */
    class NonClosingSeekableByteChannel(private val delegate: FileChannel) : SeekableByteChannel {
        override fun isOpen(): Boolean = delegate.isOpen
        override fun close() {
            // Lifecycle managed by caller's FileDescriptor / FileChannel scope
        }
        override fun read(dst: ByteBuffer): Int = delegate.read(dst)
        override fun write(src: ByteBuffer): Int = delegate.write(src)
        override fun position(): Long = delegate.position()
        override fun position(newPosition: Long): SeekableByteChannel {
            delegate.position(newPosition)
            return this
        }
        override fun size(): Long = delegate.size()
        override fun truncate(size: Long): SeekableByteChannel {
            delegate.truncate(size)
            return this
        }
    }

    /**
     * Symmetric Tail-Swap In-Place Truncating [SeekableByteChannel] for `.7z` archives in Hatch Mode.
     *
     * How it works:
     * 1. During `SevenZFile` construction (`isPayloadTruncationActive == false`), `SevenZFile` reads
     *    the 32-byte `SignatureHeader` at offset 0 and the `NextHeader` / `EncodedHeader` metadata at
     *    the tail of the `.7z` file into memory.
     * 2. Once `enablePayloadTruncation()` is invoked after header parsing:
     *    - The trailing 7z `NextHeader` (after `32 + nextHeaderOffset`) is immediately `ftruncate`d.
     *    - The remaining compressed payload `[0, payloadLimit)` is partitioned into `K` chunks of
     *      `chunkSize` (512 KB – 4 MB).
     *    - As `SevenZFile` streams forward through virtual chunks `c = 0, 1, ..., K - 1`:
     *      * When `c < K - 1 - c` (`tailIdx = K - 1 - c`): chunk `c` is cached into `activeChunkBytes`
     *        in RAM, tail chunk `tailIdx` is copied from the end of the file into physical slot `c`,
     *        and the `.7z` file on disk is **immediately `ftruncate`d to `tailIdx * chunkSize`**.
     *      * When `c == K - 1 - c`: chunk `c` is cached into `activeChunkBytes` and the file is
     *        **immediately `ftruncate`d to `c * chunkSize`**.
     *      * When `c > K - 1 - c`: chunk `c` is read from its relocated slot `K - 1 - c` (which is now
     *        the physical tail of the file!) into `activeChunkBytes`, and the file is **immediately
     *        `ftruncate`d to `(K - 1 - c) * chunkSize`**.
     *    - Result: `.7z` archives physically shrink on disk every few megabytes from 0% to 100% of
     *      extraction while presenting a seamless virtual [SeekableByteChannel] to `SevenZFile`.
     */
    class HatchSevenZTruncatingChannel(
        private val delegate: FileChannel,
        private val fd: FileDescriptor?,
        private val originalSize: Long,
        private val isHatchMode: Boolean,
        private val onTruncate: (newPhysicalSize: Long, totalReclaimed: Long, freedDelta: Long) -> Unit
    ) : SeekableByteChannel {

        private var virtualPos: Long = 0L
        private var currentPhysicalSize: Long = originalSize
        private var isPayloadTruncationActive: Boolean = false
        private var canSwapInPlace: Boolean = isHatchMode && originalSize > 128L * 1024L

        private var payloadLimit: Long = originalSize
        private var chunkSize: Int = 4 * 1024 * 1024
        private var totalChunks: Int = 0
        private var nextConsumableChunk: Int = 0

        private var relocatedSlot: IntArray = IntArray(0)
        private var relocatedLen: IntArray = IntArray(0)

        private var activeChunkIndex: Int = -1
        private var activeChunkBytes: ByteArray = ByteArray(0)
        private var activeChunkValidLen: Int = 0

        private var prevChunkIndex: Int = -1
        private var prevChunkBytes: ByteArray = ByteArray(0)
        private var prevChunkValidLen: Int = 0

        private var swapScratchBytes: ByteArray = ByteArray(0)
        private var pendingRelocationChunk: Int = -1
        private var pendingHeaderStreamEnd: Long = -1L
        private var payloadSlotsModified: Boolean = false
        private var headerTailTruncated: Boolean = false

        fun getCurrentPhysicalSize(): Long = currentPhysicalSize
        fun getTotalReclaimedBytes(): Long = (originalSize - currentPhysicalSize).coerceAtLeast(0L)
        fun hasModifiedPayloadSlots(): Boolean = payloadSlotsModified
        fun hasTruncatedHeaderTail(): Boolean = headerTailTruncated
        fun hasModifiedArchiveOnDisk(): Boolean =
            payloadSlotsModified || headerTailTruncated || currentPhysicalSize < originalSize

        private fun truncateHeaderTailIfNeeded() {
            val streamEnd = pendingHeaderStreamEnd
            if (streamEnd in 64L until currentPhysicalSize) {
                pendingHeaderStreamEnd = -1L
                val prevPhys = currentPhysicalSize
                KaiHatchEngine.faultInjector.onPoint(
                    DestructivePoint.BEFORE_7Z_HEADER_TRUNCATE,
                    prevPhys,
                    "streamEnd=$streamEnd"
                )
                if (truncatePhysicalTo(streamEnd)) {
                    headerTailTruncated = true
                    KaiHatchEngine.faultInjector.onPhysicalTruncate(
                        DestructivePoint.BEFORE_7Z_HEADER_TRUNCATE,
                        prevPhys,
                        currentPhysicalSize
                    )
                }
            }
        }

        fun enablePayloadTruncation() {
            if (!isHatchMode || isPayloadTruncationActive || originalSize <= 64L * 1024L) return
            isPayloadTruncationActive = true

            // Inspect 32-byte 7z SignatureHeader to locate NextHeader offset
            val sig = ByteArray(32)
            val sigRead = readPhysicalFully(0L, sig, 0, 32)
            var streamEnd = originalSize
            if (sigRead == 32) {
                val bb = ByteBuffer.wrap(sig).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(12)
                val nextHdrOff = bb.long
                if (nextHdrOff in 1L..(originalSize - 32L)) {
                    streamEnd = 32L + nextHdrOff
                }
            }

            // Defer physical truncation of the 7z NextHeader tail until after the first chunk/entry
            // is decompressed so a corrupt header/coder or unsupported method leaves the file 100% intact.
            pendingHeaderStreamEnd = streamEnd
            payloadLimit = streamEnd.coerceAtLeast(1L)
            val maxHeap = captureMemorySnapshot().maxHeapBytes
            val maxChunkBytes = if (maxHeap <= 512L * 1024L * 1024L) {
                1024L * 1024L // 1 MiB chunks on <=512 MiB heaps (3 MiB total swap buffers instead of 12 MiB)
            } else {
                4L * 1024L * 1024L
            }
            chunkSize = (payloadLimit / 16L).coerceIn(512L * 1024L, maxChunkBytes).toInt()
            totalChunks = ((payloadLimit + chunkSize.toLong() - 1L) / chunkSize.toLong()).toInt().coerceAtLeast(1)
            nextConsumableChunk = 0
            pendingRelocationChunk = -1

            relocatedSlot = IntArray(totalChunks) { -1 }
            relocatedLen = IntArray(totalChunks) { 0 }
            // Lazily allocate activeChunkBytes, prevChunkBytes, and swapScratchBytes when payload
            // chunks are actually read/swapped so they do not consume heap before LZMA2 decoder creation.
            activeChunkBytes = ByteArray(0)
            prevChunkBytes = ByteArray(0)
            swapScratchBytes = ByteArray(0)
        }

        private fun ensureActiveChunkBuffersAllocated() {
            if (activeChunkBytes.size < chunkSize) {
                activeChunkBytes = ByteArray(chunkSize)
            }
            if (prevChunkBytes.size < chunkSize) {
                prevChunkBytes = ByteArray(chunkSize)
            }
        }

        private fun ensureSwapScratchAllocated() {
            if (swapScratchBytes.size < chunkSize) {
                swapScratchBytes = ByteArray(chunkSize)
            }
        }

        private fun readPhysicalFully(fileOffset: Long, dst: ByteArray, dstOff: Int, len: Int): Int {
            var total = 0
            val bb = ByteBuffer.wrap(dst, dstOff, len)
            while (bb.hasRemaining()) {
                val pos = fileOffset + total
                val r = try {
                    delegate.read(bb, pos)
                } catch (_: Throwable) {
                    synchronized(delegate) {
                        delegate.position(pos)
                        delegate.read(bb)
                    }
                }
                if (r <= 0) break
                total += r
            }
            return total
        }

        private fun writePhysicalFully(fileOffset: Long, src: ByteArray, srcOff: Int, len: Int): Boolean {
            if (len <= 0) return true
            try {
                val bb = ByteBuffer.wrap(src, srcOff, len)
                var pos = fileOffset
                while (bb.hasRemaining()) {
                    val w = delegate.write(bb, pos)
                    if (w <= 0) break
                    pos += w
                }
                if (!bb.hasRemaining()) return true
            } catch (_: Throwable) {
            }
            if (fd != null) {
                try {
                    var rem = len
                    var curOff = srcOff
                    var curPos = fileOffset
                    while (rem > 0) {
                        val w = Os.pwrite(fd, src, curOff, rem, curPos)
                        if (w <= 0) break
                        curOff += w
                        rem -= w
                        curPos += w
                    }
                    if (rem == 0) return true
                } catch (_: Throwable) {
                }
            }
            return false
        }

        private fun truncatePhysicalTo(targetLen: Long): Boolean {
            val safeTarget = targetLen.coerceAtLeast(0L)
            if (safeTarget >= currentPhysicalSize) return true
            var ok = false
            try {
                delegate.truncate(safeTarget)
                ok = true
            } catch (_: Throwable) {
            }
            if (!ok && fd != null) {
                try {
                    Os.ftruncate(fd, safeTarget)
                    ok = true
                } catch (_: Throwable) {
                }
                if (!ok) {
                    try {
                        FileOutputStream(fd).channel.truncate(safeTarget)
                        ok = true
                    } catch (_: Throwable) {
                    }
                }
            }
            if (ok) {
                val freedDelta = (currentPhysicalSize - safeTarget).coerceAtLeast(0L)
                currentPhysicalSize = safeTarget
                val totalReclaimed = (originalSize - currentPhysicalSize).coerceAtLeast(0L)
                onTruncate(currentPhysicalSize, totalReclaimed, freedDelta)
            }
            return ok
        }

        private fun expectedChunkLen(cIdx: Int): Int {
            val start = cIdx.toLong() * chunkSize.toLong()
            val rem = (payloadLimit - start).coerceAtLeast(0L)
            return rem.coerceAtMost(chunkSize.toLong()).toInt()
        }

        private fun commitChunkRelocationAndTruncate(c: Int) {
            if (!canSwapInPlace || c < 0 || c >= totalChunks) return
            truncateHeaderTailIfNeeded()
            val tailIdx = totalChunks - 1 - c
            if (c < tailIdx) {
                ensureSwapScratchAllocated()
                val tailOffset = tailIdx.toLong() * chunkSize.toLong()
                val tailLen = expectedChunkLen(tailIdx)
                val tailRead = readPhysicalFully(tailOffset, swapScratchBytes, 0, tailLen)
                val slotOffset = c.toLong() * chunkSize.toLong()
                KaiHatchEngine.faultInjector.onPoint(
                    DestructivePoint.BEFORE_7Z_SLOT_PWRITE,
                    currentPhysicalSize,
                    "c=$c,tailIdx=$tailIdx,slotOffset=$slotOffset,tailRead=$tailRead"
                )
                if (tailRead > 0 && KaiHatchEngine.faultInjector.shouldSimulateTornWrite(DestructivePoint.BEFORE_7Z_SLOT_PWRITE)) {
                    val halfLen = (tailRead / 2).coerceAtLeast(1)
                    payloadSlotsModified = true
                    writePhysicalFully(slotOffset, swapScratchBytes, 0, halfLen)
                    KaiHatchEngine.faultInjector.onPhysicalSlotWrite(
                        DestructivePoint.MID_7Z_SLOT_PWRITE_TORN,
                        c,
                        tailIdx,
                        slotOffset,
                        halfLen
                    )
                    KaiHatchEngine.faultInjector.onPoint(
                        DestructivePoint.MID_7Z_SLOT_PWRITE_TORN,
                        currentPhysicalSize,
                        "c=$c,tailIdx=$tailIdx,tornBytes=$halfLen/$tailRead"
                    )
                }
                if (tailRead > 0 && writePhysicalFully(slotOffset, swapScratchBytes, 0, tailRead)) {
                    payloadSlotsModified = true
                    KaiHatchEngine.faultInjector.onPhysicalSlotWrite(
                        DestructivePoint.BEFORE_7Z_SLOT_PWRITE,
                        c,
                        tailIdx,
                        slotOffset,
                        tailRead
                    )
                    KaiHatchEngine.faultInjector.onPoint(
                        DestructivePoint.AFTER_7Z_SLOT_PWRITE_BEFORE_TRUNCATE,
                        currentPhysicalSize,
                        "c=$c,tailIdx=$tailIdx,tailOffset=$tailOffset"
                    )
                    relocatedSlot[tailIdx] = c
                    relocatedLen[tailIdx] = tailRead
                    val prevPhys = currentPhysicalSize
                    if (truncatePhysicalTo(tailOffset)) {
                        KaiHatchEngine.faultInjector.onPhysicalTruncate(
                            DestructivePoint.AFTER_7Z_SLOT_TRUNCATE,
                            prevPhys,
                            currentPhysicalSize
                        )
                    }
                    KaiHatchEngine.faultInjector.onPoint(
                        DestructivePoint.AFTER_7Z_SLOT_TRUNCATE,
                        currentPhysicalSize,
                        "c=$c,tailIdx=$tailIdx"
                    )
                } else {
                    canSwapInPlace = false
                }
            } else if (c == tailIdx) {
                val midOffset = c.toLong() * chunkSize.toLong()
                val prevPhys = currentPhysicalSize
                if (truncatePhysicalTo(midOffset)) {
                    payloadSlotsModified = true
                    KaiHatchEngine.faultInjector.onPhysicalTruncate(
                        DestructivePoint.AFTER_7Z_SLOT_TRUNCATE,
                        prevPhys,
                        currentPhysicalSize
                    )
                }
                KaiHatchEngine.faultInjector.onPoint(
                    DestructivePoint.AFTER_7Z_SLOT_TRUNCATE,
                    currentPhysicalSize,
                    "c=$c,midOffset=$midOffset"
                )
            } else {
                val slot = relocatedSlot[c]
                if (slot >= 0) {
                    val slotOffset = slot.toLong() * chunkSize.toLong()
                    val prevPhys = currentPhysicalSize
                    if (truncatePhysicalTo(slotOffset)) {
                        payloadSlotsModified = true
                        KaiHatchEngine.faultInjector.onPhysicalTruncate(
                            DestructivePoint.AFTER_7Z_SLOT_TRUNCATE,
                            prevPhys,
                            currentPhysicalSize
                        )
                    }
                    KaiHatchEngine.faultInjector.onPoint(
                        DestructivePoint.AFTER_7Z_SLOT_TRUNCATE,
                        currentPhysicalSize,
                        "c=$c,slot=$slot,slotOffset=$slotOffset"
                    )
                }
            }
        }

        fun flushPendingRelocation() {
            if (isPayloadTruncationActive) {
                truncateHeaderTailIfNeeded()
            }
            val pending = pendingRelocationChunk
            if (pending >= 0) {
                pendingRelocationChunk = -1
                commitChunkRelocationAndTruncate(pending)
            }
        }

        private fun advanceAndLoadChunk(targetChunk: Int) {
            if (targetChunk == activeChunkIndex || targetChunk == prevChunkIndex) return
            if (targetChunk < 0 || targetChunk >= totalChunks) return
            ensureActiveChunkBuffersAllocated()

            // If targetChunk jumped ahead of nextConsumableChunk, advance intermediate chunks safely
            while (nextConsumableChunk <= targetChunk) {
                // First, commit relocation/truncation for the previously read & decompressed chunk!
                flushPendingRelocation()

                val c = nextConsumableChunk
                nextConsumableChunk++
                val isTarget = (c == targetChunk)

                if (isTarget && activeChunkIndex >= 0) {
                    // Rotate current active chunk into prevChunk buffer
                    val tmpBytes = prevChunkBytes
                    prevChunkBytes = activeChunkBytes
                    prevChunkIndex = activeChunkIndex
                    prevChunkValidLen = activeChunkValidLen
                    activeChunkBytes = tmpBytes
                }

                val cLen = expectedChunkLen(c)
                val tailIdx = totalChunks - 1 - c

                if (!canSwapInPlace) {
                    if (isTarget) {
                        val readBytes = readPhysicalFully(c.toLong() * chunkSize.toLong(), activeChunkBytes, 0, cLen)
                        activeChunkIndex = c
                        activeChunkValidLen = readBytes
                    }
                    continue
                }

                // Read chunk `c` into `activeChunkBytes` BEFORE mutating its physical slot
                if (c <= tailIdx) {
                    if (isTarget) {
                        val readBytes = readPhysicalFully(c.toLong() * chunkSize.toLong(), activeChunkBytes, 0, cLen)
                        activeChunkIndex = c
                        activeChunkValidLen = readBytes
                    }
                } else {
                    val slot = relocatedSlot[c]
                    if (slot >= 0) {
                        val slotOffset = slot.toLong() * chunkSize.toLong()
                        val slotLen = relocatedLen[c].takeIf { it > 0 } ?: cLen
                        if (isTarget) {
                            val readBytes = readPhysicalFully(slotOffset, activeChunkBytes, 0, slotLen)
                            activeChunkIndex = c
                            activeChunkValidLen = readBytes
                        }
                    } else {
                        val origOffset = c.toLong() * chunkSize.toLong()
                        if (isTarget) {
                            val readBytes = readPhysicalFully(origOffset, activeChunkBytes, 0, cLen)
                            activeChunkIndex = c
                            activeChunkValidLen = readBytes
                        }
                    }
                }

                // Defer physical slot relocation/truncation of chunk `c` until `SevenZFile` finishes
                // reading/decompressing chunk `c` and advances to `c + 1` (or calls flushPendingRelocation).
                pendingRelocationChunk = c
            }
        }

        override fun isOpen(): Boolean = delegate.isOpen

        override fun close() {
            // Managed by caller's scope
        }

        override fun read(dst: ByteBuffer): Int {
            if (!dst.hasRemaining()) return 0
            if (virtualPos >= originalSize) return -1

            if (!isPayloadTruncationActive || virtualPos >= payloadLimit) {
                val maxToRead = (originalSize - virtualPos).coerceAtMost(dst.remaining().toLong()).toInt()
                val slice = dst.slice()
                slice.limit(maxToRead)
                val r = try {
                    delegate.read(slice, virtualPos)
                } catch (_: Throwable) {
                    synchronized(delegate) {
                        delegate.position(virtualPos)
                        delegate.read(slice)
                    }
                }
                if (r > 0) {
                    dst.position(dst.position() + r)
                    virtualPos += r
                }
                return r
            }

            var totalCopied = 0
            while (dst.hasRemaining() && virtualPos < payloadLimit) {
                val cIdx = (virtualPos / chunkSize.toLong()).toInt()
                val offInChunk = (virtualPos % chunkSize.toLong()).toInt()

                if (cIdx != activeChunkIndex && cIdx != prevChunkIndex) {
                    advanceAndLoadChunk(cIdx)
                }

                val (srcBuf, validLen) = when (cIdx) {
                    activeChunkIndex -> activeChunkBytes to activeChunkValidLen
                    prevChunkIndex -> prevChunkBytes to prevChunkValidLen
                    else -> {
                        // Fallback direct read if an earlier chunk was requested
                        val fallbackSlot = if (cIdx < relocatedSlot.size && relocatedSlot[cIdx] >= 0) {
                            relocatedSlot[cIdx]
                        } else cIdx
                        val maxDirect = (chunkSize - offInChunk).coerceAtMost(dst.remaining())
                        val slice = dst.slice()
                        slice.limit(maxDirect)
                        val physPos = fallbackSlot.toLong() * chunkSize.toLong() + offInChunk.toLong()
                        val r = try {
                            delegate.read(slice, physPos)
                        } catch (_: Throwable) {
                            -1
                        }
                        if (r > 0) {
                            dst.position(dst.position() + r)
                            virtualPos += r
                            totalCopied += r
                            continue
                        } else {
                            break
                        }
                    }
                }

                val avail = (validLen - offInChunk).coerceAtLeast(0)
                if (avail <= 0) break
                val toCopy = minOf(avail, dst.remaining())
                dst.put(srcBuf, offInChunk, toCopy)
                virtualPos += toCopy
                totalCopied += toCopy
            }

            return if (totalCopied > 0) totalCopied else -1
        }

        override fun write(src: ByteBuffer): Int = delegate.write(src)

        override fun position(): Long = virtualPos

        override fun position(newPosition: Long): SeekableByteChannel {
            virtualPos = newPosition.coerceAtLeast(0L)
            return this
        }

        override fun size(): Long = originalSize

        override fun truncate(size: Long): SeekableByteChannel {
            truncatePhysicalTo(size)
            return this
        }
    }

    /**
     * Opens a [SevenZFile] over a [HatchSevenZTruncatingChannel] so `.7z` archives in Hatch Mode
     * perform live sub-stream `ftruncate()` throughout extraction.
     */
    fun openTruncatingSevenZFile(
        truncatingChannel: HatchSevenZTruncatingChannel,
        archiveName: String = "Archive.7z",
        password: String? = null,
        tryRecover: Boolean = false,
        context: Context? = null,
        maxMemoryLimitKb: Int = computeSafeSevenZMemoryLimitKb(
            context = context,
            includeTruncatingChannelReserve = true
        )
    ): SevenZFile {
        truncatingChannel.position(0L)
        val options = buildSevenZOptions(
            tryRecover = tryRecover,
            maxMemoryLimitKb = maxMemoryLimitKb
        )
        val sevenZ = try {
            if (!password.isNullOrEmpty()) {
                SevenZFile(truncatingChannel, archiveName, password.toCharArray(), options)
            } else {
                SevenZFile(truncatingChannel, archiveName, options)
            }
        } catch (_: LinkageError) {
            truncatingChannel.position(0L)
            if (!password.isNullOrEmpty()) {
                SevenZFile(truncatingChannel, archiveName, password.toCharArray())
            } else {
                SevenZFile(truncatingChannel, archiveName)
            }
        }
        truncatingChannel.enablePayloadTruncation()
        return sevenZ
    }

    /**
     * Opens a [SevenZFile] directly over a native [NonClosingSeekableByteChannel].
     * Recommended for multi-gigabyte payload decompression (such as 5GB+ game ISOs in .7z archives)
     * to ensure 100% byte-aligned kernel streaming without sliding-window offset drift.
     */
    fun openDirectSevenZFile(
        channel: FileChannel,
        archiveName: String = "Archive.7z",
        password: String? = null,
        tryRecover: Boolean = false,
        context: Context? = null,
        maxMemoryLimitKb: Int = computeSafeSevenZMemoryLimitKb(
            context = context,
            includeTruncatingChannelReserve = false
        )
    ): SevenZFile {
        channel.position(0L)
        val nonClosing = NonClosingSeekableByteChannel(channel)
        val options = buildSevenZOptions(
            tryRecover = tryRecover,
            maxMemoryLimitKb = maxMemoryLimitKb
        )
        return try {
            if (!password.isNullOrEmpty()) {
                SevenZFile(nonClosing, archiveName, password.toCharArray(), options)
            } else {
                SevenZFile(nonClosing, archiveName, options)
            }
        } catch (_: LinkageError) {
            channel.position(0L)
            if (!password.isNullOrEmpty()) {
                SevenZFile(nonClosing, archiveName, password.toCharArray())
            } else {
                SevenZFile(nonClosing, archiveName)
            }
        }
    }

    /**
     * Opens a `SevenZFile` over a buffered, non-closing `SeekableByteChannel` with memory guardrails.
     * Gracefully falls back to the universal `(SeekableByteChannel, String[, char[]])` constructor
     * if the runtime classloader bundles an older `SevenZFile` signature without `SevenZFileOptions`.
     */
    fun openBufferedSevenZFile(
        channel: FileChannel,
        archiveName: String = "Archive.7z",
        password: String? = null,
        closeChannelOnClose: Boolean = false,
        tryRecover: Boolean = false,
        context: Context? = null,
        maxMemoryLimitKb: Int = computeSafeSevenZMemoryLimitKb(
            context = context,
            includeTruncatingChannelReserve = false
        )
    ): SevenZFile {
        val startPos = try {
            channel.position()
        } catch (_: Throwable) {
            0L
        }
        val bufferedChannel = BufferedSeekableByteChannel(
            delegate = channel,
            readAheadCapacity = CHANNEL_READ_AHEAD_BYTES,
            closeDelegateOnClose = closeChannelOnClose
        )
        return try {
            val options = buildSevenZOptions(
                tryRecover = tryRecover,
                maxMemoryLimitKb = maxMemoryLimitKb
            )
            if (!password.isNullOrEmpty()) {
                SevenZFile(bufferedChannel, archiveName, password.toCharArray(), options)
            } else {
                SevenZFile(bufferedChannel, archiveName, options)
            }
        } catch (_: LinkageError) {
            bufferedChannel.position(startPos)
            if (!password.isNullOrEmpty()) {
                SevenZFile(bufferedChannel, archiveName, password.toCharArray())
            } else {
                SevenZFile(bufferedChannel, archiveName)
            }
        }
    }

    /**
     * Opens an Apache Commons Compress `ZipFile` over a `BufferedSeekableByteChannel` with
     * `ignoreLocalFileHeader = true` so Central Directory inspection on 2GB+ APKs (with 50,000+ entries)
     * reads only the contiguous Central Directory tail without seeking to 50,000 Local File Headers.
     * Falls back to the universal 4-argument constructor if the runtime classloader lacks the 5-arg overload.
     */
    fun openBufferedCommonsZipFile(
        channel: FileChannel,
        archiveName: String = "Archive.zip",
        closeChannelOnClose: Boolean = false
    ): ZipFile {
        val startPos = try {
            channel.position()
        } catch (_: Throwable) {
            0L
        }
        val bufferedChannel = BufferedSeekableByteChannel(
            delegate = channel,
            readAheadCapacity = CHANNEL_READ_AHEAD_BYTES,
            closeDelegateOnClose = closeChannelOnClose
        )
        return try {
            ZipFile(
                bufferedChannel,
                archiveName,
                "UTF-8",
                true,
                true // ignoreLocalFileHeader = true for instant Central Directory parsing on 2GB+ APKs
            )
        } catch (_: LinkageError) {
            bufferedChannel.position(startPos)
            ZipFile(
                bufferedChannel,
                archiveName,
                "UTF-8",
                true
            )
        }
    }

    private val localHeaderOffsetMethod by lazy {
        try {
            ZipArchiveEntry::class.java.getDeclaredMethod("getLocalHeaderOffset").apply {
                isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses ZIP / APK / ZIP64 entries using Apache Commons Compress `ZipFile` over a
     * `BufferedSeekableByteChannel`, accurately resolving 64-bit sizes, offsets, and `SIZE_UNKNOWN`.
     */
    fun parseZipWithCommonsCompress(
        channel: FileChannel,
        archiveName: String = "Archive.zip"
    ): List<ZipEntryInfo> {
        val fileLen = try {
            channel.size().coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        if (fileLen < 22L) return emptyList()

        return try {
            openBufferedCommonsZipFile(channel, archiveName, closeChannelOnClose = false).use { zipFile ->
                val entries = ArrayList<ZipEntryInfo>()
                // Note: In commons-compress 1.21, entriesInPhysicalOrder (offsetList) is only populated
                // when ignoreLocalFileHeader == false, whereas zipFile.entries is always populated
                // directly from the Central Directory in physical Central Directory order.
                val rawEnum = zipFile.entries
                var count = 0
                val maxEntries = 150_000

                while (rawEnum.hasMoreElements() && count < maxEntries) {
                    val ze: ZipArchiveEntry = rawEnum.nextElement()
                    val rawPath = ze.name ?: continue
                    if (rawPath.isEmpty()) continue

                    val isDir = ze.isDirectory || rawPath.endsWith("/")
                    val cleanName = if (isDir) {
                        rawPath.trimEnd('/').substringAfterLast('/').ifEmpty { rawPath }
                    } else {
                        rawPath.substringAfterLast('/').ifEmpty { rawPath }
                    }

                    val resolvedSizes = resolveZipArchiveEntrySizes(ze, fileLen)
                    val isEncrypted = ze.generalPurposeBit?.usesEncryption() == true || ze.method == 99
                    val lastMod = try {
                        ze.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } catch (_: Throwable) {
                        System.currentTimeMillis()
                    }

                    entries.add(
                        ZipEntryInfo(
                            path = rawPath,
                            name = cleanName,
                            isDirectory = isDir,
                            compressedSize = resolvedSizes.compressedSize,
                            uncompressedSize = resolvedSizes.uncompressedSize,
                            crc32 = ze.crc.coerceAtLeast(0L),
                            compressionMethod = ze.method.coerceAtLeast(0),
                            localHeaderOffset = resolvedSizes.localHeaderOffset,
                            lastModified = lastMod,
                            isEncrypted = isEncrypted,
                            isZip64 = resolvedSizes.isZip64
                        )
                    )
                    count++
                }
                if (entries.size > 1) {
                    entries.sortBy { it.localHeaderOffset }
                }
                if (entries.isNotEmpty()) {
                    entries
                } else {
                    ZipParser.parseZipChannel(channel)
                }
            }
        } catch (_: Throwable) {
            ZipParser.parseZipChannel(channel)
        }
    }

    data class ResolvedEntryTelemetry(
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val isZip64: Boolean
    )

    /**
     * Resolves accurate 64-bit `compressedSize`, `uncompressedSize`, and `localHeaderOffset` for a
     * `commons-compress` `ZipArchiveEntry`, handling `ArchiveEntry.SIZE_UNKNOWN (-1L)`, 32-bit
     * unsigned `0xFFFFFFFFL` sentinels, and `Zip64ExtendedInformationExtraField (0x0001)`.
     */
    fun resolveZipArchiveEntrySizes(
        entry: ZipArchiveEntry,
        archiveFileSize: Long
    ): ResolvedEntryTelemetry {
        var cSize = entry.compressedSize
        var uSize = entry.size
        var offset = try {
            val lfhOffset = (localHeaderOffsetMethod?.invoke(entry) as? Long)?.takeIf { it >= 0L }
            lfhOffset ?: entry.dataOffset.takeIf { it >= 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }

        var isZip64 = archiveFileSize >= UINT32_MAX

        val z64Extra = try {
            entry.getExtraField(org.apache.commons.compress.archivers.zip.ZipShort(0x0001)) as? Zip64ExtendedInformationExtraField
        } catch (_: Throwable) {
            null
        }

        if (z64Extra != null) {
            isZip64 = true
            z64Extra.size?.longValue?.let { if (it >= 0L) uSize = it }
            z64Extra.compressedSize?.longValue?.let { if (it >= 0L) cSize = it }
            z64Extra.relativeHeaderOffset?.longValue?.let { if (it >= 0L) offset = it }
        }

        if (cSize == ArchiveEntry.SIZE_UNKNOWN || cSize < 0L || cSize == UINT32_MAX) {
            cSize = 0L
        }
        if (uSize == ArchiveEntry.SIZE_UNKNOWN || uSize < 0L || uSize == UINT32_MAX) {
            // If uncompressedSize is unknown in streaming header, fallback to compressedSize
            uSize = cSize
        } else if (uSize == 0L && cSize > 0L && !entry.isDirectory) {
            uSize = cSize
        }

        if (offset < 0L || offset > archiveFileSize) {
            offset = 0L
        }

        if (uSize >= UINT32_MAX || cSize >= UINT32_MAX || offset >= UINT32_MAX) {
            isZip64 = true
        }

        return ResolvedEntryTelemetry(
            compressedSize = cSize,
            uncompressedSize = uSize,
            localHeaderOffset = offset,
            isZip64 = isZip64
        )
    }

    /**
     * Parses a `.tar` or decompressed `.tar.gz` `InputStream` using Apache Commons Compress
     * `TarArchiveInputStream` with bounded buffered reads and safe entry skipping.
     */
    fun parseTarStreamWithCommonsCompress(
        rawInput: InputStream,
        streamSizeHint: Long = -1L
    ): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        val bufSize = computeOptimalBufferSize(streamSizeHint)
        val buffered = if (rawInput is BufferedInputStream) rawInput else BufferedInputStream(rawInput, bufSize)

        try {
            val tarIn = TarArchiveInputStream(buffered)
            var simulatedOffset = 0L
            var count = 0
            val maxEntries = 150_000

            while (count < maxEntries) {
                val entry = try {
                    tarIn.nextTarEntry
                } catch (_: Throwable) {
                    null
                } ?: break

                val rawName = entry.name?.replace('\\', '/')?.trimStart('/') ?: continue
                if (rawName.isEmpty()) continue
                val isDir = entry.isDirectory || rawName.endsWith("/")
                val cleanName = rawName.trimEnd('/').substringAfterLast('/').ifEmpty { rawName }
                val entrySize = entry.realSize.coerceAtLeast(entry.size.coerceAtLeast(0L))
                val lastMod = try {
                    entry.modTime?.time ?: System.currentTimeMillis()
                } catch (_: Throwable) {
                    System.currentTimeMillis()
                }

                entries.add(
                    ZipEntryInfo(
                        path = if (isDir && !rawName.endsWith("/")) "$rawName/" else rawName,
                        name = cleanName,
                        isDirectory = isDir,
                        compressedSize = entrySize,
                        uncompressedSize = entrySize,
                        crc32 = 0L,
                        compressionMethod = 100, // TAR 512-byte block
                        localHeaderOffset = simulatedOffset,
                        lastModified = lastMod,
                        isEncrypted = false,
                        isZip64 = entrySize >= UINT32_MAX || simulatedOffset >= UINT32_MAX
                    )
                )

                val dataBlocks = (entrySize + 511L) / 512L
                simulatedOffset += 512L + (dataBlocks * 512L)
                count++
            }
        } catch (_: Throwable) {
        }
        return entries
    }

    /**
     * Wraps an `OutputStream` in a memory-efficient `BufferedOutputStream` sized for the target stream.
     */
    fun wrapBufferedOutput(out: OutputStream, streamSizeHint: Long = -1L): BufferedOutputStream {
        return if (out is BufferedOutputStream) out else BufferedOutputStream(out, computeOptimalBufferSize(streamSizeHint))
    }

    /**
     * Wraps an `InputStream` in a memory-efficient `BufferedInputStream` sized for the source stream.
     */
    fun wrapBufferedInput(input: InputStream, streamSizeHint: Long = -1L): BufferedInputStream {
        return if (input is BufferedInputStream) input else BufferedInputStream(input, computeOptimalBufferSize(streamSizeHint))
    }

    /**
     * Copies up to [maxBytes] from [input] to [output] using the thread-local adaptive buffer pool,
     * optionally updating [crc] and reporting incremental byte counts via [onChunkWritten].
     */
    inline fun copyStreamBounded(
        input: InputStream,
        output: OutputStream?,
        streamSizeHint: Long = -1L,
        maxBytes: Long = Long.MAX_VALUE,
        crc: CRC32? = null,
        shouldContinue: () -> Boolean = { true },
        onChunkWritten: (bytesReadInChunk: Int, totalBytesCopied: Long) -> Unit = { _, _ -> }
    ): Long {
        val buffer = borrowIoBuffer(streamSizeHint)
        var totalCopied = 0L
        var remaining = maxBytes

        while (remaining > 0L && shouldContinue()) {
            val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break

            output?.write(buffer, 0, read)
            crc?.update(buffer, 0, read)
            totalCopied += read
            remaining -= read
            onChunkWritten(read, totalCopied)
        }
        output?.flush()
        return totalCopied
    }

    /**
     * Read-ahead buffered [SeekableByteChannel] tailored for Apache Commons Compress (`ZipFile`,
     * `SevenZFile`) and 64-bit random-access archive parsing over Android `FileChannel` / SAF descriptors.
     *
     * Eliminates syscall thrashing when `commons-compress` performs tens of thousands of small
     * header reads across 2GB+ APKs or 6GB+ .7z files.
     */
    class BufferedSeekableByteChannel(
        private val delegate: FileChannel,
        readAheadCapacity: Int = CHANNEL_READ_AHEAD_BYTES,
        private val closeDelegateOnClose: Boolean = false
    ) : SeekableByteChannel {

        private val windowCapacity = readAheadCapacity.coerceIn(MIN_BUFFER_BYTES, LARGE_STREAM_BUFFER_BYTES)
        private val windowBytes = ByteArray(windowCapacity)
        private val windowBuffer = ByteBuffer.wrap(windowBytes).order(ByteOrder.LITTLE_ENDIAN)

        private var windowStartOffset: Long = -1L
        private var windowValidLength: Int = 0
        private var logicalPos: Long = try {
            delegate.position()
        } catch (_: Throwable) {
            0L
        }
        private var cachedSize: Long = try {
            delegate.size()
        } catch (_: Throwable) {
            0L
        }
        private var closed = false

        fun invalidateCache() {
            windowStartOffset = -1L
            windowValidLength = 0
            cachedSize = try {
                delegate.size()
            } catch (_: Throwable) {
                cachedSize
            }
        }

        override fun isOpen(): Boolean = !closed && delegate.isOpen

        override fun close() {
            closed = true
            windowStartOffset = -1L
            windowValidLength = 0
            if (closeDelegateOnClose) {
                delegate.close()
            }
        }

        override fun position(): Long {
            if (!isOpen) throw ClosedChannelException()
            return logicalPos
        }

        override fun position(newPosition: Long): SeekableByteChannel {
            if (!isOpen) throw ClosedChannelException()
            require(newPosition >= 0L) { "Negative position: $newPosition" }
            logicalPos = newPosition
            try {
                delegate.position(newPosition)
            } catch (_: Throwable) {
            }
            return this
        }

        override fun size(): Long {
            if (!isOpen) throw ClosedChannelException()
            return cachedSize
        }

        override fun read(dst: ByteBuffer): Int {
            if (!isOpen) throw ClosedChannelException()
            val requested = dst.remaining()
            if (requested == 0) return 0
            if (logicalPos >= cachedSize) return -1

            // Fast path for bulk payload reads (>= windowCapacity): bypass and invalidate sliding window
            if (requested >= windowCapacity) {
                invalidateCache()
                val directRead = readDirectFromDelegate(dst, logicalPos)
                if (directRead > 0) {
                    logicalPos += directRead
                    try {
                        delegate.position(logicalPos)
                    } catch (_: Throwable) {
                    }
                }
                return directRead
            }

            var totalCopied = 0
            while (dst.hasRemaining() && logicalPos < cachedSize) {
                if (!isOffsetInWindow(logicalPos)) {
                    val filled = refillWindowAt(logicalPos)
                    if (filled <= 0) break
                }
                val offsetInWindow = (logicalPos - windowStartOffset).toInt()
                val availableInWindow = windowValidLength - offsetInWindow
                if (availableInWindow <= 0) break

                val toCopy = minOf(dst.remaining(), availableInWindow)
                dst.put(windowBytes, offsetInWindow, toCopy)
                logicalPos += toCopy
                totalCopied += toCopy
            }

            return if (totalCopied > 0) totalCopied else -1
        }

        override fun write(src: ByteBuffer): Int {
            if (!isOpen) throw ClosedChannelException()
            invalidateCache()
            delegate.position(logicalPos)
            val written = delegate.write(src)
            if (written > 0) {
                logicalPos += written
                if (logicalPos > cachedSize) {
                    cachedSize = logicalPos
                }
            }
            return written
        }

        override fun truncate(size: Long): SeekableByteChannel {
            if (!isOpen) throw ClosedChannelException()
            invalidateCache()
            delegate.truncate(size)
            cachedSize = size.coerceAtLeast(0L)
            if (logicalPos > cachedSize) {
                logicalPos = cachedSize
            }
            return this
        }

        private fun isOffsetInWindow(pos: Long): Boolean {
            return windowStartOffset >= 0L &&
                    windowValidLength > 0 &&
                    pos >= windowStartOffset &&
                    pos < windowStartOffset + windowValidLength
        }

        private fun refillWindowAt(targetOffset: Long): Int {
            windowBuffer.clear()
            val maxToRead = (cachedSize - targetOffset).coerceAtMost(windowCapacity.toLong()).toInt()
            if (maxToRead <= 0) {
                windowStartOffset = -1L
                windowValidLength = 0
                return -1
            }
            windowBuffer.limit(maxToRead)

            var totalRead = 0
            while (windowBuffer.hasRemaining()) {
                val r = try {
                    delegate.read(windowBuffer, targetOffset + totalRead)
                } catch (_: Throwable) {
                    // Fallback to synchronized position + read if positional pread is unsupported
                    delegate.position(targetOffset + totalRead)
                    delegate.read(windowBuffer)
                }
                if (r <= 0) break
                totalRead += r
            }

            return if (totalRead > 0) {
                windowStartOffset = targetOffset
                windowValidLength = totalRead
                totalRead
            } else {
                windowStartOffset = -1L
                windowValidLength = 0
                -1
            }
        }

        private fun readDirectFromDelegate(dst: ByteBuffer, startOffset: Long): Int {
            var totalRead = 0
            while (dst.hasRemaining() && (startOffset + totalRead) < cachedSize) {
                val r = try {
                    delegate.read(dst, startOffset + totalRead)
                } catch (_: Throwable) {
                    delegate.position(startOffset + totalRead)
                    delegate.read(dst)
                }
                if (r <= 0) break
                totalRead += r
            }
            return if (totalRead > 0) totalRead else -1
        }
    }
}
