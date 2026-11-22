package com.biplov.kaihatch.model

import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.biplov.kaihatch.R
import java.util.Locale

enum class AppLanguage(
    val tag: String,
    val label: String,
    val nativeName: String,
    @StringRes val labelResId: Int,
    @StringRes val subtitleResId: Int
) {
    SYSTEM(
        tag = "",
        label = "System Default",
        nativeName = "System Default",
        labelResId = R.string.language_system_default,
        subtitleResId = R.string.language_system_default_subtitle
    ),
    ENGLISH(
        tag = "en",
        label = "English",
        nativeName = "English",
        labelResId = R.string.language_english,
        subtitleResId = R.string.language_english_subtitle
    ),
    SPANISH(
        tag = "es",
        label = "Spanish",
        nativeName = "Español",
        labelResId = R.string.language_spanish,
        subtitleResId = R.string.language_spanish_subtitle
    ),
    FRENCH(
        tag = "fr",
        label = "French",
        nativeName = "Français",
        labelResId = R.string.language_french,
        subtitleResId = R.string.language_french_subtitle
    ),
    GERMAN(
        tag = "de",
        label = "German",
        nativeName = "Deutsch",
        labelResId = R.string.language_german,
        subtitleResId = R.string.language_german_subtitle
    ),
    ITALIAN(
        tag = "it",
        label = "Italian",
        nativeName = "Italiano",
        labelResId = R.string.language_italian,
        subtitleResId = R.string.language_italian_subtitle
    ),
    PORTUGUESE_BR(
        tag = "pt-BR",
        label = "Portuguese (Brazil)",
        nativeName = "Português (Brasil)",
        labelResId = R.string.language_portuguese_br,
        subtitleResId = R.string.language_portuguese_br_subtitle
    ),
    PORTUGUESE_PT(
        tag = "pt-PT",
        label = "Portuguese (Portugal)",
        nativeName = "Português (Portugal)",
        labelResId = R.string.language_portuguese_pt,
        subtitleResId = R.string.language_portuguese_pt_subtitle
    ),
    DUTCH(
        tag = "nl",
        label = "Dutch",
        nativeName = "Nederlands",
        labelResId = R.string.language_dutch,
        subtitleResId = R.string.language_dutch_subtitle
    ),
    RUSSIAN(
        tag = "ru",
        label = "Russian",
        nativeName = "Русский",
        labelResId = R.string.language_russian,
        subtitleResId = R.string.language_russian_subtitle
    ),
    UKRAINIAN(
        tag = "uk",
        label = "Ukrainian",
        nativeName = "Українська",
        labelResId = R.string.language_ukrainian,
        subtitleResId = R.string.language_ukrainian_subtitle
    ),
    POLISH(
        tag = "pl",
        label = "Polish",
        nativeName = "Polski",
        labelResId = R.string.language_polish,
        subtitleResId = R.string.language_polish_subtitle
    ),
    CZECH(
        tag = "cs",
        label = "Czech",
        nativeName = "Čeština",
        labelResId = R.string.language_czech,
        subtitleResId = R.string.language_czech_subtitle
    ),
    ROMANIAN(
        tag = "ro",
        label = "Romanian",
        nativeName = "Română",
        labelResId = R.string.language_romanian,
        subtitleResId = R.string.language_romanian_subtitle
    ),
    HUNGARIAN(
        tag = "hu",
        label = "Hungarian",
        nativeName = "Magyar",
        labelResId = R.string.language_hungarian,
        subtitleResId = R.string.language_hungarian_subtitle
    ),
    GREEK(
        tag = "el",
        label = "Greek",
        nativeName = "Ελληνικά",
        labelResId = R.string.language_greek,
        subtitleResId = R.string.language_greek_subtitle
    ),
    TURKISH(
        tag = "tr",
        label = "Turkish",
        nativeName = "Türkçe",
        labelResId = R.string.language_turkish,
        subtitleResId = R.string.language_turkish_subtitle
    ),
    JAPANESE(
        tag = "ja",
        label = "Japanese",
        nativeName = "日本語",
        labelResId = R.string.language_japanese,
        subtitleResId = R.string.language_japanese_subtitle
    ),
    KOREAN(
        tag = "ko",
        label = "Korean",
        nativeName = "한국어",
        labelResId = R.string.language_korean,
        subtitleResId = R.string.language_korean_subtitle
    ),
    CHINESE_SIMPLIFIED(
        tag = "zh-CN",
        label = "Chinese (Simplified)",
        nativeName = "简体中文",
        labelResId = R.string.language_chinese_simplified,
        subtitleResId = R.string.language_chinese_simplified_subtitle
    ),
    CHINESE_TRADITIONAL(
        tag = "zh-TW",
        label = "Chinese (Traditional)",
        nativeName = "繁體中文",
        labelResId = R.string.language_chinese_traditional,
        subtitleResId = R.string.language_chinese_traditional_subtitle
    ),
    INDONESIAN(
        tag = "id",
        label = "Indonesian",
        nativeName = "Bahasa Indonesia",
        labelResId = R.string.language_indonesian,
        subtitleResId = R.string.language_indonesian_subtitle
    ),
    MALAY(
        tag = "ms",
        label = "Malay",
        nativeName = "Bahasa Melayu",
        labelResId = R.string.language_malay,
        subtitleResId = R.string.language_malay_subtitle
    ),
    VIETNAMESE(
        tag = "vi",
        label = "Vietnamese",
        nativeName = "Tiếng Việt",
        labelResId = R.string.language_vietnamese,
        subtitleResId = R.string.language_vietnamese_subtitle
    ),
    THAI(
        tag = "th",
        label = "Thai",
        nativeName = "ไทย",
        labelResId = R.string.language_thai,
        subtitleResId = R.string.language_thai_subtitle
    ),
    FILIPINO(
        tag = "fil",
        label = "Filipino",
        nativeName = "Filipino",
        labelResId = R.string.language_filipino,
        subtitleResId = R.string.language_filipino_subtitle
    ),
    ARABIC(
        tag = "ar",
        label = "Arabic",
        nativeName = "العربية",
        labelResId = R.string.language_arabic,
        subtitleResId = R.string.language_arabic_subtitle
    ),
    HEBREW(
        tag = "he",
        label = "Hebrew",
        nativeName = "עברית",
        labelResId = R.string.language_hebrew,
        subtitleResId = R.string.language_hebrew_subtitle
    ),
    PERSIAN(
        tag = "fa",
        label = "Persian",
        nativeName = "فارسی",
        labelResId = R.string.language_persian,
        subtitleResId = R.string.language_persian_subtitle
    ),
    SWEDISH(
        tag = "sv",
        label = "Swedish",
        nativeName = "Svenska",
        labelResId = R.string.language_swedish,
        subtitleResId = R.string.language_swedish_subtitle
    ),
    DANISH(
        tag = "da",
        label = "Danish",
        nativeName = "Dansk",
        labelResId = R.string.language_danish,
        subtitleResId = R.string.language_danish_subtitle
    ),
    NORWEGIAN_BOKMAL(
        tag = "nb",
        label = "Norwegian Bokmål",
        nativeName = "Norsk bokmål",
        labelResId = R.string.language_norwegian_bokmal,
        subtitleResId = R.string.language_norwegian_bokmal_subtitle
    ),
    FINNISH(
        tag = "fi",
        label = "Finnish",
        nativeName = "Suomi",
        labelResId = R.string.language_finnish,
        subtitleResId = R.string.language_finnish_subtitle
    );

    val languageTag: String
        get() = tag

    val isRtl: Boolean
        get() = this == ARABIC || this == HEBREW || this == PERSIAN

    companion object {
        fun fromSavedValue(raw: String?): AppLanguage {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return SYSTEM
            entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }?.let { return it }
            return fromLanguageTag(cleaned)
        }

        /**
         * Matches a BCP-47 language tag (including regional variants, scripts, and legacy ISO codes)
         * to one of the 32 supported [AppLanguage] entries, or returns `null` if the language is
         * not supported or represents an empty/system tag.
         */
        fun matchSupportedLanguage(tag: String?): AppLanguage? {
            val normalized = tag?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
            if (normalized.isEmpty() || normalized == "system" || normalized == "und") {
                return null
            }

            val subtags = normalized.split('-').filter { it.isNotEmpty() }
            val primaryRaw = subtags.firstOrNull() ?: return null

            // 1. Chinese script & regional variants
            if (primaryRaw == "zh") {
                val hasHant = subtags.any { it == "hant" }
                val hasHans = subtags.any { it == "hans" }
                val hasTradRegion = subtags.any { it == "tw" || it == "hk" || it == "mo" }
                return when {
                    hasHant -> CHINESE_TRADITIONAL
                    hasHans -> CHINESE_SIMPLIFIED
                    hasTradRegion -> CHINESE_TRADITIONAL
                    else -> CHINESE_SIMPLIFIED
                }
            }

            // 2. Portuguese regional variants (European/PALOP vs Brazilian)
            if (primaryRaw == "pt") {
                val ptPortugalRegions = setOf("pt", "ao", "mz", "cv", "gw", "st", "tl", "mo", "gq")
                val hasPtPortugalRegion = subtags.drop(1).any { it in ptPortugalRegions }
                return if (hasPtPortugalRegion) {
                    PORTUGUESE_PT
                } else {
                    PORTUGUESE_BR
                }
            }

            // 3. Exact tag match (e.g., "en", "es", "fil", "nb")
            entries.firstOrNull { lang ->
                lang != SYSTEM && normalized == lang.tag.lowercase(Locale.ROOT).replace('_', '-')
            }?.let { return it }

            // 4. Tag prefix match (e.g., "en-us", "es-419", "fr-ca")
            entries.firstOrNull { lang ->
                val langNorm = lang.tag.lowercase(Locale.ROOT).replace('_', '-')
                lang != SYSTEM && normalized.startsWith("$langNorm-")
            }?.let { return it }

            // 5. Primary language subtag match with legacy/alias ISO code normalization
            val canonicalPrimary = when (primaryRaw) {
                "in" -> "id"
                "iw" -> "he"
                "tl" -> "fil"
                "no", "nn" -> "nb"
                else -> primaryRaw
            }
            return entries.firstOrNull { lang ->
                val langPrimary = lang.tag.lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
                lang != SYSTEM && langPrimary == canonicalPrimary
            }
        }

        fun matchSupportedLocale(locale: Locale?): AppLanguage? {
            if (locale == null) return null
            matchSupportedLanguage(locale.toLanguageTag())?.let { return it }
            val fallbackTag = buildString {
                append(locale.language)
                if (locale.script.isNotEmpty()) {
                    append('-').append(locale.script)
                }
                if (locale.country.isNotEmpty()) {
                    append('-').append(locale.country)
                }
            }
            return matchSupportedLanguage(fallbackTag)
        }

        fun matchSupportedLocaleList(locales: LocaleList?): AppLanguage? {
            if (locales == null || locales.isEmpty) return null
            return matchSupportedLocale(locales[0])
        }

        fun fromLanguageTag(tag: String?): AppLanguage {
            return matchSupportedLanguage(tag) ?: SYSTEM
        }

        fun fromLocaleList(locales: LocaleList?): AppLanguage {
            return matchSupportedLocaleList(locales) ?: SYSTEM
        }

        fun availableLanguages(includeSystemDefault: Boolean = true): List<AppLanguage> {
            return if (includeSystemDefault) {
                entries
            } else {
                entries.filter { it != SYSTEM }
            }
        }

        fun filterByQuery(
            query: String,
            localizedLabels: Map<AppLanguage, String> = emptyMap(),
            localizedSubtitles: Map<AppLanguage, String> = emptyMap(),
            includeSystemDefault: Boolean = true
        ): List<AppLanguage> {
            val candidates = availableLanguages(includeSystemDefault)
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return candidates
            return candidates.filter { lang ->
                lang.nativeName.contains(trimmed, ignoreCase = true) ||
                    lang.label.contains(trimmed, ignoreCase = true) ||
                    (localizedLabels[lang]?.contains(trimmed, ignoreCase = true) == true) ||
                    (localizedSubtitles[lang]?.contains(trimmed, ignoreCase = true) == true) ||
                    lang.tag.contains(trimmed, ignoreCase = true)
            }
        }
    }
}

