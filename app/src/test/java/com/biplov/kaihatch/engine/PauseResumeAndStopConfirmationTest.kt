package com.biplov.kaihatch.engine

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.data.ArchiveJobRunner
import com.biplov.kaihatch.data.HatchNotificationCancelReceiver
import com.biplov.kaihatch.data.HatchNotificationHelper
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.ui.HatchViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PauseResumeAndStopConfirmationTest {

    private fun deterministicBytes(seed: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        val rng = Random(seed)
        rng.nextBytes(out)
        return out
    }

    private fun crc32Of(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun createStoredZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zos ->
            for ((name, bytes) in entries) {
                val ze = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = crc32Of(bytes)
                }
                zos.putNextEntry(ze)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun createDeflatedZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zos ->
            zos.setLevel(1)
            for ((name, bytes) in entries) {
                val ze = ZipEntry(name).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(ze)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun createSevenZ(file: File, entries: List<Pair<String, ByteArray>>) {
        SevenZOutputFile(file).use { szOut ->
            szOut.setContentMethods(listOf(org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(SevenZMethod.LZMA2)))
            for ((name, bytes) in entries) {
                val tmpPayload = File(file.parentFile, "tmp_7z_${System.nanoTime()}.bin")
                try {
                    tmpPayload.writeBytes(bytes)
                    val entry = szOut.createArchiveEntry(tmpPayload, name)
                    szOut.putArchiveEntry(entry as org.apache.commons.compress.archivers.ArchiveEntry)
                    szOut.write(bytes)
                    szOut.closeArchiveEntry()
                } finally {
                    tmpPayload.delete()
                }
            }
            szOut.finish()
        }
    }

    private fun createTar(file: File, entries: List<Pair<String, ByteArray>>, gzip: Boolean) {
        val rawOut = BufferedOutputStream(FileOutputStream(file))
        val out = if (gzip) GZIPOutputStream(rawOut) else rawOut
        out.use { stream ->
            for ((name, bytes) in entries) {
                val header = ByteArray(512)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

                fun writeOctal(value: Long, offset: Int, length: Int) {
                    val octal = value.toString(8).padStart(length - 1, '0')
                    val b = octal.toByteArray(Charsets.US_ASCII)
                    System.arraycopy(b, 0, header, offset, minOf(b.size, length - 1))
                    header[offset + length - 1] = 0
                }

                writeOctal(0b110100100L, 100, 8) // mode 0644
                writeOctal(0L, 108, 8) // uid
                writeOctal(0L, 116, 8) // gid
                writeOctal(bytes.size.toLong(), 124, 12) // size
                writeOctal(System.currentTimeMillis() / 1000L, 136, 12) // mtime
                // Fill checksum field with spaces (0x20) before computing
                for (i in 148 until 156) header[i] = 0x20.toByte()
                header[156] = '0'.code.toByte() // normal file flag

                var sum = 0L
                for (b in header) sum += (b.toInt() and 0xFF)
                val chkOctal = sum.toString(8).padStart(6, '0').toByteArray(Charsets.US_ASCII)
                System.arraycopy(chkOctal, 0, header, 148, minOf(chkOctal.size, 6))
                header[154] = 0
                header[155] = 0x20.toByte()

                stream.write(header)
                stream.write(bytes)
                val pad = ((512 - (bytes.size % 512)) % 512)
                if (pad > 0) {
                    stream.write(ByteArray(pad))
                }
            }
            // Two 512-byte EOF zero blocks
            stream.write(ByteArray(1024))
            stream.flush()
        }
    }

    @Test
    fun floatingControlsExpandAndCollapseWithoutChangingExtractionState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HatchViewModel(app)

        // Simulate active extraction state
        val activeProgress = ExtractionProgress(
            archiveName = "sample.zip",
            isHatchMode = true,
            status = ExtractionStatus.EXTRACTING,
            totalEntries = 10,
            completedEntries = 4,
            bytesExtracted = 4096L,
            totalUncompressedBytes = 10240L
        )
        ArchiveJobRunner.updateExtractionProgress(activeProgress)
        // Use reflection to set viewModel's active state directly for deterministic UI state testing
        val field = HatchViewModel::class.java.getDeclaredField("_extractionProgress").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ExtractionProgress>
        stateFlow.value = activeProgress

        assertFalse(viewModel.isFloatingControlsExpanded.value)
        assertEquals(ExtractionStatus.EXTRACTING, viewModel.extractionProgress.value.status)

        // 1. Tapping collapsed floating button expands controls without cancelling extraction
        viewModel.setFloatingControlsExpanded(true)
        assertTrue(viewModel.isFloatingControlsExpanded.value)
        assertEquals(ExtractionStatus.EXTRACTING, viewModel.extractionProgress.value.status)

        // 2. Tapping outside collapses controls without changing extraction state
        viewModel.setFloatingControlsExpanded(false)
        assertFalse(viewModel.isFloatingControlsExpanded.value)
        assertEquals(ExtractionStatus.EXTRACTING, viewModel.extractionProgress.value.status)

        // Also test when paused: collapsing and re-expanding preserves PAUSED state
        viewModel.setFloatingControlsExpanded(true)
        viewModel.pauseExtraction()
        assertEquals(ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)
        viewModel.setFloatingControlsExpanded(false)
        assertFalse(viewModel.isFloatingControlsExpanded.value)
        assertEquals(ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)
        viewModel.setFloatingControlsExpanded(true)
        assertTrue(viewModel.isFloatingControlsExpanded.value)
        assertEquals(ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)

        // 3. Rapid repeated expand/collapse toggles must not corrupt state or interrupt extraction
        repeat(20) {
            viewModel.toggleFloatingControlsExpanded()
        }
        assertTrue(viewModel.isFloatingControlsExpanded.value)
        assertEquals(ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)

        // 4. Verify state-aware collapsed floating button icon, "Actions" label, and accessibility description
        val extractingSpec = com.biplov.kaihatch.resolveCollapsedExtractionTileSpec(ExtractionStatus.EXTRACTING)
        assertEquals(androidx.compose.material.icons.Icons.Default.Pause, extractingSpec.icon)
        assertEquals("Actions", extractingSpec.label)
        assertEquals(
            "Extraction actions. Double tap to open Pause and Stop controls.",
            extractingSpec.contentDescription
        )

        // During PAUSING transition, keep showing Pause icon until engine acknowledges PAUSED
        val pausingSpec = com.biplov.kaihatch.resolveCollapsedExtractionTileSpec(ExtractionStatus.PAUSING)
        assertEquals(androidx.compose.material.icons.Icons.Default.Pause, pausingSpec.icon)
        assertEquals("Actions", pausingSpec.label)

        // Once PAUSED is reached, switch icon to Play while keeping "Actions" label
        val pausedSpec = com.biplov.kaihatch.resolveCollapsedExtractionTileSpec(ExtractionStatus.PAUSED)
        assertEquals(androidx.compose.material.icons.Icons.Default.PlayArrow, pausedSpec.icon)
        assertEquals("Actions", pausedSpec.label)
        assertEquals(
            "Extraction actions, paused. Double tap to open Resume and Stop controls.",
            pausedSpec.contentDescription
        )

        // 5. Respect Android reduced-motion / animation-disabled global setting
        android.provider.Settings.Global.putFloat(
            app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )
        assertFalse(com.biplov.kaihatch.areSystemAnimationsEnabled(app))
        android.provider.Settings.Global.putFloat(
            app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    @Test
    fun pauseTransitionsToPausedZeroMutationWhilePausedAndMultipleCyclesComplete() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "pause_resume_cycles_${System.nanoTime()}").apply { mkdirs() }

        try {
            // Test both Hatch Mode and Standard Mode with multiple entries + large sub-entry payload
            for (hatchMode in listOf(true, false)) {
                val entries = listOf(
                    "alpha.bin" to deterministicBytes(101L, 380 * 1024),
                    "beta.bin" to deterministicBytes(202L, 420 * 1024),
                    "gamma_large.bin" to deterministicBytes(303L, 4500 * 1024),
                    "delta.bin" to deterministicBytes(404L, 350 * 1024)
                )
                val zipFile = File(testDir, "cycle_hatch_${hatchMode}.zip")
                createDeflatedZip(zipFile, entries)
                val outDir = File(testDir, "out_hatch_${hatchMode}").apply { mkdirs() }

                val pauseController = ExtractionPauseController()
                val observedStatuses = CopyOnWriteArrayList<ExtractionStatus>()
                val checkpointCallCount = java.util.concurrent.atomic.AtomicInteger(0)
                val secondPauseTriggered = AtomicBoolean(false)
                val allowSecondPause = AtomicBoolean(false)
                val latestProgressSnapshot = java.util.concurrent.atomic.AtomicReference(ExtractionProgress())

                pauseController.onBeforeCheckpoint = {
                    val c = checkpointCallCount.incrementAndGet()
                    // Trigger first pause deterministically on checkpoint #2 (after first entry/chunk is written)
                    if (c == 2) {
                        pauseController.requestPause()
                    } else if (allowSecondPause.get() && secondPauseTriggered.compareAndSet(false, true)) {
                        pauseController.requestPause()
                    }
                }

                val job = launch(Dispatchers.Default) {
                    KaiHatchEngine.extractArchive(
                        archiveFile = zipFile,
                        targetDir = outDir,
                        isHatchMode = hatchMode,
                        pauseController = pauseController
                    ).collect { progress ->
                        latestProgressSnapshot.set(progress)
                        observedStatuses.add(progress.status)
                    }
                }

                // Wait for first pause to be acknowledged at a safe checkpoint
                assertTrue("Engine should reach PAUSED at safe checkpoint (hatch=$hatchMode)", pauseController.awaitPaused(5000L))
                delay(50)

                // Verify zero bytes extracted and zero source archive size change while PAUSED
                val bytesWhilePausedStart = latestProgressSnapshot.get().bytesExtracted
                val diskBytesWhilePausedStart = if (zipFile.exists()) zipFile.length() else 0L
                val reclaimedWhilePausedStart = latestProgressSnapshot.get().bytesReclaimed

                delay(150)

                assertEquals("Zero bytes should be extracted while PAUSED", bytesWhilePausedStart, latestProgressSnapshot.get().bytesExtracted)
                if (zipFile.exists()) {
                    assertEquals("Zero physical truncations should occur while PAUSED", diskBytesWhilePausedStart, zipFile.length())
                }
                assertEquals("Reclaimed bytes should stay frozen while PAUSED", reclaimedWhilePausedStart, latestProgressSnapshot.get().bytesReclaimed)

                // Enable second pause on next checkpoint and resume first pause
                allowSecondPause.set(true)
                assertTrue(pauseController.resume())

                // Perform a second Pause/Resume cycle
                if (pauseController.awaitPaused(3000L)) {
                    delay(40)
                    assertTrue(pauseController.resume())
                }

                job.join()

                assertTrue("Pause must have been acknowledged at least twice (got ${pauseController.pauseAcknowledgeCount})", pauseController.pauseAcknowledgeCount >= 2)
                assertTrue("Statuses must include PAUSING or PAUSED", observedStatuses.contains(ExtractionStatus.PAUSED) || observedStatuses.contains(ExtractionStatus.PAUSING))
                assertEquals("Final status must be COMPLETED", ExtractionStatus.COMPLETED, latestProgressSnapshot.get().status)

                // Verify every output file is byte-identical
                for ((name, expectedBytes) in entries) {
                    val outFile = File(outDir, name)
                    assertTrue("Output file $name must exist (hatch=$hatchMode)", outFile.exists())
                    assertArrayEquals("Output file $name must match original bytes (hatch=$hatchMode)", expectedBytes, outFile.readBytes())
                }
            }
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun pauseAndResumeAcrossAllFormatsAndSafZeroCopy() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "formats_pause_resume_${System.nanoTime()}").apply { mkdirs() }

        try {
            val entries = listOf(
                "file1.dat" to deterministicBytes(11L, 256 * 1024),
                "file2.dat" to deterministicBytes(22L, 384 * 1024),
                "file3.dat" to deterministicBytes(33L, 256 * 1024)
            )

            // 8a. ZIP/STORE (Hatch Mode)
            val zipStore = File(testDir, "test_store.zip")
            createStoredZip(zipStore, entries)
            val storeOut = File(testDir, "out_store").apply { mkdirs() }
            runPauseResumeVerification(
                extractFlow = { controller ->
                    KaiHatchEngine.extractArchive(
                        archiveFile = zipStore,
                        targetDir = storeOut,
                        isHatchMode = true,
                        pauseController = controller
                    )
                },
                expectedEntries = entries,
                outDir = storeOut
            )

            // 8b. 7z/LZMA2 (Hatch Mode with HatchSevenZTruncatingChannel relocation state preserved)
            val sevenZEntries = listOf(
                "seven_a.bin" to deterministicBytes(701L, 1200 * 1024),
                "seven_b.bin" to deterministicBytes(702L, 1500 * 1024),
                "seven_c.bin" to deterministicBytes(703L, 1200 * 1024)
            )
            val sevenZFile = File(testDir, "test_lzma2.7z")
            createSevenZ(sevenZFile, sevenZEntries)
            val sevenZOut = File(testDir, "out_7z").apply { mkdirs() }
            runPauseResumeVerification(
                extractFlow = { controller ->
                    KaiHatchEngine.extractArchive(
                        archiveFile = sevenZFile,
                        targetDir = sevenZOut,
                        isHatchMode = true,
                        pauseController = controller
                    )
                },
                expectedEntries = sevenZEntries,
                outDir = sevenZOut
            )

            // 8c. TAR (Hatch Mode)
            val tarFile = File(testDir, "test_archive.tar")
            createTar(tarFile, entries, gzip = false)
            val tarOut = File(testDir, "out_tar").apply { mkdirs() }
            runPauseResumeVerification(
                extractFlow = { controller ->
                    KaiHatchEngine.extractArchive(
                        archiveFile = tarFile,
                        targetDir = tarOut,
                        isHatchMode = true,
                        pauseController = controller
                    )
                },
                expectedEntries = entries,
                outDir = tarOut
            )

            // 8d. TAR.GZ (Standard Streaming Mode)
            val tarGzFile = File(testDir, "test_archive.tar.gz")
            createTar(tarGzFile, entries, gzip = true)
            val tarGzOut = File(testDir, "out_targz").apply { mkdirs() }
            runPauseResumeVerification(
                extractFlow = { controller ->
                    KaiHatchEngine.extractArchive(
                        archiveFile = tarGzFile,
                        targetDir = tarGzOut,
                        isHatchMode = false,
                        pauseController = controller
                    )
                },
                expectedEntries = entries,
                outDir = tarGzOut
            )

            // 9. SAF-backed Zero-Copy URI extraction (`extractSafUriZeroCopy`)
            val safZipFile = File(testDir, "saf_zero_copy.zip")
            createDeflatedZip(safZipFile, entries)
            val safUri = Uri.fromFile(safZipFile)
            val parsedEntries = ZipParser.parseArchive(safZipFile)
            val safOut = File(testDir, "out_saf").apply { mkdirs() }
            runPauseResumeVerification(
                extractFlow = { controller ->
                    KaiHatchEngine.extractSafUriZeroCopy(
                        context = app,
                        uri = safUri,
                        archiveName = safZipFile.name,
                        entries = parsedEntries,
                        targetDir = safOut,
                        isHatchMode = true,
                        pauseController = controller
                    )
                },
                expectedEntries = entries,
                outDir = safOut
            )
        } finally {
            testDir.deleteRecursively()
        }
    }

    private suspend fun runPauseResumeVerification(
        extractFlow: (ExtractionPauseController) -> kotlinx.coroutines.flow.Flow<ExtractionProgress>,
        expectedEntries: List<Pair<String, ByteArray>>,
        outDir: File
    ) = kotlinx.coroutines.coroutineScope {
        val controller = ExtractionPauseController()
        val checkpointCount = java.util.concurrent.atomic.AtomicInteger(0)
        val finalProgress = java.util.concurrent.atomic.AtomicReference(ExtractionProgress())

        controller.onBeforeCheckpoint = {
            if (checkpointCount.incrementAndGet() == 2) {
                controller.requestPause()
            }
        }

        val job = launch(Dispatchers.Default) {
            extractFlow(controller).collect { p ->
                finalProgress.set(p)
            }
        }

        assertTrue("Format extraction must pause at safe checkpoint", controller.awaitPaused(5000L))
        delay(40)
        assertTrue("Format extraction must resume cleanly", controller.resume())
        job.join()

        assertEquals(ExtractionStatus.COMPLETED, finalProgress.get().status)
        for ((name, bytes) in expectedEntries) {
            val f = File(outDir, name)
            assertTrue("Expected extracted file $name to exist", f.exists())
            assertArrayEquals("Extracted file $name content mismatch", bytes, f.readBytes())
        }
    }

    @Test
    fun stopConfirmationDialogKeepExtractingNotificationCancelAndAutoDismiss() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HatchViewModel(app)

        val field = HatchViewModel::class.java.getDeclaredField("_extractionProgress").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ExtractionProgress>

        val runningHatchProgress = ExtractionProgress(
            archiveName = "destructive.zip",
            isHatchMode = true,
            status = ExtractionStatus.EXTRACTING,
            totalEntries = 5,
            completedEntries = 2
        )
        stateFlow.value = runningHatchProgress
        ArchiveJobRunner.updateExtractionProgress(runningHatchProgress)

        // 10. Tapping STOP opens Stop confirmation dialog without immediately cancelling extraction
        viewModel.setFloatingControlsExpanded(true)
        viewModel.requestStopExtractionConfirmation()
        assertTrue(viewModel.showStopConfirmDialog.value)
        assertEquals(ExtractionStatus.EXTRACTING, viewModel.extractionProgress.value.status)

        // 11a. Selecting Keep Extracting while running leaves extraction running
        viewModel.dismissStopExtractionConfirmation()
        assertFalse(viewModel.showStopConfirmDialog.value)
        assertEquals(ExtractionStatus.EXTRACTING, viewModel.extractionProgress.value.status)

        // 11b. Pause, open Stop dialog, select Keep Extracting -> remains PAUSED
        viewModel.pauseExtraction()
        assertEquals(ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)
        viewModel.requestStopExtractionConfirmation()
        assertTrue(viewModel.showStopConfirmDialog.value)
        viewModel.dismissStopExtractionConfirmation()
        assertFalse(viewModel.showStopConfirmDialog.value)
        assertEquals(ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)

        // 17. Notification cancel action does NOT bypass safety confirmation for active extraction
        val receiver = HatchNotificationCancelReceiver()
        receiver.onReceive(app, Intent(HatchNotificationHelper.ACTION_CANCEL_TASK))
        assertTrue("Notification cancel must request Stop confirmation instead of cancelling directly", viewModel.showStopConfirmDialog.value)
        assertEquals("Extraction must remain PAUSED until user confirms Stop", ExtractionStatus.PAUSED, viewModel.extractionProgress.value.status)

        // 18. When extraction completes or fails while controls/dialog are open, both reset cleanly
        val handleMethod = HatchViewModel::class.java.getDeclaredMethod(
            "handleExtractionProgressUpdate",
            ExtractionProgress::class.java,
            String::class.java,
            File::class.java,
            Uri::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java
        ).apply { isAccessible = true }

        runBlocking {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                handleMethod.invoke(
                    viewModel,
                    runningHatchProgress.copy(status = ExtractionStatus.COMPLETED),
                    "destructive.zip",
                    app.cacheDir,
                    null,
                    1024L,
                    true,
                    System.currentTimeMillis(),
                    cont
                )
            }
        }

        assertFalse("Floating controls must collapse on completion", viewModel.isFloatingControlsExpanded.value)
        assertFalse("Stop confirmation dialog must dismiss on completion", viewModel.showStopConfirmDialog.value)
    }

    @Test
    fun confirmedStopWakesPausedCoroutinePreservesCompletedFilesAndSourceArchive() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val testDir = File(app.cacheDir, "stop_integrity_${System.nanoTime()}").apply { mkdirs() }

        try {
            // Create a Hatch Mode archive with a smaller tail entry (extracted first in reverse order)
            // and a multi-megabyte head entry (interrupted mid-extraction).
            val headLargeBytes = deterministicBytes(901L, 6 * 1024 * 1024)
            val tailCompletedBytes = deterministicBytes(902L, 256 * 1024)
            val zipFile = File(testDir, "hatch_stop_test.zip")
            createStoredZip(
                zipFile,
                listOf(
                    "01_head_large_incomplete.bin" to headLargeBytes,
                    "02_tail_completed.bin" to tailCompletedBytes
                )
            )
            val outDir = File(testDir, "out_stop_test").apply { mkdirs() }
            val pauseController = ExtractionPauseController()
            val pausedInLargeEntry = AtomicBoolean(false)
            val progressesAfterPause = CopyOnWriteArrayList<ExtractionProgress>()

            val extractJob = launch(Dispatchers.Default) {
                try {
                    KaiHatchEngine.extractArchive(
                        archiveFile = zipFile,
                        targetDir = outDir,
                        isHatchMode = true,
                        context = app,
                        pauseController = pauseController
                    ).collect { progress ->
                        if (pauseController.isPaused) {
                            progressesAfterPause.add(progress)
                        }
                        // Wait until the tail entry ("02_tail_completed.bin") is completed and the engine is mid-way through "01_head_large_incomplete.bin"
                        if (progress.completedEntries >= 1 &&
                            progress.currentFileName.contains("01_head_large_incomplete.bin") &&
                            progress.currentFileBytesProcessed > 0L &&
                            pausedInLargeEntry.compareAndSet(false, true)
                        ) {
                            pauseController.requestPause()
                        }
                    }
                } catch (_: CancellationException) {
                    // Expected on confirmed Stop
                }
            }

            assertTrue("Engine must pause mid-entry at safe checkpoint", pauseController.awaitPaused(5000L))
            delay(50)
            val archiveBytesAtPause = zipFile.length()
            progressesAfterPause.clear()

            // 13. Confirmed Stop while PAUSED wakes the paused coroutine and cancels without doing further extraction work
            pauseController.cancel()
            extractJob.cancel()
            extractJob.join()

            assertTrue("No additional progress emissions should occur after cancel while paused", progressesAfterPause.isEmpty())

            // 14. Completed output file ("02_tail_completed.bin") remains intact and byte-identical
            val completedFile = File(outDir, "02_tail_completed.bin")
            assertTrue("Completed output file must be preserved on Stop", completedFile.exists())
            assertArrayEquals("Completed output file must be valid", tailCompletedBytes, completedFile.readBytes())

            // 15. Incomplete output file ("01_head_large_incomplete.bin") must be quarantined/deleted
            val incompleteFile = File(outDir, "01_head_large_incomplete.bin")
            assertFalse("Incomplete current output file must be deleted on confirmed Stop", incompleteFile.exists())

            // 16. In Hatch Mode, the remaining source archive and recovery journal are NOT deleted by Stop
            assertTrue("Remaining source archive must NOT be deleted on Stop", zipFile.exists())
            assertEquals("Source archive must not be truncated further during Stop from paused state", archiveBytesAtPause, zipFile.length())
            val pendingStates = ResumeJournalManager.loadPendingStates(app)
            assertTrue(
                "Crash-safe recovery journal for Hatch Mode extraction must be preserved on Stop",
                pendingStates.any { it.archivePath == zipFile.absolutePath }
            )
            // Clean up journal state created in test
            pendingStates.filter { it.archivePath == zipFile.absolutePath }.forEach {
                ResumeJournalManager.deleteState(it.stateFilePath)
            }
        } finally {
            testDir.deleteRecursively()
        }
    }
}
