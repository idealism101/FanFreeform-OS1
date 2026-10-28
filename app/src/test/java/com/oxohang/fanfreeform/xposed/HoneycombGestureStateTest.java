package com.oxohang.fanfreeform.xposed;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 蜂窝态手势状态机单测。
 *
 * <p>进入已改为显式 {@code enter()}（是否进入由调用方按场景决定），
 * 状态机本身只保留"退出"的迟滞判定 {@code shouldExit(distance, returnThreshold)}。
 */
public final class HoneycombGestureStateTest {
    @Test public void exitsOnlyAtTheOriginalFanActivationPosition() {
        HoneycombGestureState state = new HoneycombGestureState();
        state.enter();
        // 未到回落阈值：保持激活，不退出。
        assertFalse(state.shouldExit(171f, 170f));
        // 到达阈值：退出。
        assertTrue(state.shouldExit(170f, 170f));
        // 已退出，后续更小的位移不再重复触发。
        assertFalse(state.shouldExit(120f, 170f));
    }

    @Test public void resetAllowsFreshEntry() {
        HoneycombGestureState state = new HoneycombGestureState();
        state.enter();
        state.reset();
        // reset 后不再处于激活态，退出判定不成立。
        assertFalse(state.shouldExit(170f, 170f));
        // 重新进入后，迟滞判定照旧可用。
        state.enter();
        assertTrue(state.shouldExit(170f, 170f));
    }

    @Test public void sideHoneycombWithoutFanReturnThresholdNeverSwitchesToFan() {
        HoneycombGestureState state = new HoneycombGestureState();
        state.enter();
        // 回落阈值为 0（侧边蜂窝没有风扇回落位）：永不退出。
        assertFalse(state.shouldExit(0f, 0f));
    }

    @Test public void exitIsOneShotUntilReentered() {
        HoneycombGestureState state = new HoneycombGestureState();
        state.enter();
        assertTrue(state.shouldExit(170f, 170f));
        // 退出后不重复触发。
        assertFalse(state.shouldExit(170f, 170f));
        // 再次进入后可再次退出。
        state.enter();
        assertTrue(state.shouldExit(170f, 170f));
    }
}
