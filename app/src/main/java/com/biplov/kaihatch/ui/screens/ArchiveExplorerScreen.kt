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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biplov.kaihatch.data.FilePreviewData
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ExtractionStatus
import com.biplov.kaihatch.model.ZipEntryInfo
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.ScreenTab
import com.biplov.kaihatch.ui.components.ExpressiveCleanHeader
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupPosition
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
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

    val exploringArchive by viewModel.exploringArchive.collectAsState()
    val exploringSafUri by viewModel.exploringSafUri.collectAsState()
    val exploringArchiveName by viewModel.exploringArchiveName.collectAsState()
    val allEntries by viewModel.explorerAllEntries.collectAsState()
    val currentFolderPath by viewModel.currentFolderPath.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedPaths by viewModel.selectedExplorerPaths.collectAsState()
    val archivePassword by viewModel.archivePassword.collectAsState()
    val extractionProgress by viewModel.extractionProgress.collectAsState()
    val showPersistentFolderPrompt by viewModel.showPersistentFolderPrompt.collectAsState()

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
                    text = "Select Output Folder First",
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    text = "Please choose a destination folder so KaiHatch can unpack your selected files directly to your storage."
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
                    Text("Select Folder & Extract", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPersistentFolderPrompt() }) {
                    Text("Cancel")
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
        ExpressiveCleanHeader(
            badgeText = if (hasOpenedArchive) "$totalFileCount FILES" else "ARCHIVE PREVIEW",
            title = "Explorer",
            subtitle = exploringArchiveName.ifEmpty { "Inspect folders, preview files, and extract only what you need" }
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
                            text = "No Archive Loaded",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the floating Open button at the bottom right to inspect and preview any ZIP, ZIP64, 7Z, APK, TAR, or TAR.GZ container without extracting the full file.",
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
                                contentDescription = "Close archive",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Close",
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
                placeholder = { Text("Search files inside archive...", fontSize = 13.sp) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = primary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            val isExplorerExtracting = extractionProgress.status in listOf(
                ExtractionStatus.ANALYZING,
                ExtractionStatus.EXTRACTING,
                ExtractionStatus.VERIFYING_CRC,
                ExtractionStatus.TRUNCATING
            )
            if (isExplorerExtracting || extractionProgress.status == ExtractionStatus.COMPLETED) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = if (extractionProgress.status == ExtractionStatus.COMPLETED) primaryContainer else secondaryContainer,
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
                                        "Extracting (${(extractionProgress.overallProgressFraction * 100).toInt()}%) • ${extractionProgress.completedEntries}/${extractionProgress.totalEntries}"
                                    } else {
                                        "Extracted ${extractionProgress.totalEntries} item(s) (${StorageHelper.formatBytes(extractionProgress.bytesExtracted)})"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (extractionProgress.status == ExtractionStatus.COMPLETED) onPrimaryContainer else onSecondaryContainer
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
                                .clickable { viewModel.switchTab(ScreenTab.EXTRACT) }
                                .testTag("explorer_view_extracted_button")
                        ) {
                            Text(
                                text = "View Files",
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
                            text = "${selectedPaths.size} item(s) selected for batch unpack",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = onPrimaryContainer
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Invert",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = onPrimaryContainer,
                                modifier = Modifier
                                    .clickable { viewModel.invertExplorerSelection(displayedEntries) }
                                    .testTag("explorer_invert_selection_button")
                            )
                            Text(
                                text = "Clear",
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
                    val segments = currentFolderPath.split('/').filter { it.isNotEmpty() }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentFolderPath.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.navigateUpFolder() },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = primary, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (currentFolderPath.isEmpty()) primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.clickable { viewModel.navigateToFolder("") }
                                ) {
                                    Text(
                                        text = "root",
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
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(50),
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
                        text = "${displayedEntries.size} result(s)",
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
                                contentDescription = "Select all files in folder",
                                tint = if (allVisibleSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (allVisibleSelected) "Deselect All" else "Select All (${selectableFilesInView.size})",
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
                                    text = "Folder is empty",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    items(displayedEntries.size) { index ->
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
                                            .clip(RoundedCornerShape(14.dp))
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
                                                    contentDescription = "Encrypted",
                                                    tint = primary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }

                                        if (!entry.isDirectory) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "${StorageHelper.formatBytes(entry.uncompressedSize)} • ${entry.ratio.toInt()}% saved",
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
                                                text = "Directory • Tap to browse",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                if (entry.isDirectory) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Open directory",
                                        tint = primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
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
                                            contentDescription = "Preview file",
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
