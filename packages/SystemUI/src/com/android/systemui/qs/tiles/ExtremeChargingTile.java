/*
 * Copyright (C) 2026 The Evolution X Project
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

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.qs.QSTile.Icon;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SettingObserver;

import javax.inject.Inject;

public class ExtremeChargingTile extends QSTileImpl<BooleanState>
        implements BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "extremecharging";

    private final GlobalSettings mGlobalSettings;
    private final BatteryController mBatteryController;
    private final SettingObserver mBodegaEnabledSetting;
    private final SettingObserver mBodegaEligibleSetting;
    private final SettingObserver mBodegaSupportedSetting;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_extreme_charging);
    private boolean mListening;

    @Inject
    public ExtremeChargingTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            GlobalSettings globalSettings,
            BatteryController batteryController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mGlobalSettings = globalSettings;
        mBatteryController = batteryController;

        mBodegaEnabledSetting = new SettingObserver(globalSettings, mHandler, "bodega_enabled") {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };

        mBodegaEligibleSetting = new SettingObserver(globalSettings, mHandler, "bodega_eligible") {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };

        mBodegaSupportedSetting = new SettingObserver(globalSettings, mHandler, "bodega_supported") {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };

        mBatteryController.addCallback(this);
    }

    @Override
    public boolean isAvailable() {
        return mGlobalSettings.getInt("bodega_supported", 0) == 1;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        if (!isAvailable()) {
            return;
        }

        int enabled = mGlobalSettings.getInt("bodega_enabled", 0);
        int eligible = mGlobalSettings.getInt("bodega_eligible", 0);

        if (enabled == 1) {
            mGlobalSettings.putInt("bodega_enabled", 0);
        } else if (eligible == 1) {
            mGlobalSettings.putInt("bodega_enabled", 1);
        } else {
            return;
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_extreme_charging_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (!isAvailable()) {
            state.state = Tile.STATE_UNAVAILABLE;
            return;
        }

        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_extreme_charging_label);
        state.hasLongClickEffect = false;

        boolean enabled = mGlobalSettings.getInt("bodega_enabled", 0) == 1;
        boolean eligible = mGlobalSettings.getInt("bodega_eligible", 0) == 1;
        boolean plugged = mBatteryController.isPluggedIn();

        state.value = enabled;

        if (enabled) {
            state.state = Tile.STATE_ACTIVE;
            state.secondaryLabel = mContext.getString(R.string.quick_settings_extreme_charging_active);
        } else if (eligible) {
            state.state = Tile.STATE_INACTIVE;
            state.secondaryLabel = mContext.getString(R.string.quick_settings_extreme_charging_ready);
        } else {
            state.state = Tile.STATE_UNAVAILABLE;
            if (!plugged) {
                state.secondaryLabel = mContext.getString(R.string.quick_settings_extreme_charging_unplugged);
            } else {
                state.secondaryLabel = mContext.getString(R.string.quick_settings_extreme_charging_unavailable);
            }
        }
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VIEW_UNKNOWN;
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        refreshState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            refreshState();
        }
        mBodegaEnabledSetting.setListening(listening);
        mBodegaEligibleSetting.setListening(listening);
        mBodegaSupportedSetting.setListening(listening);
    }
}
