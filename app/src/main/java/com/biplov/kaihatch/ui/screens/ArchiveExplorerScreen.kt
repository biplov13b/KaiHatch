package com.biplov.kaihatch.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.FilePreviewData
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionProgress
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ZipEntryInfo
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.ScreenTab
import com.biplov.kaihatch.ui.components.ExpressiveCleanHeader
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupPosition
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
import com.biplov.kaihatch.ui.components.ExpressiveShapes
import com.biplov.kaihatch.ui.components.FileViewerDialog
import com.biplov.kaihatch.ui.components.groupPositionFor
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArchiveExplorerScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exploringArchive by viewModel.exploringArchive.collectAsStateWithLifecycle()
    val exploringSafUri by viewModel.exploringSafUri.collectAsStateWithLifecycle()
    val exploringArchiveName by viewModel.exploringArchiveName.collectAsStateWithLifecycle()
    val allEntries by viewModel.explorerAllEntries.collectAsStateWithLifecycle()
    val currentFolderPath by viewModel.currentFolderPath.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedPaths by viewModel.selectedExplorerPaths.collectAsStateWithLifecycle()
    val archivePassword by viewModel.archivePassword.collectAsStateWithLifecycle()
    val extractionProgressState = viewModel.extractionProgress.collectAsStateWithLifecycle()
    val showPersistentFolderPrompt by viewModel.showPersistentFolderPrompt.collectAsStateWithLifecycle()

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer

    var activeEntry by remember { mutableStateOf<ZipEntryInfo?>(null) }
    var activePreview by remember { mutableStateOf<FilePreviewData?>(null) }

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
                    text = stringResource(R.string.dialog_output_folder_title),
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_output_folder_explorer_body)
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
                    )
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
                TextButton(onClick = { viewModel.dismissPersistentFolderPrompt() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val hasOpenedArchive = exploringArchive != null || exploringSafUri != null
    val hasInternalBackState = activeEntry != null ||
        selectedPaths.isNotEmpty() ||
        searchQuery.isNotEmpty() ||
        currentFolderPath.isNotEmpty()

    BackHandler(enabled = hasInternalBackState) {
        when {
            activeEntry != null -> {
                activeEntry = null
                activePreview = null
            }
            selectedPaths.isNotEmpty() -> viewModel.clearExplorerSelection()
            searchQuery.isNotEmpty() -> viewModel.setSearchQuery("")
            currentFolderPath.isNotEmpty() -> viewModel.navigateUpFolder()
        }
    }

    if (activeEntry != null && activePreview != null && hasOpenedArchive) {
        val entryToExtract = activeEntry!!
        FileViewerDialog(
            previewData = activePreview!!,
            crcHex = entryToExtract.crcHex,
            onResolveFileForAction = {
                StorageHelper.extractSingleArchiveEntry(
                    context = context,
                    archiveFile = exploringArchive,
                    safUri = exploringSafUri,
                    entry = entryToExtract,
                    destDir = StorageHelper.getTempDir(context),
                    password = archivePassword.ifEmpty { null }
                )
            },
            onDismiss = {
                activeEntry = null
                activePreview = null
            }
        )
    }

    // Filter entries based on folder or search (memoized to prevent redundant O(N) scans)
    val displayedEntries: List<ZipEntryInfo> = remember(allEntries, currentFolderPath, searchQuery) {
        if (searchQuery.isNotEmpty()) {
            allEntries.filter { it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true) }
        } else {
            val normalizedFolder = currentFolderPath.trimStart('/')
            val itemsMap = mutableMapOf<String, ZipEntryInfo>()

            for (entry in allEntries) {
                val entryPath = entry.path.trimStart('/')
                if (normalizedFolder.isEmpty()) {
                    val topLevelName = entryPath.substringBefore('/')
                    val isDir = entryPath.contains('/') || entry.isDirectory
                    if (!itemsMap.containsKey(topLevelName)) {
                        itemsMap[topLevelName] = entry.copy(
                            name = topLevelName,
                            path = if (isDir) "$topLevelName/" else entry.path,
                            isDirectory = isDir
                        )
                    }
                } else if (entryPath.startsWith(normalizedFolder) && entryPath != normalizedFolder) {
                    val relative = entryPath.removePrefix(normalizedFolder).trimStart('/')
                    val directChild = relative.substringBefore('/')
                    val isDir = relative.contains('/') || entry.isDirectory
                    if (!itemsMap.containsKey(directChild)) {
                        itemsMap[directChild] = entry.copy(
                            name = directChild,
                            path = if (isDir) "$normalizedFolder$directChild/" else entry.path,
                            isDirectory = isDir
                        )
                    }
                }
            }
            itemsMap.values.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    val totalFileCount = remember(allEntries) {
        allEntries.count { !it.isDirectory }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 8.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Clean, Minimal Material 3 Expressive Header
        val defaultExplorerSubtitle = stringResource(R.string.explorer_header_subtitle_default)
        ExpressiveCleanHeader(
            badgeText = if (hasOpenedArchive) {
                stringResource(R.string.explorer_header_files_badge, totalFileCount)
            } else {
                stringResource(R.string.explorer_header_badge_preview)
            },
            title = stringResource(R.string.explorer_header_title),
            subtitle = exploringArchiveName.ifEmpty { defaultExplorerSubtitle }
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (!hasOpenedArchive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ExpressiveGroupedCard(
                    position = ExpressiveGroupPosition.SINGLE,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ExpressiveCloverIcon(
                            icon = Icons.Default.Archive,
                            containerColor = primaryContainer,
                            iconColor = primary,
                            size = 64.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.explorer_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.explorer_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // Active Archive Info Bar with Close / Deselect button
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = exploringArchiveName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { viewModel.closeExplorerArchive() }
                            .testTag("explorer_close_archive_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.explorer_close_archive_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_close),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expressive Pill Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.explorer_search_placeholder), fontSize = 13.sp) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = primary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.explorer_clear_search_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("explorer_search_input"),
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Live Partial / Full Extraction Feedback Banner in Explorer
            ExplorerExtractionFeedbackBanner(
                extractionProgressState = extractionProgressState,
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                onViewExtractedClick = { viewModel.switchTab(ScreenTab.EXTRACT) }
            )

            // Multi-select Pill Banner when items are checked
            if (selectedPaths.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.explorer_batch_selected_count, selectedPaths.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = onPrimaryContainer
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.explorer_invert_selection),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = onPrimaryContainer,
                                modifier = Modifier
                                    .clickable { viewModel.invertExplorerSelection(displayedEntries) }
                                    .testTag("explorer_invert_selection_button")
                            )
                            Text(
                                text = stringResource(R.string.explorer_clear_search_cd),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primary,
                                modifier = Modifier.clickable { viewModel.clearExplorerSelection() }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Expressive Pill Breadcrumbs Bar + Batch Select All (when not searching)
            val selectableFilesInView = remember(displayedEntries) {
                displayedEntries.filter { !it.isDirectory }
            }
            val allVisibleSelected = remember(selectableFilesInView, selectedPaths) {
                selectableFilesInView.isNotEmpty() && selectableFilesInView.all { it.path in selectedPaths }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (searchQuery.isEmpty()) {
                    val segments = remember(currentFolderPath) {
                        currentFolderPath.split('/').filter { it.isNotEmpty() }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentFolderPath.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.navigateUpFolder() },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item(key = "crumb_root") {
                                Surface(
                                    shape = CircleShape,
                                    color = if (currentFolderPath.isEmpty()) primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.clickable { viewModel.navigateToFolder("") }
                                ) {
                                    Text(
                                        text = stringResource(R.string.explorer_root_crumb),
                                        color = if (currentFolderPath.isEmpty()) onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            var accumulated = ""
                            for (seg in segments) {
                                accumulated += "$seg/"
                                val target = accumulated
                                item(key = "crumb_$target") {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (target == currentFolderPath) primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier.clickable { viewModel.navigateToFolder(target) }
                                    ) {
                                        Text(
                                            text = seg,
                                            color = if (target == currentFolderPath) onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.explorer_results_count, displayedEntries.size),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectableFilesInView.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (allVisibleSelected) primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { viewModel.selectAllInCurrentExplorerFolder(displayedEntries) }
                            .testTag("explorer_select_all_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.explorer_select_all_cd),
                                tint = if (allVisibleSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (allVisibleSelected) {
                                    stringResource(R.string.explorer_deselect_all)
                                } else {
                                    stringResource(R.string.explorer_select_all_count, selectableFilesInView.size)
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (allVisibleSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Entries List (M3 Expressive Stacked Group)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 148.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (displayedEntries.isEmpty()) {
                    item {
                        ExpressiveGroupedCard(
                            position = ExpressiveGroupPosition.SINGLE,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.explorer_folder_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    items(
                        count = displayedEntries.size,
                        key = { index -> displayedEntries[index].path },
                        contentType = { index -> if (displayedEntries[index].isDirectory) "dir" else "file" }
                    ) { index ->
                        val entry = displayedEntries[index]
                        val isChecked = selectedPaths.contains(entry.path)
                        val pos = groupPositionFor(index, displayedEntries.size)

                        ExpressiveGroupedCard(
                            position = pos,
                            isSelected = isChecked,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = primaryContainer,
                            onClick = {
                                if (entry.isDirectory) {
                                    viewModel.navigateToFolder(entry.path)
                                } else {
                                    scope.launch {
                                        val preview = withContext(Dispatchers.IO) {
                                            StorageHelper.readArchiveEntryPreview(
                                                context = context,
                                                archiveFile = exploringArchive,
                                                safUri = exploringSafUri,
                                                entry = entry,
                                                password = archivePassword.ifEmpty { null }
                                            )
                                        }
                                        activeEntry = entry
                                        activePreview = preview
                                    }
                                }
                            },
                            modifier = Modifier.testTag("explorer_entry_${entry.name}")
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
                                    if (!entry.isDirectory) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { viewModel.toggleExplorerSelection(entry.path) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = primary,
                                                checkmarkColor = onPrimary,
                                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    } else {
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(ExpressiveShapes.IconBox)
                                            .background(
                                                if (entry.isDirectory) secondaryContainer else primaryContainer
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            tint = if (entry.isDirectory) onSecondaryContainer else primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = entry.name,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (entry.isEncrypted) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = stringResource(R.string.sheet_preview_encrypted_cd),
                                                    tint = primary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }

                                        if (!entry.isDirectory) {
                                            val formattedUncompressedSize = remember(entry.uncompressedSize) {
                                                StorageHelper.formatBytes(entry.uncompressedSize)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.explorer_entry_meta_saved,
                                                        formattedUncompressedSize,
                                                        entry.ratio.toInt()
                                                    ),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = entry.crcHex,
                                                    fontSize = 10.sp,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    color = primary
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = stringResource(R.string.explorer_directory_subtitle),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                if (entry.isDirectory) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = stringResource(R.string.explorer_open_directory_cd),
                                        tint = primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = when {
                                                    entry.isZip64 -> "ZIP64"
                                                    entry.compressionMethod == 0 -> "STORE"
                                                    else -> "DEFLATE"
                                                },
                                                fontSize = 9.sp,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                color = primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Default.Visibility,
                                            contentDescription = stringResource(R.string.explorer_preview_file_cd),
                                            tint = primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(96.dp))
                }
            }
        }
    }
}

@Composable
private fun ExplorerExtractionFeedbackBanner(
    extractionProgressState: State<ExtractionProgress>,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    onViewExtractedClick: () -> Unit
) {
    val status by remember {
        derivedStateOf { extractionProgressState.value.status }
    }
    val isExplorerExtracting = status == ExtractionStatus.ANALYZING ||
        status == ExtractionStatus.EXTRACTING ||
        status == ExtractionStatus.VERIFYING_CRC ||
        status == ExtractionStatus.TRUNCATING

    if (isExplorerExtracting || status == ExtractionStatus.COMPLETED) {
        val extractionProgress = extractionProgressState.value
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (status == ExtractionStatus.COMPLETED) primaryContainer else secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("explorer_extraction_feedback_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isExplorerExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = primary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isExplorerExtracting) {
                                stringResource(
                                    R.string.explorer_extracting_banner,
                                    (extractionProgress.overallProgressFraction * 100).toInt(),
                                    extractionProgress.completedEntries,
                                    extractionProgress.totalEntries
                                )
                            } else {
                                stringResource(
                                    R.string.explorer_extracted_banner,
                                    extractionProgress.totalEntries,
                                    StorageHelper.formatBytes(extractionProgress.bytesExtracted)
                                )
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (status == ExtractionStatus.COMPLETED) onPrimaryContainer else onSecondaryContainer
                        )
                        if (isExplorerExtracting && extractionProgress.currentFileName.isNotEmpty()) {
                            Text(
                                text = extractionProgress.currentFileName,
                                fontSize = 10.sp,
                                color = onSecondaryContainer.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(50),
                    color = primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onViewExtractedClick)
                        .testTag("explorer_view_extracted_button")
                ) {
                    Text(
                        text = stringResource(R.string.explorer_view_files_button),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

