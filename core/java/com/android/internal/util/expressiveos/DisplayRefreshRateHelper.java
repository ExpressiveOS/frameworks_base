/*
 * Copyright (C) 2022-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.expressiveos;

import static android.provider.Settings.System.MIN_REFRESH_RATE;
import static android.provider.Settings.System.PEAK_REFRESH_RATE;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Comparator;

/** @hide */
public class DisplayRefreshRateHelper {

    private static final float DEFAULT_REFRESH_RATE = 60f;
    /** No minimum: the display may go as low as the panel allows. */
    public static final int ADAPTIVE_REFRESH_RATE = 0;

    private static DisplayRefreshRateHelper sInstance = null;

    private final ArrayList<Integer> mRefreshRateList = new ArrayList<>();
    private final Context mContext;

    public static DisplayRefreshRateHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DisplayRefreshRateHelper(context);
        }
        return sInstance;
    }

    private DisplayRefreshRateHelper(Context context) {
        mContext = context;
        initialize();
    }

    private void initialize() {
        final Display.Mode mode = mContext.getDisplay().getMode();
        final Display.Mode[] modes = mContext.getDisplay().getSupportedModes();
        for (Display.Mode m : modes) {
            if (m.getPhysicalWidth() == mode.getPhysicalWidth() &&
                    m.getPhysicalHeight() == mode.getPhysicalHeight()) {
                mRefreshRateList.add((int) m.getRefreshRate());
            }
        }
        mRefreshRateList.sort(Comparator.naturalOrder());
    }

    public ArrayList<Integer> getSupportedRefreshRateList() {
        return mRefreshRateList;
    }

    /**
     * The minimum refresh rate the user set, or {@link #ADAPTIVE_REFRESH_RATE} (0) when there is
     * none: the display then picks any rate it supports, as DisplayModeDirector's default does.
     */
    public int getMinimumRefreshRate() {
        final int ret = (int) Settings.System.getFloatForUser(mContext.getContentResolver(),
                MIN_REFRESH_RATE, ADAPTIVE_REFRESH_RATE, UserHandle.USER_SYSTEM);
        if (ret <= ADAPTIVE_REFRESH_RATE) {
            return ADAPTIVE_REFRESH_RATE;
        }
        if (mRefreshRateList.size() != 0 && !mRefreshRateList.contains(ret)) {
            return mRefreshRateList.get(mRefreshRateList.size() - 1);
        }
        return ret;
    }

    public int getPeakRefreshRate() {
        final int refreshRate = mContext.getResources().getInteger(
                R.integer.config_defaultPeakRefreshRate);
        final float defaultPeakRefreshRate = refreshRate != 0 ? (float) refreshRate : DEFAULT_REFRESH_RATE;
        final int ret = (int) Settings.System.getFloatForUser(mContext.getContentResolver(),
                PEAK_REFRESH_RATE, defaultPeakRefreshRate, UserHandle.USER_SYSTEM);
        if (mRefreshRateList.size() != 0 && !mRefreshRateList.contains(ret)) {
            return mRefreshRateList.get(mRefreshRateList.size() - 1);
        }
        return ret;
    }

    public ArrayList<Integer> getRefreshRate() {
        final ArrayList<Integer> ret = new ArrayList<>();
        ret.add(getMinimumRefreshRate());
        ret.add(getPeakRefreshRate());
        return ret;
    }

    public void setMinimumRefreshRate(int refreshRate) {
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                MIN_REFRESH_RATE, (float) refreshRate, UserHandle.USER_SYSTEM);
    }

    public void setPeakRefreshRate(int refreshRate) {
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                PEAK_REFRESH_RATE, (float) refreshRate, UserHandle.USER_SYSTEM);
    }

    public void setRefreshRate(int minRefreshRate, int peakRefreshRate) {
        setMinimumRefreshRate(minRefreshRate);
        setPeakRefreshRate(peakRefreshRate);
    }

    public boolean isRefreshRateValid(int refreshRate) {
        return mRefreshRateList.contains(refreshRate);
    }
}
