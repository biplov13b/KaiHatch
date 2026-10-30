package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.CompressionStatus
import com.example.model.ExtractionStatus
import com.example.ui.HatchViewModel
import com.example.ui.ScreenTab
import com.example.ui.components.HapticUtil
import com.example.ui.screens.ArchiveExplorerScreen
import com.example.ui.screens.CompressScreen
import com.example.ui.screens.HatchExtractScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpaceLabScreen
import com.example.ui.theme.KaiHatchTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
private data class DockTabSpec(
    val tab: ScreenTab,
    val title: String,
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
        testTag = "nav_extract",
        selectedIcon = Icons.Filled.Unarchive,
        unselectedIcon = Icons.Outlined.Unarchive
    ),
    DockTabSpec(
        tab = ScreenTab.COMPRESS,
        title = "Pack",
        testTag = "nav_compress",
        selectedIcon = Icons.Filled.Archive,
        unselectedIcon = Icons.Outlined.Archive
    ),
    DockTabSpec(
        tab = ScreenTab.EXPLORER,
        title = "Browse",
        testTag = "nav_explorer",
        selectedIcon = Icons.Filled.FolderOpen,
        unselectedIcon = Icons.Outlined.FolderOpen
    ),
    DockTabSpec(
        tab = ScreenTab.HISTORY,
        title = "History",
        testTag = "nav_history",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )
)

private val ActionTileShape = RoundedCornerShape(22.dp)
private val DockPillSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)
private val TabIconColorSpring = spring<Color>(stiffness = Spring.StiffnessMedium)

class MainActivity : ComponentActivity() {

    private var pendingIncomingUri by mutableStateOf<Uri?>(null)
    private var pendingShortcutAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        requestHighRefreshRate120Hz()
        super.onCreate(savedInstanceState)

        extractUriFromIntent(intent)?.let { pendingIncomingUri = it }
        extractShortcutActionFromIntent(intent)?.let { pendingShortcutAction = it }

