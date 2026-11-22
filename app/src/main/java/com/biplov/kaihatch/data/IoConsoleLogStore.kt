package com.biplov.kaihatch.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Bounded, persistent, deduplicating store for KaiHatch's I/O Console session logs.
 *
 * Guarantees:
 * - Never automatically clears logs across archive refreshes, extraction lifecycle states
 *   (start, Pause/Resume, Stop, recovery, success, failure), or app restarts / force-stops.
 * - Clears persisted and in-memory history ONLY via explicit [clear] calls.
 * - Enforces bounded line count ([maxLines]) and UTF-8 byte cap ([maxBytes]) with FIFO eviction.
 * - Deduplicates repeated cumulative/sliding snapshots emitted by extraction engine runs.
 * - Persists atomically (temp file + flush + fsync + rename) off the main thread.
 */
class IoConsoleLogStore(
    private val logFile: File,
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val debounceFlushMs: Long = DEFAULT_DEBOUNCE_FLUSH_MS
) {
    constructor(
        context: Context,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        debounceFlushMs: Long = DEFAULT_DEBOUNCE_FLUSH_MS
    ) : this(
        logFile = File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME),
        maxLines = maxLines,
        maxBytes = maxBytes,
        debounceFlushMs = debounceFlushMs
    )

    companion object {
        const val LOG_DIR_NAME = "io_console_logs"
        const val LOG_FILE_NAME = "session_console.log"
        const val DEFAULT_MAX_LINES = 500
        const val DEFAULT_MAX_BYTES = 128 * 1024
        const val DEFAULT_DEBOUNCE_FLUSH_MS = 120L
        private const val MAX_SINGLE_LINE_CHARS = 1024
    }

    private val lock = Any()
    private val diskLock = Any()
    private val lines = ArrayDeque<String>()
    private var totalUtf8Bytes: Int = 0
    private var loadedFromDisk: Boolean = false
    private var explicitlyCleared: Boolean = false

    // Per-run engine snapshot tracker to extract only truly new lines from sliding engine logs.
    private var lastRunId: Long = Long.MIN_VALUE
    private var lastRunSnapshot: List<String> = emptyList()

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var pendingFlushJob: Job? = null

    /**
     * Loads persisted logs from disk (must be invoked off the main thread) and merges them
     * ahead of any early in-memory lines appended before disk loading completed.
     */
    fun loadPersistedSync(): List<String> {
        val persisted = readLinesFromDisk()
        val snapshotToWrite: List<String>?
        val result: List<String>
        synchronized(lock) {
            if (loadedFromDisk || explicitlyCleared) {
                return lines.toList()
            }
            loadedFromDisk = true
            if (persisted.isNotEmpty()) {
                val earlyLines = lines.toList()
                lines.clear()
                totalUtf8Bytes = 0
                for (line in persisted) {
                    appendSanitizedLocked(line, skipConsecutiveDuplicate = false)
                }
                for (line in earlyLines) {
                    appendSanitizedLocked(line, skipConsecutiveDuplicate = true)
                }
                snapshotToWrite = if (earlyLines.isNotEmpty()) lines.toList() else null
            } else {
                snapshotToWrite = null
            }
            result = lines.toList()
        }
        if (snapshotToWrite != null) {
            writeLinesToDiskSync(snapshotToWrite)
        }
        return result
    }

    /**
     * Returns an immutable snapshot of the current logs.
     */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /**
     * Appends direct event/diagnostic log lines.
     * Returns a new immutable snapshot ONLY if at least one line was actually added;
     * otherwise returns `null` so callers can skip redundant UI state updates/recompositions.
     */
    fun appendLines(
        newLines: List<String>,
        flushImmediately: Boolean = false
    ): List<String>? {
        if (newLines.isEmpty()) return null
        val updatedSnapshot: List<String>? = synchronized(lock) {
            explicitlyCleared = false
            var changed = false
            for (raw in newLines) {
                if (appendSanitizedLocked(raw, skipConsecutiveDuplicate = true)) {
                    changed = true
                }
            }
            if (changed) lines.toList() else null
        }
        if (updatedSnapshot != null) {
            schedulePersist(updatedSnapshot, flushImmediately)
        }
        return updatedSnapshot
    }

    /**
     * Appends only the newly added tail lines from a cumulative or sliding-window engine
     * [snapshot] for a specific [runId].
     *
     * Returns a new immutable snapshot ONLY when new lines were discovered; otherwise `null`.
     */
    fun appendEngineSnapshot(
        runId: Long,
        snapshot: List<String>,
        flushImmediately: Boolean = false
    ): List<String>? {
        if (snapshot.isEmpty()) {
            if (flushImmediately) {
                val current = synchronized(lock) { lines.toList() }
                if (current.isNotEmpty()) {
                    schedulePersist(current, flushImmediately = true)
                }
            }
            return null
        }
        val sanitizedIncoming = snapshot.mapNotNull { sanitizeLine(it) }
        if (sanitizedIncoming.isEmpty()) return null

        val updatedSnapshot: List<String>? = synchronized(lock) {
            explicitlyCleared = false
            val delta: List<String> = if (runId == lastRunId && lastRunSnapshot.isNotEmpty()) {
                if (sanitizedIncoming == lastRunSnapshot) {
                    emptyList()
                } else {
                    val overlap = findMaxSuffixPrefixOverlap(lastRunSnapshot, sanitizedIncoming)
                    if (overlap >= sanitizedIncoming.size) {
                        emptyList()
                    } else {
                        sanitizedIncoming.subList(overlap, sanitizedIncoming.size)
                    }
                }
            } else {
                sanitizedIncoming
            }

            lastRunId = runId
            lastRunSnapshot = sanitizedIncoming

            if (delta.isEmpty()) {
                null
            } else {
                var changed = false
                for (line in delta) {
                    if (appendSanitizedLocked(line, skipConsecutiveDuplicate = false)) {
                        changed = true
                    }
                }
                if (changed) lines.toList() else null
            }
        }

        if (updatedSnapshot != null) {
            schedulePersist(updatedSnapshot, flushImmediately)
        } else if (flushImmediately) {
            val current = synchronized(lock) { lines.toList() }
            if (current.isNotEmpty()) {
                schedulePersist(current, flushImmediately = true)
            }
        }
        return updatedSnapshot
    }

    /**
     * Explicitly clears all in-memory and persisted session logs.
     * Must ONLY be called in response to an explicit user action.
     */
    fun clear(): List<String> {
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        synchronized(lock) {
            lines.clear()
            totalUtf8Bytes = 0
            lastRunId = Long.MIN_VALUE
            lastRunSnapshot = emptyList()
            loadedFromDisk = true
            explicitlyCleared = true
        }
        persistScope.launch {
            deletePersistedFileSync()
        }
        return emptyList()
    }

    /**
     * Synchronous flush helper (used for deterministic testing or explicit durability barriers).
     */
    fun flushNowSync() {
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        val snap = synchronized(lock) {
            if (explicitlyCleared && lines.isEmpty()) null else lines.toList()
        }
        if (snap == null) {
            deletePersistedFileSync()
        } else {
            writeLinesToDiskSync(snap)
        }
    }

    private fun schedulePersist(snapshot: List<String>, flushImmediately: Boolean) {
        if (flushImmediately || debounceFlushMs <= 0L) {
            pendingFlushJob?.cancel()
            pendingFlushJob = persistScope.launch {
                val latest = synchronized(lock) {
                    if (explicitlyCleared && lines.isEmpty()) null else lines.toList()
                }
                if (latest != null) {
                    writeLinesToDiskSync(latest)
                }
            }
            return
        }
        pendingFlushJob?.cancel()
        pendingFlushJob = persistScope.launch {
            delay(debounceFlushMs)
            val latest = synchronized(lock) {
                if (explicitlyCleared && lines.isEmpty()) null else lines.toList()
            }
            if (latest != null) {
                writeLinesToDiskSync(latest)
            }
        }
    }

    private fun appendSanitizedLocked(rawLine: String, skipConsecutiveDuplicate: Boolean): Boolean {
        val clean = sanitizeLine(rawLine) ?: return false
        if (skipConsecutiveDuplicate && lines.lastOrNull() == clean) {
            return false
        }
        val lineBytes = utf8LineSize(clean)
        lines.addLast(clean)
        totalUtf8Bytes += lineBytes
        trimToBoundsLocked()
        return true
    }

    private fun trimToBoundsLocked() {
        while (lines.size > maxLines || (totalUtf8Bytes > maxBytes && lines.size > 1)) {
            val removed = lines.removeFirst()
            totalUtf8Bytes = (totalUtf8Bytes - utf8LineSize(removed)).coerceAtLeast(0)
        }
    }

    private fun sanitizeLine(raw: String): String? {
        val cleaned = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_SINGLE_LINE_CHARS) {
            cleaned.substring(0, MAX_SINGLE_LINE_CHARS - 1) + "…"
        } else {
            cleaned
        }
    }

    private fun utf8LineSize(line: String): Int {
        return line.toByteArray(StandardCharsets.UTF_8).size + 1
    }

    private fun findMaxSuffixPrefixOverlap(previous: List<String>, current: List<String>): Int {
        val maxPossible = minOf(previous.size, current.size)
        for (overlap in maxPossible downTo 1) {
            var matched = true
            val prevStart = previous.size - overlap
            for (i in 0 until overlap) {
                if (previous[prevStart + i] != current[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return overlap
        }
        return 0
    }

    private fun readLinesFromDisk(): List<String> = synchronized(diskLock) {
        if (!logFile.exists() || !logFile.isFile) return emptyList()
        return try {
            logFile.readLines(StandardCharsets.UTF_8).mapNotNull { sanitizeLine(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeLinesToDiskSync(snapshot: List<String>) = synchronized(diskLock) {
        try {
            val parent = logFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tmpFile = File(parent ?: logFile.absoluteFile.parentFile, "${logFile.name}.tmp")
            val content = if (snapshot.isEmpty()) {
                ""
            } else {
                snapshot.joinToString(separator = "\n", postfix = "\n")
            }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            FileOutputStream(tmpFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }
            if (!tmpFile.renameTo(logFile)) {
                FileOutputStream(logFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
                tmpFile.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private fun deletePersistedFileSync() = synchronized(diskLock) {
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
            val tmpFile = File(logFile.parentFile, "${logFile.name}.tmp")
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