enum class AppAccentPalette(
    val label: String,
    val subtitle: String,
    val seedHex: Long,
    @StringRes val labelResId: Int,
    @StringRes val subtitleResId: Int
) {
    DYNAMIC("Dynamic (Wallpaper)", "Material You", 0xFF4ADE80, R.string.accent_dynamic_label, R.string.accent_dynamic_subtitle),
    EMERALD("Expressive Mint", "Fresh mint", 0xFF34D399, R.string.accent_emerald_label, R.string.accent_emerald_subtitle),
    OCEAN("Oceanic Sky", "Signature KaiHatch", 0xFF38BDF8, R.string.accent_ocean_label, R.string.accent_ocean_subtitle),
    VIOLET("Royal Iris", "Lavender & violet", 0xFFC084FC, R.string.accent_violet_label, R.string.accent_violet_subtitle),
    CORAL("Sunset Coral", "Rose & terracotta", 0xFFFB7185, R.string.accent_coral_label, R.string.accent_coral_subtitle),
    AMBER("Solar Gold", "Honey & amber", 0xFFFBBF24, R.string.accent_amber_label, R.string.accent_amber_subtitle)
}

enum class AppThemeMode(
    val label: String,
    @StringRes val labelResId: Int
) {
    DARK("Dark", R.string.theme_mode_dark),
    LIGHT("Light", R.string.theme_mode_light),
    SYSTEM("System", R.string.theme_mode_system)
}

