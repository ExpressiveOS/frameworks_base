/*
 * Copyright (C) 2026 The ExpressiveOS Project
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

package android.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.ScrollView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * In-process universal scroll-to-top and 5-second jump-back undo engine for {@link ViewRootImpl}.
 *
 * <p>Extends AOSP's native {@link View#dispatchScrollToTop(int)} with zero public API changes,
 * adding support for {@link WebView}, AndroidX {@code RecyclerView}, {@code NestedScrollView},
 * {@code CoordinatorLayout}/{@code AppBarLayout}, R8-obfuscated virtualized lists, Jetpack
 * Compose ({@code AndroidComposeView}), and Chromium {@link AccessibilityNodeProvider} trees.
 *
 * @hide
 */
public final class ExpressiveScrollToTopHelper {

    /** Duration during which a second status-bar tap at the top jumps back to prior position. */
    public static final long UNDO_WINDOW_MS = 5_000L;

    /** Minimum elapsed time before a second tap is treated as jump-back rather than mid-flight. */
    public static final long IN_FLIGHT_SETTLE_GUARD_MS = 350L;

    /** Threshold above which RecyclerView teleports to {@link #RECYCLER_TELEPORT_TARGET} first. */
    public static final int RECYCLER_TELEPORT_THRESHOLD = 14;

    /** Item index to teleport to before running smoothScrollToPosition(0) on long feeds. */
    public static final int RECYCLER_TELEPORT_TARGET = 12;

    /** Maximum virtual accessibility page/scroll actions per Choreographer frame. */
    public static final int A11Y_ACTIONS_PER_FRAME = 4;

    /** Maximum total virtual accessibility page/scroll actions per scroll-to-top burst. */
    public static final int MAX_A11Y_BURST_STEPS = 32;

    private static final long SCROLL_Y_ANIM_DURATION_MS = 250L;
    private static final int MAX_SYNC_SCROLL_BY_STEPS = 64;
    private static final int OBFUSCATED_SMOOTH_FRAMES = 8;
    private static final int MAX_A11Y_TRAVERSAL_NODES = 256;

    private static final DecelerateInterpolator sDecelerateInterpolator =
            new DecelerateInterpolator();

    /** Fallback UI-thread state map when invoked without a hosting {@link ViewRootImpl}. */
    private static final WeakHashMap<View, ExpressiveScrollToTopHelper> sDetachedHelpers =
            new WeakHashMap<>();

    private static final ConcurrentHashMap<Class<?>, ViewReflectionInfo> sViewReflectionCache =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, LayoutManagerReflectionInfo>
            sLayoutManagerCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, AppBarReflectionInfo> sAppBarCache =
            new ConcurrentHashMap<>();

    private final LongSupplier mUptimeMillisSupplier;
    private final BooleanSupplier mAnimatorsEnabledSupplier;
    private final Predicate<Context> mRestoreEnabledPredicate;
    private final Consumer<Runnable> mFrameScheduler;

    @Nullable
    private ScrollUndoSnapshot mUndoSnapshot;
    @Nullable
    private InFlightBurst mActiveBurst;
    @Nullable
    private WeakReference<ObjectAnimator> mActiveAnimatorRef;

    public ExpressiveScrollToTopHelper() {
        this(
                SystemClock::uptimeMillis,
                ValueAnimator::areAnimatorsEnabled,
                ExpressiveScrollToTopHelper::isRestoreEnabledFromSettings,
                ExpressiveScrollToTopHelper::scheduleOnNextChoreographerFrame);
    }

    @VisibleForTesting
    public ExpressiveScrollToTopHelper(
            @NonNull LongSupplier uptimeMillisSupplier,
            @NonNull BooleanSupplier animatorsEnabledSupplier,
            @NonNull Predicate<Context> restoreEnabledPredicate,
            @NonNull Consumer<Runnable> frameScheduler) {
        mUptimeMillisSupplier = uptimeMillisSupplier;
        mAnimatorsEnabledSupplier = animatorsEnabledSupplier;
        mRestoreEnabledPredicate = restoreEnabledPredicate;
        mFrameScheduler = frameScheduler;
    }

    /**
     * Static entry point wired from {@link ViewRootImpl#dispatchScrollToTop(int)}.
     */
    public static boolean handleScrollToTop(
            @Nullable ViewRootImpl viewRoot, @Nullable View rootView, int windowX) {
        if (rootView == null) {
            return false;
        }
        final ExpressiveScrollToTopHelper helper;
        if (viewRoot != null) {
            if (viewRoot.mExpressiveScrollToTopHelper == null) {
                viewRoot.mExpressiveScrollToTopHelper = new ExpressiveScrollToTopHelper();
            }
            helper = viewRoot.mExpressiveScrollToTopHelper;
        } else {
            ExpressiveScrollToTopHelper cached = sDetachedHelpers.get(rootView);
            if (cached == null) {
                cached = new ExpressiveScrollToTopHelper();
                sDetachedHelpers.put(rootView, cached);
            }
            helper = cached;
        }
        return helper.handleScrollToTop(rootView, windowX);
    }

    /**
     * Clears any pending 5-second jump-back undo snapshot immediately on user screen touch.
     */
    public static void onUserTouchDown(@Nullable ViewRootImpl viewRoot) {
        if (viewRoot != null && viewRoot.mExpressiveScrollToTopHelper != null) {
            viewRoot.mExpressiveScrollToTopHelper.onUserTouchDown();
        }
    }

    /**
     * Cleans up helper state and cancels any pending callbacks when the window detaches.
     */
    public static void onDetachedFromWindow(@Nullable ViewRootImpl viewRoot) {
        if (viewRoot != null && viewRoot.mExpressiveScrollToTopHelper != null) {
            viewRoot.mExpressiveScrollToTopHelper.reset();
            viewRoot.mExpressiveScrollToTopHelper = null;
        }
    }

    /**
     * Cancels in-flight bursts/animations and invalidates any saved undo position on user touch.
     */
    public void onUserTouchDown() {
        cancelInFlightAnimations();
        mUndoSnapshot = null;
    }

    /**
     * Resets all helper state.
     */
    public void reset() {
        cancelInFlightAnimations();
        mUndoSnapshot = null;
    }