        setContent {
            val hatchViewModel: HatchViewModel = viewModel()
            val themeMode by hatchViewModel.themeMode.collectAsState()
            val accentPalette by hatchViewModel.accentPalette.collectAsState()

            KaiHatchTheme(
                themeMode = themeMode,
                accentPalette = accentPalette
            ) {
                val incoming = pendingIncomingUri
                LaunchedEffect(incoming) {
                    if (incoming != null) {
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
                        }
                        pendingShortcutAction = null
                    }
                }
                KaiHatchApp(viewModel = hatchViewModel)
            }
        }
    }

    /**
     * Requests the highest available display refresh rate (e.g. 120Hz / 144Hz) from the WindowManager
     * so devices that throttle standard apps to 60Hz unlock full 120fps frame pacing.
     */
    private fun requestHighRefreshRate120Hz() {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val supportedModes = display.supportedModes
            val highestMode = supportedModes.maxByOrNull { it.refreshRate }
            val params = window.attributes
            if (highestMode != null && highestMode.refreshRate >= 90f) {
                params.preferredDisplayModeId = highestMode.modeId
                params.preferredRefreshRate = highestMode.refreshRate
            } else {
                params.preferredRefreshRate = 120f
            }
            window.attributes = params
        } catch (_: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUriFromIntent(intent)?.let { pendingIncomingUri = it }
        extractShortcutActionFromIntent(intent)?.let { pendingShortcutAction = it }
    }

    private fun extractShortcutActionFromIntent(intent: Intent?): String? {
        val action = intent?.action ?: return null
        return when (action) {
            ACTION_SHORTCUT_EXTRACT,
            ACTION_SHORTCUT_LABS,
            ACTION_SHORTCUT_SETTINGS -> action
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
fun KaiHatchApp(viewModel: HatchViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCompletedOnboarding by viewModel.hasCompletedOnboarding.collectAsState()

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

    KaiHatchRootContent(
        viewModel = viewModel,
        onPickArchive = onPickArchive,
        onPickExplorerArchive = onPickExplorerArchive,
        onPickCompressFiles = onPickCompressFiles
    )
}

@Composable
private fun KaiHatchRootContent(
    viewModel: HatchViewModel,
    onPickArchive: () -> Unit,
    onPickExplorerArchive: () -> Unit,
    onPickCompressFiles: () -> Unit
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val activePageTab by viewModel.activePageTab.collectAsState()
    val isSwipeTabsEnabled by viewModel.isSwipeTabsEnabled.collectAsState()
    val isBlurEnabled by viewModel.isBlurEnabled.collectAsState()

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

    // Keep ViewModel activePageTab synced with Pager settledPage on manual swipe
    LaunchedEffect(pagerState.settledPage) {
        val settledSpec = PagerDockTabs.getOrNull(pagerState.settledPage) ?: PagerDockTabs.first()
        if (!showSettings && activePageTab != settledSpec.tab && pagerState.targetPage == pagerState.settledPage) {
            HapticUtil.tick(view)
            viewModel.switchTab(settledSpec.tab)
        }
    }

    // Keep Pager synced when ViewModel selects tab programmatically
    LaunchedEffect(activePageTab) {
        val targetIndex = PagerDockTabs.indexOfFirst { it.tab == activePageTab }
        if (targetIndex >= 0 && pagerState.currentPage != targetIndex) {
            pagerState.animateScrollToPage(
                page = targetIndex,
                animationSpec = DockPillSpring
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

    // Pre-allocated progressive gradient brushes so drawBehind performs zero per-frame allocations at 120fps
    val topProgressiveBrush = remember(bgSurface, surfaceContainerTint) {
        Brush.verticalGradient(
            0.00f to bgSurface.copy(alpha = 0.98f),
            0.38f to bgSurface.copy(alpha = 0.86f),
            0.72f to surfaceContainerTint.copy(alpha = 0.45f),
            1.00f to Color.Transparent
        )
    }
    val bottomProgressiveBrush = remember(bgSurface, surfaceContainerTint, primaryTint) {
        Brush.verticalGradient(
            0.00f to Color.Transparent,
            0.28f to bgSurface.copy(alpha = 0.42f),
            0.62f to surfaceContainerTint.copy(alpha = 0.84f),
            0.85f to bgSurface.copy(alpha = 0.95f),
            1.00f to bgSurface.copy(alpha = 0.99f)
        )
    }

    val blurScrimAlphaState = animateFloatAsState(
        targetValue = if (isBlurEnabled) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "progressive_blur_alpha"
    )
    val isBlurScrimMounted by remember {
        derivedStateOf { isBlurEnabled || blurScrimAlphaState.value > 0.001f }
    }

    val settingsProgressState = animateFloatAsState(
        targetValue = if (showSettings) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "settings_transition"
    )
    // Only flips at transition boundaries so intermediate frames never trigger root recomposition
    val isSettingsMounted by remember {
        derivedStateOf { showSettings || settingsProgressState.value > 0.001f }
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
                        val p = if (showSpaceLab) 1f else settingsProgressState.value
                        alpha = (1f - p * 1.25f).coerceIn(0f, 1f)
                        val s = 1f - (p * 0.035f)
                        scaleX = s
                        scaleY = s
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

            // 1b. Space Lab Benchmark Overlay (Opened inside Settings)
            if (showSpaceLab) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgSurface)
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
                        .graphicsLayer { alpha = blurScrimAlphaState.value }
                        .drawBehind {
                            drawRect(brush = topProgressiveBrush)
                        }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = blurScrimAlphaState.value }
                        .drawBehind {
                            drawRect(brush = bottomProgressiveBrush)
                        }
                )
            }

            // 4. Contextual Floating Action Tile (Bottom-Right above dock)
            if (!showSettings && !showSpaceLab) {
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
                            animationSpec = DockPillSpring
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

/**
 * Floating Action Overlay above the bottom dock.
 * Scopes StateFlow subscriptions to the active tab so background state changes never trigger cross-tab recomposition.
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
    val extractionProgress by viewModel.extractionProgress.collectAsState()
    val compressionProgress by viewModel.compressionProgress.collectAsState()
    val view = LocalView.current

    val isExtracting = extractionProgress.status == ExtractionStatus.EXTRACTING ||
        extractionProgress.status == ExtractionStatus.ANALYZING ||
        extractionProgress.status == ExtractionStatus.TRUNCATING
    val isCompressing = compressionProgress.status == CompressionStatus.COMPRESSING ||
        compressionProgress.status == CompressionStatus.PREPARING

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isExtracting || isCompressing) {
            val rawProgress = if (isExtracting) {
                extractionProgress.overallProgressFraction
            } else {
                compressionProgress.overallProgressFraction
            }
            val animatedProgressState = animateFloatAsState(
                targetValue = rawProgress.coerceIn(0f, 1f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "stop_tile_progress_outline"
            )
            ExpressiveProgressStopTile(
                progressProvider = { animatedProgressState.value },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                progressOutlineColor = MaterialTheme.colorScheme.error,
                onClick = {
                    HapticUtil.heavy(view)
                    if (isExtracting) viewModel.cancelExtraction()
                    if (isCompressing) viewModel.cancelCompression()
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
    val archiveSummary by viewModel.archiveSummary.collectAsState()
    val view = LocalView.current

    if (archiveSummary == null) {
        ExpressiveActionTile(
            icon = Icons.Default.FolderOpen,
            label = "OPEN",
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
    val exploringArchive by viewModel.exploringArchive.collectAsState()
    val exploringSafUri by viewModel.exploringSafUri.collectAsState()
    val selectedExplorerPaths by viewModel.selectedExplorerPaths.collectAsState()
    val view = LocalView.current

    val hasExplorerLoaded = exploringArchive != null || exploringSafUri != null
    if (hasExplorerLoaded && selectedExplorerPaths.isNotEmpty()) {
        ExpressiveActionTile(
            icon = Icons.Default.Unarchive,
            label = "EXTRACT (${selectedExplorerPaths.size})",
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
            label = "OPEN",
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
    val labGenerating by viewModel.labGenerating.collectAsState()
    val view = LocalView.current

    if (!labGenerating) {
        ExpressiveActionTile(
            icon = Icons.Default.Science,
            label = "DEMO",
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
    val historyRecords by viewModel.historyRepo.records.collectAsState()
    val view = LocalView.current

    if (historyRecords.isNotEmpty()) {
        ExpressiveActionTile(
            icon = Icons.Default.DeleteSweep,
            label = "CLEAR",
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
            label = "OPEN",
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

@Composable
private fun ExpressiveProgressStopTile(
    progressProvider: () -> Float,
    containerColor: Color,
    contentColor: Color,
    progressOutlineColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .size(width = 68.dp, height = 68.dp)
            .clip(ActionTileShape)
            .background(containerColor)
            .clickable(onClick = onClick)
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
                val trackColor = contentColor.copy(alpha = 0.2f)

                onDrawWithContent {
                    drawContent()
                    drawPath(
                        path = fullPath,
                        color = trackColor,
                        style = trackStroke
                    )
                    val progress = progressProvider().coerceIn(0f, 1f)
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
                        color = progressOutlineColor,
                        style = progressStroke
                    )
                }
            }
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .testTag("floating_stop_action_tile"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = "STOP",
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "STOP",
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
                                    contentDescription = "Back to workspace",
                                    tint = activeContentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Text(
                                text = "Settings",
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
                                    contentDescription = "Back to Settings",
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
                                    contentDescription = "Space Lab",
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
                                    val indicatorLeft = fractionalPage * itemW
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
                                        animationSpec = TabIconColorSpring,
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
            val companionBgColor = if (dockMode != DockOverlayMode.WORKSPACE) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                dockContainerColor
            }
            val companionIconTint = if (dockMode != DockOverlayMode.WORKSPACE) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(companionBgColor)
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
                    contentDescription = if (isHomeButton) "Home" else "Settings",
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
            contentDescription = spec.title,
            tint = tintColor,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = spec.title,
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
