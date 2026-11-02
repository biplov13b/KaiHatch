package com.biplov.kaihatch.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.CompressionStatus
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.ScreenTab
import com.biplov.kaihatch.ui.components.ExpressiveBentoTile
import com.biplov.kaihatch.ui.components.ExpressiveCleanHeader
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupPosition
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
import com.biplov.kaihatch.ui.components.ExpressiveMorphButton
import com.biplov.kaihatch.ui.components.ExpressiveSectionHeader
import com.biplov.kaihatch.ui.components.ExpressiveWaveProgressIndicator
import com.biplov.kaihatch.ui.components.groupPositionFor
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompressScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val itemsToCompress by viewModel.itemsToCompress.collectAsState()
    val settings by viewModel.compressionSettings.collectAsState()
    val progress by viewModel.compressionProgress.collectAsState()
    val extractedFolders by viewModel.extractedFolders.collectAsState()
    val fileSizesCache by viewModel.fileSizesCache.collectAsState()
    val grantedFolder by viewModel.grantedFolder.collectAsState()
    val showPersistentFolderPrompt by viewModel.showPersistentFolderPrompt.collectAsState()

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer

    var archiveNameInput by remember(settings.archiveName) { mutableStateOf(settings.archiveName) }
    var passwordInput by remember(settings.password) { mutableStateOf(settings.password) }
    var showPasswordField by remember { mutableStateOf(settings.password.isNotEmpty()) }

    val addFilesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addExternalUrisToCompress(context, uris)
        }
    }

    val addFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.addExternalFolderToCompress(context, uri)
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
                    text = "Select Output Folder First",
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    text = "Please choose a destination folder (the same output folder used for extraction) so KaiHatch can automatically save your created archive directly to your device storage."
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
                    modifier = Modifier.testTag("confirm_select_persistent_folder_compress_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Folder & Pack", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissPersistentFolderPrompt() }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 8.dp, bottom = 148.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            ExpressiveCleanHeader(
                badgeText = "ARCHIVE BUILDER",
                title = "Pack Studio",
                subtitle = "Create compressed or password-protected archives directly to your folder"
            )
        }

        // Archive Configuration Expressive Bento Surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("compression_settings_card"),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "CONTAINER FORMAT",
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                val allFormats = listOf(
                    ArchiveFormat.ZIP to "ZIP",
                    ArchiveFormat.SEVEN_Z to "7Z",
                    ArchiveFormat.RAR to "RAR",
                    ArchiveFormat.TAR to "TAR",
                    ArchiveFormat.TAR_GZ to "TAR.GZ"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allFormats.forEach { (fmt, shortTitle) ->
                        val selected = settings.format == fmt
                        val corner = if (selected) 20.dp else 14.dp
                        val bg = if (selected) primary else MaterialTheme.colorScheme.surfaceContainerLow

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(corner))
                                .clickable {
                                    viewModel.updateCompressionSettings(settings.copy(format = fmt))
                                }
                                .testTag("format_chip_${fmt.name.lowercase()}"),
                            shape = RoundedCornerShape(corner),
                            color = bg
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = shortTitle,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    color = if (selected) onPrimary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val formatCaption = when (settings.format) {
                    ArchiveFormat.ZIP -> ".zip • Universal Deflate compression with optional ZipCrypto password"
                    ArchiveFormat.SEVEN_Z -> ".7z • High-ratio LZMA2 solid archive compression"
                    ArchiveFormat.RAR -> ".rar • Compatible archive container with optional password"
                    ArchiveFormat.TAR -> ".tar • Uncompressed POSIX stream archive"
                    ArchiveFormat.TAR_GZ -> ".tar.gz • Gzip-compressed POSIX tarball"
                    else -> ".zip • Standard Deflate archive"
                }
                Text(
                    text = formatCaption,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = archiveNameInput,
                    onValueChange = {
                        archiveNameInput = it
                        viewModel.updateCompressionSettings(
                            settings.copy(archiveName = it),
                            userEditedName = true
                        )
                    },
                    label = { Text("Output Archive Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("archive_name_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedBorderColor = primary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedLabelColor = primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(22.dp)
                )

                if (settings.format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR)) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "COMPRESSION LEVEL",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 0.8.sp
                        )

                        if (settings.format in listOf(ArchiveFormat.ZIP, ArchiveFormat.RAR)) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (settings.password.isNotEmpty() || showPasswordField) {
                                    primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { showPasswordField = !showPasswordField }
                                    .testTag("toggle_compress_password_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
                                        text = if (settings.password.isNotEmpty()) "Password Set" else "Password",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (settings.password.isNotEmpty() || showPasswordField) {
                                            onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val levels = listOf(
                        0 to "Store (0)",
                        1 to "Fast (1)",
                        6 to "Balanced (6)",
                        9 to "Ultra (9)"
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        levels.forEach { (lvl, label) ->
                            val selected = settings.compressionLevel == lvl
                            ExpressivePillChip(
                                label = label,
                                selected = selected,
                                activeContainer = primary,
                                activeContent = onPrimary,
                                onClick = {
                                    viewModel.updateCompressionSettings(settings.copy(compressionLevel = lvl))
                                }
                            )
                        }
                    }
                }

                if (settings.format in listOf(ArchiveFormat.ZIP, ArchiveFormat.RAR) && showPasswordField) {
                    val applyAndClosePassword = {
                        viewModel.updateCompressionSettings(settings.copy(password = passwordInput))
                        showPasswordField = false
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            viewModel.updateCompressionSettings(settings.copy(password = it))
                        },
                        label = { Text("Password Protection (Optional)") },
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
                                    .testTag("apply_compress_password_button")
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
                            .testTag("compress_password_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedBorderColor = primary,
                            unfocusedBorderColor = Color.Transparent,
                            focusedLabelColor = primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }
        }

        // Destination Folder Bar (Same persistent folder used for extraction)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("compression_destination_card"),
            shape = RoundedCornerShape(22.dp),
            color = if (grantedFolder != null) primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (grantedFolder != null) Icons.Default.FolderSpecial else Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (grantedFolder != null) "Save to: ${grantedFolder!!.displayName}" else "Output Folder: Not Selected",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (grantedFolder != null) "Auto-saved directly to extraction folder" else "Prompted on Pack",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = { destinationFolderPickerLauncher.launch(null) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (grantedFolder != null) MaterialTheme.colorScheme.surfaceContainerHigh else primary,
                        contentColor = if (grantedFolder != null) MaterialTheme.colorScheme.onSurface else onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (grantedFolder != null) "Change" else "Select Folder",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Active Compression Wavy In-App Progress Card
        if (progress.status == CompressionStatus.COMPRESSING || progress.status == CompressionStatus.PREPARING) {
            val rawFrac = progress.overallProgressFraction

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compression_progress_card"),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Packing (${settings.format.label})...",
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "OVERALL PROGRESS (${progress.currentFileIndex}/${progress.totalFiles})",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(rawFrac * 100).toInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    ExpressiveWaveProgressIndicator(
                        progress = rawFrac,
                        activeColor = primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        isWaveAnimated = true,
                        height = 18.dp,
                        modifier = Modifier.testTag("in_app_compression_progress_bar")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = progress.currentFileName.ifEmpty { "Working..." },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${StorageHelper.formatBytes(progress.bytesPerSecond)}/s",
                                    fontSize = 11.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = onPrimaryContainer
                                )
                            }
                        }

                        Text(
                            text = "Output: ${StorageHelper.formatBytes(progress.currentOutputBytes)}",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Compression Complete State (Auto-saved directly to the selected folder)
        if (progress.status == CompressionStatus.COMPLETED) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compression_completed_card"),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ExpressiveCloverIcon(
                        icon = Icons.Default.CheckCircle,
                        containerColor = primaryContainer,
                        iconColor = primary,
                        size = 64.dp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Archive Created Successfully!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Saved to ${progress.savedLocationLabel ?: grantedFolder?.displayName ?: "Output Folder"}",
                                fontSize = 11.sp,
                                color = onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val ratioPercent = if (progress.totalInputBytes > 0) {
                        (((progress.totalInputBytes - progress.currentOutputBytes).toFloat() / progress.totalInputBytes.toFloat()) * 100f).toInt().coerceAtLeast(0)
                    } else 0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExpressiveBentoTile(
                            label = "Input",
                            value = StorageHelper.formatBytes(progress.totalInputBytes),
                            accentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        ExpressiveBentoTile(
                            label = "Compressed",
                            value = StorageHelper.formatBytes(progress.currentOutputBytes),
                            accentColor = primary,
                            modifier = Modifier.weight(1f)
                        )
                        ExpressiveBentoTile(
                            label = "Reduced",
                            value = "$ratioPercent%",
                            accentColor = primary,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val localFile = progress.outputFilePath?.let { File(it) }?.takeIf { it.exists() }
                            val safUri = progress.outputUriString?.let { Uri.parse(it) }
                            val shareName = localFile?.name ?: settings.archiveName
                            StorageHelper.shareFileOrUri(
                                context = context,
                                file = localFile,
                                uri = safUri,
                                fileName = shareName
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("share_created_zip_button"),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Archive", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    ExpressiveMorphButton(
                        text = "Open in KaiHatch Extractor",
                        icon = Icons.Default.Archive,
                        onClick = {
                            val localFile = progress.outputFilePath?.let { File(it) }?.takeIf { it.exists() }
                            val safUri = progress.outputUriString?.let { Uri.parse(it) }
                            if (localFile != null) {
                                viewModel.selectArchive(localFile)
                                viewModel.switchTab(ScreenTab.EXTRACT)
                            } else if (safUri != null) {
                                viewModel.selectSafUriZeroCopy(safUri)
                                viewModel.switchTab(ScreenTab.EXTRACT)
                            }
                        },
                        containerColor = primaryContainer,
                        contentColor = onPrimaryContainer,
                        height = 54.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Files to Compress (M3 Expressive Stacked Group)
        ExpressiveSectionHeader(
            title = "Files to Compress",
            countText = "${itemsToCompress.size}",
            accentColor = primary,
            trailingContent = {
                if (itemsToCompress.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = primaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { addFilesPickerLauncher.launch(arrayOf("*/*")) }
                                .testTag("add_files_to_compress_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = onPrimaryContainer, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Files",
                                    fontSize = 11.sp,
                                    color = onPrimaryContainer,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = secondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { addFolderPickerLauncher.launch(null) }
                                .testTag("add_folder_to_compress_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = null, tint = onSecondaryContainer, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Folder",
                                    fontSize = 11.sp,
                                    color = onSecondaryContainer,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.clickable { viewModel.clearItemsToCompress() }
                        ) {
                            Text(
                                text = "Clear",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        )

        if (itemsToCompress.isEmpty()) {
            ExpressiveGroupedCard(
                position = ExpressiveGroupPosition.SINGLE,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ExpressiveCloverIcon(
                        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                        containerColor = primaryContainer,
                        iconColor = primary,
                        size = 50.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("No files or folders queued", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Add individual files or an entire folder from your device storage to pack.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { addFilesPickerLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = primaryContainer,
                                contentColor = onPrimaryContainer
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Files", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = { addFolderPickerLauncher.launch(null) },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = secondaryContainer,
                                contentColor = onSecondaryContainer
                            )
                        ) {
                            Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Folder", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsToCompress.forEachIndexed { idx, file ->
                    val pos = groupPositionFor(idx, itemsToCompress.size)
                    ExpressiveGroupedCard(
                        position = pos,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                        .background(if (file.isDirectory) secondaryContainer else primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (file.isDirectory) onSecondaryContainer else primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val size = fileSizesCache[file.absolutePath] ?: 0L
                                    Text(
                                        text = StorageHelper.formatBytes(size),
                                        fontSize = 11.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(onClick = { viewModel.removeItemToCompress(file) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Expressive Shape-Morphing Start Compression Button
        if (itemsToCompress.isNotEmpty() && progress.status != CompressionStatus.COMPRESSING) {
            ExpressiveMorphButton(
                text = "Create ${settings.format.label} Archive",
                subtitle = "${itemsToCompress.size} item(s) • Auto-saved to ${grantedFolder?.displayName ?: "Output Folder"}",
                icon = Icons.Default.Archive,
                onClick = { viewModel.startCompression() },
                containerColor = primary,
                contentColor = onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start_compression_button")
            )
        }

        // Extracted Folders Quick Add (M3 Expressive Stacked Group)
        if (extractedFolders.isNotEmpty()) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                ExpressiveSectionHeader(
                    title = "Re-Compress Extracted Folders",
                    countText = "${extractedFolders.size}",
                    accentColor = primary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                extractedFolders.forEachIndexed { idx, folder ->
                    val pos = groupPositionFor(idx, extractedFolders.size)
                    ExpressiveGroupedCard(
                        position = pos,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onClick = {
                            folder.localFile?.let { viewModel.addItemToCompress(it) }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = folder.name,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val folderBytes = folder.totalSizeBytes
                                    Text(
                                        text = "${StorageHelper.formatBytes(folderBytes)}${if (folder.isSaf) " • Storage Folder" else " • Tap to add"}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = primaryContainer
                            ) {
                                Text(
                                    text = "+ ADD",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
private fun ExpressivePillChip(
    label: String,
    selected: Boolean,
    activeContainer: Color,
    activeContent: Color,
    onClick: () -> Unit
) {
    val corner = if (selected) 24.dp else 14.dp
    val bg = if (selected) activeContainer else MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        shape = RoundedCornerShape(corner),
        color = bg,
        modifier = Modifier
            .clip(RoundedCornerShape(corner))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) activeContent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}
