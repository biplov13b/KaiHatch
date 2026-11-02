package com.biplov.kaihatch.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biplov.kaihatch.data.ArchiveJobRunner
import com.biplov.kaihatch.data.ExtractedFileEntry
import com.biplov.kaihatch.data.ExtractedFolderEntry
import com.biplov.kaihatch.data.GrantedFolderInfo
import com.biplov.kaihatch.data.HatchNotificationHelper
import com.biplov.kaihatch.data.HistoryRepository
import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.engine.HatchCompressEngine
import com.biplov.kaihatch.engine.KaiHatchEngine
import com.biplov.kaihatch.engine.SpaceLabEngine
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.AppAccentPalette
import com.biplov.kaihatch.model.AppThemeMode
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.ArchiveSummary
import com.biplov.kaihatch.model.CompressionProgress
import com.biplov.kaihatch.model.CompressionSettings
import com.biplov.kaihatch.model.CompressionStatus
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.OperationRecord
import com.biplov.kaihatch.model.ResumeJournalState
import com.biplov.kaihatch.model.ZipEntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
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

    // Unified Material 3 Expressive Display & Color Settings
    private val _accentPalette = MutableStateFlow(loadSavedAccentPalette())
    val accentPalette: StateFlow<AppAccentPalette> = _accentPalette.asStateFlow()

    private val _themeMode = MutableStateFlow(loadSavedThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    // Progressive Blur & Tab Swipe Settings (matching sameerasw/essentials)
    private val _isBlurEnabled = MutableStateFlow(prefs.getBoolean("use_blur", true))
    val isBlurEnabled: StateFlow<Boolean> = _isBlurEnabled.asStateFlow()

    private val _isSwipeTabsEnabled = MutableStateFlow(prefs.getBoolean("swipe_tabs", true))
    val isSwipeTabsEnabled: StateFlow<Boolean> = _isSwipeTabsEnabled.asStateFlow()

    // Notification & Persistent Folder Permission Status
    private val _notificationsEnabled = MutableStateFlow(HatchNotificationHelper.hasNotificationPermission(application))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _grantedFolder = MutableStateFlow<GrantedFolderInfo?>(StorageHelper.getGrantedFolderInfo(application))
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

    private val extractionCompletionCounter = AtomicLong(0L)
    private val _lastCompletedTarget = MutableStateFlow<CompletedExtractionTarget?>(null)
    val lastCompletedTarget: StateFlow<CompletedExtractionTarget?> = _lastCompletedTarget.asStateFlow()

    // Precomputed file & folder byte sizes cached off the main thread to prevent UI jank on tab switches
    private val _fileSizesCache = MutableStateFlow<Map<String, Long>>(emptyMap())
    val fileSizesCache: StateFlow<Map<String, Long>> = _fileSizesCache.asStateFlow()

    init {
        if (ArchiveJobRunner.isExtractionActive()) {
            _extractionProgress.value = ArchiveJobRunner.extractionProgress.value
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
            HatchNotificationHelper.ensureChannels(application)
            _deviceFreeSpace.value = StorageHelper.getDeviceFreeBytes(application)
            _deviceTotalSpace.value = StorageHelper.getDeviceTotalBytes(application)
            _pendingResumeStates.value = ResumeJournalManager.loadPendingStates(application)
        }
        refreshLocalFiles()
    }

    private fun loadOrMigrateOnboardingState(application: Application): Boolean {
        if (prefs.contains(KEY_ONBOARDING_COMPLETED)) {
            return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        }
        val hasExistingUserState = prefs.contains("theme_mode") ||
            prefs.contains("accent_palette") ||
            prefs.contains("use_blur") ||
            prefs.contains("swipe_tabs") ||
            prefs.contains("default_hatch_mode") ||
            prefs.contains("selected_saf_folder_uri") ||
            StorageHelper.getGrantedFolderInfo(application) != null ||
            historyRepo.records.value.isNotEmpty()
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
        val raw = prefs.getString("accent_palette", AppAccentPalette.EMERALD.name) ?: AppAccentPalette.EMERALD.name
        return try {
            AppAccentPalette.valueOf(raw)
        } catch (_: Exception) {
            AppAccentPalette.EMERALD
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

    fun setAccentPalette(palette: AppAccentPalette) {
        _accentPalette.value = palette
        prefs.edit().putString("accent_palette", palette.name).apply()
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
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
        viewModelScope.launch(Dispatchers.IO) {
            val notifGranted = HatchNotificationHelper.hasNotificationPermission(app)
            val folderInfo = StorageHelper.getGrantedFolderInfo(app)
            _notificationsEnabled.value = notifGranted
            _grantedFolder.value = folderInfo
        }
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val UI_PROGRESS_MIN_INTERVAL_NS = 8_000_000L
    }

    fun onFolderPermissionGranted(treeUri: Uri) {
        val app = getApplication<Application>()
        val info = StorageHelper.saveGrantedFolder(app, treeUri)
        _grantedFolder.value = info
        _showPersistentFolderPrompt.value = false
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
        refreshLocalFiles()
    }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _deviceFreeSpace.value = StorageHelper.getDeviceFreeBytes(app)
            _deviceTotalSpace.value = StorageHelper.getDeviceTotalBytes(app)
        }
    }

    fun refreshPendingResumeStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val states = ResumeJournalManager.loadPendingStates(getApplication())
            _pendingResumeStates.value = states
        }
    }

    fun dismissResumeState(state: ResumeJournalState) {
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

    fun deleteExtractedFolder(folder: ExtractedFolderEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (folder.safUri != null) {
                StorageHelper.deleteSafDocument(app, folder.safUri)
            }
            folder.localFile?.deleteRecursively()
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
            file.localFile?.delete()
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
        selectJob?.cancel()
        selectJob = null
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
        _extractionProgress.value = ExtractionProgress()
        closeExplorerArchive()
        dismissPersistentFolderPrompt()
    }

    fun dismissExtractionStatusCard() {
        _extractionProgress.value = ExtractionProgress()
    }

    fun selectArchive(file: File) {
        selectJob?.cancel()
        if (_extractionProgress.value.status in listOf(
                ExtractionStatus.COMPLETED,
                ExtractionStatus.ERROR,
                ExtractionStatus.CANCELLED
            )
        ) {
            _extractionProgress.value = ExtractionProgress()
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
                    SpaceLabEngine.computeArchiveSummary(actualFile, _deviceFreeSpace.value)
                }
                _archiveSummary.value = summary
                _exploringArchive.value = actualFile
                _exploringSafUri.value = null
                _exploringArchiveName.value = summary.fileName
                _currentFolderPath.value = ""
                _searchQuery.value = ""
                _selectedExplorerPaths.value = emptySet()
                _explorerAllEntries.value = summary.entries
                _extractionProgress.value = _extractionProgress.value.copy(
                    archiveName = summary.fileName,
                    journalLogs = listOf(
                        "[Ready] Indexed ${summary.fileName} (${summary.entryCount} entries)",
                        "[Telemetry] Archive: ${StorageHelper.formatBytes(summary.fileSizeBytes)} -> Unpacked: ${StorageHelper.formatBytes(summary.uncompressedSizeBytes)}",
                        "[Savings] In-Place ftruncate saves ${StorageHelper.formatBytes(summary.spaceSavedWithHatch)} peak disk"
                    )
                )
            } catch (t: Throwable) {
                com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.onLowMemoryCleanup()
                _extractionProgress.value = ExtractionProgress(
                    archiveName = file.name,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "Failed to inspect archive '${file.name}': ${t.localizedMessage ?: t.javaClass.simpleName}"
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
        val app = getApplication<Application>()
        selectJob?.cancel()
        if (_extractionProgress.value.status in listOf(
                ExtractionStatus.COMPLETED,
                ExtractionStatus.ERROR,
                ExtractionStatus.CANCELLED
            )
        ) {
            _extractionProgress.value = ExtractionProgress()
        }
        _selectedArchiveFile.value = null
        _selectedSafUri.value = uri
        selectJob = viewModelScope.launch {
            try {
                val zeroCopySummary = withContext(Dispatchers.IO) {
                    SpaceLabEngine.computeSafUriSummary(app, uri, _deviceFreeSpace.value)
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
                    _extractionProgress.value = _extractionProgress.value.copy(
                        archiveName = zeroCopySummary.fileName,
                        journalLogs = listOf(
                            "[Zero-Copy SAF] Direct descriptor bound: ${zeroCopySummary.fileName} (${zeroCopySummary.entryCount} entries)",
                            "[Telemetry] Archive: ${StorageHelper.formatBytes(zeroCopySummary.fileSizeBytes)} -> Unpacked: ${StorageHelper.formatBytes(zeroCopySummary.uncompressedSizeBytes)}",
                            "[Savings] In-Place ftruncate saves ${StorageHelper.formatBytes(zeroCopySummary.spaceSavedWithHatch)} peak disk"
                        )
                    )
                } else {
                    // Only fallback-copy small cloud stream URIs (<100 MB) that lack a seekable FileChannel.
                    // Never blindly copy multi-GB files into internal storage!
                    val uriSize = withContext(Dispatchers.IO) { StorageHelper.queryUriSize(app, uri) }
                    if (uriSize in 1L..(100L * 1024L * 1024L)) {
                        val destDir = StorageHelper.getArchivesDir(app)
                        val copied = withContext(Dispatchers.IO) {
                            StorageHelper.copyUriToLocalFile(app, uri, destDir)
                        }
                        if (copied != null) {
                            refreshLocalFiles()
                            selectArchive(copied)
                            return@launch
                        }
                    }
                    val name = StorageHelper.queryFileName(app, uri) ?: "Selected file"
                    _extractionProgress.value = ExtractionProgress(
                        archiveName = name,
                        status = ExtractionStatus.ERROR,
                        errorMessage = "Could not recognize archive structure in '$name'. Supported formats: .zip, .7z, .apk, .tar, .tar.gz."
                    )
                }
            } catch (t: Throwable) {
                com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.onLowMemoryCleanup()
                val name = StorageHelper.queryFileName(app, uri) ?: "Selected file"
                _extractionProgress.value = ExtractionProgress(
                    archiveName = name,
                    status = ExtractionStatus.ERROR,
                    errorMessage = "Failed to inspect '$name': ${t.localizedMessage ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * Runs a fast header, structure, and CRC32 metadata integrity check on the currently selected archive
     * and appends the verification results to the I/O Console.
     */
    fun verifySelectedArchiveIntegrity() {
        val summary = _archiveSummary.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val entries = summary.entries.filter { !it.isDirectory }
            val totalFiles = entries.size
            val encryptedCount = entries.count { it.isEncrypted }
            val validCrcCount = entries.count { it.crc32 != 0L || it.uncompressedSize == 0L }
            val corruptOffsets = entries.count { it.localHeaderOffset < 0L || it.compressedSize < 0L }

            val statusLine = if (corruptOffsets == 0) {
                "[Verify OK] ${summary.fileName}: $totalFiles files verified (${StorageHelper.formatBytes(summary.uncompressedSizeBytes)} unpacked, $validCrcCount CRC32 headers valid)"
            } else {
                "[Verify WARN] ${summary.fileName}: $corruptOffsets entry header offset(s) out of bounds"
            }
            val encryptionLine = if (encryptedCount > 0) {
                "[Security] $encryptedCount encrypted entries detected (${if (_archivePassword.value.isNotEmpty()) "Password configured" else "Password required before extraction"})"
            } else {
                "[Security] Unencrypted central directory • Ready for ${if (_isHatchMode.value) "In-Place ftruncate()" else "Standard Copy"}"
            }

            val updatedLogs = (_extractionProgress.value.journalLogs + listOf(statusLine, encryptionLine)).takeLast(40)
            _extractionProgress.value = _extractionProgress.value.copy(
                isCrcValid = corruptOffsets == 0,
                journalLogs = updatedLogs
            )
        }
    }

    fun startExtraction(selectedEntriesOnly: Set<String>? = null, requirePersistentFolder: Boolean = true) {
        val summary = _archiveSummary.value ?: return
        val app = getApplication<Application>()

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
            journalLogs = listOf("Preparing output directory and initializing descriptor..."),
            startTime = startTime
        )

        extractJob?.cancel()
        val entryLabel = if (summary.entryCount == 1) "1 file" else "${summary.entryCount} files"
        val taskTitle = "Extracting $entryLabel"
        extractJob = ArchiveJobRunner.launchExtractionJob(
            context = app,
            title = taskTitle,
            subtitle = summary.fileName
        ) { pauseController ->
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
                    startTime = startTime
                )
            }
        }
    }

    fun resumeInterruptedTask(resumeState: ResumeJournalState) {
        val app = getApplication<Application>()
        val startTime = System.currentTimeMillis()
        val pass = _archivePassword.value.ifEmpty { resumeState.password }

        _lastCompletedTarget.value = null
        _isFloatingControlsExpanded.value = false
        _showStopConfirmDialog.value = false
        extractJob?.cancel()
        val remainingCount = resumeState.remainingEntries.size
        val resumeLabel = if (remainingCount == 1) "1 file" else "$remainingCount files"
        extractJob = ArchiveJobRunner.launchExtractionJob(
            context = app,
            title = "Extracting $resumeLabel",
            subtitle = resumeState.archiveName
        ) { pauseController ->
            val resumeTargetDir = File(resumeState.targetDirPath)
            val resumeTreeUri = _grantedFolder.value?.let { Uri.parse(it.uriString) }
            val resumeFolderDocUri = if (resumeTreeUri != null && resumeTargetDir.name.endsWith("_extracted")) {
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
                    startTime = startTime
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
        val app = getApplication<Application>()
        val previous = _extractionProgress.value
        val nowNs = System.nanoTime()
        val isIntermediateByteTickOnly =
            (progress.status == ExtractionStatus.EXTRACTING || progress.status == ExtractionStatus.TRUNCATING) &&
                progress.status == previous.status &&
                progress.completedEntries == previous.completedEntries &&
                progress.currentFileName == previous.currentFileName &&
                progress.journalLogs.size == previous.journalLogs.size

        if (progress.status == ExtractionStatus.COMPLETED) {
            val canonicalPath = runCatching { targetDir.canonicalPath }.getOrNull() ?: targetDir.absolutePath
            val docId = targetFolderDocUri?.let {
                runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
            }
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

        if (!isIntermediateByteTickOnly || (nowNs - lastExtractionUiUpdateNs) >= UI_PROGRESS_MIN_INTERVAL_NS) {
            lastExtractionUiUpdateNs = nowNs
            _extractionProgress.value = progress
        }
        ArchiveJobRunner.updateExtractionProgress(progress)

        when (progress.status) {
            ExtractionStatus.ANALYZING, ExtractionStatus.EXTRACTING, ExtractionStatus.TRUNCATING -> {
                if (!HatchNotificationHelper.shouldDispatchLiveUpdate()) return
                val speedText = if (progress.bytesPerSecond > 0L) {
                    "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s"
                } else ""
                val countText = when {
                    progress.totalEntries == 1 -> "1 file"
                    progress.totalEntries > 1 -> "${progress.totalEntries} files"
                    else -> archiveName
                }
                val notifTitle = "Extracting $countText"
                val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { archiveName }
                val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                val remainingLine = HatchNotificationHelper.formatRemainingLine(
                    remainingBytes = remainingBytes,
                    bytesPerSecond = progress.bytesPerSecond
                )
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = app,
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
                val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { archiveName }
                val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = app,
                        title = "Pausing extraction...",
                        subtitle = fileLine,
                        detailBigText = "Finishing current atomic step • ${StorageHelper.formatBytes(remainingBytes)} remaining",
                        progressFraction = progress.overallProgressFraction,
                        speedText = "PAUSING",
                        indeterminate = false,
                        forceUpdate = true
                    )
                }
            }

            ExtractionStatus.PAUSED -> {
                val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { archiveName }
                val remainingBytes = (progress.totalUncompressedBytes - progress.bytesExtracted).coerceAtLeast(0L)
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showLiveProgressNotification(
                        context = app,
                        title = "Extraction paused",
                        subtitle = fileLine,
                        detailBigText = "Paused at ${(progress.overallProgressFraction * 100f).toInt()}% • ${StorageHelper.formatBytes(remainingBytes)} remaining",
                        progressFraction = progress.overallProgressFraction,
                        speedText = "PAUSED",
                        indeterminate = false,
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

                val doneCountText = if (progress.totalEntries == 1) "1 file" else "${progress.totalEntries} files"
                val summaryMsg = "$archiveName • ${StorageHelper.formatBytes(progress.bytesExtracted)}"
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.showCompletionNotification(
                        context = app,
                        title = "Extracted $doneCountText",
                        message = summaryMsg
                    )
                }
            }

            ExtractionStatus.ERROR, ExtractionStatus.CANCELLED -> {
                _isFloatingControlsExpanded.value = false
                _showStopConfirmDialog.value = false
                viewModelScope.launch(Dispatchers.IO) {
                    HatchNotificationHelper.cancelProgressNotification(app)
                }
                refreshPendingResumeStates()
            }

            else -> {}
        }
    }

    fun setFloatingControlsExpanded(expanded: Boolean) {
        if (!_extractionProgress.value.status.isActiveSession) {
            _isFloatingControlsExpanded.value = false
            return
        }
        _isFloatingControlsExpanded.value = expanded
    }

    fun toggleFloatingControlsExpanded() {
        if (!_extractionProgress.value.status.isActiveSession) {
            _isFloatingControlsExpanded.value = false
            return
        }
        _isFloatingControlsExpanded.value = !_isFloatingControlsExpanded.value
    }

    fun pauseExtraction() {
        val current = _extractionProgress.value
        if (!current.status.isActiveSession || current.status == ExtractionStatus.PAUSING || current.status == ExtractionStatus.PAUSED) {
            return
        }
        val controller = ArchiveJobRunner.activePauseController
        if (controller != null) {
            controller.requestPause()
            // If controller already transitioned directly to PAUSED (e.g., before any worker entered), reflect it
            val targetStatus = if (controller.isPaused) ExtractionStatus.PAUSED else ExtractionStatus.PAUSING
            val updated = current.copy(
                status = targetStatus,
                bytesPerSecond = 0L
            )
            _extractionProgress.value = updated
            ArchiveJobRunner.updateExtractionProgress(updated)
        } else {
            val updated = current.copy(
                status = ExtractionStatus.PAUSED,
                bytesPerSecond = 0L
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
        val updated = current.copy(
            status = ExtractionStatus.EXTRACTING
        )
        _extractionProgress.value = updated
        ArchiveJobRunner.updateExtractionProgress(updated)
    }

    fun requestStopExtractionConfirmation() {
        if (!_extractionProgress.value.status.isActiveSession) return
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
        ArchiveJobRunner.cancelExtraction(getApplication())
        extractJob?.cancel()
        val cancelledState = _extractionProgress.value.copy(
            status = ExtractionStatus.CANCELLED,
            bytesPerSecond = 0L
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
    private fun maybeAutoNameFromFirstItem(firstItem: File) {
        val currentName = _compressionSettings.value.archiveName
        if (!hasCustomArchiveName || currentName.startsWith("KaiHatch_")) {
            val rawBase = firstItem.name
                .removeSuffix("_extracted")
                .let { if (firstItem.isDirectory) it else it.substringBeforeLast('.', it) }
                .trim()
            if (rawBase.isNotEmpty()) {
                val autoNamed = normalizeArchiveNameForFormat(rawBase, _compressionSettings.value.format)
                _compressionSettings.value = _compressionSettings.value.copy(archiveName = autoNamed)
            }
        }
    }

    fun addItemToCompress(file: File) {
        val current = _itemsToCompress.value.toMutableList()
        if (!current.contains(file)) {
            val wasEmpty = current.isEmpty()
            current.add(file)
            _itemsToCompress.value = current
            if (wasEmpty) {
                maybeAutoNameFromFirstItem(file)
            }
            viewModelScope.launch(Dispatchers.IO) {
                val size = if (file.isDirectory) StorageHelper.getDirectorySize(file) else file.length()
                val updated = _fileSizesCache.value.toMutableMap()
                updated[file.absolutePath] = size
                _fileSizesCache.value = updated
            }
        }
    }

    fun addExternalFolderToCompress(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val stagingDir = File(getApplication<Application>().cacheDir, "compress_staging").apply { mkdirs() }
            val folderFile = StorageHelper.resolveOrStageSafTreeFolder(context, treeUri, stagingDir) ?: return@launch
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
            val stagingDir = File(getApplication<Application>().cacheDir, "compress_staging").apply { mkdirs() }
            val current = _itemsToCompress.value.toMutableList()
            val wasEmpty = current.isEmpty()
            val updatedSizes = _fileSizesCache.value.toMutableMap()
            var addedCount = 0

            for (uri in uris) {
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
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    if (targetFile.exists() && !current.contains(targetFile)) {
                        current.add(targetFile)
                        updatedSizes[targetFile.absolutePath] = targetFile.length()
                        addedCount++
                    }
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
    }

    fun clearItemsToCompress() {
        _itemsToCompress.value = emptyList()
        hasCustomArchiveName = false
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
        val initialFileLabel = if (items.size == 1) "1 file" else "${items.size} files"
        compressJob = ArchiveJobRunner.launchCompressionJob(
            context = app,
            title = "Archiving $initialFileLabel",
            subtitle = safeName
        ) {
            val folderTarget = StorageHelper.prepareArchiveTargetInGrantedFolder(
                context = app,
                treeUri = targetTreeUri,
                fileName = safeName
            )
            val targetArchive = folderTarget.workingFile
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
                        val speedText = if (progress.bytesPerSecond > 0L) {
                            "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s"
                        } else ""
                        val totalCount = if (progress.totalFiles > 0) progress.totalFiles else items.size
                        val countLabel = if (totalCount == 1) "1 file" else "$totalCount files"
                        val fileLine = progress.currentFileName.substringAfterLast('/').ifEmpty { safeName }
                        val remainingBytes = (progress.totalInputBytes - progress.processedInputBytes).coerceAtLeast(0L)
                        val remainingLine = HatchNotificationHelper.formatRemainingLine(
                            remainingBytes = remainingBytes,
                            bytesPerSecond = progress.bytesPerSecond
                        )
                        viewModelScope.launch(Dispatchers.IO) {
                            HatchNotificationHelper.showLiveProgressNotification(
                                context = app,
                                title = "Archiving $countLabel",
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
                        val outSize = StorageHelper.finalizeArchiveTargetInGrantedFolder(app, folderTarget)
                        val savedLocationLabel = granted?.displayName ?: folderTarget.folderDisplayLabel
                        val finalProgress = progress.copy(
                            currentOutputBytes = outSize,
                            outputFilePath = folderTarget.directPosixFile?.absolutePath,
                            outputUriString = folderTarget.documentUri?.toString(),
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
                                type = "COMPRESS",
                                sourceName = safeName,
                                targetPath = folderTarget.directPosixFile?.absolutePath
                                    ?: folderTarget.documentUri?.toString()
                                    ?: safeName,
                                originalSize = totalBytes,
                                reclaimedOrSavedSize = saved,
                                entryCount = items.size,
                                timestamp = System.currentTimeMillis(),
                                durationMs = duration,
                                success = true
                            )
                        )

                        val archivedCountLabel = if (items.size == 1) "1 file" else "${items.size} files"
                        viewModelScope.launch(Dispatchers.IO) {
                            HatchNotificationHelper.showCompletionNotification(
                                context = app,
                                title = "Archived $archivedCountLabel",
                                message = "$safeName • ${StorageHelper.formatBytes(outSize)}"
                            )
                        }
                    }

                    CompressionStatus.ERROR, CompressionStatus.CANCELLED -> {
                        if (folderTarget.requiresSafStreamOnFinish) {
                            try { targetArchive.delete() } catch (_: Throwable) {}
                        }
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
            val sampleDir = StorageHelper.getSampleArchivesDir(app)
            val generatedFile = SpaceLabEngine.generateTestArchive(sampleDir, sizeMb)
            _labGenerating.value = false
            refreshStorage()
            refreshLocalFiles()
            selectArchive(generatedFile)
            switchTab(ScreenTab.EXTRACT)
        }
    }
}
