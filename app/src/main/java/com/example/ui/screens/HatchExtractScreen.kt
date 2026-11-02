package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ExtractedFileEntry
import com.example.data.ExtractedFolderEntry
import com.example.data.FilePreviewData
import com.example.data.StorageHelper
import com.example.engine.ZipParser
import com.example.model.ExtractionStatus
import com.example.ui.CompletedExtractionTarget
import com.example.ui.HatchViewModel
import com.example.ui.ScreenTab
import com.example.ui.components.ArchivePreviewerDialog
import com.example.ui.components.ExpressiveBentoTile
import com.example.ui.components.ExpressiveCleanHeader
import com.example.ui.components.ExpressiveCloverIcon
import com.example.ui.components.ExpressiveGroupPosition
import com.example.ui.components.ExpressiveGroupedCard
import com.example.ui.components.ExpressiveMorphButton
import com.example.ui.components.ExpressiveSectionHeader
import com.example.ui.components.ExpressiveWaveProgressIndicator
import com.example.ui.components.FileViewerDialog
import com.example.ui.components.JournalTerminal
import com.example.ui.components.StorageGauge
import com.example.ui.components.groupPositionFor
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
                val folderCanonical = localFile?.let {
                    runCatching { it.canonicalPath }.getOrNull() ?: it.absolutePath
                }
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
                val hasConflictingLocalPath = folder.localFile != null &&
                    !completedTarget.canonicalDirPath.isNullOrEmpty() &&
                    !folder.isSaf &&
                    (runCatching { folder.localFile.canonicalPath }.getOrNull() ?: folder.localFile.absolutePath) != completedTarget.canonicalDirPath
                !hasConflictingLocalPath && folder.name == expectedName
            } ?: extractedFolders.firstOrNull { folder ->
                val hasConflictingLocalPath = folder.localFile != null &&
                    !completedTarget.canonicalDirPath.isNullOrEmpty() &&
                    !folder.isSaf &&
                    (runCatching { folder.localFile.canonicalPath }.getOrNull() ?: folder.localFile.absolutePath) != completedTarget.canonicalDirPath
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

    val outCanonical = runCatching { File(outDir).canonicalPath }.getOrNull()
    val byLocalPath = extractedFolders.firstOrNull { folder ->
        val localFile = folder.localFile ?: return@firstOrNull false
        val folderCanonical = runCatching { localFile.canonicalPath }.getOrNull() ?: localFile.absolutePath
        localFile.absolutePath == outDir || (outCanonical != null && folderCanonical == outCanonical)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HatchExtractScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val selectedFile by viewModel.selectedArchiveFile.collectAsState()
    val selectedSafUri by viewModel.selectedSafUri.collectAsState()
    val archiveSummary by viewModel.archiveSummary.collectAsState()
    val isHatchMode by viewModel.isHatchMode.collectAsState()
    val archivePassword by viewModel.archivePassword.collectAsState()
    val progress by viewModel.extractionProgress.collectAsState()
    val pendingResumeStates by viewModel.pendingResumeStates.collectAsState()
    val availableArchives by viewModel.availableArchives.collectAsState()
    val extractedFolders by viewModel.extractedFolders.collectAsState()
    val lastCompletedTarget by viewModel.lastCompletedTarget.collectAsState()
    val fileSizesCache by viewModel.fileSizesCache.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val grantedFolder by viewModel.grantedFolder.collectAsState()
    val showPersistentFolderPrompt by viewModel.showPersistentFolderPrompt.collectAsState()

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
    val scrollState = rememberScrollState()
    var scrollContainerTopInRoot by remember { mutableStateOf(0f) }
    val folderTopOffsets = remember { mutableStateMapOf<String, Int>() }
    var showArchivePreviewer by remember { mutableStateOf(false) }
    var showPasswordField by remember(archiveSummary?.fileName, archiveSummary?.hasEncryptedEntries) {
        mutableStateOf(archiveSummary?.hasEncryptedEntries == true)
    }
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

    val isExtractionRunning = progress.status in listOf(
        ExtractionStatus.ANALYZING,
        ExtractionStatus.EXTRACTING,
        ExtractionStatus.VERIFYING_CRC,
        ExtractionStatus.TRUNCATING
    )

    val completionKey: String? = when {
        progress.status != ExtractionStatus.COMPLETED -> null
        lastCompletedTarget != null -> {
            val target = lastCompletedTarget!!
            "target_${target.completionId}_${target.folderDocUriString.orEmpty()}_${target.canonicalDirPath.orEmpty()}_${target.outputDirectory.orEmpty()}"
        }
        !progress.outputDirectory.isNullOrEmpty() -> {
            "progress_${progress.startTime}_${progress.outputDirectory}"
        }
        else -> null
    }

    LaunchedEffect(completionKey, lastCompletedTarget, progress.outputDirectory, extractedFolders) {
        if (progress.status == ExtractionStatus.COMPLETED &&
            completionKey != null &&
            handledAutoExpandCompletionKey != completionKey
        ) {
            val matched = resolveTargetExtractedFolder(
                extractedFolders = extractedFolders,
                completedTarget = lastCompletedTarget,
                fallbackOutputDirectory = progress.outputDirectory
            )
            if (matched != null) {
                expandedFolderPath = matched.id
                handledAutoExpandCompletionKey = completionKey
                pendingScrollFolderId = matched.id
            }
        }
    }

    LaunchedEffect(pendingScrollFolderId, extractedFolders) {
        val targetId = pendingScrollFolderId ?: return@LaunchedEffect
        if (extractedFolders.none { it.id == targetId }) return@LaunchedEffect

        var attempts = 0
        while (folderTopOffsets[targetId] == null && attempts < 6) {
            withFrameNanos { }
            attempts++
        }
        withFrameNanos { }

        val initialY = folderTopOffsets[targetId]
        if (initialY != null) {
            val desiredScroll = (initialY - scrollTopPaddingPx).coerceAtLeast(0)
            scrollState.animateScrollTo(desiredScroll.coerceAtMost(scrollState.maxValue))
            if (desiredScroll > scrollState.value) {
                delay(220)
                val updatedY = folderTopOffsets[targetId] ?: initialY
                val finalScroll = (updatedY - scrollTopPaddingPx).coerceAtLeast(0)
                scrollState.animateScrollTo(finalScroll.coerceAtMost(scrollState.maxValue))
            }
            pendingScrollFolderId = null
        }
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
                    text = "Select Persistent Folder First",
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    text = "Persistent Folder Access is not yet selected. Please choose a destination folder (e.g. Download or a custom folder) so KaiHatch can unpack files directly to your storage.\n\nExtraction will start automatically right after you select the folder."
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
                    Text("Select Folder & Extract", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissPersistentFolderPrompt() },
                    modifier = Modifier.testTag("cancel_persistent_folder_prompt_button")
                ) {
                    Text("Cancel")
                }
            },
            modifier = Modifier.testTag("persistent_folder_prompt_dialog")
        )
    }

    if (showDiagnosticLogDialog) {
        DiagnosticLogDialog(
            progress = progress,
            archiveSummary = archiveSummary,
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
                Text("Delete Extracted Folder?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Are you sure you want to permanently delete \"${folderPendingDeletion!!.name}\" and all of its unpacked files? This cannot be undone.")
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
                            folderTopOffsets.remove(toDelete.id)
                            viewModel.deleteExtractedFolder(toDelete)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (filePendingDeletion != null) {
        AlertDialog(
            onDismissRequest = { filePendingDeletion = null },
            icon = {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text("Delete File?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Are you sure you want to permanently delete \"${filePendingDeletion!!.name}\"? This cannot be undone.")
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
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDeletion = null }) {
                    Text("Cancel")
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
                                text = "In-Place Extraction Consumes Archive",
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
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "In-Place Extraction saves disk space by progressively shrinking and removing the source archive (\"${archiveSummary!!.fileName}\") as files are unpacked.\n\nThe original archive will not remain after extraction completes. Choose Standard Extraction if you want to keep the source archive intact.",
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
                                text = "Use Standard",
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
                                text = "Use In-Place",
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                scrollContainerTopInRoot = coords.positionInRoot().y
            }
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 8.dp, bottom = 148.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            ExpressiveCleanHeader(
                badgeText = "",
                title = "KaiHatch",
                subtitle = "Extract smarter. Use less space."
            )
        }

        // Crash-Safe Interrupted Extraction Resume Cards (.hatchstate.json)
        if (pendingResumeStates.isNotEmpty() && !isExtractionRunning) {
            pendingResumeStates.forEachIndexed { idx, resumeState ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("resume_journal_card_$idx"),
                    shape = RoundedCornerShape(26.dp),
                    color = tertiaryContainer
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
                                    icon = Icons.Default.Restore,
                                    containerColor = primaryContainer,
                                    iconColor = primary,
                                    size = 42.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Interrupted Unpack Ready",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = onTertiaryContainer
                                    )
                                    Text(
                                        text = "${resumeState.archiveName} • ${resumeState.remainingEntries.size}/${resumeState.totalEntries} left",
                                        fontSize = 11.sp,
                                        color = onTertiaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.dismissResumeState(resumeState) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss resume state",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        ExpressiveMorphButton(
                            text = "Resume In-Place Extraction",
                            subtitle = "Reclaimed so far: ${StorageHelper.formatBytes(resumeState.bytesReclaimedSoFar)}",
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
                }
            }
        }

        // STATE 1: No file selected -> Show Live Log Console directly on Extract Tab
        if (archiveSummary == null && !isExtractionRunning && progress.status != ExtractionStatus.COMPLETED) {
            JournalTerminal(
                logs = progress.journalLogs,
                isActive = false,
                modifier = Modifier.testTag("standby_journal_terminal")
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
                                text = "${summary.entryCount} files · ${StorageHelper.formatBytes(summary.fileSizeBytes)} · ${summary.format.extension.uppercase()}",
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
                                contentDescription = "Change Archive",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Change Archive",
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
                                contentDescription = "Clear archive selection",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Clear",
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
                            text = "Browse archive contents",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Browse archive contents",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Secondary Utility Chips: Password, Test Archive, Logs (N)
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
                                        text = if (archivePassword.isNotEmpty()) "Password Set" else "Password",
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
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        viewModel.verifySelectedArchiveIntegrity()
                                        showConsoleWhenIdle = true
                                        Toast.makeText(context, "Archive CRC32 & header integrity verified", Toast.LENGTH_SHORT).show()
                                    }
                                    .testTag("verify_archive_integrity_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "Test Archive",
                                        tint = primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Test Archive",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (!showConsoleWhenIdle && !isExtractionRunning && progress.status != ExtractionStatus.COMPLETED) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { showConsoleWhenIdle = true }
                                    .testTag("toggle_console_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = "Open Logs",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Logs (${progress.journalLogs.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
                            label = { Text("Archive Decryption Password") },
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
                                        text = "OK",
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
            if (showConsoleWhenIdle && !isExtractionRunning && progress.status != ExtractionStatus.COMPLETED) {
                JournalTerminal(
                    logs = progress.journalLogs,
                    isActive = false,
                    defaultExpanded = true,
                    onExpandedChange = { expanded ->
                        if (!expanded) showConsoleWhenIdle = false
                    },
                    modifier = Modifier.testTag("idle_expanded_journal_terminal")
                )
            }

            // 2. Extraction Method Section ("In-Place Extraction" vs "Standard Extraction")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Extraction method",
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
                            text = "In-Place Extraction",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Uses less storage • Consumes archive",
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
                            text = "Standard Extraction",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Preserves original archive",
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
                            text = "Source archive is progressively shrunk and consumed during In-Place Extraction.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Smart Storage Warning Banner if Standard Extraction exceeds free device space
            if (!isHatchMode && summary.deviceFreeSpace > 0L && summary.standardPeakSpaceRequired > summary.deviceFreeSpace) {
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
                                    text = "Insufficient Free Space for Standard Extraction",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Requires ${StorageHelper.formatBytes(summary.standardPeakSpaceRequired)} peak (${StorageHelper.formatBytes(summary.deviceFreeSpace)} free). Switch to In-Place Extraction.",
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
                            Text("Use In-Place", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    "$cleanBase / Extracted"
                } else {
                    "Select output folder"
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
                                        text = "Destination",
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
                                    text = if (grantedFolder != null) "Change" else "Select Folder",
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
                                text = if (isHatchMode) "Estimated storage saved" else "Estimated peak storage",
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
                    text = if (isHatchMode) "Start KaiHatch In-Place Unpack" else "Start Standard Extraction",
                    subtitle = if (isHatchMode) "Saves $consistentSavedText peak storage" else "Preserves original archive file",
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
                        text = if (showStorageComparison) "Hide detailed storage comparison" else "View detailed storage comparison",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showStorageComparison) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                AnimatedVisibility(visible = showStorageComparison) {
                    StorageGauge(
                        archiveBytes = summary.fileSizeBytes,
                        uncompressedBytes = summary.uncompressedSizeBytes,
                        currentArchiveOnDisk = summary.fileSizeBytes,
                        extractedOnDisk = 0L,
                        isHatchMode = isHatchMode,
                        deviceFreeBytes = summary.deviceFreeSpace,
                        hatchPeakOverrideBytes = summary.hatchPeakSpaceRequired
                    )
                }
            }
            }
        }

        // Error State Surface
        if (progress.status == ExtractionStatus.ERROR && !progress.errorMessage.isNullOrEmpty()) {
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
                                    text = "Extraction Failed",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = progress.errorMessage!!,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.dismissExtractionStatusCard() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    JournalTerminal(
                        logs = progress.journalLogs,
                        isActive = false
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showDiagnosticLogDialog = true },
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
                                contentDescription = "View Logs & Diagnostics",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Full Diagnostics",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                val report = buildDiagnosticReport(progress, archiveSummary)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("KaiHatch Extraction Logs", report))
                                Toast.makeText(context, "Diagnostic logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("copy_diagnostic_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy logs",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // STATE 3: Once Extract is hit -> Full Live Extraction HUD + Live Log Console
        if (isExtractionRunning) {
            val animatedOverall by animateFloatAsState(
                targetValue = progress.overallProgressFraction,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = primary,
                                strokeWidth = 3.dp,
                                strokeCap = StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isHatchMode) "In-Place Unpacking & Shrinking" else "Extracting Archive...",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (notificationsEnabled) "Background Worker & Status Bar Active" else "Background Worker Active",
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
                        Text(
                            text = "ACTIVE: ${progress.currentFileName.ifEmpty { "Preparing..." }}",
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
                        isWaveAnimated = true,
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
                                text = "+${StorageHelper.formatBytes(effectiveReclaimedBytes)} freed",
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
                                    text = if (progress.isCrcValid) "CRC32 OK" else "CRC ERR",
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
                        logs = progress.journalLogs,
                        isActive = true
                    )
                }
            }
        }

        // Completed State Card + Console
        if (progress.status == ExtractionStatus.COMPLETED) {
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
                                    text = "Unpack Complete",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${progress.totalEntries} files • ${StorageHelper.formatBytes(progress.bytesExtracted)}" +
                                        if (progress.isHatchMode) " • ${StorageHelper.formatBytes(progress.bytesReclaimed)} reclaimed" else "",
                                    fontSize = 11.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = primary
                                )
                            }
                        }

                        if (progress.outputDirectory != null) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.refreshLocalFiles()
                                    val matched = resolveTargetExtractedFolder(
                                        extractedFolders = extractedFolders,
                                        completedTarget = lastCompletedTarget,
                                        fallbackOutputDirectory = progress.outputDirectory
                                    )
                                    if (matched != null) {
                                        expandedFolderPath = matched.id
                                        pendingScrollFolderId = matched.id
                                    } else {
                                        handledAutoExpandCompletionKey = null
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("view_extracted_files_button")
                            ) {
                                Text("Files", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    JournalTerminal(
                        logs = progress.journalLogs,
                        isActive = false,
                        defaultExpanded = false
                    )
                }
            }
        }

        // Extracted Output Folders (Placed first before Local Archive Repository)
        if (extractedFolders.isNotEmpty()) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                ExpressiveSectionHeader(
                    title = "Extracted Output Folders",
                    countText = "${extractedFolders.size}",
                    accentColor = primary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                extractedFolders.forEachIndexed { idx, folder ->
                    key(folder.id) {
                    val isExpanded = isExtractedFolderExpanded(
                        folder = folder,
                        expandedFolderId = expandedFolderPath,
                        allFolders = extractedFolders
                    )
                    val filesInside = folder.children
                    val pos = groupPositionFor(idx, extractedFolders.size)

                    ExpressiveGroupedCard(
                        position = pos,
                        isSelected = isExpanded,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {
                            expandedFolderPath = if (isExpanded) null else folder.id
                        },
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val contentOffsetY = (coords.positionInRoot().y - scrollContainerTopInRoot + scrollState.value).roundToInt()
                                folderTopOffsets[folder.id] = contentOffsetY.coerceAtLeast(0)
                            }
                            .testTag("extracted_folder_$idx")
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                            .clip(RoundedCornerShape(12.dp))
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
                                            text = "${StorageHelper.formatBytes(folder.totalSizeBytes)} • ${filesInside.size} items",
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
                                            shape = RoundedCornerShape(50),
                                            color = secondaryContainer,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable {
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
                                                    contentDescription = "Pack folder",
                                                    tint = onSecondaryContainer,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Pack",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = onSecondaryContainer
                                                )
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = { folderPendingDeletion = folder },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete extracted folder",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (isExpanded) primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                expandedFolderPath = if (isExpanded) null else folder.id
                                            }
                                            .testTag("toggle_extracted_folder_$idx")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                                tint = if (isExpanded) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            if (isExpanded) {
                                                Text(
                                                    text = "Collapse",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (filesInside.isEmpty()) {
                                        Text(
                                            text = "No files found in folder",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    } else {
                                        filesInside.forEach { childFile ->
                                            val extLower = childFile.name.substringAfterLast('.', "").lowercase()
                                            val fileIcon = when {
                                                extLower in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg") -> Icons.Default.Image
                                                extLower in setOf("mp4", "mkv", "webm", "avi", "mov") -> Icons.Default.VideoFile
                                                extLower in setOf("mp3", "wav", "ogg", "flac", "m4a") -> Icons.Default.AudioFile
                                                extLower in setOf("zip", "7z", "rar", "tar", "gz", "apk") -> Icons.Default.Archive
                                                extLower in setOf("iso", "img", "bin", "rom") -> Icons.Default.Album
                                                extLower in setOf("kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "md") -> Icons.Default.Code
                                                else -> Icons.Default.Description
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .clickable {
                                                        coroutineScope.launch {
                                                            val preview = withContext(Dispatchers.IO) {
                                                                if (childFile.safUri != null) {
                                                                    StorageHelper.readSafDocumentPreview(context, childFile.safUri, childFile.name, childFile.sizeBytes)
                                                                } else if (childFile.localFile != null) {
                                                                    StorageHelper.readFilePreview(childFile.localFile)
                                                                } else null
                                                            }
                                                            viewingFileEntry = childFile
                                                            viewingPreviewData = preview
                                                        }
                                                    }
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
                                                                text = "${StorageHelper.formatBytes(childFile.sizeBytes)} • ${extLower.uppercase().ifEmpty { "FILE" }}",
                                                                fontSize = 10.sp,
                                                                fontFamily = JetBrainsMonoFontFamily,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        IconButton(
                                                            onClick = { filePendingDeletion = childFile },
                                                            modifier = Modifier.size(28.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Delete file",
                                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Surface(
                                                            onClick = {
                                                                StorageHelper.openExtractedFileEntryWithSystem(context, childFile)
                                                            },
                                                            shape = RoundedCornerShape(50),
                                                            color = primaryContainer,
                                                            modifier = Modifier.testTag("open_extracted_file_${childFile.name}")
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = "OPEN",
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

                                        // Bottom collapse button so user can collapse right from the bottom of long file lists
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable { expandedFolderPath = null }
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
                                                    contentDescription = "Collapse folder",
                                                    tint = primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Collapse Folder",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = primary
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
        }

        // Available Archives Section (Placed after Extracted Output Folders)
        Column {
            Spacer(modifier = Modifier.height(2.dp))
            ExpressiveSectionHeader(
                title = "Available Archives",
                countText = "${availableArchives.size}",
                accentColor = primary
            )
        }

        if (availableArchives.isEmpty()) {
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
                            text = "No archives in folder",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Tap Open below to pick an external archive file.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                availableArchives.forEachIndexed { index, file ->
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
                        modifier = Modifier.testTag("archive_item_$index")
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
                                        .clip(RoundedCornerShape(12.dp))
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
                                    Text(
                                        text = "${StorageHelper.formatBytes(archiveBytes)} • ${ZipParser.detectFormat(file.name).label}",
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
                                        contentDescription = "Preview archive",
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
                                        contentDescription = "Share archive",
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
                                        contentDescription = "Delete archive",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Select",
                                    tint = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(96.dp))
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
    progress: com.example.model.ExtractionProgress,
    summary: com.example.model.ArchiveSummary?
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
    sb.appendLine("EVENT JOURNAL LOGS (${progress.journalLogs.size} events):")
    if (progress.journalLogs.isEmpty()) {
        sb.appendLine("(No journal logs recorded)")
    } else {
        progress.journalLogs.forEach { sb.appendLine(it) }
    }
    sb.appendLine("==========================================")
    return sb.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticLogDialog(
    progress: com.example.model.ExtractionProgress,
    archiveSummary: com.example.model.ArchiveSummary?,
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
                                text = "Extraction Diagnostics",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = progress.archiveName.ifEmpty { archiveSummary?.fileName ?: "Archive" },
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
                            contentDescription = "Close diagnostics",
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
                        text = "STATUS: ${progress.status.name}",
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
                            text = "TOTAL: ${StorageHelper.formatBytes(progress.totalUncompressedBytes)}",
                            container = MaterialTheme.colorScheme.surfaceContainerHigh,
                            content = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (progress.isHatchMode) {
                        ExpressivePillBadge(
                            text = "IN-PLACE HATCH",
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

                Text(
                    text = "EVENT JOURNAL (${progress.journalLogs.size} ENTRIES)",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                JournalTerminal(
                    logs = if (progress.journalLogs.isEmpty()) listOf("No events logged for this session yet.") else progress.journalLogs,
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
                            val dump = buildDiagnosticReport(progress, archiveSummary)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("KaiHatch Diagnostics", dump))
                            Toast.makeText(context, "Diagnostic log copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("copy_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy report",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy Logs",
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
                            text = "Close",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
