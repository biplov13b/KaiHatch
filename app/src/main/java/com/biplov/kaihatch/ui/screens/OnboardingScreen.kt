package com.biplov.kaihatch.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.biplov.kaihatch.data.GrantedFolderInfo
import com.biplov.kaihatch.model.AppThemeMode
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily

private const val TOTAL_ONBOARDING_STEPS = 4

@Composable
fun OnboardingScreen(
    viewModel: HatchViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val grantedFolder by viewModel.grantedFolder.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var currentStep by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = currentStep > 0) {
        currentStep = (currentStep - 1).coerceAtLeast(0)
    }

    // Keep notification and folder permission state synced when returning from system dialogs/settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissionsState()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFolderPermissionGranted(uri)
        } else {
            viewModel.onFolderPickerCancelled()
        }
    }

    val topBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_root"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .padding(
                        top = topBarInset + 12.dp,
                        bottom = bottomBarInset + 16.dp,
                        start = 20.dp,
                        end = 20.dp
                    ),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar: Step Indicator + Visible Corner Skip Option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("onboarding_step_indicators")
                    ) {
                        for (index in 0 until TOTAL_ONBOARDING_STEPS) {
                            val isActive = index == currentStep
                            val isCompleted = index < currentStep
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(if (isActive) 28.dp else 10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isActive -> primary
                                            isCompleted -> primary.copy(alpha = 0.45f)
                                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                        }
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${currentStep + 1} / $TOTAL_ONBOARDING_STEPS",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = { viewModel.skipOnboarding() },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("onboarding_skip_button")
                    ) {
                        Text(
                            text = "Skip",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Main Scrollable Step Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
                        },
                        label = "onboarding_step_transition"
                    ) { step ->
                        val stepScrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(stepScrollState),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            when (step) {
                                0 -> OnboardingWelcomeStep()
                                1 -> OnboardingWhatIsKaiHatchStep()
                                2 -> OnboardingPermissionsAndDestinationStep(
                                    notificationsEnabled = notificationsEnabled,
                                    grantedFolder = grantedFolder,
                                    onRequestNotifications = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            try {
                                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                try {
                                                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                        data = Uri.fromParts("package", context.packageName, null)
                                                    }
                                                    context.startActivity(fallback)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    },
                                    onSelectFolder = {
                                        folderPickerLauncher.launch(null)
                                    }
                                )
                                3 -> OnboardingAppearanceStep(
                                    selectedThemeMode = themeMode,
                                    onSelectThemeMode = { mode ->
                                        viewModel.setThemeMode(mode)
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom Navigation Controls: Back + Next / Get Started
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(
                            onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("onboarding_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Back",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (currentStep < TOTAL_ONBOARDING_STEPS - 1) {
                        Button(
                            onClick = { currentStep = (currentStep + 1).coerceAtMost(TOTAL_ONBOARDING_STEPS - 1) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("onboarding_next_button")
                        ) {
                            Text(
                                text = "Next",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = { viewModel.completeOnboarding() },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("onboarding_get_started_button")
                        ) {
                            Text(
                                text = "Get Started",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Get Started",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingWelcomeStep() {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_step_welcome"),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ExpressiveCloverIcon(
            icon = Icons.Default.Bolt,
            containerColor = primaryContainer,
            iconColor = primary,
            size = 64.dp
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Welcome to KaiHatch",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Extract archives without needing twice the storage. KaiHatch makes room as it goes, helping you unpack files even when storage is limited.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OnboardingFeatureRow(
                    icon = Icons.Default.Bolt,
                    title = "Progressive In-Place Unpacking",
                    subtitle = "Reclaims space from the archive while files are extracted."
                )
                OnboardingFeatureRow(
                    icon = Icons.Default.Storage,
                    title = "Built for Local Device Storage",
                    subtitle = "Works directly with local archives without full temporary copies."
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = primaryContainer.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Supports ZIP, ZIP64, APK, TAR, 7Z & RAR archives",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingWhatIsKaiHatchStep() {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_step_what_is_kaihatch"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ExpressiveCloverIcon(
            icon = Icons.Default.Storage,
            containerColor = primaryContainer,
            iconColor = primary,
            size = 56.dp
        )

        Text(
            text = "What's KaiHatch?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "KaiHatch is an Android archive extractor built around genuine in-place extraction.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 21.sp
                )
                Text(
                    text = "Unlike conventional extractors that keep the original archive intact while creating extracted files, Hatch Mode progressively reuses space from the archive itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )
                Text(
                    text = "This means you can extract supported archives with significantly less additional free storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )
                Text(
                    text = "You can also use Standard Mode for conventional extraction.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )
            }
        }

        // Visible Destructive Operation Note
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_hatch_mode_warning"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Important warning",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Important: Hatch Mode modifies the original archive. Interrupting extraction may leave it unrecoverable.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun OnboardingPermissionsAndDestinationStep(
    notificationsEnabled: Boolean,
    grantedFolder: GrantedFolderInfo?,
    onRequestNotifications: () -> Unit,
    onSelectFolder: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_step_permissions"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ExpressiveCloverIcon(
            icon = Icons.Default.FolderSpecial,
            containerColor = primaryContainer,
            iconColor = primary,
            size = 56.dp
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Permissions & Destination",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Configure optional notifications and choose where extracted files should be saved. You can change these anytime in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        // Section A: Notifications
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_notifications_card"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (notificationsEnabled) primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (notificationsEnabled) {
                                    Icons.Default.NotificationsActive
                                } else {
                                    Icons.Default.Notifications
                                },
                                contentDescription = null,
                                tint = if (notificationsEnabled) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Extraction Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (notificationsEnabled) {
                                    "Enabled • Progress & completion alerts active"
                                } else {
                                    "Show extraction progress and completion notifications"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notificationsEnabled) primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (notificationsEnabled) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Permission Granted",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = onPrimaryContainer
                            )
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRequestNotifications,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = primaryContainer,
                            contentColor = onPrimaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("onboarding_request_notification_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Allow Notifications",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Section B: Extraction Destination
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_destination_card"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (grantedFolder != null) primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (grantedFolder != null) Icons.Default.FolderSpecial else Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = if (grantedFolder != null) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Extraction Destination",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = grantedFolder?.readablePath
                                ?: "Default destination (or choose a preferred folder such as Downloads)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (grantedFolder != null) primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onSelectFolder,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (grantedFolder != null) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            primaryContainer
                        },
                        contentColor = if (grantedFolder != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            onPrimaryContainer
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("onboarding_select_destination_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (grantedFolder != null) {
                            "Change Destination (${grantedFolder.displayName})"
                        } else {
                            "Choose Extraction Folder"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingAppearanceStep(
    selectedThemeMode: AppThemeMode,
    onSelectThemeMode: (AppThemeMode) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val themeOptions = listOf(
        Triple(
            AppThemeMode.LIGHT,
            "Light",
            "Warm, balanced surfaces with crisp contrast" to Icons.Default.LightMode
        ),
        Triple(
            AppThemeMode.DARK,
            "Dark",
            "Deep slate surfaces designed for low-light comfort" to Icons.Default.DarkMode
        ),
        Triple(
            AppThemeMode.SYSTEM,
            "System default",
            "Automatically follows your device appearance setting" to Icons.Default.SettingsBrightness
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_step_appearance"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ExpressiveCloverIcon(
            icon = Icons.Default.Palette,
            containerColor = primaryContainer,
            iconColor = primary,
            size = 56.dp
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Make it yours",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Choose how KaiHatch looks. Your selection applies immediately and is saved to Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            themeOptions.forEach { (mode, displayTitle, subtitleAndIcon) ->
                val (subtitle, icon) = subtitleAndIcon
                val isSelected = selectedThemeMode == mode
                val cardBg = if (isSelected) {
                    primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
                val borderColor = if (isSelected) {
                    primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(22.dp)
                        )
                        .clickable { onSelectThemeMode(mode) }
                        .testTag("onboarding_theme_${mode.name.lowercase()}"),
                    shape = RoundedCornerShape(22.dp),
                    color = cardBg
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) primary.copy(alpha = 0.18f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = displayTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
