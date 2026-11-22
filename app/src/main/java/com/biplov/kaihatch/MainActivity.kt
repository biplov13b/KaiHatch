package com.biplov.kaihatch

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.biplov.kaihatch.data.HatchNotificationHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biplov.kaihatch.model.CompressionStatus
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.ScreenTab
import com.biplov.kaihatch.ui.components.ExpressiveMotion
import com.biplov.kaihatch.ui.components.HapticUtil
import com.biplov.kaihatch.ui.screens.ArchiveExplorerScreen
import com.biplov.kaihatch.ui.screens.CompressScreen
import com.biplov.kaihatch.ui.screens.HatchExtractScreen
import com.biplov.kaihatch.ui.screens.HistoryScreen
import com.biplov.kaihatch.ui.screens.OnboardingScreen
import com.biplov.kaihatch.ui.screens.SettingsScreen
import com.biplov.kaihatch.ui.screens.SpaceLabScreen
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import com.biplov.kaihatch.data.LanguageHelper
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.ui.components.AppUpdateDialog
import com.biplov.kaihatch.ui.theme.KaiHatchTheme
import com.biplov.kaihatch.updater.AppUpdateManager
import com.biplov.kaihatch.updater.UpdateNotificationHelper
import com.biplov.kaihatch.updater.UpdateUiEvent
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
private data class DockTabSpec(
    val tab: ScreenTab,
    val title: String,
    @StringRes val titleResId: Int,
    val testTag: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private enum class DockOverlayMode {
    WORKSPACE,
    SETTINGS,
    SPACE_LAB
}

private val PagerDockTabs = listOf(
    DockTabSpec(
        tab = ScreenTab.EXTRACT,
        title = "Unpack",
        titleResId = R.string.nav_unpack,
        testTag = "nav_extract",
        selectedIcon = Icons.Filled.Unarchive,
        unselectedIcon = Icons.Outlined.Unarchive
    ),
    DockTabSpec(
        tab = ScreenTab.COMPRESS,
        title = "Pack",
        titleResId = R.string.nav_pack,
        testTag = "nav_compress",
        selectedIcon = Icons.Filled.Archive,
        unselectedIcon = Icons.Outlined.Archive
    ),
    DockTabSpec(
        tab = ScreenTab.EXPLORER,
        title = "Browse",
        titleResId = R.string.nav_browse,
        testTag = "nav_explorer",
        selectedIcon = Icons.Filled.FolderOpen,
        unselectedIcon = Icons.Outlined.FolderOpen
    ),
    DockTabSpec(
        tab = ScreenTab.HISTORY,
        title = "History",
        titleResId = R.string.nav_history,
        testTag = "nav_history",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )
)

private val ActionTileShape = RoundedCornerShape(22.dp)

class MainActivity : ComponentActivity() {

    private var pendingIncomingUri by mutableStateOf<Uri?>(null)
    private var pendingShortcutAction by mutableStateOf<String?>(null)
    private var pendingUpdateIntent by mutableStateOf<Intent?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        extractUriFromIntent(intent)?.let { pendingIncomingUri = it }
        extractShortcutActionFromIntent(intent)?.let { pendingShortcutAction = it }
        extractUpdateIntent(intent)?.let { pendingUpdateIntent = it }

        setContent {
            val hatchViewModel: HatchViewModel = viewModel()
            val themeMode by hatchViewModel.themeMode.collectAsStateWithLifecycle()
            val accentPalette by hatchViewModel.accentPalette.collectAsStateWithLifecycle()
            val appLanguage by hatchViewModel.appLanguage.collectAsStateWithLifecycle()

            val currentConfig = androidx.compose.ui.platform.LocalConfiguration.current
            LaunchedEffect(currentConfig) {
                hatchViewModel.refreshPermissionsState()
            }

            KaiHatchTheme(
                themeMode = themeMode,
                accentPalette = accentPalette,
                appLanguage = appLanguage
            ) {
                val incoming = pendingIncomingUri
                val hasCompletedOnboarding by hatchViewModel.hasCompletedOnboarding.collectAsStateWithLifecycle()
                LaunchedEffect(incoming, hasCompletedOnboarding) {
                    if (incoming != null && hasCompletedOnboarding) {
                        StorageHelper.takePersistablePermission(this@MainActivity, incoming)

                        hatchViewModel.closeSettings()
                        hatchViewModel.switchTab(ScreenTab.EXTRACT)
                        hatchViewModel.selectSafUriZeroCopy(incoming)
                        pendingIncomingUri = null
                    }
                }
                val shortcutAction = pendingShortcutAction
                LaunchedEffect(shortcutAction) {
                    if (shortcutAction != null) {
                        when (shortcutAction) {
                            ACTION_SHORTCUT_EXTRACT -> {
                                hatchViewModel.closeSettings()
                                hatchViewModel.switchTab(ScreenTab.EXTRACT)
                            }
                            ACTION_SHORTCUT_LABS -> {
                                hatchViewModel.openSpaceLabFromSettings()
                            }
                            ACTION_SHORTCUT_SETTINGS -> {
                                hatchViewModel.openSettings()
                            }
                            HatchNotificationHelper.ACTION_CONFIRM_STOP_FROM_NOTIFICATION -> {
                                hatchViewModel.closeSettings()
                                hatchViewModel.switchTab(ScreenTab.EXTRACT)
                                hatchViewModel.requestStopExtractionConfirmation()
                            }
                        }
                        pendingShortcutAction = null
                    }
                }
                val updateIntent = pendingUpdateIntent
                LaunchedEffect(updateIntent, hasCompletedOnboarding) {
                    if (updateIntent != null && hasCompletedOnboarding) {
                        val releaseJson = updateIntent.getStringExtra(UpdateNotificationHelper.EXTRA_RELEASE_JSON)
                        val tagName = updateIntent.getStringExtra(UpdateNotificationHelper.EXTRA_TAG_NAME)
                        when (updateIntent.action) {
                            UpdateNotificationHelper.ACTION_OPEN_UPDATE_DIALOG -> {
                                hatchViewModel.openUpdateDialogFromNotification(releaseJson, tagName)
                            }
                            UpdateNotificationHelper.ACTION_INSTALL_CACHED_UPDATE -> {
                                hatchViewModel.handleInstallCachedUpdateFromNotification(releaseJson, tagName)
                            }
                        }
                        pendingUpdateIntent = null
                    }
                }
                KaiHatchApp(
                    viewModel = hatchViewModel,
                    launchedFromUpdateNotification = updateIntent != null
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUriFromIntent(intent)?.let { pendingIncomingUri = it }
        extractShortcutActionFromIntent(intent)?.let { pendingShortcutAction = it }
        extractUpdateIntent(intent)?.let { pendingUpdateIntent = it }
    }

    private fun extractUpdateIntent(intent: Intent?): Intent? {
        val action = intent?.action ?: return null
        return when (action) {
            UpdateNotificationHelper.ACTION_OPEN_UPDATE_DIALOG,
            UpdateNotificationHelper.ACTION_INSTALL_CACHED_UPDATE -> intent
            else -> null
        }
    }

    private fun extractShortcutActionFromIntent(intent: Intent?): String? {
        val action = intent?.action ?: return null
        return when (action) {
            ACTION_SHORTCUT_EXTRACT,
            ACTION_SHORTCUT_LABS,
            ACTION_SHORTCUT_SETTINGS,
            HatchNotificationHelper.ACTION_CONFIRM_STOP_FROM_NOTIFICATION -> action
            else -> null
        }
    }

    private fun extractUriFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                } ?: intent.data
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_SHORTCUT_EXTRACT = "com.biplov.kaihatch.SHORTCUT_EXTRACT"
        const val ACTION_SHORTCUT_LABS = "com.biplov.kaihatch.SHORTCUT_LABS"
        const val ACTION_SHORTCUT_SETTINGS = "com.biplov.kaihatch.SHORTCUT_SETTINGS"
    }
}

@Composable
fun KaiHatchApp(
    viewModel: HatchViewModel = viewModel(),
    launchedFromUpdateNotification: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCompletedOnboarding by viewModel.hasCompletedOnboarding.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!hasCompletedOnboarding) {
        OnboardingScreen(viewModel = viewModel)
        return
    }

    // SAF OpenDocument Picker for importing external .zip / .apk / .7z / .tar / .tar.gz archives
    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            viewModel.selectSafUriZeroCopy(uri)
        }
    }

    // SAF OpenDocument Picker specifically for Archive Explorer tab
    val explorerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            viewModel.openSafUriInExplorer(uri)
        }
    }

    // SAF OpenMultipleDocuments Picker for Compress Tab
    val compressFilesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addExternalUrisToCompress(context, uris)
        }
    }

    val onPickArchive = remember(archivePickerLauncher) {
        {
            archivePickerLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/vnd.android.package-archive",
                    "application/x-7z-compressed",
                    "application/x-tar",
                    "application/gzip",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }
    }

    val onPickExplorerArchive = remember(explorerPickerLauncher) {
        {
            explorerPickerLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/vnd.android.package-archive",
                    "application/x-7z-compressed",
                    "application/x-tar",
                    "application/gzip",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }
    }

    val onPickCompressFiles = remember(compressFilesPickerLauncher) {
        { compressFilesPickerLauncher.launch(arrayOf("*/*")) }
    }

    // Install Unknown Apps permission flow (Android 8+)
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onInstallPermissionSettingsReturned()
    }

    // Startup GitHub update check + cleanup + WorkManager scheduling
    LaunchedEffect(Unit) {
        viewModel.initializeUpdaterOnStartup(
            launchedFromUpdateNotification = launchedFromUpdateNotification
        )
    }

    // One-shot updater UI events (Toasts, native Android installer launch, install permission request)
    LaunchedEffect(viewModel) {
        viewModel.updateEvents.collect { event ->
            when (event) {
                is UpdateUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is UpdateUiEvent.LaunchInstaller -> {
                    try {
                        val installIntent = AppUpdateManager.createNativeInstallerIntent(
                            context = context,
                            apkFile = event.apkFile
                        )
                        context.startActivity(installIntent)
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_toast_installer_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is UpdateUiEvent.RequestInstallPermission -> {
                    try {
                        installPermissionLauncher.launch(
                            AppUpdateManager.createUnknownAppSourcesSettingsIntent(context)
                        )
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_toast_install_permission_needed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    KaiHatchRootContent(
        viewModel = viewModel,
        onPickArchive = onPickArchive,
        onPickExplorerArchive = onPickExplorerArchive,
        onPickCompressFiles = onPickCompressFiles
    )

    val updateDialogState by viewModel.updateDialogState.collectAsStateWithLifecycle()
    AppUpdateDialog(
        state = updateDialogState,
        onUpdateClick = { viewModel.startUpdateDownloadOrInstall() },
        onSkipClick = { viewModel.skipCurrentUpdate() },
        onCancelDownloadClick = { viewModel.cancelUpdateDownload() },
        onRequestInstallPermissionClick = { viewModel.confirmRequestInstallPermission() },
        onRetryClick = { viewModel.startUpdateDownloadOrInstall() },
        onDismiss = { viewModel.dismissUpdateDialog() }
    )
}

@Composable
private fun KaiHatchRootContent(
    viewModel: HatchViewModel,
    onPickArchive: () -> Unit,
    onPickExplorerArchive: () -> Unit,
    onPickCompressFiles: () -> Unit
) {
    com.biplov.kaihatch.ui.diagnostics.RememberDebugFrameMonitor()

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val activePageTab by viewModel.activePageTab.collectAsStateWithLifecycle()
    val isSwipeTabsEnabled by viewModel.isSwipeTabsEnabled.collectAsStateWithLifecycle()
    val isBlurEnabled by viewModel.isBlurEnabled.collectAsStateWithLifecycle()

    val showSettings = currentTab == ScreenTab.SETTINGS
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val initialPageIndex = remember {
        PagerDockTabs.indexOfFirst { it.tab == activePageTab }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { PagerDockTabs.size }
    )

    // Keep ViewModel activePageTab synced with Pager settledPage on manual swipe without
    // reading settledPage directly in KaiHatchRootContent composition scope
    val currentShowSettings by androidx.compose.runtime.rememberUpdatedState(showSettings)
    val currentActivePageTab by androidx.compose.runtime.rememberUpdatedState(activePageTab)
    LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow {
            Triple(pagerState.settledPage, pagerState.targetPage, pagerState.isScrollInProgress)
        }.collect { (settledPage, targetPage, isScrolling) ->
            com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.setInteractionActive(
                "nav:pager_swipe",
                isScrolling
            )
            val settledSpec = PagerDockTabs.getOrNull(settledPage) ?: PagerDockTabs.first()
            if (!currentShowSettings && currentActivePageTab != settledSpec.tab && targetPage == settledPage) {
                HapticUtil.tick(view)
                viewModel.switchTab(settledSpec.tab)
            }
        }
    }

    // Keep Pager synced when ViewModel selects tab programmatically
    LaunchedEffect(activePageTab) {
        val targetIndex = PagerDockTabs.indexOfFirst { it.tab == activePageTab }
        if (targetIndex >= 0 && pagerState.currentPage != targetIndex) {
            pagerState.animateScrollToPage(
                page = targetIndex,
                animationSpec = ExpressiveMotion.DockPagerSpring
            )
        }
    }

    val showSpaceLab = currentTab == ScreenTab.SPACE_LAB

    BackHandler(enabled = showSettings || showSpaceLab || activePageTab != ScreenTab.EXTRACT) {
        when {
            showSpaceLab -> viewModel.returnFromLabToSettings()
            showSettings -> viewModel.closeSettings()
            else -> viewModel.switchTab(ScreenTab.EXTRACT)
        }
    }

    val bgSurface = MaterialTheme.colorScheme.background
    val primaryTint = MaterialTheme.colorScheme.primary
    val surfaceContainerTint = MaterialTheme.colorScheme.surfaceContainerLow

    val blurScrimAlphaState = animateFloatAsState(
        targetValue = if (isBlurEnabled) 1f else 0f,
        animationSpec = ExpressiveMotion.ScrimFadeSpec,
        label = "progressive_blur_alpha"
    )
    val isBlurScrimMounted by remember {
        derivedStateOf { isBlurEnabled || blurScrimAlphaState.value > 0.001f }
    }

    val settingsProgressState = animateFloatAsState(
        targetValue = if (showSettings) 1f else 0f,
        animationSpec = ExpressiveMotion.OverlayTransitionSpec,
        label = "settings_transition"
    )
    // Only flips at transition boundaries so intermediate frames never trigger root recomposition
    val isSettingsMounted by remember {
        derivedStateOf { showSettings || settingsProgressState.value > 0.001f }
    }
    val isSettingsTransitioning by remember {
        derivedStateOf { settingsProgressState.value > 0.001f && settingsProgressState.value < 0.999f }
    }
    LaunchedEffect(isSettingsTransitioning) {
        com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.setInteractionActive(
            "transition:settings",
            isSettingsTransitioning
        )
    }

    val spaceLabProgressState = animateFloatAsState(
        targetValue = if (showSpaceLab) 1f else 0f,
        animationSpec = ExpressiveMotion.OverlayTransitionSpec,
        label = "space_lab_transition"
    )
    val isSpaceLabMounted by remember {
        derivedStateOf { showSpaceLab || spaceLabProgressState.value > 0.001f }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgSurface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Main 4-Tab HorizontalPager Workspace (Remains mounted so opening/closing Settings is instant)
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = isSwipeTabsEnabled && !showSettings && !showSpaceLab,
                beyondViewportPageCount = 1,
                key = { page -> PagerDockTabs[page].tab.name },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer {
                        val p = maxOf(settingsProgressState.value, spaceLabProgressState.value)
                        if (p > 0.001f) {
                            alpha = (1f - p * 1.25f).coerceIn(0f, 1f)
                            val s = 1f - (p * 0.035f)
                            scaleX = s
                            scaleY = s
                        } else {
                            alpha = 1f
                            scaleX = 1f
                            scaleY = 1f
                        }
                    }
            ) { page ->
                when (PagerDockTabs[page].tab) {
                    ScreenTab.EXTRACT -> HatchExtractScreen(viewModel = viewModel)
                    ScreenTab.COMPRESS -> CompressScreen(viewModel = viewModel)
                    ScreenTab.EXPLORER -> ArchiveExplorerScreen(viewModel = viewModel)
                    ScreenTab.SPACE_LAB -> SpaceLabScreen(viewModel = viewModel)
                    ScreenTab.HISTORY -> HistoryScreen(viewModel = viewModel)
                    ScreenTab.SETTINGS -> HatchExtractScreen(viewModel = viewModel)
                }
            }

            // 1b. Space Lab Benchmark Overlay (Driven purely by GPU graphicsLayer & drawBehind during transition)
            if (isSpaceLabMounted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(color = bgSurface, alpha = spaceLabProgressState.value)
                        }
                        .graphicsLayer {
                            val p = spaceLabProgressState.value
                            alpha = p
                            translationY = (1f - p) * 28.dp.toPx()
                        }
                        .statusBarsPadding()
                ) {
                    SpaceLabScreen(viewModel = viewModel)
                }
            }

            // 2. Settings Screen Overlay (Driven purely by GPU graphicsLayer & drawBehind during transition)
            if (isSettingsMounted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(color = bgSurface, alpha = settingsProgressState.value)
                        }
                        .graphicsLayer {
                            val p = settingsProgressState.value
                            alpha = p
                            translationY = (1f - p) * 28.dp.toPx()
                        }
                        .statusBarsPadding()
                ) {
                    SettingsScreen(viewModel = viewModel)
                }
            }

            // 3. Progressive Top & Bottom Blur Scrims (Controlled by Settings -> Progressive Bottom & Top Blur)
            if (isBlurScrimMounted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.TopCenter)
                        .drawWithCache {
                            val cachedTopBrush = Brush.verticalGradient(
                                0.00f to bgSurface.copy(alpha = 0.98f),
                                0.38f to bgSurface.copy(alpha = 0.86f),
                                0.72f to surfaceContainerTint.copy(alpha = 0.45f),
                                1.00f to Color.Transparent,
                                startY = 0f,
                                endY = size.height
                            )
                            onDrawBehind {
                                drawRect(brush = cachedTopBrush, alpha = blurScrimAlphaState.value)
                            }
                        }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                        .align(Alignment.BottomCenter)
                        .drawWithCache {
                            val cachedBottomBrush = Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.28f to bgSurface.copy(alpha = 0.42f),
                                0.62f to surfaceContainerTint.copy(alpha = 0.84f),
                                0.85f to bgSurface.copy(alpha = 0.95f),
                                1.00f to bgSurface.copy(alpha = 0.99f),
                                startY = 0f,
                                endY = size.height
                            )
                            onDrawBehind {
                                drawRect(brush = cachedBottomBrush, alpha = blurScrimAlphaState.value)
                            }
                        }
                )
            }

            // 4. Contextual Floating Action Tile (Bottom-Right above dock) + Outside-Tap Collapse Layer
            if (!showSettings && !showSpaceLab) {
                HatchFloatingControlsOutsideScrim(viewModel = viewModel)

                HatchFloatingActionOverlay(
                    activePageTab = activePageTab,
                    viewModel = viewModel,
                    onPickArchive = onPickArchive,
                    onPickExplorerArchive = onPickExplorerArchive,
                    onPickCompressFiles = onPickCompressFiles,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 86.dp)
                )
            }

            HatchStopConfirmationOverlay(viewModel = viewModel)

            // 5. Essentials-style Morphing Floating Bottom Dock
            val dockOverlayMode = when {
                showSpaceLab -> DockOverlayMode.SPACE_LAB
                showSettings -> DockOverlayMode.SETTINGS
                else -> DockOverlayMode.WORKSPACE
            }
            HatchFloatingBottomDock(
                pagerState = pagerState,
                dockMode = dockOverlayMode,
                isBlurEnabled = isBlurEnabled,
                onTabSelected = { targetIndex, spec ->
                    HapticUtil.tick(view)
                    if (showSettings || showSpaceLab) {
                        viewModel.closeSettings()
                    }
                    viewModel.switchTab(spec.tab)
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(
                            page = targetIndex,
                            animationSpec = ExpressiveMotion.DockPagerSpring
                        )
                    }
                },
                onBackFromOverlay = {
                    HapticUtil.click(view)
                    when (dockOverlayMode) {
                        DockOverlayMode.SPACE_LAB -> viewModel.returnFromLabToSettings()
                        DockOverlayMode.SETTINGS -> viewModel.closeSettings()
                        DockOverlayMode.WORKSPACE -> viewModel.openSettings()
                    }
                },
                onCompanionButtonClick = {
                    HapticUtil.click(view)
                    when (dockOverlayMode) {
                        DockOverlayMode.WORKSPACE -> viewModel.openSettings()
                        DockOverlayMode.SETTINGS -> viewModel.closeSettings()
                        DockOverlayMode.SPACE_LAB -> viewModel.returnFromLabToSettings()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
            )
        }
    }
}

