package com.biplov.kaihatch.engine

import android.net.Uri
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.PlanModeVerdict
import com.biplov.kaihatch.model.StoragePlannerRecommendation
import com.biplov.kaihatch.model.ZipEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase1StoragePlannerTest {

    private val gb = 1024L * 1024L * 1024L
    private val mb = 1024L * 1024L

    @Test
    fun `STORED ZIP on Direct POSIX vs SAF estimates distinct peak additional storage`() {
        // 4 GB single STORED entry inside a 4 GB archive
        val entrySize = 4L * gb
        val entry = ZipEntryInfo(
            path = "disk_image.img",
            name = "disk_image.img",
            isDirectory = false,
            compressedSize = entrySize,
            uncompressedSize = entrySize,
            crc32 = 0x12345678L,
            compressionMethod = 0, // STORED
            localHeaderOffset = 0L,
            isEncrypted = false
        )

        val posixPlan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = entrySize,
            entries = listOf(entry),
            format = ArchiveFormat.ZIP,
            destinationFreeBytes = 2L * gb,
            isDestinationCapacityReliable = true,
            isDirectPosixDestination = true
        )

        // Direct POSIX STORED uses 8 MB reverse sub-entry slice truncation + journal overhead (~9 MB additional)
        assertTrue(
            "Direct POSIX STORED Hatch peak additional (${posixPlan.hatchPeakAdditionalBytes}) should be <= 16 MB",
            posixPlan.hatchPeakAdditionalBytes <= 16L * mb
        )
        // Standard Mode needs the full 4 GB extracted copy + 8 MB stream working space alongside the 4 GB archive
        assertEquals(entrySize + posixPlan.standardTempWorkingBytes, posixPlan.standardPeakAdditionalBytes)
        assertEquals(entrySize + posixPlan.standardPeakAdditionalBytes, posixPlan.standardTotalPeakBytes)
        // With 2 GB free, Standard (needs ~4.07 GB) fails while Hatch (needs ~73 MB with reserve) fits safely!
        assertEquals(PlanModeVerdict.INSUFFICIENT_SPACE, posixPlan.standardVerdict)
        assertEquals(PlanModeVerdict.FITS_SAFELY, posixPlan.hatchVerdict)
        assertEquals(StoragePlannerRecommendation.HATCH_MODE_RECOMMENDED, posixPlan.recommendation)
        assertTrue(posixPlan.canGuaranteeSufficientSpace)

        // On non-POSIX SAF destination, intermediate sub-entry slice truncation is disabled for safety;
        // the full entry is written before the archive is truncated.
        val safPlan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = entrySize,
            entries = listOf(entry),
            format = ArchiveFormat.ZIP,
            destinationFreeBytes = 2L * gb,
            isDestinationCapacityReliable = true,
            isDirectPosixDestination = false
        )
        assertTrue(
            "SAF STORED single-entry Hatch peak additional (${safPlan.hatchPeakAdditionalBytes}) must account for full entry before truncation",
            safPlan.hatchPeakAdditionalBytes >= entrySize
        )
        assertEquals(PlanModeVerdict.INSUFFICIENT_SPACE, safPlan.hatchVerdict)
        assertEquals(StoragePlannerRecommendation.INSUFFICIENT_FOR_BOTH, safPlan.recommendation)
    }

    @Test
    fun `DEFLATED multi-entry ZIP accounts for decompress-first tail-swap working space`() {
        // 4 entries of 500 MB compressed -> 1 GB uncompressed each
        // Archive total = 2 GB, Uncompressed total = 4 GB
        val compEach = 500L * mb
        val uncompEach = 1024L * mb
        val entries = (0 until 4).map { idx ->
            ZipEntryInfo(
                path = "part_$idx.bin",
                name = "part_$idx.bin",
                isDirectory = false,
                compressedSize = compEach,
                uncompressedSize = uncompEach,
                crc32 = 0x11111111L + idx,
                compressionMethod = 8, // DEFLATE
                localHeaderOffset = idx * compEach,
                isEncrypted = false
            )
        }
        val archiveSize = 4L * compEach // 2 GB
        val totalUncomp = 4L * uncompEach // 4 GB

        val plan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = archiveSize,
            entries = entries,
            format = ArchiveFormat.ZIP,
            destinationFreeBytes = 3200L * mb, // 3.125 GB free
            isDestinationCapacityReliable = true,
            isDirectPosixDestination = true
        )

        // Standard Mode needs 4 GB + standardTempWorkingBytes additional space
        assertEquals(totalUncomp + plan.standardTempWorkingBytes, plan.standardPeakAdditionalBytes)
        assertEquals(archiveSize + plan.standardPeakAdditionalBytes, plan.standardTotalPeakBytes)

        // Hatch Mode progressive reverse extraction:
        // Last entry (index 0 at offset 0) reaches 4 GB extracted while 500 MB archive tail + journal remains -> ~4.5 GB total footprint -> ~2.5 GB additional
        assertTrue(plan.hatchPeakAdditionalBytes < plan.standardPeakAdditionalBytes)
        assertTrue(plan.spaceSavedWithHatchBytes >= 1400L * mb)
        assertTrue(plan.hatchTempWorkingBytes >= 32L * mb) // 32 MB chunk0 + journal overhead

        // With 3,200 MB free: Standard (~4,072 MB with reserve) is INSUFFICIENT_SPACE, Hatch (~2,565 MB with reserve) FITS_SAFELY
        assertEquals(PlanModeVerdict.INSUFFICIENT_SPACE, plan.standardVerdict)
        assertEquals(PlanModeVerdict.FITS_SAFELY, plan.hatchVerdict)
        assertEquals(StoragePlannerRecommendation.HATCH_MODE_RECOMMENDED, plan.recommendation)
    }

    @Test
    fun `ZIP64 64GB archive handles 64-bit offsets and 25000 entries lightweight without overflow`() {
        val entryCount = 25_000
        val compPerEntry = 2L * mb // ~50 GB compressed
        val uncompPerEntry = 3L * mb // ~75 GB uncompressed
        val entries = ArrayList<ZipEntryInfo>(entryCount)
        for (i in 0 until entryCount) {
            entries.add(
                ZipEntryInfo(
                    path = "dataset/chunk_$i.dat",
                    name = "chunk_$i.dat",
                    isDirectory = false,
                    compressedSize = compPerEntry,
                    uncompressedSize = uncompPerEntry,
                    crc32 = i.toLong(),
                    compressionMethod = 8,
                    localHeaderOffset = i.toLong() * compPerEntry,
                    isEncrypted = false,
                    isZip64 = true
                )
            )
        }
        val archiveSize = entryCount.toLong() * compPerEntry
        val extractedSize = entryCount.toLong() * uncompPerEntry

        val startNs = System.nanoTime()
        val plan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = archiveSize,
            entries = entries,
            format = ArchiveFormat.ZIP,
            destinationFreeBytes = 100L * gb,
            isDestinationCapacityReliable = true,
            isDirectPosixDestination = true
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertEquals(archiveSize, plan.archiveSizeBytes)
        assertEquals(extractedSize, plan.extractedSizeBytes)
        assertEquals(64L * mb, plan.safetyReserveBytes)
        assertEquals(PlanModeVerdict.FITS_SAFELY, plan.standardVerdict)
        assertEquals(PlanModeVerdict.FITS_SAFELY, plan.hatchVerdict)
        assertEquals(StoragePlannerRecommendation.BOTH_MODES_FIT, plan.recommendation)
        assertTrue(
            "25,000-entry ZIP64 storage plan should complete in < 250ms (took ${elapsedMs}ms)",
            elapsedMs < 250L
        )
    }

    @Test
    fun `unknown SAF capacity never guarantees sufficient space`() {
        val opaqueSafUri = Uri.parse("content://com.thirdparty.cloudprovider.documents/tree/vault%3Aarchive_target")
        val capacityInfo = StorageHelper.queryDestinationCapacity(
            context = null,
            targetDir = null,
            targetTreeUri = opaqueSafUri
        )

        assertFalse("Opaque SAF provider without statvfs must not report reliable capacity", capacityInfo.isReliable)
        assertNull("Opaque SAF provider capacity must be null rather than fabricated", capacityInfo.availableBytes)
        assertFalse(capacityInfo.isDirectPosix)

        val entry = ZipEntryInfo(
            path = "video.mp4",
            name = "video.mp4",
            isDirectory = false,
            compressedSize = 500L * mb,
            uncompressedSize = 500L * mb,
            crc32 = 12345L,
            compressionMethod = 0,
            localHeaderOffset = 0L
        )

        val plan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = 500L * mb,
            entries = listOf(entry),
            format = ArchiveFormat.ZIP,
            destinationFreeBytes = capacityInfo.availableBytes,
            isDestinationCapacityReliable = capacityInfo.isReliable,
            isDirectPosixDestination = capacityInfo.isDirectPosix
        )

        assertFalse("Must never guarantee sufficient space when SAF capacity is unknown", plan.canGuaranteeSufficientSpace)
        assertFalse(plan.isDestinationCapacityReliable)
        assertNull(plan.destinationFreeBytes)
        assertEquals(PlanModeVerdict.CAPACITY_UNKNOWN, plan.standardVerdict)
        assertEquals(PlanModeVerdict.CAPACITY_UNKNOWN, plan.hatchVerdict)
        assertEquals(
            StoragePlannerRecommendation.CAPACITY_UNKNOWN_PROCEED_WITH_CAUTION,
            plan.recommendation
        )
    }

    @Test
    fun `unsupported Hatch modes report UNSUPPORTED_MODE while keeping Standard estimate valid`() {
        val entry1 = ZipEntryInfo(
            path = "archive_part1.tar",
            name = "archive_part1.tar",
            isDirectory = false,
            compressedSize = 100L * mb,
            uncompressedSize = 300L * mb,
            crc32 = 99L,
            compressionMethod = 8,
            localHeaderOffset = 0L
        )
        val entry2 = ZipEntryInfo(
            path = "archive_part2.tar",
            name = "archive_part2.tar",
            isDirectory = false,
            compressedSize = 100L * mb,
            uncompressedSize = 300L * mb,
            crc32 = 100L,
            compressionMethod = 8,
            localHeaderOffset = 100L * mb
        )

        // Case 1: TAR_GZ format
        val tarGzPlan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = 200L * mb,
            entries = listOf(entry1, entry2),
            format = ArchiveFormat.TAR_GZ,
            destinationFreeBytes = 1024L * mb,
            isDestinationCapacityReliable = true
        )
        assertFalse(tarGzPlan.isHatchModeSupported)
        assertEquals(PlanModeVerdict.FITS_SAFELY, tarGzPlan.standardVerdict)
        assertEquals(PlanModeVerdict.UNSUPPORTED_MODE, tarGzPlan.hatchVerdict)
        assertEquals(0L, tarGzPlan.spaceSavedWithHatchBytes)

        // Case 2: Solid 7z archive (multiple entries sharing solid block offset 0L)
        val solidEntry2 = entry2.copy(localHeaderOffset = 0L)
        val solid7zPlan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = 200L * mb,
            entries = listOf(entry1, solidEntry2),
            format = ArchiveFormat.SEVEN_Z,
            destinationFreeBytes = 1024L * mb,
            isDestinationCapacityReliable = true
        )
        assertFalse(solid7zPlan.isHatchModeSupported)
        assertEquals(PlanModeVerdict.UNSUPPORTED_MODE, solid7zPlan.hatchVerdict)

        // Case 3: Partial entry selection
        val partialSelectionPlan = SpaceLabEngine.computePreExtractionStoragePlan(
            archiveSizeBytes = 200L * mb,
            entries = listOf(entry1, entry2),
            format = ArchiveFormat.ZIP,
            destinationFreeBytes = 1024L * mb,
            isDestinationCapacityReliable = true,
            selectedEntryPaths = setOf("archive_part1.tar")
        )
        assertFalse(partialSelectionPlan.isHatchModeSupported)
        assertEquals(PlanModeVerdict.UNSUPPORTED_MODE, partialSelectionPlan.hatchVerdict)
    }
}
