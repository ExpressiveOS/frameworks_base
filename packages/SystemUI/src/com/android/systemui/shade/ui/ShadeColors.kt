/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    // High-opacity obsidian frosted scrims for Pure Black Theme when blur is active (~88% panel,
    // ~90-92% notification scrim preserving depth & true OLED black feel).
    private const val BLACK_THEME_SHADE_PANEL_BLUR = 0xE0000000.toInt()
    private const val BLACK_THEME_NOTIFICATION_SCRIM_BLUR = 0xE60D0D0D.toInt()
    private const val BLACK_THEME_CLASSIC_NOTIFICATION_SCRIM_BLUR = 0xEB0A0A0A.toInt()
    private const val BLACK_THEME_NOTIFICATION_SCRIM_FALLBACK = 0xFF0D0D0D.toInt()
    private const val SETTING_BERRY_BLACK_THEME = "berry_black_theme"

    @JvmStatic
    fun isBlackTheme(context: Context): Boolean {
        val isNightMode =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (!isNightMode) {
            return false
        }
        return Settings.Secure.getIntForUser(
            context.contentResolver,
            SETTING_BERRY_BLACK_THEME,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
    }

    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        return if (blurSupported) {
            if (isBlackTheme(context)) {
                shadePanelStandard(context)
            } else if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context)
            }
        } else {
            shadePanelFallback(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        return if (blurSupported) {
            notificationScrimStandard(context)
        } else {
            notificationScrimFallback(context)
        }
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    /**
     * Background color of the rounded cornered rect behind Notifications in Single- or SplitShade.
     */
    @JvmStatic
    fun classicShadeNotificationScrimBg(
        context: Context,
        blurSupported: Boolean,
        composited: Boolean,
    ): Int {
        if (isBlackTheme(context)) {
            return if (blurSupported) {
                if (composited) {
                    BLACK_THEME_CLASSIC_NOTIFICATION_SCRIM_BLUR
                } else {
                    BLACK_THEME_NOTIFICATION_SCRIM_BLUR
                }
            } else {
                BLACK_THEME_NOTIFICATION_SCRIM_FALLBACK
            }
        }
        val surfaceEffect0Color =
            context.resources.getColor(
                com.android.internal.R.color.customColorSurfaceEffect0,
                context.theme,
            )
        if (!composited) {
            // SplitShade (avoid adding the shade panel with scrim)
            return surfaceEffect0Color
        }
        // SingleShade
        val shadePanelWithScrim = shadePanel(context, blurSupported, withScrim = true)
        return ColorUtils.compositeColors(surfaceEffect0Color, shadePanelWithScrim)
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context): Int {
        if (isBlackTheme(context)) {
            return BLACK_THEME_SHADE_PANEL_BLUR
        }
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        return if (isBlackTheme(context)) {
            Color.BLACK
        } else {
            context.getColor(R.color.shade_panel_fallback)
        }
    }

    @JvmStatic
    private fun notificationScrimStandard(context: Context): Int {
        if (isBlackTheme(context)) {
            return BLACK_THEME_NOTIFICATION_SCRIM_BLUR
        }
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.5f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context): Int {
        if (isBlackTheme(context)) {
            return BLACK_THEME_NOTIFICATION_SCRIM_FALLBACK
        }
        return context.getColor(R.color.notification_scrim_fallback)
    }
}
