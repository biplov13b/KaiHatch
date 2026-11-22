package com.biplov.kaihatch.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.ArchiveJobRunner
import com.biplov.kaihatch.data.ExtractedFileEntry
import com.biplov.kaihatch.data.ExtractedFolderEntry
import com.biplov.kaihatch.data.GrantedFolderInfo
import com.biplov.kaihatch.data.HatchNotificationHelper
import com.biplov.kaihatch.data.HistoryRepository
import com.biplov.kaihatch.data.IoConsoleLogStore
import com.biplov.kaihatch.data.LanguageHelper
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.engine.ArchiveVerifierEngine
import com.biplov.kaihatch.engine.HatchCompressEngine
import com.biplov.kaihatch.engine.KaiHatchEngine
import com.biplov.kaihatch.engine.SpaceLabEngine
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.AppAccentPalette
import com.biplov.kaihatch.model.AppLanguage
import com.biplov.kaihatch.model.AppThemeMode
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ArchiveSummary
import com.biplov.kaihatch.model.ArchiveVerificationState
import com.biplov.kaihatch.model.CompressionProgress
import com.biplov.kaihatch.model.CompressionSettings
import com.biplov.kaihatch.model.CompressionStatus
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.OperationRecord
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.VerificationLevel
import com.biplov.kaihatch.model.VerificationStatus
import com.biplov.kaihatch.model.ZipEntryInfo
import com.biplov.kaihatch.BuildConfig
import com.biplov.kaihatch.updater.AppUpdateManager
import com.biplov.kaihatch.updater.GitHubReleaseInfo
import com.biplov.kaihatch.updater.UpdateCheckResult
import com.biplov.kaihatch.updater.UpdateDialogState
import com.biplov.kaihatch.updater.UpdateDownloadOutcome
import com.biplov.kaihatch.updater.UpdateNotificationHelper
import com.biplov.kaihatch.updater.UpdateUiEvent
import com.biplov.kaihatch.updater.UpdateWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class CompletedExtractionTarget(
    val completionId: Long,
    val folderDocUriString: String? = null,
    val folderDocumentId: String? = null,
    val canonicalDirPath: String? = null,
    val absoluteDirPath: String? = null,
    val outputDirectory: String? = null,
    val folderName: String = ""
)

enum class ScreenTab {
    EXTRACT,
    COMPRESS,
    EXPLORER,
    SPACE_LAB,
    HISTORY,
    SETTINGS
}

