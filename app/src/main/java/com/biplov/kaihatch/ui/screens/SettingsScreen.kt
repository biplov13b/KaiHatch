package com.biplov.kaihatch.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.HatchNotificationHelper
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.model.AppAccentPalette
import com.biplov.kaihatch.model.AppLanguage
import com.biplov.kaihatch.model.AppThemeMode
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupPosition
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
import com.biplov.kaihatch.ui.components.ExpressiveMotion
import com.biplov.kaihatch.ui.components.ExpressiveSectionHeader
import com.biplov.kaihatch.ui.components.HapticUtil
import com.biplov.kaihatch.ui.components.LanguagePickerDialog
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily

private val PaletteRows = AppAccentPalette.entries.chunked(2)
private val SelectedChipShape = RoundedCornerShape(22.dp)
private val UnselectedChipShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)
private val FolderCardShape = RoundedCornerShape(16.dp)
private val FooterCardShape = RoundedCornerShape(26.dp)
private val BackButtonShape = RoundedCornerShape(16.dp)
private val AccordionExpandedShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 24.dp,
    bottomStart = 8.dp,
    bottomEnd = 8.dp
)
private val AccordionCollapsedShape = RoundedCornerShape(24.dp)
private val AccordionIconShape = RoundedCornerShape(13.dp)