enum class ExtractionStatus {
    IDLE,
    ANALYZING,
    EXTRACTING,
    VERIFYING_CRC,
    TRUNCATING,
    PAUSING,
    PAUSED,
    FINALIZING,
    COMPLETED,
    CANCELLED,
    ERROR;

    val isActiveSession: Boolean
        get() = this == ANALYZING ||
            this == EXTRACTING ||
            this == VERIFYING_CRC ||
            this == TRUNCATING ||
            this == PAUSING ||
            this == PAUSED ||
            this == FINALIZING
}

enum class CompressionStatus {
    IDLE,
    PREPARING,
    COMPRESSING,
    COMPLETED,
    CANCELLED,
    ERROR
}

enum class ArchiveFormat(
    val label: String,
    val extension: String,
    @StringRes val labelResId: Int
) {
    ZIP("ZIP / ZIP64", "zip", R.string.format_label_zip),
    SEVEN_Z("7Z (7-Zip LZMA2)", "7z", R.string.format_label_7z),
    RAR("RAR Archive", "rar", R.string.format_label_rar),
    APK("APK Package (ZIP)", "apk", R.string.format_label_apk),
    TAR("TAR (Block-Truncatable)", "tar", R.string.format_label_tar),
    TAR_GZ("TAR.GZ (Gzip Stream)", "tar.gz", R.string.format_label_tar_gz),
    SPLIT_ZIP("Multi-Part Split ZIP", "zip.001", R.string.format_label_split_zip)
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
enum class PlanModeVerdict {
    FITS_SAFELY,
    INSUFFICIENT_SPACE,
    CAPACITY_UNKNOWN,
    UNSUPPORTED_MODE
}

@Immutable
enum class StoragePlannerRecommendation {
    BOTH_MODES_FIT,
    HATCH_MODE_RECOMMENDED,
    INSUFFICIENT_FOR_BOTH,
    CAPACITY_UNKNOWN_PROCEED_WITH_CAUTION
}

@Immutable
data class PreExtractionStoragePlan(
    val archiveSizeBytes: Long,
    val extractedSizeBytes: Long,
    val standardTempWorkingBytes: Long,
    val hatchTempWorkingBytes: Long,
    val safetyReserveBytes: Long,
    val standardPeakAdditionalBytes: Long,
    val hatchPeakAdditionalBytes: Long,
    val standardRequiredWithReserveBytes: Long,
    val hatchRequiredWithReserveBytes: Long,
    val standardTotalPeakBytes: Long,
    val hatchTotalPeakBytes: Long,
    val spaceSavedWithHatchBytes: Long,
    val destinationFreeBytes: Long?,
    val isDestinationCapacityReliable: Boolean,
    val isDirectPosixDestination: Boolean,
    val isHatchModeSupported: Boolean,
    val isEstimateReliable: Boolean,
    val canGuaranteeSufficientSpace: Boolean,
    val standardVerdict: PlanModeVerdict,
    val hatchVerdict: PlanModeVerdict,
    val recommendation: StoragePlannerRecommendation,
    val statusReason: String? = null
)

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
    val deviceFreeSpace: Long,
    val storagePlan: PreExtractionStoragePlan? = null
)

