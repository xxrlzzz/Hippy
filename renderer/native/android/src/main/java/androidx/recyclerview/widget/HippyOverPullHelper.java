/* Tencent is pleased to support the open source community by making easy-recyclerview-helper available.
 * Copyright (C) 2020 THL A29 Limited, a Tencent company. All rights reserved.
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

package androidx.recyclerview.widget;

import static android.view.View.OVER_SCROLL_NEVER;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import com.tencent.mtt.hippy.views.hippylist.recyclerview.helper.AnimatorListenerBase;

/**
 * Created on 2021/3/15.
 * Description
 * 原生recyclerView是不支持拉到最顶部，还可以继续拉动，要实现继续拉动，并且松手回弹的效果
 * recyclerView上拉回弹和下拉回弹的效果实现
 */
public class HippyOverPullHelper {

    private static final int DURATION = 150;
    private final OnScrollListener listener;

    private int overPullState = OVER_PULL_NONE;
    public static final int OVER_PULL_NONE = 0;
    public static final int OVER_PULL_DOWN_ING = 1;
    public static final int OVER_PULL_UP_ING = 2;
    public static final int OVER_PULL_NORMAL = 3;
    public static final int OVER_PULL_SETTLING = 4;

    private ValueAnimator animator;
    private boolean enableOverDrag = true;
    private int lastOverScrollMode = -1;
    private boolean isRollBacking = false;
    private HippyOverPullListener overPullListener = null;
    private RecyclerViewBase recyclerView;
    private int scrollState;

    private final boolean isVertical;

    private final IRecyclerViewScrollDelegate scrollDelegate;

