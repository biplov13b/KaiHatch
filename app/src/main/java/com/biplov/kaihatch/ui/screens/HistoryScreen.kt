package com.biplov.kaihatch.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.ui.HatchViewModel
import com.biplov.kaihatch.ui.components.ExpressiveCleanHeader
import com.biplov.kaihatch.ui.components.ExpressiveCloverIcon
import com.biplov.kaihatch.ui.components.ExpressiveGroupPosition
import com.biplov.kaihatch.ui.components.ExpressiveGroupedCard
import com.biplov.kaihatch.ui.components.ExpressiveSectionHeader
import com.biplov.kaihatch.ui.components.ExpressiveShapes
import com.biplov.kaihatch.ui.components.groupPositionFor
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HatchViewModel,
    topInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val records by viewModel.historyRepo.records.collectAsStateWithLifecycle()
    val totalReclaimed by viewModel.historyRepo.totalReclaimedBytes.collectAsStateWithLifecycle()

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer

    val formattedTotalReclaimed = remember(totalReclaimed) {
        StorageHelper.formatBytes(totalReclaimed)
    }
    val dateFormat = remember {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = topInsetPadding + 8.dp, bottom = 244.dp)
    ) {
        item(key = "history_header_and_hero", contentType = "history_hero") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    ExpressiveCleanHeader(
                        badgeText = stringResource(R.string.history_header_badge),
                        title = stringResource(R.string.history_header_title),
                        subtitle = stringResource(R.string.history_header_subtitle)
                    )
                }

                // Oversized Display Typography Cumulative Savings Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cumulative_savings_hero_card"),
                    shape = ExpressiveShapes.HeroCard,
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
                            text = stringResource(R.string.history_total_reclaimed_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = primary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = formattedTotalReclaimed,
                            style = MaterialTheme.typography.displayMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = stringResource(R.string.history_total_reclaimed_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // History Records List (M3 Expressive Stacked Group)
                ExpressiveSectionHeader(
                    title = stringResource(R.string.history_operation_ledger_title),
                    countText = "${records.size}",
                    accentColor = primary
                )

                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        if (records.isEmpty()) {
            item(key = "history_empty_state", contentType = "history_empty") {
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
                        Text(
                            text = stringResource(R.string.history_empty_title),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.history_empty_subtitle),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                items = records,
                key = { _, rec -> rec.id },
                contentType = { _, _ -> "history_record" }
            ) { idx, rec ->
                val dateStr = remember(rec.timestamp) {
                    dateFormat.format(Date(rec.timestamp))
                }
                val formattedOrigSize = remember(rec.originalSize) {
                    StorageHelper.formatBytes(rec.originalSize)
                }
                val formattedSavedSize = remember(rec.reclaimedOrSavedSize) {
                    if (rec.reclaimedOrSavedSize > 0L) {
                        StorageHelper.formatBytes(rec.reclaimedOrSavedSize)
                    } else ""
                }
                val isHatch = rec.type == "HATCH_EXTRACT"
                val isCompress = rec.type == "COMPRESS"
                val pos = groupPositionFor(idx, records.size)

                ExpressiveGroupedCard(
                    position = pos,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .testTag("record_card_${rec.id}")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isHatch || isCompress) primaryContainer else secondaryContainer
                                ) {
                                    Text(
                                        text = when {
                                            isHatch -> stringResource(R.string.history_badge_hatch)
                                            isCompress -> stringResource(R.string.history_badge_compress)
                                            else -> stringResource(R.string.history_badge_standard)
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

                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.history_success_cd),
                                tint = primary,
                                modifier = Modifier.size(18.dp)
                            )
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
                                text = stringResource(
                                    R.string.history_record_meta,
                                    formattedOrigSize,
                                    rec.entryCount,
                                    rec.durationMs
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (rec.reclaimedOrSavedSize > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.history_saved_badge,
                                        formattedSavedSize
                                    ),
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
}