@Stable
class HatchViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("kaihatch_settings", Context.MODE_PRIVATE)
    val historyRepo = HistoryRepository(application)

    private val _currentTab = MutableStateFlow(ScreenTab.EXTRACT)
    val currentTab: StateFlow<ScreenTab> = _currentTab.asStateFlow()

    private val _activePageTab = MutableStateFlow(ScreenTab.EXTRACT)
    val activePageTab: StateFlow<ScreenTab> = _activePageTab.asStateFlow()

    private var lastNonSettingsTab: ScreenTab = ScreenTab.EXTRACT

    // First-launch onboarding state (with automatic migration for existing users updating the app)
    private val _hasCompletedOnboarding = MutableStateFlow(loadOrMigrateOnboardingState(application))
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    // Unified Material 3 Expressive Display, Color & Language Settings
    private val _accentPalette = MutableStateFlow(loadSavedAccentPalette())
    val accentPalette: StateFlow<AppAccentPalette> = _accentPalette.asStateFlow()

    private val _themeMode = MutableStateFlow(loadSavedThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _appLanguage = MutableStateFlow(loadSavedAppLanguage())
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    private val _isSystemLanguageSupported = MutableStateFlow(
        LanguageHelper.isDeviceLanguageSupported(application)
    )
    val isSystemLanguageSupported: StateFlow<Boolean> = _isSystemLanguageSupported.asStateFlow()

    @Volatile
    private var cachedLocalizedContext: Pair<AppLanguage, Context>? = null

    private val localizedAppContext: Context
        get() {
            val lang = _appLanguage.value
            val cached = cachedLocalizedContext
            if (cached != null && cached.first == lang) {
                return cached.second
            }
            val wrapped = LanguageHelper.wrapContext(getApplication(), lang)
            cachedLocalizedContext = lang to wrapped
            return wrapped
        }

    // Progressive Blur & Tab Swipe Settings (matching sameerasw/essentials)
    private val _isBlurEnabled = MutableStateFlow(prefs.getBoolean("use_blur", true))
    val isBlurEnabled: StateFlow<Boolean> = _isBlurEnabled.asStateFlow()

    private val _isSwipeTabsEnabled = MutableStateFlow(prefs.getBoolean("swipe_tabs", true))
    val isSwipeTabsEnabled: StateFlow<Boolean> = _isSwipeTabsEnabled.asStateFlow()

    // Notification & Persistent Folder Permission Status
    private val _notificationsEnabled = MutableStateFlow(HatchNotificationHelper.hasNotificationPermission(application))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _allFilesAccessEnabled = MutableStateFlow(StorageHelper.hasAllFilesAccess())
    val allFilesAccessEnabled: StateFlow<Boolean> = _allFilesAccessEnabled.asStateFlow()

    private val _grantedFolder = MutableStateFlow<GrantedFolderInfo?>(StorageHelper.getGrantedFolderInfoFast(application))
    val grantedFolder: StateFlow<GrantedFolderInfo?> = _grantedFolder.asStateFlow()

    // Mandatory Persistent Folder Prompt State (triggered when clicking Extract without a granted folder)
    private val _showPersistentFolderPrompt = MutableStateFlow(false)
    val showPersistentFolderPrompt: StateFlow<Boolean> = _showPersistentFolderPrompt.asStateFlow()

    private var autoStartExtractionAfterFolderGrant = false
    private var pendingExtractionSelectedEntries: Set<String>? = null
    private var autoStartCompressionAfterFolderGrant = false

    // Device Storage (populated asynchronously on Dispatchers.IO in init)
    private val _deviceFreeSpace = MutableStateFlow(0L)
    val deviceFreeSpace: StateFlow<Long> = _deviceFreeSpace.asStateFlow()

    private val _deviceTotalSpace = MutableStateFlow(0L)
    val deviceTotalSpace: StateFlow<Long> = _deviceTotalSpace.asStateFlow()

    // Extraction State
    private val _selectedArchiveFile = MutableStateFlow<File?>(null)
    val selectedArchiveFile: StateFlow<File?> = _selectedArchiveFile.asStateFlow()

    private val _selectedSafUri = MutableStateFlow<Uri?>(null)
    val selectedSafUri: StateFlow<Uri?> = _selectedSafUri.asStateFlow()

    private val _archiveSummary = MutableStateFlow<ArchiveSummary?>(null)
    val archiveSummary: StateFlow<ArchiveSummary?> = _archiveSummary.asStateFlow()

    private val _isHatchMode = MutableStateFlow(prefs.getBoolean("default_hatch_mode", true))
    val isHatchMode: StateFlow<Boolean> = _isHatchMode.asStateFlow()

    private val _archivePassword = MutableStateFlow("")
    val archivePassword: StateFlow<String> = _archivePassword.asStateFlow()

    private val consoleLogStore = IoConsoleLogStore(application)
    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs: StateFlow<List<String>> = _consoleLogs.asStateFlow()
    private val consoleRunIdCounter = AtomicLong(0L)

    private val _extractionProgress = MutableStateFlow(ExtractionProgress())
    val extractionProgress: StateFlow<ExtractionProgress> = _extractionProgress.asStateFlow()
    @Volatile
    private var lastExtractionUiUpdateNs: Long = 0L
    @Volatile
    private var lastCompressionUiUpdateNs: Long = 0L

    // Crash-Safe Pending Resume Journals
    private val _pendingResumeStates = MutableStateFlow<List<ResumeJournalState>>(emptyList())
    val pendingResumeStates: StateFlow<List<ResumeJournalState>> = _pendingResumeStates.asStateFlow()

    // Floating Extraction Control Expansion & Stop Confirmation Dialog State
    private val _isFloatingControlsExpanded = MutableStateFlow(false)
    val isFloatingControlsExpanded: StateFlow<Boolean> = _isFloatingControlsExpanded.asStateFlow()

    private val _showStopConfirmDialog = MutableStateFlow(false)
    val showStopConfirmDialog: StateFlow<Boolean> = _showStopConfirmDialog.asStateFlow()

    private var extractJob: Job? = null

    // Pre-Extraction Archive Verification State (Phase 2: Verify Before Hatch)
    private val _verificationState = MutableStateFlow(ArchiveVerificationState())
    val verificationState: StateFlow<ArchiveVerificationState> = _verificationState.asStateFlow()
    private var verifyJob: Job? = null

    // Built-in GitHub App Updater State
    private val _updateDialogState = MutableStateFlow<UpdateDialogState>(UpdateDialogState.Hidden)
    val updateDialogState: StateFlow<UpdateDialogState> = _updateDialogState.asStateFlow()

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _updateEvents = Channel<UpdateUiEvent>(capacity = Channel.BUFFERED)
    val updateEvents: Flow<UpdateUiEvent> = _updateEvents.receiveAsFlow()

    private val startupUpdateInitialized = AtomicBoolean(false)
    private val suppressStartupUpdatePrompt = AtomicBoolean(false)
    private val updateDownloadCancelled = AtomicBoolean(false)
    private var updateDownloadJob: Job? = null
    private var pendingInstallRelease: GitHubReleaseInfo? = null
    private var pendingInstallApkFile: File? = null

    // Compression State
    private val _itemsToCompress = MutableStateFlow<List<File>>(emptyList())
    val itemsToCompress: StateFlow<List<File>> = _itemsToCompress.asStateFlow()

    private val _compressionSettings = MutableStateFlow(
        CompressionSettings(archiveName = "KaiHatch_${System.currentTimeMillis() / 1000}.zip")
    )
    val compressionSettings: StateFlow<CompressionSettings> = _compressionSettings.asStateFlow()

    private var hasCustomArchiveName: Boolean = false

    private val _compressionProgress = MutableStateFlow(CompressionProgress())
    val compressionProgress: StateFlow<CompressionProgress> = _compressionProgress.asStateFlow()

    private var compressJob: Job? = null

    // Explorer State
    private val _exploringArchive = MutableStateFlow<File?>(null)
    val exploringArchive: StateFlow<File?> = _exploringArchive.asStateFlow()

    private val _exploringSafUri = MutableStateFlow<Uri?>(null)
    val exploringSafUri: StateFlow<Uri?> = _exploringSafUri.asStateFlow()

    private val _exploringArchiveName = MutableStateFlow("")
    val exploringArchiveName: StateFlow<String> = _exploringArchiveName.asStateFlow()

    private val _explorerAllEntries = MutableStateFlow<List<ZipEntryInfo>>(emptyList())
    val explorerAllEntries: StateFlow<List<ZipEntryInfo>> = _explorerAllEntries.asStateFlow()

    private val _currentFolderPath = MutableStateFlow("")
    val currentFolderPath: StateFlow<String> = _currentFolderPath.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedExplorerPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedExplorerPaths: StateFlow<Set<String>> = _selectedExplorerPaths.asStateFlow()

    // Space Lab State
    private val _labGenerating = MutableStateFlow(false)
    val labGenerating: StateFlow<Boolean> = _labGenerating.asStateFlow()

    private val _availableArchives = MutableStateFlow<List<File>>(emptyList())
    val availableArchives: StateFlow<List<File>> = _availableArchives.asStateFlow()

    private val _extractedFolders = MutableStateFlow<List<ExtractedFolderEntry>>(emptyList())
    val extractedFolders: StateFlow<List<ExtractedFolderEntry>> = _extractedFolders.asStateFlow()

    // Lazy expandable directory tree state for extracted folders (subfolders loaded on-demand on Dispatchers.IO)
    private val _expandedSubfolderIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedSubfolderIds: StateFlow<Set<String>> = _expandedSubfolderIds.asStateFlow()

    private val _subfolderChildrenCache = MutableStateFlow<Map<String, List<ExtractedFileEntry>>>(emptyMap())
    val subfolderChildrenCache: StateFlow<Map<String, List<ExtractedFileEntry>>> = _subfolderChildrenCache.asStateFlow()

    private val _loadingSubfolderIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingSubfolderIds: StateFlow<Set<String>> = _loadingSubfolderIds.asStateFlow()

    private val subfolderLoadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val extractionCompletionCounter = AtomicLong(0L)
    private val _lastCompletedTarget = MutableStateFlow<CompletedExtractionTarget?>(null)
    val lastCompletedTarget: StateFlow<CompletedExtractionTarget?> = _lastCompletedTarget.asStateFlow()

    // Precomputed file & folder byte sizes cached off the main thread to prevent UI jank on tab switches
    private val _fileSizesCache = MutableStateFlow<Map<String, Long>>(emptyMap())
    val fileSizesCache: StateFlow<Map<String, Long>> = _fileSizesCache.asStateFlow()

    init {
        if (ArchiveJobRunner.isExtractionActive()) {
            val activeProgress = ArchiveJobRunner.extractionProgress.value
            val seededRunId = consoleRunIdCounter.incrementAndGet()
            val (_, merged) = appendEngineConsoleSnapshot(seededRunId, activeProgress.journalLogs, flushImmediately = false)
            _extractionProgress.value = activeProgress.copy(journalLogs = merged)
        }
        if (ArchiveJobRunner.isCompressionActive()) {
            _compressionProgress.value = ArchiveJobRunner.compressionProgress.value
        }
        HatchNotificationHelper.onCancelRequested = {
            if (ArchiveJobRunner.isCompressionActive()) {
                cancelCompression()
            }
        }
        HatchNotificationHelper.onStopConfirmationRequested = {
            requestStopExtractionConfirmation()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val persistedLogs = consoleLogStore.loadPersistedSync()
            _consoleLogs.value = persistedLogs
            if (persistedLogs.isNotEmpty()) {
                _extractionProgress.update { cur ->
                    if (cur.journalLogs == persistedLogs) cur else cur.copy(journalLogs = persistedLogs)
                }
            }
            HatchNotificationHelper.ensureChannels(application)
            if (_grantedFolder.value == null) {
                _grantedFolder.value = StorageHelper.getGrantedFolderInfo(application)
            }
            _deviceFreeSpace.value = StorageHelper.getDeviceFreeBytes(application)
            _deviceTotalSpace.value = StorageHelper.getDeviceTotalBytes(application)
            _pendingResumeStates.value = ResumeJournalManager.loadPendingStates(application)
            StorageHelper.cleanupAbandonedCompressionTempFiles(
                context = application,
                isCompressionRunning = ArchiveJobRunner.isCompressionActive(),
                queuedItems = _itemsToCompress.value,
                minAgeMs = 5_000L,
                queuedItemsProvider = { _itemsToCompress.value }
            )
        }
        refreshLocalFiles()
    }

    private fun appendConsoleLines(
        lines: List<String>,
        flushImmediately: Boolean = false
    ): List<String> {
        val updated = consoleLogStore.appendLines(lines, flushImmediately = flushImmediately)
        if (updated != null) {
            _consoleLogs.value = updated
            return updated
        }
        return _consoleLogs.value
    }

    private fun appendEngineConsoleSnapshot(
        runId: Long,
        snapshot: List<String>,
        flushImmediately: Boolean = false
    ): Pair<Boolean, List<String>> {
        val updated = consoleLogStore.appendEngineSnapshot(
            runId = runId,
            snapshot = snapshot,
            flushImmediately = flushImmediately
        )
        return if (updated != null) {
            _consoleLogs.value = updated
            true to updated
        } else {
            false to _consoleLogs.value
        }
    }

    /**
     * Explicitly clears persisted and in-memory I/O Console logs.
     * Invoked ONLY by an explicit user Clear action.
     */
    fun clearConsoleLogs() {
        val cleared = consoleLogStore.clear()
        _consoleLogs.value = cleared
        _extractionProgress.update { it.copy(journalLogs = cleared) }
    }

    internal fun flushConsoleLogsForTest() {
        consoleLogStore.flushNowSync()
    }

    private fun loadOrMigrateOnboardingState(application: Application): Boolean {
        if (prefs.contains(KEY_ONBOARDING_COMPLETED)) {
            return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        }
        val hasExistingUserState = prefs.contains("theme_mode") ||
            prefs.contains("accent_palette") ||
            prefs.contains(KEY_APP_LANGUAGE) ||
            prefs.contains("use_blur") ||
            prefs.contains("swipe_tabs") ||
            prefs.contains("default_hatch_mode") ||
            prefs.contains("selected_saf_folder_uri") ||
            StorageHelper.getGrantedFolderInfoFast(application) != null ||
            prefs.contains("operations_json")
        if (hasExistingUserState) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
            return true
        }
        return false
    }

    fun completeOnboarding() {
        _hasCompletedOnboarding.value = true
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    fun skipOnboarding() {
        completeOnboarding()
    }

    private fun loadSavedAccentPalette(): AppAccentPalette {
        val raw = prefs.getString("accent_palette", AppAccentPalette.OCEAN.name) ?: AppAccentPalette.OCEAN.name
        return try {
            AppAccentPalette.valueOf(raw)
        } catch (_: Exception) {
            AppAccentPalette.OCEAN
        }
    }

    private fun loadSavedThemeMode(): AppThemeMode {
        val raw = prefs.getString("theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        return try {
            AppThemeMode.valueOf(raw)
        } catch (_: Exception) {
            AppThemeMode.DARK
        }
    }

    private fun loadSavedAppLanguage(): AppLanguage {
        return LanguageHelper.getSavedLanguage(getApplication())
    }

    fun setAccentPalette(palette: AppAccentPalette) {
        _accentPalette.value = palette
        prefs.edit().putString("accent_palette", palette.name).apply()
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setAppLanguage(language: AppLanguage) {
        val app = getApplication<Application>()
        val systemSupported = LanguageHelper.isDeviceLanguageSupported(app)
        _isSystemLanguageSupported.value = systemSupported
        val effectiveLanguage = if (language == AppLanguage.SYSTEM && !systemSupported) {
            AppLanguage.ENGLISH
        } else {
            language
        }
        LanguageHelper.saveAndApplyLanguage(app, language)
        _appLanguage.value = effectiveLanguage
        viewModelScope.launch(Dispatchers.IO) {
            HatchNotificationHelper.ensureChannels(
                LanguageHelper.wrapContext(app, effectiveLanguage),
                forceUpdate = true
            )
        }
    }

    fun setBlurEnabled(enabled: Boolean) {
        _isBlurEnabled.value = enabled
        prefs.edit().putBoolean("use_blur", enabled).apply()
    }

    fun setSwipeTabsEnabled(enabled: Boolean) {
        _isSwipeTabsEnabled.value = enabled
        prefs.edit().putBoolean("swipe_tabs", enabled).apply()
    }

    fun switchTab(tab: ScreenTab) {
        if (tab != ScreenTab.SETTINGS) {
            lastNonSettingsTab = tab
            _activePageTab.value = tab
        }
        _currentTab.value = tab
    }

    fun openSettings() {
        if (_currentTab.value != ScreenTab.SETTINGS) {
            lastNonSettingsTab = _currentTab.value
            _activePageTab.value = _currentTab.value
        }
        _currentTab.value = ScreenTab.SETTINGS
    }

    fun openSpaceLabFromSettings() {
        if (_currentTab.value != ScreenTab.SETTINGS && _currentTab.value != ScreenTab.SPACE_LAB) {
            lastNonSettingsTab = _currentTab.value
        }
        _activePageTab.value = ScreenTab.SPACE_LAB
        _currentTab.value = ScreenTab.SPACE_LAB
    }

    fun returnFromLabToSettings() {
        if (lastNonSettingsTab == ScreenTab.SPACE_LAB || lastNonSettingsTab == ScreenTab.SETTINGS) {
            lastNonSettingsTab = ScreenTab.EXTRACT
        }
        _activePageTab.value = lastNonSettingsTab
        _currentTab.value = ScreenTab.SETTINGS
    }

    fun closeSettings() {
        val target = if (lastNonSettingsTab == ScreenTab.SETTINGS) ScreenTab.EXTRACT else lastNonSettingsTab
        _currentTab.value = target
        _activePageTab.value = target
    }

    fun refreshPermissionsState() {
        val app = getApplication<Application>()
        _notificationsEnabled.value = HatchNotificationHelper.hasNotificationPermission(app)
        _allFilesAccessEnabled.value = StorageHelper.hasAllFilesAccess()
        val systemSupported = LanguageHelper.isDeviceLanguageSupported(app)
        if (_isSystemLanguageSupported.value != systemSupported) {
            _isSystemLanguageSupported.value = systemSupported
        }
        val currentSavedLanguage = LanguageHelper.getSavedLanguage(app)
        if (_appLanguage.value != currentSavedLanguage) {
            _appLanguage.value = currentSavedLanguage
            LanguageHelper.applyLanguage(app, currentSavedLanguage)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val notifGranted = HatchNotificationHelper.hasNotificationPermission(app)
            val allFilesGranted = StorageHelper.hasAllFilesAccess()
            val folderInfo = StorageHelper.getGrantedFolderInfo(app)
            _notificationsEnabled.value = notifGranted
            _allFilesAccessEnabled.value = allFilesGranted
            _grantedFolder.value = folderInfo
        }
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_APP_LANGUAGE = LanguageHelper.KEY_APP_LANGUAGE
        const val UI_PROGRESS_MIN_INTERVAL_NS = 16_000_000L
    }

    private fun resolveCurrentDestinationCapacity(archiveName: String? = null): com.biplov.kaihatch.data.DestinationCapacityInfo {
        val app = getApplication<Application>()
        val granted = _grantedFolder.value
        val targetTreeUri = granted?.let { Uri.parse(it.uriString) }
        val cleanBase = (archiveName ?: _archiveSummary.value?.fileName.orEmpty())
            .removeSuffix(".tar.gz")
            .removeSuffix(".tgz")
            .removeSuffix(".7z")
            .removeSuffix(".apk")
            .substringBeforeLast('.')
            .ifEmpty { "archive" }
        val subfolderName = "${cleanBase}_extracted"
        val targetDir = if (targetTreeUri != null) {
            StorageHelper.tryResolveDirectTreeRootDir(targetTreeUri)?.let { File(it, subfolderName) }
                ?: File(StorageHelper.getExtractedDir(app), subfolderName)
        } else {
            File(StorageHelper.getExtractedDir(app), subfolderName)
        }
        return StorageHelper.queryDestinationCapacity(
            context = app,
            targetDir = targetDir,
            targetTreeUri = targetTreeUri
        )
    }

    private fun refreshArchiveStoragePlanAsync() {
        val currentSummary = _archiveSummary.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val cap = resolveCurrentDestinationCapacity(currentSummary.fileName)
            val updatedPlan = SpaceLabEngine.computePreExtractionStoragePlan(
                archiveSizeBytes = currentSummary.fileSizeBytes,
                entries = currentSummary.entries,
                format = currentSummary.format,
                destinationFreeBytes = cap.availableBytes,
                isDestinationCapacityReliable = cap.isReliable,
                isDirectPosixDestination = cap.isDirectPosix,
                hasEncryptedEntries = currentSummary.hasEncryptedEntries
            )
            val latest = _archiveSummary.value
            if (latest != null &&
                latest.fileName == currentSummary.fileName &&
                latest.fileSizeBytes == currentSummary.fileSizeBytes
            ) {
                _archiveSummary.value = latest.copy(
                    deviceFreeSpace = if (cap.isReliable && cap.availableBytes != null) cap.availableBytes else 0L,
                    storagePlan = updatedPlan
                )
            }
        }
    }

    fun onFolderPermissionGranted(treeUri: Uri) {
        val app = getApplication<Application>()
        val info = StorageHelper.saveGrantedFolder(app, treeUri)
        _grantedFolder.value = info
        _showPersistentFolderPrompt.value = false
        refreshArchiveStoragePlanAsync()
        refreshLocalFiles()
        if (autoStartExtractionAfterFolderGrant && _archiveSummary.value != null) {
            val pendingEntries = pendingExtractionSelectedEntries
            autoStartExtractionAfterFolderGrant = false
            pendingExtractionSelectedEntries = null
            startExtraction(selectedEntriesOnly = pendingEntries, requirePersistentFolder = false)
        } else if (autoStartCompressionAfterFolderGrant && _itemsToCompress.value.isNotEmpty()) {
            autoStartCompressionAfterFolderGrant = false
            startCompression(requirePersistentFolder = false)
        }
    }

    fun onPersistentFolderPickerLaunched() {
        _showPersistentFolderPrompt.value = false
    }

    fun dismissPersistentFolderPrompt() {
        _showPersistentFolderPrompt.value = false
        autoStartExtractionAfterFolderGrant = false
        pendingExtractionSelectedEntries = null
        autoStartCompressionAfterFolderGrant = false
    }

    fun onFolderPickerCancelled() {
        _showPersistentFolderPrompt.value = false
        autoStartExtractionAfterFolderGrant = false
        pendingExtractionSelectedEntries = null
        autoStartCompressionAfterFolderGrant = false
    }

    fun removeGrantedFolder() {
        val app = getApplication<Application>()
        StorageHelper.removeGrantedFolder(app, _grantedFolder.value?.uriString)
        _grantedFolder.value = null
        refreshArchiveStoragePlanAsync()
        refreshLocalFiles()
    }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _deviceFreeSpace.value = StorageHelper.getDeviceFreeBytes(app)
            _deviceTotalSpace.value = StorageHelper.getDeviceTotalBytes(app)
            refreshArchiveStoragePlanAsync()
        }
    }

    fun refreshPendingResumeStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val states = ResumeJournalManager.loadPendingStates(getApplication())
            _pendingResumeStates.value = states
        }
    }

    fun dismissResumeState(state: ResumeJournalState) {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        viewModelScope.launch(Dispatchers.IO) {
            ResumeJournalManager.deleteState(state.stateFilePath)
            refreshPendingResumeStates()
        }
    }

    fun clearTempCache(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            StorageHelper.getTempDir(app).deleteRecursively()
            StorageHelper.getTempDir(app)
            refreshStorage()
            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    fun refreshLocalFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshLocalFilesInternal()
        }
    }

    private suspend fun refreshLocalFilesInternal() = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val archivesDir = StorageHelper.getArchivesDir(app)
        val sampleDir = StorageHelper.getSampleArchivesDir(app)

        val validExts = listOf("zip", "7z", "rar", "apk", "xapk", "apks", "jar", "tar", "gz", "tgz", "001")
        val seenPaths = mutableSetOf<String>()
        val list = mutableListOf<File>()

        fun addUniqueArchive(file: File) {
            val key = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
            if (seenPaths.add(key)) {
                list.add(file)
            }
        }

        if (_grantedFolder.value != null) {
            try {
                val treeUri = Uri.parse(_grantedFolder.value!!.uriString)
                StorageHelper.listSafArchivesInGrantedFolder(treeUri).forEach { addUniqueArchive(it) }
            } catch (_: Throwable) {
            }
        }
        archivesDir.listFiles()?.filter {
            it.isFile && (it.extension.lowercase() in validExts || it.name.endsWith(".tar.gz", true))
        }?.forEach { addUniqueArchive(it) }
        sampleDir.listFiles()?.filter {
            it.isFile && (it.extension.lowercase() in validExts || it.name.endsWith(".tar.gz", true))
        }?.forEach { addUniqueArchive(it) }

        val sortedArchives = list.sortedByDescending { it.lastModified() }

        val safFolders = if (_grantedFolder.value != null) {
            try {
                val treeUri = Uri.parse(_grantedFolder.value!!.uriString)
                StorageHelper.listSafExtractedFolders(app, treeUri)
            } catch (_: Throwable) {
                emptyList()
            }
        } else emptyList()

        val localFolders = StorageHelper.listLocalExtractedFolders(app)
        val safNames = safFolders.map { it.name.lowercase() }.toSet()
        val allFolders = safFolders + localFolders.filter { it.name.lowercase() !in safNames }

        val updatedSizes = _fileSizesCache.value.toMutableMap()
        for (archive in sortedArchives) {
            updatedSizes[archive.absolutePath] = archive.length()
        }
        for (folder in allFolders) {
            updatedSizes[folder.id] = folder.totalSizeBytes
        }
        for (queued in _itemsToCompress.value) {
            if (!updatedSizes.containsKey(queued.absolutePath)) {
                updatedSizes[queued.absolutePath] = if (queued.isDirectory) {
                    StorageHelper.getDirectorySize(queued)
                } else {
                    queued.length()
                }
            }
        }

        _fileSizesCache.value = updatedSizes
        _availableArchives.value = sortedArchives
        _extractedFolders.value = allFolders
    }

    fun deleteArchive(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_selectedArchiveFile.value?.absolutePath == file.absolutePath) {
                _selectedArchiveFile.value = null
                _archiveSummary.value = null
            }
            if (_exploringArchive.value?.absolutePath == file.absolutePath) {
                _exploringArchive.value = null
                _explorerAllEntries.value = emptyList()
            }
            file.delete()
            refreshStorage()
            refreshLocalFiles()
        }
    }

    /**
     * Toggles expansion of a nested subfolder inside an extracted output folder.
     * State is updated synchronously so rapid repeated taps never queue animations or freeze the UI.
     * Subfolder contents are loaded lazily on [Dispatchers.IO] only on first expansion and cached in memory.
     */
    fun toggleSubfolderExpanded(subfolder: ExtractedFileEntry) {
        if (!subfolder.isDirectory) return
        val folderId = subfolder.id
        val currentExpanded = _expandedSubfolderIds.value
        if (folderId in currentExpanded) {
            _expandedSubfolderIds.value = currentExpanded - folderId
        } else {
            _expandedSubfolderIds.value = currentExpanded + folderId
            if (!_subfolderChildrenCache.value.containsKey(folderId)) {
                loadSubfolderChildrenAsync(subfolder)
            }
        }
    }

    fun collapseAllSubfolders() {
        _expandedSubfolderIds.value = emptySet()
    }

    /**
     * Pre-warms [subfolderChildrenCache] on [Dispatchers.IO] for up to 16 direct child subfolders
     * of an opened folder so tapping a subfolder starts its height transition on frame 0 without I/O delay.
     */
    fun prefetchSubfolderChildren(subfolders: List<ExtractedFileEntry>) {
        val currentCache = _subfolderChildrenCache.value
        val toPrefetch = subfolders
            .asSequence()
            .filter {
                it.isDirectory &&
                    !currentCache.containsKey(it.id) &&
                    subfolderLoadJobs[it.id]?.isActive != true
            }
            .take(16)
            .toList()
        if (toPrefetch.isEmpty()) return

        for (sub in toPrefetch) {
            val folderId = sub.id
            val job = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val children = StorageHelper.listExtractedSubfolderChildren(app, sub)
                    _subfolderChildrenCache.value = _subfolderChildrenCache.value + (folderId to children)
                } finally {
                    subfolderLoadJobs.remove(folderId)
                }
            }
            subfolderLoadJobs[folderId] = job
        }
    }

    private fun loadSubfolderChildrenAsync(subfolder: ExtractedFileEntry, forceReload: Boolean = false) {
        val folderId = subfolder.id
        if (!forceReload && _subfolderChildrenCache.value.containsKey(folderId)) return
        if (!forceReload && subfolderLoadJobs[folderId]?.isActive == true) return

        _loadingSubfolderIds.value = _loadingSubfolderIds.value + folderId
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val children = StorageHelper.listExtractedSubfolderChildren(app, subfolder)
                _subfolderChildrenCache.value = _subfolderChildrenCache.value + (folderId to children)
            } finally {
                _loadingSubfolderIds.value = _loadingSubfolderIds.value - folderId
                subfolderLoadJobs.remove(folderId)
            }
        }
        subfolderLoadJobs[folderId] = job
    }

    fun deleteExtractedFolder(folder: ExtractedFolderEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (folder.safUri != null) {
                StorageHelper.deleteSafDocument(app, folder.safUri)
            }
            folder.localFile?.deleteRecursively()
            val prefix = folder.localFile?.absolutePath ?: folder.id
            _expandedSubfolderIds.value = _expandedSubfolderIds.value.filterNot {
                it == prefix || it.startsWith("$prefix/")
            }.toSet()
            _subfolderChildrenCache.value = _subfolderChildrenCache.value.filterKeys {
                it != prefix && !it.startsWith("$prefix/")
            }
            refreshStorage()
            refreshLocalFiles()
        }
    }

    fun deleteExtractedFile(file: ExtractedFileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (file.safUri != null) {
                StorageHelper.deleteSafDocument(app, file.safUri)
            }
            if (file.isDirectory) {
                file.localFile?.deleteRecursively()
            } else {
                file.localFile?.delete()
            }

            // Immediately prune deleted entry (and any nested cached entries if it was a subfolder) from cache
            val updatedCache = _subfolderChildrenCache.value.toMutableMap()
            if (file.parentId.isNotEmpty() && updatedCache.containsKey(file.parentId)) {
                updatedCache[file.parentId] = updatedCache[file.parentId].orEmpty().filterNot { it.id == file.id }
            }
            if (file.isDirectory) {
                val dirPrefix = file.id
                val keysToRemove = updatedCache.keys.filter { it == dirPrefix || it.startsWith("$dirPrefix/") }
                keysToRemove.forEach { updatedCache.remove(it) }
                _expandedSubfolderIds.value = _expandedSubfolderIds.value.filterNot {
                    it == dirPrefix || it.startsWith("$dirPrefix/")
                }.toSet()
            }
            _subfolderChildrenCache.value = updatedCache

            refreshStorage()
            refreshLocalFiles()
        }
    }

    fun setHatchMode(enabled: Boolean) {
        _isHatchMode.value = enabled
        prefs.edit().putBoolean("default_hatch_mode", enabled).apply()
    }

    fun setArchivePassword(password: String) {
        _archivePassword.value = password
    }

    private var selectJob: Job? = null

    /**
     * Deselects / dismisses the currently selected archive on the Extract screen so the user
     * can return to the clean unselected state or pick a different file without extracting.
     */
    fun deselectArchive() {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        selectJob?.cancel()
        selectJob = null
        verifyJob?.cancel()
        verifyJob = null
        val isExtracting = _extractionProgress.value.status.isActiveSession
        if (isExtracting) {
            ArchiveJobRunner.activePauseController?.cancel()
            extractJob?.cancel()
            HatchNotificationHelper.cancelProgressNotification(getApplication())
        }
        _isFloatingControlsExpanded.value = false
        _showStopConfirmDialog.value = false
        _selectedArchiveFile.value = null
        _selectedSafUri.value = null
        _archiveSummary.value = null
        _archivePassword.value = ""
        _verificationState.value = ArchiveVerificationState()
        _extractionProgress.value = ExtractionProgress(journalLogs = _consoleLogs.value)
        closeExplorerArchive()
        dismissPersistentFolderPrompt()
    }

    fun dismissExtractionStatusCard() {
        _extractionProgress.value = ExtractionProgress(journalLogs = _consoleLogs.value)
    }

    fun selectArchive(file: File) {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        selectJob?.cancel()
        verifyJob?.cancel()
        verifyJob = null
        _verificationState.value = ArchiveVerificationState()
        if (_extractionProgress.value.status in listOf(
                ExtractionStatus.COMPLETED,
                ExtractionStatus.ERROR,
                ExtractionStatus.CANCELLED
            )
        ) {
            _extractionProgress.value = ExtractionProgress(journalLogs = _consoleLogs.value)
        }
        _selectedSafUri.value = null
        _selectedArchiveFile.value = file
        selectJob = viewModelScope.launch {
            try {
                val actualFile = withContext(Dispatchers.IO) {
                    if (file.name.matches(Regex(".*\\.\\d{3}$"))) {
                        StorageHelper.reassembleSplitVolumesInPlace(file)
                    } else {
                        file
                    }
                }
                _selectedArchiveFile.value = actualFile
                val summary = withContext(Dispatchers.IO) {
                    val cap = resolveCurrentDestinationCapacity(actualFile.name)
                    SpaceLabEngine.computeArchiveSummary(
                        file = actualFile,
                        deviceFreeBytes = cap.availableBytes ?: _deviceFreeSpace.value,
                        isDestinationCapacityReliable = cap.isReliable,
                        isDirectPosixDestination = cap.isDirectPosix
                    )
                }
                if (summary.entryCount == 0) {
                    val openFailureReason = withContext(Dispatchers.IO) {
                        when {
                            !actualFile.exists() -> "Archive file no longer exists on disk."
                            actualFile.length() == 0L -> "Archive file is empty (0 bytes)."
                            summary.format == ArchiveFormat.ZIP || summary.format == ArchiveFormat.APK -> {
                                try {
                                    java.io.RandomAccessFile(actualFile, "r").use { raf ->
                                        ArchiveVerifierEngine.validateZipEocdAndZip64Structure(raf.channel, raf.length())
                                    }
                                } catch (t: Throwable) {
                                    t.localizedMessage ?: t.javaClass.simpleName
                                }
                            }
                            else -> "No readable archive entries found; archive header or directory structure may be corrupted or truncated."
                        }
                    }
                    if (openFailureReason != null) {
                        val app = getApplication<Application>()
                        _selectedArchiveFile.value = null
                        _archiveSummary.value = null
                        val matchingPending = withContext(Dispatchers.IO) {
                            (_pendingResumeStates.value.ifEmpty { ResumeJournalManager.loadPendingStates(app) })
                                .firstOrNull { it.archivePath == actualFile.absolutePath || it.archiveName == actualFile.name }
                        }
                        val errMsg = app.getString(
                            R.string.error_inspect_archive_failed,
                            actualFile.name,
                            openFailureReason
                        )
                        val openLogs = buildList {
                            add("[Archive Open Error] Cannot open '${actualFile.name}' (${StorageHelper.formatBytes(actualFile.length())})")
                            add("[Cause] $openFailureReason")
                            if (matchingPending != null) {
                                add("[Recovery Notice] Interrupted Hatch Mode journal detected for '${matchingPending.archiveName}' — use the recovery card above to Resume or Restore Remaining ZIP.")
                            }
                        }
                        val mergedLogs = appendConsoleLines(openLogs, flushImmediately = true)
                        _extractionProgress.value = ExtractionProgress(
                            archiveName = actualFile.name,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            errorMessage = errMsg,
                            journalLogs = mergedLogs
                        )
                        return@launch
                    }
                }
                _archiveSummary.value = summary
                _exploringArchive.value = actualFile
                _exploringSafUri.value = null
                _exploringArchiveName.value = summary.fileName
                _currentFolderPath.value = ""
                _searchQuery.value = ""
                _selectedExplorerPaths.value = emptySet()
                _explorerAllEntries.value = summary.entries
                val mergedReadyLogs = appendConsoleLines(
                    listOf(
                        "[Ready] Indexed ${summary.fileName} (${summary.entryCount} entries)",
                        "[Telemetry] Archive: ${StorageHelper.formatBytes(summary.fileSizeBytes)} -> Unpacked: ${StorageHelper.formatBytes(summary.uncompressedSizeBytes)}",
                        "[Savings] In-Place ftruncate saves ${StorageHelper.formatBytes(summary.spaceSavedWithHatch)} peak disk"
                    ),
                    flushImmediately = false
                )
                _extractionProgress.value = _extractionProgress.value.copy(
                    archiveName = summary.fileName,
                    journalLogs = mergedReadyLogs
                )
            } catch (t: Throwable) {
                val app = getApplication<Application>()
                com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.onLowMemoryCleanup()
                val detail = t.localizedMessage ?: t.javaClass.simpleName
                val errMsg = app.getString(
                    R.string.error_inspect_archive_failed,
                    file.name,
                    detail
                )
                val matchingPending = _pendingResumeStates.value.firstOrNull {
                    it.archivePath == file.absolutePath || it.archiveName == file.name
                }
                val openLogs = buildList {
                    add("[Archive Open Error] Failed to inspect '${file.name}' (${StorageHelper.formatBytes(file.length())})")
                    add("[Cause] ${t.javaClass.simpleName}: $detail")
                    if (matchingPending != null) {
                        add("[Recovery Notice] Interrupted Hatch Mode journal detected for '${matchingPending.archiveName}' — use the recovery card above to Resume or Restore Remaining ZIP.")
                    }
                }
                val mergedLogs = appendConsoleLines(openLogs, flushImmediately = true)
                _extractionProgress.value = ExtractionProgress(
                    archiveName = file.name,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = errMsg,
                    journalLogs = mergedLogs
                )
            }
        }
    }

    /**
     * Zero-Copy Direct SAF URI selection:
     * Inspects the archive directly from its ParcelFileDescriptor (supporting 6GB+ .7z, 2.5GB+ .apk,
     * .zip, .tar, .tar.gz) without copying gigabytes into internal storage!
     */
    fun selectSafUriZeroCopy(uri: Uri) {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        val app = getApplication<Application>()
        selectJob?.cancel()
        verifyJob?.cancel()
        verifyJob = null
        _verificationState.value = ArchiveVerificationState()
        if (_extractionProgress.value.status in listOf(
                ExtractionStatus.COMPLETED,
                ExtractionStatus.ERROR,
                ExtractionStatus.CANCELLED
            )
        ) {
            _extractionProgress.value = ExtractionProgress(journalLogs = _consoleLogs.value)
        }
        _selectedArchiveFile.value = null
        _selectedSafUri.value = uri
        selectJob = viewModelScope.launch {
            try {
                // When direct filesystem access is permitted (e.g., optional All Files Access granted)
                // and the picked URI strictly proves identity + rw + fsync against a real local file,
                // prefer direct POSIX access over SAF Binder/FUSE indirection.
                val verifiedDirectFile = withContext(Dispatchers.IO) {
                    StorageHelper.tryResolveVerifiedDirectSourceFile(app, uri)
                }
                if (verifiedDirectFile != null) {
                    selectArchive(verifiedDirectFile)
                    return@launch
                }

                val zeroCopySummary = withContext(Dispatchers.IO) {
                    val cap = resolveCurrentDestinationCapacity(StorageHelper.queryFileName(app, uri))
                    SpaceLabEngine.computeSafUriSummary(
                        context = app,
                        uri = uri,
                        deviceFreeBytes = cap.availableBytes ?: _deviceFreeSpace.value,
                        isDestinationCapacityReliable = cap.isReliable,
                        isDirectPosixDestination = cap.isDirectPosix
                    )
                }
                if (zeroCopySummary != null) {
                    _selectedArchiveFile.value = null
                    _selectedSafUri.value = uri
                    _archiveSummary.value = zeroCopySummary
                    _exploringArchive.value = null
                    _exploringSafUri.value = uri
                    _exploringArchiveName.value = zeroCopySummary.fileName
                    _currentFolderPath.value = ""
                    _searchQuery.value = ""
                    _selectedExplorerPaths.value = emptySet()
                    _explorerAllEntries.value = zeroCopySummary.entries
                    val mergedSafLogs = appendConsoleLines(
                        listOf(
                            "[Zero-Copy SAF] Direct descriptor bound: ${zeroCopySummary.fileName} (${zeroCopySummary.entryCount} entries)",
                            "[Telemetry] Archive: ${StorageHelper.formatBytes(zeroCopySummary.fileSizeBytes)} -> Unpacked: ${StorageHelper.formatBytes(zeroCopySummary.uncompressedSizeBytes)}",
                            "[Savings] In-Place ftruncate saves ${StorageHelper.formatBytes(zeroCopySummary.spaceSavedWithHatch)} peak disk"
                        ),
                        flushImmediately = false
                    )
                    _extractionProgress.value = _extractionProgress.value.copy(
                        archiveName = zeroCopySummary.fileName,
                        journalLogs = mergedSafLogs
                    )
                } else {
                    // Only fallback-copy small cloud stream URIs (<100 MB) that lack a seekable FileChannel.
                    // Never blindly copy multi-GB files into internal storage!
                    val uriSize = withContext(Dispatchers.IO) { StorageHelper.queryUriSize(app, uri) }
                    if (uriSize in 1L..(100L * 1024L * 1024L)) {
                        val copied = withContext(Dispatchers.IO) {
                            val destDir = StorageHelper.getArchivesDir(app)
                            StorageHelper.copyUriToLocalFile(app, uri, destDir)
                        }
                        if (copied != null) {
                            refreshLocalFiles()
                            selectArchive(copied)
                            return@launch
                        }
                    }
                    val name = withContext(Dispatchers.IO) {
                        StorageHelper.queryFileName(app, uri)
                    } ?: app.getString(R.string.error_selected_file_fallback)
                    val errMsg = app.getString(R.string.error_unrecognized_archive, name)
                    val matchingPending = _pendingResumeStates.value.firstOrNull {
                        it.sourceUriString == uri.toString() || it.archiveName == name
                    }
                    val openLogs = buildList {
                        add("[Archive Open Error] Unrecognized or unreadable archive structure in '$name'")
                        add("[Source] SAF URI (${if (uriSize > 0L) StorageHelper.formatBytes(uriSize) else "size unavailable"})")
                        if (matchingPending != null) {
                            add("[Recovery Notice] Interrupted Hatch Mode journal detected for '${matchingPending.archiveName}' — use the recovery card above to Resume or Restore Remaining ZIP.")
                        }
                    }
                    val mergedLogs = appendConsoleLines(openLogs, flushImmediately = true)
                    _extractionProgress.value = ExtractionProgress(
                        archiveName = name,
                        isZeroCopySaf = true,
                        status = ExtractionStatus.ERROR,
                        isCrcValid = false,
                        errorMessage = errMsg,
                        journalLogs = mergedLogs
                    )
                }
            } catch (t: Throwable) {
                com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.onLowMemoryCleanup()
                val name = withContext(Dispatchers.IO) {
                    StorageHelper.queryFileName(app, uri)
                } ?: app.getString(R.string.error_selected_file_fallback)
                val detail = t.localizedMessage ?: t.javaClass.simpleName
                val errMsg = app.getString(
                    R.string.error_inspect_uri_failed,
                    name,
                    detail
                )
                val matchingPending = _pendingResumeStates.value.firstOrNull {
                    it.sourceUriString == uri.toString() || it.archiveName == name
                }
                val openLogs = buildList {
                    add("[Archive Open Error] Failed to inspect SAF URI '$name'")
                    add("[Cause] ${t.javaClass.simpleName}: $detail")
                    if (matchingPending != null) {
                        add("[Recovery Notice] Interrupted Hatch Mode journal detected for '${matchingPending.archiveName}' — use the recovery card above to Resume or Restore Remaining ZIP.")
                    }
                }
                val mergedLogs = appendConsoleLines(openLogs, flushImmediately = true)
                _extractionProgress.value = ExtractionProgress(
                    archiveName = name,
                    isZeroCopySaf = true,
                    status = ExtractionStatus.ERROR,
                    isCrcValid = false,
                    errorMessage = errMsg,
                    journalLogs = mergedLogs
                )
            }
        }
    }

    /**
     * Runs a fast structural & header integrity check (`QUICK_CHECK`) on the currently selected archive.
     */
    fun verifySelectedArchiveIntegrity() {
        startArchiveVerification(VerificationLevel.QUICK_CHECK)
    }

    /**
     * Starts a read-only pre-extraction verification job at the requested [VerificationLevel]:
     * - [VerificationLevel.QUICK_CHECK]: Validates EOCD/ZIP64 structures, Central Directory, Local File Headers,
     *   TAR 512B header checksums, 7z NextHeader bounds, and entry offset spans without decompressing payloads.
     * - [VerificationLevel.FULL_VERIFY]: Streams and decompresses payloads with bounded memory to verify
     *   per-entry CRC32 checksums where supported.
     */
    fun startArchiveVerification(level: VerificationLevel) {
        val summary = _archiveSummary.value ?: return
        val app = getApplication<Application>()
        val pass = _archivePassword.value.ifEmpty { null }
        val localFile = _selectedArchiveFile.value
        val safUri = _selectedSafUri.value

        val priorState = _verificationState.value
        val preservedQuickStatus = if (
            priorState.quickCheckStatus == VerificationStatus.RUNNING ||
            priorState.isSourceModifiedSinceVerify
        ) {
            VerificationStatus.IDLE
        } else {
            priorState.quickCheckStatus
        }
        val preservedFullStatus = if (
            priorState.fullVerifyStatus == VerificationStatus.RUNNING ||
            priorState.isSourceModifiedSinceVerify
        ) {
            VerificationStatus.IDLE
        } else {
            priorState.fullVerifyStatus
        }

        val levelLabel = if (level == VerificationLevel.QUICK_CHECK) {
            "Quick Check (structure & headers)"
        } else {
            "Full Verify (stream & CRC32)"
        }
        val startLogs = appendConsoleLines(
            listOf("[Verify Start] ${summary.fileName} (${summary.format.label}): Starting $levelLabel..."),
            flushImmediately = false
        )
        _extractionProgress.value = _extractionProgress.value.copy(journalLogs = startLogs)

        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            try {
                val flow = when {
                    localFile != null -> ArchiveVerifierEngine.verifyArchive(
                        archiveFile = localFile,
                        level = level,
                        password = pass,
                        context = app
                    )
                    safUri != null -> ArchiveVerifierEngine.verifySafUri(
                        context = app,
                        uri = safUri,
                        archiveName = summary.fileName,
                        level = level,
                        password = pass
                    )
                    else -> return@launch
                }

                flow.collect { state ->
                    val updatedState = state.copy(
                        quickCheckStatus = if (level == VerificationLevel.QUICK_CHECK) state.status else preservedQuickStatus,
                        fullVerifyStatus = if (level == VerificationLevel.FULL_VERIFY) state.status else preservedFullStatus
                    )
                    _verificationState.value = updatedState
                    if (state.status != VerificationStatus.IDLE && state.status != VerificationStatus.RUNNING) {
                        val tag = when (state.status) {
                            VerificationStatus.PASSED_STRUCTURAL -> "[Quick Check OK]"
                            VerificationStatus.PASSED_FULL_INTEGRITY -> "[Full Verify OK]"
                            VerificationStatus.UNSUPPORTED -> "[Verify Unsupported]"
                            VerificationStatus.FAILED -> "[Verify FAILED]"
                            VerificationStatus.CANCELLED -> "[Verify Cancelled]"
                            else -> "[Verify]"
                        }
                        val logEntry = buildString {
                            append("$tag ${summary.fileName} (${state.elapsedTimeMs} ms): ${state.summaryMessage}")
                            if (!state.corruptedEntryPath.isNullOrEmpty()) {
                                append(" [Entry: ${state.corruptedEntryPath}]")
                            }
                            if (!state.detailMessage.isNullOrEmpty()) {
                                append(" — ${state.detailMessage}")
                            }
                        }
                        val updatedLogs = appendConsoleLines(listOf(logEntry), flushImmediately = true)
                        _extractionProgress.value = _extractionProgress.value.copy(
                            isCrcValid = state.status != VerificationStatus.FAILED,
                            journalLogs = updatedLogs
                        )
                    }
                }
            } catch (_: CancellationException) {
                val prev = _verificationState.value
                _verificationState.value = prev.copy(
                    status = VerificationStatus.CANCELLED,
                    quickCheckStatus = if (level == VerificationLevel.QUICK_CHECK) VerificationStatus.CANCELLED else preservedQuickStatus,
                    fullVerifyStatus = if (level == VerificationLevel.FULL_VERIFY) VerificationStatus.CANCELLED else preservedFullStatus,
                    summaryMessage = "Verification cancelled",
                    detailMessage = "Verification stopped at ${prev.processedEntries}/${prev.totalEntries} entries."
                )
            }
        }
    }

    fun cancelArchiveVerification() {
        verifyJob?.cancel()
        verifyJob = null
        val prev = _verificationState.value
        if (prev.isRunning) {
            val activeQuick = prev.quickCheckStatus == VerificationStatus.RUNNING ||
                (prev.level == VerificationLevel.QUICK_CHECK && prev.status == VerificationStatus.RUNNING)
            val activeFull = prev.fullVerifyStatus == VerificationStatus.RUNNING ||
                (prev.level == VerificationLevel.FULL_VERIFY && prev.status == VerificationStatus.RUNNING)
            _verificationState.value = prev.copy(
                status = VerificationStatus.CANCELLED,
                quickCheckStatus = if (activeQuick) VerificationStatus.CANCELLED else prev.quickCheckStatus,
                fullVerifyStatus = if (activeFull) VerificationStatus.CANCELLED else prev.fullVerifyStatus,
                summaryMessage = "Verification cancelled",
                detailMessage = "Verification stopped at ${prev.processedEntries}/${prev.totalEntries} entries."
            )
            val updatedLogs = appendConsoleLines(
                listOf("[Verify Cancelled] ${prev.archiveName}: Stopped at ${prev.processedEntries}/${prev.totalEntries} entries (${prev.elapsedTimeMs} ms)"),
                flushImmediately = true
            )
            _extractionProgress.value = _extractionProgress.value.copy(journalLogs = updatedLogs)
        }
    }

    fun dismissVerificationResult() {
        verifyJob?.cancel()
        verifyJob = null
        _verificationState.value = ArchiveVerificationState()
    }

    /**
     * Checks whether the currently selected source archive has changed on disk since verification completed.
     */
    suspend fun checkVerificationSourceFreshness(): Boolean = withContext(Dispatchers.IO) {
        val currentVerify = _verificationState.value
        val fp = currentVerify.sourceFingerprint ?: return@withContext false
        val changed = ArchiveVerifierEngine.hasSourceChangedSinceVerification(
            fingerprint = fp,
            archiveFile = _selectedArchiveFile.value,
            context = getApplication(),
            safUri = _selectedSafUri.value
        )
        if (changed && !currentVerify.isSourceModifiedSinceVerify) {
            _verificationState.value = currentVerify.copy(
                status = VerificationStatus.IDLE,
                quickCheckStatus = VerificationStatus.IDLE,
                fullVerifyStatus = VerificationStatus.IDLE,
                isSourceModifiedSinceVerify = true,
                summaryMessage = "Source archive modified since verification",
                detailMessage = "Archive size, timestamp, or header/trailer bytes changed after verification. Re-verify before Hatch."
            )
            val updatedLogs = appendConsoleLines(
                listOf("[Verify Invalidated] ${currentVerify.archiveName}: Source archive modified after verification; verification status invalidated."),
                flushImmediately = true
            )
            _extractionProgress.value = _extractionProgress.value.copy(journalLogs = updatedLogs)
        }
        changed
    }

    fun startExtraction(selectedEntriesOnly: Set<String>? = null, requirePersistentFolder: Boolean = true) {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        val summary = _archiveSummary.value ?: return
        val app = getApplication<Application>()

        // Cancel any running pre-extraction verification job before starting extraction
        if (verifyJob?.isActive == true) {
            verifyJob?.cancel()
            verifyJob = null
        }

        // Always refresh granted folder state before checking
        if (_grantedFolder.value == null) {
            _grantedFolder.value = StorageHelper.getGrantedFolderInfo(app)
        }

        // Mandatory check when clicking Extract: if Persistent Folder Access is not selected,
        // prompt the user to select it first, and automatically proceed once selected.
        if (requirePersistentFolder && _grantedFolder.value == null) {
            pendingExtractionSelectedEntries = selectedEntriesOnly
            autoStartExtractionAfterFolderGrant = true
            _showPersistentFolderPrompt.value = true
            return
        }

        _showPersistentFolderPrompt.value = false
        autoStartExtractionAfterFolderGrant = false
        pendingExtractionSelectedEntries = null

        val cleanBase = summary.fileName
            .removeSuffix(".tar.gz")
            .removeSuffix(".tgz")
            .removeSuffix(".7z")
            .removeSuffix(".apk")
            .substringBeforeLast('.')

        val granted = _grantedFolder.value
        val targetTreeUri = granted?.let { Uri.parse(it.uriString) }
        val startTime = System.currentTimeMillis()
        val originalArchiveSize = summary.fileSizeBytes
        val hatchMode = _isHatchMode.value && selectedEntriesOnly == null && summary.format != ArchiveFormat.TAR_GZ
        val pass = _archivePassword.value.ifEmpty { null }

        // Immediately reflect active state so UI never lags while resolving SAF directories
        _lastCompletedTarget.value = null
        _isFloatingControlsExpanded.value = false
        _showStopConfirmDialog.value = false
        HatchNotificationHelper.cancelCompletionNotification(app)
        val runId = consoleRunIdCounter.incrementAndGet()
        val initialLogs = appendConsoleLines(
            listOf("[Extract Start] ${summary.fileName}: Preparing output directory and initializing descriptor..."),
            flushImmediately = false
        )
        _extractionProgress.value = ExtractionProgress(
            archiveName = summary.fileName,
            isHatchMode = hatchMode,
            isZeroCopySaf = _selectedSafUri.value != null && summary.isZeroCopyDirectUri,
            isZip64 = summary.isZip64,
            status = ExtractionStatus.ANALYZING,
            totalEntries = summary.entryCount,
            originalArchiveBytes = originalArchiveSize,
            currentArchiveBytesOnDisk = originalArchiveSize,
            totalUncompressedBytes = summary.uncompressedSizeBytes,
            journalLogs = initialLogs,
            startTime = startTime
        )

        extractJob?.cancel()
        val locCtx = localizedAppContext
        val entryLabel = if (summary.entryCount == 1) {
            locCtx.getString(R.string.notif_count_one_file)
        } else {
            locCtx.getString(R.string.notif_count_many_files, summary.entryCount)
        }
        val taskTitle = locCtx.getString(R.string.notif_extracting_title, entryLabel)
        extractJob = ArchiveJobRunner.launchExtractionJob(
            context = app,
            title = taskTitle,
            subtitle = summary.fileName
        ) { pauseController ->
            // Detect if the source archive was modified between verification and extraction
            val verifySnapshot = _verificationState.value
            val verifyFp = verifySnapshot.sourceFingerprint
            if (verifyFp != null &&
                (verifySnapshot.status == VerificationStatus.PASSED_STRUCTURAL ||
                    verifySnapshot.status == VerificationStatus.PASSED_FULL_INTEGRITY ||
                    verifySnapshot.quickCheckStatus == VerificationStatus.PASSED_STRUCTURAL ||
                    verifySnapshot.fullVerifyStatus == VerificationStatus.PASSED_FULL_INTEGRITY)
            ) {
                val sourceMutated = ArchiveVerifierEngine.hasSourceChangedSinceVerification(
                    fingerprint = verifyFp,
                    archiveFile = _selectedArchiveFile.value,
                    context = app,
                    safUri = _selectedSafUri.value
                )
                if (sourceMutated) {
                    _verificationState.value = verifySnapshot.copy(
                        status = VerificationStatus.IDLE,
                        quickCheckStatus = VerificationStatus.IDLE,
                        fullVerifyStatus = VerificationStatus.IDLE,
                        isSourceModifiedSinceVerify = true,
                        summaryMessage = "Source archive modified since verification",
                        detailMessage = "Archive changed on disk after verification completed. Please re-verify before extraction."
                    )
                    if (hatchMode) {
                        val errMsg = "Source archive '${summary.fileName}' was modified after verification completed. Aborting In-Place Hatch extraction to protect your archive; please re-verify or re-select the archive."
                        val mergedLogs = appendConsoleLines(listOf("[Verify Guard] $errMsg"), flushImmediately = true)
                        val abortedProgress = ExtractionProgress(
                            archiveName = summary.fileName,
                            isHatchMode = true,
                            status = ExtractionStatus.ERROR,
                            isCrcValid = false,
                            originalArchiveBytes = originalArchiveSize,
                            currentArchiveBytesOnDisk = originalArchiveSize,
                            errorMessage = errMsg,
                            journalLogs = mergedLogs
                        )
                        _extractionProgress.value = abortedProgress
                        ArchiveJobRunner.updateExtractionProgress(abortedProgress)
                        return@launchExtractionJob
                    }
                }
            }

            StorageHelper.clearSafDirCache()

            val subfolderName = "${cleanBase}_extracted"
            val targetFolderDocUri = if (targetTreeUri != null) {
                StorageHelper.getOrCreateDocumentSubfolder(app, targetTreeUri, subfolderName)
            } else null

            val targetDir = if (targetTreeUri != null) {
                StorageHelper.tryResolveDirectWritableSubfolder(targetTreeUri, subfolderName)
                    ?: File(StorageHelper.getExtractedDir(app), subfolderName)
            } else {
                File(StorageHelper.getExtractedDir(app), subfolderName)
            }

            val flow = if (_selectedSafUri.value != null && summary.isZeroCopyDirectUri) {
                KaiHatchEngine.extractSafUriZeroCopy(
                    context = app,
                    uri = _selectedSafUri.value!!,
                    archiveName = summary.fileName,
                    entries = summary.entries,
                    targetDir = targetDir,
                    isHatchMode = hatchMode,
                    password = pass,
                    selectedEntryPaths = selectedEntriesOnly,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    pauseController = pauseController
                )
            } else {
                val file = _selectedArchiveFile.value ?: return@launchExtractionJob
                KaiHatchEngine.extractArchive(
                    archiveFile = file,
                    targetDir = targetDir,
                    isHatchMode = hatchMode,
                    password = pass,
                    selectedEntryPaths = selectedEntriesOnly,
                    context = app,
                    targetTreeUri = targetTreeUri,
                    targetFolderDocUri = targetFolderDocUri,
                    pauseController = pauseController
                )
            }

            flow.collect { progress ->
                handleExtractionProgressUpdate(
                    progress = progress,
                    archiveName = summary.fileName,
                    targetDir = targetDir,
                    targetFolderDocUri = targetFolderDocUri,
                    originalArchiveSize = originalArchiveSize,
                    hatchMode = hatchMode,
                    startTime = startTime,
                    runId = runId
                )
            }
        }
    }

    fun resumeInterruptedTask(resumeState: ResumeJournalState) {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        val app = getApplication<Application>()
        val startTime = System.currentTimeMillis()
        val pass = _archivePassword.value.ifEmpty { resumeState.password }

        _lastCompletedTarget.value = null
        _isFloatingControlsExpanded.value = false
        _showStopConfirmDialog.value = false
        HatchNotificationHelper.cancelCompletionNotification(app)
        val runId = consoleRunIdCounter.incrementAndGet()
        appendConsoleLines(
            listOf("[Recovery] Resuming interrupted extraction for '${resumeState.archiveName}' (${resumeState.remainingEntries.size} remaining entries)..."),
            flushImmediately = true
        )
        extractJob?.cancel()
        val locCtx = localizedAppContext
        val remainingCount = resumeState.remainingEntries.size
        val resumeLabel = if (remainingCount == 1) {
            locCtx.getString(R.string.notif_count_one_file)
        } else {
            locCtx.getString(R.string.notif_count_many_files, remainingCount)
        }
        extractJob = ArchiveJobRunner.launchExtractionJob(
            context = app,
            title = locCtx.getString(R.string.notif_extracting_title, resumeLabel),
            subtitle = resumeState.archiveName
        ) { pauseController ->
            val resumeTargetDir = File(resumeState.targetDirPath)
            val resumeTreeUri = resumeState.targetTreeUriString?.takeIf { it.isNotEmpty() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            } ?: _grantedFolder.value?.let { Uri.parse(it.uriString) }
            val resumeFolderDocUri = resumeState.targetFolderDocUriString?.takeIf { it.isNotEmpty() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            } ?: if (resumeTreeUri != null && resumeTargetDir.name.endsWith("_extracted")) {
                runCatching {
                    StorageHelper.getOrCreateDocumentSubfolder(app, resumeTreeUri, resumeTargetDir.name)
                }.getOrNull()
            } else null

            KaiHatchEngine.resumeInterruptedExtraction(
                context = app,
                resumeState = resumeState,
                password = pass,
                pauseController = pauseController
            ).collect { progress ->
                handleExtractionProgressUpdate(
                    progress = progress,
                    archiveName = resumeState.archiveName,
                    targetDir = resumeTargetDir,
                    targetFolderDocUri = resumeFolderDocUri,
                    originalArchiveSize = resumeState.originalArchiveBytes,
                    hatchMode = true,
                    startTime = startTime,
                    runId = runId
                )
            }
        }
    }

    fun restoreInterruptedZip(resumeState: ResumeJournalState) {
        if (_extractionProgress.value.status == ExtractionStatus.FINALIZING) return
        val app = getApplication<Application>()
        val locCtx = localizedAppContext
        val startTime = System.currentTimeMillis()
        _lastCompletedTarget.value = null
        _isFloatingControlsExpanded.value = false
        _showStopConfirmDialog.value = false
        HatchNotificationHelper.cancelCompletionNotification(app)
        val runId = consoleRunIdCounter.incrementAndGet()
        val restoreStartLogs = appendConsoleLines(
            listOf("[RESTORE] Starting in-place ZIP restoration for '${resumeState.archiveName}'…"),
            flushImmediately = true
        )
        val finalizingProgress = ExtractionProgress(
            archiveName = resumeState.archiveName,
            isHatchMode = true,
            isZeroCopySaf = !resumeState.sourceUriString.isNullOrEmpty(),
            isZip64 = resumeState.remainingEntries.any { it.isZip64 },
            status = ExtractionStatus.FINALIZING,
            totalEntries = resumeState.totalEntries,
            completedEntries = resumeState.completedPaths.size,
            currentFileName = "Finalizing remaining archive…",
            originalArchiveBytes = resumeState.originalArchiveBytes,
            currentArchiveBytesOnDisk = resumeState.currentArchiveBytes,
            bytesReclaimed = resumeState.bytesReclaimedSoFar,
            journalLogs = restoreStartLogs,
            startTime = startTime,
            isPauseSupported = false
        )
        _extractionProgress.value = finalizingProgress
        ArchiveJobRunner.updateExtractionProgress(finalizingProgress)

        extractJob?.cancel()
        extractJob = ArchiveJobRunner.launchExtractionJob(
            context = app,
            title = locCtx.getString(R.string.extract_hud_finalizing_title),
            subtitle = resumeState.archiveName
        ) { _ ->
            val resumeTargetDir = File(resumeState.targetDirPath)
            val resumeFolderDocUri = resumeState.targetFolderDocUriString?.takeIf { it.isNotEmpty() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            }
            KaiHatchEngine.restoreInterruptedZipArchive(
                context = app,
                resumeState = resumeState
            ).collect { progress ->
                handleExtractionProgressUpdate(
                    progress = progress,
                    archiveName = resumeState.archiveName,
                    targetDir = resumeTargetDir,
                    targetFolderDocUri = resumeFolderDocUri,
                    originalArchiveSize = resumeState.originalArchiveBytes,
                    hatchMode = true,
                    startTime = startTime,
                    runId = runId
                )
            }
        }
    }

    private suspend fun handleExtractionProgressUpdate(
        progress: ExtractionProgress,
        archiveName: String,
        targetDir: File,
        targetFolderDocUri: Uri?,
        originalArchiveSize: Long,
        hatchMode: Boolean,
        startTime: Long
    ) {
        handleExtractionProgressUpdate(
            progress = progress,
            archiveName = archiveName,
            targetDir = targetDir,
            targetFolderDocUri = targetFolderDocUri,
            originalArchiveSize = originalArchiveSize,
            hatchMode = hatchMode,
            startTime = startTime,
            runId = consoleRunIdCounter.get()
        )
    }

    private suspend fun handleExtractionProgressUpdate(
        progress: ExtractionProgress,
        archiveName: String,
        targetDir: File,
        targetFolderDocUri: Uri?,
        originalArchiveSize: Long,
        hatchMode: Boolean,
        startTime: Long,
        runId: Long
    ) {
        val app = getApplication<Application>()
        val previous = _extractionProgress.value
        val nowNs = System.nanoTime()

        val flushImmediately = progress.status in listOf(
            ExtractionStatus.COMPLETED,
            ExtractionStatus.ERROR,
            ExtractionStatus.CANCELLED,
            ExtractionStatus.PAUSED,
            ExtractionStatus.FINALIZING
        )
        val (snapshotAdded, logsAfterSnapshot) = appendEngineConsoleSnapshot(
            runId = runId,
            snapshot = progress.journalLogs,
            flushImmediately = flushImmediately
        )
        val mergedLogs = if (progress.status == ExtractionStatus.ERROR && progress.journalLogs.isEmpty()) {
            val errLine = "[ERROR] ${progress.errorMessage ?: "Operation failed unexpectedly."}"
            appendConsoleLines(listOf(errLine), flushImmediately = true)
        } else {
            logsAfterSnapshot
        }

        val isIntermediateByteTickOnly =
            (progress.status == ExtractionStatus.EXTRACTING || progress.status == ExtractionStatus.TRUNCATING) &&
                progress.status == previous.status &&
                progress.completedEntries == previous.completedEntries &&
                progress.currentFileName == previous.currentFileName &&
                !snapshotAdded

        if (progress.status == ExtractionStatus.COMPLETED) {
            val canonicalPath = runCatching { targetDir.canonicalPath }.getOrNull() ?: targetDir.absolutePath
            val docId = targetFolderDocUri?.let {
                runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
            }
            // Reset nested subfolder expansion and cache so only the main output folder auto-expands
            // with all nested subfolders collapsed.
            _expandedSubfolderIds.value = emptySet()
            _subfolderChildrenCache.value = emptyMap()
            _loadingSubfolderIds.value = emptySet()
            _lastCompletedTarget.value = CompletedExtractionTarget(
                completionId = extractionCompletionCounter.incrementAndGet(),
                folderDocUriString = targetFolderDocUri?.toString(),
                folderDocumentId = docId,
                canonicalDirPath = canonicalPath,
                absoluteDirPath = targetDir.absolutePath,
                outputDirectory = progress.outputDirectory,
                folderName = targetDir.name.ifEmpty { progress.outputDirectory?.substringAfterLast('/') ?: "" }
            )
            refreshStorage()
            refreshLocalFilesInternal()
            refreshPendingResumeStates()
        }

        val normalizedProgress = progress.copy(journalLogs = mergedLogs)

        if (!isIntermediateByteTickOnly || (nowNs - lastExtractionUiUpdateNs) >= UI_PROGRESS_MIN_INTERVAL_NS) {
            lastExtractionUiUpdateNs = nowNs
            _extractionProgress.value = normalizedProgress
        }
        ArchiveJobRunner.updateExtractionProgress(normalizedProgress)

        when (progress.status) {
            ExtractionStatus.ANALYZING, ExtractionStatus.EXTRACTING, ExtractionStatus.TRUNCATING -> {
                if (!HatchNotificationHelper.shouldDispatchLiveUpdate()) return
                val locCtx = localizedAppContext
                val speedText = if (progress.bytesPerSecond > 0L) {
                    "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s"
                } else ""
                val countText = when {
                    progress.totalEntries == 1 -> locCtx.getString(R.string.notif_count_one_file)
                    progress.totalEntries > 1 -> locCtx.getString(R.string.notif_count_many_files, progress.totalEntries)
                    else -> archiveName
                }
                val notifTitle = locCtx.getString(R.string.notif_extracting_title, countText)
                val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { archiveName }
                val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                val remainingLine = HatchNotificationHelper.formatRemainingLine(
                    remainingBytes = remainingBytes,
                    bytesPerSecond = progress.bytesPerSecond,
                    context = locCtx
                )
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = locCtx,
                        title = notifTitle,
                        subtitle = fileLine,
                        detailBigText = remainingLine,
                        progressFraction = progress.overallProgressFraction,
                        speedText = speedText,
                        indeterminate = progress.status == ExtractionStatus.ANALYZING,
                        alreadyThrottled = true
                    )
                }
            }

            ExtractionStatus.PAUSING -> {
                val locCtx = localizedAppContext
                val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { archiveName }
                val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = locCtx,
                        title = locCtx.getString(R.string.notif_pausing_title),
                        subtitle = fileLine,
                        detailBigText = locCtx.getString(
                            R.string.notif_pausing_detail,
                            StorageHelper.formatBytes(remainingBytes)
                        ),
                        progressFraction = progress.overallProgressFraction,
                        speedText = locCtx.getString(R.string.extract_hud_prefix_pausing),
                        indeterminate = false,
                        forceUpdate = true
                    )
                }
            }

            ExtractionStatus.PAUSED -> {
                val locCtx = localizedAppContext
                val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { archiveName }
                val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = locCtx,
                        title = locCtx.getString(R.string.notif_paused_title),
                        subtitle = fileLine,
                        detailBigText = locCtx.getString(
                            R.string.notif_paused_detail,
                            (progress.overallProgressFraction * 100f).toInt(),
                            StorageHelper.formatBytes(remainingBytes)
                        ),
                        progressFraction = progress.overallProgressFraction,
                        speedText = locCtx.getString(R.string.extract_hud_prefix_paused),
                        indeterminate = false,
                        forceUpdate = true
                    )
                }
            }

            ExtractionStatus.FINALIZING -> {
                _isFloatingControlsExpanded.value = false
                _showStopConfirmDialog.value = false
                val locCtx = localizedAppContext
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = locCtx,
                        title = locCtx.getString(R.string.extract_hud_finalizing_title),
                        subtitle = archiveName,
                        detailBigText = locCtx.getString(R.string.extract_hud_finalizing_subtitle),
                        progressFraction = progress.overallProgressFraction,
                        speedText = locCtx.getString(R.string.extract_hud_prefix_finalizing),
                        indeterminate = true,
                        forceUpdate = true
                    )
                }
            }

            ExtractionStatus.COMPLETED -> {
                _isFloatingControlsExpanded.value = false
                _showStopConfirmDialog.value = false
                if (progress.isHatchMode) {
                    _selectedArchiveFile.value = null
                    _selectedSafUri.value = null
                    _archiveSummary.value = null
                    closeExplorerArchive()
                }

                val duration = System.currentTimeMillis() - startTime
                val outDir = progress.outputDirectory
                val finalDestination = if (!outDir.isNullOrEmpty()) {
                    outDir
                } else {
                    targetDir.absolutePath
                }
                historyRepo.addRecord(
                    OperationRecord(
                        id = UUID.randomUUID().toString(),
                        type = if (progress.isHatchMode) "HATCH_EXTRACT" else "STANDARD_EXTRACT",
                        sourceName = archiveName,
                        targetPath = finalDestination,
                        originalSize = originalArchiveSize,
                        reclaimedOrSavedSize = if (progress.isHatchMode) originalArchiveSize else 0L,
                        entryCount = progress.totalEntries,
                        timestamp = System.currentTimeMillis(),
                        durationMs = duration,
                        success = true
                    )
                )

                val locCtx = localizedAppContext
                val doneCountText = if (progress.totalEntries == 1) {
                    locCtx.getString(R.string.notif_count_one_file)
                } else {
                    locCtx.getString(R.string.notif_count_many_files, progress.totalEntries)
                }
                val summaryMsg = "$archiveName • ${StorageHelper.formatBytes(progress.bytesExtracted)}"
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showCompletionNotification(
                        context = locCtx,
                        title = locCtx.getString(R.string.notif_extracted_title, doneCountText),
                        message = summaryMsg
                    )
                }
            }

            ExtractionStatus.ERROR -> {
                _isFloatingControlsExpanded.value = false
                _showStopConfirmDialog.value = false
                val locCtx = localizedAppContext
                val errTitle = locCtx.getString(R.string.extract_error_title)
                val errMsg = progress.errorMessage?.takeIf { it.isNotBlank() } ?: archiveName
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showErrorNotification(
                        context = locCtx,
                        title = errTitle,
                        message = errMsg
                    )
                }
                refreshStorage()
                refreshLocalFiles()
                refreshPendingResumeStates()
            }

            ExtractionStatus.CANCELLED -> {
                _isFloatingControlsExpanded.value = false
                _showStopConfirmDialog.value = false
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.cancelProgressNotification(app)
                    HatchNotificationHelper.cancelCompletionNotification(app)
                }
                refreshStorage()
                refreshLocalFiles()
                refreshPendingResumeStates()
                if (progress.isHatchMode) {
                    val currentSelectedFile = _selectedArchiveFile.value
                    val currentSelectedUri = _selectedSafUri.value
                    if (currentSelectedFile != null && currentSelectedFile.exists()) {
                        refreshRestoredArchiveAfterStop(file = currentSelectedFile, uri = null)
                    } else if (currentSelectedUri != null) {
                        refreshRestoredArchiveAfterStop(file = null, uri = currentSelectedUri)
                    }
                }
            }

            else -> {}
        }
    }

    private fun refreshRestoredArchiveAfterStop(file: File?, uri: Uri?) {
        val app = getApplication<Application>()
        selectJob?.cancel()
        verifyJob?.cancel()
        verifyJob = null
        _verificationState.value = ArchiveVerificationState()
        selectJob = viewModelScope.launch {
            runCatching {
                val summary = withContext(Dispatchers.IO) {
                    when {
                        file != null && file.exists() -> {
                            val cap = resolveCurrentDestinationCapacity(file.name)
                            SpaceLabEngine.computeArchiveSummary(
                                file = file,
                                deviceFreeBytes = cap.availableBytes ?: _deviceFreeSpace.value,
                                isDestinationCapacityReliable = cap.isReliable,
                                isDirectPosixDestination = cap.isDirectPosix
                            )
                        }
                        uri != null -> {
                            val cap = resolveCurrentDestinationCapacity(StorageHelper.queryFileName(app, uri))
                            SpaceLabEngine.computeSafUriSummary(
                                context = app,
                                uri = uri,
                                deviceFreeBytes = cap.availableBytes ?: _deviceFreeSpace.value,
                                isDestinationCapacityReliable = cap.isReliable,
                                isDirectPosixDestination = cap.isDirectPosix
                            )
                        }
                        else -> null
                    }
                }
                if (summary != null) {
                    _archiveSummary.value = summary
                    _exploringArchive.value = file
                    _exploringSafUri.value = uri
                    _exploringArchiveName.value = summary.fileName
                    _currentFolderPath.value = ""
                    _searchQuery.value = ""
                    _selectedExplorerPaths.value = emptySet()
                    _explorerAllEntries.value = summary.entries
                    if (_extractionProgress.value.status == ExtractionStatus.CANCELLED) {
                        val refreshedNote =
                            "[Restored] Re-indexed ${summary.fileName} (${summary.entryCount} remaining entries, ${StorageHelper.formatBytes(summary.fileSizeBytes)})"
                        val preservedLogs = appendConsoleLines(listOf(refreshedNote), flushImmediately = true)
                        _extractionProgress.update { cur ->
                            if (cur.status == ExtractionStatus.CANCELLED) {
                                cur.copy(journalLogs = preservedLogs)
                            } else {
                                cur
                            }
                        }
                    }
                }
            }
        }
    }

    fun setFloatingControlsExpanded(expanded: Boolean) {
        val status = _extractionProgress.value.status
        if (!status.isActiveSession || status == ExtractionStatus.FINALIZING) {
            _isFloatingControlsExpanded.value = false
            return
        }
        _isFloatingControlsExpanded.value = expanded
    }

    fun toggleFloatingControlsExpanded() {
        val status = _extractionProgress.value.status
        if (!status.isActiveSession || status == ExtractionStatus.FINALIZING) {
            _isFloatingControlsExpanded.value = false
            return
        }
        _isFloatingControlsExpanded.value = !_isFloatingControlsExpanded.value
    }

    fun pauseExtraction() {
        val current = _extractionProgress.value
        if (!current.status.isActiveSession ||
            current.status == ExtractionStatus.PAUSING ||
            current.status == ExtractionStatus.PAUSED ||
            current.status == ExtractionStatus.FINALIZING
        ) {
            return
        }
        val controller = ArchiveJobRunner.activePauseController
        val mergedLogs = appendConsoleLines(
            listOf("[Control] Pause requested for '${current.archiveName.ifEmpty { "archive" }}'."),
            flushImmediately = true
        )
        if (controller != null) {
            controller.requestPause()
            // If controller already transitioned directly to PAUSED (e.g., before any worker entered), reflect it
            val targetStatus = if (controller.isPaused) ExtractionStatus.PAUSED else ExtractionStatus.PAUSING
            val updated = current.copy(
                status = targetStatus,
                bytesPerSecond = 0L,
                journalLogs = mergedLogs
            )
            _extractionProgress.value = updated
            ArchiveJobRunner.updateExtractionProgress(updated)
        } else {
            val updated = current.copy(
                status = ExtractionStatus.PAUSED,
                bytesPerSecond = 0L,
                journalLogs = mergedLogs
            )
            _extractionProgress.value = updated
            ArchiveJobRunner.updateExtractionProgress(updated)
        }
    }

    fun resumeExtraction() {
        val current = _extractionProgress.value
        if (current.status != ExtractionStatus.PAUSED && current.status != ExtractionStatus.PAUSING) {
            return
        }
        val controller = ArchiveJobRunner.activePauseController
        if (controller != null) {
            controller.resume()
        }
        val mergedLogs = appendConsoleLines(
            listOf("[Control] Resumed extraction for '${current.archiveName.ifEmpty { "archive" }}'."),
            flushImmediately = true
        )
        val updated = current.copy(
            status = ExtractionStatus.EXTRACTING,
            journalLogs = mergedLogs
        )
        _extractionProgress.value = updated
        ArchiveJobRunner.updateExtractionProgress(updated)
    }

    fun requestStopExtractionConfirmation() {
        val status = _extractionProgress.value.status
        if (!status.isActiveSession || status == ExtractionStatus.FINALIZING) return
        _showStopConfirmDialog.value = true
    }

    fun dismissStopExtractionConfirmation() {
        _showStopConfirmDialog.value = false
    }

    fun confirmStopExtraction() {
        _showStopConfirmDialog.value = false
        _isFloatingControlsExpanded.value = false
        cancelExtraction()
    }

    fun cancelExtraction() {
        _showStopConfirmDialog.value = false
        _isFloatingControlsExpanded.value = false
        val current = _extractionProgress.value
        if (current.status == ExtractionStatus.FINALIZING) {
            return
        }
        val mergedLogs = appendConsoleLines(
            listOf("[Control] Stop requested for '${current.archiveName.ifEmpty { "archive" }}'."),
            flushImmediately = true
        )
        if (current.isHatchMode && ArchiveJobRunner.requestCooperativeStop()) {
            val finalizingState = current.copy(
                status = ExtractionStatus.FINALIZING,
                currentFileName = "Finalizing remaining archive…",
                bytesPerSecond = 0L,
                journalLogs = mergedLogs,
                isPauseSupported = false
            )
            _extractionProgress.value = finalizingState
            ArchiveJobRunner.updateExtractionProgress(finalizingState)
            return
        }
        ArchiveJobRunner.cancelExtraction(getApplication())
        extractJob?.cancel()
        HatchNotificationHelper.cancelCompletionNotification(getApplication())
        val cancelledState = current.copy(
            status = ExtractionStatus.CANCELLED,
            bytesPerSecond = 0L,
            journalLogs = mergedLogs
        )
        _extractionProgress.value = cancelledState
        ArchiveJobRunner.updateExtractionProgress(cancelledState)
        refreshPendingResumeStates()
    }

    fun cancelCompression() {
        compressJob?.cancel()
        ArchiveJobRunner.cancelCompression(getApplication())
        val cancelledState = _compressionProgress.value.copy(
            status = CompressionStatus.CANCELLED
        )
        _compressionProgress.value = cancelledState
        ArchiveJobRunner.updateCompressionProgress(cancelledState)
    }

    // Compression actions
    private fun maybeAutoNameFromFirstItem(firstItem: File, isDirectory: Boolean = firstItem.isDirectory) {
        val currentName = _compressionSettings.value.archiveName
        if (!hasCustomArchiveName || currentName.startsWith("KaiHatch_")) {
            val rawBase = firstItem.name
                .removeSuffix("_extracted")
                .let { if (isDirectory) it else it.substringBeforeLast('.', it) }
                .trim()
            if (rawBase.isNotEmpty()) {
                val autoNamed = normalizeArchiveNameForFormat(rawBase, _compressionSettings.value.format)
                _compressionSettings.value = _compressionSettings.value.copy(archiveName = autoNamed)
            }
        }
    }

    fun addItemToCompress(file: File) {
        val app = getApplication<Application>()
        if (StorageHelper.isCompressionStagingFile(app, file)) {
            StorageHelper.registerActiveCompressionFile(file)
        }
        val current = _itemsToCompress.value.toMutableList()
        if (!current.contains(file)) {
            val wasEmpty = current.isEmpty()
            current.add(file)
            _itemsToCompress.value = current
            viewModelScope.launch(Dispatchers.IO) {
                val isDir = file.isDirectory
                if (wasEmpty) {
                    maybeAutoNameFromFirstItem(file, isDirectory = isDir)
                }
                val size = if (isDir) StorageHelper.getDirectorySize(file) else file.length()
                val updated = _fileSizesCache.value.toMutableMap()
                updated[file.absolutePath] = size
                _fileSizesCache.value = updated
            }
        }
    }

    fun addExternalFolderToCompress(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val stagingDir = StorageHelper.getCompressStagingDir(getApplication())
            val folderFile = StorageHelper.resolveOrStageSafTreeFolder(context, treeUri, stagingDir) ?: return@launch
            if (StorageHelper.isCompressionStagingFile(getApplication(), folderFile)) {
                StorageHelper.registerActiveCompressionFile(folderFile)
            }
            val current = _itemsToCompress.value.toMutableList()
            if (!current.any { it.absolutePath == folderFile.absolutePath }) {
                val wasEmpty = current.isEmpty()
                current.add(folderFile)
                val folderSize = StorageHelper.getDirectorySize(folderFile)
                val updatedSizes = _fileSizesCache.value.toMutableMap()
                updatedSizes[folderFile.absolutePath] = folderSize
                _fileSizesCache.value = updatedSizes
                _itemsToCompress.value = current
                if (wasEmpty) {
                    maybeAutoNameFromFirstItem(folderFile)
                }
            }
        }
    }

    fun addExternalUrisToCompress(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val stagingDir = StorageHelper.getCompressStagingDir(app)
            val current = _itemsToCompress.value.toMutableList()
            val wasEmpty = current.isEmpty()
            val updatedSizes = _fileSizesCache.value.toMutableMap()
            var addedCount = 0

            for (uri in uris) {
                var candidateStagedFile: File? = null
                runCatching {
                    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "item_${System.currentTimeMillis()}"
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) {
                                cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                            }
                        }
                    }
                    val safeName = displayName.replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_")
                    val targetFile = File(stagingDir, safeName)
                    candidateStagedFile = targetFile
                    StorageHelper.registerActiveCompressionFile(targetFile)
                    var copiedOk = false
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            output.flush()
                        }
                        copiedOk = true
                    }
                    if (copiedOk && targetFile.exists() && !current.contains(targetFile)) {
                        current.add(targetFile)
                        updatedSizes[targetFile.absolutePath] = targetFile.length()
                        addedCount++
                    } else if (!copiedOk && targetFile.exists()) {
                        StorageHelper.cleanupCompressionFile(
                            context = app,
                            file = targetFile,
                            reason = "addExternalUrisToCompress_unopened_stream",
                            forceUnregister = true
                        )
                    }
                }.onFailure {
                    StorageHelper.cleanupCompressionFile(
                        context = app,
                        file = candidateStagedFile,
                        reason = "addExternalUrisToCompress_copy_failure",
                        forceUnregister = true
                    )
                }
            }

            if (addedCount > 0) {
                _fileSizesCache.value = updatedSizes
                _itemsToCompress.value = current
                if (wasEmpty && current.isNotEmpty()) {
                    maybeAutoNameFromFirstItem(current.first())
                }
            }
        }
    }

    fun removeItemToCompress(file: File) {
        val current = _itemsToCompress.value.toMutableList()
        current.remove(file)
        _itemsToCompress.value = current
        if (current.isEmpty()) {
            hasCustomArchiveName = false
        }
        val app = getApplication<Application>()
        if (StorageHelper.isCompressionStagingFile(app, file) && !ArchiveJobRunner.isCompressionActive()) {
            StorageHelper.cleanupCompressionFile(
                context = app,
                file = file,
                reason = "removeItemToCompress",
                forceUnregister = true
            )
            val updatedSizes = _fileSizesCache.value.toMutableMap()
            updatedSizes.remove(file.absolutePath)
            _fileSizesCache.value = updatedSizes
        }
    }

    fun clearItemsToCompress() {
        val previousItems = _itemsToCompress.value
        _itemsToCompress.value = emptyList()
        hasCustomArchiveName = false
        val app = getApplication<Application>()
        if (previousItems.isNotEmpty() && !ArchiveJobRunner.isCompressionActive()) {
            StorageHelper.cleanupStagedCompressionItems(
                context = app,
                items = previousItems,
                reason = "clearItemsToCompress"
            )
            val updatedSizes = _fileSizesCache.value.toMutableMap()
            previousItems.forEach { item ->
                if (StorageHelper.isCompressionStagingFile(app, item)) {
                    updatedSizes.remove(item.absolutePath)
                }
            }
            _fileSizesCache.value = updatedSizes
        }
    }

    private fun normalizeArchiveNameForFormat(rawName: String, format: ArchiveFormat): String {
        val desiredExt = when (format) {
            ArchiveFormat.SEVEN_Z -> ".7z"
            ArchiveFormat.RAR -> ".rar"
            ArchiveFormat.TAR -> ".tar"
            ArchiveFormat.TAR_GZ -> ".tar.gz"
            else -> ".zip"
        }
        val baseName = rawName.trim()
            .removeSuffix(".tar.gz")
            .removeSuffix(".tgz")
            .removeSuffix(".zip")
            .removeSuffix(".7z")
            .removeSuffix(".rar")
            .removeSuffix(".tar")
            .removeSuffix(".apk")
            .ifEmpty { "KaiHatch_${System.currentTimeMillis() / 1000}" }
        return "$baseName$desiredExt"
    }

    fun updateCompressionSettings(settings: CompressionSettings, userEditedName: Boolean = false) {
        if (userEditedName) {
            hasCustomArchiveName = settings.archiveName.isNotBlank() && !settings.archiveName.startsWith("KaiHatch_")
        }
        val updated = if (settings.format != _compressionSettings.value.format) {
            settings.copy(
                archiveName = normalizeArchiveNameForFormat(settings.archiveName, settings.format),
                splitVolumeSizeMb = 0,
                comment = ""
            )
        } else {
            settings.copy(
                splitVolumeSizeMb = 0,
                comment = ""
            )
        }
        _compressionSettings.value = updated
    }

    fun startCompression(requirePersistentFolder: Boolean = true) {
        val items = _itemsToCompress.value
        if (items.isEmpty()) return

        val app = getApplication<Application>()
        if (_grantedFolder.value == null) {
            _grantedFolder.value = StorageHelper.getGrantedFolderInfo(app)
        }

        if (requirePersistentFolder && _grantedFolder.value == null) {
            autoStartCompressionAfterFolderGrant = true
            _showPersistentFolderPrompt.value = true
            return
        }

        _showPersistentFolderPrompt.value = false
        autoStartCompressionAfterFolderGrant = false

        val settings = _compressionSettings.value
        val safeName = normalizeArchiveNameForFormat(settings.archiveName, settings.format)
        val granted = _grantedFolder.value
        val targetTreeUri = granted?.let { Uri.parse(it.uriString) }
        val startTime = System.currentTimeMillis()

        compressJob?.cancel()
        val locCtxInit = localizedAppContext
        val initialFileLabel = if (items.size == 1) {
            locCtxInit.getString(R.string.notif_count_one_file)
        } else {
            locCtxInit.getString(R.string.notif_count_many_files, items.size)
        }
        compressJob = ArchiveJobRunner.launchCompressionJob(
            context = app,
            title = locCtxInit.getString(R.string.notif_archiving_title, initialFileLabel),
            subtitle = safeName
        ) {
            var folderTarget: StorageHelper.GrantedFolderArchiveTarget? = null
            var compressionSucceeded = false

            fun cleanupFailedOrCancelledTarget(target: StorageHelper.GrantedFolderArchiveTarget?, reason: String) {
                if (target == null) return
                StorageHelper.unregisterActiveCompressionFile(target.workingFile)
                if (target.requiresSafStreamOnFinish || StorageHelper.isCompressionTempFile(app, target.workingFile)) {
                    StorageHelper.cleanupCompressionFile(
                        context = app,
                        file = target.workingFile,
                        reason = reason,
                        forceUnregister = true
                    )
                } else {
                    runCatching {
                        if (target.workingFile.exists()) {
                            target.workingFile.delete()
                        }
                    }
                }
                target.directPosixFile?.let { direct ->
                    StorageHelper.unregisterActiveCompressionFile(direct)
                    runCatching {
                        if (direct.exists()) {
                            direct.delete()
                        }
                    }
                }
                target.documentUri?.let { docUri ->
                    runCatching { StorageHelper.deleteSafDocument(app, docUri) }
                }
            }

            items.forEach { StorageHelper.registerActiveCompressionFile(it) }
            try {
                val preparedTarget = StorageHelper.prepareArchiveTargetInGrantedFolder(
                    context = app,
                    treeUri = targetTreeUri,
                    fileName = safeName
                )
                folderTarget = preparedTarget
                val targetArchive = preparedTarget.workingFile
                val totalBytes = items.sumOf {
                    _fileSizesCache.value[it.absolutePath]
                        ?: if (it.isDirectory) StorageHelper.getDirectorySize(it) else it.length()
                }
                HatchCompressEngine.compressFiles(items, targetArchive, settings.copy(archiveName = safeName)).collect { progress ->
                    when (progress.status) {
                        CompressionStatus.PREPARING, CompressionStatus.COMPRESSING -> {
                            val previous = _compressionProgress.value
                            val nowNs = System.nanoTime()
                            val isIntermediateByteTickOnly =
                                progress.status == CompressionStatus.COMPRESSING &&
                                    progress.status == previous.status &&
                                    progress.currentFileIndex == previous.currentFileIndex &&
                                    progress.currentFileName == previous.currentFileName
                            if (!isIntermediateByteTickOnly || (nowNs - lastCompressionUiUpdateNs) >= UI_PROGRESS_MIN_INTERVAL_NS) {
                                lastCompressionUiUpdateNs = nowNs
                                _compressionProgress.value = progress
                            }
                            ArchiveJobRunner.updateCompressionProgress(progress)

                            if (!HatchNotificationHelper.shouldDispatchLiveUpdate()) return@collect
                            val locCtx = localizedAppContext
                            val speedText = if (progress.bytesPerSecond > 0L) {
                                "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s"
                            } else ""
                            val totalCount = if (progress.totalFiles > 0) progress.totalFiles else items.size
                            val countLabel = if (totalCount == 1) {
                                locCtx.getString(R.string.notif_count_one_file)
                            } else {
                                locCtx.getString(R.string.notif_count_many_files, totalCount)
                            }
                            val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { safeName }
                            val remainingBytes = (progress.totalInputBytes - progress.processedInputBytes).coerceAtLeast(0L)
                            val remainingLine = HatchNotificationHelper.formatRemainingLine(
                                remainingBytes = remainingBytes,
                                bytesPerSecond = progress.bytesPerSecond,
                                context = locCtx
                            )
                            viewModelScope.launch(Dispatchers.IO) {
                                HatchNotificationHelper.showLiveProgressNotification(
                                    context = locCtx,
                                    title = locCtx.getString(R.string.notif_archiving_title, countLabel),
                                    subtitle = fileLine,
                                    detailBigText = remainingLine,
                                    progressFraction = progress.overallProgressFraction,
                                    speedText = speedText,
                                    indeterminate = progress.status == CompressionStatus.PREPARING,
                                    alreadyThrottled = true
                                )
                            }
                        }

                        CompressionStatus.COMPLETED -> {
                            try {
                                val outSize = StorageHelper.finalizeArchiveTargetInGrantedFolder(app, preparedTarget)
                                compressionSucceeded = true
                                val savedLocationLabel = granted?.displayName ?: preparedTarget.folderDisplayLabel
                                val finalProgress = progress.copy(
                                    currentOutputBytes = outSize,
                                    outputFilePath = preparedTarget.directPosixFile?.absolutePath,
                                    outputUriString = preparedTarget.documentUri?.toString(),
                                    savedLocationLabel = savedLocationLabel
                                )
                                _compressionProgress.value = finalProgress
                                ArchiveJobRunner.updateCompressionProgress(finalProgress)

                                refreshStorage()
                                refreshLocalFiles()

                                val duration = System.currentTimeMillis() - startTime
                                val saved = (totalBytes - outSize).coerceAtLeast(0L)

                                historyRepo.addRecord(
                                    OperationRecord(
                                        id = UUID.randomUUID().toString(),
                                        sourceName = safeName,
                                        targetPath = preparedTarget.directPosixFile?.absolutePath
                                            ?: preparedTarget.documentUri?.toString()
                                            ?: safeName,
                                        type = "COMPRESS",
                                        originalSize = totalBytes,
                                        reclaimedOrSavedSize = saved,
                                        entryCount = items.size,
                                        timestamp = System.currentTimeMillis(),
                                        durationMs = duration,
                                        success = true
                                    )
                                )

                                val locCtx = localizedAppContext
                                val archivedCountLabel = if (items.size == 1) {
                                    locCtx.getString(R.string.notif_count_one_file)
                                } else {
                                    locCtx.getString(R.string.notif_count_many_files, items.size)
                                }
                                viewModelScope.launch(Dispatchers.IO) {
                                    HatchNotificationHelper.showCompletionNotification(
                                        context = locCtx,
                                        title = locCtx.getString(R.string.notif_archived_title, archivedCountLabel),
                                        message = "$safeName • ${StorageHelper.formatBytes(outSize)}"
                                    )
                                }
                            } catch (finalizeEx: Exception) {
                                cleanupFailedOrCancelledTarget(preparedTarget, "finalize_saf_export_error")
                                val errorProgress = progress.copy(
                                    status = CompressionStatus.ERROR,
                                    errorMessage = "Compression failed: ${finalizeEx.message}"
                                )
                                _compressionProgress.value = errorProgress
                                ArchiveJobRunner.updateCompressionProgress(errorProgress)
                                viewModelScope.launch(Dispatchers.IO) {
                                    HatchNotificationHelper.cancelProgressNotification(app)
                                }
                            }
                        }

                        CompressionStatus.ERROR, CompressionStatus.CANCELLED -> {
                            cleanupFailedOrCancelledTarget(
                                preparedTarget,
                                if (progress.status == CompressionStatus.CANCELLED) "compression_cancelled" else "compression_error"
                            )
                            _compressionProgress.value = progress
                            ArchiveJobRunner.updateCompressionProgress(progress)
                            viewModelScope.launch(Dispatchers.IO) {
                                HatchNotificationHelper.cancelProgressNotification(app)
                            }
                        }

                        else -> {
                            _compressionProgress.value = progress
                            ArchiveJobRunner.updateCompressionProgress(progress)
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                if (!compressionSucceeded) {
                    cleanupFailedOrCancelledTarget(folderTarget, "compression_job_cancelled")
                    val cancelledProgress = _compressionProgress.value.copy(
                        status = CompressionStatus.CANCELLED
                    )
                    _compressionProgress.value = cancelledProgress
                    ArchiveJobRunner.updateCompressionProgress(cancelledProgress)
                }
                throw ce
            } catch (e: Exception) {
                if (!compressionSucceeded) {
                    cleanupFailedOrCancelledTarget(folderTarget, "compression_job_exception")
                    val errorProgress = _compressionProgress.value.copy(
                        status = CompressionStatus.ERROR,
                        errorMessage = "Compression failed: ${e.message}"
                    )
                    _compressionProgress.value = errorProgress
                    ArchiveJobRunner.updateCompressionProgress(errorProgress)
                    viewModelScope.launch(Dispatchers.IO) {
                        HatchNotificationHelper.cancelProgressNotification(app)
                    }
                }
            } finally {
                folderTarget?.let { target ->
                    StorageHelper.unregisterActiveCompressionFile(target.workingFile)
                    target.directPosixFile?.let { StorageHelper.unregisterActiveCompressionFile(it) }
                    if (target.requiresSafStreamOnFinish || StorageHelper.isCompressionTempFile(app, target.workingFile)) {
                        StorageHelper.cleanupCompressionFile(
                            context = app,
                            file = target.workingFile,
                            reason = "startCompression_finally",
                            forceUnregister = true
                        )
                    }
                }
                val cleanedStagedCount = StorageHelper.cleanupStagedCompressionItems(
                    context = app,
                    items = items,
                    reason = "startCompression_finally"
                )
                if (cleanedStagedCount > 0) {
                    val remainingQueue = _itemsToCompress.value.filter { item ->
                        !StorageHelper.isCompressionStagingFile(app, item) || item.exists()
                    }
                    _itemsToCompress.value = remainingQueue
                    val updatedSizes = _fileSizesCache.value.toMutableMap()
                    items.forEach { item ->
                        if (StorageHelper.isCompressionStagingFile(app, item) && !item.exists()) {
                            updatedSizes.remove(item.absolutePath)
                        }
                    }
                    _fileSizesCache.value = updatedSizes
                }
            }
        }
    }

    // Explorer actions
    fun openArchiveInExplorer(file: File) {
        _exploringArchive.value = file
        _exploringSafUri.value = null
        _exploringArchiveName.value = file.name
        _currentFolderPath.value = ""
        _searchQuery.value = ""
        _selectedExplorerPaths.value = emptySet()
        val cachedEntries = if (_selectedArchiveFile.value == file) {
            _archiveSummary.value?.entries
        } else {
            null
        }
        if (cachedEntries != null) {
            _explorerAllEntries.value = cachedEntries
            switchTab(ScreenTab.EXPLORER)
        } else {
            switchTab(ScreenTab.EXPLORER)
            viewModelScope.launch {
                val entries = withContext(Dispatchers.IO) {
                    ZipParser.parseArchive(file, _archivePassword.value.ifEmpty { null })
                }
                _explorerAllEntries.value = entries
            }
        }
    }

    fun openSafUriInExplorer(uri: Uri) {
        val app = getApplication<Application>()
        _exploringArchive.value = null
        _exploringSafUri.value = uri
        _currentFolderPath.value = ""
        _searchQuery.value = ""
        _selectedExplorerPaths.value = emptySet()
        val cachedSummary = if (_selectedSafUri.value == uri) {
            _archiveSummary.value
        } else {
            null
        }
        if (cachedSummary != null) {
            _exploringArchiveName.value = cachedSummary.fileName
            _explorerAllEntries.value = cachedSummary.entries
            switchTab(ScreenTab.EXPLORER)
        } else {
            switchTab(ScreenTab.EXPLORER)
            viewModelScope.launch {
                val name = withContext(Dispatchers.IO) {
                    StorageHelper.queryFileName(app, uri) ?: "External_Archive.zip"
                }
                val entries = withContext(Dispatchers.IO) {
                    ZipParser.parseSafUri(app, uri, name, _archivePassword.value.ifEmpty { null })
                }
                _exploringArchiveName.value = name
                _explorerAllEntries.value = entries
            }
        }
    }

    fun closeExplorerArchive() {
        _exploringArchive.value = null
        _exploringSafUri.value = null
        _exploringArchiveName.value = ""
        _explorerAllEntries.value = emptyList()
        _currentFolderPath.value = ""
        _searchQuery.value = ""
        _selectedExplorerPaths.value = emptySet()
    }

    fun toggleExplorerSelection(path: String) {
        val current = _selectedExplorerPaths.value.toMutableSet()
        if (current.contains(path)) {
            current.remove(path)
        } else {
            current.add(path)
        }
        _selectedExplorerPaths.value = current
    }

    fun clearExplorerSelection() {
        _selectedExplorerPaths.value = emptySet()
    }

    fun selectAllInCurrentExplorerFolder(visibleFileEntries: List<ZipEntryInfo>) {
        if (visibleFileEntries.isEmpty()) return
        val current = _selectedExplorerPaths.value.toMutableSet()
        val filePaths = visibleFileEntries.filter { !it.isDirectory }.map { it.path }
        val allSelected = filePaths.isNotEmpty() && filePaths.all { it in current }
        if (allSelected) {
            current.removeAll(filePaths.toSet())
        } else {
            current.addAll(filePaths)
        }
        _selectedExplorerPaths.value = current
    }

    fun invertExplorerSelection(visibleFileEntries: List<ZipEntryInfo>) {
        if (visibleFileEntries.isEmpty()) return
        val current = _selectedExplorerPaths.value.toMutableSet()
        visibleFileEntries.filter { !it.isDirectory }.forEach { entry ->
            if (current.contains(entry.path)) {
                current.remove(entry.path)
            } else {
                current.add(entry.path)
            }
        }
        _selectedExplorerPaths.value = current
    }

    fun extractSelectedExplorerEntries() {
        val selected = _selectedExplorerPaths.value
        if (selected.isEmpty()) return

        viewModelScope.launch {
            val needReselect = (_exploringArchive.value != null && _selectedArchiveFile.value != _exploringArchive.value) ||
                (_exploringSafUri.value != null && _selectedSafUri.value != _exploringSafUri.value)
            if (needReselect) {
                if (_exploringArchive.value != null) {
                    selectArchive(_exploringArchive.value!!)
                } else if (_exploringSafUri.value != null) {
                    selectSafUriZeroCopy(_exploringSafUri.value!!)
                }
                kotlinx.coroutines.delay(150)
            }
            startExtraction(selectedEntriesOnly = selected)
            _selectedExplorerPaths.value = emptySet()
        }
    }

    fun navigateToFolder(path: String) {
        _currentFolderPath.value = path
    }

    fun navigateUpFolder() {
        val current = _currentFolderPath.value.trimEnd('/')
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "")
        _currentFolderPath.value = if (parent.isEmpty()) "" else "$parent/"
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // Space Lab Actions
    fun generateTestPackage(sizeMb: Int) {
        viewModelScope.launch {
            _labGenerating.value = true
            val app = getApplication<Application>()
            val generatedFile = withContext(Dispatchers.IO) {
                val sampleDir = StorageHelper.getSampleArchivesDir(app)
                SpaceLabEngine.generateTestArchive(sampleDir, sizeMb)
            }
            _labGenerating.value = false
            refreshStorage()
            refreshLocalFiles()
            selectArchive(generatedFile)
            switchTab(ScreenTab.EXTRACT)
        }
    }

    // ==================== GitHub App Updater ====================

    fun initializeUpdaterOnStartup(launchedFromUpdateNotification: Boolean = false) {
        if (launchedFromUpdateNotification) {
            suppressStartupUpdatePrompt.set(true)
        }
        if (!startupUpdateInitialized.compareAndSet(false, true)) return

        viewModelScope.launch {
            val app = getApplication<Application>()
            val manager = AppUpdateManager.getInstance(app)

            withContext(Dispatchers.IO) {
                manager.performLaunchCleanup()
                try {
                    UpdateWorkScheduler.schedulePeriodicUpdateChecks(app)
                } catch (_: Throwable) {
                    // Non-fatal in constrained test environments
                }
            }

            if (suppressStartupUpdatePrompt.get()) {
                return@launch
            }

            _updateEvents.trySend(
                UpdateUiEvent.ShowToast(localizedAppContext.getString(R.string.update_toast_checking))
            )

            _isCheckingForUpdates.value = true
            val result = try {
                manager.checkForUpdates(
                    isManual = false,
                    minIntervalMs = AppUpdateManager.STARTUP_MIN_CHECK_INTERVAL_MS
                )
            } finally {
                _isCheckingForUpdates.value = false
            }

            if (result is UpdateCheckResult.UpdateAvailable) {
                // Only show popup if no notification-triggered action/install or dialog is active
                if (!suppressStartupUpdatePrompt.get() && _updateDialogState.value is UpdateDialogState.Hidden) {
                    _updateDialogState.value = UpdateDialogState.Available(
                        release = result.release,
                        hasCachedApk = result.cachedApkFile != null
                    )
                }
            }
        }
    }

    fun checkForUpdatesManually() {
        if (_isCheckingForUpdates.value) return

        viewModelScope.launch {
            val app = getApplication<Application>()
            val manager = AppUpdateManager.getInstance(app)

            _updateEvents.trySend(
                UpdateUiEvent.ShowToast(localizedAppContext.getString(R.string.update_toast_checking))
            )

            _isCheckingForUpdates.value = true
            val result = try {
                withContext(Dispatchers.IO) {
                    manager.performLaunchCleanup()
                }
                manager.checkForUpdates(isManual = true)
            } finally {
                _isCheckingForUpdates.value = false
            }

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _updateDialogState.value = UpdateDialogState.Available(
                        release = result.release,
                        hasCachedApk = result.cachedApkFile != null
                    )
                }
                is UpdateCheckResult.UpToDate -> {
                    _updateEvents.trySend(
                        UpdateUiEvent.ShowToast(
                            localizedAppContext.getString(
                                R.string.update_toast_up_to_date,
                                BuildConfig.VERSION_NAME
                            )
                        )
                    )
                }
                is UpdateCheckResult.Offline -> {
                    _updateEvents.trySend(
                        UpdateUiEvent.ShowToast(
                            localizedAppContext.getString(R.string.update_toast_offline)
                        )
                    )
                }
                is UpdateCheckResult.Error -> {
                    _updateEvents.trySend(
                        UpdateUiEvent.ShowToast(
                            localizedAppContext.getString(R.string.update_toast_check_failed)
                        )
                    )
                }
                is UpdateCheckResult.Skipped,
                is UpdateCheckResult.Throttled -> Unit
            }
        }
    }

    fun startUpdateDownloadOrInstall() {
        val release = when (val current = _updateDialogState.value) {
            is UpdateDialogState.Available -> current.release
            is UpdateDialogState.Error -> current.release
            else -> null
        } ?: return

        updateDownloadJob?.cancel()
        updateDownloadCancelled.set(false)

        updateDownloadJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val manager = AppUpdateManager.getInstance(app)

            // Reuse verified cached APK immediately if present
            val cached = withContext(Dispatchers.IO) {
                manager.findReusableCachedApk(release)
            }
            if (cached != null) {
                _updateEvents.trySend(
                    UpdateUiEvent.ShowToast(
                        localizedAppContext.getString(R.string.update_toast_reusing_cache)
                    )
                )
                requestOrLaunchInstaller(release, cached)
                return@launch
            }

            _updateDialogState.value = UpdateDialogState.Downloading(
                release = release,
                downloadedBytes = 0L,
                totalBytes = release.apkSizeBytes,
                progressFraction = 0f
            )

            val outcome = manager.downloadOrReuseUpdate(
                release = release,
                onProgress = { downloaded, total ->
                    if (!updateDownloadCancelled.get()) {
                        val fraction = if (total > 0L) {
                            (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        _updateDialogState.value = UpdateDialogState.Downloading(
                            release = release,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            progressFraction = fraction
                        )
                    }
                },
                isCancelled = { updateDownloadCancelled.get() }
            )

            when (outcome) {
                is UpdateDownloadOutcome.ReadyToInstall -> {
                    requestOrLaunchInstaller(outcome.release, outcome.apkFile)
                }
                is UpdateDownloadOutcome.Cancelled -> {
                    _updateDialogState.value = UpdateDialogState.Available(
                        release = release,
                        hasCachedApk = false
                    )
                }
                is UpdateDownloadOutcome.Failed -> {
                    _updateDialogState.value = UpdateDialogState.Error(
                        release = release,
                        message = outcome.message
                    )
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadCancelled.set(true)
        updateDownloadJob?.cancel()
        updateDownloadJob = null

        val activeRelease = (_updateDialogState.value as? UpdateDialogState.Downloading)?.release
        if (activeRelease != null) {
            _updateDialogState.value = UpdateDialogState.Available(
                release = activeRelease,
                hasCachedApk = false
            )
        } else {
            _updateDialogState.value = UpdateDialogState.Hidden
        }
    }

    fun skipCurrentUpdate() {
        val release = when (val current = _updateDialogState.value) {
            is UpdateDialogState.Available -> current.release
            is UpdateDialogState.Error -> current.release
            is UpdateDialogState.PermissionRequired -> current.release
            else -> null
        }
        if (release != null) {
            AppUpdateManager.getInstance(getApplication()).skipVersion(release.tagName)
        }
        pendingInstallRelease = null
        pendingInstallApkFile = null
        _updateDialogState.value = UpdateDialogState.Hidden
    }

    fun dismissUpdateDialog() {
        if (_updateDialogState.value is UpdateDialogState.Downloading) return
        _updateDialogState.value = UpdateDialogState.Hidden
    }

    fun confirmRequestInstallPermission() {
        val state = _updateDialogState.value as? UpdateDialogState.PermissionRequired ?: return
        pendingInstallRelease = state.release
        pendingInstallApkFile = state.apkFile
        _updateEvents.trySend(
            UpdateUiEvent.RequestInstallPermission(
                apkFile = state.apkFile,
                release = state.release
            )
        )
    }

    fun onInstallPermissionSettingsReturned() {
        val release = pendingInstallRelease ?: return
        val app = getApplication<Application>()
        if (AppUpdateManager.canRequestPackageInstalls(app)) {
            viewModelScope.launch {
                val manager = AppUpdateManager.getInstance(app)
                val verifiedApk = withContext(Dispatchers.IO) {
                    manager.findReusableCachedApk(release)
                } ?: pendingInstallApkFile?.takeIf { it.exists() }
                if (verifiedApk != null) {
                    pendingInstallRelease = null
                    pendingInstallApkFile = null
                    _updateDialogState.value = UpdateDialogState.Hidden
                    _updateEvents.trySend(
                        UpdateUiEvent.LaunchInstaller(apkFile = verifiedApk, release = release)
                    )
                } else {
                    _updateDialogState.value = UpdateDialogState.Available(
                        release = release,
                        hasCachedApk = false
                    )
                }
            }
        } else {
            _updateEvents.trySend(
                UpdateUiEvent.ShowToast(
                    localizedAppContext.getString(R.string.update_toast_install_permission_needed)
                )
            )
        }
    }

    private fun resolveNotificationRelease(
        manager: AppUpdateManager,
        releaseJson: String?,
        tagName: String?
    ): GitHubReleaseInfo? {
        val cached = manager.preferences.lastKnownRelease
        val parsed = GitHubReleaseInfo.fromJsonString(releaseJson)
        return when {
            parsed != null && parsed.releaseNotes.isBlank() && cached?.tagName == parsed.tagName ->
                parsed.copy(releaseNotes = cached.releaseNotes)
            parsed != null -> parsed
            else -> cached?.takeIf { tagName == null || it.tagName == tagName }
        }
    }

    fun openUpdateDialogFromNotification(releaseJson: String?, tagName: String?) {
        suppressStartupUpdatePrompt.set(true)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val manager = AppUpdateManager.getInstance(app)
            val release = resolveNotificationRelease(manager, releaseJson, tagName)

            if (release != null) {
                val cached = withContext(Dispatchers.IO) {
                    manager.findReusableCachedApk(release)
                }
                _updateDialogState.value = UpdateDialogState.Available(
                    release = release,
                    hasCachedApk = cached != null
                )
            } else {
                checkForUpdatesManually()
            }
        }
    }

    fun handleInstallCachedUpdateFromNotification(releaseJson: String?, tagName: String?) {
        suppressStartupUpdatePrompt.set(true)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val manager = AppUpdateManager.getInstance(app)
            UpdateNotificationHelper.cancelReadyToInstallNotification(app)

            val verifiedPair = withContext(Dispatchers.IO) {
                manager.findVerifiedCachedApkForInstall(expectedTagName = tagName)
            }

            if (verifiedPair != null) {
                val (apkFile, meta) = verifiedPair
                val release = resolveNotificationRelease(manager, releaseJson, tagName)
                    ?: GitHubReleaseInfo(
                        tagName = meta.tagName,
                        versionName = meta.versionName,
                        releaseName = meta.tagName,
                        releaseNotes = "",
                        htmlUrl = "https://github.com/biplov13b/KaiHatch/releases",
                        publishedAt = "",
                        apkAssetName = meta.fileName,
                        apkDownloadUrl = "https://github.com/biplov13b/KaiHatch/releases",
                        apkSizeBytes = meta.sizeBytes,
                        expectedSha256 = meta.sha256
                    )
                requestOrLaunchInstaller(release, apkFile)
            } else {
                openUpdateDialogFromNotification(releaseJson, tagName)
            }
        }
    }

    private fun requestOrLaunchInstaller(release: GitHubReleaseInfo, apkFile: File) {
        suppressStartupUpdatePrompt.set(true)
        val app = getApplication<Application>()
        UpdateNotificationHelper.cancelAvailableNotification(app)
        UpdateNotificationHelper.cancelReadyToInstallNotification(app)

        if (AppUpdateManager.canRequestPackageInstalls(app)) {
            pendingInstallRelease = null
            pendingInstallApkFile = null
            _updateDialogState.value = UpdateDialogState.Hidden
            _updateEvents.trySend(
                UpdateUiEvent.LaunchInstaller(apkFile = apkFile, release = release)
            )
        } else {
            pendingInstallRelease = release
            pendingInstallApkFile = apkFile
            _updateDialogState.value = UpdateDialogState.PermissionRequired(
                release = release,
                apkFile = apkFile
            )
        }
    }
}
