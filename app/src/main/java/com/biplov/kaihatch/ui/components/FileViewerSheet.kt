package com.biplov.kaihatch.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.ExtractedFileEntry
import com.biplov.kaihatch.data.FilePreviewData
import com.biplov.kaihatch.data.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerDialog(
    previewData: FilePreviewData,
    fileEntry: ExtractedFileEntry? = null,
    crcHex: String? = null,
    onResolveFileForAction: (suspend () -> File?)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val primaryColor = MaterialTheme.colorScheme.primary
    val innerBgColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)

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
                .testTag("file_viewer_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(ExpressiveShapes.Small)
                            .background(primaryColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (previewData.isBinary) Icons.Default.Hexagon else Icons.Default.Code,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = previewData.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        val metaText = remember(previewData.sizeBytes, previewData.mimeType, crcHex) {
                            buildString {
                                append(StorageHelper.formatBytes(previewData.sizeBytes))
                                append(" • ")
                                append(previewData.mimeType)
                                if (!crcHex.isNullOrEmpty()) {
                                    append(" • ")
                                    append(crcHex)
                                }
                            }
                        }
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_file_viewer_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sheet_viewer_close_cd))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (previewData.isBinary) {
                            stringResource(R.string.sheet_viewer_badge_hex)
                        } else {
                            stringResource(R.string.sheet_viewer_badge_text)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryColor
                    )
                }

                if (onResolveFileForAction != null || fileEntry?.localFile != null) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    onResolveFileForAction?.invoke() ?: fileEntry?.localFile
                                }
                                if (file != null && file.exists()) {
                                    try {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                intent,
                                                context.getString(R.string.sheet_viewer_share_chooser, file.name)
                                            )
                                        )
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val hScroll = rememberScrollState()
            val emptyFileLabel = stringResource(R.string.sheet_viewer_empty_file)
            val lineChunks = remember(previewData.contentPreview, emptyFileLabel) {
                val raw = previewData.contentPreview.ifEmpty { emptyFileLabel }
                val lines = raw.split('\n')
                if (lines.size <= 24) {
                    listOf(raw)
                } else {
                    lines.chunked(24).map { it.joinToString("\n") }
                }
            }
            val textColor = if (previewData.isBinary) primaryColor else MaterialTheme.colorScheme.onSurface
            val monoTextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 440.dp)
                    .clip(ExpressiveShapes.RowItem)
                    .background(innerBgColor)
                    .border(1.dp, borderColor, ExpressiveShapes.RowItem)
                    .horizontalScroll(hScroll),
                contentPadding = PaddingValues(14.dp)
            ) {
                itemsIndexed(
                    items = lineChunks,
                    key = { index, _ -> "preview_chunk_$index" },
                    contentType = { _, _ -> "preview_text_chunk" }
                ) { _, chunk ->
                    Text(
                        text = chunk,
                        style = monoTextStyle,
                        fontFamily = FontFamily.Monospace,
                        color = textColor,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}
