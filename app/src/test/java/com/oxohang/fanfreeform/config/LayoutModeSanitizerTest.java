package com.oxohang.fanfreeform.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LayoutModeSanitizerTest {
    @Test
    public void removedSideIconWallFallsBackToList() {
        assertEquals(ConfigContract.SIDE_LAYOUT_LIST,
                LayoutModeSanitizer.sideLayout(6));
    }

    @Test
    public void legacyTaskIconWallValueIsNoLongerRemapped() {
        // 旧版曾把 2 当作「图标墙」的遗留映射。现在 2 本身即「三星同款卡片列表」，
        // 图标墙由 1（SIDE_TASK_LAYOUT_ICONS）承担，所以 2 不再做遗留转换。
        assertEquals(ConfigContract.SIDE_TASK_LAYOUT_SAMSUNG_LIST,
                LayoutModeSanitizer.taskLayout(2));
        assertEquals(ConfigContract.SIDE_TASK_LAYOUT_ICONS,
                LayoutModeSanitizer.taskLayout(1));
    }

    @Test
    public void invalidValuesAreClamped() {
        assertEquals(ConfigContract.SIDE_LAYOUT_LIST,
                LayoutModeSanitizer.sideLayout(-10));
        assertEquals(ConfigContract.SIDE_TASK_LAYOUT_FLAT,
                LayoutModeSanitizer.taskLayout(-5));
        // 上界是「三星同款卡片列表」，不是图标墙。
        assertEquals(ConfigContract.SIDE_TASK_LAYOUT_SAMSUNG_LIST,
                LayoutModeSanitizer.taskLayout(50));
    }
}