@Immutable
enum class VerificationLevel {
    QUICK_CHECK,
    FULL_VERIFY
}

@Immutable
enum class VerificationStatus {
    IDLE,
    RUNNING,
    PASSED_STRUCTURAL,
    PASSED_FULL_INTEGRITY,
    FAILED,
    UNSUPPORTED,
    CANCELLED
}

@Immutable
data class ArchiveSourceFingerprint(
    val filePath: String? = null,
    val sourceUriString: String? = null,
    val fileSizeBytes: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val headTailDigestCrc32: Long = 0L
)

@Immutable
data class ArchiveVerificationState(
    val archiveName: String = "",
    val level: VerificationLevel = VerificationLevel.QUICK_CHECK,
    val status: VerificationStatus = VerificationStatus.IDLE,
    val quickCheckStatus: VerificationStatus = VerificationStatus.IDLE,
    val fullVerifyStatus: VerificationStatus = VerificationStatus.IDLE,
    val totalEntries: Int = 0,
    val processedEntries: Int = 0,
    val totalBytes: Long = 0L,
    val processedBytes: Long = 0L,
    val currentEntryName: String = "",
    val crcVerifiedEntries: Int = 0,
    val structuralCheckedEntries: Int = 0,
    val skippedOrUnsupportedEntries: Int = 0,
    val corruptedEntryPath: String? = null,
    val summaryMessage: String = "",
    val detailMessage: String? = null,
    val sourceFingerprint: ArchiveSourceFingerprint? = null,
    val isSourceModifiedSinceVerify: Boolean = false,
    val elapsedTimeMs: Long = 0L
) {
    val progressFraction: Float
        get() = when {
            status == VerificationStatus.PASSED_STRUCTURAL || status == VerificationStatus.PASSED_FULL_INTEGRITY -> 1f
            level == VerificationLevel.FULL_VERIFY && totalBytes > 0L ->
                (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalEntries > 0 ->
                (processedEntries.toFloat() / totalEntries.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val isRunning: Boolean
        get() = status == VerificationStatus.RUNNING ||
            quickCheckStatus == VerificationStatus.RUNNING ||
            fullVerifyStatus == VerificationStatus.RUNNING

    fun pillStatusFor(targetLevel: VerificationLevel): VerificationStatus {
        val raw = when (targetLevel) {
            VerificationLevel.QUICK_CHECK ->
                if (quickCheckStatus != VerificationStatus.IDLE) quickCheckStatus
                else if (level == VerificationLevel.QUICK_CHECK) status
                else VerificationStatus.IDLE
            VerificationLevel.FULL_VERIFY ->
                if (fullVerifyStatus != VerificationStatus.IDLE) fullVerifyStatus
                else if (level == VerificationLevel.FULL_VERIFY) status
                else VerificationStatus.IDLE
        }
        if (isSourceModifiedSinceVerify &&
            (raw == VerificationStatus.PASSED_STRUCTURAL || raw == VerificationStatus.PASSED_FULL_INTEGRITY)
        ) {
            return VerificationStatus.IDLE
        }
        return raw
    }
}

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
    val elapsedTimeMs: Long = 0L,
    val isPauseSupported: Boolean = true
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
    val verifiedEntryCrcs: Map<String, Long> = emptyMap(),
    val targetTreeUriString: String? = null,
    val targetFolderDocUriString: String? = null,
    val hasPendingRestoration: Boolean = false
)

@Immutable
enum class RestorationPhase(val code: Int) {
    VALIDATED_READY(1),
    WRITING_CENTRAL_DIRECTORY(2),
    VERIFYING_RESTORED_ARCHIVE(3),
    COMPLETED(4);

    companion object {
        fun fromCode(code: Int): RestorationPhase =
            entries.firstOrNull { it.code == code } ?: VALIDATED_READY
    }
}

@Immutable
data class RestorationCheckpointState(
    val stateFilePath: String,
    val archivePath: String,
    val sourceUriString: String? = null,
    val archiveName: String,
    val newCentralDirOffset: Long,
    val expectedFinalArchiveSize: Long,
    val remainingEntryCount: Int,
    val phase: RestorationPhase,
    val timestamp: Long = System.currentTimeMillis()
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
