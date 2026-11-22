package com.biplov.kaihatch.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.biplov.kaihatch.R
import com.biplov.kaihatch.model.AppLanguage
import com.biplov.kaihatch.ui.theme.JetBrainsMonoFontFamily

private val PickerDialogShape = RoundedCornerShape(28.dp)
private val SearchFieldShape = RoundedCornerShape(18.dp)
private val SelectedLanguageItemShape = RoundedCornerShape(20.dp)
private val UnselectedLanguageItemShape = RoundedCornerShape(16.dp)
private val CompactSelectorShape = RoundedCornerShape(50)

/**
 * Compact pill selector button displaying the currently active [AppLanguage]
 * (e.g., on the Onboarding Welcome screen or inside Settings).
 */
@Composable
fun CompactLanguageSelectorButton(
    selectedLanguage: AppLanguage,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    isSystemLanguageSupported: Boolean = true
) {
    val view = LocalView.current
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh

    val effectiveSelected = if (!isSystemLanguageSupported && selectedLanguage == AppLanguage.SYSTEM) {
        AppLanguage.ENGLISH
    } else {
        selectedLanguage
    }

    val displayLabel = if (effectiveSelected == AppLanguage.SYSTEM) {
        stringResource(effectiveSelected.labelResId)
    } else {
        effectiveSelected.nativeName
    }

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CompactSelectorShape)
            .background(surfaceContainerHigh, CompactSelectorShape)
            .border(1.dp, primary.copy(alpha = 0.30f), CompactSelectorShape)
            .clickable(role = Role.Button) {
                HapticUtil.performUIHaptic(view)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = onPrimaryContainer,
                modifier = Modifier.size(15.dp)
            )
        }

        Text(
            text = displayLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Shared searchable language picker dialog with native language names.
 * Used by both Onboarding (Welcome step) and Settings → Appearance & Theme.
 */
@Composable
fun LanguagePickerDialog(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
    isSystemLanguageSupported: Boolean = true
) {
    val view = LocalView.current
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val effectiveSelectedLanguage = if (!isSystemLanguageSupported && selectedLanguage == AppLanguage.SYSTEM) {
        AppLanguage.ENGLISH
    } else {
        selectedLanguage
    }

    val allLanguages = remember(isSystemLanguageSupported) {
        AppLanguage.availableLanguages(includeSystemDefault = isSystemLanguageSupported)
    }
    val localizedLabels = allLanguages.associateWith { stringResource(it.labelResId) }
    val localizedSubtitles = allLanguages.associateWith { stringResource(it.subtitleResId) }

    val filteredLanguages = remember(searchQuery, localizedLabels, localizedSubtitles, isSystemLanguageSupported) {
        AppLanguage.filterByQuery(
            query = searchQuery,
            localizedLabels = localizedLabels,
            localizedSubtitles = localizedSubtitles,
            includeSystemDefault = isSystemLanguageSupported
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("language_picker_dialog"),
            shape = PickerDialogShape,
            color = surfaceContainer,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExpressiveCloverIcon(
                            icon = Icons.Default.Language,
                            containerColor = primaryContainer,
                            iconColor = primary,
                            size = 42.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.language_picker_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = onSurface
                            )
                            Text(
                                text = stringResource(R.string.language_picker_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(surfaceContainerHigh)
                            .testTag("language_picker_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.language_picker_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.testTag("language_picker_clear_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = SearchFieldShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = surfaceContainerLow,
                        unfocusedContainerColor = surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("language_picker_search_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Language Options List with Material 3 Expressive Scrollbar
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 10.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (filteredLanguages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp)
                                    .testTag("language_picker_empty_state"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.language_picker_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onSurfaceVariant
                                )
                            }
                        } else {
                            filteredLanguages.forEach { language ->
                                val isSelected = language == effectiveSelectedLanguage
                                val itemShape = if (isSelected) SelectedLanguageItemShape else UnselectedLanguageItemShape
                                val itemBg = if (isSelected) primaryContainer else surfaceContainerLow
                                val borderColor = if (isSelected) primary.copy(alpha = 0.45f) else surfaceContainerHigh

                                val primaryDisplayName = if (language == AppLanguage.SYSTEM) {
                                    localizedLabels[language] ?: language.nativeName
                                } else {
                                    language.nativeName
                                }
                                val subtitleText = localizedSubtitles[language] ?: language.label
                                val codeBadge = if (language == AppLanguage.SYSTEM) {
                                    stringResource(R.string.language_auto_badge)
                                } else {
                                    language.tag.uppercase()
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 58.dp)
                                        .clip(itemShape)
                                        .background(itemBg, itemShape)
                                        .border(1.dp, borderColor, itemShape)
                                        .clickable(role = Role.RadioButton) {
                                            HapticUtil.performUIHaptic(view)
                                            onLanguageSelected(language)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                        .testTag("language_option_${language.name.lowercase()}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isSelected) primary.copy(alpha = 0.18f)
                                                    else surfaceContainerHigh
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = codeBadge,
                                                fontSize = 10.sp,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) onPrimaryContainer else onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column {
                                            Text(
                                                text = primaryDisplayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) onPrimaryContainer else onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = subtitleText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSelected) onPrimaryContainer.copy(alpha = 0.8f) else onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) primary else surfaceContainerHigh,
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Medium-slim Material 3 Expressive-style scrollbar with interactive drag
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(top = 2.dp, bottom = 2.dp)
                    ) {
                        ExpressiveListScrollbar(
                            scrollState = scrollState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Slim, Material 3 Expressive-style scrollbar for the language picker.
 *
 * Features:
 * - Medium-slim 4dp width with fully rounded ends, dynamically expanding to 6.5dp during drag.
 * - Follows active theme accent color (`MaterialTheme.colorScheme.primary`).
 * - Allows users to hold and drag the thumb for fast scrolling through the list.
 * - Highlights with a stronger shade of the active accent while dragging.
 * - Smooth fade-in to active opacity (1f) during scroll/drag gestures;
 *   fades to subtle idle opacity (0.42f) to indicate more languages are available below.
 * - Fully hides when filtered search results fit without scrolling.
 */
@Composable
private fun ExpressiveListScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val canScroll = scrollState.maxValue > 0
    val isScrolling = scrollState.isScrollInProgress
    var isDragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    val targetAlpha = when {
        !canScroll -> 0f
        isDragging || isScrolling -> 1f
        else -> 0.42f
    }

    val animatedAlphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = if (isDragging || isScrolling) 150 else 450,
            easing = FastOutSlowInEasing
        ),
        label = "language_picker_scrollbar_alpha"
    )
    val isScrollbarVisible by remember {
        androidx.compose.runtime.derivedStateOf { animatedAlphaState.value > 0.005f }
    }

    // Expand thumb width during drag for tactile M3 Expressive feedback
    val thumbWidthDp by animateDpAsState(
        targetValue = if (isDragging) 6.5.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "language_picker_scrollbar_width"
    )

    if (!isScrollbarVisible) return

    val primaryAccent = MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // Thumb color: follows current theme accent, with stronger highlight while dragging
    val baseThumbColor = primaryAccent
    val highlightedThumbColor = if (isDark) {
        lerp(primaryAccent, Color.White, 0.28f)
    } else {
        lerp(primaryAccent, Color.Black, 0.18f)
    }
    val effectiveThumbColor = if (isDragging) highlightedThumbColor else baseThumbColor

    // Subtle translucent track matching current accent
    val trackColor = primaryAccent.copy(alpha = if (isDark) 0.12f else 0.10f)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .onSizeChanged { size ->
                trackHeightPx = size.height.toFloat()
            }
            .pointerInput(scrollState) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val totalTrack = trackHeightPx
                        val maxScroll = scrollState.maxValue.toFloat()
                        if (totalTrack > 0f && maxScroll > 0f) {
                            isDragging = true
                            val totalContentHeight = totalTrack + maxScroll
                            val visibleRatio = (totalTrack / totalContentHeight).coerceIn(0.10f, 1f)
                            val minThumbPx = 24.dp.toPx().coerceAtMost(totalTrack)
                            val thumbPx = (totalTrack * visibleRatio).coerceIn(minThumbPx, totalTrack)
                            val scrollableTrack = totalTrack - thumbPx
                            if (scrollableTrack > 0f) {
                                val targetThumbTop = (offset.y - thumbPx / 2f).coerceIn(0f, scrollableTrack)
                                val fraction = targetThumbTop / scrollableTrack
                                coroutineScope.launch {
                                    scrollState.scrollTo((fraction * maxScroll).toInt())
                                }
                            }
                        }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { _, dragAmount ->
                        val totalTrack = trackHeightPx
                        val maxScroll = scrollState.maxValue.toFloat()
                        if (totalTrack > 0f && maxScroll > 0f) {
                            val totalContentHeight = totalTrack + maxScroll
                            val visibleRatio = (totalTrack / totalContentHeight).coerceIn(0.10f, 1f)
                            val minThumbPx = 24.dp.toPx().coerceAtMost(totalTrack)
                            val thumbPx = (totalTrack * visibleRatio).coerceIn(minThumbPx, totalTrack)
                            val scrollableTrack = totalTrack - thumbPx
                            if (scrollableTrack > 0f) {
                                val scrollDelta = (dragAmount / scrollableTrack) * maxScroll
                                coroutineScope.launch {
                                    val newScroll = (scrollState.value + scrollDelta).coerceIn(0f, maxScroll)
                                    scrollState.scrollTo(newScroll.toInt())
                                }
                            }
                        }
                    }
                )
            }
            .testTag("language_picker_scrollbar"),
        contentAlignment = Alignment.CenterEnd
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(thumbWidthDp)
                .padding(end = 1.dp)
        ) {
            val totalTrackHeight = size.height
            val currentAlpha = animatedAlphaState.value
            if (totalTrackHeight <= 0f || currentAlpha <= 0.005f) return@Canvas

            val trackWidth = size.width
            val cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)

            // 1. Subtle translucent track with rounded ends
            drawRoundRect(
                color = trackColor.copy(alpha = trackColor.alpha * (currentAlpha / 0.42f).coerceIn(0.6f, 1f)),
                topLeft = Offset(0f, 0f),
                size = Size(trackWidth, totalTrackHeight),
                cornerRadius = cornerRadius
            )

            // 2. Dynamic thumb geometry based on viewport vs total content
            val totalContentHeight = totalTrackHeight + scrollState.maxValue
            val visibleRatio = (totalTrackHeight / totalContentHeight).coerceIn(0.10f, 1f)
            val minThumbHeight = 24.dp.toPx().coerceAtMost(totalTrackHeight)
            val thumbHeight = (totalTrackHeight * visibleRatio).coerceIn(minThumbHeight, totalTrackHeight)

            val scrollableTrackDistance = totalTrackHeight - thumbHeight
            val scrollProgress = if (scrollState.maxValue > 0) {
                (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val thumbTop = scrollProgress * scrollableTrackDistance

            // 3. Dynamic accent thumb with rounded ends
            drawRoundRect(
                color = effectiveThumbColor.copy(alpha = currentAlpha),
                topLeft = Offset(0f, thumbTop),
                size = Size(trackWidth, thumbHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}
