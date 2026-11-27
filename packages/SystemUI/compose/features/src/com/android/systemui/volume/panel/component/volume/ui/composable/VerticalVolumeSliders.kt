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

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.gradient.GradientColors
import com.android.systemui.gradient.rememberGradientColors
import com.android.systemui.gradient.rememberGradientEnabled
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon

private val ColumnMinWidth = 60.dp
private val ColumnScrollWidth = 76.dp
private val SliderWidth = 52.dp
private val SliderHeight = 216.dp
private val TrackWidth = 40.dp
private val TrackCornerRadius = 12.dp
private val ThumbWidth = 52.dp
private val ThumbHeight = 4.dp
private val InsideTrackIconSize = 20.dp
private val LabelTargetMinSize = 48.dp
private val ColumnSpacing = 4.dp
private val ScrollColumnSpacing = 8.dp
private const val MaxUnscrolledStreams = 5

/**
 * Native Android 17 M3 Expressive vertical volume slider columns for the expanded Volume Panel
 * (`Settings.System` key `"volume_panel_vertical_sliders"` == 1).
 *
 * Uses the exact same SystemUI [Slider], [SliderTrack], [SliderDefaults.Thumb], gradient tokens,
 * and [VolumeHapticsConfigsProvider] as horizontal [VolumeSlider] and collapsed
 * `VolumeDialogSliderViewBinder`, rotated 90 degrees.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VerticalVolumeSliders(
    viewModels: List<SliderViewModel>,
    modifier: Modifier = Modifier,
) {
    require(viewModels.isNotEmpty())
    val states = viewModels.map { it.slider.collectAsStateWithLifecycle().value }
    val fontScale = LocalDensity.current.fontScale
    val isGradientEnabled = rememberGradientEnabled(Settings.System.VOLUME_SLIDER_GRADIENT)
    val gradient: GradientColors? = rememberGradientColors().takeIf { isGradientEnabled }
    val baseSliderColors = VolumeSliderColors.Defaults
    val sliderColors =
        if (gradient != null) {
            baseSliderColors.copy(activeTrackColor = Color.Transparent)
        } else {
            baseSliderColors
        }
    val thumbColors =
        if (gradient != null) {
            sliderColors.copy(thumbColor = gradient.startColor)
        } else {
            sliderColors
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val requiredUnscrolledWidth =
                ColumnMinWidth * viewModels.size + ColumnSpacing * (viewModels.size - 1)
            val shouldScroll =
                viewModels.size > MaxUnscrolledStreams ||
                    fontScale > 1.25f ||
                    maxWidth < requiredUnscrolledWidth

            if (shouldScroll) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ScrollColumnSpacing),
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
                            hapticsViewModelFactory =
                                sliderViewModel.getSliderHapticsViewModelFactory(),
                            sliderColors = sliderColors,
                            thumbColors = thumbColors,
                            gradient = gradient,
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
                            hapticsViewModelFactory =
                                sliderViewModel.getSliderHapticsViewModelFactory(),
                            sliderColors = sliderColors,
                            thumbColors = thumbColors,
                            gradient = gradient,
                            modifier = Modifier.weight(1f).widthIn(min = ColumnMinWidth),
                        )
                    }
                }
            }
        }

        val disabledMessages =
            states
                .mapNotNull { state -> if (!state.isEnabled) state.disabledMessage else null }
                .distinct()
        disabledMessages.forEach { message ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .testTag(VolumeSlidersMotionTestKeys.DISABLED_MESSAGE_TAG),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VerticalVolumeSliderColumn(
    state: SliderState,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onIconTapped: () -> Unit,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory?,
    sliderColors: SliderColors,
    thumbColors: SliderColors,
    gradient: GradientColors?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isMuted = (state.value - state.valueRange.start) <= 0.001f
    val labelColor =
        if (state.isEnabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state is SliderState.Empty) {
            Spacer(modifier = Modifier.size(width = SliderWidth, height = SliderHeight))
            Spacer(modifier = Modifier.height(LabelTargetMinSize))
        } else {
            Slider(
                value = state.value,
                valueRange = state.valueRange,
                onValueChanged = onValueChange,
                onValueChangeFinished = { onValueChangeFinished() },
                colors = sliderColors,
                isEnabled = state.isEnabled,
                isVertical = true,
                isReverseDirection = true,
                stepDistance = state.step,
                accessibilityParams =
                    AccessibilityParams(
                        contentDescription = state.a11yContentDescription.ifEmpty { state.label },
                        stateDescription = state.a11yStateDescription,
                    ),
                track = { sliderState ->
                    SliderTrack(
                        sliderState = sliderState,
                        colors = sliderColors,
                        isEnabled = state.isEnabled,
                        isVertical = true,
                        trackSize = TrackWidth,
                        trackCornerSize = TrackCornerRadius,
                        gradient = gradient,
                        activeTrackEndIcon =
                            state.icon?.let { icon ->
                                { iconsState ->
                                    SliderIcon(
                                        icon = {
                                            Icon(
                                                icon = icon,
                                                tint = null,
                                                modifier =
                                                    Modifier.size(InsideTrackIconSize)
                                                        .testTag(
                                                            VolumeSlidersMotionTestKeys
                                                                .ACTIVE_ICON_TAG
                                                        ),
                                            )
                                        },
                                        isVisible = !iconsState.isInactiveTrackEndIconVisible,
                                    )
                                }
                            },
                        inactiveTrackEndIcon =
                            state.icon?.let { icon ->
                                { iconsState ->
                                    SliderIcon(
                                        icon = {
                                            Icon(
                                                icon = icon,
                                                tint = null,
                                                modifier =
                                                    Modifier.size(InsideTrackIconSize)
                                                        .testTag(
                                                            VolumeSlidersMotionTestKeys
                                                                .INACTIVE_ICON_TAG
                                                        ),
                                            )
                                        },
                                        isVisible = iconsState.isInactiveTrackEndIconVisible,
                                    )
                                }
                            },
                    )
                },
                thumb = { sliderState, interactionSource ->
                    SliderDefaults.Thumb(
                        sliderState = sliderState,
                        interactionSource = interactionSource,
                        enabled = state.isEnabled,
                        colors = thumbColors,
                        thumbSize = DpSize(ThumbWidth, ThumbHeight),
                    )
                },
                haptics =
                    hapticsViewModelFactory?.let {
                        Haptics.Enabled(
                            hapticsViewModelFactory = it,
                            hapticConfigs =
                                VolumeHapticsConfigsProvider.continuousConfigs(state.hapticFilter),
                            orientation = Orientation.Vertical,
                        )
                    } ?: Haptics.Disabled,
                modifier =
                    Modifier.width(SliderWidth)
                        .height(SliderHeight)
                        .borderOnFocus(
                            color = MaterialTheme.colorScheme.secondary,
                            cornerSize = CornerSize(TrackCornerRadius),
                        )
                        .sysuiResTag(state.label),
            )

            val canToggleOrAnnounce =
                state.isMutable || (!state.isEnabled && state.disabledMessage != null)
            val defaultMuteDescription =
                if (state.isMutable) {
                    stringResource(
                        if (isMuted) {
                            R.string.volume_panel_unmute_stream
                        } else {
                            R.string.volume_panel_mute_stream
                        },
                        state.label,
                    )
                } else {
                    state.label
                }
            val labelActionDescription =
                state.a11yClickDescription?.takeIf { it.isNotBlank() } ?: defaultMuteDescription

            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minWidth = LabelTargetMinSize, minHeight = LabelTargetMinSize)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics(mergeDescendants = true) {
                            contentDescription = labelActionDescription
                            if (canToggleOrAnnounce) {
                                role = Role.Button
                            }
                        }
                        .then(
                            if (canToggleOrAnnounce) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = labelActionDescription,
                                    onClick = {
                                        if (!state.isEnabled) {
                                            state.disabledMessage?.let { msg ->
                                                view.announceForAccessibility(msg)
                                            }
                                            return@clickable
                                        }
                                        if (isHapticFeedbackEnabled(context)) {
                                            view.performHapticFeedback(
                                                HapticFeedbackConstants.CONTEXT_CLICK
                                            )
                                        }
                                        onIconTapped()
                                    },
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
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
