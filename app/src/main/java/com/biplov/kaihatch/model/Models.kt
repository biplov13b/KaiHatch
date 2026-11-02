package com.biplov.kaihatch.model

import androidx.compose.runtime.Immutable

enum class AppAccentPalette(val label: String, val subtitle: String, val seedHex: Long) {
    DYNAMIC("Dynamic (Wallpaper)", "Material You system colors", 0xFF4ADE80),
    EMERALD("Expressive Mint", "Signature KaiHatch emerald", 0xFF34D399),
    OCEAN("Oceanic Sky", "Clean electric cyan & azure", 0xFF38BDF8),
    VIOLET("Royal Iris", "Expressive lavender & violet", 0xFFC084FC),
    CORAL("Sunset Coral", "Warm rose & terracotta", 0xFFFB7185),
    AMBER("Solar Gold", "Vibrant honey & amber", 0xFFFBBF24)
}

enum class AppThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

enum class ExtractionStatus {
    IDLE,
    ANALYZING,
    EXTRACTING,
    VERIFYING_CRC,
    TRUNCATING,
    COMPLETED,
    CANCELLED,
    ERROR
}

enum class CompressionStatus {
    IDLE,
    PREPARING,
    COMPRESSING,
    COMPLETED,
    CANCELLED,
    ERROR
}

enum class ArchiveFormat(val label: String, val extension: String) {
    ZIP("ZIP / ZIP64", "zip"),
    SEVEN_Z("7Z (7-Zip LZMA2)", "7z"),
    RAR("RAR Archive", "rar"),
    APK("APK Package (ZIP)", "apk"),
    TAR("TAR (Block-Truncatable)", "tar"),
    TAR_GZ("TAR.GZ (Gzip Stream)", "tar.gz"),
    SPLIT_ZIP("Multi-Part Split ZIP", "zip.001")
}

@Immutable
data class ZipEntryInfo(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val compressionMethod: Int, // 0 = Stored, 8 = Deflated, 100 = TAR 512B block
    val localHeaderOffset: Long,
    val lastModified: Long = System.currentTimeMillis(),
    val isEncrypted: Boolean = false,
    val isZip64: Boolean = false
) {
    val crcHex: String get() = String.format("0x%08X", crc32)
    val ratio: Float
        get() = if (uncompressedSize > 0) {
            ((uncompressedSize - compressedSize).toFloat() / uncompressedSize.toFloat() * 100f).coerceAtLeast(0f)
        } else 0f
}

@Immutable
data class ArchiveSummary(
    val filePath: String,
    val sourceUriString: String? = null,
    val fileName: String,
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    val isZip64: Boolean = false,
    val hasEncryptedEntries: Boolean = false,
    val isZeroCopyDirectUri: Boolean = false,
    val fileSizeBytes: Long,
    val uncompressedSizeBytes: Long,
    val entryCount: Int,
    val entries: List<ZipEntryInfo>,
    val standardPeakSpaceRequired: Long,
    val hatchPeakSpaceRequired: Long,
    val spaceSavedWithHatch: Long,
    val deviceFreeSpace: Long
)

@Immutable
data class ExtractionProgress(
    val archiveName: String = "",
    val isHatchMode: Boolean = true,
    val isZeroCopySaf: Boolean = false,
    val isZip64: Boolean = false,
    val status: ExtractionStatus = ExtractionStatus.IDLE,
    val totalEntries: Int = 0,
    val completedEntries: Int = 0,
    val currentFileName: String = "",
    val currentFileBytesProcessed: Long = 0L,
    val currentFileTotalBytes: Long = 0L,
    val totalUncompressedBytes: Long = 0L,
    val currentEntryCrc: Long = 0L,
    val isCrcValid: Boolean = true,
    val originalArchiveBytes: Long = 0L,
    val currentArchiveBytesOnDisk: Long = 0L,
    val bytesExtracted: Long = 0L,
    val bytesReclaimed: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val journalLogs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val outputDirectory: String? = null,
    val startTime: Long = 0L,
    val elapsedTimeMs: Long = 0L
) {
    val overallProgressFraction: Float
        get() = when {
            status == ExtractionStatus.COMPLETED -> 1f
            totalUncompressedBytes > 0L -> (bytesExtracted.toFloat() / totalUncompressedBytes.toFloat()).coerceIn(0f, 1f)
            totalEntries > 0 -> (completedEntries.toFloat() / totalEntries.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val currentFileProgressFraction: Float
        get() = if (currentFileTotalBytes > 0L) {
            (currentFileBytesProcessed.toFloat() / currentFileTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

@Immutable
data class ResumeJournalState(
    val stateFilePath: String,
    val archivePath: String,
    val sourceUriString: String? = null,
    val archiveName: String,
    val targetDirPath: String,
    val originalArchiveBytes: Long,
    val currentArchiveBytes: Long,
    val bytesReclaimedSoFar: Long,
    val totalEntries: Int,
    val completedPaths: Set<String>,
    val remainingEntries: List<ZipEntryInfo>,
    val password: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val verifiedEntryCrcs: Map<String, Long> = emptyMap()
)

@Immutable
data class CompressionSettings(
    val archiveName: String,
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    val compressionLevel: Int = 6, // 0=Store, 1=Fast, 6=Balanced, 9=Ultra
    val password: String = "",
    val splitVolumeSizeMb: Int = 0, // 0 = single file
    val comment: String = ""
)

@Immutable
data class CompressionProgress(
    val status: CompressionStatus = CompressionStatus.IDLE,
    val totalFiles: Int = 0,
    val currentFileIndex: Int = 0,
    val currentFileName: String = "",
    val totalInputBytes: Long = 0L,
    val processedInputBytes: Long = 0L,
    val currentOutputBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val outputFilePath: String? = null,
    val outputUriString: String? = null,
    val savedLocationLabel: String? = null,
    val splitVolumesCreated: Int = 1,
    val isEncrypted: Boolean = false,
    val errorMessage: String? = null,
    val startTime: Long = 0L,
    val elapsedTimeMs: Long = 0L
) {
    val overallProgressFraction: Float
        get() = when {
            status == CompressionStatus.COMPLETED -> 1f
            totalInputBytes > 0L -> (processedInputBytes.toFloat() / totalInputBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (currentFileIndex.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

@Immutable
data class OperationRecord(
    val id: String,
    val type: String, // "HATCH_EXTRACT", "STANDARD_EXTRACT", "COMPRESS"
    val sourceName: String,
    val targetPath: String,
    val originalSize: Long,
    val reclaimedOrSavedSize: Long,
    val entryCount: Int,
    val timestamp: Long,
    val durationMs: Long,
    val success: Boolean
)
