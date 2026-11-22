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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.GrantedFolderInfo
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.ArchiveFormat
import com.biplov.kaihatch.model.CompressionProgress
import com.biplov.kaihatch.model.CompressionSettings
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
import com.biplov.kaihatch.ui.components.ExpressiveShapes
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

    val itemsToCompress by viewModel.itemsToCompress.collectAsStateWithLifecycle()
    val settings by viewModel.compressionSettings.collectAsStateWithLifecycle()
    val progressState = viewModel.compressionProgress.collectAsStateWithLifecycle()
    val extractedFolders by viewModel.extractedFolders.collectAsStateWithLifecycle()
    val fileSizesCache by viewModel.fileSizesCache.collectAsStateWithLifecycle()
    val grantedFolder by viewModel.grantedFolder.collectAsStateWithLifecycle()
    val showPersistentFolderPrompt by viewModel.showPersistentFolderPrompt.collectAsStateWithLifecycle()

    val compressionStatus by remember {
        derivedStateOf { progressState.value.status }
    }

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
                    text = stringResource(R.string.dialog_output_folder_title),
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_output_folder_compress_body)
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
                    Text(stringResource(R.string.dialog_output_folder_compress_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissPersistentFolderPrompt() }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = topInsetPadding + 8.dp, bottom = 244.dp)
    ) {
        item(key = "compress_top_controls", contentType = "compress_top_controls") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            ExpressiveCleanHeader(
                badgeText = stringResource(R.string.compress_header_badge),
                title = stringResource(R.string.compress_header_title),
                subtitle = stringResource(R.string.compress_header_subtitle)
            )
        }

        // Archive Configuration Expressive Bento Surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("compression_settings_card"),
            shape = ExpressiveShapes.HeroCard,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.compress_container_format_label),
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
                    ArchiveFormat.ZIP -> stringResource(R.string.compress_format_desc_zip)
                    ArchiveFormat.SEVEN_Z -> stringResource(R.string.compress_format_desc_7z)
                    ArchiveFormat.RAR -> stringResource(R.string.compress_format_desc_rar)
                    ArchiveFormat.TAR -> stringResource(R.string.compress_format_desc_tar)
                    ArchiveFormat.TAR_GZ -> stringResource(R.string.compress_format_desc_tar_gz)
                    else -> stringResource(R.string.compress_format_desc_default)
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
                    label = { Text(stringResource(R.string.compress_output_name_label)) },
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
                            text = stringResource(R.string.compress_level_label),
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
                                        text = if (settings.password.isNotEmpty()) {
                                            stringResource(R.string.extract_password_set)
                                        } else {
                                            stringResource(R.string.extract_password_label)
                                        },
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
                        0 to stringResource(R.string.compress_level_store),
                        1 to stringResource(R.string.compress_level_fast),
                        6 to stringResource(R.string.compress_level_balanced),
                        9 to stringResource(R.string.compress_level_ultra)
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
                        label = { Text(stringResource(R.string.compress_password_optional_label)) },
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
                            text = if (grantedFolder != null) {
                                stringResource(R.string.compress_save_to_format, grantedFolder!!.displayName)
                            } else {
                                stringResource(R.string.compress_output_folder_not_selected)
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (grantedFolder != null) {
                                stringResource(R.string.compress_save_to_subtitle_granted)
                            } else {
                                stringResource(R.string.compress_save_to_subtitle_ungranted)
                            },
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
                        text = if (grantedFolder != null) {
                            stringResource(R.string.extract_destination_change)
                        } else {
                            stringResource(R.string.extract_destination_select_folder)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Active Compression Wavy In-App Progress Card
        if (compressionStatus == CompressionStatus.COMPRESSING || compressionStatus == CompressionStatus.PREPARING) {
            ActiveCompressionProgressCard(
                progressState = progressState,
                settings = settings,
                primary = primary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer
            )
        }

        // Compression Complete State (Auto-saved directly to the selected folder)
        if (compressionStatus == CompressionStatus.COMPLETED) {
            CompletedCompressionCard(
                progressState = progressState,
                settings = settings,
                grantedFolder = grantedFolder,
                primary = primary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                onOpenInExtractor = { localFile, safUri ->
                    if (localFile != null) {
                        viewModel.selectArchive(localFile)
                        viewModel.switchTab(ScreenTab.EXTRACT)
                    } else if (safUri != null) {
                        viewModel.selectSafUriZeroCopy(safUri)
                        viewModel.switchTab(ScreenTab.EXTRACT)
                    }
                }
            )
        }

        // Files to Compress (M3 Expressive Stacked Group)
        ExpressiveSectionHeader(
            title = stringResource(R.string.compress_files_section_title),
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
                                    text = stringResource(R.string.compress_add_files_chip),
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
                                    text = stringResource(R.string.compress_add_folder_chip),
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
                                text = stringResource(R.string.compress_clear_chip),
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
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        if (itemsToCompress.isEmpty()) {
            item(key = "compress_empty_queue", contentType = "compress_empty_queue") {
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
                        Text(
                            text = stringResource(R.string.compress_empty_queue_title),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.compress_empty_queue_subtitle),
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
                                shape = ExpressiveShapes.SubItem,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = primaryContainer,
                                    contentColor = onPrimaryContainer
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.compress_add_files_button),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            FilledTonalButton(
                                onClick = { addFolderPickerLauncher.launch(null) },
                                shape = ExpressiveShapes.SubItem,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = secondaryContainer,
                                    contentColor = onSecondaryContainer
                                )
                            ) {
                                Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.compress_add_folder_button),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                items = itemsToCompress,
                key = { _, file -> "queue_${file.absolutePath}" },
                contentType = { _, _ -> "compress_queue_item" }
            ) { idx, file ->
                val pos = groupPositionFor(idx, itemsToCompress.size)
                val isDir = remember(file.absolutePath) { file.isDirectory }
                val size = fileSizesCache[file.absolutePath] ?: 0L
                val formattedSize = remember(size) { StorageHelper.formatBytes(size) }

                ExpressiveGroupedCard(
                    position = pos,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.padding(bottom = 4.dp)
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
                                    .clip(ExpressiveShapes.Small)
                                    .background(if (isDir) secondaryContainer else primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (isDir) onSecondaryContainer else primary,
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
                                Text(
                                    text = formattedSize,
                                    fontSize = 11.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(onClick = { viewModel.removeItemToCompress(file) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.compress_remove_item_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Expressive Shape-Morphing Start Compression Button
        if (itemsToCompress.isNotEmpty() && compressionStatus != CompressionStatus.COMPRESSING) {
            item(key = "compress_start_action", contentType = "compress_start_action") {
                val defaultOutFolder = stringResource(R.string.compress_completed_default_folder)
                ExpressiveMorphButton(
                    text = stringResource(
                        R.string.compress_create_archive_button,
                        stringResource(settings.format.labelResId)
                    ),
                    subtitle = stringResource(
                        R.string.compress_create_archive_subtitle,
                        itemsToCompress.size,
                        grantedFolder?.displayName ?: defaultOutFolder
                    ),
                    icon = Icons.Default.Archive,
                    onClick = { viewModel.startCompression() },
                    containerColor = primary,
                    contentColor = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag("start_compression_button")
                )
            }
        }

        // Extracted Folders Quick Add (M3 Expressive Stacked Group)
        if (extractedFolders.isNotEmpty()) {
            item(key = "compress_recompress_header", contentType = "section_header") {
                Column(modifier = Modifier.padding(top = 20.dp, bottom = 14.dp)) {
                    ExpressiveSectionHeader(
                        title = stringResource(R.string.compress_recompress_section_title),
                        countText = "${extractedFolders.size}",
                        accentColor = primary
                    )
                }
            }

            itemsIndexed(
                items = extractedFolders,
                key = { _, folder -> "recompress_${folder.id}" },
                contentType = { _, _ -> "recompress_folder_item" }
            ) { idx, folder ->
                val pos = groupPositionFor(idx, extractedFolders.size)
                val formattedFolderBytes = remember(folder.totalSizeBytes) {
                    StorageHelper.formatBytes(folder.totalSizeBytes)
                }

                ExpressiveGroupedCard(
                    position = pos,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = {
                        folder.localFile?.let { viewModel.addItemToCompress(it) }
                    },
                    modifier = Modifier.padding(bottom = 4.dp)
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
                                Text(
                                    text = if (folder.isSaf) {
                                        stringResource(R.string.compress_folder_meta_saf, formattedFolderBytes)
                                    } else {
                                        stringResource(R.string.compress_folder_meta_local, formattedFolderBytes)
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = primaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.compress_add_badge),
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
}

@Composable
private fun ActiveCompressionProgressCard(
    progressState: State<CompressionProgress>,
    settings: CompressionSettings,
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color
) {
    val progress = progressState.value
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
                        text = stringResource(
                            R.string.compress_packing_title,
                            stringResource(settings.format.labelResId)
                        ),
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
                    text = stringResource(
                        R.string.compress_overall_progress_format,
                        progress.currentFileIndex,
                        progress.totalFiles
                    ),
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

            val workingFallback = stringResource(R.string.compress_working)
            Text(
                text = progress.currentFileName.ifEmpty { workingFallback },
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
                    text = stringResource(
                        R.string.compress_output_bytes_format,
                        StorageHelper.formatBytes(progress.currentOutputBytes)
                    ),
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CompletedCompressionCard(
    progressState: State<CompressionProgress>,
    settings: CompressionSettings,
    grantedFolder: GrantedFolderInfo?,
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    onOpenInExtractor: (File?, Uri?) -> Unit
) {
    val context = LocalContext.current
    val progress = progressState.value

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
                text = stringResource(R.string.compress_completed_title),
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
                    val defaultFolderLabel = stringResource(R.string.compress_completed_default_folder)
                    Text(
                        text = stringResource(
                            R.string.compress_completed_saved_to,
                            progress.savedLocationLabel ?: grantedFolder?.displayName ?: defaultFolderLabel
                        ),
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
                    label = stringResource(R.string.compress_bento_input),
                    value = StorageHelper.formatBytes(progress.totalInputBytes),
                    accentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveBentoTile(
                    label = stringResource(R.string.compress_bento_compressed),
                    value = StorageHelper.formatBytes(progress.currentOutputBytes),
                    accentColor = primary,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveBentoTile(
                    label = stringResource(R.string.compress_bento_reduced),
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
                Text(
                    text = stringResource(R.string.compress_share_archive),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            ExpressiveMorphButton(
                text = stringResource(R.string.compress_open_in_extractor),
                icon = Icons.Default.Archive,
                onClick = {
                    val localFile = progress.outputFilePath?.let { File(it) }?.takeIf { it.exists() }
                    val safUri = progress.outputUriString?.let { Uri.parse(it) }
                    onOpenInExtractor(localFile, safUri)
                },
                containerColor = primaryContainer,
                contentColor = onPrimaryContainer,
                height = 54.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
