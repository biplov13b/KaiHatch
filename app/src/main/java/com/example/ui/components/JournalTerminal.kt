package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun JournalTerminal(
    logs: List<String>,
    isActive: Boolean = true,
    defaultExpanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var isExpanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }

    LaunchedEffect(logs.size, isExpanded) {
        if (isExpanded && logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    val outerBg = MaterialTheme.colorScheme.surfaceContainerLow
    val innerBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.CardOuter)
            .background(outerBg)
            .border(1.dp, outlineColor, ExpressiveShapes.CardOuter)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val next = !isExpanded
                    isExpanded = next
                    onExpandedChange?.invoke(next)
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(ExpressiveShapes.Small)
                        .background(primaryColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "I/O Console",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isActive) "Live activity log" else "Activity & storage log",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariantColor
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isExpanded) primaryColor.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    .clickable {
                        val next = !isExpanded
                        isExpanded = next
                        onExpandedChange?.invoke(next)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("console_expand_collapse_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse console" else "Expand console",
                    tint = if (isExpanded) primaryColor else onSurfaceVariantColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isExpanded) "Collapse" else "Expand",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isExpanded) primaryColor else onSurfaceVariantColor
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ExpressiveShapes.RowItem)
                        .background(innerBg)
                        .border(1.dp, outlineColor, ExpressiveShapes.RowItem)
                ) {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp)
                                .padding(14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = "> Ready\n> Select an archive to start unpacking.",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = onSurfaceVariantColor.copy(alpha = 0.75f),
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 180.dp)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = logs,
                                key = { index, _ -> index },
                                contentType = { _, _ -> "log_line" }
                            ) { _, line ->
                                val lineColor = when {
                                    line.contains("Truncated") || line.contains("Reclaimed") -> primaryColor
                                    line.contains("CRC32 OK") || line.contains("Complete") -> tertiaryColor
                                    line.contains("Error") || line.contains("Interrupt") -> errorColor
                                    else -> onSurfaceVariantColor
                                }
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = lineColor,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
