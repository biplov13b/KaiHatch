package com.biplov.kaihatch.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.biplov.kaihatch.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biplov.kaihatch.data.ExtractedFileEntry
import com.biplov.kaihatch.data.ExtractedFolderEntry
import com.biplov.kaihatch.data.FilePreviewData
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.ArchiveSummary
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.PlanModeVerdict
import com.biplov.kaihatch.model.PreExtractionStoragePlan
import com.biplov.kaihatch.model.StoragePlannerRecommendation
import com.biplov.kaihatch.model.VerificationLevel
import com.biplov.kaihatch.model.VerificationStatus
import com.biplov.kaihatch.ui.CompletedExtractionTarget
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.ScreenTab
import com.biplov.kaihatch.ui.components.ArchivePreviewerDialog
import com.biplov.kaihatch.ui.components.ExpressiveBentoTile
import com.biplov.kaihatch.ui.components.ExpressiveCleanHeader
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupPosition
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
import com.biplov.kaihatch.ui.components.ExpressiveMorphButton
import com.biplov.kaihatch.ui.components.ExpressiveMotion
import com.biplov.kaihatch.ui.components.ExpressiveSectionHeader
import com.biplov.kaihatch.ui.components.ExpressiveShapes
import com.biplov.kaihatch.ui.components.ExpressiveWaveProgressIndicator
import com.biplov.kaihatch.ui.components.FileViewerDialog
import com.biplov.kaihatch.ui.components.HapticUtil
import com.biplov.kaihatch.ui.components.JournalTerminal
import com.biplov.kaihatch.ui.components.StorageGauge
import com.biplov.kaihatch.ui.components.groupPositionFor
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val INLINE_ANIMATED_NODE_LIMIT = 30
private const val LARGE_FOLDER_HEAD_BATCH = 24
private const val MAX_CLOSING_VIEWPORT_NODES = 16

/**
 * Resolves the exact [ExtractedFolderEntry] associated with a completed extraction operation.
 *
 * Prioritizes canonical SAF document URIs / IDs and canonical local filesystem paths before
 * falling back to exact folder identity. Never falls back to `extractedFolders.firstOrNull()`,
 * returning `null` if the target output folder is not yet present in [extractedFolders].
 */
internal fun resolveTargetExtractedFolder(
    extractedFolders: List<ExtractedFolderEntry>,
    completedTarget: CompletedExtractionTarget?,
    fallbackOutputDirectory: String? = null
): ExtractedFolderEntry? {
    if (extractedFolders.isEmpty()) return null

    if (completedTarget != null) {
        if (!completedTarget.folderDocUriString.isNullOrEmpty() || !completedTarget.folderDocumentId.isNullOrEmpty()) {
            val bySaf = extractedFolders.firstOrNull { folder ->
                (!completedTarget.folderDocUriString.isNullOrEmpty() &&
                    folder.safUri?.toString() == completedTarget.folderDocUriString) ||
                    (!completedTarget.folderDocumentId.isNullOrEmpty() &&
                        folder.id == completedTarget.folderDocumentId)
            }
            if (bySaf != null) return bySaf
        }

        if (!completedTarget.canonicalDirPath.isNullOrEmpty() || !completedTarget.absoluteDirPath.isNullOrEmpty()) {
            val byPath = extractedFolders.firstOrNull { folder ->
                val localFile = folder.localFile
                val folderCanonical = folder.canonicalPath ?: localFile?.absolutePath
                (!completedTarget.canonicalDirPath.isNullOrEmpty() && (
                    folderCanonical == completedTarget.canonicalDirPath ||
                        folder.id == completedTarget.canonicalDirPath
                    )) ||
                    (!completedTarget.absoluteDirPath.isNullOrEmpty() && (
                        localFile?.absolutePath == completedTarget.absoluteDirPath ||
                            folder.id == completedTarget.absoluteDirPath
                        ))
            }
            if (byPath != null) return byPath
        }

        val outDir = completedTarget.outputDirectory
        if (!outDir.isNullOrEmpty()) {
            val byIdOrUri = extractedFolders.firstOrNull { folder ->
                folder.id == outDir || folder.safUri?.toString() == outDir
            }
            if (byIdOrUri != null) return byIdOrUri
        }

        val expectedName = completedTarget.folderName.ifEmpty { outDir?.substringAfterLast('/') ?: "" }
        if (expectedName.isNotEmpty()) {
            val byName = extractedFolders.firstOrNull { folder ->
                val folderCanonical = folder.canonicalPath ?: folder.localFile?.absolutePath
                val hasConflictingLocalPath = folder.localFile != null &&
                    !completedTarget.canonicalDirPath.isNullOrEmpty() &&
                    !folder.isSaf &&
                    folderCanonical != completedTarget.canonicalDirPath
                !hasConflictingLocalPath && folder.name == expectedName
            } ?: extractedFolders.firstOrNull { folder ->
                val folderCanonical = folder.canonicalPath ?: folder.localFile?.absolutePath
                val hasConflictingLocalPath = folder.localFile != null &&
                    !completedTarget.canonicalDirPath.isNullOrEmpty() &&
                    !folder.isSaf &&
                    folderCanonical != completedTarget.canonicalDirPath
                !hasConflictingLocalPath && folder.name.equals(expectedName, ignoreCase = true)
            }
            if (byName != null) return byName
        }

        return null
    }

    val outDir = fallbackOutputDirectory?.takeIf { it.isNotBlank() } ?: return null

    val byIdOrUri = extractedFolders.firstOrNull { folder ->
        folder.id == outDir || folder.safUri?.toString() == outDir
    }
    if (byIdOrUri != null) return byIdOrUri

    val byLocalPath = extractedFolders.firstOrNull { folder ->
        val localFile = folder.localFile ?: return@firstOrNull false
        val folderCanonical = folder.canonicalPath ?: localFile.absolutePath
        localFile.absolutePath == outDir || folderCanonical == outDir
    }
    if (byLocalPath != null) return byLocalPath

    val leafName = outDir.substringAfterLast('/')
    if (leafName.isNotEmpty()) {
        return extractedFolders.firstOrNull { it.name == leafName }
            ?: extractedFolders.firstOrNull { it.name.equals(leafName, ignoreCase = true) }
    }

    return null
}

/**
 * Determines whether [folder] is the single currently expanded folder.
 */
internal fun isExtractedFolderExpanded(
    folder: ExtractedFolderEntry,
    expandedFolderId: String?,
    allFolders: List<ExtractedFolderEntry>
): Boolean {
    if (expandedFolderId.isNullOrEmpty()) return false
    if (allFolders.any { it.id == expandedFolderId }) {
        return folder.id == expandedFolderId
    }
    val resolved = resolveTargetExtractedFolder(
        extractedFolders = allFolders,
        completedTarget = null,
        fallbackOutputDirectory = expandedFolderId
    )
    return resolved?.id == folder.id
}

internal enum class TreeNodeType {
    SUBFOLDER,
    FILE,
    EMPTY_SUBFOLDER_NOTICE
}

@androidx.compose.runtime.Immutable
internal data class VisibleExtractedTreeNode(
    val uniqueKey: String,
    val rootFolderId: String = "",
    val entry: ExtractedFileEntry?,
    val depth: Int,
    val nodeType: TreeNodeType,
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val cachedChildCount: Int? = null,
    val parentSubfolderId: String = "",
    val parentFolderId: String = rootFolderId,
    val isClosing: Boolean = false
) {
    val contentType: String
        get() = nodeType.name
}

/**
 * Builds the flattened virtualized list of currently visible directory tree nodes for [rootFolder].
 * Only traverses into subfolders whose IDs are present in [expandedSubfolderIds] (plus a bounded
 * viewport slice for [closingSubfolderIds] during reverse collapse transitions), preserving
 * folders-first alphabetical ordering at every level without recursively loading unopened directories.
 */
