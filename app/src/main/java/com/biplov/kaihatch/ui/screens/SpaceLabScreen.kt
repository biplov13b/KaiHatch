package com.biplov.kaihatch.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.components.ExpressiveBentoTile
import com.biplov.kaihatch.ui.components.ExpressiveCleanHeader
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
import com.biplov.kaihatch.ui.components.ExpressiveSectionHeader
import com.biplov.kaihatch.ui.components.ExpressiveWaveProgressIndicator
import com.biplov.kaihatch.ui.components.groupPositionFor
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily

private val SpaceLabSteps = listOf(
    "64-Bit Central Directory Scan" to "Indexes all file offsets & ZIP64 extra fields without decompressing.",
    "Crash-Safe .hatchstate Journal" to "Persists the Central Directory table atomically before touching archive bytes.",
    "Reverse Back-to-Front Extraction" to "Extracts from the highest byte offset toward the beginning of the file.",
    "CRC-32 Verification Gate" to "Verifies checksum integrity on every extracted file before truncation.",
    "Zero-Copy Linux ftruncate()" to "Shrinks the archive file descriptor immediately, freeing disk blocks live!"
)

private val SpaceLabPackages = listOf(
    Triple(3, "Mini Demo (3 MB)", "Quick test package with text logs & config"),
    Triple(10, "Game Assets Pack (10 MB)", "Textures, PCM audio & metadata database"),
    Triple(25, "Heavy Benchmark (25 MB)", "Large payload with high compression variance")
)

private val StorageCardShape = RoundedCornerShape(28.dp)
private val StepBadgeShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(50)

@Composable
fun SpaceLabScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val freeSpace by viewModel.deviceFreeSpace.collectAsState()
    val totalSpace by viewModel.deviceTotalSpace.collectAsState()
    val isGenerating by viewModel.labGenerating.collectAsState()

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 8.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            ExpressiveCleanHeader(
                badgeText = "STORAGE BENCHMARK",
                title = "Space Lab",
                subtitle = "Analyze free space and test zero-copy unpacking speed"
            )
        }

        val usedSpace = (totalSpace - freeSpace).coerceAtLeast(0L)
        val usedFraction = if (totalSpace > 0) (usedSpace.toFloat() / totalSpace.toFloat()).coerceIn(0f, 1f) else 0.5f

        Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceContainer, StorageCardShape)
                    .padding(18.dp)
                    .testTag("device_storage_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveCloverIcon(
                            icon = Icons.Default.Storage,
                            containerColor = primaryContainer,
                            iconColor = primary,
                            size = 44.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Device Storage Overview",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Live available and used disk space",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                ExpressiveWaveProgressIndicator(
                    progress = usedFraction,
                    activeColor = if (usedFraction > 0.85f) MaterialTheme.colorScheme.error else primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    isWaveAnimated = false,
                    height = 14.dp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExpressiveBentoTile(
                        label = "Used Space",
                        value = StorageHelper.formatBytes(usedSpace),
                        accentColor = primary,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveBentoTile(
                        label = "Available",
                        value = StorageHelper.formatBytes(freeSpace),
                        accentColor = primary,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveBentoTile(
                        label = "Total Capacity",
                        value = StorageHelper.formatBytes(totalSpace),
                        accentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

        Column(
            modifier = Modifier.testTag("how_kaihatch_works_card"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
                ExpressiveSectionHeader(
                    title = "How In-Place Truncation Works",
                    countText = "5 STEPS",
                    accentColor = primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                SpaceLabSteps.forEachIndexed { idx, (title, desc) ->
                    val pos = groupPositionFor(idx, SpaceLabSteps.size)
                    ExpressiveGroupedCard(
                        position = pos,
                        containerColor = surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(primaryContainer, StepBadgeShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = onPrimaryContainer,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

        Column(
            modifier = Modifier.testTag("generate_test_packages_card"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
                ExpressiveSectionHeader(
                    title = "Generate Benchmark Archives",
                    countText = "LIVE DEMO",
                    accentColor = primary,
                    trailingContent = {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = primary,
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                SpaceLabPackages.forEachIndexed { idx, (sizeMb, label, desc) ->
                    val pos = groupPositionFor(idx, SpaceLabPackages.size)
                    ExpressiveGroupedCard(
                        position = pos,
                        containerColor = surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Button(
                                onClick = { viewModel.generateTestPackage(sizeMb) },
                                enabled = !isGenerating,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primary,
                                    contentColor = onPrimary
                                ),
                                shape = PillShape,
                                modifier = Modifier
                                    .height(40.dp)
                                    .testTag("generate_${sizeMb}mb_button")
                            ) {
                                Icon(imageVector = Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Create", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
    }
}
