/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The ExpressiveOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.expressiveos;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.R;

import java.util.List;

/**
 * Helpers behind the "close app" and "last app" gesture actions.
 *
 * @hide
 */
public class ActionUtils {
    private static final boolean DEBUG = false;
    private static final String TAG = ActionUtils.class.getSimpleName();
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * Kills the top most / most recent user application, but leaves out the launcher.
     *
     * @param context the current context, used to retrieve the package manager.
     * @param userId the ID of the currently active user
     * @return {@code true} when a user application was found and closed.
     */
    public static boolean killForegroundApp(Context context, int userId) {
        try {
            return killForegroundAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not kill foreground app");
        }
        return false;
    }

    private static boolean killForegroundAppInternal(Context context, int userId)
            throws RemoteException {
        final IActivityTaskManager atm = ActivityTaskManager.getService();

        if (atm.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_PINNED) {
            return false;
        }

        final RootTaskInfo foregroundTask = getForegroundTask(context, userId);
        if (foregroundTask == null) {
            return false;
        }

        final String packageName = foregroundTask.topActivity.getPackageName();
        if (packageName == null) {
            return false;
        }

        final IActivityManager am = ActivityManager.getService();
        am.forceStopPackage(packageName, foregroundTask.userId);

        am.removeTask(foregroundTask.taskId);

        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context, R.string.app_killed_message, Toast.LENGTH_SHORT).show();
        });

        return true;
    }

    /**
     * Attempt to bring up the last activity in the stack before the current active one.
     *
     * @param context the current context, used to retrieve the package manager.
     * @param userId the ID of the currently active user
     * @return whether an activity was found to switch to
     */
    public static boolean switchToLastApp(Context context, int userId) {
        try {
            return switchToLastAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not switch to last app");
        }
        return false;
    }

    private static boolean switchToLastAppInternal(Context context, int userId)
            throws RemoteException {
        final ActivityManager.RecentTaskInfo lastTask = getLastTask(context, userId);

        if (lastTask == null || lastTask.id < 0) {
            return false;
        }

        if (DEBUG) {
            Log.d(TAG, "switching to " + lastTask.baseIntent.getComponent().getPackageName());
        }
        final IActivityManager am = ActivityManager.getService();
        am.moveTaskToFront(null, null, lastTask.id,
                ActivityManager.MOVE_TASK_NO_USER_ACTION, getLastAppAnimation(context));

        return true;
    }

    /**
     * The slide animation of the last app switch is optional: it is only used when the platform
     * resources ship the {@code last_app_in} and {@code last_app_out} animations, otherwise the
     * default task transition plays.
     */
    private static Bundle getLastAppAnimation(Context context) {
        final Resources res = context.getResources();
        final int enterAnim = res.getIdentifier("last_app_in", "anim", "android");
        final int exitAnim = res.getIdentifier("last_app_out", "anim", "android");
        if (enterAnim == 0 || exitAnim == 0) {
            return null;
        }
        return ActivityOptions.makeCustomAnimation(context, enterAnim, exitAnim).toBundle();
    }

    private static ActivityManager.RecentTaskInfo getLastTask(Context context, int userId)
            throws RemoteException {
        final String defaultHomePackage = resolveCurrentLauncherPackage(context, userId);
        final IActivityManager am = ActivityManager.getService();
        final List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(5,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId).getList();

        for (int i = 1; i < tasks.size(); i++) {
            final ActivityManager.RecentTaskInfo task = tasks.get(i);
            if (task.baseIntent == null) {
                continue;
            }
            if (task.origActivity != null) {
                task.baseIntent.setComponent(task.origActivity);
            }
            if (task.baseIntent.getComponent() == null) {
                continue;
            }
            final String packageName = task.baseIntent.getComponent().getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals(SYSTEMUI_PACKAGE)) {
                return task;
            }
        }

        return null;
    }

    private static RootTaskInfo getForegroundTask(Context context, int userId)
            throws RemoteException {
        final String defaultHomePackage = resolveCurrentLauncherPackage(context, userId);
        final IActivityManager am = ActivityManager.getService();
        final RootTaskInfo focusedStack = am.getFocusedRootTaskInfo();

        if (focusedStack == null || focusedStack.topActivity == null) {
            return null;
        }

        final String packageName = focusedStack.topActivity.getPackageName();
        if (!packageName.equals(defaultHomePackage)
                && !packageName.equals(SYSTEMUI_PACKAGE)) {
            return focusedStack;
        }

        return null;
    }

    private static String resolveCurrentLauncherPackage(Context context, int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivityAsUser(launcherIntent, 0, userId);

        if (launcherInfo != null && launcherInfo.activityInfo != null
                && !launcherInfo.activityInfo.packageName.equals("android")) {
            return launcherInfo.activityInfo.packageName;
        }

        return null;
    }
}
