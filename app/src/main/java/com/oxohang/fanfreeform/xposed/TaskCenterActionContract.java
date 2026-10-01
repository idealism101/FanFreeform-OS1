package com.oxohang.fanfreeform.xposed;

/**
 * 任务中心列表（模块进程的普通 Activity）与模块运行时的 IPC 契约。
 *
 * <p>两侧进程不同，各取所长：
 * <ul>
 *   <li><b>模块进程</b>（桌面应用程序进程）：承载 Activity。只有应用窗口（ty=1
 *       BASE_APPLICATION）才会被系统按"当前界面"管理 —— 返回键、上划回桌面、
 *       下拉状态栏覆盖全部由系统原生生命周期处理，本模块一行关闭代码都不写。</li>
 *   <li><b>SystemUI 进程</b>：模块运行时（{@code FanRuntime}）所在进程，持有
 *       {@code SoScSplitScreenController} 等系统窗口管理对象。分屏动作必须由
 *       该进程调用。</li>
 * </ul>
 *
 * <p>因为 Activity 与运行时的进程是并列的（列表不再由 SystemUI 进程
 * {@code addView} 出来），两个方向都需要一条消息：
 * <ul>
 *   <li>{@link #ACTION_SHOW} — SystemUI 进程 → 模块进程：请把列表显示出来。</li>
 *   <li>{@link #ACTION_SPLIT} / {@link #ACTION_FREEFORM} — 模块进程 → SystemUI
 *       进程：用户在列表里选了分屏/弹出视图动作。</li>
 *   <li>{@link #ACTION_STARTED} — 模块进程 → SystemUI 进程：列表窗口已被系统
 *       真正显示（{@code onStart} 时发送）。这是"列表即将出现在屏幕上"唯一可靠
 *       的信号：届时收起重叠在列表之上的系统窗口捕获层，既不影响列表的窗口
 *       身份，也不存在"列表还没起来就把捕获层收掉"的竞态。</li>
 * </ul>
 */
final class TaskCenterActionContract {
    /** SystemUI 进程 → 模块进程：显示列表。 */
    static final String ACTION_SHOW = "com.oxohang.fanfreeform.action.TASK_LIST_SHOW";
    /** 模块进程 → SystemUI 进程：列表窗口已显示。 */
    static final String ACTION_STARTED = "com.oxohang.fanfreeform.action.TASK_LIST_STARTED";
    /** 模块进程 → SystemUI 进程：在分屏视图中打开。 */
    static final String ACTION_SPLIT = "com.oxohang.fanfreeform.action.TASK_LIST_SPLIT";
    /** 模块进程 → SystemUI 进程：在弹出视图（小窗）中打开。 */
    static final String ACTION_FREEFORM = "com.oxohang.fanfreeform.action.TASK_LIST_FREEFORM";

    static final String EXTRA_TASK_ID = "task_id";
    static final String EXTRA_PACKAGE = "package_name";
    static final String EXTRA_MAX_COUNT = "max_count";
    static final String EXTRA_INCLUDE_SNAPSHOTS = "include_snapshots";

    /** SystemUI 进程侧要收的广播所属包名（这些广播都显式 setPackage 到它）。 */
    static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    /** 广播的发送方应是模块自身；接收方据此校验来源。 */
    static final String SENDER_PACKAGE = "com.oxohang.fanfreeform";

    /**
     * 列表 Activity 的宿主包与全限定类名。
     *
     * <p><b>必须写死，不能用 {@code context.getPackageName()}。</b>
     * 发起跳转的 {@code FanRuntime} 运行在 SystemUI 进程，它拿到的 Context 属于
     * SystemUI，{@code getPackageName()} 返回的是 {@code com.android.systemui} ——
     * 用它拼组件名会去 SystemUI 包里找这个 Activity，必然抛
     * {@code ActivityNotFoundException}（设备实测过）。
     */
    static final String HOST_PACKAGE = SENDER_PACKAGE;
    static final String LIST_ACTIVITY_CLASS =
            "com.oxohang.fanfreeform.xposed.TaskCenterListActivity";

    private TaskCenterActionContract() { }
}
