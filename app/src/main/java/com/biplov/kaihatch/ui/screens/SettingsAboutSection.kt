package com.biplov.kaihatch.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.biplov.kaihatch.BuildConfig
import com.biplov.kaihatch.R
import com.biplov.kaihatch.ui.components.HapticUtil
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily

internal const val ABOUT_WEBSITE_URL = "https://github.com/biplov13b/KaiHatch"
internal const val ABOUT_EMAIL_ADDRESS = "biplovbiswas13@gmail.com"
internal const val ABOUT_TELEGRAM_URL = "https://t.me/KaiHatch"
internal const val ABOUT_KOFI_URL = "https://ko-fi.com/biplov13b"
internal const val APACHE_2_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
internal const val HATCHZIP_INSPIRATION_URL = "https://github.com/Fabiannez/HatchZip"

private val AboutCardOuterShape = RoundedCornerShape(28.dp)
private val AboutAppIconShape = RoundedCornerShape(22.dp)
private val ContactButtonShape = RoundedCornerShape(18.dp)
private val SupportButtonShape = RoundedCornerShape(18.dp)
private val LicensesRowShape = RoundedCornerShape(18.dp)
private val LicenseItemCardShape = RoundedCornerShape(20.dp)

internal fun createExternalUrlIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

internal fun createEmailContactIntent(
    emailAddress: String,
    subject: String = "KaiHatch Feedback"
): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$emailAddress")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

internal fun openExternalUrl(context: Context, url: String): Boolean {
    return try {
        context.startActivity(createExternalUrlIntent(url))
        true
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.about_toast_open_link_error), Toast.LENGTH_SHORT).show()
        false
    }
}

internal fun openEmailContact(context: Context, emailAddress: String): Boolean {
    val subject = context.getString(R.string.about_email_subject)
    return try {
        context.startActivity(createEmailContactIntent(emailAddress, subject))
        true
    } catch (_: Exception) {
        try {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(fallback, context.getString(R.string.about_email_chooser_title)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.about_toast_no_email_app, emailAddress), Toast.LENGTH_SHORT).show()
            false
        }
    }
}

internal data class ThirdPartyLicenseNotice(
    val name: String,
    val coordinates: String,
    val version: String,
    val license: String,
    val copyrightHolder: String,
    val scope: String
)

internal val VERIFIED_THIRD_PARTY_NOTICES: List<ThirdPartyLicenseNotice> = listOf(
    ThirdPartyLicenseNotice(
        name = "Apache Commons Compress",
        coordinates = "org.apache.commons:commons-compress",
        version = "1.26.2",
        license = "Apache License 2.0",
        copyrightHolder = "Copyright 2002–2024 The Apache Software Foundation",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "XZ for Java",
        coordinates = "org.tukaani:xz",
        version = "1.10",
        license = "Zero-Clause BSD (0BSD)",
        copyrightHolder = "Lasse Collin <lasse.collin@tukaani.org>",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "AndroidX Core KTX",
        coordinates = "androidx.core:core-ktx",
        version = "1.18.0",
        license = "Apache License 2.0",
        copyrightHolder = "The Android Open Source Project",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "AndroidX Lifecycle",
        coordinates = "androidx.lifecycle:lifecycle-runtime-ktx, lifecycle-viewmodel-compose, lifecycle-runtime-compose",
        version = "2.10.0",
        license = "Apache License 2.0",
        copyrightHolder = "The Android Open Source Project",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "AndroidX Activity Compose",
        coordinates = "androidx.activity:activity-compose",
        version = "1.13.0",
        license = "Apache License 2.0",
        copyrightHolder = "The Android Open Source Project",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "AndroidX Jetpack Compose",
        coordinates = "androidx.compose:compose-bom (ui, material3, material-icons-extended)",
        version = "2026.03.00",
        license = "Apache License 2.0",
        copyrightHolder = "The Android Open Source Project",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "AndroidX WorkManager KTX",
        coordinates = "androidx.work:work-runtime-ktx",
        version = "2.10.0",
        license = "Apache License 2.0",
        copyrightHolder = "The Android Open Source Project",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "Kotlin Standard Library & KotlinX Coroutines",
        coordinates = "org.jetbrains.kotlinx:kotlinx-coroutines-core, kotlinx-coroutines-android",
        version = "1.10.2",
        license = "Apache License 2.0",
        copyrightHolder = "Copyright 2010–2024 JetBrains s.r.o. and Kotlin Programming Language contributors",
        scope = "Runtime"
    ),
    ThirdPartyLicenseNotice(
        name = "JUnit 4",
        coordinates = "junit:junit",
        version = "4.13.2",
        license = "Eclipse Public License 1.0 (EPL-1.0)",
        copyrightHolder = "JUnit Contributors",
        scope = "Test-Only (Not Distributed in APK)"
    ),
    ThirdPartyLicenseNotice(
        name = "Robolectric",
        coordinates = "org.robolectric:robolectric",
        version = "4.16",
        license = "MIT / Apache License 2.0",
        copyrightHolder = "Robolectric Contributors / Google Inc.",
        scope = "Test-Only (Not Distributed in APK)"
    ),
    ThirdPartyLicenseNotice(
        name = "AndroidX Test Core & Ext JUnit",
        coordinates = "androidx.test:core (1.6.1), androidx.test.ext:junit (1.3.0)",
        version = "1.6.1 / 1.3.0",
        license = "Apache License 2.0",
        copyrightHolder = "The Android Open Source Project",
        scope = "Test-Only (Not Distributed in APK)"
    ),
    ThirdPartyLicenseNotice(
        name = "KotlinX Coroutines Test",
        coordinates = "org.jetbrains.kotlinx:kotlinx-coroutines-test",
        version = "1.10.2",
        license = "Apache License 2.0",
        copyrightHolder = "JetBrains s.r.o. and Kotlin Programming Language contributors",
        scope = "Test-Only (Not Distributed in APK)"
    )
)

