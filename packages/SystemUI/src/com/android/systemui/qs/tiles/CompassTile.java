/*
 * Copyright (C) 2019-2024 crDroid Android Project
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

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

public class CompassTile extends QSTileImpl<BooleanState> implements SensorEventListener {

    public static final String TILE_SPEC = "compass";

    private final static float ALPHA = 0.97f;
    private static final long MIN_UPDATE_INTERVAL_MS = 25L;
    private static final float MIN_DEGREE_DELTA = 1.0f;

    private boolean mActive = false;

    private SensorManager mSensorManager;
    private Sensor mAccelerationSensor;
    private Sensor mGeomagneticFieldSensor;

    private float[] mAcceleration;
    private float[] mGeomagnetic;
    private final float[] mRotationMatrix = new float[9];
    private final float[] mInclinationMatrix = new float[9];
    private final float[] mOrientation = new float[3];

    private boolean mListeningSensors;
    private long mLastUpdateTimeMs = 0L;
    private float mLastReportedDegree = Float.NaN;

    private Drawable mBaseCompassDrawable;
    private final Bitmap[] mRotatedBitmaps = new Bitmap[2];
    private final Canvas[] mRotatedCanvases = new Canvas[2];
    private final BitmapDrawable[] mRotatedDrawables = new BitmapDrawable[2];
    private int mBufferIndex = 0;

    @Inject
    public CompassTile(QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mGeomagneticFieldSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        setListeningSensors(false);
        mSensorManager = null;
        mBaseCompassDrawable = null;
        for (int i = 0; i < mRotatedBitmaps.length; i++) {
            mRotatedBitmaps[i] = null;
            mRotatedCanvases[i] = null;
            mRotatedDrawables[i] = null;
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mActive = !mActive;
        refreshState();
        setListeningSensors(mActive);
    }

    @Override
    public void handleLongClick(@Nullable Expandable expandable) {
        handleClick(expandable);
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    private void setListeningSensors(boolean listening) {
        if (listening == mListeningSensors) return;
        if (mSensorManager == null) {
            mListeningSensors = false;
            return;
        }
        if (listening && (mAccelerationSensor == null || mGeomagneticFieldSensor == null)) {
            mListeningSensors = false;
            return;
        }
        mListeningSensors = listening;
        if (mListeningSensors) {
            mLastUpdateTimeMs = 0L;
            mLastReportedDegree = Float.NaN;
            mSensorManager.registerListener(
                    this, mAccelerationSensor, SensorManager.SENSOR_DELAY_GAME);
            mSensorManager.registerListener(
                    this, mGeomagneticFieldSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_compass_label);
    }

    private Drawable getRotatedCompassDrawable(float degrees) {
        if (mBaseCompassDrawable == null) {
            mBaseCompassDrawable = mContext.getDrawable(R.drawable.ic_qs_compass);
            if (mBaseCompassDrawable == null) {
                return null;
            }
            int w = mBaseCompassDrawable.getIntrinsicWidth();
            int h = mBaseCompassDrawable.getIntrinsicHeight();
            if (w <= 0) w = 64;
            if (h <= 0) h = 64;
            int size = Math.max(w, h);
            int left = (size - w) / 2;
            int top = (size - h) / 2;
            mBaseCompassDrawable.setBounds(left, top, left + w, top + h);
            for (int i = 0; i < mRotatedBitmaps.length; i++) {
                mRotatedBitmaps[i] = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                mRotatedCanvases[i] = new Canvas(mRotatedBitmaps[i]);
                mRotatedDrawables[i] = new BitmapDrawable(mContext.getResources(), mRotatedBitmaps[i]);
            }
        }

        final int idx = mBufferIndex;
        mBufferIndex ^= 1;

        final Canvas canvas = mRotatedCanvases[idx];
        final Bitmap bitmap = mRotatedBitmaps[idx];
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.save();
        canvas.rotate(degrees, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        mBaseCompassDrawable.draw(canvas);
        canvas.restore();
        return mRotatedDrawables[idx];
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final float degrees = arg == null ? 0f : (Float) arg;

        state.value = mActive;

        if (state.value) {
            state.state = Tile.STATE_ACTIVE;
            if (arg != null) {
                state.label = formatValueWithCardinalDirection(degrees);
            } else {
                state.label = mContext.getString(R.string.quick_settings_compass_init);
            }
        } else {
            state.label = mContext.getString(R.string.quick_settings_compass_label);
            state.state = Tile.STATE_INACTIVE;
        }
        Drawable iconDrawable = getRotatedCompassDrawable(degrees);
        if (iconDrawable != null) {
            state.icon = new DrawableIcon(iconDrawable);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_PANEL;
    }

    @Override
    public boolean isAvailable() {
        return mSensorManager != null && mAccelerationSensor != null
                && mGeomagneticFieldSensor != null;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (!listening) {
            setListeningSensors(false);
            mActive = false;
        }
    }

    private String formatValueWithCardinalDirection(float degree) {
        float normalized = ((degree % 360f) + 360f) % 360f;
        int cardinalDirectionIndex = ((int) Math.floor(((normalized + 22.5) % 360) / 45)) % 8;
        String[] cardinalDirections = mContext.getResources().getStringArray(
                R.array.cardinal_directions);

        return mContext.getString(R.string.quick_settings_compass_value, normalized,
                cardinalDirections[cardinalDirectionIndex]);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (mAcceleration == null) {
                mAcceleration = event.values.clone();
            }

            values = mAcceleration;
        } else {
            // Magnetic field sensor
            if (mGeomagnetic == null) {
                mGeomagnetic = event.values.clone();
            }

            values = mGeomagnetic;
        }

        for (int i = 0; i < 3; i++) {
            values[i] = ALPHA * values[i] + (1 - ALPHA) * event.values[i];
        }

        if (!mActive || !mListeningSensors || mAcceleration == null || mGeomagnetic == null) {
            // Nothing to do at this moment
            return;
        }

        if (!SensorManager.getRotationMatrix(
                mRotationMatrix, mInclinationMatrix, mAcceleration, mGeomagnetic)) {
            // Rotation matrix couldn't be calculated
            return;
        }

        // Get the current orientation
        SensorManager.getOrientation(mRotationMatrix, mOrientation);

        // Convert azimuth to degrees
        float newDegree = (float) Math.toDegrees(mOrientation[0]);
        newDegree = (newDegree + 360f) % 360f;

        // Convert the angle to one that points north relative to the device
        newDegree = (360f - newDegree) % 360f;

        final long now = SystemClock.elapsedRealtime();
        if (!Float.isNaN(mLastReportedDegree)) {
            if (now - mLastUpdateTimeMs < MIN_UPDATE_INTERVAL_MS) {
                return;
            }
            float delta = Math.abs(newDegree - mLastReportedDegree);
            if (delta > 180f) {
                delta = 360f - delta;
            }
            if (delta < MIN_DEGREE_DELTA) {
                return;
            }
        }
        mLastUpdateTimeMs = now;
        mLastReportedDegree = newDegree;

        refreshState(Float.valueOf(newDegree));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // noop
    }
}
