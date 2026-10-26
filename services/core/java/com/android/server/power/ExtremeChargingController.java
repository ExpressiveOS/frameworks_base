/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.power;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * ExtremeChargingController manages Google Pixel "Extreme Charging" ("Bodega") mode.
 *
 * It acts as an observer and power HAL client for the Pixel Power HAL extension interface
 * (google.hardware.power.extension.pixel.IPowerExt).
 *
 * On devices where the HAL extension supports the "BODEGA" mode hint (Pixel 11 series:
 * kodiak, grizzly, cubs, yogi), activating Bodega relaxes charging thermal throttle
 * ceilings and engages battery priority charging (/sys/class/power_supply/battery/priority_charging_enable).
 *
 * When the battery reaches 100%, becomes FULL, or is disconnected from power, the controller
 * automatically deactivates Bodega mode and updates Settings.Global state.
 */
public class ExtremeChargingController {
    private static final String TAG = "ExtremeChargingController";

    public static final String SETTING_BODEGA_ENABLED = "bodega_enabled";
    public static final String SETTING_BODEGA_ELIGIBLE = "bodega_eligible";
    public static final String SETTING_BODEGA_SUPPORTED = "bodega_supported";
    public static final String SETTING_BODEGA_TIMESTAMP_MS = "bodega_enabled_timestamp_ms";

    private static final String POWER_HAL_SERVICE = "android.hardware.power.IPower/default";
    private static final String POWER_EXT_DESCRIPTOR = "google.hardware.power.extension.pixel.IPowerExt";
    private static final int TRANSACTION_SET_MODE = IBinder.FIRST_CALL_TRANSACTION; // 1
    private static final int TRANSACTION_IS_MODE_SUPPORTED = IBinder.FIRST_CALL_TRANSACTION + 1; // 2
    private static final String MODE_BODEGA = "BODEGA";
    // Highest battery level Bodega may be turned on at; once on it runs until full or unplugged.
    // The QS tile offers it only while bodega_eligible is set, which is worked out from this.
    private static final int BODEGA_MAX_LEVEL = 50;

    private final Context mContext;
    private final Handler mHandler;
    private final ContentResolver mResolver;
    private final Object mLock = new Object();

    private IBinder mPowerExt;
    private boolean mSupported = false;
    private boolean mInitialized = false;

    private boolean mEnabled = false;
    private boolean mEligible = false;
    private int mLastPlugType = 0;
    private int mLastLevel = -1;
    private int mLastStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
    private int mLastChargingState = 0;

    private final ContentObserver mSettingObserver;

    public ExtremeChargingController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mResolver = context.getContentResolver();

        mSettingObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                synchronized (mLock) {
                    handleSettingChangedLocked();
                }
            }
        };
    }

    public void init() {
        synchronized (mLock) {
            if (mInitialized) {
                return;
            }
            mInitialized = true;

            mSupported = queryModeSupported(MODE_BODEGA);
            Settings.Global.putInt(mResolver, SETTING_BODEGA_SUPPORTED, mSupported ? 1 : 0);

            if (!mSupported) {
                Slog.i(TAG, "Extreme Charging mode (Bodega) is not supported on this platform");
                Settings.Global.putInt(mResolver, SETTING_BODEGA_ELIGIBLE, 0);
                Settings.Global.putInt(mResolver, SETTING_BODEGA_ENABLED, 0);
                Settings.Global.putString(mResolver, SETTING_BODEGA_TIMESTAMP_MS, "0");
                return;
            }

            Slog.i(TAG, "Extreme Charging mode (Bodega) supported. Initializing state...");
            // Ensure HAL and settings start in clean disabled state
            setHalMode(MODE_BODEGA, false);
            mEnabled = false;
            Settings.Global.putInt(mResolver, SETTING_BODEGA_ELIGIBLE, 0);
            Settings.Global.putInt(mResolver, SETTING_BODEGA_ENABLED, 0);
            Settings.Global.putString(mResolver, SETTING_BODEGA_TIMESTAMP_MS, "0");

            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(SETTING_BODEGA_ENABLED),
                    false,
                    mSettingObserver,
                    UserHandle.USER_ALL);
        }
    }

    public boolean isSupported() {
        synchronized (mLock) {
            return mSupported;
        }
    }

    public void onBatteryValuesChanged(int plugType, int level, int status, int chargingState) {
        synchronized (mLock) {
            if (!mSupported) {
                return;
            }

            mLastPlugType = plugType;
            mLastLevel = level;
            mLastStatus = status;
            mLastChargingState = chargingState;

            boolean plugged = (plugType != 0);
            boolean full = (status == BatteryManager.BATTERY_STATUS_FULL || level >= 100);
            boolean chargeLimitReached = (chargingState == 4 && level >= 80);

            // Auto-disable if active and condition violated
            if (mEnabled) {
                if (!plugged || full || chargeLimitReached) {
                    Slog.i(TAG, "Extreme Charging auto-deactivated: plugged=" + plugged
                            + ", level=" + level + ", status=" + status + ", chargingState=" + chargingState);
                    updateEnabledLocked(false);
                }
            }

            // Update eligibility: plugged in, level <= 50%, not full, not charge-limited
            boolean eligible = plugged && (level <= BODEGA_MAX_LEVEL) && !full
                    && !chargeLimitReached;
            if (eligible != mEligible) {
                mEligible = eligible;
                Settings.Global.putInt(mResolver, SETTING_BODEGA_ELIGIBLE, eligible ? 1 : 0);
            }
        }
    }

    private void handleSettingChangedLocked() {
        if (!mSupported) {
            return;
        }

        int settingVal = Settings.Global.getInt(mResolver, SETTING_BODEGA_ENABLED, 0);
        boolean requestedEnable = (settingVal == 1);

        if (requestedEnable == mEnabled) {
            return;
        }

        if (requestedEnable) {
            boolean plugged = (mLastPlugType != 0);
            boolean full = (mLastStatus == BatteryManager.BATTERY_STATUS_FULL || mLastLevel >= 100);
            boolean chargeLimitReached = (mLastChargingState == 4 && mLastLevel >= 80);

            if (!plugged || full || chargeLimitReached || mLastLevel > BODEGA_MAX_LEVEL) {
                Slog.w(TAG, "Cannot activate Extreme Charging: device is not eligible (plugged="
                        + plugged + ", level=" + mLastLevel + ", status=" + mLastStatus + ")");
                Settings.Global.putInt(mResolver, SETTING_BODEGA_ENABLED, 0);
                return;
            }

            updateEnabledLocked(true);
        } else {
            updateEnabledLocked(false);
        }
    }

    private void updateEnabledLocked(boolean enable) {
        if (mEnabled == enable) {
            return;
        }

        mEnabled = enable;
        setHalMode(MODE_BODEGA, enable);

        Settings.Global.putInt(mResolver, SETTING_BODEGA_ENABLED, enable ? 1 : 0);
        Settings.Global.putString(mResolver, SETTING_BODEGA_TIMESTAMP_MS,
                enable ? String.valueOf(System.currentTimeMillis()) : "0");

        // Recompute eligibility
        boolean plugged = (mLastPlugType != 0);
        boolean full = (mLastStatus == BatteryManager.BATTERY_STATUS_FULL || mLastLevel >= 100);
        boolean chargeLimitReached = (mLastChargingState == 4 && mLastLevel >= 80);
        boolean eligible = plugged && (mLastLevel <= BODEGA_MAX_LEVEL) && !full
                && !chargeLimitReached;
        if (eligible != mEligible) {
            mEligible = eligible;
            Settings.Global.putInt(mResolver, SETTING_BODEGA_ELIGIBLE, eligible ? 1 : 0);
        }

        Slog.i(TAG, "Extreme Charging mode (Bodega) " + (enable ? "ACTIVATED" : "DEACTIVATED"));
    }

    private synchronized IBinder getExtensionLocked() {
        if (mPowerExt != null && mPowerExt.isBinderAlive() && mPowerExt.pingBinder()) {
            return mPowerExt;
        }
        mPowerExt = null;
        try {
            IBinder powerService = ServiceManager.getService(POWER_HAL_SERVICE);
            if (powerService == null) {
                Slog.w(TAG, "Power HAL service not found: " + POWER_HAL_SERVICE);
                return null;
            }
            mPowerExt = powerService.getExtension();
            if (mPowerExt != null) {
                mPowerExt.linkToDeath(() -> {
                    synchronized (ExtremeChargingController.this) {
                        Slog.w(TAG, "Power HAL extension binder died, clearing reference");
                        mPowerExt = null;
                    }
                }, 0);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get power extension binder", e);
        }
        return mPowerExt;
    }

    private boolean queryModeSupported(String mode) {
        IBinder ext = getExtensionLocked();
        if (ext == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(POWER_EXT_DESCRIPTOR);
            data.writeString(mode);
            ext.transact(TRANSACTION_IS_MODE_SUPPORTED, data, reply, 0);
            reply.readException();
            return reply.readBoolean();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to query mode support for " + mode, e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void setHalMode(String mode, boolean enabled) {
        IBinder ext = getExtensionLocked();
        if (ext == null) {
            Slog.w(TAG, "Cannot set mode " + mode + ": Power HAL extension unavailable");
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(POWER_EXT_DESCRIPTOR);
            data.writeString(mode);
            data.writeBoolean(enabled);
            ext.transact(TRANSACTION_SET_MODE, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to send setMode transaction for " + mode, e);
        } finally {
            data.recycle();
        }
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Extreme Charging (Bodega) State:");
            pw.println("  Supported: " + mSupported);
            pw.println("  Enabled: " + mEnabled);
            pw.println("  Eligible: " + mEligible);
            pw.println("  Last Plugged: " + (mLastPlugType != 0));
            pw.println("  Last Level: " + mLastLevel);
            pw.println("  Last Status: " + mLastStatus);
            pw.println("  Last ChargingState: " + mLastChargingState);
        }
    }
}