@Composable
private fun HatchFloatingControlsOutsideScrim(viewModel: HatchViewModel) {
    val isFloatingControlsExpanded by viewModel.isFloatingControlsExpanded.collectAsStateWithLifecycle()
    val extractionProgressState = viewModel.extractionProgress.collectAsStateWithLifecycle()
    val isActiveSession by remember {
        derivedStateOf { extractionProgressState.value.status.isActiveSession }
    }

    if (isFloatingControlsExpanded && isActiveSession) {
        val outsideTapInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = outsideTapInteraction,
                    indication = null,
                    onClick = { viewModel.setFloatingControlsExpanded(false) }
                )
                .testTag("floating_controls_outside_scrim")
        )
    }
}

@Composable
private fun HatchStopConfirmationOverlay(viewModel: HatchViewModel) {
    val showStopConfirmDialog by viewModel.showStopConfirmDialog.collectAsStateWithLifecycle()
    val extractionProgressState = viewModel.extractionProgress.collectAsStateWithLifecycle()
    val isActiveSession by remember {
        derivedStateOf { extractionProgressState.value.status.isActiveSession }
    }
    val isHatchMode by remember {
        derivedStateOf { extractionProgressState.value.isHatchMode }
    }

    if (showStopConfirmDialog && isActiveSession) {
        StopExtractionConfirmationDialog(
            isHatchMode = isHatchMode,
            onKeepExtracting = { viewModel.dismissStopExtractionConfirmation() },
            onConfirmStop = { viewModel.confirmStopExtraction() }
        )
    }
}

