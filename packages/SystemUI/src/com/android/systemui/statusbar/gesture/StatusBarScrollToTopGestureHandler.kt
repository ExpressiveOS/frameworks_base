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

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Rect
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.ComposeView
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.ui.AxDynamicBarStatusBarExpansion
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayId
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel
import com.android.systemui.statusbar.domain.interactor.ScrollToTopInteractor
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SystemSettings
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.abs

/**
 * Arbitrates single-tap scroll-to-top gestures on the collapsed status bar against double-tap to
 * sleep, triple-tap freeform, brightness drag, shade/QS pulldown, and interactive status-bar chips.
 */
@PerDisplaySingleton
class StatusBarScrollToTopGestureHandler
@Inject
constructor(
    @DisplayId private val displayId: Int,
    @DisplayAware private val context: Context,
    @DisplayAware private val statusBarWindowStateController: Lazy<StatusBarWindowStateController>,
    @DisplayAware private val ongoingActivityChipsViewModel: Lazy<OngoingActivityChipsViewModel>,
    private val scrollToTopInteractor: Lazy<ScrollToTopInteractor>,
    private val systemSettings: SystemSettings,
    private val userTracker: UserTracker,
    @Main private val mainExecutor: DelayableExecutor,
    private val statusBarStateController: Lazy<StatusBarStateController>,
    private val keyguardStateController: Lazy<KeyguardStateController>,
    private val panelExpansionInteractor: Lazy<PanelExpansionInteractor>,
    private val shadeInteractor: Lazy<ShadeInteractor>,
    private val headsUpManager: Lazy<HeadsUpManager>,
    private val accessibilityManager: Lazy<AccessibilityManager>,
    private val axDynamicBarInteractor: Lazy<AxDynamicBarInteractor>,
    private val axDynamicBarStatusBarExpansion: Lazy<AxDynamicBarStatusBarExpansion>,
) : SystemUIDisplaySubcomponent.LifecycleListener {

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Volatile
    var activeZone: Int = ZONE_FULL
        private set

    @Volatile
    var isDoubleTapSleepEnabled: Boolean = true
        private set

    @Volatile
    var isTripleTapFreeformEnabled: Boolean = false
        private set

    private val axDynamicBarChipBounds = Rect()
    private val ongoingActivityChipsBounds = Rect()
    private val dynamicIslandBounds = Rect()
    private val tmpScreenLoc = IntArray(2)
    private val tmpViewBounds = Rect()

    private var isTrackingTap = false
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downTime = 0L

    private var pendingCancelRunnable: Runnable? = null
    private var inMultiTapBurst = false
    private var lastBurstDownUptimeMillis = -1L
    private var lastBurstUpUptimeMillis = -1L

    private val settingsObserver =
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                mainExecutor.execute { refreshSettings() }
            }
        }

    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                refreshSettings(newUser)
            }
        }

    init {
        refreshSettings()
        systemSettings.registerContentObserverForUserAsync(
            KEY_SCROLL_TO_TOP,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        systemSettings.registerContentObserverForUserAsync(
            KEY_SCROLL_TO_TOP_ZONE,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        systemSettings.registerContentObserverForUserAsync(
            KEY_DOUBLE_TAP_SLEEP,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        systemSettings.registerContentObserverForUserAsync(
            KEY_TRIPLE_TAP_FREEFORM,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        userTracker.addCallback(userTrackerCallback, mainExecutor)
    }

    override fun stop() {
        cancelCurrentGesture()
        systemSettings.unregisterContentObserverAsync(settingsObserver)
        userTracker.removeCallback(userTrackerCallback)
    }

    /** Updates screen bounds of the active [AxDynamicBar] status-bar pill, or null when hidden. */
    fun updateAxDynamicBarChipBounds(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            axDynamicBarChipBounds.setEmpty()
        } else {
            axDynamicBarChipBounds.set(bounds)
        }
    }

    /** Updates screen bounds of active [OngoingActivityChips], or null when none are visible. */
    fun updateOngoingActivityChipsBounds(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            ongoingActivityChipsBounds.setEmpty()
        } else {
            ongoingActivityChipsBounds.set(bounds)
        }
    }

    /** Updates screen bounds of the centered/cutout Dynamic Island pill, or null when hidden. */
    fun updateDynamicIslandBounds(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            dynamicIslandBounds.setEmpty()
        } else {
            dynamicIslandBounds.set(bounds)
        }
    }

    /**
     * Observes `ACTION_DOWN` during `PhoneStatusBarView.onInterceptTouchEvent` so a second tap
     * cancels any pending single-tap runnable even if a child view consumes the second tap.
     */
    fun onInterceptTouchEvent(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            handleMultiTapPreemptionOnDown(event)
        }
    }

    /** Processes touch events on [statusBarView] to detect valid scroll-to-top taps. */
    fun onTouchEvent(event: MotionEvent, statusBarView: PhoneStatusBarView) {
        val viewConfig = ViewConfiguration.get(statusBarView.context)
        val touchSlop = viewConfig.scaledTouchSlop.toFloat()
        val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (handleMultiTapPreemptionOnDown(event)) {
                    isTrackingTap = false
                    return
                }
                if (event.pointerCount > 1) {
                    isTrackingTap = false
                    return
                }
                if (!isContextAllowed(statusBarView)) {
                    isTrackingTap = false
                    return
                }
                if (!isWithinActiveZone(event.x, event.y, statusBarView)) {
                    isTrackingTap = false
                    return
                }
                if (isExcludedChipTouch(event.rawX, event.rawY, statusBarView)) {
                    isTrackingTap = false
                    return
                }

                isTrackingTap = true
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downTime = event.downTime
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isTrackingTap = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (inMultiTapBurst) {
                    return
                }
                if (!isTrackingTap) {
                    return
                }
                if (
                    event.pointerCount > 1 ||
                        abs(event.x - downX) > touchSlop ||
                        abs(event.y - downY) > touchSlop ||
                        (event.eventTime - downTime) >= longPressTimeoutMs
                ) {
                    isTrackingTap = false
                }
            }

            MotionEvent.ACTION_UP -> {
                if (inMultiTapBurst) {
                    lastBurstUpUptimeMillis = event.eventTime
                    isTrackingTap = false
                    return
                }
                if (!isTrackingTap) {
                    return
                }
                isTrackingTap = false

                if (
                    abs(event.x - downX) > touchSlop ||
                        abs(event.y - downY) > touchSlop ||
                        (event.eventTime - downTime) >= longPressTimeoutMs
                ) {
                    return
                }
                if (!isContextAllowed(statusBarView)) {
                    return
                }

                val targetX = downRawX.toInt()
                if (isDoubleTapSleepEnabled || isTripleTapFreeformEnabled) {
                    cancelPendingRunnable()
                    pendingCancelRunnable =
                        mainExecutor.executeDelayed(
                            {
                                pendingCancelRunnable = null
                                if (!inMultiTapBurst && isContextAllowed(statusBarView)) {
                                    scrollToTopInteractor.get().onScrollToTop(displayId, targetX)
                                }
                            },
                            DEFER_MULTI_TAP_MS,
                        )
                } else {
                    scrollToTopInteractor.get().onScrollToTop(displayId, targetX)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (inMultiTapBurst) {
                    lastBurstUpUptimeMillis = event.eventTime
                }
                isTrackingTap = false
            }
        }
    }

    /**
     * Future-proof gate for Compose Flexiglass (`StatusBarRoot`) when
     * `StatusBarEventForwardingModernization` is enabled.
     */
    fun shouldAllowTap(
        localX: Float,
        localY: Float,
        statusBarView: PhoneStatusBarView?,
    ): Boolean {
        if (!isContextAllowed(statusBarView)) {
            return false
        }
        val view = statusBarView ?: return false
        if (!isWithinActiveZone(localX, localY, view)) {
            return false
        }
        view.getLocationOnScreen(tmpScreenLoc)
        val rawX = tmpScreenLoc[0] + localX
        val rawY = tmpScreenLoc[1] + localY
        return !isExcludedChipTouch(rawX, rawY, view)
    }

    /**
     * Handles a Compose Flexiglass tap from `StatusBarRoot`, honoring multi-tap burst preemption
     * and [DEFER_MULTI_TAP_MS] deferral when double-tap sleep or triple-tap freeform is enabled.
     */
    fun handleComposeTap(
        localX: Float,
        localY: Float,
        statusBarView: PhoneStatusBarView?,
        onAllowed: () -> Unit,
    ) {
        val now = SystemClock.uptimeMillis()
        if (pendingCancelRunnable != null) {
            cancelPendingRunnable()
            inMultiTapBurst = true
            lastBurstDownUptimeMillis = now
            lastBurstUpUptimeMillis = now
            return
        }
        if (inMultiTapBurst) {
            val expired =
                if (lastBurstUpUptimeMillis >= 0L) {
                    (now - lastBurstUpUptimeMillis) > MULTI_TAP_BURST_RESET_MS
                } else {
                    val fallbackTimeout =
                        MULTI_TAP_BURST_RESET_MS + ViewConfiguration.getLongPressTimeout().toLong()
                    (now - lastBurstDownUptimeMillis) > fallbackTimeout
                }
            if (!expired) {
                lastBurstUpUptimeMillis = now
                return
            }
            inMultiTapBurst = false
            lastBurstDownUptimeMillis = -1L
            lastBurstUpUptimeMillis = -1L
        }
        if (!shouldAllowTap(localX, localY, statusBarView)) {
            return
        }
        if (isDoubleTapSleepEnabled || isTripleTapFreeformEnabled) {
            cancelPendingRunnable()
            pendingCancelRunnable =
                mainExecutor.executeDelayed(
                    {
                        pendingCancelRunnable = null
                        if (!inMultiTapBurst && isContextAllowed(statusBarView)) {
                            onAllowed()
                        }
                    },
                    DEFER_MULTI_TAP_MS,
                )
        } else {
            onAllowed()
        }
    }

    /** Cancels any active tap tracking or pending delayed scroll runnable. */
    fun cancelCurrentGesture() {
        isTrackingTap = false
        cancelPendingRunnable()
        inMultiTapBurst = false
        lastBurstDownUptimeMillis = -1L
        lastBurstUpUptimeMillis = -1L
    }

    /**
     * Handles multi-tap burst detection on `ACTION_DOWN`.
     *
     * @return `true` if this `ACTION_DOWN` cancelled a pending single-tap scroll or belongs to an
     *   active multi-tap burst (Tap 2 / Tap 3) and must be suppressed.
     */
    private fun handleMultiTapPreemptionOnDown(event: MotionEvent): Boolean {
        if (pendingCancelRunnable != null) {
            cancelPendingRunnable()
            inMultiTapBurst = true
            lastBurstDownUptimeMillis = event.downTime
            lastBurstUpUptimeMillis = -1L
            return true
        }

        if (inMultiTapBurst) {
            // Same gesture seen by both onInterceptTouchEvent and onTouchEvent.
            if (event.downTime == lastBurstDownUptimeMillis) {
                return true
            }

            val expired =
                if (lastBurstUpUptimeMillis >= 0L) {
                    (event.eventTime - lastBurstUpUptimeMillis) > MULTI_TAP_BURST_RESET_MS
                } else {
                    // Self-heal if a child view consumed the prior tap's ACTION_UP.
                    val fallbackTimeout =
                        MULTI_TAP_BURST_RESET_MS + ViewConfiguration.getLongPressTimeout().toLong()
                    (event.eventTime - lastBurstDownUptimeMillis) > fallbackTimeout
                }

            if (!expired) {
                lastBurstDownUptimeMillis = event.downTime
                lastBurstUpUptimeMillis = -1L
                return true
            }

            inMultiTapBurst = false
            lastBurstDownUptimeMillis = -1L
            lastBurstUpUptimeMillis = -1L
        }

        return false
    }

    private fun cancelPendingRunnable() {
        pendingCancelRunnable?.run()
        pendingCancelRunnable = null
    }

    private fun isContextAllowed(statusBarView: PhoneStatusBarView?): Boolean {
        if (!isEnabled) return false
        if (accessibilityManager.get().isTouchExplorationEnabled) return false

        val sbState = statusBarStateController.get()
        if (sbState.state != StatusBarState.SHADE) return false
        if (sbState.isDozing || sbState.isDreaming || sbState.isPulsing || sbState.isExpanded) {
            return false
        }

        val kgState = keyguardStateController.get()
        if (
            (kgState.isShowing && !kgState.isOccluded) ||
                kgState.isPrimaryBouncerShowing ||
                kgState.isKeyguardGoingAway
        ) {
            return false
        }

        val panel = panelExpansionInteractor.get()
        if (!panel.isFullyCollapsed || panel.isTracking || panel.isCollapsing) {
            return false
        }

        val shade = shadeInteractor.get()
        if (
            shade.isAnyExpanded.value ||
                shade.isQsExpanded.value ||
                shade.isUserInteracting.value
        ) {
            return false
        }

        if (axDynamicBarStatusBarExpansion.get().isExpanded.value) return false
        val hun = headsUpManager.get()
        if (hun.hasPinnedHeadsUp() || hun.isHeadsUpAnimatingAwayValue()) {
            return false
        }
        if (!statusBarWindowStateController.get().windowIsShowing()) return false

        val view = statusBarView ?: return false
        if (
            !view.isAttachedToWindow ||
                view.visibility != View.VISIBLE ||
                view.width <= 0 ||
                view.height <= 0
        ) {
            return false
        }

        return true
    }

    private fun isWithinActiveZone(
        localX: Float,
        localY: Float,
        statusBarView: PhoneStatusBarView,
    ): Boolean {
        if (localY < TOP_EDGE_GUARD_PX || localY >= statusBarView.height) {
            return false
        }

        val width = statusBarView.width.toFloat()
        val density = statusBarView.resources.displayMetrics.density
        val bevelPx = PALM_BEVEL_DP * density
        if (width <= 2f * bevelPx || localX < bevelPx || localX > width - bevelPx) {
            return false
        }

        return when (activeZone) {
            ZONE_FULL -> true
            ZONE_CENTER -> {
                if (localX in (CENTER_ZONE_START_FRACTION * width)..(CENTER_ZONE_END_FRACTION * width)) {
                    return true
                }
                val cutoutRect = statusBarView.rootWindowInsets?.displayCutout?.boundingRectTop
                if (cutoutRect != null && !cutoutRect.isEmpty) {
                    val marginPx = CUTOUT_MARGIN_DP * density
                    val windowX = localX + statusBarView.left
                    windowX in (cutoutRect.left - marginPx)..(cutoutRect.right + marginPx)
                } else {
                    false
                }
            }
            ZONE_START,
            ZONE_END -> {
                val isRtl =
                    statusBarView.layoutDirection == View.LAYOUT_DIRECTION_RTL ||
                        statusBarView.resources.configuration.layoutDirection ==
                            View.LAYOUT_DIRECTION_RTL
                val useLeftSide =
                    (activeZone == ZONE_START && !isRtl) || (activeZone == ZONE_END && isRtl)
                if (useLeftSide) {
                    localX <= SIDE_ZONE_FRACTION * width
                } else {
                    localX >= (1f - SIDE_ZONE_FRACTION) * width
                }
            }
            else -> true
        }
    }

    private fun isExcludedChipTouch(
        rawX: Float,
        rawY: Float,
        statusBarView: PhoneStatusBarView,
    ): Boolean {
        val rx = rawX.toInt()
        val ry = rawY.toInt()

        if (hasActiveAxDynamicBarChip() && axDynamicBarChipBounds.contains(rx, ry)) {
            return true
        }

        if (hasActiveOngoingActivityChip() && ongoingActivityChipsBounds.contains(rx, ry)) {
            return true
        }

        if (!dynamicIslandBounds.isEmpty && dynamicIslandBounds.contains(rx, ry)) {
            return true
        }

        val dynamicIslandView = statusBarView.findViewWithTag<View>(DYNAMIC_ISLAND_VIEW_TAG)
        if (
            dynamicIslandView != null &&
                isViewVisibleInStatusBar(dynamicIslandView, statusBarView) &&
                viewContainsScreenPoint(dynamicIslandView, rx, ry)
        ) {
            return true
        }

        val userSwitcher = statusBarView.findViewById<View>(R.id.user_switcher_container)
        if (
            userSwitcher != null &&
                isViewVisibleInStatusBar(userSwitcher, statusBarView) &&
                viewContainsScreenPoint(userSwitcher, rx, ry)
        ) {
            return true
        }

        return containsVisibleClickableChildAt(statusBarView, statusBarView, rx, ry)
    }

    private fun hasActiveAxDynamicBarChip(): Boolean {
        val ax = axDynamicBarInteractor.get()
        if (!ax.settings.isEnabled.value) return false
        if (ax.isOnKeyguard.value) return false
        val uiState = ax.uiState.value
        return uiState.shouldShow || uiState.notificationAlert != null
    }

    private fun hasActiveOngoingActivityChip(): Boolean {
        return ongoingActivityChipsViewModel.get().chips.value.active.any { !it.isHidden }
    }

    private fun containsVisibleClickableChildAt(
        group: ViewGroup,
        statusBarView: PhoneStatusBarView,
        rx: Int,
        ry: Int,
    ): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) {
                continue
            }
            // ComposeViews manage internal composable hit regions reported via update*ChipBounds.
            if (child is ComposeView) {
                continue
            }
            if (
                child.isClickable &&
                    child.id != R.id.status_bar_start_side_content &&
                    child.id != R.id.system_icons &&
                    isViewVisibleInStatusBar(child, statusBarView) &&
                    viewContainsScreenPoint(child, rx, ry)
            ) {
                return true
            }
            if (
                child is ViewGroup &&
                    containsVisibleClickableChildAt(child, statusBarView, rx, ry)
            ) {
                return true
            }
        }
        return false
    }

    private fun isViewVisibleInStatusBar(view: View, statusBarView: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === statusBarView) return true
            current = current.parent as? View
        }
        return view.visibility == View.VISIBLE
    }

    private fun viewContainsScreenPoint(view: View, rx: Int, ry: Int): Boolean {
        view.getLocationOnScreen(tmpScreenLoc)
        tmpViewBounds.set(
            tmpScreenLoc[0],
            tmpScreenLoc[1],
            tmpScreenLoc[0] + view.width,
            tmpScreenLoc[1] + view.height,
        )
        return tmpViewBounds.contains(rx, ry)
    }

    private fun refreshSettings(userId: Int = userTracker.userId) {
        val enabled = systemSettings.getIntForUser(KEY_SCROLL_TO_TOP, 1, userId) != 0
        val rawZone = systemSettings.getIntForUser(KEY_SCROLL_TO_TOP_ZONE, ZONE_FULL, userId)
        val zone = if (rawZone in ZONE_FULL..ZONE_END) rawZone else ZONE_FULL
        val dt2sDefault = getDefaultDoubleTapToSleep()
        val dt2s = systemSettings.getIntForUser(KEY_DOUBLE_TAP_SLEEP, dt2sDefault, userId) != 0
        val tripleTap = systemSettings.getIntForUser(KEY_TRIPLE_TAP_FREEFORM, 0, userId) != 0

        if (!enabled) {
            cancelCurrentGesture()
        }
        isEnabled = enabled
        activeZone = zone
        isDoubleTapSleepEnabled = dt2s
        isTripleTapFreeformEnabled = tripleTap
    }

    private fun getDefaultDoubleTapToSleep(): Int {
        return try {
            if (
                context.resources.getBoolean(
                    com.android.internal.R.bool.config_dt2sGestureEnabledByDefault
                )
            ) {
                1
            } else {
                0
            }
        } catch (_: Resources.NotFoundException) {
            1
        }
    }

    companion object {
        const val KEY_SCROLL_TO_TOP = "status_bar_scroll_to_top"
        const val KEY_SCROLL_TO_TOP_ZONE = "status_bar_scroll_to_top_zone"
        const val KEY_DOUBLE_TAP_SLEEP = Settings.System.DOUBLE_TAP_SLEEP_GESTURE
        const val KEY_TRIPLE_TAP_FREEFORM = "status_bar_triple_tap_freeform"

        const val ZONE_FULL = 0
        const val ZONE_CENTER = 1
        const val ZONE_START = 2
        const val ZONE_END = 3

        const val DEFER_MULTI_TAP_MS = 220L
        const val MULTI_TAP_BURST_RESET_MS = 250L

        const val DYNAMIC_ISLAND_VIEW_TAG = "status_bar_dynamic_island_container"

        private const val TOP_EDGE_GUARD_PX = 1f
        private const val PALM_BEVEL_DP = 8f
        private const val CUTOUT_MARGIN_DP = 16f
        private const val CENTER_ZONE_START_FRACTION = 0.33f
        private const val CENTER_ZONE_END_FRACTION = 0.67f
        private const val SIDE_ZONE_FRACTION = 0.38f
    }
}
