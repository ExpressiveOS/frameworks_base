/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.toast

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.app.animation.Interpolators

class ToastDefaultAnimation {
    /**
     * sum of the in and out animation durations cannot exceed
     * [com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_ANIM_BUFFER] to prevent the toast
     * window from being removed before animations are completed
     */
    companion object {
        // total duration shouldn't exceed NotificationManagerService's delay for "in" animation
        fun toastIn(view: View): AnimatorSet? {
            val icon: View? = view.findViewById(com.android.systemui.res.R.id.icon)
            val text: View? = view.findViewById(com.android.systemui.res.R.id.text)
            if (icon == null || text == null) {
                return null
            }
            if (!ValueAnimator.areAnimatorsEnabled()) {
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationY = 0f
                view.alpha = 1f
                text.alpha = 1f
                icon.alpha = 1f
                return AnimatorSet().apply {
                    play(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 1f).setDuration(0))
                }
            }
            val linearInterp = LinearInterpolator()
            val expressiveIn = Interpolators.EMPHASIZED_DECELERATE
            val startOffsetPx = 12f * view.resources.displayMetrics.density
            val sX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.92f, 1f).apply {
                interpolator = expressiveIn
                duration = 300
            }
            val sY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.92f, 1f).apply {
                interpolator = expressiveIn
                duration = 300
            }
            val tY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, startOffsetPx, 0f).apply {
                interpolator = expressiveIn
                duration = 300
            }
            val vA = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
                interpolator = linearInterp
                duration = 90
            }
            text.alpha = 0f // Set now otherwise won't apply until start delay
            val tA = ObjectAnimator.ofFloat(text, View.ALPHA, 0f, 1f).apply {
                interpolator = linearInterp
                duration = 200
                startDelay = 40
            }
            icon.alpha = 0f // Set now otherwise won't apply until start delay
            val iA = ObjectAnimator.ofFloat(icon, View.ALPHA, 0f, 1f).apply {
                interpolator = linearInterp
                duration = 200
                startDelay = 40
            }
            return AnimatorSet().apply {
                playTogether(sX, sY, tY, vA, tA, iA)
            }
        }

        fun toastOut(view: View): AnimatorSet? {
            // total duration shouldn't exceed NotificationManagerService's delay for "out" anim
            val icon: View? = view.findViewById(com.android.systemui.res.R.id.icon)
            val text: View? = view.findViewById(com.android.systemui.res.R.id.text)
            if (icon == null || text == null) {
                return null
            }
            if (!ValueAnimator.areAnimatorsEnabled()) {
                view.alpha = 0f
                text.alpha = 0f
                icon.alpha = 0f
                return AnimatorSet().apply {
                    play(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 0f).setDuration(0))
                }
            }
            val linearInterp = LinearInterpolator()
            val expressiveOut = Interpolators.EMPHASIZED_ACCELERATE
            val endOffsetPx = 8f * view.resources.displayMetrics.density
            val viewScaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.94f).apply {
                interpolator = expressiveOut
                duration = 200
            }
            val viewScaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.94f).apply {
                interpolator = expressiveOut
                duration = 200
            }
            val viewTranslateY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, endOffsetPx).apply {
                interpolator = expressiveOut
                duration = 200
            }
            val viewAlpha = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
                interpolator = linearInterp
                duration = 100
                startDelay = 100
            }
            val textAlpha = ObjectAnimator.ofFloat(text, View.ALPHA, 1f, 0f).apply {
                interpolator = linearInterp
                duration = 140
            }
            val iconAlpha = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f).apply {
                interpolator = linearInterp
                duration = 140
            }
            return AnimatorSet().apply {
                playTogether(
                    viewScaleX,
                    viewScaleY,
                    viewTranslateY,
                    viewAlpha,
                    textAlpha,
                    iconAlpha,
                )
            }
        }
    }
}