    public HippyOverPullHelper(RecyclerViewBase recyclerView, boolean isVertical) {
        this.recyclerView = recyclerView;
        this.isVertical = isVertical;
        this.scrollDelegate = isVertical ? new VerticalScrollDelegate(recyclerView) :
            new HorizontalScrollDelegate(recyclerView);
        lastOverScrollMode = recyclerView.getOverScrollMode();
        listener = new OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (scrollState != newState && newState == RecyclerView.SCROLL_STATE_IDLE) {
                    rollbackToBottomOrTop();
                }
                scrollState = newState;
            }
        };
        recyclerView.addOnScrollListener(listener);
    }

    public boolean isVertical() {
        return this.isVertical;
    }

    public void destroy() {
        recyclerView.removeOnScrollListener(listener);
    }

    private int getTouchSlop() {
        final ViewConfiguration vc = ViewConfiguration.get(recyclerView.getContext());
        return vc.getScaledTouchSlop();
    }

    public void setOverPullListener(HippyOverPullListener overPullListener) {
        this.overPullListener = overPullListener;
    }

    private boolean isMoving(MotionEvent event) {
        return scrollDelegate.isMoving(event, getTouchSlop());
    }

    public boolean onTouchEvent(MotionEvent event) {
        return isRollBacking || checkOverDrag(event);
    }

    /**
     * 检测是否处于顶部过界拉取，或者顶部过界拉取
     */
    private boolean checkOverDrag(MotionEvent event) {
        if (!enableOverDrag) {
            return false;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                scrollDelegate.updateMotion(event);
                break;
            case MotionEvent.ACTION_MOVE:
                boolean overPullDown = isOverPullDown(event);
                boolean overScrollUp = isOverPullUp(event);
                if ((overPullDown || overScrollUp)) {
                    recyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
                    recyclerView.invalidateGlows();
                    if (overPullDown) {
                        setOverPullState(OVER_PULL_DOWN_ING);
                    } else {
                        setOverPullState(OVER_PULL_UP_ING);
                    }
                    int delta = scrollDelegate.getSignedDistance(event) / 2;
//                    if (deltaY > 0) {
//                        //下拉的时候除以2，放慢拉动的速度，调节拉动的手感
//                        deltaY = deltaY / 2;
//                    }
                    scrollDelegate.offsetChildren(delta);
                    if (overPullListener != null) {
                        overPullListener.onOverPullStateChanged(overPullState, overPullState, getOverPullOffset());
                    }
                } else {
                    setOverPullState(OVER_PULL_NORMAL);
                }
                scrollDelegate.updateLastRaw(event);
                break;
            default:
                reset();
        }
        return overPullState == OVER_PULL_DOWN_ING || overPullState == OVER_PULL_UP_ING;
    }

    /**
     * 在松开手后，
     * 1、如果当前处于fling状态，scrollState的值是SCROLL_STATE_SETTLING，先不做rollbackToBottomOrTop
     * 等到onScrollStateChanged 变成 IDLE的时候，再做rollbackToBottomOrTop
     * 2、如果当前处于非fling状态，scrollState的值不是SCROLL_STATE_SETTLING，就立即做rollbackToBottomOrTop
     */
    public void handleEventUp(MotionEvent event) {
        if (isActionUpOrCancel(event)) {
            revertOverScrollMode();
            if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_SETTLING) {
                rollbackToBottomOrTop();
            }
        }
    }

    private void revertOverScrollMode() {
        if (lastOverScrollMode != -1) {
            recyclerView.setOverScrollMode(lastOverScrollMode);
        }
    }

    private int getOverPullOffset() {
        if (overPullState == OVER_PULL_DOWN_ING) {
            return getOverPullDownOffset();
        } else if (overPullState == OVER_PULL_UP_ING) {
            return getOverPullUpOffset();
        }
        return 0;
    }

    void setOverPullState(int newOverPullState) {
        if (overPullListener != null) {
            overPullListener.onOverPullStateChanged(overPullState, newOverPullState, getOverPullOffset());
        }
        overPullState = newOverPullState;
    }

    /**
     * 因为可能出现越界拉取，松手后需要回退到原来的位置，要么回到顶部，要么回到底部
     */
    void rollbackToBottomOrTop() {
        int distanceToTop = scrollDelegate.computeScrollOffset();
        if (distanceToTop < 0) {
            //顶部空出了一部分，需要回滚上去
            rollbackTo(distanceToTop, 0);
        } else {
            //底部空出一部分，需要混滚下去
            int overPullUpOffset = getOverPullUpOffset();
            if (overPullUpOffset != 0) {
                rollbackTo(overPullUpOffset, 0);
            }
        }
    }

    /**
     * 计算底部被overPull的偏移，需要向下回滚的距离
     * 要么出现底部内容顶满distanceToBottom，要么出现顶部内容顶满distanceToTop，取最小的那一个
     *
     * @return
     */
    public int getOverPullUpOffset() {
        int contentOffset = scrollDelegate.computeScrollOffset();
        int scrollRange = scrollDelegate.computeScrollRange();
        int blankStartToEnd = contentOffset + scrollDelegate.getScrollSpace() - scrollRange;
        if (blankStartToEnd > 0 && contentOffset > 0) {
            return Math.min(blankStartToEnd, contentOffset);
        }
        return 0;
    }

    private boolean isActionUpOrCancel(MotionEvent event) {
        return event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL;
    }

    private void endAnimation() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.end();
            animator = null;
        }
        isRollBacking = false;
    }

    /**
     * 回弹动画的接口
     */
    private void rollbackTo(int from, int to) {
        endAnimation();
        animator = ValueAnimator.ofInt(from, to);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new RollbackUpdateListener(from));
        animator.addListener(new AnimatorListenerBase() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setOverPullState(OVER_PULL_NONE);
                isRollBacking = false;
            }
        });
        isRollBacking = true;
        animator.setDuration(DURATION).start();
    }

    private void reset() {
        revertOverScrollMode();
        scrollDelegate.clearMotion();
        setOverPullState(OVER_PULL_NONE);
    }

    /**
     * 顶部是否可以越界下拉，拉出一段空白区域，越界的部分最多不能超过RecyclerView高度+1
     */
    private boolean isOverPullDown(MotionEvent event) {
        //常规情况，内容在顶部offset为0，异常情况，内容被完全拉到最底部，看不见内容的时候，offset也为0
        int offset = scrollDelegate.computeScrollOffset();
        int dis = scrollDelegate.getDistance(event) + 1;
        //不能把内容完全拉得看不见
        if (Math.abs(offset) + dis < scrollDelegate.getScrollSpace()) {
            return isMoving(event) && isPullDownAction(event) && !canOverPullDown();
        }
        return false;
    }

    /**
     * 底部是否可以越界上拉，拉出一段空白区域，越界的部分最多不能超过RecyclerView高度的一般
     */
    private boolean isOverPullUp(MotionEvent event) {
        int dis = scrollDelegate.getDistance(event) + 1;
        int distanceToEnd = scrollDelegate.computeScrollOffset() + scrollDelegate.getScrollSpace()
            - scrollDelegate.computeScrollRange();
        if (distanceToEnd + dis < recyclerView.getWidth()) {
            return isMoving(event) && isPullUpAction(event) && !canOverPullUp();
        }
        return false;
    }

    boolean isPullDownAction(MotionEvent event) {
        return scrollDelegate.motionDirection(event) > 0;
    }

    boolean isPullUpAction(MotionEvent event) {
        return scrollDelegate.motionDirection(event) <= 0;
    }

    /**
     * 顶部还有内容，还可以向下拉到
     */
    boolean canOverPullDown() {
        return scrollDelegate.canScroll(-1);
    }

    /**
     * 底部还有内容，还可以向上拉动
     */
    boolean canOverPullUp() {
        return scrollDelegate.canScroll(1);
    }

    public int getOverPullState() {
        return overPullState;
    }

    /**
     * 下拉的时候，返回值<0,表示顶部被下拉了一部分距离
     */
    public int getOverPullDownOffset() {
        if (overPullState == OVER_PULL_DOWN_ING) {
            return scrollDelegate.computeScrollOffset();
        }
        return 0;
    }

    private class RollbackUpdateListener implements ValueAnimator.AnimatorUpdateListener {

        int currentValue;
//        int totalConsumedY;

        RollbackUpdateListener(int fromValue) {
            currentValue = fromValue;
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            if (recyclerView.isDataChangedWithoutNotify()) {
                //由于动画是一个异步操作，做动画的时候，recyclerView的adapter状态已经变化，但是没有进行notify，导致state和adapter
                //的itemCount对不齐，比如hippy场景，直接把recyclerView的renderNode删除了，adapter的itemCount直接变为0，
                //由于没有notifyDatSetChange，state的itemCount不为0，这样就会出现validateViewHolderForOffsetPosition报
                //IndexOutOfBoundsException
                return;
            }
            int value = (int) animation.getAnimatedValue();
            int[] consumed = new int[2];
            int diff = value - currentValue;
            //dy>0 上回弹，列表内容向上滚动，慢慢显示底部的内容;dy<0 下回弹，列表内容向下滚动，慢慢显示顶部的内容
            if (isVertical) {
                recyclerView.scrollStep(0, diff, consumed);
            } else {
                recyclerView.scrollStep(diff, 0, consumed);
            }
            int consumedY = consumed[1];
//            totalConsumedY += consumedY;

            //consumedY是排版view消耗的Y的距离,没有内容填充，即consumedY为0，需要强行offsetChildrenVertical
            if (isVertical) {
                int leftOffset = consumedY - diff;
                if (leftOffset != 0) {
                    //leftOffset<0 向上回弹，leftOffset>0  向下回弹
                    recyclerView.offsetChildrenVertical(leftOffset);
                }
            } else {
                int topOffset = consumed[0] - diff;
                if (topOffset != 0) {
                    recyclerView.offsetChildrenHorizontal(topOffset);
                }
            }
            setOverPullState(OVER_PULL_SETTLING);
            currentValue = value;
        }
    }

    private interface IRecyclerViewScrollDelegate {
        void offsetChildren(int dis);

        int computeScrollOffset();
        int computeScrollRange();

        int getScrollSpace();

        boolean canScroll(int direction);

        int getDistance(MotionEvent event);
        int getSignedDistance(MotionEvent event);

        void updateMotion(MotionEvent event);
        void updateLastRaw(MotionEvent event);
        void clearMotion();

        int motionDirection(MotionEvent event);

        boolean isMoving(MotionEvent event, int slop);
    }

    private static class VerticalScrollDelegate implements IRecyclerViewScrollDelegate {
        private final RecyclerView recyclerView;
        float lastRawY = -1;
        float downRawY = -1;

        public VerticalScrollDelegate(RecyclerView recyclerView) {
            this.recyclerView = recyclerView;
        }
        @Override
        public void offsetChildren(int dx) {
            recyclerView.offsetChildrenVertical(dx);
        }

        @Override
        public int computeScrollOffset() {
            return recyclerView.computeVerticalScrollOffset();
        }

        @Override
        public int computeScrollRange() {
            return recyclerView.computeVerticalScrollRange();
        }

        @Override
        public int getScrollSpace() {
            return recyclerView.getHeight();
        }

        @Override
        public boolean canScroll(int direction) {
            return recyclerView.canScrollVertically(direction);
        }

        @Override
        public int getDistance(MotionEvent event) {
            return Math.abs((int)(event.getRawY() - lastRawY));
        }

        @Override
        public int getSignedDistance(MotionEvent event) {
            return (int)(event.getRawY() - lastRawY);
        }

        @Override
        public void updateMotion(MotionEvent event) {
            lastRawY = event.getRawY();
            downRawY = event.getRawY();
        }

        @Override
        public void updateLastRaw(MotionEvent event) {
            lastRawY = event.getRawY();
        }

        @Override
        public void clearMotion() {
            lastRawY = -1;
            downRawY = -1;
        }

        @Override
        public int motionDirection(MotionEvent event) {
            return (int) (event.getRawY() - lastRawY);
        }

        @Override
        public boolean isMoving(MotionEvent event, int slop) {
            return lastRawY > 0 && Math.abs(event.getRawY() - downRawY) > slop;
        }
    }

    private static class HorizontalScrollDelegate implements IRecyclerViewScrollDelegate {
        private final RecyclerView recyclerView;
        private float lastRawX = -1;
        private float downRawX = -1;

        public HorizontalScrollDelegate(RecyclerView recyclerView) {
            this.recyclerView = recyclerView;
        }
        @Override
        public void offsetChildren(int dy) {
            recyclerView.offsetChildrenHorizontal(dy);
        }

        @Override
        public int computeScrollOffset() {
            return recyclerView.computeHorizontalScrollOffset();
        }

        @Override
        public int computeScrollRange() {
            return recyclerView.computeHorizontalScrollRange();
        }

        @Override
        public int getScrollSpace() {
            return recyclerView.getWidth();
        }

        @Override
        public boolean canScroll(int direction) {
            return recyclerView.canScrollHorizontally(direction);
        }

        @Override
        public int getDistance(MotionEvent event) {
            return Math.abs((int)(event.getRawX() - lastRawX));
        }

        @Override
        public int getSignedDistance(MotionEvent event) {
            return (int)(event.getRawX() - lastRawX);
        }

        @Override
        public void updateMotion(MotionEvent event) {
            lastRawX = event.getRawX();
            downRawX = event.getRawX();
        }

        @Override
        public void updateLastRaw(MotionEvent event) {
            lastRawX = event.getRawX();
        }

        @Override
        public void clearMotion() {
            lastRawX = -1;
            downRawX = -1;
        }

        @Override
        public int motionDirection(MotionEvent event) {
            return (int) (event.getRawX() - lastRawX);
        }

        @Override
        public boolean isMoving(MotionEvent event, int slop) {
            return lastRawX > 0 && Math.abs(event.getRawX() - downRawX) > slop;
        }
    }
}
