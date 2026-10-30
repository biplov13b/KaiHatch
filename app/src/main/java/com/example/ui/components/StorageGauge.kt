package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.StorageHelper
import com.example.ui.theme.JetBrainsMonoFontFamily

private val SavingsEmeraldColor = Color(0xFF2ECC71)

@Composable
fun StorageGauge(
    archiveBytes: Long,
    uncompressedBytes: Long,
    currentArchiveOnDisk: Long,
    extractedOnDisk: Long,
    isHatchMode: Boolean,
    deviceFreeBytes: Long = 0L,
    hatchPeakOverrideBytes: Long = 0L,
    modifier: Modifier = Modifier
) {
    val standardPeakBytes = (archiveBytes + uncompressedBytes).coerceAtLeast(1L)
    val hatchPeakBytes = maxOf(
        hatchPeakOverrideBytes,
        maxOf(archiveBytes, uncompressedBytes)
    ).coerceAtLeast(1L)
    val savedBytes = (standardPeakBytes - hatchPeakBytes).coerceAtLeast(0L)
    val savingsPercent = ((savedBytes.toDouble() / standardPeakBytes.toDouble()) * 100.0).toInt()

    val isLiveExtracting = extractedOnDisk > 0L || currentArchiveOnDisk != archiveBytes
    val baseArchiveTarget = if (isLiveExtracting) {
        (currentArchiveOnDisk.toFloat() / standardPeakBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        (archiveBytes.toFloat() / standardPeakBytes.toFloat()).coerceIn(0f, 1f)
    }
    val totalInPlaceTarget = if (isLiveExtracting) {
        ((currentArchiveOnDisk + extractedOnDisk).toFloat() / standardPeakBytes.toFloat())
            .coerceIn(baseArchiveTarget, 1f)
    } else {
        (hatchPeakBytes.toFloat() / standardPeakBytes.toFloat())
            .coerceIn(baseArchiveTarget, 1f)
    }

    val animatedBaseRatio by animateFloatAsState(
        targetValue = baseArchiveTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "hatch_base_ratio"
    )
    val animatedTotalInPlaceRatio by animateFloatAsState(
        targetValue = totalInPlaceTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "hatch_total_ratio"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error

    val standardPeakFormatted = remember(standardPeakBytes) { StorageHelper.formatBytes(standardPeakBytes) }
    val hatchPeakFormatted = remember(hatchPeakBytes) { StorageHelper.formatBytes(hatchPeakBytes) }
    val savedBytesFormatted = remember(standardPeakBytes, hatchPeakBytes) {
        StorageHelper.formatConsistentSavedBytes(standardPeakBytes, hatchPeakBytes)
    }
    val archiveFormatted = remember(archiveBytes) { StorageHelper.formatBytes(archiveBytes) }
    val unpackedFormatted = remember(uncompressedBytes) { StorageHelper.formatBytes(uncompressedBytes) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(18.dp)
    ) {
        // Header Row: "Storage comparison" + "Estimated" / "Live" Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Storage comparison",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isLiveExtracting) primaryColor.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isLiveExtracting) "Live" else "Estimated",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLiveExtracting) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Side-by-Side Peak Comparison Boxes: "Standard peak" vs "In-place peak"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PeakComparisonBox(
                label = "Standard peak",
                value = standardPeakFormatted,
                isHighlighted = !isHatchMode,
                valueColor = if (!isHatchMode) errorColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            PeakComparisonBox(
                label = "In-place peak",
                value = hatchPeakFormatted,
                isHighlighted = isHatchMode,
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        if (deviceFreeBytes > 0L && standardPeakBytes > deviceFreeBytes && hatchPeakBytes <= deviceFreeBytes) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tertiaryColor.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = tertiaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Standard extraction exceeds free space — In-Place fits!",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Estimated Peak Storage Saved Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isLiveExtracting) "Peak storage saved" else "Estimated peak storage saved",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isHatchMode) savedBytesFormatted else "0 B",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                color = if (isHatchMode) SavingsEmeraldColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Visual Peak Bar:
        // - In-Place selected: shows archive file size in solid color + extra peak space in light faded color
        // - Standard selected: shows full red bar across the 2x peak size
        if (isHatchMode) {
            InPlacePeakBar(
                baseRatio = animatedBaseRatio,
                totalPeakRatio = animatedTotalInPlaceRatio,
                solidColor = primaryColor,
                fadedExtraColor = primaryColor.copy(alpha = 0.35f)
            )
        } else {
            StandardFullRedBar(barColor = errorColor)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isHatchMode) {
                "$savingsPercent% lower peak storage use than standard extraction."
            } else {
                "Standard extraction keeps the source archive intact (requires $standardPeakFormatted peak)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        Spacer(modifier = Modifier.height(12.dp))

        // Breakdown Rows: Archive size & Extracted size
        StorageBreakdownRow(
            label = "Archive size",
            value = archiveFormatted
        )
        Spacer(modifier = Modifier.height(8.dp))
        StorageBreakdownRow(
            label = "Extracted size",
            value = unpackedFormatted
        )

        if (isLiveExtracting) {
            Spacer(modifier = Modifier.height(8.dp))
            val archiveNowFormatted = remember(currentArchiveOnDisk) { StorageHelper.formatBytes(currentArchiveOnDisk) }
            val extractedNowFormatted = remember(extractedOnDisk) { StorageHelper.formatBytes(extractedOnDisk) }
            StorageBreakdownRow(
                label = "Live disk usage",
                value = "Archive $archiveNowFormatted • Output $extractedNowFormatted"
            )
        }
    }
}

@Composable
private fun PeakComparisonBox(
    label: String,
    value: String,
    isHighlighted: Boolean,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = if (isHighlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            fontSize = 18.sp,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor
        )
    }
}

@Composable
private fun StorageBreakdownRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StandardFullRedBar(
    barColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(barColor)
    )
}

@Composable
private fun InPlacePeakBar(
    baseRatio: Float,
    totalPeakRatio: Float,
    solidColor: Color,
    fadedExtraColor: Color
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
    ) {
        val w = size.width
        val h = size.height
        val radius = CornerRadius(h / 2f, h / 2f)

        // Track background
        drawRoundRect(
            color = trackColor,
            size = Size(w, h),
            cornerRadius = radius
        )

        val baseWidth = (w * baseRatio).coerceIn(0f, w)
        val totalPeakWidth = (w * totalPeakRatio).coerceIn(baseWidth, w)

        // Light faded bar extending to the total in-place peak (representing the extra space it takes)
        if (totalPeakWidth > 0f) {
            drawRoundRect(
                color = fadedExtraColor,
                topLeft = Offset(0f, 0f),
                size = Size(totalPeakWidth, h),
                cornerRadius = radius
            )
        }

        // Solid bar for the archive file size itself
        if (baseWidth > 0f) {
            drawRoundRect(
                color = solidColor,
                topLeft = Offset(0f, 0f),
                size = Size(baseWidth, h),
                cornerRadius = radius
            )
        }
    }
}