internal fun buildVisibleExtractedTreeNodes(
    rootFolder: ExtractedFolderEntry,
    expandedSubfolderIds: Set<String>,
    subfolderCache: Map<String, List<ExtractedFileEntry>>,
    loadingSubfolderIds: Set<String> = emptySet(),
    closingSubfolderIds: Set<String> = emptySet(),
    isRootClosing: Boolean = false
): List<VisibleExtractedTreeNode> {
    if (rootFolder.children.isEmpty()) return emptyList()
    val result = ArrayList<VisibleExtractedTreeNode>(
        if (isRootClosing) minOf(rootFolder.children.size, MAX_CLOSING_VIEWPORT_NODES)
        else rootFolder.children.size + expandedSubfolderIds.size * 4
    )
    val visitedDirs = HashSet<String>()
    var totalClosingNodes = 0

    fun appendLevel(
        entries: List<ExtractedFileEntry>,
        depth: Int,
        parentId: String,
        parentClosing: Boolean
    ) {
        val sortedEntries = StorageHelper.sortExtractedEntries(entries)
        for (entry in sortedEntries) {
            if (parentClosing && totalClosingNodes >= MAX_CLOSING_VIEWPORT_NODES) break
            if (parentClosing) totalClosingNodes++

            if (entry.isDirectory) {
                val dirId = entry.id
                val isExpanded = !parentClosing && (dirId in expandedSubfolderIds)
                val isDirClosing = parentClosing || (!isExpanded && dirId in closingSubfolderIds)
                val isLoading = dirId in loadingSubfolderIds
                val cachedChildren = subfolderCache[dirId]
                result.add(
                    VisibleExtractedTreeNode(
                        uniqueKey = "node_${rootFolder.id}_$dirId",
                        rootFolderId = rootFolder.id,
                        entry = entry,
                        depth = depth,
                        nodeType = TreeNodeType.SUBFOLDER,
                        isExpanded = isExpanded,
                        isLoading = isLoading,
                        cachedChildCount = cachedChildren?.size,
                        parentFolderId = parentId,
                        isClosing = parentClosing
                    )
                )
                if ((isExpanded || isDirClosing) && visitedDirs.add(dirId)) {
                    if (cachedChildren != null) {
                        if (cachedChildren.isEmpty() && !isLoading) {
                            result.add(
                                VisibleExtractedTreeNode(
                                    uniqueKey = "empty_${rootFolder.id}_$dirId",
                                    rootFolderId = rootFolder.id,
                                    entry = null,
                                    depth = depth + 1,
                                    nodeType = TreeNodeType.EMPTY_SUBFOLDER_NOTICE,
                                    parentSubfolderId = dirId,
                                    parentFolderId = dirId,
                                    isClosing = isDirClosing
                                )
                            )
                        } else {
                            appendLevel(
                                entries = cachedChildren,
                                depth = depth + 1,
                                parentId = dirId,
                                parentClosing = isDirClosing
                            )
                        }
                    }
                }
            } else {
                result.add(
                    VisibleExtractedTreeNode(
                        uniqueKey = "node_${rootFolder.id}_${entry.id}",
                        rootFolderId = rootFolder.id,
                        entry = entry,
                        depth = depth,
                        nodeType = TreeNodeType.FILE,
                        parentFolderId = parentId,
                        isClosing = parentClosing
                    )
                )
            }
        }
    }

    appendLevel(
        entries = rootFolder.children,
        depth = 0,
        parentId = rootFolder.id,
        parentClosing = isRootClosing
    )
    return result
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HatchExtractScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val selectedFile by viewModel.selectedArchiveFile.collectAsStateWithLifecycle()
    val selectedSafUri by viewModel.selectedSafUri.collectAsStateWithLifecycle()
    val archiveSummary by viewModel.archiveSummary.collectAsStateWithLifecycle()
    val isHatchMode by viewModel.isHatchMode.collectAsStateWithLifecycle()
    val archivePassword by viewModel.archivePassword.collectAsStateWithLifecycle()
    val progressState = viewModel.extractionProgress.collectAsStateWithLifecycle()
    val pendingResumeStates by viewModel.pendingResumeStates.collectAsStateWithLifecycle()
    val availableArchives by viewModel.availableArchives.collectAsStateWithLifecycle()
    val extractedFolders by viewModel.extractedFolders.collectAsStateWithLifecycle()
    val expandedSubfolderIds by viewModel.expandedSubfolderIds.collectAsStateWithLifecycle()
    val subfolderChildrenCache by viewModel.subfolderChildrenCache.collectAsStateWithLifecycle()
    val loadingSubfolderIds by viewModel.loadingSubfolderIds.collectAsStateWithLifecycle()
    val lastCompletedTarget by viewModel.lastCompletedTarget.collectAsStateWithLifecycle()
    val fileSizesCache by viewModel.fileSizesCache.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val grantedFolder by viewModel.grantedFolder.collectAsStateWithLifecycle()
    val showPersistentFolderPrompt by viewModel.showPersistentFolderPrompt.collectAsStateWithLifecycle()
    val verificationState by viewModel.verificationState.collectAsStateWithLifecycle()
    val consoleLogsState = viewModel.consoleLogs.collectAsStateWithLifecycle()

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondary = MaterialTheme.colorScheme.secondary
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer
    val onTertiaryContainer = MaterialTheme.colorScheme.onTertiaryContainer

    val density = LocalDensity.current
    val scrollTopPaddingPx = remember(density) { with(density) { 12.dp.roundToPx() } }
    val lazyListState = rememberLazyListState()
    var showArchivePreviewer by remember { mutableStateOf(false) }
    var showPasswordField by remember(archiveSummary?.fileName, archiveSummary?.hasEncryptedEntries) {
        mutableStateOf(archiveSummary?.hasEncryptedEntries == true)
    }
    var showVerifyPanel by remember(archiveSummary?.fileName) { mutableStateOf(true) }
    var showConsoleWhenIdle by remember(archiveSummary?.fileName) { mutableStateOf(false) }
    var showStorageComparison by remember(archiveSummary?.fileName) { mutableStateOf(false) }
    var showInPlaceConfirmDialog by remember { mutableStateOf(false) }
    var skipInPlaceConfirmSession by remember { mutableStateOf(false) }
    var expandedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var handledAutoExpandCompletionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingScrollFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingFileEntry by remember { mutableStateOf<ExtractedFileEntry?>(null) }
    var viewingPreviewData by remember { mutableStateOf<FilePreviewData?>(null) }
    var showDiagnosticLogDialog by remember { mutableStateOf(false) }
    var folderPendingDeletion by remember { mutableStateOf<ExtractedFolderEntry?>(null) }
    var filePendingDeletion by remember { mutableStateOf<ExtractedFileEntry?>(null) }

    val extractionStatus by remember {
        derivedStateOf { progressState.value.status }
    }
    val isExtractionRunning by remember {
        derivedStateOf { progressState.value.status.isActiveSession }
    }
    val completedOutputDirectory by remember {
        derivedStateOf { progressState.value.outputDirectory }
    }

    val resolvedExpandedFolderId: String? = remember(extractedFolders, expandedFolderPath) {
        val targetId = expandedFolderPath
        if (targetId.isNullOrEmpty()) {
            null
        } else if (extractedFolders.any { it.id == targetId }) {
            targetId
        } else {
            resolveTargetExtractedFolder(
                extractedFolders = extractedFolders,
                completedTarget = null,
                fallbackOutputDirectory = targetId
            )?.id
        }
    }

    val isScrollingExtractList by remember {
        derivedStateOf { lazyListState.isScrollInProgress }
    }
    LaunchedEffect(isScrollingExtractList) {
        com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.setInteractionActive(
            "scroll:extract",
            isScrollingExtractList
        )
    }
    LaunchedEffect(isExtractionRunning) {
        com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.setInteractionActive(
            "extract:active",
            isExtractionRunning
        )
    }

    // Pre-warm direct subfolders of the currently expanded root folder and any expanded subfolders
    // on Dispatchers.IO so tapping a subfolder starts its height transition immediately on frame 0.
    LaunchedEffect(resolvedExpandedFolderId, extractedFolders, expandedSubfolderIds, subfolderChildrenCache.keys) {
        val activeRoot = resolvedExpandedFolderId?.let { id ->
            extractedFolders.firstOrNull { it.id == id }
        }
        if (activeRoot != null) {
            viewModel.prefetchSubfolderChildren(activeRoot.children.filter { it.isDirectory })
        }
        expandedSubfolderIds.forEach { expandedSubId ->
            val cached = subfolderChildrenCache[expandedSubId]
            if (!cached.isNullOrEmpty()) {
                viewModel.prefetchSubfolderChildren(cached.filter { it.isDirectory })
            }
        }
    }

    val toggleRootFolderExpansion: (ExtractedFolderEntry, Boolean) -> Unit = { folder, currentlyExpanded ->
        com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.markEvent(
            "root_folder_toggle",
            "${folder.name}: ${if (currentlyExpanded) "collapse" else "expand"}"
        )
        if (currentlyExpanded) {
            expandedFolderPath = null
        } else {
            expandedFolderPath = folder.id
            viewModel.prefetchSubfolderChildren(folder.children.filter { it.isDirectory })
        }
    }

    val toggleSubfolderWithAnimation: (ExtractedFileEntry) -> Unit = { subfolderEntry ->
        com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.markEvent(
            "subfolder_toggle",
            subfolderEntry.name
        )
        viewModel.toggleSubfolderExpanded(subfolderEntry)
    }

    val completionKey by remember(lastCompletedTarget) {
        derivedStateOf {
            val p = progressState.value
            when {
                p.status != ExtractionStatus.COMPLETED -> null
                lastCompletedTarget != null -> {
                    val target = lastCompletedTarget!!
                    "target_${target.completionId}_${target.folderDocUriString.orEmpty()}_${target.canonicalDirPath.orEmpty()}_${target.outputDirectory.orEmpty()}"
                }
                !p.outputDirectory.isNullOrEmpty() -> {
                    "progress_${p.startTime}_${p.outputDirectory}"
                }
                else -> null
            }
        }
    }

    LaunchedEffect(completionKey, lastCompletedTarget, completedOutputDirectory, extractedFolders, extractionStatus) {
        if (extractionStatus == ExtractionStatus.COMPLETED &&
            completionKey != null &&
            handledAutoExpandCompletionKey != completionKey
        ) {
            val matched = resolveTargetExtractedFolder(
                extractedFolders = extractedFolders,
                completedTarget = lastCompletedTarget,
                fallbackOutputDirectory = completedOutputDirectory
            )
            if (matched != null) {
                viewModel.collapseAllSubfolders()
                expandedFolderPath = matched.id
                handledAutoExpandCompletionKey = completionKey
                pendingScrollFolderId = matched.id
            }
        }
    }

    LaunchedEffect(pendingScrollFolderId, extractedFolders) {
        val targetId = pendingScrollFolderId ?: return@LaunchedEffect
        val folderIdx = extractedFolders.indexOfFirst { it.id == targetId }
        if (folderIdx < 0) return@LaunchedEffect

        withFrameNanos { }
        val targetKey = "extracted_folder_header_$targetId"
        val existingInfo = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }
        val targetItemIndex = existingInfo?.index ?: (2 + folderIdx)
        runCatching {
            lazyListState.animateScrollToItem(
                index = targetItemIndex.coerceAtLeast(0),
                scrollOffset = -scrollTopPaddingPx
            )
        }
        pendingScrollFolderId = null
    }

    val changeArchivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            StorageHelper.takePersistablePermission(context, uri)
            viewModel.selectSafUriZeroCopy(uri)
        }
    }

    val destinationFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFolderPermissionGranted(uri)
        } else {
            viewModel.onFolderPickerCancelled()
        }
    }

    if (showPersistentFolderPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPersistentFolderPrompt() },
            icon = {
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = primary
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.dialog_persistent_folder_extract_title),
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_persistent_folder_extract_body)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onPersistentFolderPickerLaunched()
                        destinationFolderPickerLauncher.launch(null)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary,
                        contentColor = onPrimary
                    ),
                    modifier = Modifier.testTag("confirm_select_persistent_folder_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.dialog_persistent_folder_extract_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissPersistentFolderPrompt() },
                    modifier = Modifier.testTag("cancel_persistent_folder_prompt_button")
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("persistent_folder_prompt_dialog")
        )
    }

    if (showDiagnosticLogDialog) {
        DiagnosticLogDialog(
            progress = progressState.value,
            archiveSummary = archiveSummary,
            consoleLogs = consoleLogsState.value,
            onClearLogs = { viewModel.clearConsoleLogs() },
            onDismiss = { showDiagnosticLogDialog = false }
        )
    }

    if (showArchivePreviewer && archiveSummary != null) {
        ArchivePreviewerDialog(
            summary = archiveSummary!!,
            archiveFile = selectedFile,
            safUri = selectedSafUri,
            password = archivePassword.ifEmpty { null },
            onOpenInExplorer = {
                if (selectedFile != null) {
                    viewModel.openArchiveInExplorer(selectedFile!!)
                } else if (selectedSafUri != null) {
                    viewModel.openSafUriInExplorer(selectedSafUri!!)
                }
            },
            onExtractArchive = {
                viewModel.startExtraction()
            },
            onDismiss = { showArchivePreviewer = false }
        )
    }

    if (viewingFileEntry != null && viewingPreviewData != null) {
        val targetEntry = viewingFileEntry!!
        FileViewerDialog(
            previewData = viewingPreviewData!!,
            fileEntry = targetEntry,
            onResolveFileForAction = { targetEntry.localFile },
            onDismiss = {
                viewingFileEntry = null
                viewingPreviewData = null
            }
        )
    }

    if (folderPendingDeletion != null) {
        AlertDialog(
            onDismissRequest = { folderPendingDeletion = null },
            icon = {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.dialog_delete_folder_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(stringResource(R.string.dialog_delete_folder_body, folderPendingDeletion!!.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = folderPendingDeletion
                        folderPendingDeletion = null
                        if (toDelete != null) {
                            if (expandedFolderPath == toDelete.id) {
                                expandedFolderPath = null
                            }
                            viewModel.deleteExtractedFolder(toDelete)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDeletion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (filePendingDeletion != null) {
        val isDeletingSubfolder = filePendingDeletion!!.isDirectory
        AlertDialog(
            onDismissRequest = { filePendingDeletion = null },
            icon = {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(
                    text = stringResource(
                        if (isDeletingSubfolder) R.string.dialog_delete_folder_title else R.string.dialog_delete_file_title
                    ),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (isDeletingSubfolder) R.string.dialog_delete_folder_body else R.string.dialog_delete_file_body,
                        filePendingDeletion!!.name
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = filePendingDeletion
                        filePendingDeletion = null
                        toDelete?.let { viewModel.deleteExtractedFile(it) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDeletion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showInPlaceConfirmDialog && archiveSummary != null) {
        Dialog(
            onDismissRequest = { showInPlaceConfirmDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("in_place_destructive_confirm_dialog"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.dialog_inplace_confirm_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { showInPlaceConfirmDialog = false },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .testTag("close_in_place_confirm_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.dialog_inplace_confirm_body, archiveSummary!!.fileName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                showInPlaceConfirmDialog = false
                                viewModel.setHatchMode(false)
                            },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("switch_to_standard_from_dialog_button")
                        ) {
                            Text(
                                text = stringResource(R.string.dialog_inplace_use_standard),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = {
                                showInPlaceConfirmDialog = false
                                skipInPlaceConfirmSession = true
                                viewModel.startExtraction()
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("confirm_in_place_extract_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.dialog_inplace_use_inplace),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    val defaultFileType = stringResource(R.string.extract_file_default_type)

    // Prebuild level-0 preview nodes for small folders once when extractedFolders changes,
    // so subfolder toggles inside the active folder never rebuild non-expanded folders.
    val collapsedPreviewNodesByFolderId = remember(extractedFolders) {
        extractedFolders.associate { folder ->
            folder.id to if (folder.children.isNotEmpty() && folder.children.size <= INLINE_ANIMATED_NODE_LIMIT) {
                buildVisibleExtractedTreeNodes(
                    rootFolder = folder,
                    expandedSubfolderIds = emptySet(),
                    subfolderCache = emptyMap(),
                    loadingSubfolderIds = emptySet()
                )
            } else {
                emptyList()
            }
        }
    }

    val activeExpandedFolderNodes = remember(
        extractedFolders,
        resolvedExpandedFolderId,
        expandedSubfolderIds,
        subfolderChildrenCache,
        loadingSubfolderIds
    ) {
        val activeFolder = resolvedExpandedFolderId?.let { targetId ->
            extractedFolders.firstOrNull { it.id == targetId }
        }
        if (activeFolder != null && activeFolder.children.isNotEmpty()) {
            com.biplov.kaihatch.ui.diagnostics.UiPerformanceMonitor.traceSection("buildVisibleExtractedTreeNodes") {
                buildVisibleExtractedTreeNodes(
                    rootFolder = activeFolder,
                    expandedSubfolderIds = expandedSubfolderIds,
                    subfolderCache = subfolderChildrenCache,
                    loadingSubfolderIds = loadingSubfolderIds
                )
            }
        } else {
            emptyList()
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = topInsetPadding + 8.dp, bottom = 244.dp)
    ) {
        item(key = "top_extraction_controls", contentType = "top_controls") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            ExpressiveCleanHeader(
                badgeText = "",
                title = stringResource(R.string.extract_header_title),
                subtitle = stringResource(R.string.extract_header_subtitle)
            )
        }

        // Crash-Safe Interrupted Extraction Resume Cards (.hatchstate.json)
        if (pendingResumeStates.isNotEmpty() && !isExtractionRunning) {
            pendingResumeStates.forEachIndexed { idx, resumeState ->
                val recoveryCard = remember(resumeState, context) {
                    val resumeElig = com.biplov.kaihatch.data.ResumeJournalManager.assessResumeEligibility(resumeState, context)
                    val restoreElig = com.biplov.kaihatch.data.ResumeJournalManager.assessRestoreEligibility(resumeState, context)
                    com.biplov.kaihatch.ui.resolveRecoveryCardState(
                        resumeState = resumeState,
                        resumeEligibility = resumeElig,
                        restoreEligibility = restoreElig
                    )
                }
                val isUnavailableCard = recoveryCard.kind == com.biplov.kaihatch.ui.RecoveryCardKind.UNAVAILABLE
                val cardBg = if (isUnavailableCard) MaterialTheme.colorScheme.errorContainer else tertiaryContainer
                val cardFg = if (isUnavailableCard) MaterialTheme.colorScheme.onErrorContainer else onTertiaryContainer
                val cardIcon = if (isUnavailableCard) Icons.Default.ErrorOutline else Icons.Default.Restore
                val cardIconBg = if (isUnavailableCard) MaterialTheme.colorScheme.error else primaryContainer
                val cardIconTint = if (isUnavailableCard) MaterialTheme.colorScheme.onError else primary
                val cardTitle = when (recoveryCard.kind) {
                    com.biplov.kaihatch.ui.RecoveryCardKind.BOTH_AVAILABLE ->
                        stringResource(R.string.extract_resume_ready_title)
                    com.biplov.kaihatch.ui.RecoveryCardKind.RESUME_ONLY ->
                        stringResource(R.string.extract_resume_only_title)
                    com.biplov.kaihatch.ui.RecoveryCardKind.RESTORE_ONLY ->
                        stringResource(R.string.extract_restore_only_title)
                    com.biplov.kaihatch.ui.RecoveryCardKind.RESTORE_PENDING ->
                        stringResource(R.string.extract_restore_pending_title)
                    com.biplov.kaihatch.ui.RecoveryCardKind.UNAVAILABLE ->
                        stringResource(R.string.extract_recovery_unavailable_title)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("resume_journal_card_$idx"),
                    shape = RoundedCornerShape(26.dp),
                    color = cardBg
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ExpressiveCloverIcon(
                                    icon = cardIcon,
                                    containerColor = cardIconBg,
                                    iconColor = cardIconTint,
                                    size = 42.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = cardTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = cardFg,
                                        modifier = Modifier.testTag("recovery_card_title_$idx")
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.extract_resume_ready_subtitle,
                                            resumeState.archiveName,
                                            recoveryCard.remainingEntriesCount,
                                            resumeState.totalEntries
                                        ),
                                        fontSize = 11.sp,
                                        color = cardFg.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.dismissResumeState(resumeState) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .testTag("dismiss_resume_state_button_$idx")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.extract_resume_dismiss_cd),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (recoveryCard.showResumeButton) {
                            ExpressiveMorphButton(
                                text = stringResource(R.string.extract_resume_button_title),
                                subtitle = stringResource(
                                    R.string.extract_resume_button_subtitle,
                                    StorageHelper.formatBytes(resumeState.bytesReclaimedSoFar)
                                ),
                                icon = Icons.Default.Restore,
                                onClick = { viewModel.resumeInterruptedTask(resumeState) },
                                containerColor = primary,
                                contentColor = onPrimary,
                                height = 52.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("resume_interrupted_button_$idx")
                            )
                        }

                        if (recoveryCard.showRestoreButton) {
                            if (recoveryCard.showResumeButton) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            val restoreTitle = if (recoveryCard.isPendingRestoreRetry) {
                                stringResource(R.string.extract_restore_retry_title)
                            } else {
                                stringResource(R.string.extract_restore_remaining_zip_title)
                            }
                            val restoreSubtitle = when {
                                recoveryCard.isPendingRestoreRetry ->
                                    stringResource(R.string.extract_restore_retry_subtitle, recoveryCard.restoreRemainingCount)
                                recoveryCard.pendingDeflateTailSwapEntryPath != null ->
                                    stringResource(
                                        R.string.extract_restore_finish_entry_then_zip_subtitle,
                                        recoveryCard.restoreRemainingCount
                                    )
                                else ->
                                    stringResource(R.string.extract_restore_remaining_zip_subtitle, recoveryCard.restoreRemainingCount)
                            }
                            ExpressiveMorphButton(
                                text = restoreTitle,
                                subtitle = restoreSubtitle,
                                icon = Icons.Default.Archive,
                                onClick = { viewModel.restoreInterruptedZip(resumeState) },
                                containerColor = if (!recoveryCard.showResumeButton) primary else secondaryContainer,
                                contentColor = if (!recoveryCard.showResumeButton) onPrimary else onSecondaryContainer,
                                height = 48.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("restore_remaining_zip_button_$idx")
                            )
                            if (recoveryCard.isPendingRestoreRetry) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.extract_restore_pending_hint),
                                    fontSize = 11.sp,
                                    color = cardFg.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("recovery_pending_restore_hint_$idx")
                                )
                            }
                        }

                        if (!recoveryCard.secondaryReason.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = recoveryCard.secondaryReason,
                                fontSize = 11.sp,
                                color = cardFg.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recovery_secondary_reason_$idx")
                            )
                        }

                        if (isUnavailableCard && !recoveryCard.unavailableReason.isNullOrBlank()) {
                            Text(
                                text = recoveryCard.unavailableReason,
                                fontSize = 11.sp,
                                color = cardFg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("unrecoverable_resume_reason_$idx")
                            )
                        }

                        if (recoveryCard.isSafDirectPosixLimitation) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.extract_recovery_saf_limitation_note),
                                fontSize = 11.sp,
                                color = cardFg.copy(alpha = 0.88f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recovery_saf_limitation_note_$idx")
                            )
                        }
                    }
                }
            }
        }

        // STATE 1: No file selected -> Show Live Log Console directly on Extract Tab
        if (archiveSummary == null && !isExtractionRunning && extractionStatus != ExtractionStatus.COMPLETED && extractionStatus != ExtractionStatus.ERROR) {
            StandbyJournalConsoleSection(
                consoleLogsState = consoleLogsState,
                onClearLogs = { viewModel.clearConsoleLogs() }
            )
        }

        // STATE 2: File selected -> Clean Two-Layer Extraction Flow
        if (archiveSummary != null) {
            val summary = archiveSummary!!
            val consistentSavedText = remember(summary.standardPeakSpaceRequired, summary.hatchPeakSpaceRequired) {
                StorageHelper.formatConsistentSavedBytes(
                    summary.standardPeakSpaceRequired,
                    summary.hatchPeakSpaceRequired
                )
            }

            if (!isExtractionRunning) {
                // 1. Selected Archive Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_archive_card"),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header Row: Archive Icon + Name + Clean Metadata
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Archive,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = summary.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    R.string.extract_selected_archive_meta,
                                    summary.entryCount,
                                    StorageHelper.formatBytes(summary.fileSizeBytes),
                                    summary.format.extension.uppercase()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // File Management Controls: "Change Archive" + Subtle Non-Destructive "Clear"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { changeArchivePickerLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(38.dp)
                                .testTag("change_selected_archive_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.extract_change_archive),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.extract_change_archive),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.deselectArchive() },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(0.8f)
                                .height(38.dp)
                                .testTag("dismiss_selected_archive_pill")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.extract_clear_selection_cd),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.extract_clear_selection),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    Spacer(modifier = Modifier.height(6.dp))

                    // "Browse archive contents" row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showArchivePreviewer = true }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                            .testTag("open_archive_previewer_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.extract_browse_contents),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.extract_browse_contents),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 4. Verify Before Hatch (Quick Check & Full Verify pills with inline status indicators)
                    if (showVerifyPanel && !isExtractionRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        Spacer(modifier = Modifier.height(10.dp))

                        val quickStatus = verificationState.pillStatusFor(VerificationLevel.QUICK_CHECK)
                        val fullStatus = verificationState.pillStatusFor(VerificationLevel.FULL_VERIFY)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("verify_before_hatch_panel"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.verify_before_hatch_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (verificationState.isRunning) {
                                    TextButton(
                                        onClick = { viewModel.cancelArchiveVerification() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("verify_cancel_button")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.verify_cancel_button),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val quickContainerColor by animateColorAsState(
                                    targetValue = when (quickStatus) {
                                        VerificationStatus.RUNNING,
                                        VerificationStatus.PASSED_STRUCTURAL -> secondaryContainer.copy(alpha = 0.65f)
                                        VerificationStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    animationSpec = tween(durationMillis = 180),
                                    label = "quickVerifyPillBg"
                                )
                                val quickBorderColor by animateColorAsState(
                                    targetValue = when (quickStatus) {
                                        VerificationStatus.RUNNING,
                                        VerificationStatus.PASSED_STRUCTURAL -> secondary
                                        VerificationStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                    },
                                    animationSpec = tween(durationMillis = 180),
                                    label = "quickVerifyPillBorder"
                                )

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = quickContainerColor,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = quickBorderColor
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = !verificationState.isRunning) {
                                            viewModel.startArchiveVerification(VerificationLevel.QUICK_CHECK)
                                        }
                                        .testTag("verify_quick_check_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.verify_quick_check_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.verify_quick_check_subtitle),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (quickStatus != VerificationStatus.IDLE &&
                                            quickStatus != VerificationStatus.CANCELLED
                                        ) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .testTag("verify_quick_check_status_indicator"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                when (quickStatus) {
                                                    VerificationStatus.RUNNING -> {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(15.dp),
                                                            strokeWidth = 2.dp,
                                                            color = secondary
                                                        )
                                                    }
                                                    VerificationStatus.PASSED_STRUCTURAL -> {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = stringResource(R.string.verify_badge_structural),
                                                            tint = secondary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    VerificationStatus.FAILED -> {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = stringResource(R.string.verify_badge_failed),
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    VerificationStatus.UNSUPPORTED -> {
                                                        Icon(
                                                            imageVector = Icons.Default.ErrorOutline,
                                                            contentDescription = stringResource(R.string.verify_badge_unsupported),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    else -> Unit
                                                }
                                            }
                                        }
                                    }
                                }

                                val fullContainerColor by animateColorAsState(
                                    targetValue = when (fullStatus) {
                                        VerificationStatus.RUNNING,
                                        VerificationStatus.PASSED_FULL_INTEGRITY -> primaryContainer.copy(alpha = 0.65f)
                                        VerificationStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    animationSpec = tween(durationMillis = 180),
                                    label = "fullVerifyPillBg"
                                )
                                val fullBorderColor by animateColorAsState(
                                    targetValue = when (fullStatus) {
                                        VerificationStatus.RUNNING,
                                        VerificationStatus.PASSED_FULL_INTEGRITY -> primary
                                        VerificationStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                    },
                                    animationSpec = tween(durationMillis = 180),
                                    label = "fullVerifyPillBorder"
                                )

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = fullContainerColor,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = fullBorderColor
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = !verificationState.isRunning) {
                                            viewModel.startArchiveVerification(VerificationLevel.FULL_VERIFY)
                                        }
                                        .testTag("verify_full_integrity_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.verify_full_verify_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.verify_full_verify_subtitle),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (fullStatus != VerificationStatus.IDLE &&
                                            fullStatus != VerificationStatus.CANCELLED
                                        ) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .testTag("verify_full_verify_status_indicator"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                when (fullStatus) {
                                                    VerificationStatus.RUNNING -> {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(15.dp),
                                                            strokeWidth = 2.dp,
                                                            color = primary
                                                        )
                                                    }
                                                    VerificationStatus.PASSED_FULL_INTEGRITY -> {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = stringResource(R.string.verify_badge_full_integrity),
                                                            tint = primary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    VerificationStatus.FAILED -> {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = stringResource(R.string.verify_badge_failed),
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    VerificationStatus.UNSUPPORTED -> {
                                                        Icon(
                                                            imageVector = Icons.Default.ErrorOutline,
                                                            contentDescription = stringResource(R.string.verify_badge_unsupported),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    else -> Unit
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // 5. Secondary Utility Chips: Password, Test Archive, Logs (N)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (archivePassword.isNotEmpty() || showPasswordField) {
                                    primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { showPasswordField = !showPasswordField }
                                    .testTag("toggle_password_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (archivePassword.isNotEmpty()) {
                                            stringResource(R.string.extract_password_set)
                                        } else {
                                            stringResource(R.string.extract_password_label)
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (archivePassword.isNotEmpty() || showPasswordField) {
                                            onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (showVerifyPanel || verificationState.status != VerificationStatus.IDLE) {
                                    primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        showVerifyPanel = true
                                        viewModel.verifySelectedArchiveIntegrity()
                                    }
                                    .testTag("verify_archive_integrity_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = stringResource(R.string.extract_test_archive),
                                        tint = primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.extract_test_archive),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (showVerifyPanel || verificationState.status != VerificationStatus.IDLE) {
                                            onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }

                        if (!showConsoleWhenIdle && !isExtractionRunning && extractionStatus != ExtractionStatus.COMPLETED) {
                            IdleLogsTogglePill(
                                consoleLogsState = consoleLogsState,
                                onShowConsole = { showConsoleWhenIdle = true }
                            )
                        }
                    }

                    if (showPasswordField) {
                        val applyAndClosePassword = {
                            if (archivePassword.isEmpty()) {
                                viewModel.setArchivePassword("")
                            } else {
                                viewModel.setArchivePassword(archivePassword)
                            }
                            showPasswordField = false
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = archivePassword,
                            onValueChange = { viewModel.setArchivePassword(it) },
                            label = { Text(stringResource(R.string.extract_decryption_password_label)) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = primary)
                            },
                            trailingIcon = {
                                Button(
                                    onClick = applyAndClosePassword,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primary,
                                        contentColor = onPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .height(34.dp)
                                        .testTag("apply_password_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.extract_password_ok),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { applyAndClosePassword() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("extract_password_input"),
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedBorderColor = primary,
                                unfocusedBorderColor = Color.Transparent,
                                focusedLabelColor = primary,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            // Expandable Logs Console when user taps "Logs (N)" while IDLE
            IdleExpandedJournalConsole(
                consoleLogsState = consoleLogsState,
                visible = showConsoleWhenIdle && !isExtractionRunning && extractionStatus != ExtractionStatus.COMPLETED,
                onDismiss = { showConsoleWhenIdle = false },
                onClearLogs = { viewModel.clearConsoleLogs() }
            )

            // 2. Extraction Method Section ("In-Place Extraction" vs "Standard Extraction")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.extract_method_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hatch_mode_switch"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left Option: In-Place Extraction
                    val hatchBg = if (isHatchMode) {
                        primaryContainer.copy(alpha = 0.42f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                    val hatchBorder = if (isHatchMode) {
                        primary.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(hatchBg)
                            .border(
                                width = if (isHatchMode) 1.5.dp else 1.dp,
                                color = hatchBorder,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { viewModel.setHatchMode(true) }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = if (isHatchMode) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Icon(
                                imageVector = if (isHatchMode) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isHatchMode) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.extract_method_inplace_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.extract_method_inplace_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Right Option: Standard Extraction
                    val stdBg = if (!isHatchMode) {
                        primaryContainer.copy(alpha = 0.42f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                    val stdBorder = if (!isHatchMode) {
                        primary.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(stdBg)
                            .border(
                                width = if (!isHatchMode) 1.5.dp else 1.dp,
                                color = stdBorder,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { viewModel.setHatchMode(false) }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (!isHatchMode) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                            Icon(
                                imageVector = if (!isHatchMode) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (!isHatchMode) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.extract_method_standard_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.extract_method_standard_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Explicit P0 Destructive Behavior Notice when In-Place Extraction is selected
                if (isHatchMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("in_place_destructive_notice"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.extract_inplace_destructive_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Pre-Extraction Storage Planner Warnings & SAF Telemetry Notice
            val storagePlan = summary.storagePlan
            val showStandardLowStorageWarning = if (storagePlan != null) {
                !isHatchMode && storagePlan.isDestinationCapacityReliable &&
                    storagePlan.standardVerdict == PlanModeVerdict.INSUFFICIENT_SPACE &&
                    storagePlan.hatchVerdict == PlanModeVerdict.FITS_SAFELY
            } else {
                !isHatchMode && summary.deviceFreeSpace > 0L && summary.standardPeakSpaceRequired > summary.deviceFreeSpace
            }
            val showBothModesInsufficientWarning = storagePlan != null &&
                storagePlan.isDestinationCapacityReliable &&
                storagePlan.recommendation == StoragePlannerRecommendation.INSUFFICIENT_FOR_BOTH
            val showUnknownSafCapacityBanner = storagePlan != null &&
                grantedFolder != null &&
                !storagePlan.isDestinationCapacityReliable

            if (showStandardLowStorageWarning) {
                val neededBytes = storagePlan?.standardRequiredWithReserveBytes ?: summary.standardPeakSpaceRequired
                val freeBytes = storagePlan?.destinationFreeBytes ?: summary.deviceFreeSpace
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("low_storage_warning_banner"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.extract_low_storage_warning_title),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(
                                        R.string.extract_low_storage_warning_body,
                                        StorageHelper.formatBytes(neededBytes),
                                        StorageHelper.formatBytes(freeBytes)
                                    ),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.setHatchMode(true) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.extract_low_storage_use_inplace),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else if (showBothModesInsufficientWarning && storagePlan != null) {
                val activeAdditionalBytes = if (isHatchMode && storagePlan.isHatchModeSupported) {
                    storagePlan.hatchPeakAdditionalBytes
                } else {
                    storagePlan.standardPeakAdditionalBytes
                }
                val freeBytes = storagePlan.destinationFreeBytes ?: 0L
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("insufficient_storage_both_modes_banner"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.extract_insufficient_storage_both_title),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(
                                    R.string.extract_insufficient_storage_both_body,
                                    StorageHelper.formatBytes(activeAdditionalBytes),
                                    StorageHelper.formatBytes(storagePlan.safetyReserveBytes),
                                    StorageHelper.formatBytes(freeBytes)
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            } else if (showUnknownSafCapacityBanner) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unknown_saf_capacity_banner"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.extract_unknown_saf_capacity_title),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = stringResource(R.string.extract_unknown_saf_capacity_body),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            // 3. Destination & Estimated Storage Saved Card + Primary "Extract Archive" CTA (when idle)
            if (!isExtractionRunning) {
                val formattedDestinationPath = if (grantedFolder != null) {
                    val cleanBase = grantedFolder!!.readablePath
                        .removePrefix("Internal Storage / ")
                        .trim()
                        .ifEmpty { grantedFolder!!.displayName }
                    stringResource(R.string.extract_destination_path_format, cleanBase)
                } else {
                    stringResource(R.string.extract_destination_select_prompt)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("extraction_destination_card"),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (grantedFolder != null) Icons.Default.FolderOpen else Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.extract_destination_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = formattedDestinationPath,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            TextButton(
                                onClick = { destinationFolderPickerLauncher.launch(null) }
                            ) {
                                Text(
                                    text = if (grantedFolder != null) {
                                        stringResource(R.string.extract_destination_change)
                                    } else {
                                        stringResource(R.string.extract_destination_select_folder)
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isHatchMode) {
                                    stringResource(R.string.extract_estimated_storage_saved)
                                } else {
                                    stringResource(R.string.extract_estimated_peak_storage)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isHatchMode) {
                                    consistentSavedText
                                } else {
                                    StorageHelper.formatBytes(summary.standardPeakSpaceRequired)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isHatchMode) primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // 4. Dominant Primary Action Button (replaces duplicate bottom button)
                ExpressiveMorphButton(
                    text = if (isHatchMode) {
                        stringResource(R.string.extract_start_inplace_button)
                    } else {
                        stringResource(R.string.extract_start_standard_button)
                    },
                    subtitle = if (isHatchMode) {
                        stringResource(R.string.extract_start_inplace_subtitle, consistentSavedText)
                    } else {
                        stringResource(R.string.extract_start_standard_subtitle)
                    },
                    icon = if (isHatchMode) Icons.Default.Bolt else Icons.Default.PlayArrow,
                    onClick = {
                        if (isHatchMode && !skipInPlaceConfirmSession && grantedFolder != null) {
                            showInPlaceConfirmDialog = true
                        } else {
                            viewModel.startExtraction()
                        }
                    },
                    containerColor = primary,
                    contentColor = onPrimary,
                    height = 62.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start_extraction_button")
                )

                // 5. Layer 2: Collapsible "View detailed storage comparison" Toggle (hidden during active extraction)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showStorageComparison = !showStorageComparison }
                        .padding(vertical = 8.dp)
                        .testTag("toggle_storage_comparison_button"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (showStorageComparison) {
                            stringResource(R.string.extract_hide_storage_comparison)
                        } else {
                            stringResource(R.string.extract_view_storage_comparison)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val storageChevronRotationState = animateFloatAsState(
                        targetValue = if (showStorageComparison) 180f else 0f,
                        animationSpec = ExpressiveMotion.AccordionFloatSpring,
                        label = "storage_comparison_chevron"
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = storageChevronRotationState.value }
                    )
                }
                AnimatedVisibility(
                    visible = showStorageComparison,
                    enter = ExpressiveMotion.AccordionEnterTransition,
                    exit = ExpressiveMotion.AccordionExitTransition
                ) {
                    StorageGauge(
                        archiveBytes = summary.fileSizeBytes,
                        uncompressedBytes = summary.uncompressedSizeBytes,
                        currentArchiveOnDisk = summary.fileSizeBytes,
                        extractedOnDisk = 0L,
                        isHatchMode = isHatchMode,
                        deviceFreeBytes = summary.deviceFreeSpace,
                        hatchPeakOverrideBytes = summary.hatchPeakSpaceRequired,
                        storagePlan = storagePlan
                    )
                }
            }
            }
        }

        // Error State Surface
        if (extractionStatus == ExtractionStatus.ERROR) {
            ExtractionErrorCard(
                progressState = progressState,
                consoleLogsState = consoleLogsState,
                archiveSummary = archiveSummary,
                onDismissError = { viewModel.dismissExtractionStatusCard() },
                onShowDiagnostics = { showDiagnosticLogDialog = true },
                onClearLogs = { viewModel.clearConsoleLogs() }
            )
        }

        // STATE 3: Once Extract is hit -> Full Live Extraction HUD + Live Log Console
        if (isExtractionRunning) {
            LiveExtractionHudCard(
                progressState = progressState,
                consoleLogsState = consoleLogsState,
                isHatchMode = isHatchMode,
                notificationsEnabled = notificationsEnabled,
                primary = primary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                onClearLogs = { viewModel.clearConsoleLogs() }
            )
        }

        // Completed State Card + Console
        if (extractionStatus == ExtractionStatus.COMPLETED) {
            ExtractionCompletedCard(
                progressState = progressState,
                consoleLogsState = consoleLogsState,
                primary = primary,
                primaryContainer = primaryContainer,
                onClearLogs = { viewModel.clearConsoleLogs() },
                onViewFilesClick = { outputDir ->
                    viewModel.refreshLocalFiles()
                    val matched = resolveTargetExtractedFolder(
                        extractedFolders = extractedFolders,
                        completedTarget = lastCompletedTarget,
                        fallbackOutputDirectory = outputDir
                    )
                    if (matched != null) {
                        HapticUtil.performUIHaptic(view)
                        viewModel.collapseAllSubfolders()
                        expandedFolderPath = matched.id
                        pendingScrollFolderId = matched.id
                    } else {
                        handledAutoExpandCompletionKey = null
                    }
                }
            )
        }
            }
        }

        // Extracted Output Folders (Virtualized Expandable Directory Tree)
        if (extractedFolders.isNotEmpty()) {
            item(
                key = "extracted_folders_section_header",
                contentType = "section_header"
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
                ) {
                    ExpressiveSectionHeader(
                        title = stringResource(R.string.extract_section_output_folders),
                        countText = "${extractedFolders.size}",
                        accentColor = primary
                    )
                }
            }

            extractedFolders.forEachIndexed { idx, folder ->
                val isExpanded = folder.id == resolvedExpandedFolderId
                val filesInside = folder.children
                val displayItemCount = folder.totalFileCount.coerceAtLeast(filesInside.size)
                val pos = groupPositionFor(idx, extractedFolders.size)

                val visibleTreeNodes = if (isExpanded) {
                    activeExpandedFolderNodes
                } else {
                    collapsedPreviewNodesByFolderId[folder.id].orEmpty()
                }
                val isLargeFolder = visibleTreeNodes.size > INLINE_ANIMATED_NODE_LIMIT
                val headNodes = if (isLargeFolder) {
                    visibleTreeNodes.take(LARGE_FOLDER_HEAD_BATCH)
                } else {
                    emptyList()
                }
                val tailNodes = if (isLargeFolder && isExpanded) {
                    visibleTreeNodes.drop(LARGE_FOLDER_HEAD_BATCH)
                } else {
                    emptyList()
                }

                item(
                    key = "extracted_folder_header_${folder.id}",
                    contentType = "extracted_folder_header"
                ) {
                    val headerChevronRotationState = animateFloatAsState(
                        targetValue = if (isExpanded) 180f else 0f,
                        animationSpec = ExpressiveMotion.AccordionFloatSpring,
                        label = "root_folder_chevron_rotation"
                    )
                    val headerContainerColorState = animateColorAsState(
                        targetValue = if (isExpanded) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        animationSpec = ExpressiveMotion.AccordionColorSpring,
                        label = "root_folder_container_color"
                    )
                    val togglePillColorState = animateColorAsState(
                        targetValue = if (isExpanded) primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        animationSpec = ExpressiveMotion.AccordionColorSpring,
                        label = "root_folder_pill_color"
                    )
                    val toggleIconTint by animateColorAsState(
                        targetValue = if (isExpanded) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = ExpressiveMotion.AccordionColorSpring,
                        label = "root_folder_icon_tint"
                    )
                    val baseTopCorner = when (pos) {
                        ExpressiveGroupPosition.SINGLE, ExpressiveGroupPosition.TOP -> 24.dp
                        ExpressiveGroupPosition.MIDDLE, ExpressiveGroupPosition.BOTTOM -> 6.dp
                    }
                    val baseBottomCorner = when (pos) {
                        ExpressiveGroupPosition.SINGLE, ExpressiveGroupPosition.BOTTOM -> 24.dp
                        ExpressiveGroupPosition.TOP, ExpressiveGroupPosition.MIDDLE -> 6.dp
                    }
                    val topCornerRadius by animateDpAsState(
                        targetValue = if (isExpanded) 24.dp else baseTopCorner,
                        animationSpec = ExpressiveMotion.AccordionDpSpring,
                        label = "root_folder_top_corner"
                    )
                    val bottomCornerRadius by animateDpAsState(
                        targetValue = if (isExpanded) 4.dp else baseBottomCorner,
                        animationSpec = ExpressiveMotion.AccordionDpSpring,
                        label = "root_folder_bottom_corner"
                    )
                    val headerShape = RoundedCornerShape(
                        topStart = topCornerRadius,
                        topEnd = topCornerRadius,
                        bottomStart = bottomCornerRadius,
                        bottomEnd = bottomCornerRadius
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (isLargeFolder && isExpanded) 0.dp else 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(headerShape)
                                .drawBehind { drawRect(color = headerContainerColorState.value) }
                                .clickable(
                                    role = Role.Button,
                                    onClick = {
                                        HapticUtil.performUIHaptic(view)
                                        toggleRootFolderExpansion(folder, isExpanded)
                                    }
                                )
                                .testTag("extracted_folder_$idx")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(ExpressiveShapes.Small)
                                            .background(if (folder.isSaf) primaryContainer else secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (folder.isSaf) Icons.Default.FolderSpecial else Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (folder.isSaf) primary else secondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    val formattedFolderSize = remember(folder.totalSizeBytes) {
                                        StorageHelper.formatBytes(folder.totalSizeBytes)
                                    }
                                    Column {
                                        Text(
                                            text = folder.name,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.extract_folder_meta,
                                                formattedFolderSize,
                                                displayItemCount
                                            ),
                                            fontSize = 10.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (folder.localFile != null) {
                                        Surface(
                                            shape = CircleShape,
                                            color = secondaryContainer,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .clickable {
                                                    HapticUtil.performUIHaptic(view)
                                                    viewModel.addItemToCompress(folder.localFile)
                                                    viewModel.switchTab(ScreenTab.COMPRESS)
                                                }
                                                .testTag("pack_extracted_folder_$idx")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Archive,
                                                    contentDescription = stringResource(R.string.extract_folder_pack_cd),
                                                    tint = onSecondaryContainer,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = stringResource(R.string.extract_folder_pack_button),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = onSecondaryContainer
                                                )
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            HapticUtil.performUIHaptic(view)
                                            folderPendingDeletion = folder
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.extract_folder_delete_cd),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.Transparent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .drawBehind { drawRect(color = togglePillColorState.value) }
                                            .clickable(
                                                role = Role.Button,
                                                onClick = {
                                                    HapticUtil.performUIHaptic(view)
                                                    toggleRootFolderExpansion(folder, isExpanded)
                                                }
                                            )
                                            .testTag("toggle_extracted_folder_$idx")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ExpandMore,
                                                contentDescription = if (isExpanded) {
                                                    stringResource(R.string.extract_folder_collapse)
                                                } else {
                                                    stringResource(R.string.extract_folder_expand)
                                                },
                                                tint = toggleIconTint,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .graphicsLayer { rotationZ = headerChevronRotationState.value }
                                            )
                                            AnimatedVisibility(
                                                visible = isExpanded,
                                                enter = ExpressiveMotion.AccordionHorizontalEnterTransition,
                                                exit = ExpressiveMotion.AccordionHorizontalExitTransition
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.extract_folder_collapse),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Coordinated Settings-style height transition for the expanded directory tree
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = ExpressiveMotion.AccordionEnterTransition,
                            exit = ExpressiveMotion.AccordionExitTransition
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (filesInside.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                            .padding(horizontal = 20.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.extract_folder_empty),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else if (!isLargeFolder) {
                                    ExtractedFolderAnimatedSubtree(
                                        entries = filesInside,
                                        depth = 0,
                                        expandedSubfolderIds = expandedSubfolderIds,
                                        subfolderCache = subfolderChildrenCache,
                                        loadingSubfolderIds = loadingSubfolderIds,
                                        defaultFileType = defaultFileType,
                                        primary = primary,
                                        primaryContainer = primaryContainer,
                                        onPrimaryContainer = onPrimaryContainer,
                                        secondary = secondary,
                                        secondaryContainer = secondaryContainer,
                                        onToggleSubfolder = { subfolderEntry ->
                                            HapticUtil.performUIHaptic(view)
                                            toggleSubfolderWithAnimation(subfolderEntry)
                                        },
                                        onPackSubfolder = { subfolderFile ->
                                            HapticUtil.performUIHaptic(view)
                                            viewModel.addItemToCompress(subfolderFile)
                                            viewModel.switchTab(ScreenTab.COMPRESS)
                                        },
                                        onPreviewFile = { childFile ->
                                            HapticUtil.performUIHaptic(view)
                                            coroutineScope.launch {
                                                val preview = withContext(Dispatchers.IO) {
                                                    if (childFile.safUri != null) {
                                                        StorageHelper.readSafDocumentPreview(
                                                            context,
                                                            childFile.safUri,
                                                            childFile.name,
                                                            childFile.sizeBytes
                                                        )
                                                    } else if (childFile.localFile != null) {
                                                        StorageHelper.readFilePreview(childFile.localFile)
                                                    } else null
                                                }
                                                viewingFileEntry = childFile
                                                viewingPreviewData = preview
                                            }
                                        },
                                        onOpenFile = { childFile ->
                                            HapticUtil.performUIHaptic(view)
                                            StorageHelper.openExtractedFileEntryWithSystem(context, childFile)
                                        },
                                        onDeleteEntry = { entry ->
                                            HapticUtil.performUIHaptic(view)
                                            filePendingDeletion = entry
                                        }
                                    )
                                } else {
                                    headNodes.forEach { node ->
                                        key(node.uniqueKey) {
                                            ExtractedDirectoryTreeNodeRow(
                                                node = node,
                                                defaultFileType = defaultFileType,
                                                primary = primary,
                                                primaryContainer = primaryContainer,
                                                onPrimaryContainer = onPrimaryContainer,
                                                secondary = secondary,
                                                secondaryContainer = secondaryContainer,
                                                onToggleSubfolder = { subfolderEntry ->
                                                    HapticUtil.performUIHaptic(view)
                                                    toggleSubfolderWithAnimation(subfolderEntry)
                                                },
                                                onPackSubfolder = { subfolderFile ->
                                                    HapticUtil.performUIHaptic(view)
                                                    viewModel.addItemToCompress(subfolderFile)
                                                    viewModel.switchTab(ScreenTab.COMPRESS)
                                                },
                                                onPreviewFile = { childFile ->
                                                    HapticUtil.performUIHaptic(view)
                                                    coroutineScope.launch {
                                                        val preview = withContext(Dispatchers.IO) {
                                                            if (childFile.safUri != null) {
                                                                StorageHelper.readSafDocumentPreview(
                                                                    context,
                                                                    childFile.safUri,
                                                                    childFile.name,
                                                                    childFile.sizeBytes
                                                                )
                                                            } else if (childFile.localFile != null) {
                                                                StorageHelper.readFilePreview(childFile.localFile)
                                                            } else null
                                                        }
                                                        viewingFileEntry = childFile
                                                        viewingPreviewData = preview
                                                    }
                                                },
                                                onOpenFile = { childFile ->
                                                    HapticUtil.performUIHaptic(view)
                                                    StorageHelper.openExtractedFileEntryWithSystem(context, childFile)
                                                },
                                                onDeleteEntry = { entry ->
                                                    HapticUtil.performUIHaptic(view)
                                                    filePendingDeletion = entry
                                                }
                                            )
                                        }
                                    }
                                }

                                if (!isLargeFolder) {
                                    ExtractedFolderCollapseFooter(
                                        idx = idx,
                                        primary = primary,
                                        onCollapse = {
                                            HapticUtil.performUIHaptic(view)
                                            toggleRootFolderExpansion(folder, true)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Virtualized overflow items for huge folders (> 30 visible nodes)
                if (isLargeFolder && isExpanded) {
                    items(
                        items = tailNodes,
                        key = { it.uniqueKey },
                        contentType = { it.contentType }
                    ) { node ->
                        ExtractedDirectoryTreeNodeRow(
                            node = node,
                            defaultFileType = defaultFileType,
                            primary = primary,
                            primaryContainer = primaryContainer,
                            onPrimaryContainer = onPrimaryContainer,
                            secondary = secondary,
                            secondaryContainer = secondaryContainer,
                            onToggleSubfolder = { subfolderEntry ->
                                HapticUtil.performUIHaptic(view)
                                toggleSubfolderWithAnimation(subfolderEntry)
                            },
                            onPackSubfolder = { subfolderFile ->
                                HapticUtil.performUIHaptic(view)
                                viewModel.addItemToCompress(subfolderFile)
                                viewModel.switchTab(ScreenTab.COMPRESS)
                            },
                            onPreviewFile = { childFile ->
                                HapticUtil.performUIHaptic(view)
                                coroutineScope.launch {
                                    val preview = withContext(Dispatchers.IO) {
                                        if (childFile.safUri != null) {
                                            StorageHelper.readSafDocumentPreview(
                                                context,
                                                childFile.safUri,
                                                childFile.name,
                                                childFile.sizeBytes
                                            )
                                        } else if (childFile.localFile != null) {
                                            StorageHelper.readFilePreview(childFile.localFile)
                                        } else null
                                    }
                                    viewingFileEntry = childFile
                                    viewingPreviewData = preview
                                }
                            },
                            onOpenFile = { childFile ->
                                HapticUtil.performUIHaptic(view)
                                StorageHelper.openExtractedFileEntryWithSystem(context, childFile)
                            },
                            onDeleteEntry = { entry ->
                                HapticUtil.performUIHaptic(view)
                                filePendingDeletion = entry
                            }
                        )
                    }

                    item(
                        key = "extracted_folder_footer_${folder.id}",
                        contentType = "extracted_folder_footer"
                    ) {
                        Box(modifier = Modifier.padding(bottom = 4.dp)) {
                            ExtractedFolderCollapseFooter(
                                idx = idx,
                                primary = primary,
                                onCollapse = {
                                    HapticUtil.performUIHaptic(view)
                                    toggleRootFolderExpansion(folder, true)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Available Archives Section (Placed after Extracted Output Folders)
        item(
            key = "available_archives_section_header",
            contentType = "section_header"
        ) {
            Column(
                modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)
            ) {
                ExpressiveSectionHeader(
                    title = stringResource(R.string.extract_section_available_archives),
                    countText = "${availableArchives.size}",
                    accentColor = primary
                )
            }
        }

        if (availableArchives.isEmpty()) {
            item(
                key = "available_archives_empty",
                contentType = "empty_archives"
            ) {
                ExpressiveGroupedCard(
                    position = ExpressiveGroupPosition.SINGLE,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Archive,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.extract_archives_empty_title),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            )
                            Text(
                                text = stringResource(R.string.extract_archives_empty_subtitle),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                items = availableArchives,
                key = { _, file -> "archive_${file.absolutePath}" },
                contentType = { _, _ -> "archive_item" }
            ) { index, file ->
                val isSelected = selectedFile?.absolutePath == file.absolutePath
                val pos = groupPositionFor(index, availableArchives.size)

                ExpressiveGroupedCard(
                    position = pos,
                    isSelected = isSelected,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    selectedContainerColor = primaryContainer,
                    onClick = {
                        if (isSelected) {
                            viewModel.deselectArchive()
                        } else {
                            viewModel.selectArchive(file)
                        }
                    },
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .testTag("archive_item_$index")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(ExpressiveShapes.Small)
                                    .background(
                                        if (isSelected) primary.copy(alpha = 0.22f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    tint = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = file.name,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val archiveBytes = fileSizesCache[file.absolutePath] ?: 0L
                                val formattedArchiveBytes = remember(archiveBytes) {
                                    StorageHelper.formatBytes(archiveBytes)
                                }
                                val detectedFormat = remember(file.name) {
                                    ZipParser.detectFormat(file.name)
                                }
                                Text(
                                    text = "$formattedArchiveBytes • ${stringResource(detectedFormat.labelResId)}",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (isSelected) onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    viewModel.selectArchive(file)
                                    showArchivePreviewer = true
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("preview_repo_archive_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = stringResource(R.string.extract_archive_preview_cd),
                                    tint = primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = { StorageHelper.shareFile(context, file) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.extract_archive_share_cd),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            IconButton(
                                onClick = { viewModel.deleteArchive(file) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.extract_archive_delete_cd),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.extract_archive_select_cd),
                                tint = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandbyJournalConsoleSection(
    consoleLogsState: State<List<String>>,
    onClearLogs: () -> Unit
) {
    JournalTerminal(
        logs = consoleLogsState.value,
        isActive = false,
        onClearLogs = onClearLogs,
        modifier = Modifier.testTag("standby_journal_terminal")
    )
}

@Composable
private fun IdleLogsTogglePill(
    consoleLogsState: State<List<String>>,
    onShowConsole: () -> Unit
) {
    val logCount by remember {
        derivedStateOf { consoleLogsState.value.size }
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onShowConsole)
            .testTag("toggle_console_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = stringResource(R.string.extract_open_logs_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.extract_logs_count, logCount),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdleExpandedJournalConsole(
    consoleLogsState: State<List<String>>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onClearLogs: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = ExpressiveMotion.AccordionEnterTransition,
        exit = ExpressiveMotion.AccordionExitTransition
    ) {
        JournalTerminal(
            logs = consoleLogsState.value,
            isActive = false,
            defaultExpanded = true,
            onExpandedChange = { expanded ->
                if (!expanded) onDismiss()
            },
            onClearLogs = onClearLogs,
            modifier = Modifier.testTag("idle_expanded_journal_terminal")
        )
    }
}

@Composable
private fun ExtractionErrorCard(
    progressState: State<ExtractionProgress>,
    consoleLogsState: State<List<String>>,
    archiveSummary: ArchiveSummary?,
    onDismissError: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val progress = progressState.value
    val logs = consoleLogsState.value.ifEmpty { progress.journalLogs }
    if (progress.errorMessage.isNullOrEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("extraction_error_card"),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExpressiveCloverIcon(
                        icon = Icons.Default.ErrorOutline,
                        containerColor = MaterialTheme.colorScheme.error,
                        iconColor = MaterialTheme.colorScheme.onError,
                        size = 38.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.extract_error_title),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = progress.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismissError,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.extract_error_dismiss_cd),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            JournalTerminal(
                logs = logs,
                isActive = false,
                onClearLogs = onClearLogs
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onShowDiagnostics,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("view_diagnostic_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = stringResource(R.string.extract_error_view_logs_cd),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.extract_error_full_diagnostics),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                FilledTonalButton(
                    onClick = {
                        val report = buildDiagnosticReport(progress, archiveSummary, logs)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                context.getString(R.string.extract_error_clipboard_label),
                                report
                            )
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.extract_error_copied_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("copy_diagnostic_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.extract_error_copy_cd),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.extract_error_copy_button), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LiveExtractionHudCard(
    progressState: State<ExtractionProgress>,
    consoleLogsState: State<List<String>>,
    isHatchMode: Boolean,
    notificationsEnabled: Boolean,
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    onClearLogs: () -> Unit
) {
    val progress = progressState.value
    val isPausedOrPausing = progress.status == ExtractionStatus.PAUSED || progress.status == ExtractionStatus.PAUSING
    val animatedOverall by animateFloatAsState(
        targetValue = progress.overallProgressFraction,
        animationSpec = if (isPausedOrPausing) {
            SnapSpec()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        },
        label = "overall_extract_progress"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("extraction_hud_card"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (progress.status == ExtractionStatus.PAUSED) {
                        Icon(
                            imageVector = Icons.Default.PauseCircle,
                            contentDescription = stringResource(R.string.extract_hud_paused_title),
                            tint = primary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = primary,
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = when (progress.status) {
                                ExtractionStatus.PAUSED -> stringResource(R.string.extract_hud_paused_title)
                                ExtractionStatus.PAUSING -> stringResource(R.string.extract_hud_pausing_title)
                                ExtractionStatus.FINALIZING -> stringResource(R.string.extract_hud_finalizing_title)
                                else -> if (isHatchMode) {
                                    stringResource(R.string.extract_hud_inplace_title)
                                } else {
                                    stringResource(R.string.extract_hud_standard_title)
                                }
                            },
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                        Text(
                            text = when (progress.status) {
                                ExtractionStatus.PAUSED -> stringResource(R.string.extract_hud_paused_subtitle)
                                ExtractionStatus.PAUSING -> stringResource(R.string.extract_hud_pausing_subtitle)
                                ExtractionStatus.FINALIZING -> stringResource(R.string.extract_hud_finalizing_subtitle)
                                else -> if (isHatchMode) {
                                    stringResource(R.string.extract_hud_inplace_subtitle)
                                } else if (notificationsEnabled) {
                                    stringResource(R.string.extract_hud_worker_notif_subtitle)
                                } else {
                                    stringResource(R.string.extract_hud_worker_subtitle)
                                }
                            },
                            fontSize = 11.sp,
                            color = primary
                        )
                    }
                }

                Text(
                    text = "${(animatedOverall * 100f).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    color = primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val filePrefix = when (progress.status) {
                    ExtractionStatus.PAUSED -> stringResource(R.string.extract_hud_prefix_paused)
                    ExtractionStatus.PAUSING -> stringResource(R.string.extract_hud_prefix_pausing)
                    ExtractionStatus.FINALIZING -> stringResource(R.string.extract_hud_prefix_finalizing)
                    else -> stringResource(R.string.extract_hud_prefix_active)
                }
                val prepLabel = stringResource(R.string.extract_hud_preparing)
                Text(
                    text = "$filePrefix: ${progress.currentFileName.ifEmpty { prepLabel }}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${progress.completedEntries}/${progress.totalEntries} • ${StorageHelper.formatBytes(progress.bytesExtracted)}",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            ExpressiveWaveProgressIndicator(
                progress = progress.overallProgressFraction,
                activeColor = primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                isWaveAnimated = !isPausedOrPausing,
                height = 14.dp,
                modifier = Modifier.testTag("in_app_overall_progress_bar")
            )

            Spacer(modifier = Modifier.height(12.dp))

            val effectiveDiskBytes = if (progress.originalArchiveBytes > 0L && progress.bytesReclaimed > 0L) {
                minOf(
                    progress.currentArchiveBytesOnDisk,
                    (progress.originalArchiveBytes - progress.bytesReclaimed).coerceAtLeast(0L)
                )
            } else {
                progress.currentArchiveBytesOnDisk
            }
            val effectiveReclaimedBytes = if (progress.originalArchiveBytes > 0L) {
                maxOf(
                    progress.bytesReclaimed,
                    (progress.originalArchiveBytes - effectiveDiskBytes).coerceAtLeast(0L)
                )
            } else {
                progress.bytesReclaimed
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = onSecondaryContainer,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = onSecondaryContainer
                        )
                    }
                }

                if (isHatchMode) {
                    Text(
                        text = stringResource(
                            R.string.extract_hud_freed_format,
                            StorageHelper.formatBytes(effectiveReclaimedBytes)
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (progress.isCrcValid) primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = if (progress.isCrcValid) primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (progress.isCrcValid) {
                                stringResource(R.string.extract_hud_crc_ok)
                            } else {
                                stringResource(R.string.extract_hud_crc_err)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (progress.isCrcValid) onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            JournalTerminal(
                logs = consoleLogsState.value.ifEmpty { progress.journalLogs },
                isActive = true,
                onClearLogs = onClearLogs
            )
        }
    }
}

@Composable
private fun ExtractionCompletedCard(
    progressState: State<ExtractionProgress>,
    consoleLogsState: State<List<String>>,
    primary: Color,
    primaryContainer: Color,
    onClearLogs: () -> Unit,
    onViewFilesClick: (String?) -> Unit
) {
    val progress = progressState.value
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("extraction_completed_card"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    ExpressiveCloverIcon(
                        icon = Icons.Default.CheckCircle,
                        containerColor = primaryContainer,
                        iconColor = primary,
                        size = 44.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.extract_completed_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (progress.isHatchMode) {
                                stringResource(
                                    R.string.extract_completed_summary_reclaimed,
                                    progress.totalEntries,
                                    StorageHelper.formatBytes(progress.bytesExtracted),
                                    StorageHelper.formatBytes(progress.bytesReclaimed)
                                )
                            } else {
                                stringResource(
                                    R.string.extract_completed_summary,
                                    progress.totalEntries,
                                    StorageHelper.formatBytes(progress.bytesExtracted)
                                )
                            },
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = primary
                        )
                    }
                }

                if (progress.outputDirectory != null) {
                    FilledTonalButton(
                        onClick = { onViewFilesClick(progress.outputDirectory) },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("view_extracted_files_button")
                    ) {
                        Text(
                            text = stringResource(R.string.extract_completed_files_button),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            JournalTerminal(
                logs = consoleLogsState.value.ifEmpty { progress.journalLogs },
                isActive = false,
                defaultExpanded = true,
                onClearLogs = onClearLogs
            )
        }
    }
}

@Composable
private fun ExtractedFolderCollapseFooter(
    idx: Int,
    primary: Color,
    onCollapse: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp
                )
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(
                    role = Role.Button,
                    onClick = onCollapse
                )
                .testTag("bottom_collapse_extracted_folder_$idx")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandLess,
                    contentDescription = stringResource(R.string.extract_folder_collapse_cd),
                    tint = primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.extract_folder_collapse_button),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = primary
                )
            }
        }
    }
}

@Composable
private fun ExtractedFolderAnimatedSubtree(
    entries: List<ExtractedFileEntry>,
    depth: Int,
    expandedSubfolderIds: Set<String>,
    subfolderCache: Map<String, List<ExtractedFileEntry>>,
    loadingSubfolderIds: Set<String>,
    defaultFileType: String,
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    onToggleSubfolder: (ExtractedFileEntry) -> Unit,
    onPackSubfolder: (File) -> Unit,
    onPreviewFile: (ExtractedFileEntry) -> Unit,
    onOpenFile: (ExtractedFileEntry) -> Unit,
    onDeleteEntry: (ExtractedFileEntry) -> Unit
) {
    val sortedEntries = remember(entries) { StorageHelper.sortExtractedEntries(entries) }
    Column(modifier = Modifier.fillMaxWidth()) {
        sortedEntries.forEach { entry ->
            key(entry.id) {
                if (entry.isDirectory) {
                    val isSubExpanded = entry.id in expandedSubfolderIds
                    val isLoading = entry.id in loadingSubfolderIds
                    val cachedChildren = subfolderCache[entry.id]
                    val node = VisibleExtractedTreeNode(
                        uniqueKey = "subfolder_${entry.id}",
                        nodeType = TreeNodeType.SUBFOLDER,
                        entry = entry,
                        depth = depth,
                        isExpanded = isSubExpanded,
                        isLoading = isLoading,
                        cachedChildCount = cachedChildren?.size
                    )
                    ExtractedDirectoryTreeNodeRow(
                        node = node,
                        defaultFileType = defaultFileType,
                        primary = primary,
                        primaryContainer = primaryContainer,
                        onPrimaryContainer = onPrimaryContainer,
                        secondary = secondary,
                        secondaryContainer = secondaryContainer,
                        onToggleSubfolder = onToggleSubfolder,
                        onPackSubfolder = onPackSubfolder,
                        onPreviewFile = onPreviewFile,
                        onOpenFile = onOpenFile,
                        onDeleteEntry = onDeleteEntry
                    )
                    AnimatedVisibility(
                        visible = isSubExpanded,
                        enter = ExpressiveMotion.AccordionEnterTransition,
                        exit = ExpressiveMotion.AccordionExitTransition
                    ) {
                        if (cachedChildren != null) {
                            if (cachedChildren.isEmpty()) {
                                ExtractedDirectoryTreeNodeRow(
                                    node = VisibleExtractedTreeNode(
                                        uniqueKey = "empty_subfolder_${entry.id}",
                                        nodeType = TreeNodeType.EMPTY_SUBFOLDER_NOTICE,
                                        entry = null,
                                        depth = depth + 1
                                    ),
                                    defaultFileType = defaultFileType,
                                    primary = primary,
                                    primaryContainer = primaryContainer,
                                    onPrimaryContainer = onPrimaryContainer,
                                    secondary = secondary,
                                    secondaryContainer = secondaryContainer,
                                    onToggleSubfolder = onToggleSubfolder,
                                    onPackSubfolder = onPackSubfolder,
                                    onPreviewFile = onPreviewFile,
                                    onOpenFile = onOpenFile,
                                    onDeleteEntry = onDeleteEntry
                                )
                            } else if (depth < 16) {
                                ExtractedFolderAnimatedSubtree(
                                    entries = cachedChildren,
                                    depth = depth + 1,
                                    expandedSubfolderIds = expandedSubfolderIds,
                                    subfolderCache = subfolderCache,
                                    loadingSubfolderIds = loadingSubfolderIds,
                                    defaultFileType = defaultFileType,
                                    primary = primary,
                                    primaryContainer = primaryContainer,
                                    onPrimaryContainer = onPrimaryContainer,
                                    secondary = secondary,
                                    secondaryContainer = secondaryContainer,
                                    onToggleSubfolder = onToggleSubfolder,
                                    onPackSubfolder = onPackSubfolder,
                                    onPreviewFile = onPreviewFile,
                                    onOpenFile = onOpenFile,
                                    onDeleteEntry = onDeleteEntry
                                )
                            }
                        }
                    }
                } else {
                    ExtractedDirectoryTreeNodeRow(
                        node = VisibleExtractedTreeNode(
                            uniqueKey = "file_${entry.id}",
                            nodeType = TreeNodeType.FILE,
                            entry = entry,
                            depth = depth
                        ),
                        defaultFileType = defaultFileType,
                        primary = primary,
                        primaryContainer = primaryContainer,
                        onPrimaryContainer = onPrimaryContainer,
                        secondary = secondary,
                        secondaryContainer = secondaryContainer,
                        onToggleSubfolder = onToggleSubfolder,
                        onPackSubfolder = onPackSubfolder,
                        onPreviewFile = onPreviewFile,
                        onOpenFile = onOpenFile,
                        onDeleteEntry = onDeleteEntry
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtractedDirectoryTreeNodeRow(
    node: VisibleExtractedTreeNode,
    defaultFileType: String,
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    onToggleSubfolder: (ExtractedFileEntry) -> Unit,
    onPackSubfolder: (File) -> Unit,
    onPreviewFile: (ExtractedFileEntry) -> Unit,
    onOpenFile: (ExtractedFileEntry) -> Unit,
    onDeleteEntry: (ExtractedFileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val indentDp = (node.depth.coerceAtMost(8) * 14).dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indentDp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.depth > 0) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(primary.copy(alpha = 0.25f))
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            when (node.nodeType) {
                TreeNodeType.EMPTY_SUBFOLDER_NOTICE -> {
                    Surface(
                        shape = ExpressiveShapes.IconBox,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.extract_folder_empty),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                TreeNodeType.SUBFOLDER -> {
                    val subfolder = node.entry ?: return@Row
                    val subfolderChevronRotationState = animateFloatAsState(
                        targetValue = if (node.isExpanded) 180f else 0f,
                        animationSpec = ExpressiveMotion.AccordionFloatSpring,
                        label = "subfolder_chevron_rotation"
                    )
                    val subfolderBgColorState = animateColorAsState(
                        targetValue = if (node.isExpanded) {
                            secondaryContainer.copy(alpha = 0.58f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        animationSpec = ExpressiveMotion.AccordionColorSpring,
                        label = "subfolder_bg_color"
                    )
                    val subfolderIconBadgeColorState = animateColorAsState(
                        targetValue = if (node.isExpanded) primaryContainer else secondaryContainer,
                        animationSpec = ExpressiveMotion.AccordionColorSpring,
                        label = "subfolder_icon_badge_color"
                    )
                    val subfolderIconTint by animateColorAsState(
                        targetValue = if (node.isExpanded) primary else secondary,
                        animationSpec = ExpressiveMotion.AccordionColorSpring,
                        label = "subfolder_icon_tint"
                    )

                    Surface(
                        shape = ExpressiveShapes.SubItem,
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.SubItem)
                            .drawBehind {
                                val r = 16.dp.toPx()
                                drawRoundRect(
                                    color = subfolderBgColorState.value,
                                    cornerRadius = CornerRadius(r, r)
                                )
                            }
                            .clickable(
                                role = Role.Button,
                                onClick = { onToggleSubfolder(subfolder) }
                            )
                            .testTag("extracted_subfolder_row_${subfolder.relativePath}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(ExpressiveShapes.IconBadge)
                                        .drawBehind {
                                            val r = 10.dp.toPx()
                                            drawRoundRect(
                                                color = subfolderIconBadgeColorState.value,
                                                cornerRadius = CornerRadius(r, r)
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (node.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = subfolderIconTint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = subfolder.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subMeta = when {
                                        node.isLoading -> stringResource(R.string.extract_hud_preparing)
                                        node.cachedChildCount != null -> "${subfolder.relativePath} • ${node.cachedChildCount}"
                                        else -> subfolder.relativePath
                                    }
                                    Text(
                                        text = subMeta,
                                        fontSize = 10.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (subfolder.localFile != null) {
                                    IconButton(
                                        onClick = { onPackSubfolder(subfolder.localFile) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Archive,
                                            contentDescription = stringResource(R.string.extract_folder_pack_cd),
                                            tint = secondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onDeleteEntry(subfolder) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.extract_folder_delete_cd),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                if (node.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = if (node.isExpanded) {
                                            stringResource(R.string.extract_folder_collapse)
                                        } else {
                                            stringResource(R.string.extract_folder_expand)
                                        },
                                        tint = if (node.isExpanded) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .graphicsLayer { rotationZ = subfolderChevronRotationState.value }
                                    )
                                }
                            }
                        }
                    }
                }

                TreeNodeType.FILE -> {
                    val childFile = node.entry ?: return@Row
                    val (extUpper, fileIcon, formattedSize) = remember(childFile.name, childFile.sizeBytes) {
                        val extLower = childFile.name.substringAfterLast('.', "").lowercase()
                        val icon = when (extLower) {
                            "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> Icons.Default.Image
                            "mp4", "mkv", "webm", "avi", "mov" -> Icons.Default.VideoFile
                            "mp3", "wav", "ogg", "flac", "m4a" -> Icons.Default.AudioFile
                            "zip", "7z", "rar", "tar", "gz", "apk" -> Icons.Default.Archive
                            "iso", "img", "bin", "rom" -> Icons.Default.Album
                            "kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "md" -> Icons.Default.Code
                            else -> Icons.Default.Description
                        }
                        Triple(extLower.uppercase(), icon, StorageHelper.formatBytes(childFile.sizeBytes))
                    }
                    Surface(
                        shape = ExpressiveShapes.SubItem,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.SubItem)
                            .clickable(
                                role = Role.Button,
                                onClick = { onPreviewFile(childFile) }
                            )
                            .testTag("extracted_file_row_${childFile.name}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = fileIcon,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = childFile.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$formattedSize • ${extUpper.ifEmpty { defaultFileType }}",
                                        fontSize = 10.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onDeleteEntry(childFile) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.extract_file_delete_cd),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    onClick = { onOpenFile(childFile) },
                                    shape = CircleShape,
                                    color = primaryContainer,
                                    modifier = Modifier.testTag("open_extracted_file_${childFile.name}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.extract_file_open_button),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressivePillBadge(text: String, container: Color, content: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

private fun buildDiagnosticReport(
    progress: com.biplov.kaihatch.model.ExtractionProgress,
    summary: com.biplov.kaihatch.model.ArchiveSummary?,
    consoleLogs: List<String> = emptyList()
): String {
    val sb = StringBuilder()
    sb.appendLine("==========================================")
    sb.appendLine("KAIHATCH EXTRACTION DIAGNOSTIC REPORT")
    sb.appendLine("==========================================")
    sb.appendLine("Archive: ${progress.archiveName.ifEmpty { summary?.fileName ?: "Unknown" }}")
    sb.appendLine("Format: ${summary?.format?.label ?: "Auto-detected"}")
    sb.appendLine("Original Size: ${StorageHelper.formatBytes(summary?.fileSizeBytes ?: progress.originalArchiveBytes)}")
    sb.appendLine("Uncompressed Target: ${StorageHelper.formatBytes(summary?.uncompressedSizeBytes ?: progress.totalUncompressedBytes)}")
    sb.appendLine("Status: ${progress.status}")
    sb.appendLine("Hatch Mode: ${progress.isHatchMode} | Zero-Copy SAF: ${progress.isZeroCopySaf} | Zip64: ${progress.isZip64}")
    sb.appendLine("Progress: ${progress.completedEntries}/${progress.totalEntries} entries (${StorageHelper.formatBytes(progress.bytesExtracted)})")
    if (!progress.errorMessage.isNullOrEmpty()) {
        sb.appendLine("Failure Reason: ${progress.errorMessage}")
    }
    val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    sb.appendLine("Timestamp: $timeStr")
    sb.appendLine("------------------------------------------")
    val effectiveLogs = consoleLogs.ifEmpty { progress.journalLogs }
    sb.appendLine("EVENT JOURNAL LOGS (${effectiveLogs.size} events):")
    if (effectiveLogs.isEmpty()) {
        sb.appendLine("(No journal logs recorded)")
    } else {
        effectiveLogs.forEach { sb.appendLine(it) }
    }
    sb.appendLine("==========================================")
    return sb.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticLogDialog(
    progress: com.biplov.kaihatch.model.ExtractionProgress,
    archiveSummary: com.biplov.kaihatch.model.ArchiveSummary?,
    consoleLogs: List<String> = emptyList(),
    onClearLogs: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 24.dp)
                .testTag("diagnostic_logs_dialog"),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExpressiveCloverIcon(
                            icon = Icons.Default.ReceiptLong,
                            containerColor = if (progress.status == ExtractionStatus.ERROR) MaterialTheme.colorScheme.errorContainer else primaryContainer,
                            iconColor = if (progress.status == ExtractionStatus.ERROR) MaterialTheme.colorScheme.error else primary,
                            size = 46.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.dialog_diagnostics_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = progress.archiveName.ifEmpty { archiveSummary?.fileName ?: stringResource(R.string.dialog_diagnostics_default_archive) },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.dialog_diagnostics_close_cd),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExpressivePillBadge(
                        text = stringResource(R.string.dialog_diagnostics_status_badge, progress.status.name),
                        container = if (progress.status == ExtractionStatus.ERROR) MaterialTheme.colorScheme.errorContainer else primaryContainer,
                        content = if (progress.status == ExtractionStatus.ERROR) MaterialTheme.colorScheme.onErrorContainer else onPrimaryContainer
                    )
                    archiveSummary?.format?.let { fmt ->
                        ExpressivePillBadge(
                            text = fmt.label,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (progress.totalUncompressedBytes > 0L) {
                        ExpressivePillBadge(
                            text = stringResource(R.string.dialog_diagnostics_total_badge, StorageHelper.formatBytes(progress.totalUncompressedBytes)),
                            container = MaterialTheme.colorScheme.surfaceContainerHigh,
                            content = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (progress.isHatchMode) {
                        ExpressivePillBadge(
                            text = stringResource(R.string.dialog_diagnostics_hatch_badge),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                if (!progress.errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = progress.errorMessage,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val effectiveLogs = consoleLogs.ifEmpty { progress.journalLogs }
                Text(
                    text = stringResource(R.string.dialog_diagnostics_journal_header, effectiveLogs.size),
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                val emptyJournalMsg = stringResource(R.string.dialog_diagnostics_journal_empty)
                JournalTerminal(
                    logs = if (effectiveLogs.isEmpty()) listOf(emptyJournalMsg) else effectiveLogs,
                    onClearLogs = onClearLogs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val dump = buildDiagnosticReport(progress, archiveSummary, effectiveLogs)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.dialog_diagnostics_clipboard_label), dump))
                            Toast.makeText(context, context.getString(R.string.dialog_diagnostics_copied_toast), Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("copy_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.dialog_diagnostics_copy_cd),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.dialog_diagnostics_copy_button),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("dismiss_diagnostics_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
