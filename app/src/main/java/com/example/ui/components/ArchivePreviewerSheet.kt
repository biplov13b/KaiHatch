package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.FilePreviewData
import com.example.data.StorageHelper
import com.example.model.ArchiveSummary
import com.example.model.ZipEntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePreviewerDialog(
    summary: ArchiveSummary,
    archiveFile: File? = null,
    safUri: Uri? = null,
    password: String? = null,
    onOpenInExplorer: (() -> Unit)? = null,
    onExtractArchive: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var activeEntry by remember { mutableStateOf<ZipEntryInfo?>(null) }
    var activePreview by remember { mutableStateOf<FilePreviewData?>(null) }

    val filteredEntries = remember(summary.entries, searchQuery) {
        if (searchQuery.isBlank()) {
            summary.entries
        } else {
            summary.entries.filter { it.path.contains(searchQuery, ignoreCase = true) }
        }
    }

    if (activeEntry != null && activePreview != null) {
        val target = activeEntry!!
        FileViewerDialog(
            previewData = activePreview!!,
            crcHex = target.crcHex,
            onResolveFileForAction = {
                StorageHelper.extractSingleArchiveEntry(
                    context = context,
                    archiveFile = archiveFile,
                    safUri = safUri,
                    entry = target,
                    destDir = StorageHelper.getTempDir(context),
                    password = password
                )
            },
            onDismiss = {
                activeEntry = null
                activePreview = null
            }
        )
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val rowBgColor = MaterialTheme.colorScheme.surfaceContainer
    val rowBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ExpressiveShapes.CardOuter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("archive_preview_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Central Directory Inspector",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${summary.fileName} • ${summary.entryCount} entries indexed without extracting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_preview_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter entries by name or extension...") },
                singleLine = true,
                shape = ExpressiveShapes.SubItem,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = rowBgColor,
                    unfocusedContainerColor = rowBgColor
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview_search_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = filteredEntries,
                    key = { it.path },
                    contentType = { if (it.isDirectory) "dir" else "file" }
                ) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.IconBox)
                            .background(rowBgColor)
                            .border(1.dp, rowBorderColor, ExpressiveShapes.IconBox)
                            .clickable(enabled = !entry.isDirectory) {
                                scope.launch {
                                    val preview = withContext(Dispatchers.IO) {
                                        StorageHelper.readArchiveEntryPreview(
                                            context = context,
                                            archiveFile = archiveFile,
                                            safUri = safUri,
                                            entry = entry,
                                            password = password
                                        )
                                    }
                                    activeEntry = entry
                                    activePreview = preview
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(ExpressiveShapes.Small)
                                    .background(
                                        if (entry.isDirectory) tertiaryColor.copy(alpha = 0.14f)
                                        else primaryColor.copy(alpha = 0.14f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) tertiaryColor else primaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = entry.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (entry.isEncrypted) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Encrypted",
                                            tint = tertiaryColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Offset: 0x${entry.localHeaderOffset.toString(16).uppercase()} • ${entry.crcHex}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${StorageHelper.formatBytes(entry.compressedSize)} -> ${StorageHelper.formatBytes(entry.uncompressedSize)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor
                            )
                        }
                    }
                }
            }

            if (onOpenInExplorer != null || onExtractArchive != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onOpenInExplorer != null) {
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                onOpenInExplorer()
                            },
                            shape = ExpressiveShapes.SubItem,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open in Explorer", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onExtractArchive != null) {
                        Button(
                            onClick = {
                                onDismiss()
                                onExtractArchive()
                            },
                            shape = ExpressiveShapes.SubItem,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unpack Now", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}
