package com.biplov.kaihatch.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biplov.kaihatch.R

@Composable
fun JournalTerminal(
    logs: List<String>,
    isActive: Boolean = true,
    defaultExpanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onClearLogs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    var isExpanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var lastAutoScrolledCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(logs.size, isExpanded) {
        if (isExpanded && logs.isNotEmpty() && logs.size != lastAutoScrolledCount) {
            lastAutoScrolledCount = logs.size
            listState.scrollToItem(logs.size - 1)
        } else if (logs.isEmpty()) {
            lastAutoScrolledCount = 0
        }
    }

    val chevronRotationState = animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = ExpressiveMotion.AccordionFloatSpring,
        label = "console_chevron_rotation"
    )

    val headerInteractionSource = remember { MutableInteractionSource() }
    val pillInteractionSource = remember { MutableInteractionSource() }
    val copyInteractionSource = remember { MutableInteractionSource() }
    val clearInteractionSource = remember { MutableInteractionSource() }

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
                .clip(ExpressiveShapes.RowItem)
                .clickable(
                    interactionSource = headerInteractionSource,
                    indication = androidx.compose.material3.ripple(),
                    role = Role.Button,
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        val next = !isExpanded
                        isExpanded = next
                        onExpandedChange?.invoke(next)
                    }
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
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
                        text = stringResource(R.string.journal_terminal_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isActive) {
                            stringResource(R.string.journal_terminal_subtitle_active)
                        } else {
                            stringResource(R.string.journal_terminal_subtitle_idle)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariantColor
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (logs.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(
                                interactionSource = copyInteractionSource,
                                indication = androidx.compose.material3.ripple(),
                                role = Role.Button,
                                onClick = {
                                    HapticUtil.performUIHaptic(view)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.extract_error_clipboard_label),
                                            logs.joinToString(separator = "\n")
                                        )
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.extract_error_copied_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            .testTag("console_copy_logs_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.extract_error_copy_cd),
                            tint = onSurfaceVariantColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    if (onClearLogs != null) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(
                                    interactionSource = clearInteractionSource,
                                    indication = androidx.compose.material3.ripple(),
                                    role = Role.Button,
                                    onClick = {
                                        HapticUtil.performUIHaptic(view)
                                        onClearLogs()
                                    }
                                )
                                .testTag("console_clear_logs_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.compress_clear_chip),
                                tint = onSurfaceVariantColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isExpanded) primaryColor.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .clickable(
                            interactionSource = pillInteractionSource,
                            indication = androidx.compose.material3.ripple(),
                            role = Role.Button,
                            onClick = {
                                HapticUtil.performUIHaptic(view)
                                val next = !isExpanded
                                isExpanded = next
                                onExpandedChange?.invoke(next)
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("console_expand_collapse_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) {
                            stringResource(R.string.journal_terminal_collapse_cd)
                        } else {
                            stringResource(R.string.journal_terminal_expand_cd)
                        },
                        tint = if (isExpanded) primaryColor else onSurfaceVariantColor,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = chevronRotationState.value }
                    )
                    Text(
                        text = if (isExpanded) {
                            stringResource(R.string.journal_terminal_collapse)
                        } else {
                            stringResource(R.string.journal_terminal_expand)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isExpanded) primaryColor else onSurfaceVariantColor
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = ExpressiveMotion.AccordionEnterTransition,
            exit = ExpressiveMotion.AccordionExitTransition
        ) {
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
                                text = stringResource(R.string.journal_terminal_empty),
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
                                .padding(14.dp)
                                .testTag("console_log_list"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = logs,
                                key = { index, _ -> index },
                                contentType = { _, _ -> "log_line" }
                            ) { _, line ->
                                val lineColor = when {
                                    line.contains("Truncated", ignoreCase = true) ||
                                        line.contains("Reclaimed", ignoreCase = true) ||
                                        line.contains("ftruncate", ignoreCase = true) -> primaryColor
                                    line.contains("CRC32 OK", ignoreCase = true) ||
                                        line.contains("CRC OK", ignoreCase = true) ||
                                        line.contains("Complete", ignoreCase = true) -> tertiaryColor
                                    line.contains("Error", ignoreCase = true) ||
                                        line.contains("Failed", ignoreCase = true) ||
                                        line.contains("Interrupt", ignoreCase = true) -> errorColor
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
