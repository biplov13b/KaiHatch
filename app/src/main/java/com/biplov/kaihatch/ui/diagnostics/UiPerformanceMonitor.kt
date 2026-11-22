package com.biplov.kaihatch.ui.diagnostics

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.biplov.kaihatch.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only UI frame pacing, jank, and thermal-throttling diagnostics monitor.
 *
 * All public entry points check [BuildConfig.DEBUG] first and compile down to
 * zero-overhead no-ops in release builds. FrameMetrics collection runs on a
 * dedicated background [HandlerThread] so metric aggregation never blocks or
 * allocates on the main UI thread.
 */
object UiPerformanceMonitor {
    private const val TAG = "KaiHatchPerf"

    // Standard frame budgets in nanoseconds
    private const val FRAME_BUDGET_120HZ_NS = 8_333_333L   // ~8.33 ms
    private const val FRAME_BUDGET_60HZ_NS = 16_666_667L   // ~16.67 ms
    private const val SEVERE_JANK_NS = 33_333_334L         // ~33.33 ms (2+ dropped 60Hz frames)
    private const val FROZEN_FRAME_NS = 700_000_000L       // 700 ms
    private const val SUMMARY_INTERVAL_FRAMES = 180        // Log periodic summary during active interactions

    private val activeInteractions = ConcurrentHashMap<String, Long>()
    private val simulateThermalThrottlingBudget = AtomicBoolean(false)

    private val totalFrames = AtomicLong(0L)
    private val slowFrames60Hz = AtomicLong(0L)
    private val slowFrames120Hz = AtomicLong(0L)
    private val severeJankFrames = AtomicLong(0L)
    private val frozenFrames = AtomicLong(0L)
    private val windowFramesCounter = AtomicInteger(0)
    private val windowMaxFrameNs = AtomicLong(0L)

    @Volatile
    private var metricsThread: HandlerThread? = null

    @Volatile
    private var metricsHandler: Handler? = null

    @Volatile
    private var lastThermalStatus: Int = 0

    /**
     * Enables or disables strict thermal-headroom frame budget diagnostics in debug builds.
     * When enabled, frames exceeding the 8.33ms (120Hz / thermally constrained) budget during
     * active scrolling, navigation, folder expansion, or extraction are flagged early.
     */
    fun setStrictThermalBudgetCheckEnabled(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        simulateThermalThrottlingBudget.set(enabled)
    }

    /**
     * Marks an interaction context (e.g. `scroll:extract`, `transition:settings`,
     * `expand:folder`, `extract:active`, `nav:tab`) as active or inactive for
     * attributing frame timings.
     */
    fun setInteractionActive(key: String, active: Boolean) {
        if (!BuildConfig.DEBUG) return
        if (active) {
            activeInteractions[key] = System.nanoTime()
        } else {
            activeInteractions.remove(key)
        }
    }

    /**
     * Records a discrete UI transition or expansion event for debug trace correlation.
     */
    fun markEvent(eventName: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        if (detail.isEmpty()) {
            Log.d(TAG, "Event: $eventName | active=${activeInteractions.keys}")
        } else {
            Log.d(TAG, "Event: $eventName ($detail) | active=${activeInteractions.keys}")
        }
    }

