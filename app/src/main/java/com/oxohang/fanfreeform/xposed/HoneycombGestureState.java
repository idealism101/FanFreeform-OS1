package com.oxohang.fanfreeform.xposed;

final class HoneycombGestureState {
    private boolean active;

    void enter() { active = true; }

    boolean shouldExit(float distance, float returnThreshold) {
        if (!active || returnThreshold <= 0f || distance > returnThreshold) return false;
        active = false;
        return true;
    }

    void reset() { active = false; }
}
