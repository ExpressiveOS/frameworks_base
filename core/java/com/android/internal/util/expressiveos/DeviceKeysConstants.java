/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The ExpressiveOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.expressiveos;

import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;

/** @hide */
public class DeviceKeysConstants {
    /**
     * Actions that can be assigned to the edge long swipe, three-finger swipe and shake gestures.
     *
     * The ordinals are what gets stored in {@link Settings.System} (for example
     * {@link Settings.System#KEY_THREE_FINGERS_SWIPE_ACTION}) and must stay in sync with the
     * hardware_keys_action_values array of the settings apps.
     */
    public enum Action {
        NOTHING,
        MENU,
        APP_SWITCH,
        SEARCH,
        VOICE_SEARCH,
        IN_APP_SEARCH,
        LAUNCH_CAMERA,
        SLEEP,
        LAST_APP,
        SPLIT_SCREEN,
        KILL_APP,
        PLAY_PAUSE_MUSIC,
        SCREENSHOT,
        PARTIAL_SCREENSHOT,
        TORCH,
        VOLUME_PANEL,
        CLEAR_ALL_NOTIFICATIONS,
        NOTIFICATIONS,
        QS_PANEL,
        RINGER_MODES;

        public static Action fromIntSafe(int id) {
            if (id < NOTHING.ordinal() || id >= Action.values().length) {
                return NOTHING;
            }
            return Action.values()[id];
        }

        public static Action fromSettings(ContentResolver cr, String setting, Action def) {
            return fromIntSafe(Settings.System.getIntForUser(cr,
                    setting, def.ordinal(), UserHandle.USER_CURRENT));
        }
    }

    public static final int KEY_MASK_HOME = 0x01;
    public static final int KEY_MASK_BACK = 0x02;
    public static final int KEY_MASK_MENU = 0x04;
    public static final int KEY_MASK_ASSIST = 0x08;
    public static final int KEY_MASK_APP_SWITCH = 0x10;
    public static final int KEY_MASK_CAMERA = 0x20;
    public static final int KEY_MASK_VOLUME = 0x40;
}
