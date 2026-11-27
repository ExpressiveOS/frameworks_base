/*
 * Copyright (C) 2026 ExpressiveOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.panel.component.volume.ui.composable

import android.animation.ValueAnimator
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private val ColumnMinWidth = 52.dp
private val ColumnScrollWidth = 60.dp
private val TrackWidth = 54.dp
private val TrackHeight = 200.dp
private val TrackCornerRadius = 20.dp
private val ColumnSpacing = 12.dp
private val IconButtonTargetSize = 48.dp
private val IconVisualSize = 24.dp
private val HandleWidth = 24.dp
private val HandleHeight = 4.dp
private const val MaxUnscrolledStreams = 5

/**
 * M3 Expressive vertical volume slider columns for the expanded Volume Panel
 * (`Settings.System` key `"volume_panel_vertical_sliders"` == 1).
 */
@Composable
fun VerticalVolumeSliders(
    viewModels: List<SliderViewModel>,
    modifier: Modifier = Modifier,
) {
    require(viewModels.isNotEmpty())
    val states = viewModels.map { it.slider.collectAsStateWithLifecycle().value }
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val requiredUnscrolledWidth =
                ColumnMinWidth * viewModels.size + ColumnSpacing * (viewModels.size - 1)
            val shouldScroll =
                viewModels.size > MaxUnscrolledStreams ||
                    fontScale > 1.3f ||
                    maxWidth < requiredUnscrolledWidth

            if (shouldScroll) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ColumnSpacing),
                    verticalAlignment = Alignment.Top,
                ) {
                    viewModels.forEachIndexed { index, sliderViewModel ->
                        val sliderState = states[index]
                        VerticalVolumeSliderColumn(
                            state = sliderState,
                            onValueChange = { newValue ->
                                sliderViewModel.onValueChanged(sliderState, newValue)
                            },
                            onValueChangeFinished = { sliderViewModel.onValueChangeFinished() },
                            onIconTapped = { sliderViewModel.toggleMuted(sliderState) },
                            modifier = Modifier.width(ColumnScrollWidth),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(ColumnSpacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top,
                ) {
                    viewModels.forEachIndexed { index, sliderViewModel ->
                        val sliderState = states[index]
                        VerticalVolumeSliderColumn(
                            state = sliderState,
                            onValueChange = { newValue ->
                                sliderViewModel.onValueChanged(sliderState, newValue)
                            },
                            onValueChangeFinished = { sliderViewModel.onValueChangeFinished() },
                            onIconTapped = { sliderViewModel.toggleMuted(sliderState) },
                            modifier = Modifier.weight(1f).widthIn(min = ColumnMinWidth),
                        )
                    }
                }
            }
        }

        val disabledMessages =
            states.mapNotNull { state ->
                if (!state.isEnabled) state.disabledMessage else null
            }.distinct()
        disabledMessages.forEach { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialIcon(
                    painter = painterResource(R.drawable.ic_error_outline),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.basicMarquee().clearAndSetSemantics {},
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VerticalVolumeSliderColumn(
    state: SliderState,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onIconTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = MaterialTheme.colorScheme

    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnIconTapped by rememberUpdatedState(onIconTapped)
    val latestState by rememberUpdatedState(state)

    val minVal = state.valueRange.start
    val maxVal = state.valueRange.endInclusive
    val rangeSpan = (maxVal - minVal).takeIf { it > 0f } ?: 1f
    val externalFraction = ((state.value - minVal) / rangeSpan).coerceIn(0f, 1f)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(externalFraction) }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var lastHapticStep by remember { mutableIntStateOf(Int.MIN_VALUE) }

    val targetFraction = if (isDragging) dragFraction else externalFraction
    val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
    val animatedFraction by
        animateFloatAsState(
            targetValue = targetFraction,
            animationSpec =
                if (isDragging || !animatorsEnabled) {
                    snap()
                } else {
                    MaterialTheme.motionScheme.fastSpatialSpec()
                },
            label = "VerticalVolumeSliderFill",
        )

    val trackShape = RoundedCornerShape(TrackCornerRadius)
    val inactiveTrackColor =
        if (state.isEnabled) {
            colorScheme.surfaceContainerHighest
        } else {
            colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
        }
    val activeTrackColor =
        if (state.isEnabled) {
            colorScheme.primary
        } else {
            colorScheme.onSurface.copy(alpha = 0.32f)
        }
    val handleColor =
        if (state.isEnabled) {
            colorScheme.onPrimary.copy(alpha = 0.72f)
        } else {
            colorScheme.onSurface.copy(alpha = 0.38f)
        }
    val isMuted = externalFraction <= 0.001f
    val iconContainerColor =
        when {
            !state.isEnabled -> colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
            isMuted -> colorScheme.surfaceContainerHighest
            else -> colorScheme.secondaryContainer
        }
    val iconTintColor =
        when {
            !state.isEnabled -> colorScheme.onSurface.copy(alpha = 0.45f)
            isMuted -> colorScheme.onSurfaceVariant
            else -> colorScheme.onSecondaryContainer
        }
    val labelColor =
        if (state.isEnabled) {
            colorScheme.onSurface
        } else {
            colorScheme.onSurface.copy(alpha = 0.60f)
        }

    fun emitValueForY(yPx: Float, emitBookendHaptic: Boolean) {
        if (!latestState.isEnabled || trackHeightPx <= 0) return
        val rawFraction = (1f - (yPx / trackHeightPx.toFloat())).coerceIn(0f, 1f)
        val rawValue = minVal + rawFraction * rangeSpan
        val step = latestState.step
        val quantizedValue =
            if (step > 0f) {
                (minVal + ((rawValue - minVal) / step).roundToInt() * step)
                    .coerceIn(minVal, maxVal)
            } else {
                rawValue.coerceIn(minVal, maxVal)
            }
        val quantizedFraction = ((quantizedValue - minVal) / rangeSpan).coerceIn(0f, 1f)
        dragFraction = quantizedFraction

        val stepIndex =
            if (step > 0f) {
                ((quantizedValue - minVal) / step).roundToInt()
            } else {
                (quantizedFraction * 20f).roundToInt()
            }
        if (stepIndex != lastHapticStep) {
            lastHapticStep = stepIndex
            if (isHapticFeedbackEnabled(context)) {
                val atBookend =
                    emitBookendHaptic &&
                        (abs(quantizedFraction) <= 0.001f || abs(quantizedFraction - 1f) <= 0.001f)
                view.performHapticFeedback(
                    if (atBookend) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                )
            }
        }
        latestOnValueChange(quantizedValue)
    }

    val stepsCount =
        if (state.step > 0f) {
            ((maxVal - minVal) / state.step).roundToInt().coerceAtLeast(1) - 1
        } else {
            0
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state is SliderState.Empty) {
            Spacer(modifier = Modifier.size(width = TrackWidth, height = TrackHeight))
            Spacer(modifier = Modifier.size(IconButtonTargetSize))
        } else {
            // Vertical M3 Expressive pill slider track
            Box(
                modifier =
                    Modifier.width(TrackWidth)
                        .height(TrackHeight)
                        .sysuiResTag(state.label)
                        .borderOnFocus(
                            color = colorScheme.secondary,
                            cornerSize = CornerSize(TrackCornerRadius),
                        )
                        .clip(trackShape)
                        .background(inactiveTrackColor)
                        .border(
                            width = 1.dp,
                            color = colorScheme.outlineVariant.copy(alpha = 0.35f),
                            shape = trackShape,
                        )
                        .onSizeChanged { size -> trackHeightPx = size.height }
                        .semantics(mergeDescendants = true) {
                            contentDescription =
                                state.a11yContentDescription.ifEmpty { state.label }
                            stateDescription =
                                state.a11yStateDescription
                                    ?: "${(externalFraction * 100f).roundToInt()}%"
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    current = state.value,
                                    range = state.valueRange,
                                    steps = stepsCount.coerceAtLeast(0),
                                )
                            if (!state.isEnabled) {
                                disabled()
                            }
                            setProgress(label = state.label) { targetValue ->
                                if (!latestState.isEnabled) {
                                    false
                                } else {
                                    val clamped = targetValue.coerceIn(minVal, maxVal)
                                    latestOnValueChange(clamped)
                                    latestOnValueChangeFinished()
                                    true
                                }
                            }
                        }
                        .pointerInput(state.isEnabled, minVal, maxVal, state.step) {
                            if (!state.isEnabled) return@pointerInput
                            detectTapGestures(
                                onTap = { offset ->
                                    lastHapticStep = Int.MIN_VALUE
                                    emitValueForY(offset.y, emitBookendHaptic = true)
                                    latestOnValueChangeFinished()
                                }
                            )
                        }
                        .pointerInput(state.isEnabled, minVal, maxVal, state.step) {
                            if (!state.isEnabled) return@pointerInput
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    lastHapticStep = Int.MIN_VALUE
                                    emitValueForY(offset.y, emitBookendHaptic = false)
                                },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    emitValueForY(change.position.y, emitBookendHaptic = true)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    latestOnValueChangeFinished()
                                },
                                onDragCancel = {
                                    isDragging = false
                                    latestOnValueChangeFinished()
                                },
                            )
                        },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (animatedFraction > 0.001f) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .fillMaxHeight(animatedFraction.coerceIn(0f, 1f))
                                .background(activeTrackColor),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (animatedFraction >= 0.08f) {
                            Box(
                                modifier =
                                    Modifier.padding(top = 8.dp)
                                        .width(HandleWidth)
                                        .height(HandleHeight)
                                        .clip(CircleShape)
                                        .background(handleColor)
                            )
                        }
                    }
                }
            }

            // >= 48dp interactive mute / stream icon button
            Box(
                modifier =
                    Modifier.size(IconButtonTargetSize)
                        .clip(CircleShape)
                        .background(iconContainerColor)
                        .clickable(
                            enabled = state.isMutable || (!state.isEnabled && state.disabledMessage != null),
                            role = Role.Button,
                            onClickLabel = state.a11yClickDescription ?: state.label,
                            onClick = {
                                if (!latestState.isEnabled) {
                                    latestState.disabledMessage?.let { msg ->
                                        view.announceForAccessibility(msg)
                                    }
                                    return@clickable
                                }
                                if (isHapticFeedbackEnabled(context)) {
                                    view.performHapticFeedback(
                                        HapticFeedbackConstants.CONTEXT_CLICK
                                    )
                                }
                                latestOnIconTapped()
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                state.icon?.let { icon ->
                    Icon(
                        icon = icon,
                        tint = iconTintColor,
                        modifier = Modifier.size(IconVisualSize),
                    )
                }
            }

            Text(
                text = state.label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            )
        }
    }
}

private fun isHapticFeedbackEnabled(context: Context): Boolean {
    return Settings.System.getIntForUser(
        context.contentResolver,
        Settings.System.HAPTIC_FEEDBACK_ENABLED,
        1,
        UserHandle.USER_CURRENT,
    ) != 0
}
