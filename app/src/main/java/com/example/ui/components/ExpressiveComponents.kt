package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared pre-allocated shapes to eliminate per-composition RoundedCornerShape allocations.
 */
object ExpressiveShapes {
    val CardOuter = RoundedCornerShape(28.dp)
    val CardMedium = RoundedCornerShape(24.dp)
    val CardInner = RoundedCornerShape(20.dp)
    val RowItem = RoundedCornerShape(18.dp)
    val SubItem = RoundedCornerShape(16.dp)
    val IconBox = RoundedCornerShape(14.dp)
    val Small = RoundedCornerShape(12.dp)
    val Badge = RoundedCornerShape(8.dp)

    // Pre-allocated Grouped Card Shapes (zero Dp-animation recomposition overhead during scroll)
    val GroupSingle = RoundedCornerShape(26.dp)
    val GroupSelected = RoundedCornerShape(24.dp)
    val GroupTop = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
    val GroupMiddle = RoundedCornerShape(6.dp)
    val GroupBottom = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 26.dp, bottomEnd = 26.dp)
}

enum class ExpressiveGroupPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM
}

fun groupPositionFor(index: Int, totalCount: Int): ExpressiveGroupPosition {
    if (totalCount <= 1) return ExpressiveGroupPosition.SINGLE
    return when (index) {
        0 -> ExpressiveGroupPosition.TOP
        totalCount - 1 -> ExpressiveGroupPosition.BOTTOM
        else -> ExpressiveGroupPosition.MIDDLE
    }
}

/**
 * Material 3 Expressive 8-Lobe Clover/Scallop Shape.
 */
val ExpressiveCloverShape: Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseRadius = minOf(cx, cy) * 0.88f
    val waveAmp = minOf(cx, cy) * 0.10f
    val lobes = 8
    val steps = 64
    for (i in 0..steps) {
        val theta = (i.toFloat() / steps) * 2.0 * PI
        val r = baseRadius + waveAmp * cos(lobes * theta).toFloat()
        val x = cx + r * cos(theta).toFloat()
        val y = cy + r * sin(theta).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val QuickColorSpring = spring<Color>(stiffness = Spring.StiffnessMediumLow)

/**
 * Material 3 Expressive Grouped Card (Essentials / Android 16 style).
 * Uses pre-allocated static corner shapes and lightweight Box + clip + background instead of
 * 4 per-card animateDpAsState coroutines or heavy Surface wrappers.
 */
@Composable
fun ExpressiveGroupedCard(
    position: ExpressiveGroupPosition,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val shape = if (isSelected) {
        ExpressiveShapes.GroupSelected
    } else {
        when (position) {
            ExpressiveGroupPosition.SINGLE -> ExpressiveShapes.GroupSingle
            ExpressiveGroupPosition.TOP -> ExpressiveShapes.GroupTop
            ExpressiveGroupPosition.MIDDLE -> ExpressiveShapes.GroupMiddle
            ExpressiveGroupPosition.BOTTOM -> ExpressiveShapes.GroupBottom
        }
    }

    val bgColor = if (isSelected) selectedContainerColor else containerColor
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val clickModifier = if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(),
            role = Role.Button,
            onClick = {
                HapticUtil.performUIHaptic(view)
                onClick()
            }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .then(clickModifier)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Material 3 Expressive Top Screen Header with subtitle and pill status badge.
 */
@Composable
fun ExpressiveCleanHeader(
    badgeText: String = "",
    title: String,
    subtitle: String,
    badgeIcon: ImageVector = Icons.Default.Bolt,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = onSurface
            )
            if (badgeText.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant
        )
    }
}

/**
 * Material 3 Expressive Section Header with optional count pill.
 */
@Composable
fun ExpressiveSectionHeader(
    title: String,
    countText: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!countText.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 9.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor
                    )
                }
            }
        }
        trailingContent?.invoke()
    }
}

/**
 * Material 3 Expressive Clover / Scallop Icon Container.
 */
@Composable
fun ExpressiveCloverIcon(
    icon: ImageVector,
    containerColor: Color,
    iconColor: Color,
    size: Dp = 44.dp,
    iconSize: Dp = (size.value * 0.46f).dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(ExpressiveCloverShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Material 3 Expressive Bento Metric Tile.
 * Uses direct Column + clip + background instead of a Surface wrapper.
 */
@Composable
fun ExpressiveBentoTile(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(ExpressiveShapes.RowItem)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = JetBrainsMonoFontFamily,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Material 3 Expressive Action CTA Button.
 */
@Composable
fun ExpressiveMorphButton(
    text: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
    height: Dp = 62.dp,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val bg = if (enabled) containerColor else containerColor.copy(alpha = 0.45f)
    val fg = if (enabled) contentColor else contentColor.copy(alpha = 0.65f)

    Row(
        modifier = modifier
            .height(height)
            .clip(ExpressiveShapes.CardMedium)
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    onClick()
                }
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(horizontalAlignment = if (icon != null) Alignment.Start else Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = fg.copy(alpha = 0.84f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Material 3 Expressive Gapped Progress Indicator.
 * Uses simple, zero-allocation Canvas RoundRect drawing without infinite background wave timers.
 */
@Composable
fun ExpressiveWaveProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    @Suppress("UNUSED_PARAMETER") isWaveAnimated: Boolean = false,
    height: Dp = 14.dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "progress_bar"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
    ) {
        val w = size.width
        val h = size.height
        val radius = CornerRadius(h / 2f, h / 2f)
        val gapPx = 4.dp.toPx()

        val activeWidth = (w * animatedProgress).coerceIn(0f, w)
        if (activeWidth > 1f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset.Zero,
                size = Size(activeWidth, h),
                cornerRadius = radius
            )
        }

        val trackLeft = if (activeWidth > 1f) (activeWidth + gapPx).coerceAtMost(w) else 0f
        val trackWidth = (w - trackLeft).coerceAtLeast(0f)
        if (trackWidth > 1f) {
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(trackLeft, 0f),
                size = Size(trackWidth, h),
                cornerRadius = radius
            )
        }
    }
}
