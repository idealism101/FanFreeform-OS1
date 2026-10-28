package com.oxohang.fanfreeform.xposed;

/**
 * 任务面板可发起的动作 —— <b>不隶属任何具体视图</b>。
 *
 * <p><b>为什么独立成枚举</b>：这两个类型原先都是 {@code TaskCenterListView} 的
 * {@code static final int} 常量，导致编排层（{@code FanRuntime}）反向依赖呈现层：
 * 换一个面板实现，业务层就得跟着改。实测这类跨层引用共 21 处。
 * 动作与因果是"业务语义"，不是"某个视图的实现细节"，因此上提到本包。
 *
 * <p>枚举序号与历史 {@code int} 值一一对应（{@link #legacyCode()}），便于与旧日志对照。
 */
enum TaskPanelAction {
    /** 切换该应用的锁定状态（走系统原生 {@code ProcessManager}）。 */
    LOCK(0),
    /** 在分屏视图中打开。 */
    SPLIT(1),
    /** 在弹出视图（小窗）中打开。 */
    FREEFORM(2),
    /** 打开系统「应用信息」页。 */
    DETAILS(3),
    /** 启动该应用发布的桌面快捷方式（需同时带 shortcut 数据）。 */
    SHORTCUT(4),
    /** 拉起系统全局搜索。 */
    SEARCH(5);

    private final int legacyCode;

    TaskPanelAction(int legacyCode) {
        this.legacyCode = legacyCode;
    }

    /** 历史 {@code TaskCenterListView.ACTION_*} 的整数值，仅用于日志对照。 */
    int legacyCode() {
        return legacyCode;
    }
}
