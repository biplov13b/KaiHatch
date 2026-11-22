package com.biplov.kaihatch.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.biplov.kaihatch.data.LanguageHelper
import com.biplov.kaihatch.model.AppAccentPalette
import com.biplov.kaihatch.model.AppLanguage
import com.biplov.kaihatch.model.AppThemeMode

// Material 3 Expressive Shape Scale
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(44.dp)
)

private fun buildExpressiveDarkScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    surfaceTintBase: Color
): ColorScheme {
    val bgLowest = lerp(ExpressiveSurfaceLowest, primary, 0.05f)
    val surfaceDim = lerp(ExpressiveSurfaceDim, primary, 0.07f)
    val containerLow = lerp(ExpressiveSurfaceContainerLow, primary, 0.09f)
    val container = lerp(ExpressiveSurfaceContainer, primary, 0.12f)
    val containerHigh = lerp(ExpressiveSurfaceContainerHigh, primary, 0.15f)
    val containerHighest = lerp(ExpressiveSurfaceContainerHighest, primary, 0.19f)
    val outlineColor = lerp(Color(0xFF475569), primary, 0.25f)
    val outlineVariantColor = lerp(Color(0xFF283248), primary, 0.22f)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onPrimary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = ExpressiveAmber,
        onTertiary = ExpressiveAmberOnPrimary,
        tertiaryContainer = ExpressiveAmberContainer,
        onTertiaryContainer = ExpressiveAmberOnContainer,
        background = bgLowest,
        onBackground = Color(0xFFF1F5F9),
        surface = surfaceDim,
        onSurface = Color(0xFFF1F5F9),
        surfaceVariant = containerHigh,
        onSurfaceVariant = lerp(Color(0xFF94A3B8), primary, 0.18f),
        surfaceContainerLowest = bgLowest,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
        surfaceTint = surfaceTintBase,
        outline = outlineColor,
        outlineVariant = outlineVariantColor,
        error = ExpressiveRoseError,
        onError = Color(0xFF2D040E),
        errorContainer = ExpressiveRoseContainer,
        onErrorContainer = ExpressiveRoseOnContainer
    )
}

private fun buildExpressiveLightScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color = primary,
    secondaryContainer: Color,
    onSecondaryContainer: Color
): ColorScheme {
    // Balanced warm-slate neutral hierarchy: eliminates harsh glare while keeping cards,
    // grouped sections, inner wells, and interactive controls clearly differentiated.
    val bgLowest = lerp(Color(0xFFFAFBFD), primary, 0.015f)
    val surfaceCanvas = lerp(Color(0xFFF1F3F7), primary, 0.02f)
    val containerLow = lerp(Color(0xFFE5E9F1), primary, 0.035f)
    val container = lerp(Color(0xFFDCE2EC), primary, 0.05f)
    val containerHigh = lerp(Color(0xFFCFD7E4), primary, 0.065f)
    val containerHighest = lerp(Color(0xFFC1CBD9), primary, 0.08f)
    val outlineColor = lerp(Color(0xFF64748B), primary, 0.14f)
    val outlineVariantColor = lerp(Color(0xFF94A3B8), primary, 0.12f)

    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onPrimary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = Color(0xFF9A3412),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFED7AA),
        onTertiaryContainer = Color(0xFF431407),
        background = surfaceCanvas,
        onBackground = Color(0xFF0F172A),
        surface = surfaceCanvas,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = containerHigh,
        onSurfaceVariant = lerp(Color(0xFF334155), primary, 0.08f),
        surfaceContainerLowest = bgLowest,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
        surfaceTint = primary,
        outline = outlineColor,
        outlineVariant = outlineVariantColor,
        error = Color(0xFFBE123C),
        onError = Color.White,
        errorContainer = Color(0xFFFECDD3),
        onErrorContainer = Color(0xFF4C0519)
    )
}

internal fun resolveIsDarkTheme(
    themeMode: AppThemeMode,
    systemInDarkTheme: Boolean
): Boolean = when (themeMode) {
    AppThemeMode.DARK -> true
    AppThemeMode.LIGHT -> false
    AppThemeMode.SYSTEM -> systemInDarkTheme
}

internal fun shouldUseDarkSystemBarIcons(
    isDark: Boolean,
    backgroundColor: Color
): Boolean = !isDark && backgroundColor.luminance() > 0.5f