    /**
     * Handles a status-bar scroll-to-top (or 5-second jump-back undo) command on the UI thread.
     *
     * @param rootView Root view of the window.
     * @param windowX  Horizontal tap coordinate in {@code rootView} window space.
     * @return {@code true} if content was scrolled or restored (and {@code CLOCK_TICK} played).
     */
    public boolean handleScrollToTop(@NonNull View rootView, int windowX) {
        if (!rootView.canReceivePointerEvents()) {
            return false;
        }

        final long now = mUptimeMillisSupplier.getAsLong();
        final boolean animatorsEnabled = mAnimatorsEnabledSupplier.getAsBoolean();

        // 1. Check in-flight scroll-to-top or 5-second Jump-Back Undo.
        if (mUndoSnapshot != null) {
            final ScrollUndoSnapshot snap = mUndoSnapshot;
            final boolean burstRunning = mActiveBurst != null && mActiveBurst.isRunning();
            final boolean animatorRunning = isAnimatorRunning();
            final boolean recyclerSettling = !snap.settledAtTop && isRecyclerSettling(snap);
            final boolean withinSettleGuard = animatorsEnabled
                    && !snap.settledAtTop
                    && (now - snap.triggeredAtUptimeMillis) < IN_FLIGHT_SETTLE_GUARD_MS;

            if (burstRunning || animatorRunning || recyclerSettling || withinSettleGuard) {
                cancelInFlightAnimations();
                settleSnapshotTargetAtTopInstant(snap);
                snap.settledAtTop = true;
                snap.expiresAtUptimeMillis = now + UNDO_WINDOW_MS;
                return false;
            }

            mUndoSnapshot = null;
            if (now <= snap.expiresAtUptimeMillis
                    && mRestoreEnabledPredicate.test(rootView.getContext())
                    && canRestoreSnapshot(snap, rootView)) {
                if (restoreSnapshot(snap, animatorsEnabled)) {
                    rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                }
            }
        }

        // 2. Collect visible candidate views ordered by (intersectsWindowX, visualTopY, Z, order).
        final ArrayList<CandidateView> candidates = new ArrayList<>();
        collectCandidates(rootView, rootView, windowX, new Matrix(), new RectF(), candidates);
        Collections.sort(candidates);

        // 3. Multi-toolkit dispatch.
        // Determine whether an intersecting non-native candidate should take precedence over a
        // non-intersecting native ScrollView/AbsListView in another split/tab pane.
        final CandidateView topNative = findTopNativeScrollCandidate(candidates);
        final boolean hasIntersectingNative = topNative != null && topNative.intersectsWindowX;
        final boolean hasIntersectingNonNative = hasIntersectingNonNativeCandidate(
                candidates, rootView, windowX);

        boolean consumed = false;
        if (hasIntersectingNative || !hasIntersectingNonNative) {
            consumed = tryNativeDispatch(rootView, windowX, topNative, animatorsEnabled, now);
        }

        if (!consumed) {
            consumed = tryMultiToolkitCandidates(
                    rootView, windowX, candidates, animatorsEnabled, now);
        }

        if (!consumed && !hasIntersectingNative && hasIntersectingNonNative && topNative != null) {
            consumed = tryNativeDispatch(rootView, windowX, topNative, animatorsEnabled, now);
        }

        if (consumed) {
            rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        return consumed;
    }

    private boolean tryNativeDispatch(
            @NonNull View rootView,
            int windowX,
            @Nullable CandidateView expectedNative,
            boolean animatorsEnabled,
            long now) {
        ScrollUndoSnapshot candidateSnap = null;
        View appBar = null;
        boolean appBarCollapsed = false;

        if (expectedNative != null) {
            final View v = expectedNative.view;
            appBar = findAssociatedAppBarLayout(v, rootView);
            appBarCollapsed = appBar != null && isAppBarCollapsed(appBar);
            if (v instanceof ScrollView) {
                final ScrollView sv = (ScrollView) v;
                if (sv.isScrollToTopEnabled() && sv.getScrollY() > 0) {
                    candidateSnap = ScrollUndoSnapshot.forScrollY(
                            sv, sv.getScrollX(), sv.getScrollY(), appBar, appBarCollapsed,
                            !animatorsEnabled, now);
                }
            } else if (v instanceof AbsListView) {
                final AbsListView lv = (AbsListView) v;
                if (lv.isScrollToTopEnabled() && !isAbsListViewAtTop(lv)) {
                    final int firstPos = lv.getFirstVisiblePosition();
                    final View firstChild = lv.getChildAt(0);
                    final int topOffset = firstChild != null
                            ? (firstChild.getTop() - lv.getListPaddingTop()) : 0;
                    candidateSnap = ScrollUndoSnapshot.forAbsListView(
                            lv, firstPos, topOffset, appBar, appBarCollapsed,
                            !animatorsEnabled, now);
                }
            }
        }

        if (!rootView.dispatchScrollToTop(windowX)) {
            return false;
        }

        if (candidateSnap != null) {
            final View v = candidateSnap.targetRef.get();
            if (!animatorsEnabled && v != null) {
                if (v instanceof ScrollView) {
                    final ScrollView sv = (ScrollView) v;
                    // Calling smoothScrollBy(0, 0) within ANIMATED_SCROLL_GAP aborts mScroller.
                    sv.smoothScrollBy(0, 0);
                    sv.scrollTo(sv.getScrollX(), 0);
                } else if (v instanceof AbsListView) {
                    final AbsListView lv = (AbsListView) v;
                    lv.smoothScrollBy(0, 0);
                    lv.setSelectionFromTop(0, 0);
                }
            }
            if (appBarCollapsed && appBar != null) {
                setAppBarExpanded(appBar, true, animatorsEnabled);
            }
            mUndoSnapshot = candidateSnap;
        }
        return true;
    }

    private boolean tryMultiToolkitCandidates(
            @NonNull View rootView,
            int windowX,
            @NonNull List<CandidateView> candidates,
            boolean animatorsEnabled,
            long now) {
        // Pass 1: Scrollable views (WebView, RecyclerView, NestedScrollView/scrollY, R8 list).
        for (int i = 0, n = candidates.size(); i < n; i++) {
            final View v = candidates.get(i).view;
            if (v instanceof ScrollView || v instanceof AbsListView) {
                continue;
            }

            if (v instanceof WebView) {
                if (tryScrollWebView((WebView) v, rootView, animatorsEnabled, now)) {
                    return true;
                }
                continue;
            }

            final ViewReflectionInfo info = getViewReflectionInfo(v.getClass());
            if (info.isRecyclerView) {
                if (tryScrollRecyclerView(v, info, rootView, animatorsEnabled, now)) {
                    return true;
                }
                continue;
            }

            if (v.getScrollY() > 0 && v.canScrollVertically(-1)) {
                if (tryScrollViewByScrollY(v, info, rootView, animatorsEnabled, now)) {
                    return true;
                }
                continue;
            }

            if (!info.isComposeView
                    && info.overridesScrollByOrTo
                    && v.getScrollY() == 0
                    && v.canScrollVertically(-1)) {
                if (tryScrollVirtualizedByOffset(v, rootView, animatorsEnabled, now)) {
                    return true;
                }
            }
        }

        // Pass 2: Collapsed AppBarLayout when underlying content is already at item 0.
        for (int i = 0, n = candidates.size(); i < n; i++) {
            final View v = candidates.get(i).view;
            if (isAppBarLayout(v) && isAppBarCollapsed(v)) {
                if (setAppBarExpanded(v, true, animatorsEnabled)) {
                    mUndoSnapshot = ScrollUndoSnapshot.forAppBarOnly(
                            v, !animatorsEnabled, now);
                    return true;
                }
            }
        }

        // Pass 3: Jetpack Compose (AndroidComposeView) & Chromium AccessibilityNodeProvider.
        final int[] rootScreenLoc = new int[2];
        rootView.getLocationOnScreen(rootScreenLoc);
        final int screenX = rootScreenLoc[0] + windowX;

        for (int i = 0, n = candidates.size(); i < n; i++) {
            final View v = candidates.get(i).view;
            final AccessibilityNodeProvider provider = v.getAccessibilityNodeProvider();
            if (provider != null) {
                if (tryScrollAccessibilityProvider(v, provider, screenX, animatorsEnabled, now)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean tryScrollWebView(
            @NonNull WebView wv, @NonNull View rootView, boolean animatorsEnabled, long now) {
        final int prevScrollX = wv.getScrollX();
        final int prevScrollY = wv.getScrollY();
        if (prevScrollY <= 0 && !wv.canScrollVertically(-1)) {
            return false;
        }

        final View appBar = findAssociatedAppBarLayout(wv, rootView);
        final boolean appBarCollapsed = appBar != null && isAppBarCollapsed(appBar);

        boolean scrolled = wv.pageUp(true);
        if (!animatorsEnabled && wv.getScrollY() > 0) {
            wv.scrollTo(prevScrollX, 0);
            scrolled = true;
        } else if (!scrolled && prevScrollY > 0) {
            animateOrSnapScrollY(wv, null, prevScrollX, 0, animatorsEnabled);
            scrolled = true;
        }

        if (!scrolled) {
            return false;
        }

        if (appBarCollapsed) {
            setAppBarExpanded(appBar, true, animatorsEnabled);
        }
        if (prevScrollY > 0 || appBarCollapsed) {
            mUndoSnapshot = ScrollUndoSnapshot.forWebView(
                    wv, prevScrollX, prevScrollY, appBar, appBarCollapsed,
                    !animatorsEnabled, now);
        }
        return true;
    }

    private boolean tryScrollRecyclerView(
            @NonNull View rv,
            @NonNull ViewReflectionInfo info,
            @NonNull View rootView,
            boolean animatorsEnabled,
            long now) {
        Object lm = null;
        LayoutManagerReflectionInfo lmInfo = null;
        if (info.getLayoutManager != null) {
            try {
                lm = info.getLayoutManager.invoke(rv);
                if (lm != null) {
                    lmInfo = getLayoutManagerReflectionInfo(lm.getClass());
                }
            } catch (Throwable ignored) {
            }
        }

        final int firstPos = resolveRecyclerFirstVisiblePosition(rv, info, lm, lmInfo);
        final int topOffsetPx = resolveRecyclerTopOffsetPx(rv, firstPos, lm, lmInfo);
        final boolean canScrollUp = rv.canScrollVertically(-1)
                || firstPos > 0
                || (firstPos == 0 && topOffsetPx < 0);
        if (!canScrollUp) {
            return false;
        }

        final View appBar = findAssociatedAppBarLayout(rv, rootView);
        final boolean appBarCollapsed = appBar != null && isAppBarCollapsed(appBar);

        try {
            if (!animatorsEnabled) {
                info.scrollToPosition.invoke(rv, 0);
            } else if (firstPos > RECYCLER_TELEPORT_THRESHOLD) {
                info.scrollToPosition.invoke(rv, RECYCLER_TELEPORT_TARGET);
                info.smoothScrollToPosition.invoke(rv, 0);
            } else {
                info.smoothScrollToPosition.invoke(rv, 0);
            }
        } catch (Throwable t) {
            return false;
        }

        if (appBarCollapsed) {
            setAppBarExpanded(appBar, true, animatorsEnabled);
        }

        mUndoSnapshot = ScrollUndoSnapshot.forRecyclerView(
                rv, Math.max(0, firstPos), topOffsetPx, appBar, appBarCollapsed,
                !animatorsEnabled, now);
        return true;
    }

    private boolean tryScrollViewByScrollY(
            @NonNull View v,
            @NonNull ViewReflectionInfo info,
            @NonNull View rootView,
            boolean animatorsEnabled,
            long now) {
        final int prevScrollX = v.getScrollX();
        final int prevScrollY = v.getScrollY();
        if (prevScrollY <= 0) {
            return false;
        }

        final View appBar = findAssociatedAppBarLayout(v, rootView);
        final boolean appBarCollapsed = appBar != null && isAppBarCollapsed(appBar);

        animateOrSnapScrollY(v, info, prevScrollX, 0, animatorsEnabled);
        if (appBarCollapsed) {
            setAppBarExpanded(appBar, true, animatorsEnabled);
        }

        mUndoSnapshot = ScrollUndoSnapshot.forScrollY(
                v, prevScrollX, prevScrollY, appBar, appBarCollapsed, !animatorsEnabled, now);
        return true;
    }

    private boolean tryScrollVirtualizedByOffset(
            @NonNull View v,
            @NonNull View rootView,
            boolean animatorsEnabled,
            long now) {
        final int initialOffset = Math.max(0, v.computeVerticalScrollOffset());
        if (!v.canScrollVertically(-1) && initialOffset <= 0) {
            return false;
        }

        final View appBar = findAssociatedAppBarLayout(v, rootView);
        final boolean appBarCollapsed = appBar != null && isAppBarCollapsed(appBar);

        int totalScrolledPx = 0;
        final int viewHeight = Math.max(1, v.getHeight());

        if (!animatorsEnabled) {
            totalScrolledPx = scrollVirtualizedUpSynchronously(v, Integer.MAX_VALUE);
            if (totalScrolledPx <= 0) {
                return false;
            }
            if (appBarCollapsed) {
                setAppBarExpanded(appBar, true, false);
            }
            mUndoSnapshot = ScrollUndoSnapshot.forVirtualizedOffset(
                    v, totalScrolledPx, appBar, appBarCollapsed, true, now);
            return true;
        }

        // Fast-teleport if deep (> 3 viewports), leaving ~2 viewports for smooth frame settle.
        final int smoothBudgetPx = viewHeight * 2;
        if (initialOffset > viewHeight * 3) {
            final int teleportDelta = initialOffset - smoothBudgetPx;
            final int before = Math.max(0, v.computeVerticalScrollOffset());
            v.scrollBy(0, -teleportDelta);
            final int after = Math.max(0, v.computeVerticalScrollOffset());
            totalScrolledPx += (before > after) ? (before - after) : teleportDelta;
        }

        // Perform first smooth step synchronously to verify movement before firing CLOCK_TICK.
        final int remainingEst = Math.max(v.computeVerticalScrollOffset(), viewHeight);
        final int firstStep = Math.max(1, (remainingEst + OBFUSCATED_SMOOTH_FRAMES - 1)
                / OBFUSCATED_SMOOTH_FRAMES);
        final int stepMoved = stepVirtualizedUpOnce(v, firstStep);
        totalScrolledPx += stepMoved;

        if (totalScrolledPx <= 0) {
            return false;
        }

        if (appBarCollapsed) {
            setAppBarExpanded(appBar, true, true);
        }

        final boolean alreadyAtTop = !v.canScrollVertically(-1);
        final ScrollUndoSnapshot snap = ScrollUndoSnapshot.forVirtualizedOffset(
                v, totalScrolledPx, appBar, appBarCollapsed, alreadyAtTop, now);
        mUndoSnapshot = snap;

        if (!alreadyAtTop) {
            startVirtualizedSmoothBurst(v, snap, Math.max(64, firstStep));
        }
        return true;
    }

    private int scrollVirtualizedUpSynchronously(@NonNull View v, int maxSteps) {
        int totalMoved = 0;
        final int fallbackChunk = Math.max(256, v.getHeight());
        for (int i = 0; i < Math.min(maxSteps, MAX_SYNC_SCROLL_BY_STEPS); i++) {
            if (!v.canScrollVertically(-1)) {
                break;
            }
            final int offsetBefore = Math.max(0, v.computeVerticalScrollOffset());
            final int step = offsetBefore > 0 ? offsetBefore : fallbackChunk;
            final int moved = stepVirtualizedUpOnce(v, step);
            if (moved <= 0) {
                break;
            }
            totalMoved += moved;
        }
        return totalMoved;
    }

    private static int stepVirtualizedUpOnce(@NonNull View v, int requestedStepPx) {
        if (requestedStepPx <= 0 || !v.canScrollVertically(-1)) {
            return 0;
        }
        final int before = Math.max(0, v.computeVerticalScrollOffset());
        v.scrollBy(0, -requestedStepPx);
        final int after = Math.max(0, v.computeVerticalScrollOffset());
        if (before > after) {
            return before - after;
        }
        if (!v.canScrollVertically(-1) && before > 0) {
            return before;
        }
        return !v.canScrollVertically(-1) ? requestedStepPx : 0;
    }

    private void startVirtualizedSmoothBurst(
            @NonNull View target, @NonNull ScrollUndoSnapshot snap, int stepPerFramePx) {
        cancelInFlightAnimations();
        final InFlightBurst burst = new InFlightBurst() {
            private int mFrames = 0;

            @Override
            public void run() {
                if (!mRunning) {
                    return;
                }
                final View v = snap.targetRef.get();
                if (v == null || !v.canReceivePointerEvents() || !v.canScrollVertically(-1)) {
                    mRunning = false;
                    snap.settledAtTop = true;
                    return;
                }
                mFrames++;
                final int step = (mFrames >= OBFUSCATED_SMOOTH_FRAMES)
                        ? Math.max(v.computeVerticalScrollOffset(), Math.max(256, v.getHeight()))
                        : stepPerFramePx;
                final int moved = stepVirtualizedUpOnce(v, step);
                if (moved > 0) {
                    snap.savedScrolledDeltaPx += moved;
                    snap.expiresAtUptimeMillis = mUptimeMillisSupplier.getAsLong() + UNDO_WINDOW_MS;
                }
                if (moved <= 0 || !v.canScrollVertically(-1)
                        || mFrames >= MAX_SYNC_SCROLL_BY_STEPS) {
                    mRunning = false;
                    snap.settledAtTop = true;
                    return;
                }
                mFrameScheduler.accept(this);
            }
        };
        mActiveBurst = burst;
        mFrameScheduler.accept(burst);
    }

    private boolean tryScrollAccessibilityProvider(
            @NonNull View hostView,
            @NonNull AccessibilityNodeProvider provider,
            int screenX,
            boolean animatorsEnabled,
            long now) {
        final VirtualScrollCandidate best = findBestVirtualScrollNode(hostView, provider, screenX);
        if (best == null) {
            return false;
        }

        final int firstBatchLimit = animatorsEnabled
                ? A11Y_ACTIONS_PER_FRAME : MAX_A11Y_BURST_STEPS;
        int stepsExecuted = 0;
        boolean usedPageUp = false;

        for (int i = 0; i < firstBatchLimit; i++) {
            final AccessibilityNodeInfo current =
                    provider.createAccessibilityNodeInfo(best.virtualViewId);
            if (current == null || !canVirtualNodeScrollUp(current, best.hadScrollBackward)) {
                break;
            }
            final int action = pickUpwardVirtualAction(current);
            if (action == 0) {
                break;
            }
            boolean stepOk = provider.performAction(best.virtualViewId, action, null);
            if (!stepOk && action == R.id.accessibilityActionPageUp
                    && hasAction(current, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                stepOk = provider.performAction(
                        best.virtualViewId, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null);
            } else if (stepOk && action == R.id.accessibilityActionPageUp) {
                usedPageUp = true;
            }
            if (!stepOk) {
                break;
            }
            stepsExecuted++;
        }

        if (stepsExecuted <= 0) {
            return false;
        }

        final AccessibilityNodeInfo afterFirstBatch =
                provider.createAccessibilityNodeInfo(best.virtualViewId);
        final boolean reachedTop = afterFirstBatch == null
                || !canVirtualNodeScrollUp(afterFirstBatch, best.hadScrollBackward)
                || stepsExecuted >= MAX_A11Y_BURST_STEPS;

        final ScrollUndoSnapshot snap = ScrollUndoSnapshot.forVirtualNode(
                hostView, best.virtualViewId, stepsExecuted, usedPageUp,
                best.hadScrollBackward, reachedTop, now);
        mUndoSnapshot = snap;

        if (!reachedTop && animatorsEnabled) {
            startVirtualScrollUpBurst(provider, snap);
        }
        return true;
    }

    private void startVirtualScrollUpBurst(
            @NonNull AccessibilityNodeProvider provider, @NonNull ScrollUndoSnapshot snap) {
        cancelInFlightAnimations();
        final InFlightBurst burst = new InFlightBurst() {
            @Override
            public void run() {
                if (!mRunning) {
                    return;
                }
                final View host = snap.targetRef.get();
                if (host == null || !host.canReceivePointerEvents()) {
                    mRunning = false;
                    return;
                }
                boolean keepGoing = true;
                for (int i = 0; i < A11Y_ACTIONS_PER_FRAME
                        && snap.savedSteps < MAX_A11Y_BURST_STEPS; i++) {
                    final AccessibilityNodeInfo current =
                            provider.createAccessibilityNodeInfo(snap.virtualViewId);
                    if (current == null
                            || !canVirtualNodeScrollUp(current, snap.virtualHadScrollBackward)) {
                        keepGoing = false;
                        break;
                    }
                    final int action = pickUpwardVirtualAction(current);
                    if (action == 0) {
                        keepGoing = false;
                        break;
                    }
                    boolean stepOk = provider.performAction(snap.virtualViewId, action, null);
                    if (!stepOk && action == R.id.accessibilityActionPageUp
                            && hasAction(current, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                        stepOk = provider.performAction(
                                snap.virtualViewId,
                                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                                null);
                    } else if (stepOk && action == R.id.accessibilityActionPageUp) {
                        snap.virtualUsedPageUp = true;
                    }
                    if (!stepOk) {
                        keepGoing = false;
                        break;
                    }
                    snap.savedSteps++;
                    snap.expiresAtUptimeMillis = mUptimeMillisSupplier.getAsLong() + UNDO_WINDOW_MS;
                }

                if (!keepGoing || snap.savedSteps >= MAX_A11Y_BURST_STEPS) {
                    mRunning = false;
                    snap.settledAtTop = true;
                    return;
                }
                final AccessibilityNodeInfo check =
                        provider.createAccessibilityNodeInfo(snap.virtualViewId);
                if (check == null
                        || !canVirtualNodeScrollUp(check, snap.virtualHadScrollBackward)) {
                    mRunning = false;
                    snap.settledAtTop = true;
                    return;
                }
                mFrameScheduler.accept(this);
            }
        };
        mActiveBurst = burst;
        mFrameScheduler.accept(burst);
    }

    private boolean canRestoreSnapshot(@NonNull ScrollUndoSnapshot snap, @NonNull View rootView) {
        final View target = snap.targetRef.get();
        if (target == null || !target.canReceivePointerEvents()) {
            return false;
        }
        if (target.isAttachedToWindow() != rootView.isAttachedToWindow()
                || !isDescendantOrSelf(target, rootView)) {
            return false;
        }

        final View appBar = snap.appBarRef != null ? snap.appBarRef.get() : null;
        if (appBar != null && isAppBarCollapsed(appBar)) {
            return false;
        }

        switch (snap.kind) {
            case APP_BAR_ONLY:
                return !isAppBarCollapsed(target);
            case ABS_LIST_VIEW:
                return target instanceof AbsListView && isAbsListViewAtTop((AbsListView) target);
            case A11Y_VIRTUAL_NODE: {
                final AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
                if (provider == null || snap.savedSteps <= 0) {
                    return false;
                }
                final AccessibilityNodeInfo node =
                        provider.createAccessibilityNodeInfo(snap.virtualViewId);
                return node != null && !canVirtualNodeScrollUp(node, snap.virtualHadScrollBackward);
            }
            case SCROLL_Y:
            case WEB_VIEW:
                return target.getScrollY() <= 0 && !target.canScrollVertically(-1);
            case RECYCLER_VIEW:
                return isRecyclerViewAtTop(target);
            case COMPUTE_OFFSET_SCROLL_BY:
                return !target.canScrollVertically(-1) && target.computeVerticalScrollOffset() <= 0;
            default:
                return false;
        }
    }

    private boolean restoreSnapshot(@NonNull ScrollUndoSnapshot snap, boolean animate) {
        final View target = snap.targetRef.get();
        if (target == null) {
            return false;
        }
        final View appBar = snap.appBarRef != null ? snap.appBarRef.get() : null;

        boolean restored = false;
        switch (snap.kind) {
            case SCROLL_Y:
            case WEB_VIEW: {
                if (snap.savedScrollY > 0) {
                    animateOrSnapScrollY(
                            target,
                            getViewReflectionInfo(target.getClass()),
                            snap.savedScrollX,
                            snap.savedScrollY,
                            animate);
                    restored = true;
                }
                break;
            }
            case ABS_LIST_VIEW: {
                if (target instanceof AbsListView) {
                    ((AbsListView) target).setSelectionFromTop(
                            snap.savedPosition, snap.savedOffsetPx);
                    restored = true;
                }
                break;
            }
            case RECYCLER_VIEW: {
                final ViewReflectionInfo info = getViewReflectionInfo(target.getClass());
                restored = restoreRecyclerViewPosition(
                        target, info, snap.savedPosition, snap.savedOffsetPx);
                break;
            }
            case COMPUTE_OFFSET_SCROLL_BY: {
                if (snap.savedScrolledDeltaPx > 0) {
                    target.scrollBy(0, snap.savedScrolledDeltaPx);
                    restored = true;
                }
                break;
            }
            case APP_BAR_ONLY: {
                restored = setAppBarExpanded(target, false, animate);
                break;
            }
            case A11Y_VIRTUAL_NODE: {
                final AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
                if (provider != null && snap.savedSteps > 0) {
                    restored = restoreVirtualNodePosition(target, provider, snap, animate);
                }
                break;
            }
        }

        if (snap.appBarWasCollapsed && appBar != null && snap.kind != TargetKind.APP_BAR_ONLY) {
            if (setAppBarExpanded(appBar, false, animate)) {
                restored = true;
            }
        }
        return restored;
    }

    private boolean restoreVirtualNodePosition(
            @NonNull View hostView,
            @NonNull AccessibilityNodeProvider provider,
            @NonNull ScrollUndoSnapshot snap,
            boolean animate) {
        final int syncSteps = animate
                ? Math.min(snap.savedSteps, A11Y_ACTIONS_PER_FRAME) : snap.savedSteps;
        int executed = 0;
        for (int i = 0; i < syncSteps; i++) {
            if (!performDownwardVirtualStep(provider, snap.virtualViewId, snap.virtualUsedPageUp)) {
                break;
            }
            executed++;
        }
        if (executed <= 0) {
            return false;
        }
        final int remaining = snap.savedSteps - executed;
        if (remaining > 0 && animate) {
            final WeakReference<View> hostRef = new WeakReference<>(hostView);
            final InFlightBurst burst = new InFlightBurst() {
                private int mLeft = remaining;

                @Override
                public void run() {
                    if (!mRunning) {
                        return;
                    }
                    final View h = hostRef.get();
                    if (h == null || !h.canReceivePointerEvents()) {
                        mRunning = false;
                        return;
                    }
                    for (int i = 0; i < A11Y_ACTIONS_PER_FRAME && mLeft > 0; i++) {
                        if (!performDownwardVirtualStep(
                                provider, snap.virtualViewId, snap.virtualUsedPageUp)) {
                            mRunning = false;
                            return;
                        }
                        mLeft--;
                    }
                    if (mLeft <= 0) {
                        mRunning = false;
                        return;
                    }
                    mFrameScheduler.accept(this);
                }
            };
            mActiveBurst = burst;
            mFrameScheduler.accept(burst);
        }
        return true;
    }

    private static boolean performDownwardVirtualStep(
            @NonNull AccessibilityNodeProvider provider, int virtualViewId, boolean preferPageDown) {
        final AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(virtualViewId);
        if (node == null) {
            return false;
        }
        if (preferPageDown && hasAction(node, R.id.accessibilityActionPageDown)) {
            if (provider.performAction(virtualViewId, R.id.accessibilityActionPageDown, null)) {
                return true;
            }
        }
        if (hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            if (provider.performAction(
                    virtualViewId, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)) {
                return true;
            }
        }
        if (hasAction(node, R.id.accessibilityActionPageDown)) {
            return provider.performAction(virtualViewId, R.id.accessibilityActionPageDown, null);
        }
        return false;
    }

    private void settleSnapshotTargetAtTopInstant(@NonNull ScrollUndoSnapshot snap) {
        final View target = snap.targetRef.get();
        if (target == null) {
            return;
        }
        final View appBar = snap.appBarRef != null ? snap.appBarRef.get() : null;
        if (appBar != null && isAppBarCollapsed(appBar)) {
            setAppBarExpanded(appBar, true, false);
        }
        switch (snap.kind) {
            case SCROLL_Y:
            case WEB_VIEW:
                if (target instanceof ScrollView) {
                    ((ScrollView) target).smoothScrollBy(0, 0);
                }
                target.scrollTo(target.getScrollX(), 0);
                break;
            case ABS_LIST_VIEW:
                if (target instanceof AbsListView) {
                    final AbsListView lv = (AbsListView) target;
                    lv.smoothScrollBy(0, 0);
                    lv.setSelectionFromTop(0, 0);
                }
                break;
            case RECYCLER_VIEW: {
                final ViewReflectionInfo info = getViewReflectionInfo(target.getClass());
                try {
                    if (info.stopScroll != null) {
                        info.stopScroll.invoke(target);
                    }
                    if (info.scrollToPosition != null) {
                        info.scrollToPosition.invoke(target, 0);
                    }
                } catch (Throwable ignored) {
                }
                break;
            }
            case COMPUTE_OFFSET_SCROLL_BY: {
                final int moved = scrollVirtualizedUpSynchronously(target, MAX_SYNC_SCROLL_BY_STEPS);
                snap.savedScrolledDeltaPx += moved;
                break;
            }
            case APP_BAR_ONLY:
                setAppBarExpanded(target, true, false);
                break;
            case A11Y_VIRTUAL_NODE: {
                final AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
                if (provider != null) {
                    while (snap.savedSteps < MAX_A11Y_BURST_STEPS) {
                        final AccessibilityNodeInfo node =
                                provider.createAccessibilityNodeInfo(snap.virtualViewId);
                        if (node == null
                                || !canVirtualNodeScrollUp(node, snap.virtualHadScrollBackward)) {
                            break;
                        }
                        final int action = pickUpwardVirtualAction(node);
                        if (action == 0
                                || !provider.performAction(snap.virtualViewId, action, null)) {
                            break;
                        }
                        snap.savedSteps++;
                    }
                }
                break;
            }
        }
    }

    private void animateOrSnapScrollY(
            @NonNull View target,
            @Nullable ViewReflectionInfo info,
            int targetX,
            int targetY,
            boolean animate) {
        if (!animate) {
            if (target instanceof ScrollView) {
                ((ScrollView) target).smoothScrollBy(0, 0);
            } else if (info != null && info.smoothScrollBy != null) {
                try {
                    info.smoothScrollBy.invoke(target, 0, 0);
                } catch (Throwable ignored) {
                }
            }
            target.scrollTo(targetX, targetY);
            return;
        }

        if (target instanceof ScrollView) {
            ((ScrollView) target).smoothScrollTo(targetX, targetY);
            return;
        }
        if (info != null && info.smoothScrollTo != null) {
            try {
                info.smoothScrollTo.invoke(target, targetX, targetY);
                return;
            } catch (Throwable ignored) {
            }
        }

        cancelActiveAnimator();
        if (target.getScrollX() != targetX) {
            target.setScrollX(targetX);
        }
        final ObjectAnimator anim = ObjectAnimator.ofInt(
                target, "scrollY", target.getScrollY(), targetY);
        anim.setDuration(SCROLL_Y_ANIM_DURATION_MS);
        anim.setInterpolator(sDecelerateInterpolator);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mActiveAnimatorRef != null && mActiveAnimatorRef.get() == animation) {
                    mActiveAnimatorRef = null;
                }
            }
        });
        mActiveAnimatorRef = new WeakReference<>(anim);
        anim.start();
    }

    private boolean isAnimatorRunning() {
        final ObjectAnimator anim = mActiveAnimatorRef != null ? mActiveAnimatorRef.get() : null;
        return anim != null && anim.isRunning();
    }

    private void cancelActiveAnimator() {
        final ObjectAnimator anim = mActiveAnimatorRef != null ? mActiveAnimatorRef.get() : null;
        mActiveAnimatorRef = null;
        if (anim != null) {
            anim.cancel();
        }
    }

    private void cancelInFlightAnimations() {
        if (mActiveBurst != null) {
            mActiveBurst.cancel();
            mActiveBurst = null;
        }
        cancelActiveAnimator();
    }

    // =========================================================================================
    // Candidate View Discovery & Visual Sorting (matching ViewGroup.getScrollToTopComparator)
    // =========================================================================================

    private static void collectCandidates(
            @NonNull View current,
            @NonNull View rootView,
            int windowX,
            @NonNull Matrix parentToRootMatrix,
            @NonNull RectF tempRect,
            @NonNull ArrayList<CandidateView> out) {
        if (!current.canReceivePointerEvents()) {
            return;
        }
        final int w = current.getWidth();
        final int h = current.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        final Matrix localToRoot = new Matrix();
        if (current != rootView) {
            if (!current.getMatrix().isIdentity()) {
                localToRoot.set(current.getMatrix());
            }
            localToRoot.postTranslate(current.getLeft(), current.getTop());
            localToRoot.postConcat(parentToRootMatrix);
        }

        tempRect.set(0f, 0f, w, h);
        localToRoot.mapRect(tempRect);

        final boolean intersectsX = windowX >= tempRect.left && windowX < tempRect.right;
        final float visualTopY = tempRect.top;
        final float effectiveZ = computeEffectiveZ(current, rootView);
        out.add(new CandidateView(current, intersectsX, visualTopY, effectiveZ, out.size()));

        if (!(current instanceof ViewGroup)) {
            return;
        }
        final ViewGroup vg = (ViewGroup) current;
        final int childCount = vg.getChildCount();
        if (childCount <= 0) {
            return;
        }

        final Matrix childParentToRoot = new Matrix();
        childParentToRoot.setTranslate(-vg.getScrollX(), -vg.getScrollY());
        childParentToRoot.postConcat(localToRoot);

        // Collect children in baseline drawing/Z order (low to high) so higher Z / later-drawn
        // children receive higher preorderIndex without mutating vg.mPreSortedChildren.
        final boolean customOrder = vg.isChildrenDrawingOrderEnabled();
        final ArrayList<View> orderedChildren = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            final int childIdx = customOrder ? vg.getChildDrawingOrder(childCount, i) : i;
            if (childIdx >= 0 && childIdx < childCount) {
                final View child = vg.getChildAt(childIdx);
                if (child != null) {
                    orderedChildren.add(child);
                }
            }
        }
        orderedChildren.sort((c1, c2) -> Float.compare(c1.getZ(), c2.getZ()));

        for (int i = 0, n = orderedChildren.size(); i < n; i++) {
            collectCandidates(
                    orderedChildren.get(i), rootView, windowX, childParentToRoot, tempRect, out);
        }
    }

    private static float computeEffectiveZ(@NonNull View view, @NonNull View rootView) {
        float z = view.getZ();
        ViewParent p = view.getParent();
        while (p instanceof View && p != rootView) {
            z += ((View) p).getZ();
            p = p.getParent();
        }
        return z;
    }

    @Nullable
    private static CandidateView findTopNativeScrollCandidate(
            @NonNull List<CandidateView> candidates) {
        for (int i = 0, n = candidates.size(); i < n; i++) {
            final CandidateView c = candidates.get(i);
            final View v = c.view;
            if (v instanceof ScrollView) {
                final ScrollView sv = (ScrollView) v;
                if (sv.isScrollToTopEnabled() && sv.getScrollY() > 0) {
                    return c;
                }
            } else if (v instanceof AbsListView) {
                final AbsListView lv = (AbsListView) v;
                if (lv.isScrollToTopEnabled() && !isAbsListViewAtTop(lv)) {
                    return c;
                }
            } else if (getViewReflectionInfo(v.getClass()).overridesCustomOnScrollToTop) {
                return c;
            }
        }
        return null;
    }

    private static boolean hasIntersectingNonNativeCandidate(
            @NonNull List<CandidateView> candidates, @NonNull View rootView, int windowX) {
        for (int i = 0, n = candidates.size(); i < n; i++) {
            final CandidateView c = candidates.get(i);
            if (!c.intersectsWindowX) {
                break;
            }
            final View v = c.view;
            if (v instanceof ScrollView || v instanceof AbsListView) {
                continue;
            }
            if (v instanceof WebView
                    && (v.getScrollY() > 0 || v.canScrollVertically(-1))) {
                return true;
            }
            final ViewReflectionInfo info = getViewReflectionInfo(v.getClass());
            if (info.isRecyclerView && !isRecyclerViewAtTop(v)) {
                return true;
            }
            if (v.getScrollY() > 0 && v.canScrollVertically(-1)) {
                return true;
            }
            if (!info.isComposeView
                    && info.overridesScrollByOrTo
                    && v.getScrollY() == 0
                    && v.canScrollVertically(-1)) {
                return true;
            }
            if (isAppBarLayout(v) && isAppBarCollapsed(v)) {
                return true;
            }
            if (v.getAccessibilityNodeProvider() != null) {
                final int[] loc = new int[2];
                rootView.getLocationOnScreen(loc);
                if (findBestVirtualScrollNode(
                        v, v.getAccessibilityNodeProvider(), loc[0] + windowX) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAbsListViewAtTop(@NonNull AbsListView lv) {
        if (lv.getCount() == 0 || lv.getChildCount() == 0) {
            return true;
        }
        final View firstChild = lv.getChildAt(0);
        return lv.getFirstVisiblePosition() == 0
                && firstChild != null
                && firstChild.getTop() >= lv.getListPaddingTop();
    }

    private static boolean isDescendantOrSelf(@NonNull View view, @NonNull View rootView) {
        View current = view;
        while (current != null) {
            if (current == rootView) {
                return true;
            }
            final ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
        }
        return false;
    }

    // =========================================================================================
    // AndroidX RecyclerView & AppBarLayout Cached Reflection
    // =========================================================================================

    private static int resolveRecyclerFirstVisiblePosition(
            @NonNull View rv,
            @NonNull ViewReflectionInfo info,
            @Nullable Object lm,
            @Nullable LayoutManagerReflectionInfo lmInfo) {
        if (lm != null && lmInfo != null) {
            try {
                if (lmInfo.findFirstVisibleItemPosition != null) {
                    return (Integer) lmInfo.findFirstVisibleItemPosition.invoke(lm);
                }
                if (lmInfo.findFirstVisibleItemPositions != null) {
                    final int[] positions =
                            (int[]) lmInfo.findFirstVisibleItemPositions.invoke(lm, (Object) null);
                    if (positions != null) {
                        int min = Integer.MAX_VALUE;
                        for (int pos : positions) {
                            if (pos >= 0 && pos < min) {
                                min = pos;
                            }
                        }
                        if (min != Integer.MAX_VALUE) {
                            return min;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (info.findFirstVisibleItemPositionDirect != null) {
            try {
                return (Integer) info.findFirstVisibleItemPositionDirect.invoke(rv);
            } catch (Throwable ignored) {
            }
        }
        if (rv instanceof ViewGroup && ((ViewGroup) rv).getChildCount() > 0
                && info.getChildAdapterPosition != null) {
            try {
                return (Integer) info.getChildAdapterPosition.invoke(
                        rv, ((ViewGroup) rv).getChildAt(0));
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static int resolveRecyclerTopOffsetPx(
            @NonNull View rv,
            int firstPos,
            @Nullable Object lm,
            @Nullable LayoutManagerReflectionInfo lmInfo) {
        if (firstPos >= 0 && lm != null && lmInfo != null && lmInfo.findViewByPosition != null) {
            try {
                final Object itemViewObj = lmInfo.findViewByPosition.invoke(lm, firstPos);
                if (itemViewObj instanceof View) {
                    return ((View) itemViewObj).getTop() - rv.getPaddingTop();
                }
            } catch (Throwable ignored) {
            }
        }
        if (rv instanceof ViewGroup && ((ViewGroup) rv).getChildCount() > 0) {
            final View firstChild = ((ViewGroup) rv).getChildAt(0);
            if (firstChild != null) {
                return firstChild.getTop() - rv.getPaddingTop();
            }
        }
        return 0;
    }

    private static boolean isRecyclerSettling(@NonNull ScrollUndoSnapshot snap) {
        if (snap.kind != TargetKind.RECYCLER_VIEW) {
            return false;
        }
        final View rv = snap.targetRef.get();
        if (rv == null) {
            return false;
        }
        final ViewReflectionInfo info = getViewReflectionInfo(rv.getClass());
        if (info.getScrollState != null) {
            try {
                return ((Integer) info.getScrollState.invoke(rv)) != 0;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isRecyclerViewAtTop(@NonNull View rv) {
        if (rv.canScrollVertically(-1)) {
            return false;
        }
        final ViewReflectionInfo info = getViewReflectionInfo(rv.getClass());
        Object lm = null;
        LayoutManagerReflectionInfo lmInfo = null;
        if (info.getLayoutManager != null) {
            try {
                lm = info.getLayoutManager.invoke(rv);
                if (lm != null) {
                    lmInfo = getLayoutManagerReflectionInfo(lm.getClass());
                }
            } catch (Throwable ignored) {
            }
        }
        final int firstPos = resolveRecyclerFirstVisiblePosition(rv, info, lm, lmInfo);
        if (firstPos > 0) {
            return false;
        }
        final int topOffset = resolveRecyclerTopOffsetPx(rv, 0, lm, lmInfo);
        return topOffset >= 0;
    }

    private static boolean restoreRecyclerViewPosition(
            @NonNull View rv, @NonNull ViewReflectionInfo info, int pos, int offsetPx) {
        if (info.getLayoutManager != null) {
            try {
                final Object lm = info.getLayoutManager.invoke(rv);
                if (lm != null) {
                    final LayoutManagerReflectionInfo lmInfo =
                            getLayoutManagerReflectionInfo(lm.getClass());
                    if (lmInfo.scrollToPositionWithOffset != null) {
                        lmInfo.scrollToPositionWithOffset.invoke(lm, pos, offsetPx);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (info.scrollToPositionWithOffsetDirect != null) {
            try {
                info.scrollToPositionWithOffsetDirect.invoke(rv, pos, offsetPx);
                return true;
            } catch (Throwable ignored) {
            }
        }
        if (info.scrollToPosition != null) {
            try {
                info.scrollToPosition.invoke(rv, pos);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Nullable
    private static View findAssociatedAppBarLayout(@NonNull View target, @NonNull View rootView) {
        ViewParent parent = target.getParent();
        while (parent instanceof View) {
            final View pv = (View) parent;
            if (isAppBarLayout(pv)) {
                return pv;
            }
            if (pv instanceof ViewGroup) {
                final ViewGroup vg = (ViewGroup) pv;
                for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                    final View sibling = vg.getChildAt(i);
                    if (sibling != null && sibling.canReceivePointerEvents()
                            && isAppBarLayout(sibling)) {
                        return sibling;
                    }
                }
            }
            if (pv == rootView) {
                break;
            }
            parent = pv.getParent();
        }
        return null;
    }

    private static boolean isAppBarLayout(@NonNull View view) {
        return getAppBarReflectionInfo(view.getClass()).isAppBarLayout;
    }

    private static boolean isAppBarCollapsed(@NonNull View appBar) {
        final AppBarReflectionInfo info = getAppBarReflectionInfo(appBar.getClass());
        if (!info.isAppBarLayout) {
            return false;
        }
        if (info.getTopAndBottomOffset != null) {
            try {
                return ((Integer) info.getTopAndBottomOffset.invoke(appBar)) < 0;
            } catch (Throwable ignored) {
            }
        }
        return appBar.getTop() < 0 || appBar.getTranslationY() < 0f;
    }

    private static boolean setAppBarExpanded(
            @NonNull View appBar, boolean expanded, boolean animate) {
        final AppBarReflectionInfo info = getAppBarReflectionInfo(appBar.getClass());
        if (!info.isAppBarLayout) {
            return false;
        }
        try {
            if (info.setExpandedTwoArg != null) {
                info.setExpandedTwoArg.invoke(appBar, expanded, animate);
                return true;
            }
            if (info.setExpandedOneArg != null) {
                info.setExpandedOneArg.invoke(appBar, expanded);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @NonNull
    private static ViewReflectionInfo getViewReflectionInfo(@NonNull Class<?> cls) {
        ViewReflectionInfo cached = sViewReflectionCache.get(cls);
        if (cached != null) {
            return cached;
        }
        cached = new ViewReflectionInfo(cls);
        sViewReflectionCache.putIfAbsent(cls, cached);
        return cached;
    }

    @NonNull
    private static LayoutManagerReflectionInfo getLayoutManagerReflectionInfo(
            @NonNull Class<?> cls) {
        LayoutManagerReflectionInfo cached = sLayoutManagerCache.get(cls);
        if (cached != null) {
            return cached;
        }
        cached = new LayoutManagerReflectionInfo(cls);
        sLayoutManagerCache.putIfAbsent(cls, cached);
        return cached;
    }

    @NonNull
    private static AppBarReflectionInfo getAppBarReflectionInfo(@NonNull Class<?> cls) {
        AppBarReflectionInfo cached = sAppBarCache.get(cls);
        if (cached != null) {
            return cached;
        }
        cached = new AppBarReflectionInfo(cls);
        sAppBarCache.putIfAbsent(cls, cached);
        return cached;
    }

    // =========================================================================================
    // Virtual AccessibilityNodeProvider Traversal (Jetpack Compose & Chromium)
    // =========================================================================================

    @Nullable
    private static VirtualScrollCandidate findBestVirtualScrollNode(
            @NonNull View hostView, @NonNull AccessibilityNodeProvider provider, int screenX) {
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        final HashSet<Integer> visited = new HashSet<>();
        queue.add(AccessibilityNodeProvider.HOST_VIEW_ID);
        visited.add(AccessibilityNodeProvider.HOST_VIEW_ID);

        final Rect bounds = new Rect();
        VirtualScrollCandidate best = null;
        int inspected = 0;

        while (!queue.isEmpty() && inspected < MAX_A11Y_TRAVERSAL_NODES) {
            final int virtualId = queue.poll();
            final AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(virtualId);
            if (node == null) {
                continue;
            }
            inspected++;

            final boolean visibleOk = !hostView.isAttachedToWindow() || node.isVisibleToUser();
            final boolean hasScrollBack =
                    hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            final boolean hasPageUp = hasAction(node, R.id.accessibilityActionPageUp);
            if (visibleOk && (hasScrollBack || hasPageUp)) {
                node.getBoundsInScreen(bounds);
                final boolean intersects = !bounds.isEmpty()
                        && screenX >= bounds.left
                        && screenX < bounds.right;
                final int top = bounds.isEmpty() ? 0 : bounds.top;
                final VirtualScrollCandidate candidate =
                        new VirtualScrollCandidate(virtualId, intersects, top, hasScrollBack);
                if (best == null || candidate.compareTo(best) < 0) {
                    best = candidate;
                }
            }

            final int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final long childNodeId = node.getChildId(i);
                final int childVirtualId =
                        AccessibilityNodeInfo.getVirtualDescendantId(childNodeId);
                if (visited.add(childVirtualId)) {
                    queue.add(childVirtualId);
                }
            }
        }
        return best;
    }

    private static boolean canVirtualNodeScrollUp(
            @NonNull AccessibilityNodeInfo node, boolean initiallyHadScrollBackward) {
        if (initiallyHadScrollBackward) {
            return hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        return hasAction(node, R.id.accessibilityActionPageUp)
                || hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    private static int pickUpwardVirtualAction(@NonNull AccessibilityNodeInfo node) {
        if (hasAction(node, R.id.accessibilityActionPageUp)) {
            return R.id.accessibilityActionPageUp;
        }
        if (hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        }
        return 0;
    }

    private static boolean hasAction(@NonNull AccessibilityNodeInfo node, int actionId) {
        final List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (int i = 0, n = actions.size(); i < n; i++) {
            if (actions.get(i).getId() == actionId) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================================
    // System Settings & Choreographer Utilities
    // =========================================================================================

    private static boolean isRestoreEnabledFromSettings(@Nullable Context context) {
        if (context == null) {
            return true;
        }
        try {
            final ContentResolver cr = context.getContentResolver();
            if (cr == null) {
                return true;
            }
            return Settings.System.getInt(
                    cr, Settings.System.STATUS_BAR_SCROLL_TO_TOP_RESTORE, 1) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void scheduleOnNextChoreographerFrame(@NonNull Runnable action) {
        Choreographer.getInstance().postFrameCallback(frameTimeNanos -> action.run());
    }

    @Nullable
    private static Method findPublicMethod(
            @NonNull Class<?> cls, @NonNull String name, Class<?>... paramTypes) {
        try {
            final Method m = cls.getMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================================
    // Internal Value Types
    // =========================================================================================

    private enum TargetKind {
        SCROLL_Y,
        ABS_LIST_VIEW,
        WEB_VIEW,
        RECYCLER_VIEW,
        COMPUTE_OFFSET_SCROLL_BY,
        APP_BAR_ONLY,
        A11Y_VIRTUAL_NODE
    }

    private static final class ScrollUndoSnapshot {
        final WeakReference<View> targetRef;
        @Nullable
        final WeakReference<View> appBarRef;
        final TargetKind kind;
        final int savedScrollX;
        final int savedScrollY;
        final int savedPosition;
        final int savedOffsetPx;
        int savedScrolledDeltaPx;
        final int virtualViewId;
        int savedSteps;
        boolean virtualUsedPageUp;
        final boolean virtualHadScrollBackward;
        final boolean appBarWasCollapsed;
        final long triggeredAtUptimeMillis;
        long expiresAtUptimeMillis;
        boolean settledAtTop;

        private ScrollUndoSnapshot(
                @NonNull View target,
                @Nullable View appBar,
                @NonNull TargetKind kind,
                int savedScrollX,
                int savedScrollY,
                int savedPosition,
                int savedOffsetPx,
                int savedScrolledDeltaPx,
                int virtualViewId,
                int savedSteps,
                boolean virtualUsedPageUp,
                boolean virtualHadScrollBackward,
                boolean appBarWasCollapsed,
                boolean settledAtTop,
                long now) {
            this.targetRef = new WeakReference<>(target);
            this.appBarRef = appBar != null ? new WeakReference<>(appBar) : null;
            this.kind = kind;
            this.savedScrollX = savedScrollX;
            this.savedScrollY = savedScrollY;
            this.savedPosition = savedPosition;
            this.savedOffsetPx = savedOffsetPx;
            this.savedScrolledDeltaPx = savedScrolledDeltaPx;
            this.virtualViewId = virtualViewId;
            this.savedSteps = savedSteps;
            this.virtualUsedPageUp = virtualUsedPageUp;
            this.virtualHadScrollBackward = virtualHadScrollBackward;
            this.appBarWasCollapsed = appBarWasCollapsed;
            this.settledAtTop = settledAtTop;
            this.triggeredAtUptimeMillis = now;
            this.expiresAtUptimeMillis = now + UNDO_WINDOW_MS;
        }

        static ScrollUndoSnapshot forScrollY(
                View target, int scrollX, int scrollY, @Nullable View appBar,
                boolean appBarCollapsed, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    target, appBar, TargetKind.SCROLL_Y, scrollX, scrollY, 0, 0, 0,
                    AccessibilityNodeProvider.HOST_VIEW_ID, 0, false, false, appBarCollapsed,
                    settledAtTop, now);
        }

        static ScrollUndoSnapshot forWebView(
                View target, int scrollX, int scrollY, @Nullable View appBar,
                boolean appBarCollapsed, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    target, appBar, TargetKind.WEB_VIEW, scrollX, scrollY, 0, 0, 0,
                    AccessibilityNodeProvider.HOST_VIEW_ID, 0, false, false, appBarCollapsed,
                    settledAtTop, now);
        }

        static ScrollUndoSnapshot forAbsListView(
                View target, int pos, int offsetPx, @Nullable View appBar,
                boolean appBarCollapsed, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    target, appBar, TargetKind.ABS_LIST_VIEW, 0, 0, pos, offsetPx, 0,
                    AccessibilityNodeProvider.HOST_VIEW_ID, 0, false, false, appBarCollapsed,
                    settledAtTop, now);
        }

        static ScrollUndoSnapshot forRecyclerView(
                View target, int pos, int offsetPx, @Nullable View appBar,
                boolean appBarCollapsed, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    target, appBar, TargetKind.RECYCLER_VIEW, 0, 0, pos, offsetPx, 0,
                    AccessibilityNodeProvider.HOST_VIEW_ID, 0, false, false, appBarCollapsed,
                    settledAtTop, now);
        }

        static ScrollUndoSnapshot forVirtualizedOffset(
                View target, int scrolledDeltaPx, @Nullable View appBar,
                boolean appBarCollapsed, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    target, appBar, TargetKind.COMPUTE_OFFSET_SCROLL_BY, 0, 0, 0, 0,
                    scrolledDeltaPx, AccessibilityNodeProvider.HOST_VIEW_ID, 0, false, false,
                    appBarCollapsed, settledAtTop, now);
        }

        static ScrollUndoSnapshot forAppBarOnly(View appBar, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    appBar, null, TargetKind.APP_BAR_ONLY, 0, 0, 0, 0, 0,
                    AccessibilityNodeProvider.HOST_VIEW_ID, 0, false, false, true,
                    settledAtTop, now);
        }

        static ScrollUndoSnapshot forVirtualNode(
                View hostView, int virtualId, int steps, boolean usedPageUp,
                boolean hadScrollBackward, boolean settledAtTop, long now) {
            return new ScrollUndoSnapshot(
                    hostView, null, TargetKind.A11Y_VIRTUAL_NODE, 0, 0, 0, 0, 0,
                    virtualId, steps, usedPageUp, hadScrollBackward, false, settledAtTop, now);
        }
    }

    private abstract static class InFlightBurst implements Runnable {
        boolean mRunning = true;

        final boolean isRunning() {
            return mRunning;
        }

        final void cancel() {
            mRunning = false;
        }
    }

    private static final class CandidateView implements Comparable<CandidateView> {
        final View view;
        final boolean intersectsWindowX;
        final float visualTopY;
        final float effectiveZ;
        final int preorderIndex;

        CandidateView(
                View view, boolean intersectsWindowX, float visualTopY, float effectiveZ,
                int preorderIndex) {
            this.view = view;
            this.intersectsWindowX = intersectsWindowX;
            this.visualTopY = visualTopY;
            this.effectiveZ = effectiveZ;
            this.preorderIndex = preorderIndex;
        }

        @Override
        public int compareTo(@NonNull CandidateView other) {
            if (this.intersectsWindowX != other.intersectsWindowX) {
                return this.intersectsWindowX ? -1 : 1;
            }
            final int yCmp = Float.compare(this.visualTopY, other.visualTopY);
            if (yCmp != 0) {
                return yCmp;
            }
            final int zCmp = Float.compare(other.effectiveZ, this.effectiveZ);
            if (zCmp != 0) {
                return zCmp;
            }
            return Integer.compare(other.preorderIndex, this.preorderIndex);
        }
    }

    private static final class VirtualScrollCandidate
            implements Comparable<VirtualScrollCandidate> {
        final int virtualViewId;
        final boolean intersectsX;
        final int topY;
        final boolean hadScrollBackward;

        VirtualScrollCandidate(
                int virtualViewId, boolean intersectsX, int topY, boolean hadScrollBackward) {
            this.virtualViewId = virtualViewId;
            this.intersectsX = intersectsX;
            this.topY = topY;
            this.hadScrollBackward = hadScrollBackward;
        }

        @Override
        public int compareTo(@NonNull VirtualScrollCandidate other) {
            if (this.intersectsX != other.intersectsX) {
                return this.intersectsX ? -1 : 1;
            }
            return Integer.compare(this.topY, other.topY);
        }
    }

    private static final class ViewReflectionInfo {
        final boolean isRecyclerView;
        final boolean isComposeView;
        final boolean overridesScrollByOrTo;
        final boolean overridesCustomOnScrollToTop;
        @Nullable final Method scrollToPosition;
        @Nullable final Method smoothScrollToPosition;
        @Nullable final Method stopScroll;
        @Nullable final Method getScrollState;
        @Nullable final Method getLayoutManager;
        @Nullable final Method getChildAdapterPosition;
        @Nullable final Method findFirstVisibleItemPositionDirect;
        @Nullable final Method scrollToPositionWithOffsetDirect;
        @Nullable final Method smoothScrollTo;
        @Nullable final Method smoothScrollBy;

        ViewReflectionInfo(@NonNull Class<?> cls) {
            boolean compose = false;
            for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
                final String name = c.getName();
                if (name.contains("AndroidComposeView") || name.contains("ComposeView")) {
                    compose = true;
                    break;
                }
            }
            this.isComposeView = compose;

            this.scrollToPosition = findPublicMethod(cls, "scrollToPosition", int.class);
            this.smoothScrollToPosition =
                    findPublicMethod(cls, "smoothScrollToPosition", int.class);
            this.isRecyclerView = !AbsListView.class.isAssignableFrom(cls)
                    && this.scrollToPosition != null
                    && this.smoothScrollToPosition != null;

            this.stopScroll = this.isRecyclerView ? findPublicMethod(cls, "stopScroll") : null;
            this.getScrollState = this.isRecyclerView
                    ? findPublicMethod(cls, "getScrollState") : null;
            this.getLayoutManager = this.isRecyclerView
                    ? findPublicMethod(cls, "getLayoutManager") : null;
            this.getChildAdapterPosition = this.isRecyclerView
                    ? findPublicMethod(cls, "getChildAdapterPosition", View.class) : null;
            this.findFirstVisibleItemPositionDirect = this.isRecyclerView
                    ? findPublicMethod(cls, "findFirstVisibleItemPosition") : null;
            this.scrollToPositionWithOffsetDirect = this.isRecyclerView
                    ? findPublicMethod(cls, "scrollToPositionWithOffset", int.class, int.class)
                    : null;

            this.smoothScrollTo = findPublicMethod(cls, "smoothScrollTo", int.class, int.class);
            this.smoothScrollBy = findPublicMethod(cls, "smoothScrollBy", int.class, int.class);

            final Method scrollByMethod = findPublicMethod(cls, "scrollBy", int.class, int.class);
            final Method scrollToMethod = findPublicMethod(cls, "scrollTo", int.class, int.class);
            this.overridesScrollByOrTo =
                    (scrollByMethod != null && scrollByMethod.getDeclaringClass() != View.class)
                            || (scrollToMethod != null
                                    && scrollToMethod.getDeclaringClass() != View.class);

            final Method onScrollToTopMethod = findPublicMethod(cls, "onScrollToTop", int.class);
            final Class<?> onScrollDecl = onScrollToTopMethod != null
                    ? onScrollToTopMethod.getDeclaringClass() : View.class;
            this.overridesCustomOnScrollToTop = onScrollDecl != View.class
                    && onScrollDecl != ScrollView.class
                    && onScrollDecl != AbsListView.class
                    && !WebView.class.isAssignableFrom(cls)
                    && !onScrollDecl.getName().contains("Mockito");
        }
    }

    private static final class LayoutManagerReflectionInfo {
        @Nullable final Method findFirstVisibleItemPosition;
        @Nullable final Method findFirstVisibleItemPositions;
        @Nullable final Method findViewByPosition;
        @Nullable final Method scrollToPositionWithOffset;

        LayoutManagerReflectionInfo(@NonNull Class<?> cls) {
            this.findFirstVisibleItemPosition =
                    findPublicMethod(cls, "findFirstVisibleItemPosition");
            this.findFirstVisibleItemPositions =
                    findPublicMethod(cls, "findFirstVisibleItemPositions", int[].class);
            this.findViewByPosition = findPublicMethod(cls, "findViewByPosition", int.class);
            this.scrollToPositionWithOffset =
                    findPublicMethod(cls, "scrollToPositionWithOffset", int.class, int.class);
        }
    }

    private static final class AppBarReflectionInfo {
        final boolean isAppBarLayout;
        @Nullable final Method setExpandedTwoArg;
        @Nullable final Method setExpandedOneArg;
        @Nullable final Method getTopAndBottomOffset;

        AppBarReflectionInfo(@NonNull Class<?> cls) {
            boolean namedAppBar = false;
            for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
                if (c.getSimpleName().endsWith("AppBarLayout")) {
                    namedAppBar = true;
                    break;
                }
            }
            this.setExpandedTwoArg =
                    findPublicMethod(cls, "setExpanded", boolean.class, boolean.class);
            this.setExpandedOneArg = findPublicMethod(cls, "setExpanded", boolean.class);
            this.getTopAndBottomOffset = findPublicMethod(cls, "getTopAndBottomOffset");
            this.isAppBarLayout = namedAppBar
                    || (this.setExpandedTwoArg != null && this.getTopAndBottomOffset != null);
        }
    }
}
