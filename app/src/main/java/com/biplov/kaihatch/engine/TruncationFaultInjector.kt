package com.biplov.kaihatch.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Fault-injection and physical-operation observation harness for testing KaiHatch's
 * progressive in-place extraction (`Os.ftruncate` / `Os.pwrite` / journal checkpoints).
 *
 * Production builds use [NoOp], which adds zero behavioral change to extraction algorithms.
 */
enum class DestructivePoint {
    BEFORE_CD_TRUNCATE,
    AFTER_CD_TRUNCATE,
    BEFORE_STORED_SLICE_TRUNCATE,
    AFTER_STORED_SLICE_TRUNCATE,
    BEFORE_TAILSWAP_PWRITE,
    MID_TAILSWAP_PWRITE_TORN,
    BEFORE_TAILSWAP_PWRITE_SYNC,
    AFTER_TAILSWAP_PWRITE_BEFORE_TRUNCATE,
    AFTER_TAILSWAP_PREPARED_CKPT,
    AFTER_TAILSWAP_RELOCATED_CKPT_BEFORE_TRUNCATE,
    BEFORE_TAILSWAP_TRUNCATE_SYNC,
    AFTER_TAILSWAP_TRUNCATE,
    AFTER_TAILSWAP_COMMITTED_CKPT,
    BEFORE_WINDOW_TRUNCATE,
    AFTER_WINDOW_TRUNCATE_BEFORE_JOURNAL,
    AFTER_JOURNAL_SAVE,
    BEFORE_7Z_HEADER_TRUNCATE,
    BEFORE_7Z_SLOT_PWRITE,
    MID_7Z_SLOT_PWRITE_TORN,
    AFTER_7Z_SLOT_PWRITE_BEFORE_TRUNCATE,
    AFTER_7Z_SLOT_TRUNCATE,
    BEFORE_FINAL_ZERO_TRUNCATE,
    BEFORE_RESTORE_CD_WRITE,
    MID_RESTORE_CD_WRITE,
    AFTER_RESTORE_CD_WRITE_BEFORE_SYNC,
    AFTER_RESTORE_VERIFY_BEFORE_CLEANUP
}

/**
 * Unchecked fault exception thrown by a [TruncationFaultInjector] to simulate sudden
 * process termination (`SIGKILL` / I/O abort) at a precise destructive boundary.
 */
class SimulatedFaultException(
    val point: DestructivePoint,
    val occurrence: Int,
    val archiveBytesAtFault: Long,
    message: String = "Simulated fault at $point (occurrence #$occurrence, archiveBytes=$archiveBytesAtFault)"
) : RuntimeException(message)

interface TruncationFaultInjector {

    /**
     * Invoked at a specific [DestructivePoint]. Implementations may record telemetry or throw
     * [SimulatedFaultException] to abort execution immediately at that boundary.
     */
    fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String = "") {}

    /**
     * If this returns `true` during a `pwrite` slot relocation (`BEFORE_TAILSWAP_PWRITE` or
     * `BEFORE_7Z_SLOT_PWRITE`), only the first half of the buffer is written to the slot before
     * `onPoint(MID_*_PWRITE_TORN, ...)` is triggered, simulating a torn write mid-syscall.
     */
    fun shouldSimulateTornWrite(point: DestructivePoint): Boolean = false

    /**
     * Invoked whenever physical archive size is reduced via `ftruncate` / `FileChannel.truncate`.
     */
    fun onPhysicalTruncate(point: DestructivePoint, previousSize: Long, newSize: Long) {}

    /**
     * Invoked whenever a tail chunk is relocated into a head slot via `pwrite` / `FileChannel.write`.
     */
    fun onPhysicalSlotWrite(
        point: DestructivePoint,
        slotIndex: Int,
        tailIndex: Int,
        fileOffset: Long,
        bytesWritten: Int
    ) {}

    object NoOp : TruncationFaultInjector
}

/**
 * Configurable fault injector for deterministic JVM and Robolectric tests.
 * Records all destructive transitions, physical size reductions, and slot writes, and can trigger
 * a [SimulatedFaultException] (or torn write) on the Nth occurrence of a target [DestructivePoint].
 */
class RecordingFaultInjector(
    private val triggerPoint: DestructivePoint? = null,
    private val triggerOnOccurrence: Int = 1,
    private val simulateTornWrite: Boolean = false,
    private val onStepCallback: ((DestructivePoint, Long, String) -> Unit)? = null
) : TruncationFaultInjector {

    data class TruncateRecord(
        val point: DestructivePoint,
        val previousSize: Long,
        val newSize: Long
    )

    data class SlotWriteRecord(
        val point: DestructivePoint,
        val slotIndex: Int,
        val tailIndex: Int,
        val fileOffset: Long,
        val bytesWritten: Int
    )

    private val pointCounts = java.util.concurrent.ConcurrentHashMap<DestructivePoint, AtomicInteger>()
    val visitedPoints = java.util.concurrent.CopyOnWriteArrayList<Pair<DestructivePoint, Long>>()
    val truncateRecords = java.util.concurrent.CopyOnWriteArrayList<TruncateRecord>()
    val slotWriteRecords = java.util.concurrent.CopyOnWriteArrayList<SlotWriteRecord>()

    override fun onPoint(point: DestructivePoint, currentArchiveBytes: Long, detail: String) {
        val count = pointCounts.computeIfAbsent(point) { AtomicInteger(0) }.incrementAndGet()
        visitedPoints.add(point to currentArchiveBytes)
        onStepCallback?.invoke(point, currentArchiveBytes, detail)
        val isTriggeredTornMidPoint = simulateTornWrite &&
            triggerPoint == point &&
            (point == DestructivePoint.MID_TAILSWAP_PWRITE_TORN || point == DestructivePoint.MID_7Z_SLOT_PWRITE_TORN)
        if ((triggerPoint == point && count == triggerOnOccurrence) || isTriggeredTornMidPoint) {
            throw SimulatedFaultException(
                point = point,
                occurrence = if (isTriggeredTornMidPoint) triggerOnOccurrence else count,
                archiveBytesAtFault = currentArchiveBytes,
                message = "Simulated fault at $point (#${if (isTriggeredTornMidPoint) triggerOnOccurrence else count}, bytes=$currentArchiveBytes, detail=$detail)"
            )
        }
    }

    override fun shouldSimulateTornWrite(point: DestructivePoint): Boolean {
        if (!simulateTornWrite) return false
        val expectedMidPoint = when (point) {
            DestructivePoint.BEFORE_TAILSWAP_PWRITE -> DestructivePoint.MID_TAILSWAP_PWRITE_TORN
            DestructivePoint.BEFORE_7Z_SLOT_PWRITE -> DestructivePoint.MID_7Z_SLOT_PWRITE_TORN
            else -> return false
        }
        if (triggerPoint != expectedMidPoint) return false
        val pwriteCount = pointCounts[point]?.get() ?: 0
        return pwriteCount == triggerOnOccurrence
    }

    override fun onPhysicalTruncate(point: DestructivePoint, previousSize: Long, newSize: Long) {
        truncateRecords.add(TruncateRecord(point, previousSize, newSize))
    }

    override fun onPhysicalSlotWrite(
        point: DestructivePoint,
        slotIndex: Int,
        tailIndex: Int,
        fileOffset: Long,
        bytesWritten: Int
    ) {
        slotWriteRecords.add(SlotWriteRecord(point, slotIndex, tailIndex, fileOffset, bytesWritten))
    }

    fun getCount(point: DestructivePoint): Int = pointCounts[point]?.get() ?: 0
}