    /**
     * Executes [block] inside an [android.os.Trace] section in debug builds only.
     */
    inline fun <T> traceSection(sectionName: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        Trace.beginSection(sectionName.take(127))
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    internal fun attachToActivity(activity: Activity): () -> Unit {
        if (!BuildConfig.DEBUG) return {}

        val window: Window = activity.window ?: return {}
        val thread = synchronized(this) {
            metricsThread?.takeIf { it.isAlive } ?: HandlerThread(
                "KaiHatch-FrameMetrics",
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            ).apply {
                start()
                metricsThread = this
                metricsHandler = Handler(looper)
            }
        }
        val handler = metricsHandler ?: Handler(thread.looper)

        val powerManager = activity.getSystemService(PowerManager::class.java)
        val thermalListener: PowerManager.OnThermalStatusChangedListener? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                PowerManager.OnThermalStatusChangedListener { status ->
                    lastThermalStatus = status
                    val statusName = thermalStatusToString(status)
                    Log.i(TAG, "Device thermal status changed: $statusName ($status)")
                }.also { listener ->
                    runCatching { powerManager.addThermalStatusListener(activity.mainExecutor, listener) }
                }
            } else {
                null
            }

        val frameListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, dropCountSinceLastInvocation ->
            // Ignore first-draw frames where hardware pipeline initialization dominates
            if (frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener

            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val uiNs = frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
            val drawNs = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
            val syncNs = frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
            val commandIssueNs = frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)

            val allFrames = totalFrames.incrementAndGet()
            if (totalNs > FRAME_BUDGET_120HZ_NS) {
                slowFrames120Hz.incrementAndGet()
            }
            if (totalNs > FRAME_BUDGET_60HZ_NS) {
                slowFrames60Hz.incrementAndGet()
            }
            if (totalNs > SEVERE_JANK_NS) {
                severeJankFrames.incrementAndGet()
            }
            if (totalNs > FROZEN_FRAME_NS) {
                frozenFrames.incrementAndGet()
            }

            // Track maximum frame duration in the current sampling window
            var prevMax = windowMaxFrameNs.get()
            while (totalNs > prevMax && !windowMaxFrameNs.compareAndSet(prevMax, totalNs)) {
                prevMax = windowMaxFrameNs.get()
            }

            val isThermallyThrottled = lastThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                simulateThermalThrottlingBudget.get()
            val effectiveWarnThresholdNs = if (isThermallyThrottled) {
                FRAME_BUDGET_60HZ_NS
            } else {
                SEVERE_JANK_NS
            }

            if (totalNs >= effectiveWarnThresholdNs) {
                val activeTags = if (activeInteractions.isEmpty()) "idle" else activeInteractions.keys.joinToString(",")
                val totalMs = totalNs / 1_000_000.0
                val uiMs = uiNs / 1_000_000.0
                val drawMs = drawNs / 1_000_000.0
                val syncMs = (syncNs + commandIssueNs) / 1_000_000.0
                Log.w(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "Jank frame: total=%.2fms (ui=%.2fms, draw=%.2fms, gpuSync=%.2fms, dropped=%d) | thermal=%s | context=[%s]",
                        totalMs,
                        uiMs,
                        drawMs,
                        syncMs,
                        dropCountSinceLastInvocation,
                        thermalStatusToString(lastThermalStatus),
                        activeTags
                    )
                )
            }

            val windowCount = windowFramesCounter.incrementAndGet()
            if (windowCount >= SUMMARY_INTERVAL_FRAMES && activeInteractions.isNotEmpty()) {
                windowFramesCounter.set(0)
                val maxWindowMs = windowMaxFrameNs.getAndSet(0L) / 1_000_000.0
                val slow60 = slowFrames60Hz.get()
                val jankRatePct = if (allFrames > 0L) (slow60 * 100.0) / allFrames.toDouble() else 0.0
                Log.d(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "Frame summary: frames=%d, jank60Hz=%d (%.1f%%), maxRecent=%.2fms, thermal=%s, active=%s",
                        allFrames,
                        slow60,
                        jankRatePct,
                        maxWindowMs,
                        thermalStatusToString(lastThermalStatus),
                        activeInteractions.keys
                    )
                )
            }
        }

        runCatching {
            window.addOnFrameMetricsAvailableListener(frameListener, handler)
        }

        return {
            runCatching {
                window.removeOnFrameMetricsAvailableListener(frameListener)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
                runCatching {
                    powerManager.removeThermalStatusListener(thermalListener)
                }
            }
        }
    }

    private fun thermalStatusToString(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}

/**
 * Attaches the debug-only [UiPerformanceMonitor] FrameMetrics listener to the hosting Activity
 * while in composition. Compiles to an empty function in release builds.
 */
@Composable
fun RememberDebugFrameMonitor() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? Activity
        val detach = activity?.let { UiPerformanceMonitor.attachToActivity(it) }
        onDispose {
            detach?.invoke()
        }
    }
}
