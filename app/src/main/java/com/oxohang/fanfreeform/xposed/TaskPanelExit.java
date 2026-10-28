package com.oxohang.fanfreeform.xposed;

/**
 * 任务面板的出场因果 —— <b>由业务层指定，由呈现层解释</b>。
 *
 * <p>每种因果对应一套终点形态与时长，但"具体怎么动"由呈现层决定：
 * 同一个 {@link #YIELD}，列表档是"位移 −12dp + 缩至 0.92 + 淡出"，
 * 未来换一种面板可以完全是另一种表达。业务层只说"为什么退"，不说"怎么退"。
 *
 * <p><b>为什么独立成枚举</b>：原先为 {@code TaskCenterListView.EXIT_*} 常量，
 * 导致编排层与承载层都要 import 具体视图类才能表达"让位退场"。
 * 枚举序号与历史 {@code int} 值一一对应（{@link #legacyCode()}）。
 */
enum TaskPanelExit {
    /** 回落：点外部 / 返回键。与入场同路径反演，表示"用户取消了这个界面"。 */
    RECEDE(0),
    /** 让位：点应用 / 菜单项。给即将出现的新界面腾位置。 */
    YIELD(1),
    /** 甩出：跟手上划被放行。继承手速飞离屏幕，表示"用户把它推走了"。 */
    FLING(2),
    /** 安静让路：系统事件（开小窗等）。只淡出，不抢注意力。 */
    QUIET(3);

    private final int legacyCode;

    TaskPanelExit(int legacyCode) {
        this.legacyCode = legacyCode;
    }

    /** 历史 {@code TaskCenterListView.EXIT_*} 的整数值，仅用于日志对照。 */
    int legacyCode() {
        return legacyCode;
    }
}