internal fun applySystemBarAppearance(
    window: Window,
    view: View,
    useDarkIcons: Boolean
) {
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class LocalizedContextWrapper(
    base: Context,
    private val localizedDelegate: Context
) : ContextWrapper(base) {
    override fun getResources(): android.content.res.Resources = localizedDelegate.resources
    override fun getAssets(): android.content.res.AssetManager = localizedDelegate.assets
}

@Composable
fun KaiHatchTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    accentPalette: AppAccentPalette = AppAccentPalette.OCEAN,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = resolveIsDarkTheme(themeMode, isSystemInDarkTheme())

    val context = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val systemLocaleTag = remember(baseConfig) {
        LanguageHelper.getDeviceSystemLanguageTag(context)
    }
    val localizedConfiguration = remember(baseConfig, appLanguage, systemLocaleTag) {
        LanguageHelper.createLocalizedConfiguration(baseConfig, appLanguage, context)
    }
    val localizedContext = remember(context, localizedConfiguration, appLanguage, systemLocaleTag) {
        val delegate = context.createConfigurationContext(localizedConfiguration)
        LocalizedContextWrapper(context, delegate)
    }
    val colorScheme = remember(isDark, accentPalette, context) {
        when {
            accentPalette == AppAccentPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (isDark) {
                    dynamicDarkColorScheme(context)
                } else {
                    val dyn = dynamicLightColorScheme(context)
                    dyn.copy(
                        background = lerp(dyn.background, Color(0xFFF1F3F7), 0.65f),
                        onBackground = Color(0xFF0F172A),
                        surface = lerp(dyn.surface, Color(0xFFF1F3F7), 0.65f),
                        onSurface = Color(0xFF0F172A),
                        onSurfaceVariant = lerp(Color(0xFF334155), dyn.primary, 0.10f),
                        surfaceContainerLowest = lerp(dyn.surfaceContainerLowest, Color(0xFFFAFBFD), 0.6f),
                        surfaceContainerLow = lerp(dyn.surfaceContainerLow, Color(0xFFE5E9F1), 0.6f),
                        surfaceContainer = lerp(dyn.surfaceContainer, Color(0xFFDCE2EC), 0.6f),
                        surfaceContainerHigh = lerp(dyn.surfaceContainerHigh, Color(0xFFCFD7E4), 0.6f),
                        surfaceContainerHighest = lerp(dyn.surfaceContainerHighest, Color(0xFFC1CBD9), 0.6f),
                        outline = lerp(Color(0xFF64748B), dyn.primary, 0.15f),
                        outlineVariant = lerp(Color(0xFF94A3B8), dyn.primary, 0.12f)
                    )
                }
            }
            isDark -> when (accentPalette) {
                AppAccentPalette.DYNAMIC -> buildExpressiveDarkScheme(
                    primary = Color(0xFF5EEAD4),
                    onPrimary = Color(0xFF00382E),
                    primaryContainer = Color(0xFF0D4A3E),
                    onPrimaryContainer = Color(0xFF99F6E4),
                    secondary = Color(0xFF2DD4BF),
                    secondaryContainer = Color(0xFF113832),
                    onSecondaryContainer = Color(0xFFCCFBF1),
                    surfaceTintBase = Color(0xFF5EEAD4)
                )
                AppAccentPalette.EMERALD -> buildExpressiveDarkScheme(
                    primary = Color(0xFF47E695),
                    onPrimary = Color(0xFF00391C),
                    primaryContainer = Color(0xFF084C2B),
                    onPrimaryContainer = Color(0xFFA7F3D0),
                    secondary = Color(0xFF34D399),
                    secondaryContainer = Color(0xFF0F3926),
                    onSecondaryContainer = Color(0xFFD1FAE5),
                    surfaceTintBase = Color(0xFF47E695)
                )
                AppAccentPalette.OCEAN -> buildExpressiveDarkScheme(
                    primary = Color(0xFF7DD3FC),
                    onPrimary = Color(0xFF003549),
                    primaryContainer = Color(0xFF0C4A6E),
                    onPrimaryContainer = Color(0xFFE0F2FE),
                    secondary = Color(0xFF38BDF8),
                    secondaryContainer = Color(0xFF153248),
                    onSecondaryContainer = Color(0xFFBAE6FD),
                    surfaceTintBase = Color(0xFF7DD3FC)
                )
                AppAccentPalette.VIOLET -> buildExpressiveDarkScheme(
                    primary = Color(0xFFD8B4FE),
                    onPrimary = Color(0xFF3B0764),
                    primaryContainer = Color(0xFF581C87),
                    onPrimaryContainer = Color(0xFFF3E8FF),
                    secondary = Color(0xFFC084FC),
                    secondaryContainer = Color(0xFF2D1B46),
                    onSecondaryContainer = Color(0xFFE9D5FF),
                    surfaceTintBase = Color(0xFFD8B4FE)
                )
                AppAccentPalette.CORAL -> buildExpressiveDarkScheme(
                    primary = Color(0xFFFDA4AF),
                    onPrimary = Color(0xFF4C0519),
                    primaryContainer = Color(0xFF881337),
                    onPrimaryContainer = Color(0xFFFFE4E6),
                    secondary = Color(0xFFFB7185),
                    secondaryContainer = Color(0xFF3F1624),
                    onSecondaryContainer = Color(0xFFFECDD3),
                    surfaceTintBase = Color(0xFFFDA4AF)
                )
                AppAccentPalette.AMBER -> buildExpressiveDarkScheme(
                    primary = Color(0xFFFDE047),
                    onPrimary = Color(0xFF422006),
                    primaryContainer = Color(0xFF713F12),
                    onPrimaryContainer = Color(0xFFFEF08A),
                    secondary = Color(0xFFFBBF24),
                    secondaryContainer = Color(0xFF3D290D),
                    onSecondaryContainer = Color(0xFFFEF3C7),
                    surfaceTintBase = Color(0xFFFDE047)
                )
            }
            else -> when (accentPalette) {
                AppAccentPalette.DYNAMIC -> buildExpressiveLightScheme(
                    primary = Color(0xFF0F766E),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFB8E8E2),
                    onPrimaryContainer = Color(0xFF062E2B),
                    secondary = Color(0xFF0D9488),
                    secondaryContainer = Color(0xFFCBECE7),
                    onSecondaryContainer = Color(0xFF0A3632)
                )
                AppAccentPalette.EMERALD -> buildExpressiveLightScheme(
                    primary = Color(0xFF047857),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFB6EED7),
                    onPrimaryContainer = Color(0xFF032E20),
                    secondary = Color(0xFF059669),
                    secondaryContainer = Color(0xFFC8F2E0),
                    onSecondaryContainer = Color(0xFF063927)
                )
                AppAccentPalette.OCEAN -> buildExpressiveLightScheme(
                    primary = Color(0xFF0369A1),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFBAE0F7),
                    onPrimaryContainer = Color(0xFF072A44),
                    secondary = Color(0xFF0284C7),
                    secondaryContainer = Color(0xFFCCE7F9),
                    onSecondaryContainer = Color(0xFF0A3452)
                )
                AppAccentPalette.VIOLET -> buildExpressiveLightScheme(
                    primary = Color(0xFF6D28D9),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFDAD0FA),
                    onPrimaryContainer = Color(0xFF2B0B5E),
                    secondary = Color(0xFF7C3AED),
                    secondaryContainer = Color(0xFFE4DDFC),
                    onSecondaryContainer = Color(0xFF34116E)
                )
                AppAccentPalette.CORAL -> buildExpressiveLightScheme(
                    primary = Color(0xFFBE123C),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFFBCAD5),
                    onPrimaryContainer = Color(0xFF4C0519),
                    secondary = Color(0xFFE11D48),
                    secondaryContainer = Color(0xFFFCD7E0),
                    onSecondaryContainer = Color(0xFF59081E)
                )
                AppAccentPalette.AMBER -> buildExpressiveLightScheme(
                    primary = Color(0xFFB45309),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFF9DFA7),
                    onPrimaryContainer = Color(0xFF451A03),
                    secondary = Color(0xFFD97706),
                    secondaryContainer = Color(0xFFFCE8BE),
                    onSecondaryContainer = Color(0xFF4D2005)
                )
            }
        }
    }

    val isRtl = LanguageHelper.isRtlLanguage(appLanguage, localizedConfiguration, context) ||
        localizedConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val view = LocalView.current
    if (!view.isInEditMode) {
        val useDarkSystemBarIcons = shouldUseDarkSystemBarIcons(isDark, colorScheme.background)
        SideEffect {
            val androidLayoutDir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            view.layoutDirection = androidLayoutDir
            view.context.findActivity()?.window?.let { window ->
                window.decorView.layoutDirection = androidLayoutDir
                applySystemBarAppearance(window, view, useDarkSystemBarIcons)
            }
        }
    }

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext,
        LocalLayoutDirection provides layoutDirection
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ExpressiveShapes,
            content = content
        )
    }
}
