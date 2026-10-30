package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.StorageHelper
import com.example.ui.HatchViewModel
import com.example.ui.ScreenTab
import com.example.ui.components.ExpressiveCleanHeader
import com.example.ui.components.ExpressiveCloverIcon
import com.example.ui.components.ExpressiveGroupPosition
import com.example.ui.components.ExpressiveGroupedCard
import com.example.ui.components.ExpressiveSectionHeader
import com.example.ui.components.groupPositionFor
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val records by viewModel.historyRepo.records.collectAsState()
    val totalReclaimed by viewModel.historyRepo.totalReclaimedBytes.collectAsState()

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = topInsetPadding + 8.dp, bottom = 148.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            ExpressiveCleanHeader(
                badgeText = "STORAGE SAVED",
                title = "Savings Ledger",
                subtitle = "Track unpacked archives and total storage space saved"
            )
        }

        // Oversized Display Typography Cumulative Savings Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cumulative_savings_hero_card"),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ExpressiveCloverIcon(
                        icon = Icons.Default.Bolt,
                        containerColor = primaryContainer,
                        iconColor = primary,
                        size = 56.dp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "TOTAL DISK SPACE RECLAIMED",
                        style = MaterialTheme.typography.labelSmall,
                        color = primary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = StorageHelper.formatBytes(totalReclaimed),
                        style = MaterialTheme.typography.displayMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Saved from redundant duplicate copies via KaiHatch In-Place Unpacking",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

        // History Records List (M3 Expressive Stacked Group)
        ExpressiveSectionHeader(
            title = "Operation Ledger",
            countText = "${records.size}",
            accentColor = primary
        )

        if (records.isEmpty()) {
            ExpressiveGroupedCard(
                position = ExpressiveGroupPosition.SINGLE,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ExpressiveCloverIcon(
                        icon = Icons.Default.History,
                        containerColor = primaryContainer,
                        iconColor = primary,
                        size = 52.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("No operation history yet", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Unpack or compress archives to see verified records here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val dateFormat = androidx.compose.runtime.remember {
                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    records.forEachIndexed { idx, rec ->
                        val dateStr = androidx.compose.runtime.remember(rec.timestamp) {
                            dateFormat.format(Date(rec.timestamp))
                        }
                        val isHatch = rec.type == "HATCH_EXTRACT"
                        val isCompress = rec.type == "COMPRESS"
                        val pos = groupPositionFor(idx, records.size)

                        ExpressiveGroupedCard(
                            position = pos,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.testTag("record_card_${rec.id}")
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isHatch || isCompress) primaryContainer else secondaryContainer
                                        ) {
                                            Text(
                                                text = when {
                                                    isHatch -> "⚡ Hatch In-Place"
                                                    isCompress -> "🗜️ Compressed"
                                                    else -> "🛡️ Safe Extract"
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isHatch || isCompress) onPrimaryContainer else onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = dateStr,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Success", tint = primary, modifier = Modifier.size(18.dp))
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = rec.sourceName,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${StorageHelper.formatBytes(rec.originalSize)} • ${rec.entryCount} items • ${rec.durationMs}ms",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (rec.reclaimedOrSavedSize > 0) {
                                        Text(
                                            text = "+${StorageHelper.formatBytes(rec.reclaimedOrSavedSize)} saved",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }

        Spacer(modifier = Modifier.height(96.dp))
    }
}
