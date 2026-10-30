package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.HatchNotificationHelper
import com.example.data.StorageHelper
import com.example.model.AppAccentPalette
import com.example.model.AppThemeMode
import com.example.ui.HatchViewModel
import com.example.ui.components.ExpressiveCloverIcon
import com.example.ui.components.ExpressiveGroupPosition
import com.example.ui.components.ExpressiveGroupedCard
import com.example.ui.components.ExpressiveSectionHeader
import com.example.ui.components.HapticUtil
import com.example.ui.theme.JetBrainsMonoFontFamily

private val PaletteRows = AppAccentPalette.entries.chunked(2)
private val SelectedChipShape = RoundedCornerShape(22.dp)
private val UnselectedChipShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)
private val FolderCardShape = RoundedCornerShape(16.dp)
private val FooterCardShape = RoundedCornerShape(26.dp)
private val BackButtonShape = RoundedCornerShape(16.dp)

@Composable
fun SettingsScreen(
    viewModel: HatchViewModel,
    topInsetPadding: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current

    val accentPalette by viewModel.accentPalette.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val isHatchMode by viewModel.isHatchMode.collectAsState()
    val isBlurEnabled by viewModel.isBlurEnabled.collectAsState()
    val isSwipeTabsEnabled by viewModel.isSwipeTabsEnabled.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val grantedFolder by viewModel.grantedFolder.collectAsState()

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }

    val dynamicPrimarySwatch = remember(isDark, context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Color(
                context.getColor(
                    if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
                )
            )
        } else {
            if (isDark) Color(0xFF5EEAD4) else Color(0xFF0D9488)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshPermissionsState()
        if (isGranted) {
            HatchNotificationHelper.ensureChannels(context)
            Toast.makeText(context, "Notifications enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification permission declined", Toast.LENGTH_SHORT).show()
        }
    }

    val folderAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            viewModel.onFolderPermissionGranted(treeUri)
            val savedInfo = StorageHelper.parseGrantedFolderUri(treeUri)
            Toast.makeText(context, "Folder selected: ${savedInfo.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 10.dp, bottom = 140.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            viewModel.closeSettings()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(BackButtonShape)
                            .background(primaryContainer, BackButtonShape)
                            .border(
                                width = 1.dp,
                                color = primary.copy(alpha = 0.32f),
                                shape = BackButtonShape
                            )
                            .testTag("close_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Customize your theme, accent colors, storage access, and unpacking defaults",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                ExpressiveCloverIcon(
                    icon = Icons.Default.Palette,
                    containerColor = primaryContainer,
                    iconColor = primary,
                    size = 44.dp
                )
            }

        Column {
            Spacer(modifier = Modifier.height(8.dp))
            ExpressiveSectionHeader(
                title = "Appearance & Theme",
                accentColor = primary
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.TOP,
            containerColor = surfaceContainer
        ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Theme Mode",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Switch between dark, light, or automatic system appearance",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            val selected = themeMode == mode
                            val shape = if (selected) SelectedChipShape else UnselectedChipShape
                            val bg = if (selected) primary else surfaceContainerLow

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(bg, shape)
                                    .clickable {
                                        HapticUtil.performUIHaptic(view)
                                        viewModel.setThemeMode(mode)
                                    }
                                    .padding(vertical = 12.dp)
                                    .testTag("theme_mode_${mode.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) onPrimary else onSurface
                                )
                            }
                        }
                    }
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.MIDDLE,
            containerColor = surfaceContainer
        ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Accent Color",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Pick an expressive accent color shared across every screen",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaletteRows.forEach { rowPalettes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowPalettes.forEach { palette ->
                                    val selected = accentPalette == palette
                                    val swatchColor = swatchColorFor(palette, isDark, dynamicPrimarySwatch)
                                    val shape = if (selected) SelectedChipShape else UnselectedChipShape
                                    val bg = if (selected) primaryContainer else surfaceContainerLow

                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(shape)
                                            .background(bg, shape)
                                            .clickable {
                                                HapticUtil.performUIHaptic(view)
                                                viewModel.setAccentPalette(palette)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 11.dp)
                                            .testTag("accent_option_${palette.name.lowercase()}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(swatchColor, CircleShape)
                                                .border(1.5.dp, surfaceContainerHigh, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = onPrimary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = palette.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (selected) onPrimaryContainer else onSurface,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = palette.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.MIDDLE,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Progressive Bottom & Top Blur",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Smoothly blurs content scrolling behind the floating navigation bar and status bar",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isBlurEnabled,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setBlurEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = onPrimary,
                            checkedTrackColor = primary
                        ),
                        modifier = Modifier.testTag("settings_blur_switch")
                    )
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.BOTTOM,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Swipe,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Swipe Between Tabs",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Allow fluid horizontal swiping across all 5 main workspace tabs",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isSwipeTabsEnabled,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setSwipeTabsEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = onPrimary,
                            checkedTrackColor = primary
                        ),
                        modifier = Modifier.testTag("settings_swipe_tabs_switch")
                    )
                }
            }

        Column {
            Spacer(modifier = Modifier.height(8.dp))
            ExpressiveSectionHeader(
                title = "Permissions & System Access",
                accentColor = primary
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        val openSystemNotificationSettings = {
            HapticUtil.performUIHaptic(view)
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open notification settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.TOP,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (notificationsEnabled) {
                                openSystemNotificationSettings()
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                HapticUtil.performUIHaptic(view)
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openSystemNotificationSettings()
                            }
                        }
                        .padding(16.dp)
                        .testTag("settings_notifications_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (notificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = if (notificationsEnabled)
                                    "Active • Tap to manage in system notification settings"
                                else
                                    "Allow notifications for extraction and compression tasks",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    if (notificationsEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Notifications Enabled",
                                tint = primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Open Notification Settings",
                                tint = onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    HapticUtil.performUIHaptic(view)
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openSystemNotificationSettings()
                                }
                            },
                            shape = PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = onPrimary
                            ),
                            modifier = Modifier.testTag("settings_grant_notifications_button")
                        ) {
                            Text("Allow", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.MIDDLE,
            containerColor = surfaceContainer
        ) {
                val activeFolder = grantedFolder
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
                            Icon(
                                imageVector = Icons.Default.FolderShared,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Persistent Folder Access",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = onSurface
                                )
                                Text(
                                    text = if (activeFolder != null) {
                                        "Zero-copy folder permission is active and saved across app restarts."
                                    } else {
                                        "Grant a folder once for zero-copy unpacking of 4GB+ ZIP, APK, and .7z archives."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = onSurfaceVariant
                                )
                            }
                        }

                        if (activeFolder != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(
                                modifier = Modifier
                                    .background(primaryContainer, PillShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "ACTIVE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = onPrimaryContainer,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (activeFolder != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(surfaceContainerHigh, FolderCardShape)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("settings_granted_folder_card")
                        ) {
                            Text(
                                text = "SELECTED FOLDER",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primary,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = activeFolder.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = onSurface,
                                modifier = Modifier.testTag("settings_granted_folder_name")
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeFolder.readablePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant,
                                fontFamily = JetBrainsMonoFontFamily,
                                modifier = Modifier.testTag("settings_granted_folder_path")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    HapticUtil.performUIHaptic(view)
                                    folderAccessLauncher.launch(activeFolder.uri)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_grant_saf_folder_button"),
                                shape = PillShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = secondaryContainer,
                                    contentColor = onSecondaryContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderShared,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Change Folder",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            Button(
                                onClick = {
                                    HapticUtil.performUIHaptic(view)
                                    viewModel.removeGrantedFolder()
                                    Toast.makeText(context, "Folder permission removed", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .testTag("settings_remove_saf_folder_button"),
                                shape = PillShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Remove",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                HapticUtil.performUIHaptic(view)
                                folderAccessLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_grant_saf_folder_button"),
                            shape = PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = secondaryContainer,
                                contentColor = onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderShared,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Grant Folder Access",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.BOTTOM,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Default Archive App",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Set KaiHatch to open .zip, .tar, and .gz files by default",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Unable to open system settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = surfaceContainerHigh,
                            contentColor = onSurface
                        ),
                        modifier = Modifier.testTag("settings_open_system_app_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("System", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

        Column {
            Spacer(modifier = Modifier.height(8.dp))
            ExpressiveSectionHeader(
                title = "Unpacking & Storage",
                accentColor = primary
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.TOP,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Smart In-Place Unpacking",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Reclaims storage space as each file is extracted",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isHatchMode,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setHatchMode(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = onPrimary,
                            checkedTrackColor = primary
                        ),
                        modifier = Modifier.testTag("settings_hatch_mode_switch")
                    )
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.MIDDLE,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Space Lab & Synthetic Benchmark",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Generate synthetic archives and benchmark zero-copy ftruncate()",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.openSpaceLabFromSettings()
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryContainer,
                            contentColor = onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("settings_open_space_lab_button")
                    ) {
                        Text("Open Lab", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.MIDDLE,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Temporary Preview Cache",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Clear temporary files created when previewing inside archives",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.clearTempCache {
                                Toast.makeText(context, "Temporary preview cache cleared", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = surfaceContainerHigh,
                            contentColor = onSurface
                        ),
                        modifier = Modifier.testTag("clear_temp_cache_button")
                    ) {
                        Text("Clean", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.BOTTOM,
            containerColor = surfaceContainer
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Reset Activity & Savings",
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = "Clear past unpacking logs and reset the saved storage counter",
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.historyRepo.clearHistory()
                            Toast.makeText(context, "Operation history cleared", Toast.LENGTH_SHORT).show()
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.testTag("settings_clear_history_button")
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

        Column {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsAboutCard(isDark = isDark)
        }
    }
}

private fun swatchColorFor(
    palette: AppAccentPalette,
    isDark: Boolean,
    dynamicPrimary: Color
): Color = when (palette) {
    AppAccentPalette.DYNAMIC -> dynamicPrimary
    AppAccentPalette.EMERALD -> if (isDark) Color(0xFF47E695) else Color(0xFF059669)
    AppAccentPalette.OCEAN -> if (isDark) Color(0xFF7DD3FC) else Color(0xFF0284C7)
    AppAccentPalette.VIOLET -> if (isDark) Color(0xFFD8B4FE) else Color(0xFF7C3AED)
    AppAccentPalette.CORAL -> if (isDark) Color(0xFFFDA4AF) else Color(0xFFE11D48)
    AppAccentPalette.AMBER -> if (isDark) Color(0xFFFDE047) else Color(0xFFD97706)
}
