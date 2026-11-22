package com.biplov.kaihatch.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.biplov.kaihatch.BuildConfig
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily
import com.biplov.kaihatch.updater.UpdateDialogState

private val UpdateDialogShape = RoundedCornerShape(28.dp)
private val UpdateNotesCardShape = RoundedCornerShape(18.dp)
private val UpdatePillShape = RoundedCornerShape(50)
private val UpdateButtonShape = RoundedCornerShape(18.dp)

@Composable
fun AppUpdateDialog(
    state: UpdateDialogState,
    onUpdateClick: () -> Unit,
    onSkipClick: () -> Unit,
    onCancelDownloadClick: () -> Unit,
    onRequestInstallPermissionClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state is UpdateDialogState.Hidden) return

    val view = LocalView.current
    val dismissible = state !is UpdateDialogState.Downloading

    Dialog(
        onDismissRequest = {
            if (dismissible) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 460.dp)
                .testTag("app_update_dialog"),
            shape = UpdateDialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                label = "update_dialog_content"
            ) { currentState ->
                when (currentState) {
                    is UpdateDialogState.Hidden -> Unit

                    is UpdateDialogState.Available -> {
                        UpdateAvailableContent(
                            state = currentState,
                            onUpdateClick = {
                                HapticUtil.confirm(view)
                                onUpdateClick()
                            },
                            onSkipClick = {
                                HapticUtil.performUIHaptic(view)
                                onSkipClick()
                            }
                        )
                    }

                    is UpdateDialogState.Downloading -> {
                        UpdateDownloadingContent(
                            state = currentState,
                            onCancelClick = {
                                HapticUtil.performUIHaptic(view)
                                onCancelDownloadClick()
                            }
                        )
                    }

                    is UpdateDialogState.PermissionRequired -> {
                        UpdatePermissionContent(
                            state = currentState,
                            onAllowClick = {
                                HapticUtil.confirm(view)
                                onRequestInstallPermissionClick()
                            },
                            onDismiss = {
                                HapticUtil.performUIHaptic(view)
                                onDismiss()
                            }
                        )
                    }

                    is UpdateDialogState.Error -> {
                        UpdateErrorContent(
                            state = currentState,
                            onRetryClick = {
                                HapticUtil.confirm(view)
                                onRetryClick()
                            },
                            onDismiss = {
                                HapticUtil.performUIHaptic(view)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableContent(
    state: UpdateDialogState.Available,
    onUpdateClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val release = state.release
    val notesText = release.releaseNotes.ifBlank {
        stringResource(R.string.update_dialog_no_notes)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (state.hasCachedApk) Icons.Rounded.InstallMobile else Icons.Rounded.SystemUpdate,
                contentDescription = stringResource(R.string.update_dialog_title),
                tint = onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.update_dialog_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("update_dialog_title")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Version transition pill + optional size / cached badge
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(UpdatePillShape)
                    .background(primaryContainer.copy(alpha = 0.75f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.update_dialog_version_transition,
                        BuildConfig.VERSION_NAME,
                        release.versionName
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = onPrimaryContainer,
                    modifier = Modifier.testTag("update_dialog_version_pill")
                )
            }

            if (release.apkSizeBytes > 0L) {
                Text(
                    text = StorageHelper.formatBytes(release.apkSizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = onSurfaceVariant
                )
            }
        }

        if (state.hasCachedApk) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(UpdatePillShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("update_dialog_cached_badge"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.update_dialog_cached_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Release Notes Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(UpdateNotesCardShape)
                .background(surfaceContainerLow, UpdateNotesCardShape)
                .border(1.dp, outlineVariant.copy(alpha = 0.45f), UpdateNotesCardShape)
                .padding(14.dp)
        ) {
            Text(
                text = stringResource(R.string.update_dialog_whats_new),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = notesText,
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.testTag("update_dialog_release_notes")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSkipClick,
                shape = UpdateButtonShape,
                contentPadding = PaddingValues(vertical = 13.dp, horizontal = 14.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("update_dialog_skip_button")
            ) {
                Text(
                    text = stringResource(R.string.update_action_skip),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onUpdateClick,
                shape = UpdateButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = onPrimary
                ),
                contentPadding = PaddingValues(vertical = 13.dp, horizontal = 14.dp),
                modifier = Modifier
                    .weight(1.3f)
                    .heightIn(min = 48.dp)
                    .testTag("update_dialog_update_button")
            ) {
                Icon(
                    imageVector = if (state.hasCachedApk) Icons.Rounded.InstallMobile else Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (state.hasCachedApk) R.string.update_action_install else R.string.update_action_update
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UpdateDownloadingContent(
    state: UpdateDialogState.Downloading,
    onCancelClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.update_dialog_downloading_title),
                tint = onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.update_dialog_downloading_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "v${state.release.versionName}",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = JetBrainsMonoFontFamily,
            color = primary
        )

        Spacer(modifier = Modifier.height(18.dp))

        if (state.totalBytes > 0L) {
            LinearProgressIndicator(
                progress = { state.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(UpdatePillShape)
                    .testTag("update_dialog_progress_bar"),
                strokeCap = StrokeCap.Round
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.update_dialog_downloading_progress,
                    StorageHelper.formatBytes(state.downloadedBytes),
                    StorageHelper.formatBytes(state.totalBytes)
                ),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFontFamily,
                color = onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(UpdatePillShape)
                    .testTag("update_dialog_progress_bar"),
                strokeCap = StrokeCap.Round
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_downloading_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(
            onClick = onCancelClick,
            shape = UpdateButtonShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("update_dialog_cancel_download_button")
        ) {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UpdatePermissionContent(
    state: UpdateDialogState.PermissionRequired,
    onAllowClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = stringResource(R.string.update_dialog_permission_title),
                tint = onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.update_dialog_permission_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.update_dialog_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                onClick = onDismiss,
                shape = UpdateButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("update_dialog_permission_cancel_button")
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onAllowClick,
                shape = UpdateButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = onPrimary
                ),
                modifier = Modifier
                    .weight(1.4f)
                    .heightIn(min = 48.dp)
                    .testTag("update_dialog_allow_install_button")
            ) {
                Text(
                    text = stringResource(R.string.update_action_open_settings),
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun UpdateErrorContent(
    state: UpdateDialogState.Error,
    onRetryClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val onErrorContainer = MaterialTheme.colorScheme.onErrorContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.update_dialog_error_title),
                tint = onErrorContainer,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.update_dialog_error_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = UpdateButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("update_dialog_error_close_button")
            ) {
                Text(
                    text = stringResource(R.string.action_close),
                    fontWeight = FontWeight.Bold
                )
            }

            if (state.release != null) {
                Button(
                    onClick = onRetryClick,
                    shape = UpdateButtonShape,
                    modifier = Modifier
                        .weight(1.2f)
                        .heightIn(min = 48.dp)
                        .testTag("update_dialog_retry_button")
                ) {
                    Text(
                        text = stringResource(R.string.update_action_retry),
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
