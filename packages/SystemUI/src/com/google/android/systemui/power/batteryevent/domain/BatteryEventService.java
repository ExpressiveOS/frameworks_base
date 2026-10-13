/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.systemui.power.batteryevent.domain;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.settingslib.fuelgauge.BatteryStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class BatteryEventService extends Service {

    private static final String TAG = "BatteryEventService";

    private static final String ACTION_BATTERY_EVENT =
            "com.google.android.systemui.power.batteryevent.BATTERY_EVENT";

    private static final String INTELLIGENCE_PACKAGE =
            "com.google.android.settings.intelligence";

    // The binder interface Google's charging screensaver (Dreamliner) binds to. Only the data
    // callback it uses is implemented; the transaction codes match Google's SystemUI.
    private static final String SERVICE_DESCRIPTOR =
            "com.google.android.systemui.power.batteryevent.aidl.IBatteryEventService";
    private static final String DATA_LISTENER_DESCRIPTOR =
            "com.google.android.systemui.power.batteryevent.aidl.IBatteryEventsDataListener";
    private static final int TRANSACTION_REGISTER_DATA_CALLBACK = 6;
    private static final int TRANSACTION_UNREGISTER_DATA_CALLBACK = 7;
    private static final int TRANSACTION_ON_BATTERY_EVENT_DATA_CHANGED = 1;

    // BatteryEventType names, as written to parcels.
    private static final String EVENT_FULL_CHARGED = "full_charged";
    private static final String EVENT_REGULAR_CHARGING = "regular_charging";
    private static final String EVENT_FAST_CHARGING = "fast_charging";
    private static final String EVENT_SLOW_CHARGING = "slow_charging";
    private static final String EVENT_NOT_CHARGING = "not_charging";
    private static final String EVENT_CHARGING_LIMIT = "charging_limit";

    private static final String KEY_CURRENT_BATTERY_LEVEL = "current_battery_level";
    private static final String KEY_PLUGGED_TYPE = "plugged_type";
    private static final String KEY_TIME_TO_TARGET_PREFIX = "time_to_target_";
    private static final String KEY_TIME_TO_TARGET_SUFFIX = "_seconds";

    private static final String CHARGE_OPTIMIZATION_MODE = "charge_optimization_mode";

    private BroadcastReceiver mBatteryReceiver;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Intent mLastBatteryIntent;

    private final RemoteCallbackList<DataListener> mDataListeners = new RemoteCallbackList<>();

    private final Binder mBinder = new Binder() {
        {
            attachInterface(null, SERVICE_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TRANSACTION_REGISTER_DATA_CALLBACK: {
                    data.enforceInterface(SERVICE_DESCRIPTOR);
                    final IBinder listener = data.readStrongBinder();
                    final Set<String> events = readEventTypes(data);
                    int[] targetSocs = data.createIntArray();
                    if (targetSocs == null) {
                        targetSocs = new int[0];
                    }
                    registerDataListener(listener, events, targetSocs);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACTION_UNREGISTER_DATA_CALLBACK: {
                    data.enforceInterface(SERVICE_DESCRIPTOR);
                    final IBinder listener = data.readStrongBinder();
                    if (listener != null) {
                        mDataListeners.unregister(new DataListener(listener));
                    }
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BatteryEventService created");
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        registerBatteryReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BatteryEventService started, intent=" + intent);
        notifyWidget();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBatteryReceiver != null) {
            unregisterReceiver(mBatteryReceiver);
            mBatteryReceiver = null;
        }
        mDataListeners.kill();
        mHandlerThread.quitSafely();
        Log.d(TAG, "BatteryEventService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                int level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int percent = (level >= 0 && scale > 0)
                        ? (int) ((level / (float) scale) * 100) : -1;

                Log.d(TAG, "Battery event: action=" + action
                        + " level=" + percent + "% status=" + status);

                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    mLastBatteryIntent = intent;
                    notifyDataListeners();
                }

                notifyWidget();
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        registerReceiver(mBatteryReceiver, filter, null, mHandler);

        Log.d(TAG, "Battery receiver registered");
    }

    private void notifyWidget() {
        try {
            Intent notify = new Intent("PNW.batteryStatusChanged");
            notify.setPackage("com.google.android.settings.intelligence");
            sendBroadcast(notify);
            Log.d(TAG, "Notified Intelligence widget");
        } catch (Exception e) {
            Log.e(TAG, "Failed to notify widget: " + e);
        }
    }

    private static Set<String> readEventTypes(Parcel data) {
        final Set<String> events = new ArraySet<>();
        final int count = data.readInt();
        for (int i = 0; i < count; i++) {
            if (data.readInt() != 0) {
                events.add(data.readString());
            }
        }
        return events;
    }

    private void registerDataListener(IBinder binder, Set<String> events, int[] targetSocs) {
        if (binder == null) {
            Log.w(TAG, "registerBatteryEventsDataCallback: listener can't be null");
            return;
        }
        Log.i(TAG, "registerBatteryEventsDataCallback: uid=" + Binder.getCallingUid()
                + " events=" + events + " targetSocs=" + Arrays.toString(targetSocs));
        final DataListener listener = new DataListener(binder);
        final Subscription subscription = new Subscription(events, targetSocs);
        mDataListeners.register(listener, subscription);
        mHandler.post(() -> notifyDataListener(listener, subscription));
    }

    private void notifyDataListeners() {
        final int count = mDataListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                notifyDataListener(mDataListeners.getBroadcastItem(i),
                        (Subscription) mDataListeners.getBroadcastCookie(i));
            }
        } finally {
            mDataListeners.finishBroadcast();
        }
    }

    /** Sends the current battery state to one listener, on the handler thread. */
    private void notifyDataListener(DataListener listener, Subscription subscription) {
        final Intent batteryIntent = mLastBatteryIntent;
        if (batteryIntent == null) {
            return;
        }
        final BatteryStatus batteryStatus = new BatteryStatus(batteryIntent);

        final List<String> events = new ArrayList<>(getBatteryEvents(batteryStatus));
        events.retainAll(subscription.events);

        final Bundle bundle = new Bundle();
        bundle.putInt(KEY_CURRENT_BATTERY_LEVEL, batteryStatus.level);
        bundle.putInt(KEY_PLUGGED_TYPE, batteryStatus.plugged);
        final int secondsToFull = getSecondsToFull(batteryStatus);
        for (int soc : subscription.targetSocs) {
            // Google's SystemUI predicts each target with its battery HAL; Android only
            // estimates the time to a full charge, so other targets are left unset.
            if (soc == 100 && secondsToFull >= 0) {
                bundle.putInt(KEY_TIME_TO_TARGET_PREFIX + soc + KEY_TIME_TO_TARGET_SUFFIX,
                        secondsToFull);
            }
        }

        final String state = events + " " + bundle;
        if (state.equals(subscription.lastState)) {
            return;
        }

        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATA_LISTENER_DESCRIPTOR);
            data.writeInt(events.size());
            for (String event : events) {
                data.writeInt(1);
                data.writeString(event);
            }
            data.writeInt(1);
            bundle.writeToParcel(data, 0);
            listener.asBinder().transact(
                    TRANSACTION_ON_BATTERY_EVENT_DATA_CHANGED, data, reply, 0);
            reply.readException();
            subscription.lastState = state;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Error notifying data callback", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** The event types Google's SystemUI reports from the battery broadcast and settings. */
    private Set<String> getBatteryEvents(BatteryStatus batteryStatus) {
        final Set<String> events = new ArraySet<>();
        if (!batteryStatus.isPluggedIn()) {
            return events;
        }
        if (batteryStatus.status == BatteryManager.BATTERY_STATUS_FULL
                || batteryStatus.level >= 100) {
            events.add(EVENT_FULL_CHARGED);
        }
        if (batteryStatus.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            events.add(EVENT_NOT_CHARGING);
        }
        if (batteryStatus.chargingStatus == BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE
                && Settings.Secure.getInt(getContentResolver(),
                        CHARGE_OPTIMIZATION_MODE, 0) == 1) {
            events.add(EVENT_CHARGING_LIMIT);
        }
        switch (batteryStatus.getChargingSpeed(this)) {
            case BatteryStatus.CHARGING_FAST:
            case BatteryStatus.CHARGING_OEM:
                events.add(EVENT_FAST_CHARGING);
                break;
            case BatteryStatus.CHARGING_REGULAR:
                events.add(EVENT_REGULAR_CHARGING);
                break;
            case BatteryStatus.CHARGING_SLOWLY:
                events.add(EVENT_SLOW_CHARGING);
                break;
        }
        return events;
    }

    private int getSecondsToFull(BatteryStatus batteryStatus) {
        if (!batteryStatus.isPluggedIn()) {
            return -1;
        }
        final long millis = getSystemService(BatteryManager.class).computeChargeTimeRemaining();
        return millis >= 0 ? (int) (millis / 1000) : -1;
    }

    private static final class DataListener implements IInterface {
        private final IBinder mBinder;

        DataListener(IBinder binder) {
            mBinder = binder;
        }

        @Override
        public IBinder asBinder() {
            return mBinder;
        }
    }

    private static final class Subscription {
        final Set<String> events;
        final int[] targetSocs;
        String lastState;

        Subscription(Set<String> events, int[] targetSocs) {
            this.events = events;
            this.targetSocs = targetSocs;
        }
    }
}
