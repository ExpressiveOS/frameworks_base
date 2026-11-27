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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.flags.Flags;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ScrollView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit tests for {@link ExpressiveScrollToTopHelper}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@EnableFlags(Flags.FLAG_SCROLL_TO_TOP)
public class ExpressiveScrollToTopHelperTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;
    private AtomicLong mNowUptimeMs;
    private AtomicBoolean mAnimatorsEnabled;
    private AtomicBoolean mRestoreEnabled;
    private ArrayDeque<Runnable> mScheduledFrames;
    private ExpressiveScrollToTopHelper mHelper;

    @Before
    public void setUp() {
        mContext = getInstrumentation().getTargetContext();
        mNowUptimeMs = new AtomicLong(10_000L);
        mAnimatorsEnabled = new AtomicBoolean(true);
        mRestoreEnabled = new AtomicBoolean(true);
        mScheduledFrames = new ArrayDeque<>();
        mHelper = new ExpressiveScrollToTopHelper(
                mNowUptimeMs::get,
                mAnimatorsEnabled::get,
                ctx -> mRestoreEnabled.get(),
                mScheduledFrames::add);
    }

    @Test(timeout = 10_000)
    public void testScrollView_scrollsToTopAndGatesClockTickHaptic() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final ScrollView scrollView = new ScrollView(mContext);
            final View tallChild = new View(mContext);
            tallChild.setMinimumWidth(400);
            tallChild.setMinimumHeight(3000);
            scrollView.addView(tallChild, new FrameLayout.LayoutParams(400, 3000));
            root.addView(scrollView, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            scrollView.scrollTo(0, 600);
            assertEquals(600, scrollView.getScrollY());

            // Already scrolled down -> consumes and plays CLOCK_TICK.
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, root.mClockTickCount);

            // Snap to top as smooth scroll finishes; disable restore so tap at top is a no-op.
            scrollView.smoothScrollBy(0, 0);
            scrollView.scrollTo(0, 0);
            mRestoreEnabled.set(false);
            mNowUptimeMs.addAndGet(600L);

            // Already at top -> returns false and plays zero haptics.
            assertFalse(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testAbsListView_scrollsToTopAndRestoresWithinFiveSeconds() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final List<String> items = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                items.add("Row " + i);
            }
            final ListView listView = new ListView(mContext);
            listView.setAdapter(new ArrayAdapter<>(
                    mContext, android.R.layout.simple_list_item_1, items));
            root.addView(listView, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            listView.setSelectionFromTop(18, -12);
            measureAndLayout(root, 400, 800);
            assertEquals(18, listView.getFirstVisiblePosition());
            final int savedTopOffset = listView.getChildAt(0).getTop()
                    - listView.getListPaddingTop();

            // 1st tap -> scrolls to top + haptic.
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, root.mClockTickCount);

            // Finish scroll at top and advance clock within 5s undo window.
            listView.smoothScrollBy(0, 0);
            listView.setSelectionFromTop(0, 0);
            measureAndLayout(root, 400, 800);
            assertEquals(0, listView.getFirstVisiblePosition());

            mNowUptimeMs.addAndGet(800L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            measureAndLayout(root, 400, 800);
            assertEquals(2, root.mClockTickCount);
            assertEquals(18, listView.getFirstVisiblePosition());
            assertEquals(savedTopOffset,
                    listView.getChildAt(0).getTop() - listView.getListPaddingTop());
        });
    }

    @Test(timeout = 10_000)
    public void testRecyclerView_shortVsLongTeleportAndSiblingAppBarExpansion() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeAppBarLayout appBar = spy(new FakeAppBarLayout(mContext));
            appBar.setCollapsedOffset(-140);
            final FakeRecyclerView rv = spy(new FakeRecyclerView(mContext));
            root.addView(appBar, new FrameLayout.LayoutParams(400, 140));
            root.addView(rv, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            // Case 1: <= 14 items -> smoothScrollToPosition(0) directly without teleport.
            rv.setPositionAndOffset(8, -24);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            verify(rv, never()).scrollToPosition(ExpressiveScrollToTopHelper.RECYCLER_TELEPORT_TARGET);
            verify(rv).smoothScrollToPosition(0);
            verify(appBar).setExpanded(true, true);
            assertEquals(1, root.mClockTickCount);

            // Reset via user touch and test Case 2: > 14 items -> teleport to 12 then smooth 0.
            mHelper.onUserTouchDown();
            appBar.setCollapsedOffset(-140);
            rv.setPositionAndOffset(45, -30);

            assertTrue(mHelper.handleScrollToTop(root, 200));
            final InOrder inOrder = inOrder(rv);
            inOrder.verify(rv).scrollToPosition(
                    ExpressiveScrollToTopHelper.RECYCLER_TELEPORT_TARGET);
            inOrder.verify(rv).smoothScrollToPosition(0);
            assertEquals(2, root.mClockTickCount);

            // Finish smooth settle at item 0, then tap again within 5s -> restores (45, -30).
            rv.finishSmoothScroll();
            mNowUptimeMs.addAndGet(900L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(3, root.mClockTickCount);
            assertEquals(45, rv.getLayoutManager().findFirstVisibleItemPosition());
            assertEquals(-30, rv.getCurrentTopOffset());
            assertEquals(-140, appBar.getTopAndBottomOffset());
        });
    }

    @Test(timeout = 10_000)
    public void testObfuscatedVirtualizedView_usesComputeVerticalScrollOffsetAndScrollBy() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 500);
            final ObfuscatedListView list = new ObfuscatedListView(mContext, 5000);
            root.addView(list, new FrameLayout.LayoutParams(400, 500));
            measureAndLayout(root, 400, 500);

            list.scrollBy(0, 2400);
            assertEquals(2400, list.computeVerticalScrollOffset());
            assertTrue(list.canScrollVertically(-1));

            assertTrue(mHelper.handleScrollToTop(root, 200));
            drainScheduledFrames();
            assertEquals(0, list.computeVerticalScrollOffset());
            assertFalse(list.canScrollVertically(-1));
            assertEquals(1, root.mClockTickCount);

            // 5-second jump-back restores exact 2400px offset via scrollBy(0, +2400).
            mNowUptimeMs.addAndGet(700L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(2400, list.computeVerticalScrollOffset());
            assertEquals(2, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testCollapsedAppBarLayoutOnly_expandsWhenListAlreadyAtTop() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeAppBarLayout appBar = spy(new FakeAppBarLayout(mContext));
            appBar.setCollapsedOffset(-120);
            final FakeRecyclerView rv = new FakeRecyclerView(mContext);
            rv.setPositionAndOffset(0, 0);
            root.addView(appBar, new FrameLayout.LayoutParams(400, 120));
            root.addView(rv, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            assertFalse(rv.canScrollVertically(-1));
            assertTrue(mHelper.handleScrollToTop(root, 200));
            verify(appBar).setExpanded(true, true);
            assertEquals(0, appBar.getTopAndBottomOffset());
            assertEquals(1, root.mClockTickCount);

            // Second tap within 5s re-collapses AppBarLayout.
            mNowUptimeMs.addAndGet(600L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            verify(appBar).setExpanded(false, true);
            assertEquals(-120, appBar.getTopAndBottomOffset());
            assertEquals(2, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testWebView_invokesPageUpTrueAndSupportsJumpBack() {
        getInstrumentation().runOnMainSync(() -> {
            final WebView mockWebView = createMockWebView(400, 800, 520);
            final SingleMockChildRoot root = new SingleMockChildRoot(mContext, mockWebView, 400, 800);

            assertTrue(mHelper.handleScrollToTop(root, 200));
            verify(mockWebView).pageUp(true);
            assertEquals(0, mockWebView.getScrollY());
            assertEquals(1, root.mClockTickCount);

            // Test jump-back under reduced motion (instant scrollTo(0, 520)).
            mAnimatorsEnabled.set(false);
            mNowUptimeMs.addAndGet(800L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(520, mockWebView.getScrollY());
            assertEquals(2, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testVirtualAccessibilityNodeProvider_composeSimulation_burstAndUndo() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeComposeHostView composeHost = new FakeComposeHostView(mContext, 10);
            root.addView(composeHost, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            assertEquals(10, composeHost.getCurrentPage());
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, root.mClockTickCount);
            // Frame 0 executes A11Y_ACTIONS_PER_FRAME (4) actions -> page 6.
            assertEquals(6, composeHost.getCurrentPage());

            drainScheduledFrames();
            assertEquals(0, composeHost.getCurrentPage());
            assertEquals(10, composeHost.getPageUpCount());

            // 5-second jump-back restores all 10 pages via ACTION_PAGE_DOWN.
            mNowUptimeMs.addAndGet(900L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            drainScheduledFrames();
            assertEquals(10, composeHost.getCurrentPage());
            assertEquals(10, composeHost.getPageDownCount());
            assertEquals(2, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testReducedMotion_usesInstantJumpsEverywhere() {
        getInstrumentation().runOnMainSync(() -> {
            mAnimatorsEnabled.set(false);

            // 1. ScrollView snaps to 0 immediately on frame 0.
            final HapticTrackingRoot svRoot = new HapticTrackingRoot(mContext, 400, 800);
            final ScrollView sv = new ScrollView(mContext);
            final View child = new View(mContext);
            child.setMinimumWidth(400);
            child.setMinimumHeight(2400);
            sv.addView(child, new FrameLayout.LayoutParams(400, 2400));
            svRoot.addView(sv, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(svRoot, 400, 800);
            sv.scrollTo(0, 700);
            assertEquals(700, sv.getScrollY());
            assertTrue(mHelper.handleScrollToTop(svRoot, 200));
            assertEquals(0, sv.getScrollY());

            // 2. RecyclerView calls scrollToPosition(0) directly without smoothScrollToPosition.
            mHelper.reset();
            final HapticTrackingRoot rvRoot = new HapticTrackingRoot(mContext, 400, 800);
            final FakeAppBarLayout appBar = spy(new FakeAppBarLayout(mContext));
            appBar.setCollapsedOffset(-100);
            final FakeRecyclerView rv = spy(new FakeRecyclerView(mContext));
            rv.setPositionAndOffset(50, -10);
            rvRoot.addView(appBar, new FrameLayout.LayoutParams(400, 100));
            rvRoot.addView(rv, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(rvRoot, 400, 800);
            assertTrue(mHelper.handleScrollToTop(rvRoot, 200));
            verify(rv).scrollToPosition(0);
            verify(rv, never()).smoothScrollToPosition(anyInt());
            verify(appBar).setExpanded(true, false);

            // 3. Virtual AccessibilityNodeProvider chunks across frames under reduced motion
            // rather than firing a single-frame IPC spike, then drains cleanly to page 0.
            mHelper.reset();
            final HapticTrackingRoot composeRoot = new HapticTrackingRoot(mContext, 400, 800);
            final FakeComposeHostView composeHost = new FakeComposeHostView(mContext, 9);
            composeRoot.addView(composeHost, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(composeRoot, 400, 800);
            assertTrue(mHelper.handleScrollToTop(composeRoot, 200));
            assertEquals(5, composeHost.getCurrentPage());
            assertFalse(mScheduledFrames.isEmpty());
            drainScheduledFrames();
            assertEquals(0, composeHost.getCurrentPage());
        });
    }

    @Test(timeout = 10_000)
    public void testJumpBackUndo_midFlightSettle_touchInvalidation_andFiveSecondExpiry() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeRecyclerView rv = new FakeRecyclerView(mContext);
            root.addView(rv, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            // 1. Mid-flight tap (< 350ms while settling) settles at item 0 without bouncing back.
            rv.setPositionAndOffset(30, -16);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(12, rv.getLayoutManager().findFirstVisibleItemPosition());
            mNowUptimeMs.addAndGet(150L);
            assertFalse(mHelper.handleScrollToTop(root, 200));
            assertEquals(0, rv.getLayoutManager().findFirstVisibleItemPosition());

            // Subsequent tap after settling within 5s still restores to (30, -16)!
            mNowUptimeMs.addAndGet(500L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(30, rv.getLayoutManager().findFirstVisibleItemPosition());
            assertEquals(-16, rv.getCurrentTopOffset());

            // 2. User screen touch (onUserTouchDown) invalidates snapshot immediately.
            assertTrue(mHelper.handleScrollToTop(root, 200));
            rv.finishSmoothScroll();
            mNowUptimeMs.addAndGet(600L);
            mHelper.onUserTouchDown();
            assertFalse(mHelper.handleScrollToTop(root, 200));
            assertEquals(0, rv.getLayoutManager().findFirstVisibleItemPosition());

            // 3. Snapshot expires strictly after 5,000ms.
            rv.setPositionAndOffset(22, -8);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            rv.finishSmoothScroll();
            mNowUptimeMs.addAndGet(ExpressiveScrollToTopHelper.UNDO_WINDOW_MS + 1L);
            assertFalse(mHelper.handleScrollToTop(root, 200));
            assertEquals(0, rv.getLayoutManager().findFirstVisibleItemPosition());
        });
    }


    @Test(timeout = 10_000)
    public void testAndroidComposeView_dispatchesOneActionPerFrameAndSupportsDeepList() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeAndroidComposeView composeView = new FakeAndroidComposeView(mContext, 40);
            root.addView(composeView, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            assertEquals(40, composeView.getCurrentPage());
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, root.mClockTickCount);
            // AndroidComposeView dispatches strictly 1 action per Choreographer frame.
            assertEquals(39, composeView.getCurrentPage());
            assertEquals(1, composeView.getPageUpCount());

            drainScheduledFrames();
            assertEquals(0, composeView.getCurrentPage());
            assertEquals(40, composeView.getPageUpCount());

            // 5-second jump-back restores all 40 pages at 1 action per frame.
            mNowUptimeMs.addAndGet(600L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, composeView.getCurrentPage());
            drainScheduledFrames();
            assertEquals(40, composeView.getCurrentPage());
            assertEquals(2, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testVirtualAccessibilityNodeProvider_burstDurationCapStopsCleanly() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeAndroidComposeView composeView = new FakeAndroidComposeView(mContext, 200);
            root.addView(composeView, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(199, composeView.getCurrentPage());
            assertFalse(mScheduledFrames.isEmpty());

            // Advance past MAX_A11Y_BURST_DURATION_MS; next scheduled frame terminates cleanly.
            mNowUptimeMs.addAndGet(ExpressiveScrollToTopHelper.MAX_A11Y_BURST_DURATION_MS + 16L);
            final Runnable nextFrame = mScheduledFrames.poll();
            nextFrame.run();
            assertTrue(mScheduledFrames.isEmpty());
            assertEquals(199, composeView.getCurrentPage());
        });
    }

    @Test(timeout = 10_000)
    public void testVirtualAccessibilityNodeProvider_scrollToPositionFastPathAndUndo() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final FakeIndexedVirtualHostView indexedHost =
                    new FakeIndexedVirtualHostView(mContext, 85, 2);
            root.addView(indexedHost, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(0, indexedHost.getCurrentRow());
            assertEquals(0, indexedHost.getCurrentCol());
            assertTrue(mScheduledFrames.isEmpty());
            assertEquals(1, indexedHost.getScrollToPositionCount());

            // 5-second jump-back restores saved (row=85, col=2) via ACTION_SCROLL_TO_POSITION.
            mNowUptimeMs.addAndGet(700L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(85, indexedHost.getCurrentRow());
            assertEquals(2, indexedHost.getCurrentCol());
            assertEquals(2, indexedHost.getScrollToPositionCount());
            assertEquals(2, root.mClockTickCount);
        });
    }

    @Test(timeout = 10_000)
    public void testScrollView_tracksAnimatorDuringMidFlightTapAndPreservesUndo() {
        getInstrumentation().runOnMainSync(() -> {
            final HapticTrackingRoot root = new HapticTrackingRoot(mContext, 400, 800);
            final ScrollView scrollView = new ScrollView(mContext);
            final View tallChild = new View(mContext);
            tallChild.setMinimumWidth(400);
            tallChild.setMinimumHeight(3000);
            scrollView.addView(tallChild, new FrameLayout.LayoutParams(400, 3000));
            root.addView(scrollView, new FrameLayout.LayoutParams(400, 800));
            measureAndLayout(root, 400, 800);

            scrollView.scrollTo(0, 900);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(1, root.mClockTickCount);

            // Second tap while animator is running settles instantly at 0 and keeps undo intact.
            mNowUptimeMs.addAndGet(100L);
            assertFalse(mHelper.handleScrollToTop(root, 200));
            assertEquals(0, scrollView.getScrollY());
            assertEquals(1, root.mClockTickCount);

            // Third tap within 5s restores saved scrollY=900 cleanly under reduced motion.
            mAnimatorsEnabled.set(false);
            mNowUptimeMs.addAndGet(600L);
            assertTrue(mHelper.handleScrollToTop(root, 200));
            assertEquals(900, scrollView.getScrollY());
            assertEquals(2, root.mClockTickCount);
        });
    }

    // =========================================================================================
    // Test Helpers & Doubles
    // =========================================================================================

    private void drainScheduledFrames() {
        int guard = 0;
        while (!mScheduledFrames.isEmpty() && guard++ < 128) {
            mNowUptimeMs.addAndGet(16L);
            final Runnable frame = mScheduledFrames.poll();
            if (frame != null) {
                frame.run();
            }
        }
    }

    private static void measureAndLayout(View root, int width, int height) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static WebView createMockWebView(int width, int height, int initialScrollY) {
        final WebView wv = mock(WebView.class);
        final int[] scroll = new int[] {0, initialScrollY};
        wv.mLeft = 0;
        wv.mTop = 0;
        wv.mRight = width;
        wv.mBottom = height;
        wv.mScrollX = 0;
        wv.mScrollY = initialScrollY;
        doReturn(true).when(wv).canReceivePointerEvents();
        doReturn(Matrix.IDENTITY_MATRIX).when(wv).getMatrix();
        doReturn(0f).when(wv).getZ();
        doReturn(false).when(wv).isAttachedToWindow();
        doReturn(false).when(wv).dispatchScrollToTop(anyInt());
        doReturn(null).when(wv).getAccessibilityNodeProvider();
        doAnswer(inv -> ((int) inv.getArgument(0)) < 0 && scroll[1] > 0)
                .when(wv).canScrollVertically(anyInt());
        doAnswer(inv -> {
            if (scroll[1] > 0) {
                scroll[1] = 0;
                wv.mScrollY = 0;
                return true;
            }
            return false;
        }).when(wv).pageUp(anyBoolean());
        doAnswer(inv -> {
            scroll[0] = inv.getArgument(0);
            scroll[1] = inv.getArgument(1);
            wv.mScrollX = scroll[0];
            wv.mScrollY = scroll[1];
            return null;
        }).when(wv).scrollTo(anyInt(), anyInt());

        // Under Robolectric / inline mock makers, View's geometry & scroll methods are intercepted
        // by Mockito (returning 0) rather than reading View fields directly.
        if (wv.getWidth() != width) {
            doReturn(0).when(wv).getLeft();
            doReturn(0).when(wv).getTop();
            doReturn(width).when(wv).getRight();
            doReturn(height).when(wv).getBottom();
            doReturn(width).when(wv).getWidth();
            doReturn(height).when(wv).getHeight();
            doAnswer(inv -> scroll[0]).when(wv).getScrollX();
            doAnswer(inv -> scroll[1]).when(wv).getScrollY();
            doAnswer(inv -> {
                scroll[0] = inv.getArgument(0);
                wv.mScrollX = scroll[0];
                return null;
            }).when(wv).setScrollX(anyInt());
            doAnswer(inv -> {
                scroll[1] = inv.getArgument(0);
                wv.mScrollY = scroll[1];
                return null;
            }).when(wv).setScrollY(anyInt());
        }
        return wv;
    }

    private static class HapticTrackingRoot extends FrameLayout {
        int mClockTickCount = 0;

        HapticTrackingRoot(Context context, int width, int height) {
            super(context);
            setFrame(0, 0, width, height);
        }

        @Override
        public boolean performHapticFeedback(int feedbackConstant) {
            if (feedbackConstant == HapticFeedbackConstants.CLOCK_TICK) {
                mClockTickCount++;
            }
            return true;
        }
    }

    private static final class SingleMockChildRoot extends HapticTrackingRoot {
        private final View mMockChild;

        SingleMockChildRoot(Context context, View mockChild, int width, int height) {
            super(context, width, height);
            mMockChild = mockChild;
            mMockChild.mParent = this;
            doAnswer(inv -> this.isAttachedToWindow()).when(mMockChild).isAttachedToWindow();
            if (mMockChild.getParent() != this) {
                doReturn(this).when(mMockChild).getParent();
            }
        }

        @Override
        public int getChildCount() {
            return 1;
        }

        @Override
        public View getChildAt(int index) {
            return index == 0 ? mMockChild : null;
        }

        @Override
        public boolean dispatchScrollToTop(int x) {
            return false;
        }
    }

    /**
     * Simulates AndroidX {@code RecyclerView} + {@code LinearLayoutManager} reflection contract.
     * Uses a shared {@link RecyclerState} holder so {@code Mockito.spy(FakeRecyclerView)} shares
     * identical state with {@link FakeLayoutManager}.
     */
    public static class FakeRecyclerView extends FrameLayout {
        private final RecyclerState mState;
        private final FakeLayoutManager mLayoutManager;

        public FakeRecyclerView(Context context) {
            super(context);
            final View topChildView = new View(context);
            topChildView.setMinimumWidth(400);
            topChildView.setMinimumHeight(80);
            addView(topChildView, new LayoutParams(400, 80));
            mState = new RecyclerState(topChildView);
            mLayoutManager = new FakeLayoutManager(mState);
        }

        public void setPositionAndOffset(int pos, int topOffsetPx) {
            mState.setPositionAndOffset(pos, topOffsetPx);
        }

        public int getCurrentTopOffset() {
            return mState.topOffsetPx;
        }

        public FakeLayoutManager getLayoutManager() {
            return mLayoutManager;
        }

        public int getScrollState() {
            return mState.scrollState;
        }

        public void scrollToPosition(int position) {
            mState.setPositionAndOffset(position, 0);
        }

        public void smoothScrollToPosition(int position) {
            mState.pendingSmoothTarget = position;
            mState.scrollState = 2; // SCROLL_STATE_SETTLING
        }

        public void stopScroll() {
            mState.pendingSmoothTarget = -1;
            mState.scrollState = 0;
        }

        public void finishSmoothScroll() {
            if (mState.pendingSmoothTarget >= 0) {
                mState.setPositionAndOffset(mState.pendingSmoothTarget, 0);
            } else {
                mState.scrollState = 0;
            }
        }

        @Override
        public boolean canScrollVertically(int direction) {
            if (direction < 0) {
                return mState.firstVisiblePos > 0 || mState.topOffsetPx < 0;
            }
            return true;
        }

        private static final class RecyclerState {
            final View topChildView;
            int firstVisiblePos = 0;
            int topOffsetPx = 0;
            int scrollState = 0;
            int pendingSmoothTarget = -1;

            RecyclerState(View topChildView) {
                this.topChildView = topChildView;
            }

            void setPositionAndOffset(int pos, int offsetPx) {
                this.firstVisiblePos = pos;
                this.topOffsetPx = offsetPx;
                this.scrollState = 0;
                this.pendingSmoothTarget = -1;
                this.topChildView.setTop(offsetPx);
                this.topChildView.setBottom(offsetPx + 80);
            }
        }

        public static final class FakeLayoutManager {
            private final RecyclerState mState;

            FakeLayoutManager(RecyclerState state) {
                mState = state;
            }

            public int findFirstVisibleItemPosition() {
                return mState.firstVisiblePos;
            }

            public View findViewByPosition(int position) {
                return position == mState.firstVisiblePos ? mState.topChildView : null;
            }

            public void scrollToPositionWithOffset(int position, int offset) {
                mState.setPositionAndOffset(position, offset);
            }
        }
    }

    /**
     * Simulates Material {@code AppBarLayout} reflection contract.
     */
    public static class FakeAppBarLayout extends FrameLayout {
        private int mOffset = 0;
        private int mCollapsedMin = -140;

        public FakeAppBarLayout(Context context) {
            super(context);
        }

        public void setCollapsedOffset(int offset) {
            mOffset = offset;
            if (offset < 0) {
                mCollapsedMin = offset;
            }
        }

        public int getTopAndBottomOffset() {
            return mOffset;
        }

        public void setExpanded(boolean expanded, boolean animate) {
            mOffset = expanded ? 0 : mCollapsedMin;
        }
    }

    /**
     * Simulates an R8-obfuscated virtualized list exposing only {@link View} scroll primitives.
     */
    private static final class ObfuscatedListView extends View {
        private final int mContentHeight;
        private int mVirtualOffsetPx = 0;

        ObfuscatedListView(Context context, int contentHeight) {
            super(context);
            mContentHeight = contentHeight;
        }

        @Override
        protected int computeVerticalScrollOffset() {
            return mVirtualOffsetPx;
        }

        @Override
        protected int computeVerticalScrollExtent() {
            return Math.max(1, getHeight());
        }

        @Override
        protected int computeVerticalScrollRange() {
            return mContentHeight;
        }

        @Override
        public boolean canScrollVertically(int direction) {
            if (direction < 0) {
                return mVirtualOffsetPx > 0;
            }
            return mVirtualOffsetPx < (mContentHeight - getHeight());
        }

        @Override
        public void scrollBy(int x, int y) {
            final int maxOffset = Math.max(0, mContentHeight - Math.max(1, getHeight()));
            mVirtualOffsetPx = Math.max(0, Math.min(maxOffset, mVirtualOffsetPx + y));
        }
    }

    /**
     * Simulates {@code AndroidComposeView} / Chromium virtual {@link AccessibilityNodeProvider}.
     */
    private static final class FakeComposeHostView extends View {
        private static final int VIRTUAL_LAZY_COLUMN_ID = 42;
        private static final int TOTAL_PAGES = 15;

        private int mCurrentPage;
        private int mPageUpCount = 0;
        private int mPageDownCount = 0;

        private final AccessibilityNodeProvider mProvider = new AccessibilityNodeProvider() {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                if (virtualViewId == HOST_VIEW_ID) {
                    final AccessibilityNodeInfo root =
                            new AccessibilityNodeInfo(FakeComposeHostView.this);
                    root.setVisibleToUser(true);
                    root.setBoundsInScreen(new Rect(0, 0, 400, 800));
                    root.addChild(FakeComposeHostView.this, VIRTUAL_LAZY_COLUMN_ID);
                    return root;
                }
                if (virtualViewId == VIRTUAL_LAZY_COLUMN_ID) {
                    final AccessibilityNodeInfo node = new AccessibilityNodeInfo(
                            FakeComposeHostView.this, VIRTUAL_LAZY_COLUMN_ID);
                    node.setVisibleToUser(true);
                    node.setScrollable(true);
                    node.setBoundsInScreen(new Rect(0, 80, 400, 800));
                    if (mCurrentPage > 0) {
                        node.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP);
                    }
                    if (mCurrentPage < TOTAL_PAGES) {
                        node.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN);
                    }
                    return node;
                }
                return null;
            }

            @Override
            public boolean performAction(int virtualViewId, int action, Bundle arguments) {
                if (virtualViewId != VIRTUAL_LAZY_COLUMN_ID) {
                    return false;
                }
                if (action == R.id.accessibilityActionPageUp
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    if (mCurrentPage > 0) {
                        mCurrentPage--;
                        mPageUpCount++;
                        return true;
                    }
                    return false;
                }
                if (action == R.id.accessibilityActionPageDown
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    if (mCurrentPage < TOTAL_PAGES) {
                        mCurrentPage++;
                        mPageDownCount++;
                        return true;
                    }
                    return false;
                }
                return false;
            }
        };

        FakeComposeHostView(Context context, int initialPage) {
            super(context);
            mCurrentPage = initialPage;
        }

        int getCurrentPage() {
            return mCurrentPage;
        }

        int getPageUpCount() {
            return mPageUpCount;
        }

        int getPageDownCount() {
            return mPageDownCount;
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return mProvider;
        }
    }

    /**
     * Simulates Jetpack Compose's {@code AndroidComposeView} with progress range reporting.
     */
    private static final class FakeAndroidComposeView extends View {
        private static final int VIRTUAL_LAZY_COLUMN_ID = 77;
        private final int mTotalPages;
        private int mCurrentPage;
        private int mPageUpCount = 0;

        private final AccessibilityNodeProvider mProvider = new AccessibilityNodeProvider() {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                if (virtualViewId == HOST_VIEW_ID) {
                    final AccessibilityNodeInfo root =
                            new AccessibilityNodeInfo(FakeAndroidComposeView.this);
                    root.setVisibleToUser(true);
                    root.setBoundsInScreen(new Rect(0, 0, 400, 800));
                    root.addChild(FakeAndroidComposeView.this, VIRTUAL_LAZY_COLUMN_ID);
                    return root;
                }
                if (virtualViewId == VIRTUAL_LAZY_COLUMN_ID) {
                    final AccessibilityNodeInfo node = new AccessibilityNodeInfo(
                            FakeAndroidComposeView.this, VIRTUAL_LAZY_COLUMN_ID);
                    node.setVisibleToUser(true);
                    node.setScrollable(true);
                    node.setBoundsInScreen(new Rect(0, 80, 400, 800));
                    node.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                            0f, mTotalPages, mCurrentPage));
                    if (mCurrentPage > 0) {
                        node.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP);
                    }
                    if (mCurrentPage < mTotalPages) {
                        node.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN);
                    }
                    return node;
                }
                return null;
            }

            @Override
            public boolean performAction(int virtualViewId, int action, Bundle arguments) {
                if (virtualViewId != VIRTUAL_LAZY_COLUMN_ID) {
                    return false;
                }
                if (action == R.id.accessibilityActionPageUp
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    if (mCurrentPage > 0) {
                        mCurrentPage--;
                        mPageUpCount++;
                        return true;
                    }
                    return false;
                }
                if (action == R.id.accessibilityActionPageDown
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    if (mCurrentPage < mTotalPages) {
                        mCurrentPage++;
                        return true;
                    }
                    return false;
                }
                return false;
            }
        };

        FakeAndroidComposeView(Context context, int initialPage) {
            super(context);
            mTotalPages = Math.max(initialPage, 40);
            mCurrentPage = initialPage;
        }

        int getCurrentPage() {
            return mCurrentPage;
        }

        int getPageUpCount() {
            return mPageUpCount;
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return mProvider;
        }
    }

    /**
     * Simulates an indexed virtual list exposing {@code ACTION_SCROLL_TO_POSITION}.
     */
    private static final class FakeIndexedVirtualHostView extends View {
        private static final int VIRTUAL_LIST_ID = 91;
        private static final int FIRST_CHILD_ID = 92;

        private int mCurrentRow;
        private int mCurrentCol;
        private int mScrollToPositionCount = 0;

        private final AccessibilityNodeProvider mProvider = new AccessibilityNodeProvider() {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                if (virtualViewId == HOST_VIEW_ID) {
                    final AccessibilityNodeInfo root =
                            new AccessibilityNodeInfo(FakeIndexedVirtualHostView.this);
                    root.setVisibleToUser(true);
                    root.setBoundsInScreen(new Rect(0, 0, 400, 800));
                    root.addChild(FakeIndexedVirtualHostView.this, VIRTUAL_LIST_ID);
                    return root;
                }
                if (virtualViewId == VIRTUAL_LIST_ID) {
                    final AccessibilityNodeInfo node = new AccessibilityNodeInfo(
                            FakeIndexedVirtualHostView.this, VIRTUAL_LIST_ID);
                    node.setVisibleToUser(true);
                    node.setScrollable(true);
                    node.setBoundsInScreen(new Rect(0, 80, 400, 800));
                    node.addChild(FakeIndexedVirtualHostView.this, FIRST_CHILD_ID);
                    node.addAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION);
                    if (mCurrentRow > 0) {
                        node.addAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP);
                    }
                    return node;
                }
                if (virtualViewId == FIRST_CHILD_ID) {
                    final AccessibilityNodeInfo item = new AccessibilityNodeInfo(
                            FakeIndexedVirtualHostView.this, FIRST_CHILD_ID);
                    item.setVisibleToUser(true);
                    item.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                            mCurrentRow, 1, mCurrentCol, 1, false));
                    return item;
                }
                return null;
            }

            @Override
            public boolean performAction(int virtualViewId, int action, Bundle arguments) {
                if (virtualViewId == VIRTUAL_LIST_ID
                        && action == R.id.accessibilityActionScrollToPosition
                        && arguments != null) {
                    mCurrentRow = Math.max(0,
                            arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0));
                    mCurrentCol = Math.max(0,
                            arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0));
                    mScrollToPositionCount++;
                    return true;
                }
                return false;
            }
        };

        FakeIndexedVirtualHostView(Context context, int initialRow, int initialCol) {
            super(context);
            mCurrentRow = initialRow;
            mCurrentCol = initialCol;
        }

        int getCurrentRow() {
            return mCurrentRow;
        }

        int getCurrentCol() {
            return mCurrentCol;
        }

        int getScrollToPositionCount() {
            return mScrollToPositionCount;
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return mProvider;
        }
    }
}