/**
 * Floating Action Overlay above the bottom dock.
 * Scopes StateFlow subscriptions to the active tab and isolates byte-progress updates inside
 * [ExpressiveProgressStopTile] so background state changes never trigger cross-tab recomposition.
 */
@Composable
private fun HatchFloatingActionOverlay(
    activePageTab: ScreenTab,
    viewModel: HatchViewModel,
    onPickArchive: () -> Unit,
    onPickExplorerArchive: () -> Unit,
    onPickCompressFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extractionProgressState = viewModel.extractionProgress.collectAsStateWithLifecycle()
    val compressionProgressState = viewModel.compressionProgress.collectAsStateWithLifecycle()
    val isFloatingControlsExpanded by viewModel.isFloatingControlsExpanded.collectAsStateWithLifecycle()
    val view = LocalView.current

    val extractionStatus by remember {
        derivedStateOf { extractionProgressState.value.status }
    }
    val isPauseSupported by remember {
        derivedStateOf { extractionProgressState.value.isPauseSupported }
    }
    val compressionStatus by remember {
        derivedStateOf { compressionProgressState.value.status }
    }

    val isExtracting = extractionStatus.isActiveSession
    val isCompressing = compressionStatus == CompressionStatus.COMPRESSING ||
        compressionStatus == CompressionStatus.PREPARING

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isExtracting) {
            AnimatedExtractionFloatingControls(
                isExpanded = isFloatingControlsExpanded,
                status = extractionStatus,
                isPauseSupported = isPauseSupported,
                rawProgressProvider = { extractionProgressState.value.overallProgressFraction },
                onExpandClick = {
                    HapticUtil.click(view)
                    viewModel.setFloatingControlsExpanded(true)
                },
                onPauseClick = {
                    HapticUtil.click(view)
                    viewModel.pauseExtraction()
                },
                onResumeClick = {
                    HapticUtil.confirm(view)
                    viewModel.resumeExtraction()
                },
                onStopClick = {
                    HapticUtil.heavy(view)
                    viewModel.requestStopExtractionConfirmation()
                }
            )
        } else if (isCompressing) {
            ExpressiveProgressStopTile(
                rawProgressProvider = { compressionProgressState.value.overallProgressFraction },
                snapProgressAnimation = false,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                progressOutlineColor = MaterialTheme.colorScheme.error,
                onClick = {
                    HapticUtil.heavy(view)
                    viewModel.cancelCompression()
                }
            )
        } else {
            when (activePageTab) {
                ScreenTab.EXTRACT -> ExtractTabFabs(
                    viewModel = viewModel,
                    onPickArchive = onPickArchive
                )

                ScreenTab.COMPRESS -> Unit

                ScreenTab.EXPLORER -> ExplorerTabFabs(
                    viewModel = viewModel,
                    onPickExplorerArchive = onPickExplorerArchive
                )

                ScreenTab.SPACE_LAB -> SpaceLabTabFabs(viewModel = viewModel)

                ScreenTab.HISTORY -> HistoryTabFabs(
                    viewModel = viewModel,
                    onPickArchive = onPickArchive
                )

                ScreenTab.SETTINGS -> Unit
            }
        }
    }
}

