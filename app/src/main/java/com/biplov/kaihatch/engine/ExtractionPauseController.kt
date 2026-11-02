package com.biplov.kaihatch.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cooperative in-session Pause/Resume/Cancel controller owned by an active extraction session.
 *
 * Ensures that:
 * 1. Requesting pause transitions immediately to [PauseState.PAUSING] while the extraction engine
 *    completes any in-flight atomic write, tail-swap relocation (`pwrite`), physical truncation
 *    (`ftruncate`), or journal commit.
 * 2. Only when all active extraction workers reach a safe checkpoint does the controller transition
 *    to [PauseState.PAUSED] and invoke the acknowledgment callback.
 * 3. While [PauseState.PAUSED], coroutines suspend on a [StateFlow] gate with zero busy-waiting,
 *    zero byte consumption, zero file writes, and zero source mutations.
 * 4. Confirming Stop ([cancel]) while running, pausing, or paused immediately wakes any suspended
 *    coroutines and throws [CancellationException] without resuming extraction work.
 */
class ExtractionPauseController(
    val isPauseSupported: Boolean = true
) {
    enum class PauseState {
        RUNNING,
        PAUSING,
        PAUSED,
        CANCELLED
    }

    private val stateLock = Any()

    private val _state = MutableStateFlow(PauseState.RUNNING)
    val state: StateFlow<PauseState> = _state.asStateFlow()

    /**
     * Gate flow: `true` when coroutines are free to proceed (either [PauseState.RUNNING] or
     * [PauseState.CANCELLED] so parked coroutines wake up and immediately exit via cancellation);
     * `false` while [PauseState.PAUSING] or [PauseState.PAUSED].
     */
    private val releaseGate = MutableStateFlow(true)

    @Volatile
    private var fastPathRunning: Boolean = true

    @Volatile
    var onPauseRequested: (() -> Unit)? = null

    @Volatile
    var onPauseAcknowledged: (() -> Unit)? = null

    @Volatile
    var onResumed: (() -> Unit)? = null

    @Volatile
    var onBeforeCheckpoint: (() -> Unit)? = null

    private val activeWorkers = AtomicInteger(1)
    private val parkedWorkers = AtomicInteger(0)
    private val pauseAcknowledgeCounter = AtomicInteger(0)
    private val resumeNotificationPending = AtomicBoolean(false)

    private val accumulatedPausedMs = AtomicLong(0L)
    @Volatile
    private var currentPauseStartMs: Long = 0L

    val isPausingOrPaused: Boolean
        get() {
            val s = _state.value
            return s == PauseState.PAUSING || s == PauseState.PAUSED
        }

    val isPaused: Boolean
        get() = _state.value == PauseState.PAUSED

    val isPausing: Boolean
        get() = _state.value == PauseState.PAUSING

    val isCancelled: Boolean
        get() = _state.value == PauseState.CANCELLED

    val pauseAcknowledgeCount: Int
        get() = pauseAcknowledgeCounter.get()

    fun totalPausedDurationMs(nowMs: Long = System.currentTimeMillis()): Long {
        val base = accumulatedPausedMs.get()
        val start = currentPauseStartMs
        return if (_state.value == PauseState.PAUSED && start > 0L) {
            base + (nowMs - start).coerceAtLeast(0L)
        } else {
            base
        }
    }

    fun bindCallbacks(
        onPauseRequested: (() -> Unit)? = null,
        onPauseAcknowledged: (() -> Unit)? = null,
        onResumed: (() -> Unit)? = null
    ) {
        this.onPauseRequested = onPauseRequested
        this.onPauseAcknowledged = onPauseAcknowledged
        this.onResumed = onResumed
    }

    /**
     * Requests a cooperative pause. Returns `true` if the transition to [PauseState.PAUSING]
     * (or immediate [PauseState.PAUSED] if no workers are currently mid-operation) occurred.
     */
    fun requestPause(): Boolean {
        if (!isPauseSupported) return false
        var transitionedToPausing = false
        var transitionedImmediatelyToPaused = false
        synchronized(stateLock) {
            if (_state.value != PauseState.RUNNING) return false
            fastPathRunning = false
            releaseGate.value = false
            if (activeWorkers.get() <= 0) {
                _state.value = PauseState.PAUSED
                currentPauseStartMs = System.currentTimeMillis()
                pauseAcknowledgeCounter.incrementAndGet()
                resumeNotificationPending.set(true)
                transitionedImmediatelyToPaused = true
            } else {
                _state.value = PauseState.PAUSING
                transitionedToPausing = true
            }
        }
        if (transitionedToPausing || transitionedImmediatelyToPaused) {
            onPauseRequested?.invoke()
        }
        if (transitionedImmediatelyToPaused) {
            onPauseAcknowledged?.invoke()
        }
        return transitionedToPausing || transitionedImmediatelyToPaused
    }

    /**
     * Resumes extraction from [PauseState.PAUSED] or cancels a pending [PauseState.PAUSING] request.
     */
    fun resume(): Boolean {
        synchronized(stateLock) {
            val current = _state.value
            if (current != PauseState.PAUSED && current != PauseState.PAUSING) return false
            if (current == PauseState.PAUSED && currentPauseStartMs > 0L) {
                accumulatedPausedMs.addAndGet((System.currentTimeMillis() - currentPauseStartMs).coerceAtLeast(0L))
            }
            currentPauseStartMs = 0L
            _state.value = PauseState.RUNNING
            fastPathRunning = true
            releaseGate.value = true
            return true
        }
    }

    /**
     * Cancels extraction and releases any coroutines suspended in [checkpoint] so they immediately
     * throw [CancellationException] without resuming extraction work.
     */
    fun cancel() {
        synchronized(stateLock) {
            if (_state.value == PauseState.PAUSED && currentPauseStartMs > 0L) {
                accumulatedPausedMs.addAndGet((System.currentTimeMillis() - currentPauseStartMs).coerceAtLeast(0L))
            }
            currentPauseStartMs = 0L
            resumeNotificationPending.set(false)
            _state.value = PauseState.CANCELLED
            fastPathRunning = false
            releaseGate.value = true
        }
    }

    /**
     * Suspends until the controller acknowledges a safe [PauseState.PAUSED] checkpoint
     * (or is cancelled). Useful for deterministic tests and UI synchronization.
     */
    suspend fun awaitPaused(timeoutMs: Long = 0L): Boolean {
        val resultingState = if (timeoutMs > 0L) {
            withTimeoutOrNull(timeoutMs) {
                _state.first { it == PauseState.PAUSED || it == PauseState.CANCELLED }
            }
        } else {
            _state.first { it == PauseState.PAUSED || it == PauseState.CANCELLED }
        }
        return resultingState == PauseState.PAUSED
    }

    /**
     * Tracks multi-worker parallel windows so [PauseState.PAUSED] is only acknowledged
     * when every active worker in the window has reached a safe checkpoint or exited.
     */
    fun setActiveWorkerCount(count: Int) {
        synchronized(stateLock) {
            activeWorkers.set(count.coerceAtLeast(1))
        }
    }

    fun onWorkerExitedSafeZone() {
        var becamePaused = false
        synchronized(stateLock) {
            val remaining = activeWorkers.decrementAndGet().coerceAtLeast(0)
            if (_state.value == PauseState.PAUSING && remaining > 0 && parkedWorkers.get() >= remaining) {
                _state.value = PauseState.PAUSED
                currentPauseStartMs = System.currentTimeMillis()
                pauseAcknowledgeCounter.incrementAndGet()
                resumeNotificationPending.set(true)
                becamePaused = true
            }
        }
        if (becamePaused) {
            onPauseAcknowledged?.invoke()
        }
    }

    /**
     * Cooperative safe checkpoint called by the extraction engine strictly outside atomic
     * operations (never mid-chunk write before flush, never during `ftruncate`, never during
     * `pwrite` tail-swap relocation, never mid-journal update).
     */
    suspend fun checkpoint(flushBeforePause: (() -> Unit)? = null) {
        onBeforeCheckpoint?.invoke()
        if (fastPathRunning) {
            currentCoroutineContext().ensureActive()
            return
        }

        if (_state.value == PauseState.CANCELLED) {
            throw CancellationException("Extraction stopped by user")
        }
        currentCoroutineContext().ensureActive()

        // Execute caller's flush/sync before acknowledging pause so all prior writes are durable
        flushBeforePause?.invoke()

        var becamePaused = false
        synchronized(stateLock) {
            val current = _state.value
            if (current == PauseState.CANCELLED) {
                throw CancellationException("Extraction stopped by user")
            }
            if (current == PauseState.RUNNING) {
                return
            }
            val parked = parkedWorkers.incrementAndGet()
            val expected = activeWorkers.get().coerceAtLeast(1)
            if (_state.value == PauseState.PAUSING && parked >= expected) {
                _state.value = PauseState.PAUSED
                currentPauseStartMs = System.currentTimeMillis()
                pauseAcknowledgeCounter.incrementAndGet()
                resumeNotificationPending.set(true)
                becamePaused = true
            }
        }

        try {
            if (becamePaused) {
                onPauseAcknowledged?.invoke()
            }

            // Suspend without busy-waiting until resume() or cancel() opens the gate
            releaseGate.first { it }
        } finally {
            parkedWorkers.decrementAndGet()
        }

        if (_state.value == PauseState.CANCELLED) {
            throw CancellationException("Extraction stopped by user")
        }
        currentCoroutineContext().ensureActive()

        if (resumeNotificationPending.compareAndSet(true, false)) {
            onResumed?.invoke()
        }
    }
}
