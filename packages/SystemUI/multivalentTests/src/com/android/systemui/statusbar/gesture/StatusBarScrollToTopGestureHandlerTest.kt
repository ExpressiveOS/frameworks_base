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

package com.android.systemui.statusbar.gesture

import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.content.Context
import android.content.pm.UserInfo
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.IslandState
import com.android.systemui.axdynamicbar.model.IslandUiState
import com.android.systemui.axdynamicbar.ui.AxDynamicBarStatusBarExpansion
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel
import com.android.systemui.statusbar.domain.interactor.ScrollToTopInteractor
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.scrolltotop.ScrollToTop
import com.google.common.truth.Truth.assertThat
import android.os.Looper
import dagger.Lazy
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarScrollToTopGestureHandlerTest : SysuiTestCase() {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeClock = FakeSystemClock()
    private val fakeExecutor = FakeExecutor(fakeClock)
    private val fakeSettings = FakeSettings(testDispatcher)
    private val fakeUserTracker = FakeUserTracker(_userId = PRIMARY_USER_ID)
    private val scrollRecorder = RecordingScrollToTop()
    private val scrollToTopInteractor = ScrollToTopInteractor(Optional.of(scrollRecorder))

    private val commandQueue = mock<CommandQueue>()
    private val statusBarStateController = mock<StatusBarStateController>()
    private val keyguardStateController = mock<KeyguardStateController>()
    private val panelExpansionInteractor = mock<PanelExpansionInteractor>()
    private val shadeInteractor = mock<ShadeInteractor>()
    private val headsUpManager = mock<HeadsUpManager>()
    private val accessibilityManager = mock<AccessibilityManager>()
    private val axDynamicBarInteractor = mock<AxDynamicBarInteractor>()
    private val axDynamicBarSettings = mock<AxDynamicBarSettings>()
    private val axDynamicBarExpansion = mock<AxDynamicBarStatusBarExpansion>()
    private val ongoingActivityChipsViewModel = mock<OngoingActivityChipsViewModel>()

    private val anyExpandedFlow = MutableStateFlow(false)
    private val qsExpandedFlow = MutableStateFlow(false)
    private val userInteractingFlow = MutableStateFlow(false)
    private val axEnabledFlow = MutableStateFlow(false)
    private val axOnKeyguardFlow = MutableStateFlow(false)
    private val axUiStateFlow = MutableStateFlow(IslandUiState())
    private val axExpandedFlow = MutableStateFlow(false)
    private val ongoingChipsFlow = MutableStateFlow(MultipleOngoingActivityChipsModel())

    private lateinit var statusBarWindowStateController: StatusBarWindowStateController
    private lateinit var commandQueueCallback: CommandQueue.Callbacks
    private lateinit var statusBarView: TestPhoneStatusBarView
    private lateinit var userSwitcherContainer: FrameLayout
    private lateinit var underTest: StatusBarScrollToTopGestureHandler

    @Before
    fun setUp() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        whenever(statusBarStateController.isDozing).thenReturn(false)
        whenever(statusBarStateController.isDreaming).thenReturn(false)
        whenever(statusBarStateController.isPulsing).thenReturn(false)
        whenever(statusBarStateController.isExpanded).thenReturn(false)

        whenever(keyguardStateController.isShowing).thenReturn(false)
        whenever(keyguardStateController.isOccluded).thenReturn(false)
        whenever(keyguardStateController.isPrimaryBouncerShowing).thenReturn(false)
        whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(false)

        whenever(panelExpansionInteractor.isFullyCollapsed).thenReturn(true)
        whenever(panelExpansionInteractor.isTracking).thenReturn(false)
        whenever(panelExpansionInteractor.isCollapsing).thenReturn(false)

        whenever(shadeInteractor.isAnyExpanded).thenReturn(anyExpandedFlow)
        whenever(shadeInteractor.isQsExpanded).thenReturn(qsExpandedFlow)
        whenever(shadeInteractor.isUserInteracting).thenReturn(userInteractingFlow)

        whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(false)
        whenever(headsUpManager.isHeadsUpAnimatingAwayValue()).thenReturn(false)
        whenever(accessibilityManager.isTouchExplorationEnabled).thenReturn(false)

        whenever(axDynamicBarSettings.isEnabled).thenReturn(axEnabledFlow)
        whenever(axDynamicBarInteractor.settings).thenReturn(axDynamicBarSettings)
        whenever(axDynamicBarInteractor.isOnKeyguard).thenReturn(axOnKeyguardFlow)
        whenever(axDynamicBarInteractor.uiState).thenReturn(axUiStateFlow)
        whenever(axDynamicBarExpansion.isExpanded).thenReturn(axExpandedFlow)
        whenever(ongoingActivityChipsViewModel.chips).thenReturn(ongoingChipsFlow)

        statusBarWindowStateController = StatusBarWindowStateController(DISPLAY_ID, commandQueue)
        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        commandQueueCallback = callbackCaptor.value
        commandQueueCallback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

        // Construct PhoneStatusBarView programmatically so Robolectric does not inflate
        // custom Typeface/drawable views (e.g. LyricTextView) from R.layout.status_bar.
        statusBarView = TestPhoneStatusBarView(context)
        val emptyInsets = mock<WindowInsets>()
        whenever(emptyInsets.displayCutout).thenReturn(null)
        statusBarView.customRootWindowInsets = emptyInsets

        userSwitcherContainer =
            FrameLayout(context).apply {
                id = R.id.user_switcher_container
                visibility = View.GONE
            }
        statusBarView.addView(
            userSwitcherContainer,
            FrameLayout.LayoutParams(100, 40).apply {
                leftMargin = 700
                topMargin = 10
            },
        )
        layoutStatusBarView(width = STATUS_BAR_WIDTH, height = STATUS_BAR_HEIGHT)

        setSetting(StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP, 1)
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_FULL,
        )
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 1)
        setSetting(StatusBarScrollToTopGestureHandler.KEY_TRIPLE_TAP_FREEFORM, 0)

        underTest =
            StatusBarScrollToTopGestureHandler(
                displayId = DISPLAY_ID,
                context = context,
                statusBarWindowStateController = Lazy { statusBarWindowStateController },
                ongoingActivityChipsViewModel = Lazy { ongoingActivityChipsViewModel },
                scrollToTopInteractor = Lazy { scrollToTopInteractor },
                systemSettings = fakeSettings,
                userTracker = fakeUserTracker,
                mainExecutor = fakeExecutor,
                statusBarStateController = Lazy { statusBarStateController },
                keyguardStateController = Lazy { keyguardStateController },
                panelExpansionInteractor = Lazy { panelExpansionInteractor },
                shadeInteractor = Lazy { shadeInteractor },
                headsUpManager = Lazy { headsUpManager },
                accessibilityManager = Lazy { accessibilityManager },
                axDynamicBarInteractor = Lazy { axDynamicBarInteractor },
                axDynamicBarStatusBarExpansion = Lazy { axDynamicBarExpansion },
            )
    }

    @After
    fun tearDown() {
        if (::underTest.isInitialized) {
            underTest.stop()
        }
    }

    @Test(timeout = 10_000)
    fun init_onBackgroundThreadWithoutLooper_doesNotInstantiateUiDependenciesOrCrash() {
        val failure = AtomicReference<Throwable?>(null)
        val bgThread =
            Thread({
                try {
                    assertThat(Looper.myLooper()).isNull()
                    val handler =
                        StatusBarScrollToTopGestureHandler(
                            displayId = DISPLAY_ID,
                            context = context,
                            statusBarWindowStateController =
                                Lazy { error("statusBarWindowStateController resolved eagerly") },
                            ongoingActivityChipsViewModel =
                                Lazy { error("ongoingActivityChipsViewModel resolved eagerly") },
                            scrollToTopInteractor =
                                Lazy { error("scrollToTopInteractor resolved eagerly") },
                            systemSettings = fakeSettings,
                            userTracker = fakeUserTracker,
                            mainExecutor = fakeExecutor,
                            statusBarStateController =
                                Lazy { error("statusBarStateController resolved eagerly") },
                            keyguardStateController =
                                Lazy { error("keyguardStateController resolved eagerly") },
                            panelExpansionInteractor =
                                Lazy { error("panelExpansionInteractor resolved eagerly") },
                            shadeInteractor =
                                Lazy { error("shadeInteractor resolved eagerly") },
                            headsUpManager =
                                Lazy { error("headsUpManager resolved eagerly") },
                            accessibilityManager =
                                Lazy { error("accessibilityManager resolved eagerly") },
                            axDynamicBarInteractor =
                                Lazy { error("axDynamicBarInteractor resolved eagerly") },
                            axDynamicBarStatusBarExpansion =
                                Lazy { error("axDynamicBarStatusBarExpansion resolved eagerly") },
                        )
                    handler.stop()
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }, "SystemUIBg-Test")
        bgThread.start()
        bgThread.join(5_000L)
        assertThat(bgThread.isAlive).isFalse()
        assertThat(failure.get()).isNull()
    }

    @Test(timeout = 10_000)
    fun singleTap_whenDoubleTapAndTripleTapDisabled_firesImmediatelyOnActionUp() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 0)
        setSetting(StatusBarScrollToTopGestureHandler.KEY_TRIPLE_TAP_FREEFORM, 0)

        val t0 = fakeClock.uptimeMillis()
        sendTap(x = 540f, y = 24f, downTime = t0, upTime = t0 + 40L)

        // Fires synchronously at 0ms without needing any executor clock advance.
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 540)
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun singleTap_whenDoubleTapSleepEnabled_defersBy220ms() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 1)
        setSetting(StatusBarScrollToTopGestureHandler.KEY_TRIPLE_TAP_FREEFORM, 0)

        val t0 = fakeClock.uptimeMillis()
        sendTap(x = 420f, y = 24f, downTime = t0, upTime = t0 + 40L)

        assertThat(scrollRecorder.calls).isEmpty()
        assertThat(fakeExecutor.numPending()).isEqualTo(1)

        fakeClock.advanceTime(219L)
        assertThat(scrollRecorder.calls).isEmpty()

        fakeClock.advanceTime(1L)
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 420)
    }

    @Test(timeout = 10_000)
    fun singleTap_whenTripleTapFreeformEnabledOnly_defersBy220ms() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 0)
        setSetting(StatusBarScrollToTopGestureHandler.KEY_TRIPLE_TAP_FREEFORM, 1)

        val t0 = fakeClock.uptimeMillis()
        sendTap(x = 600f, y = 24f, downTime = t0, upTime = t0 + 40L)

        assertThat(scrollRecorder.calls).isEmpty()
        fakeClock.advanceTime(220L)
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 600)
    }

    @Test(timeout = 10_000)
    fun doubleTap_120msGap_cancelsTap1AndSuppressesTap2ActionUp_thenRecoversAfterBurstWindow() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 1)

        val t0 = fakeClock.uptimeMillis()
        // Tap 1 at t0..t0+40 -> schedules 220ms runnable for t0+260.
        sendTap(x = 540f, y = 24f, downTime = t0, upTime = t0 + 40L)
        assertThat(fakeExecutor.numPending()).isEqualTo(1)

        // Advance 120ms (< 220ms) and perform Tap 2 (t0+160..t0+200).
        val tap2Down = t0 + 160L
        sendTap(x = 542f, y = 25f, downTime = tap2Down, upTime = tap2Down + 40L)

        assertThat(fakeExecutor.numPending()).isEqualTo(0)
        fakeClock.advanceTime(1_000L)
        assertThat(scrollRecorder.calls).isEmpty()

        // After > 250ms idle, a fresh single tap fires normally.
        val nextDown = fakeClock.uptimeMillis()
        sendTap(x = 300f, y = 24f, downTime = nextDown, upTime = nextDown + 40L)
        fakeClock.advanceTime(220L)
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 300)
    }

    @Test(timeout = 10_000)
    fun tripleTap_suppressesAllThreeTaps() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 1)
        setSetting(StatusBarScrollToTopGestureHandler.KEY_TRIPLE_TAP_FREEFORM, 1)

        val t0 = fakeClock.uptimeMillis()
        // Tap 1: t0..t0+40
        sendTap(x = 500f, y = 24f, downTime = t0, upTime = t0 + 40L)
        // Tap 2 (120ms gap): t0+160..t0+200
        sendTap(x = 500f, y = 24f, downTime = t0 + 160L, upTime = t0 + 200L)
        // Tap 3 (120ms gap): t0+320..t0+360
        sendTap(x = 500f, y = 24f, downTime = t0 + 320L, upTime = t0 + 360L)

        fakeClock.advanceTime(1_000L)
        assertThat(scrollRecorder.calls).isEmpty()
    }

    @Test(timeout = 10_000)
    fun dragExceedingTouchSlop_dxOrDy_andLongPress_cancelScrollToTop() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 0)
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        // Vertical swipe-down (shade pulldown): dy > touchSlop
        val t1 = fakeClock.uptimeMillis()
        dispatchEvent(MotionEvent.ACTION_DOWN, 540f, 20f, t1, t1)
        dispatchEvent(MotionEvent.ACTION_MOVE, 540f, 20f + slop + 2f, t1, t1 + 20L)
        dispatchEvent(MotionEvent.ACTION_UP, 540f, 20f + slop + 2f, t1, t1 + 40L)
        assertThat(scrollRecorder.calls).isEmpty()

        // Horizontal drag (brightness control): dx > touchSlop
        val t2 = fakeClock.uptimeMillis() + 100L
        dispatchEvent(MotionEvent.ACTION_DOWN, 540f, 20f, t2, t2)
        dispatchEvent(MotionEvent.ACTION_MOVE, 540f + slop + 2f, 20f, t2, t2 + 20L)
        dispatchEvent(MotionEvent.ACTION_UP, 540f + slop + 2f, 20f, t2, t2 + 40L)
        assertThat(scrollRecorder.calls).isEmpty()

        // Long press (>= getLongPressTimeout)
        val t3 = fakeClock.uptimeMillis() + 100L
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        sendTap(x = 540f, y = 20f, downTime = t3, upTime = t3 + longPressMs)
        assertThat(scrollRecorder.calls).isEmpty()

        // ACTION_CANCEL
        val t4 = fakeClock.uptimeMillis() + 100L
        dispatchEvent(MotionEvent.ACTION_DOWN, 540f, 20f, t4, t4)
        dispatchEvent(MotionEvent.ACTION_CANCEL, 540f, 20f, t4, t4 + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
    }

    @Test(timeout = 10_000)
    fun zones_fullCenterStartEnd_rtlAndCutoutUnion_filterAccurately() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 0)
        val density = statusBarView.resources.displayMetrics.density
        val bevelPx = 8f * density

        // Zone 0 (Full): rejects 1px top edge guard and 8dp palm bevels, accepts interior.
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_FULL,
        )
        var t = fakeClock.uptimeMillis()
        sendTap(x = 540f, y = 0.5f, downTime = t, upTime = t + 30L) // top 1px guard
        t += 100L
        sendTap(x = (bevelPx - 1f).coerceAtLeast(0f), y = 20f, downTime = t, upTime = t + 30L)
        t += 100L
        sendTap(x = STATUS_BAR_WIDTH - bevelPx + 1f, y = 20f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()

        t += 100L
        sendTap(x = bevelPx + 2f, y = 20f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).hasSize(1)
        scrollRecorder.calls.clear()

        // Zone 1 (Center / Cutout): 0.33w..0.67w unioned with boundingRectTop ± 16dp.
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_CENTER,
        )
        t += 100L
        sendTap(x = 200f, y = 20f, downTime = t, upTime = t + 30L) // outside 0.33*1080=356.4
        assertThat(scrollRecorder.calls).isEmpty()

        t += 100L
        sendTap(x = 540f, y = 20f, downTime = t, upTime = t + 30L) // inside center
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 540)
        scrollRecorder.calls.clear()

        // Attach an off-center punch-hole cutout at x=190..240 -> widened region now succeeds!
        val cutout = mock<DisplayCutout>()
        whenever(cutout.boundingRectTop).thenReturn(Rect(190, 0, 240, 50))
        val cutoutInsets = mock<WindowInsets>()
        whenever(cutoutInsets.displayCutout).thenReturn(cutout)
        statusBarView.customRootWindowInsets = cutoutInsets

        val marginPx = 16f * density
        val widenedEdgeX = (240f + marginPx - 1f).coerceAtMost(350f)
        t += 100L
        sendTap(x = widenedEdgeX, y = 20f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to widenedEdgeX.toInt())
        scrollRecorder.calls.clear()

        // Zone 2 (Start = Left in LTR, Right in RTL) & Zone 3 (End = Right in LTR, Left in RTL).
        statusBarView.explicitLayoutDirection = View.LAYOUT_DIRECTION_LTR
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_START,
        )
        t += 100L
        sendTap(x = 200f, y = 20f, downTime = t, upTime = t + 30L) // Left in LTR -> allowed
        t += 100L
        sendTap(x = 900f, y = 20f, downTime = t, upTime = t + 30L) // Right in LTR -> rejected
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 200)
        scrollRecorder.calls.clear()

        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_END,
        )
        t += 100L
        sendTap(x = 200f, y = 20f, downTime = t, upTime = t + 30L) // Left in LTR -> rejected
        t += 100L
        sendTap(x = 900f, y = 20f, downTime = t, upTime = t + 30L) // Right in LTR -> allowed
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 900)
        scrollRecorder.calls.clear()

        // Flip layout direction to RTL -> Zone 2 is now Right, Zone 3 is now Left!
        statusBarView.explicitLayoutDirection = View.LAYOUT_DIRECTION_RTL
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_START,
        )
        t += 100L
        sendTap(x = 200f, y = 20f, downTime = t, upTime = t + 30L) // Left in RTL -> rejected
        t += 100L
        sendTap(x = 900f, y = 20f, downTime = t, upTime = t + 30L) // Right in RTL -> allowed
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 900)
        scrollRecorder.calls.clear()

        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP_ZONE,
            StatusBarScrollToTopGestureHandler.ZONE_END,
        )
        t += 100L
        sendTap(x = 200f, y = 20f, downTime = t, upTime = t + 30L) // Left in RTL -> allowed
        t += 100L
        sendTap(x = 900f, y = 20f, downTime = t, upTime = t + 30L) // Right in RTL -> rejected
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 200)
    }

    @Test(timeout = 10_000)
    fun interactiveChipExclusions_axDynamicBarOngoingChipUserSwitcher_suppressOnlyInsideChipBounds() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 0)
        var t = fakeClock.uptimeMillis()

        // 1. AxDynamicBar active chip exclusion
        axEnabledFlow.value = true
        axUiStateFlow.value =
            IslandUiState(
                events = listOf(IslandEvent.Torch(level = 1, maxLevel = 1)),
                islandState = IslandState.CHIP,
            )
        underTest.updateAxDynamicBarChipBounds(Rect(120, 4, 240, 50))

        sendTap(x = 180f, y = 24f, downTime = t, upTime = t + 30L) // inside AxDynamicBar pill
        assertThat(scrollRecorder.calls).isEmpty()

        t += 100L
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L) // outside pill -> scrolls!
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 540)
        scrollRecorder.calls.clear()

        axUiStateFlow.value = IslandUiState(islandState = IslandState.HIDDEN)
        underTest.updateAxDynamicBarChipBounds(null)

        // 2. OngoingActivityChips exclusion
        val activeOngoingChip =
            OngoingActivityChipModel.Active(
                key = "call_chip",
                icon = null,
                content = OngoingActivityChipModel.Content.Text("01:23"),
                colors = ColorsModel.SystemThemed,
                clickBehavior = OngoingActivityChipModel.ClickBehavior.None,
            )
        ongoingChipsFlow.value =
            MultipleOngoingActivityChipsModel(active = listOf(activeOngoingChip))
        underTest.updateOngoingActivityChipsBounds(Rect(140, 4, 280, 50))

        t += 100L
        sendTap(x = 200f, y = 24f, downTime = t, upTime = t + 30L) // inside ongoing chip
        assertThat(scrollRecorder.calls).isEmpty()

        ongoingChipsFlow.value = MultipleOngoingActivityChipsModel()
        underTest.updateOngoingActivityChipsBounds(null)

        // 3. Dynamic Island pill bounds exclusion
        underTest.updateDynamicIslandBounds(Rect(440, 4, 640, 50))
        t += 100L
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        underTest.updateDynamicIslandBounds(null)

        // 4. user_switcher_container exclusion when visible
        userSwitcherContainer.visibility = View.VISIBLE
        layoutStatusBarView(width = STATUS_BAR_WIDTH, height = STATUS_BAR_HEIGHT)
        val loc = IntArray(2)
        userSwitcherContainer.getLocationOnScreen(loc)
        val switcherCenterX = loc[0] + userSwitcherContainer.width / 2f
        val switcherCenterY = (loc[1] + userSwitcherContainer.height / 2f).coerceAtLeast(2f)

        t += 100L
        sendTap(x = switcherCenterX, y = switcherCenterY, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()

        // When hidden (GONE), tapping the same spot scrolls normally.
        userSwitcherContainer.visibility = View.GONE
        t += 100L
        sendTap(x = switcherCenterX, y = switcherCenterY, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to switcherCenterX.toInt())
    }

    @Test(timeout = 10_000)
    fun contextGuards_andLiveUserSettingsSwitch_suppressWhenIneligible() {
        setSetting(StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP, 0)
        var t = fakeClock.uptimeMillis()

        // 1. Keyguard state
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)

        // 2. Dozing
        t += 100L
        whenever(statusBarStateController.isDozing).thenReturn(true)
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        whenever(statusBarStateController.isDozing).thenReturn(false)

        // 3. Expanded shade / QS / AxDynamicBar expanded panel
        t += 100L
        whenever(panelExpansionInteractor.isFullyCollapsed).thenReturn(false)
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        whenever(panelExpansionInteractor.isFullyCollapsed).thenReturn(true)

        t += 100L
        axExpandedFlow.value = true
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        axExpandedFlow.value = false

        // 4. Pinned HUN
        t += 100L
        whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(true)
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(false)

        // 5. TalkBack touch exploration
        t += 100L
        whenever(accessibilityManager.isTouchExplorationEnabled).thenReturn(true)
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        whenever(accessibilityManager.isTouchExplorationEnabled).thenReturn(false)

        // 6. Immersive hidden status bar window
        t += 100L
        commandQueueCallback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_HIDDEN)
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()
        commandQueueCallback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

        // 7. User switch to secondary user with feature toggled OFF, then back to primary user!
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_SCROLL_TO_TOP,
            0,
            userId = SECONDARY_USER_ID,
        )
        setSetting(
            StatusBarScrollToTopGestureHandler.KEY_DOUBLE_TAP_SLEEP,
            0,
            userId = SECONDARY_USER_ID,
        )
        fakeUserTracker.set(
            listOf(UserInfo(PRIMARY_USER_ID, "Owner", 0), UserInfo(SECONDARY_USER_ID, "Guest", 0)),
            selectedUserIndex = 1,
        )
        assertThat(underTest.shouldAllowTap(540f, 24f, statusBarView)).isFalse()
        t += 100L
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).isEmpty()

        fakeUserTracker.set(
            listOf(UserInfo(PRIMARY_USER_ID, "Owner", 0), UserInfo(SECONDARY_USER_ID, "Guest", 0)),
            selectedUserIndex = 0,
        )
        assertThat(underTest.shouldAllowTap(540f, 24f, statusBarView)).isTrue()
        t += 100L
        sendTap(x = 540f, y = 24f, downTime = t, upTime = t + 30L)
        assertThat(scrollRecorder.calls).containsExactly(DISPLAY_ID to 540)
    }

    private fun setSetting(key: String, value: Int, userId: Int = PRIMARY_USER_ID) {
        fakeSettings.putIntForUser(key, value, userId)
        if (::underTest.isInitialized) {
            fakeExecutor.runAllReady()
        }
    }

    private fun layoutStatusBarView(width: Int, height: Int) {
        statusBarView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        statusBarView.layout(0, 0, width, height)
    }

    private fun sendTap(x: Float, y: Float, downTime: Long, upTime: Long) {
        dispatchEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        dispatchEvent(MotionEvent.ACTION_UP, x, y, downTime, upTime)
    }

    private fun dispatchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ) {
        if (eventTime > fakeClock.uptimeMillis()) {
            fakeClock.setUptimeMillis(eventTime)
        }
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            underTest.onTouchEvent(ev, statusBarView)
        } finally {
            ev.recycle()
        }
    }

    private class TestPhoneStatusBarView(context: Context) : PhoneStatusBarView(context, null) {
        var attached: Boolean = true
        var customRootWindowInsets: WindowInsets? = null
        var explicitLayoutDirection: Int = View.LAYOUT_DIRECTION_LTR

        override fun isAttachedToWindow(): Boolean = attached

        override fun getRootWindowInsets(): WindowInsets? = customRootWindowInsets

        override fun getLayoutDirection(): Int = explicitLayoutDirection
    }

    private class RecordingScrollToTop : ScrollToTop {
        val calls = mutableListOf<Pair<Int, Int>>()

        override fun onScrollToTop(displayId: Int, x: Int) {
            calls.add(displayId to x)
        }
    }

    private companion object {
        const val DISPLAY_ID = 0
        const val PRIMARY_USER_ID = 0
        const val SECONDARY_USER_ID = 10
        const val STATUS_BAR_WIDTH = 1080
        const val STATUS_BAR_HEIGHT = 60
    }
}
