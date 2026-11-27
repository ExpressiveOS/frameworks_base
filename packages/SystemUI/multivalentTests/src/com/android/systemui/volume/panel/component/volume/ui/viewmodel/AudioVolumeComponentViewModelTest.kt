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

package com.android.systemui.volume.panel.component.volume.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.settings.data.repository.fakeSystemSettingsRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.data.repository.userAwareSystemSettingsRepository
import com.android.systemui.util.settings.fakeSettings
import com.android.systemui.volume.domain.interactor.audioModeInteractor
import com.android.systemui.volume.mediaDeviceSessionInteractor
import com.android.systemui.volume.mediaOutputInteractor
import com.android.systemui.volume.panel.component.volume.domain.interactor.audioSlidersInteractor
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.audioSharingStreamSliderViewModelFactory
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.audioStreamSliderViewModelFactory
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.castVolumeSliderViewModelFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AudioVolumeComponentViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    @Test
    fun isVerticalSliders_defaultsToFalse() =
        with(kosmos) {
            testScope.runTest {
                val underTest = audioVolumeComponentViewModel
                val isVertical by collectLastValue(underTest.isVerticalSliders)
                runCurrent()

                assertThat(isVertical).isFalse()
            }
        }

    @Test
    fun isVerticalSliders_reactsToSystemSettingsToggle() =
        with(kosmos) {
            testScope.runTest {
                val underTest = audioVolumeComponentViewModel
                val isVertical by collectLastValue(underTest.isVerticalSliders)
                runCurrent()
                assertThat(isVertical).isFalse()

                fakeSystemSettingsRepository.setInt(
                    AudioVolumeComponentViewModel.KEY_VOLUME_PANEL_VERTICAL_SLIDERS,
                    1,
                )
                runCurrent()
                assertThat(isVertical).isTrue()

                fakeSystemSettingsRepository.setInt(
                    AudioVolumeComponentViewModel.KEY_VOLUME_PANEL_VERTICAL_SLIDERS,
                    0,
                )
                runCurrent()
                assertThat(isVertical).isFalse()
            }
        }

    @Test
    fun isVerticalSliders_reactsToUserAwareSystemSettingsObserverFlow() =
        with(kosmos) {
            testScope.runTest {
                val underTest =
                    AudioVolumeComponentViewModel(
                        scope = testScope.backgroundScope,
                        mediaOutputInteractor = mediaOutputInteractor,
                        mediaDeviceSessionInteractor = mediaDeviceSessionInteractor,
                        streamSliderViewModelFactory = audioStreamSliderViewModelFactory,
                        castVolumeSliderViewModelFactory = castVolumeSliderViewModelFactory,
                        audioSharingStreamSliderViewModelFactory =
                            audioSharingStreamSliderViewModelFactory,
                        audioModeInteractor = audioModeInteractor,
                        streamsInteractor = audioSlidersInteractor,
                        systemSettingsRepository = userAwareSystemSettingsRepository,
                    )
                val isVertical by collectLastValue(underTest.isVerticalSliders)
                runCurrent()
                assertThat(isVertical).isFalse()

                val userId = fakeUserRepository.getSelectedUserInfo().id
                fakeSettings.putIntForUser(
                    AudioVolumeComponentViewModel.KEY_VOLUME_PANEL_VERTICAL_SLIDERS,
                    1,
                    userId,
                )
                runCurrent()
                assertThat(isVertical).isTrue()

                fakeSettings.putIntForUser(
                    AudioVolumeComponentViewModel.KEY_VOLUME_PANEL_VERTICAL_SLIDERS,
                    0,
                    userId,
                )
                runCurrent()
                assertThat(isVertical).isFalse()
            }
        }
}