@Composable
fun SettingsAboutCard(
    isDark: Boolean,
    isCheckingForUpdates: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current

    var showLicensesModal by rememberSaveable { mutableStateOf(false) }

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val outerCardBg = if (isDark) surfaceContainerLow else surfaceContainer
    val outerCardBorder = outlineVariant.copy(alpha = if (isDark) 0.55f else 0.65f)
    val contactTileBg = if (isDark) {
        surfaceContainerHigh.copy(alpha = 0.55f)
    } else {
        surfaceContainerLow
    }
    val contactTileBorder = primary.copy(alpha = if (isDark) 0.22f else 0.26f)
    val supportBtnBg = primaryContainer.copy(alpha = if (isDark) 0.38f else 0.52f)
    val supportBtnBorder = primary.copy(alpha = if (isDark) 0.36f else 0.42f)
    val licensesRowBg = if (isDark) {
        surfaceContainer.copy(alpha = 0.85f)
    } else {
        surfaceContainerLow.copy(alpha = 0.85f)
    }
    val licensesRowBorder = outlineVariant.copy(alpha = if (isDark) 0.45f else 0.55f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AboutCardOuterShape)
            .background(outerCardBg, AboutCardOuterShape)
            .border(1.dp, outerCardBorder, AboutCardOuterShape)
            .padding(horizontal = 18.dp, vertical = 24.dp)
            .testTag("made_by_biplov_footer"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Actual KaiHatch App Icon from project vector launcher resources
        KaiHatchAppLogoBadge(
            borderColor = primary.copy(alpha = 0.36f),
            size = 84.dp,
            modifier = Modifier.testTag("about_kaihatch_app_icon")
        )

        Spacer(modifier = Modifier.height(16.dp))

        // App Name
        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("about_app_name")
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Tagline
        Text(
            text = stringResource(R.string.about_app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("about_app_tagline")
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Developer
        Text(
            text = stringResource(R.string.about_developer_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("about_developer_name")
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Description
        Text(
            text = stringResource(R.string.about_project_description),
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .testTag("about_project_description")
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Three horizontally aligned, equally sized icon-only contact buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AboutContactIconButton(
                backgroundColor = contactTileBg,
                borderColor = contactTileBorder,
                contentDescriptionText = stringResource(R.string.about_contact_github_cd),
                testTag = "about_contact_website_button",
                onClick = {
                    HapticUtil.performUIHaptic(view)
                    openExternalUrl(context, ABOUT_WEBSITE_URL)
                },
                modifier = Modifier.weight(1f)
            ) {
                GitHubMarkIcon(
                    color = primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            AboutContactIconButton(
                backgroundColor = contactTileBg,
                borderColor = contactTileBorder,
                contentDescriptionText = stringResource(R.string.about_contact_email_cd),
                testTag = "about_contact_email_button",
                onClick = {
                    HapticUtil.performUIHaptic(view)
                    openEmailContact(context, ABOUT_EMAIL_ADDRESS)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Email,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            AboutContactIconButton(
                backgroundColor = contactTileBg,
                borderColor = contactTileBorder,
                contentDescriptionText = stringResource(R.string.about_contact_telegram_cd),
                testTag = "about_contact_telegram_button",
                onClick = {
                    HapticUtil.performUIHaptic(view)
                    openExternalUrl(context, ABOUT_TELEGRAM_URL)
                },
                modifier = Modifier.weight(1f)
            ) {
                TelegramPaperPlaneIcon(
                    color = primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Support Development Button (Ko-fi) — text centered across full button width independently of side icons
        val supportCd = stringResource(R.string.about_support_cd)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(SupportButtonShape)
                .background(supportBtnBg, SupportButtonShape)
                .border(1.dp, supportBtnBorder, SupportButtonShape)
                .clickable {
                    HapticUtil.performUIHaptic(view)
                    openExternalUrl(context, ABOUT_KOFI_URL)
                }
                .semantics {
                    role = Role.Button
                    contentDescription = supportCd
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("about_support_development_button")
        ) {
            KofiCoffeeCupIcon(
                cupColor = onSurface,
                heartColor = Color(0xFFFB7185),
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.CenterStart)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.about_support_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.about_support_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.about_opens_in_browser_cd),
                tint = onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .align(Alignment.CenterEnd)
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
            thickness = 1.dp,
            color = outlineVariant.copy(alpha = 0.45f)
        )

        // Check for Updates row directly above Open Licences
        val checkUpdatesCd = stringResource(R.string.about_check_updates_cd)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(LicensesRowShape)
                .background(licensesRowBg, LicensesRowShape)
                .border(1.dp, licensesRowBorder, LicensesRowShape)
                .clickable(enabled = !isCheckingForUpdates) {
                    HapticUtil.performUIHaptic(view)
                    onCheckForUpdates()
                }
                .semantics {
                    role = Role.Button
                    contentDescription = checkUpdatesCd
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("about_check_for_updates_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCheckingForUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.4.dp,
                        color = primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(R.string.about_check_updates_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onSurface
                    )
                    Text(
                        text = if (isCheckingForUpdates) {
                            stringResource(R.string.about_check_updates_checking)
                        } else {
                            stringResource(R.string.about_check_updates_subtitle, BuildConfig.VERSION_NAME)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Open-source licenses row at the bottom of the About card
        val licensesCd = stringResource(R.string.about_licenses_cd)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(LicensesRowShape)
                .background(licensesRowBg, LicensesRowShape)
                .border(1.dp, licensesRowBorder, LicensesRowShape)
                .clickable {
                    HapticUtil.performUIHaptic(view)
                    showLicensesModal = true
                }
                .semantics {
                    role = Role.Button
                    contentDescription = licensesCd
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("about_open_source_licenses_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(R.string.about_licenses_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onSurface
                    )
                    Text(
                        text = stringResource(R.string.about_licenses_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showLicensesModal) {
        OpenSourceLicensesDialog(
            onDismiss = { showLicensesModal = false }
        )
    }
}

@Composable
internal fun KaiHatchAppLogoBadge(
    borderColor: Color,
    size: Dp = 84.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(AboutAppIconShape)
            .border(1.5.dp, borderColor, AboutAppIconShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.about_app_icon_cd),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.26f)
        )
    }
}

@Composable
private fun AboutContactIconButton(
    backgroundColor: Color,
    borderColor: Color,
    contentDescriptionText: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(ContactButtonShape)
            .background(backgroundColor, ContactButtonShape)
            .border(1.dp, borderColor, ContactButtonShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = contentDescriptionText
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
private fun GitHubMarkIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        val githubPath = Path().apply {
            moveTo(12f * sx, 2f * sy)
            cubicTo(6.477f * sx, 2f * sy, 2f * sx, 6.484f * sy, 2f * sx, 12.017f * sy)
            cubicTo(2f * sx, 16.442f * sy, 4.865f * sx, 20.197f * sy, 8.839f * sx, 21.522f * sy)
            cubicTo(9.339f * sx, 21.614f * sy, 9.521f * sx, 21.305f * sy, 9.521f * sx, 21.041f * sy)
            cubicTo(9.521f * sx, 20.805f * sy, 9.512f * sx, 20.024f * sy, 9.508f * sx, 19.167f * sy)
            cubicTo(6.726f * sx, 19.771f * sy, 6.139f * sx, 17.988f * sy, 6.139f * sx, 17.988f * sy)
            cubicTo(5.684f * sx, 16.832f * sy, 5.029f * sx, 16.524f * sy, 5.029f * sx, 16.524f * sy)
            cubicTo(4.121f * sx, 15.903f * sy, 5.098f * sx, 15.915f * sy, 5.098f * sx, 15.915f * sy)
            cubicTo(6.101f * sx, 15.986f * sy, 6.629f * sx, 16.946f * sy, 6.629f * sx, 16.946f * sy)
            cubicTo(7.521f * sx, 18.474f * sy, 8.969f * sx, 18.032f * sy, 9.54f * sx, 17.777f * sy)
            cubicTo(9.631f * sx, 17.13f * sy, 9.889f * sx, 16.688f * sy, 10.175f * sx, 16.438f * sy)
            cubicTo(7.955f * sx, 16.185f * sy, 5.62f * sx, 15.327f * sy, 5.62f * sx, 11.493f * sy)
            cubicTo(5.62f * sx, 10.4f * sy, 6.01f * sx, 9.507f * sy, 6.649f * sx, 8.807f * sy)
            cubicTo(6.546f * sx, 8.554f * sy, 6.203f * sx, 7.535f * sy, 6.747f * sx, 6.157f * sy)
            cubicTo(6.747f * sx, 6.157f * sy, 7.586f * sx, 5.888f * sy, 9.497f * sx, 7.184f * sy)
            cubicTo(10.295f * sx, 6.962f * sy, 11.15f * sx, 6.851f * sy, 12f * sx, 6.847f * sy)
            cubicTo(12.85f * sx, 6.851f * sy, 13.705f * sx, 6.962f * sy, 14.505f * sx, 7.184f * sy)
            cubicTo(16.414f * sx, 5.888f * sy, 17.251f * sx, 6.157f * sy, 17.251f * sx, 6.157f * sy)
            cubicTo(17.797f * sx, 7.535f * sy, 17.454f * sx, 8.554f * sy, 17.351f * sx, 8.807f * sy)
            cubicTo(17.991f * sx, 9.507f * sy, 18.38f * sx, 10.4f * sy, 18.38f * sx, 11.493f * sy)
            cubicTo(18.38f * sx, 15.337f * sy, 16.041f * sx, 16.182f * sy, 13.813f * sx, 16.43f * sy)
            cubicTo(14.172f * sx, 16.741f * sy, 14.492f * sx, 17.353f * sy, 14.492f * sx, 18.29f * sy)
            cubicTo(14.492f * sx, 19.633f * sy, 14.48f * sx, 20.716f * sy, 14.48f * sx, 21.041f * sy)
            cubicTo(14.48f * sx, 21.309f * sy, 14.66f * sx, 21.62f * sy, 15.167f * sx, 21.52f * sy)
            cubicTo(19.138f * sx, 20.192f * sy, 22f * sx, 16.44f * sy, 22f * sx, 12.017f * sy)
            cubicTo(22f * sx, 6.484f * sy, 17.522f * sx, 2f * sy, 12f * sx, 2f * sy)
            close()
        }

        drawPath(
            path = githubPath,
            color = color
        )
    }
}

@Composable
private fun TelegramPaperPlaneIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.085f

        val planeOutline = Path().apply {
            moveTo(w * 0.88f, h * 0.16f)
            lineTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.42f, h * 0.58f)
            lineTo(w * 0.56f, h * 0.86f)
            close()
        }
        drawPath(
            path = planeOutline,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        val crease = Path().apply {
            moveTo(w * 0.88f, h * 0.16f)
            lineTo(w * 0.42f, h * 0.58f)
        }
        drawPath(
            path = crease,
            color = color,
            style = Stroke(
                width = strokeWidth * 0.9f,
                cap = StrokeCap.Round
            )
        )
    }
}

@Composable
private fun KofiCoffeeCupIcon(
    cupColor: Color,
    heartColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f

        // Cup body with rounded bottom
        val cupPath = Path().apply {
            moveTo(w * 0.14f, h * 0.24f)
            lineTo(w * 0.72f, h * 0.24f)
            lineTo(w * 0.72f, h * 0.56f)
            cubicTo(
                w * 0.72f, h * 0.76f,
                w * 0.58f, h * 0.84f,
                w * 0.43f, h * 0.84f
            )
            cubicTo(
                w * 0.28f, h * 0.84f,
                w * 0.14f, h * 0.76f,
                w * 0.14f, h * 0.56f
            )
            close()
        }
        drawPath(
            path = cupPath,
            color = cupColor,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Cup handle on the right
        val handlePath = Path().apply {
            moveTo(w * 0.72f, h * 0.32f)
            cubicTo(
                w * 0.92f, h * 0.32f,
                w * 0.92f, h * 0.62f,
                w * 0.72f, h * 0.62f
            )
        }
        drawPath(
            path = handlePath,
            color = cupColor,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Small filled heart centered inside the cup
        val heartPath = Path().apply {
            val cx = w * 0.43f
            val cy = h * 0.53f
            val hs = w * 0.14f
            moveTo(cx, cy + hs * 0.95f)
            cubicTo(
                cx - hs * 1.2f, cy + hs * 0.2f,
                cx - hs * 1.15f, cy - hs * 0.85f,
                cx, cy - hs * 0.25f
            )
            cubicTo(
                cx + hs * 1.15f, cy - hs * 0.85f,
                cx + hs * 1.2f, cy + hs * 0.2f,
                cx, cy + hs * 0.95f
            )
            close()
        }
        drawPath(path = heartPath, color = heartColor)
    }
}

@Composable
internal fun OpenSourceLicensesDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    BackHandler(onBack = onDismiss)

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val topBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("open_source_licenses_screen"),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            top = topBarInset + 12.dp,
                            bottom = bottomBarInset + 28.dp,
                            start = 16.dp,
                            end = 16.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top header bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                HapticUtil.performVirtualKeyHaptic(view)
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(primaryContainer, RoundedCornerShape(16.dp))
                                .border(1.dp, primary.copy(alpha = 0.32f), RoundedCornerShape(16.dp))
                                .testTag("close_licenses_screen_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nav_back_to_settings),
                                tint = onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = stringResource(R.string.about_licenses_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.licenses_dialog_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    // 1. KaiHatch Project License Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LicenseItemCardShape)
                            .background(surfaceContainer, LicenseItemCardShape)
                            .border(1.dp, primary.copy(alpha = 0.28f), LicenseItemCardShape)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.licenses_app_license_badge),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = primary,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                        Text(
                            text = stringResource(R.string.about_app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = onSurface
                        )
                        Text(
                            text = stringResource(R.string.licenses_app_copyright),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = onSurface
                        )
                        Text(
                            text = stringResource(R.string.licenses_app_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(primaryContainer.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .clickable {
                                    HapticUtil.performUIHaptic(view)
                                    openExternalUrl(context, APACHE_2_LICENSE_URL)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("licenses_view_apache2_button"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.licenses_apache_button),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.licenses_open_apache_cd),
                                tint = onPrimaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // 2. Conceptual Inspiration & Independence Notice
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LicenseItemCardShape)
                            .background(surfaceContainer, LicenseItemCardShape)
                            .border(1.dp, outlineVariant.copy(alpha = 0.45f), LicenseItemCardShape)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.licenses_inspiration_badge),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = primary,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                        Text(
                            text = stringResource(R.string.licenses_inspiration_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                        Text(
                            text = stringResource(R.string.licenses_inspiration_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant
                        )
                    }

                    // 3. Verified Third-Party Software Notices
                    Text(
                        text = stringResource(R.string.licenses_third_party_header),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = onSurface,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )

                    VERIFIED_THIRD_PARTY_NOTICES.forEach { notice ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(LicenseItemCardShape)
                                .background(surfaceContainerLow, LicenseItemCardShape)
                                .border(1.dp, outlineVariant.copy(alpha = 0.4f), LicenseItemCardShape)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = notice.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = notice.license,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primary,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                            Text(
                                text = "${notice.coordinates} • v${notice.version} (${notice.scope})",
                                fontSize = 11.sp,
                                color = onSurfaceVariant,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                            Text(
                                text = notice.copyrightHolder,
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    // 4. Zero-Clause BSD (0BSD) Text for XZ for Java
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LicenseItemCardShape)
                            .background(surfaceContainerLow, LicenseItemCardShape)
                            .border(1.dp, outlineVariant.copy(alpha = 0.4f), LicenseItemCardShape)
                            .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.licenses_0bsd_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                        Text(
                            text = stringResource(R.string.licenses_0bsd_body),
                            fontSize = 11.sp,
                            color = onSurfaceVariant,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    }
                }
            }
        }
    }
}