@Composable
private fun ExtractTabFabs(
    viewModel: HatchViewModel,
    onPickArchive: () -> Unit
) {
    val archiveSummary by viewModel.archiveSummary.collectAsStateWithLifecycle()
    val view = LocalView.current

    if (archiveSummary == null) {
        ExpressiveActionTile(
            icon = Icons.Default.FolderOpen,
            label = stringResource(R.string.action_open),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = {
                HapticUtil.click(view)
                onPickArchive()
            }
        )
    }
}

@Composable
private fun ExplorerTabFabs(
    viewModel: HatchViewModel,
    onPickExplorerArchive: () -> Unit
) {
    val exploringArchive by viewModel.exploringArchive.collectAsStateWithLifecycle()
    val exploringSafUri by viewModel.exploringSafUri.collectAsStateWithLifecycle()
    val selectedExplorerPaths by viewModel.selectedExplorerPaths.collectAsStateWithLifecycle()
    val view = LocalView.current

    val hasExplorerLoaded = exploringArchive != null || exploringSafUri != null
    if (hasExplorerLoaded && selectedExplorerPaths.isNotEmpty()) {
        ExpressiveActionTile(
            icon = Icons.Default.Unarchive,
            label = stringResource(R.string.action_extract_count, selectedExplorerPaths.size),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = {
                HapticUtil.confirm(view)
                viewModel.extractSelectedExplorerEntries()
            }
        )
    } else {
        ExpressiveActionTile(
            icon = Icons.Default.FolderOpen,
            label = stringResource(R.string.action_open),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = {
                HapticUtil.click(view)
                onPickExplorerArchive()
            }
        )
    }
}