@Composable
fun SettingsScreen(
    viewModel: HatchViewModel,
    topInsetPadding: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current

    val accentPalette by viewModel.accentPalette.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val isSystemLanguageSupported by viewModel.isSystemLanguageSupported.collectAsStateWithLifecycle()
    val isBlurEnabled by viewModel.isBlurEnabled.collectAsStateWithLifecycle()
    val isSwipeTabsEnabled by viewModel.isSwipeTabsEnabled.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val allFilesAccessEnabled by viewModel.allFilesAccessEnabled.collectAsStateWithLifecycle()
    val grantedFolder by viewModel.grantedFolder.collectAsStateWithLifecycle()
    val isCheckingForUpdates by viewModel.isCheckingForUpdates.collectAsStateWithLifecycle()
    val isAllFilesAccessSupported = StorageHelper.isAllFilesAccessSupportedOnDevice()

    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var permissionsExpanded by rememberSaveable { mutableStateOf(false) }
    var miscExpanded by rememberSaveable { mutableStateOf(false) }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }

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
            Toast.makeText(context, context.getString(R.string.settings_toast_notifications_enabled), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.settings_toast_notifications_declined), Toast.LENGTH_SHORT).show()
        }
    }

    val folderAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            viewModel.onFolderPermissionGranted(treeUri)
            val savedInfo = StorageHelper.parseGrantedFolderUri(treeUri)
            Toast.makeText(context, context.getString(R.string.settings_toast_folder_selected, savedInfo.displayName), Toast.LENGTH_SHORT).show()
        }
    }

    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionsState()
    }

    val openAllFilesAccessSettings = {
        HapticUtil.performUIHaptic(view)
        try {
            val intent = StorageHelper.createManageAllFilesAccessIntent(context)
            allFilesAccessLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessLauncher.launch(fallback)
            } catch (_: Exception) {
                try {
                    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    allFilesAccessLauncher.launch(appDetails)
                } catch (_: Exception) {
                }
            }
        }
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
                Toast.makeText(context, context.getString(R.string.settings_toast_notifications_open_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 10.dp, bottom = 140.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            contentDescription = stringResource(R.string.action_back),
                            tint = onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_subtitle),
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

        Column(modifier = Modifier.fillMaxWidth()) {
            ExpressiveSettingsAccordionHeader(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.settings_section_appearance),
                subtitle = stringResource(R.string.settings_section_appearance_subtitle),
                itemCount = 5,
                isExpanded = appearanceExpanded,
                onToggle = { appearanceExpanded = !appearanceExpanded },
                accentColor = primary,
                modifier = Modifier.testTag("settings_section_appearance_accordion")
            )

            AnimatedVisibility(
                visible = appearanceExpanded,
                enter = ExpressiveMotion.AccordionEnterTransition,
                exit = ExpressiveMotion.AccordionExitTransition
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {

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
                                text = stringResource(R.string.settings_theme_mode_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_theme_mode_subtitle),
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
                                    text = stringResource(mode.labelResId),
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
                                text = stringResource(R.string.settings_accent_color_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_accent_color_subtitle),
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
                                                    contentDescription = stringResource(R.string.cd_selected),
                                                    tint = onPrimary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = stringResource(palette.labelResId),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (selected) onPrimaryContainer else onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = stringResource(palette.subtitleResId),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        val effectiveAppLanguage = if (appLanguage == AppLanguage.SYSTEM && !isSystemLanguageSupported) {
            AppLanguage.ENGLISH
        } else {
            appLanguage
        }
        val languageSubtitle = when (effectiveAppLanguage) {
            AppLanguage.SYSTEM -> stringResource(R.string.language_system_default)
            else -> effectiveAppLanguage.nativeName
        }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.MIDDLE,
            containerColor = surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        HapticUtil.performUIHaptic(view)
                        showLanguagePicker = true
                    }
                    .padding(16.dp)
                    .testTag("settings_language_selector"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_language_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = onSurface
                        )
                        Text(
                            text = languageSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
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
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_blur_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_blur_subtitle),
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
                                text = stringResource(R.string.settings_swipe_tabs_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_swipe_tabs_subtitle),
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
            }
        }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            ExpressiveSettingsAccordionHeader(
                icon = Icons.Default.Security,
                title = stringResource(R.string.settings_section_permissions),
                subtitle = stringResource(R.string.settings_section_permissions_subtitle),
                itemCount = if (isAllFilesAccessSupported) 3 else 2,
                isExpanded = permissionsExpanded,
                onToggle = { permissionsExpanded = !permissionsExpanded },
                accentColor = primary,
                modifier = Modifier.testTag("settings_section_permissions_accordion")
            )

            AnimatedVisibility(
                visible = permissionsExpanded,
                enter = ExpressiveMotion.AccordionEnterTransition,
                exit = ExpressiveMotion.AccordionExitTransition
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                                text = stringResource(R.string.settings_notifications_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = if (notificationsEnabled)
                                    stringResource(R.string.settings_notifications_enabled_subtitle)
                                else
                                    stringResource(R.string.settings_notifications_disabled_subtitle),
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
                                contentDescription = stringResource(R.string.settings_notifications_enabled_cd),
                                tint = primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.settings_notifications_open_cd),
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
                            Text(stringResource(R.string.settings_notifications_allow), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }

        if (isAllFilesAccessSupported) {
            ExpressiveGroupedCard(
                position = ExpressiveGroupPosition.MIDDLE,
                containerColor = surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openAllFilesAccessSettings() }
                        .padding(16.dp)
                        .testTag("settings_all_files_access_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_all_files_access_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = if (allFilesAccessEnabled) {
                                    stringResource(R.string.settings_all_files_access_enabled_subtitle)
                                } else {
                                    stringResource(R.string.settings_all_files_access_disabled_subtitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    if (allFilesAccessEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.settings_all_files_access_title),
                                tint = primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = { openAllFilesAccessSettings() },
                            shape = PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = onPrimary
                            ),
                            modifier = Modifier.testTag("settings_grant_all_files_access_button")
                        ) {
                            Text(
                                text = stringResource(R.string.settings_all_files_access_allow),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        ExpressiveGroupedCard(
            position = ExpressiveGroupPosition.BOTTOM,
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
                                    text = stringResource(R.string.settings_folder_access_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = onSurface
                                )
                                Text(
                                    text = if (activeFolder != null) {
                                        stringResource(R.string.settings_folder_access_active_subtitle)
                                    } else {
                                        stringResource(R.string.settings_folder_access_inactive_subtitle)
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
                                    text = stringResource(R.string.settings_folder_active_badge),
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
                                text = stringResource(R.string.settings_folder_selected_label),
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
                                    text = stringResource(R.string.settings_folder_change),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            Button(
                                onClick = {
                                    HapticUtil.performUIHaptic(view)
                                    viewModel.removeGrantedFolder()
                                    Toast.makeText(context, context.getString(R.string.settings_toast_folder_removed), Toast.LENGTH_SHORT).show()
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
                                    text = stringResource(R.string.settings_folder_remove),
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
                                text = stringResource(R.string.settings_folder_grant),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
            }
        }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            ExpressiveSettingsAccordionHeader(
                icon = Icons.Default.Science,
                title = stringResource(R.string.settings_section_unpacking),
                subtitle = stringResource(R.string.settings_section_unpacking_subtitle),
                itemCount = 3,
                isExpanded = miscExpanded,
                onToggle = { miscExpanded = !miscExpanded },
                accentColor = primary,
                modifier = Modifier.testTag("settings_section_misc_accordion")
            )

            AnimatedVisibility(
                visible = miscExpanded,
                enter = ExpressiveMotion.AccordionEnterTransition,
                exit = ExpressiveMotion.AccordionExitTransition
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                            imageVector = Icons.Default.Science,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_spacelab_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_spacelab_subtitle),
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
                        Text(stringResource(R.string.settings_spacelab_open_button), style = MaterialTheme.typography.labelLarge)
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
                                text = stringResource(R.string.settings_temp_cache_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_temp_cache_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.clearTempCache {
                                Toast.makeText(context, context.getString(R.string.settings_toast_temp_cache_cleared), Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = surfaceContainerHigh,
                            contentColor = onSurface
                        ),
                        modifier = Modifier.testTag("clear_temp_cache_button")
                    ) {
                        Text(stringResource(R.string.settings_temp_cache_clean_button), style = MaterialTheme.typography.labelLarge)
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
                                text = stringResource(R.string.settings_reset_history_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_reset_history_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.historyRepo.clearHistory()
                            Toast.makeText(context, context.getString(R.string.settings_toast_history_cleared), Toast.LENGTH_SHORT).show()
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.testTag("settings_clear_history_button")
                    ) {
                        Text(stringResource(R.string.settings_reset_history_button), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            }
        }
        }

        Column {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsAboutCard(
                isDark = isDark,
                isCheckingForUpdates = isCheckingForUpdates,
                onCheckForUpdates = { viewModel.checkForUpdatesManually() }
            )
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            selectedLanguage = appLanguage,
            onLanguageSelected = { language ->
                viewModel.setAppLanguage(language)
            },
            onDismiss = { showLanguagePicker = false },
            isSystemLanguageSupported = isSystemLanguageSupported
        )
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

@Composable
private fun ExpressiveSettingsAccordionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    itemCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val chevronRotationState = animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = ExpressiveMotion.AccordionFloatSpring,
        label = "accordion_chevron_rotation"
    )

    val shape = if (isExpanded) AccordionExpandedShape else AccordionCollapsedShape

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                role = Role.Button,
                onClick = {
                    HapticUtil.performUIHaptic(view)
                    onToggle()
                }
            ),
        shape = shape,
        color = surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (isExpanded) accentColor.copy(alpha = 0.45f) else outlineVariant.copy(alpha = 0.28f)
        )
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
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(AccordionIconShape)
                        .background(
                            if (isExpanded) accentColor.copy(alpha = 0.20f) else primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isExpanded) accentColor else onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(13.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isExpanded) accentColor.copy(alpha = 0.16f) else outlineVariant.copy(alpha = 0.15f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isExpanded) accentColor.copy(alpha = 0.35f) else outlineVariant.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$itemCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isExpanded) accentColor else onSurfaceVariant,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isExpanded) accentColor.copy(alpha = 0.14f) else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = if (isExpanded) accentColor else onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = chevronRotationState.value }
                    )
                }
            }
        }
    }
}