@Composable
private fun SpaceLabTabFabs(viewModel: HatchViewModel) {
    val labGenerating by viewModel.labGenerating.collectAsStateWithLifecycle()
    val view = LocalView.current

    if (!labGenerating) {
        ExpressiveActionTile(
            icon = Icons.Default.Science,
            label = stringResource(R.string.action_demo),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = {
                HapticUtil.confirm(view)
                viewModel.generateTestPackage(3)
            }
        )
    }
}

@Composable
private fun HistoryTabFabs(
    viewModel: HatchViewModel,
    onPickArchive: () -> Unit
) {
    val historyRecords by viewModel.historyRepo.records.collectAsStateWithLifecycle()
    val view = LocalView.current

    if (historyRecords.isNotEmpty()) {
        ExpressiveActionTile(
            icon = Icons.Default.DeleteSweep,
            label = stringResource(R.string.action_clear),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = {
                HapticUtil.heavy(view)
                viewModel.historyRepo.clearHistory()
            }
        )
    } else {
        ExpressiveActionTile(
            icon = Icons.Default.FolderOpen,
            label = stringResource(R.string.action_open),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = {
                HapticUtil.click(view)
                onPickArchive()
            }
        )
    }
}

@Composable
private fun SmallExpressiveFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ExpressiveActionTile(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .size(width = 68.dp, height = 68.dp)
            .clip(ActionTileShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = contentColor.copy(alpha = 0.16f),
                shape = ActionTileShape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.ExtraBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val PauseBlueContainer = Color(0xFF0C4A6E)
private val PauseBlueContent = Color(0xFFE0F2FE)
private val PauseBlueAccent = Color(0xFF38BDF8)

internal fun areSystemAnimationsEnabled(context: Context): Boolean {
    return try {
        ValueAnimator.areAnimatorsEnabled() &&
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
    } catch (_: Throwable) {
        true
    }
}

@Immutable
internal data class CollapsedExtractionTileSpec(
    val icon: ImageVector,
    val label: String,
    val contentDescription: String
)

internal fun resolveCollapsedExtractionTileSpec(
    status: ExtractionStatus,
    actionsLabel: String = "Actions",
    pausedDescription: String = "Extraction actions, paused. Double tap to open Resume and Stop controls.",
    runningDescription: String = "Extraction actions. Double tap to open Pause and Stop controls."
): CollapsedExtractionTileSpec {
    // State-aware collapsed icon: Pause while running (including PAUSING transition before engine
    // acknowledges pause), Play only once PAUSED is reached.
    val isPaused = status == ExtractionStatus.PAUSED
    return CollapsedExtractionTileSpec(
        icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
        label = actionsLabel,
        contentDescription = if (isPaused) pausedDescription else runningDescription
    )
}

/**
 * Floating extraction controls with state-aware collapsed "Actions" button and
 * coordinated expand/collapse animation matching [com.biplov.kaihatch.ui.components.JournalTerminal]'s
 * [androidx.compose.animation.AnimatedVisibility] transition pattern.
 */
@Composable
private fun AnimatedExtractionFloatingControls(
    isExpanded: Boolean,
    status: ExtractionStatus,
    isPauseSupported: Boolean,
    rawProgressProvider: () -> Float,
    onExpandClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val context = LocalContext.current
    val animationsEnabled = areSystemAnimationsEnabled(context)

    val isPaused = status == ExtractionStatus.PAUSED
    val isPausing = status == ExtractionStatus.PAUSING
    val isFinalizing = status == ExtractionStatus.FINALIZING
    val isPausedOrPausing = isPaused || isPausing || isFinalizing
    val pauseActionEnabled = isPauseSupported && !isPausing && !isFinalizing

    // Uses ExpressiveMotion.ProgressSpring so the progress outline hides/restores in lockstep with
    // the expand/collapse while reading outlineAlphaState.value exclusively in the draw phase.
    val outlineAlphaState = animateFloatAsState(
        targetValue = if (isExpanded) 0f else 1f,
        animationSpec = if (!animationsEnabled) {
            SnapSpec()
        } else {
            ExpressiveMotion.ProgressSpring
        },
        label = "floating_controls_outline_alpha"
    )

    val pauseResumeIcon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause
    val pauseResumeLabel = when {
        isPaused -> stringResource(R.string.action_resume)
        isPausing -> stringResource(R.string.action_pausing)
        else -> stringResource(R.string.action_pause)
    }
    val pauseResumeDesc = when {
        isPaused -> stringResource(R.string.cd_resume_extraction)
        isPausing -> stringResource(R.string.cd_pausing_extraction)
        else -> stringResource(R.string.cd_pause_extraction)
    }

    val collapsedSpec = resolveCollapsedExtractionTileSpec(
        status = status,
        actionsLabel = stringResource(R.string.action_actions),
        pausedDescription = stringResource(R.string.cd_extraction_actions_paused),
        runningDescription = stringResource(R.string.cd_extraction_actions_running)
    )

    Row(
        modifier = if (isExpanded) {
            Modifier.testTag("floating_expanded_extraction_controls")
        } else {
            Modifier
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = isExpanded && !isFinalizing,
            enter = if (animationsEnabled) {
                fadeIn() + expandHorizontally()
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                fadeOut() + shrinkHorizontally()
            } else {
                ExitTransition.None
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .size(width = 68.dp, height = 68.dp)
                        .clip(ActionTileShape)
                        .background(
                            if (pauseActionEnabled) PauseBlueContainer else PauseBlueContainer.copy(alpha = 0.6f)
                        )
                        .border(
                            width = 1.dp,
                            color = PauseBlueAccent.copy(alpha = if (pauseActionEnabled) 0.45f else 0.22f),
                            shape = ActionTileShape
                        )
                        .clickable(
                            enabled = pauseActionEnabled && isExpanded,
                            role = Role.Button,
                            onClick = {
                                if (isPaused) onResumeClick() else onPauseClick()
                            }
                        )
                        .padding(vertical = 10.dp, horizontal = 6.dp)
                        .testTag("floating_pause_resume_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = pauseResumeIcon,
                        contentDescription = pauseResumeDesc,
                        tint = if (pauseActionEnabled) PauseBlueContent else PauseBlueContent.copy(alpha = 0.65f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = pauseResumeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            letterSpacing = 0.5.sp
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        color = if (pauseActionEnabled) PauseBlueContent else PauseBlueContent.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
        }

        ExpressiveProgressStopTile(
            rawProgressProvider = rawProgressProvider,
            snapProgressAnimation = isPausedOrPausing,
            outlineAlphaProvider = { outlineAlphaState.value },
            containerColor = if (isFinalizing) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (isFinalizing) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            progressOutlineColor = if (isFinalizing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            icon = if (isExpanded && !isFinalizing) Icons.Default.Stop else collapsedSpec.icon,
            label = if (isExpanded && !isFinalizing) stringResource(R.string.action_stop) else collapsedSpec.label,
            contentDescription = if (isExpanded && !isFinalizing) stringResource(R.string.cd_stop_extraction) else collapsedSpec.contentDescription,
            testTag = if (isExpanded && !isFinalizing) "floating_expanded_stop_button" else "floating_stop_action_tile",
            onClick = {
                if (!isFinalizing) {
                    if (!isExpanded) {
                        onExpandClick()
                    } else {
                        onStopClick()
                    }
                }
            }
        )
    }
}

@Composable
private fun StopExtractionConfirmationDialog(
    isHatchMode: Boolean,
    onKeepExtracting: () -> Unit,
    onConfirmStop: () -> Unit
) {
    val bodyMessage = if (isHatchMode) {
        stringResource(R.string.stop_extraction_dialog_body_hatch)
    } else {
        stringResource(R.string.stop_extraction_dialog_body_standard)
    }
    val confirmButtonLabel = if (isHatchMode) {
        stringResource(R.string.stop_extraction_confirm_hatch)
    } else {
        stringResource(R.string.stop_extraction_confirm_standard)
    }

    AlertDialog(
        onDismissRequest = onKeepExtracting,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.stop_extraction_dialog_title),
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Text(
                text = bodyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("confirm_stop_extraction_button")
            ) {
                Text(
                    text = confirmButtonLabel,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepExtracting,
                modifier = Modifier.testTag("keep_extracting_button")
            ) {
                Text(
                    text = stringResource(R.string.stop_extraction_keep_extracting),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        modifier = Modifier.testTag("stop_extraction_confirm_dialog")
    )
}

@Composable
private fun ExpressiveProgressStopTile(
    rawProgressProvider: () -> Float,
    snapProgressAnimation: Boolean = false,
    containerColor: Color,
    contentColor: Color,
    progressOutlineColor: Color,
    icon: ImageVector = Icons.Default.Stop,
    label: String = stringResource(R.string.action_stop),
    contentDescription: String = label,
    outlineAlphaProvider: () -> Float = { 1f },
    testTag: String = "floating_stop_action_tile",
    onClick: () -> Unit
) {
    val rawProgress = rawProgressProvider().coerceIn(0f, 1f)
    val animatedProgressState = animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = if (snapProgressAnimation) {
            SnapSpec()
        } else {
            ExpressiveMotion.ProgressSpring
        },
        label = "stop_tile_progress_outline"
    )

    Column(
        modifier = Modifier
            .size(width = 68.dp, height = 68.dp)
            .clip(ActionTileShape)
            .background(containerColor)
            .clickable(
                role = Role.Button,
                onClick = onClick
            )
            .drawWithCache {
                val strokePx = 3.5.dp.toPx()
                val inset = strokePx / 2f
                val cornerR = (22.dp.toPx() - inset).coerceAtLeast(0f)
                val d = cornerR * 2f
                val w = size.width
                val h = size.height

                val fullPath = Path().apply {
                    moveTo(w / 2f, inset)
                    lineTo(w - inset - cornerR, inset)
                    arcTo(
                        rect = Rect(w - inset - d, inset, w - inset, inset + d),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(w - inset, h - inset - cornerR)
                    arcTo(
                        rect = Rect(w - inset - d, h - inset - d, w - inset, h - inset),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(inset + cornerR, h - inset)
                    arcTo(
                        rect = Rect(inset, h - inset - d, inset + d, h - inset),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(inset, inset + cornerR)
                    arcTo(
                        rect = Rect(inset, inset, inset + d, inset + d),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(w / 2f, inset)
                }

                val pathMeasure = PathMeasure().apply {
                    setPath(fullPath, false)
                }
                val segmentPath = Path()
                val trackStroke = Stroke(width = strokePx)
                val progressStroke = Stroke(width = strokePx, cap = StrokeCap.Round)
                val expandedBorderStroke = Stroke(width = 1.dp.toPx())
                val trackColor = contentColor.copy(alpha = 0.2f)
                val expandedBorderBaseColor = contentColor.copy(alpha = 0.16f)

                onDrawWithContent {
                    drawContent()
                    val outlineAlpha = outlineAlphaProvider().coerceIn(0f, 1f)
                    if (outlineAlpha > 0.001f) {
                        drawPath(
                            path = fullPath,
                            color = trackColor.copy(alpha = trackColor.alpha * outlineAlpha),
                            style = trackStroke
                        )
                        val progress = (if (snapProgressAnimation) rawProgress else animatedProgressState.value).coerceIn(0f, 1f)
                        val stopDistance = pathMeasure.length * progress.coerceAtLeast(0.03f)
                        segmentPath.reset()
                        pathMeasure.getSegment(
                            startDistance = 0f,
                            stopDistance = stopDistance,
                            destination = segmentPath,
                            startWithMoveTo = true
                        )
                        drawPath(
                            path = segmentPath,
                            color = progressOutlineColor.copy(alpha = progressOutlineColor.alpha * outlineAlpha),
                            style = progressStroke
                        )
                    }
                    val expandedBorderAlpha = (1f - outlineAlpha).coerceIn(0f, 1f)
                    if (expandedBorderAlpha > 0.001f) {
                        drawPath(
                            path = fullPath,
                            color = expandedBorderBaseColor.copy(alpha = expandedBorderBaseColor.alpha * expandedBorderAlpha),
                            style = expandedBorderStroke
                        )
                    }
                }
            }
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.ExtraBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Essentials-inspired Morphing Floating Bottom Dock.
 *
 * Key 120Hz Architecture:
 * - Constant outer pill geometry (296.dp x 60.dp; 284.dp inner track = 100.dp active + 4 * 46.dp inactive).
 * - `pagerState.currentPageOffsetFraction` is read ONLY inside the `Layout` measure/placement phase and
 *   `drawBehind` canvas phase—NEVER in the `@Composable` body.
 * - All 5 tab icons and labels remain permanently mounted so swiping across tabs causes ZERO recompositions
 *   and ZERO mid-swipe Text paragraph layout allocations.
 */
@Composable
private fun HatchFloatingBottomDock(
    pagerState: PagerState,
    dockMode: DockOverlayMode,
    isBlurEnabled: Boolean,
    onTabSelected: (Int, DockTabSpec) -> Unit,
    onBackFromOverlay: () -> Unit,
    onCompanionButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dockContainerColor = if (isBlurEnabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val dockBorderColor = if (isBlurEnabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val activeIndicatorColor = MaterialTheme.colorScheme.primary
    val activeContentColor = MaterialTheme.colorScheme.onPrimary
    val inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Only updates once when crossing the 50% midpoint between tabs (zero per-frame recomposition!)
    val nearestTabIndex by remember {
        derivedStateOf {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                .roundToInt()
                .coerceIn(0, PagerDockTabs.size - 1)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Adapt dock item width dynamically so narrow split-screen / multi-window panes never clip
        val availableForPill = (maxWidth - 64.dp).coerceAtLeast(204.dp)
        val adaptiveItemWidthDp = ((availableForPill - 12.dp) / PagerDockTabs.size).coerceIn(48.dp, 68.dp)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedContent(
                targetState = dockMode,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(180)))
                        .togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.92f, animationSpec = tween(140)))
                        .using(SizeTransform(clip = false))
                },
                label = "dock_mode_morph"
            ) { mode ->
                when (mode) {
                    DockOverlayMode.SETTINGS -> {
                        Row(
                            modifier = Modifier
                                .height(60.dp)
                                .clip(CircleShape)
                                .background(dockContainerColor)
                                .border(1.dp, dockBorderColor, CircleShape)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(activeIndicatorColor)
                                    .clickable(
                                        role = Role.Button,
                                        onClick = onBackFromOverlay
                                    )
                                    .testTag("settings_back_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nav_back_to_workspace),
                                    tint = activeContentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Text(
                                text = stringResource(R.string.nav_settings),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 18.dp)
                            )
                        }
                    }

                    DockOverlayMode.SPACE_LAB -> {
                        Row(
                            modifier = Modifier
                                .height(60.dp)
                                .clip(CircleShape)
                                .background(dockContainerColor)
                                .border(1.dp, dockBorderColor, CircleShape)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(activeIndicatorColor)
                                    .clickable(
                                        role = Role.Button,
                                        onClick = onBackFromOverlay
                                    )
                                    .testTag("lab_back_to_settings_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nav_back_to_settings),
                                    tint = activeContentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = stringResource(R.string.nav_space_lab),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    DockOverlayMode.WORKSPACE -> {
                        val itemWidthDp = adaptiveItemWidthDp
                        val totalPillWidthDp = itemWidthDp * PagerDockTabs.size + 12.dp

                        Box(
                            modifier = Modifier
                                .width(totalPillWidthDp)
                                .height(60.dp)
                                .clip(CircleShape)
                                .background(dockContainerColor)
                                .border(1.dp, dockBorderColor, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                                .drawBehind {
                                    val fractionalPage = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                                        .coerceIn(0f, (PagerDockTabs.size - 1).toFloat())
                                    val itemW = itemWidthDp.toPx()
                                    val indicatorLeft = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                                        (size.width - itemW) - (fractionalPage * itemW)
                                    } else {
                                        fractionalPage * itemW
                                    }
                                    drawRoundRect(
                                        color = activeIndicatorColor,
                                        topLeft = Offset(indicatorLeft, 0f),
                                        size = Size(itemW, size.height),
                                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PagerDockTabs.forEachIndexed { index, spec ->
                                    val isNearest = index == nearestTabIndex
                                    val tintColor by animateColorAsState(
                                        targetValue = if (isNearest) activeContentColor else inactiveContentColor,
                                        animationSpec = ExpressiveMotion.TabTintSpring,
                                        label = "tab_tint"
                                    )
                                    DockTabItem(
                                        spec = spec,
                                        isSelected = isNearest,
                                        tintColor = tintColor,
                                        onClick = { onTabSelected(index, spec) },
                                        modifier = Modifier
                                            .width(itemWidthDp)
                                            .height(50.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Companion Right Circle Button:
            // - In Settings: shows Home icon (returns to app)
            // - In Workspace or Lab: shows Settings icon (opens/returns to Settings)
            val isHomeButton = dockMode == DockOverlayMode.SETTINGS
            val companionBgColorState = animateColorAsState(
                targetValue = if (dockMode != DockOverlayMode.WORKSPACE) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    dockContainerColor
                },
                animationSpec = ExpressiveMotion.TabTintSpring,
                label = "companion_bg_color"
            )
            val companionIconTint by animateColorAsState(
                targetValue = if (dockMode != DockOverlayMode.WORKSPACE) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = ExpressiveMotion.TabTintSpring,
                label = "companion_icon_tint"
            )

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .drawBehind { drawCircle(color = companionBgColorState.value) }
                    .border(1.dp, dockBorderColor, CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onCompanionButtonClick
                    )
                    .testTag("top_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isHomeButton) Icons.Default.Home else Icons.Default.Settings,
                    contentDescription = if (isHomeButton) stringResource(R.string.nav_home) else stringResource(R.string.nav_settings),
                    tint = companionIconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Single labeled tab item inside [HatchFloatingBottomDock].
 * Displays both the icon and the label for all 4 tabs at all times so users never have to guess icon meanings.
 */
@Composable
private fun DockTabItem(
    spec: DockTabSpec,
    isSelected: Boolean,
    tintColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val localizedTitle = stringResource(spec.titleResId)
    Column(
        modifier = modifier
            .testTag(spec.testTag)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSelected) spec.selectedIcon else spec.unselectedIcon,
            contentDescription = localizedTitle,
            tint = tintColor,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = localizedTitle,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp
            ),
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = tintColor,
            maxLines = 1,
            softWrap = false
        )
    }
}
