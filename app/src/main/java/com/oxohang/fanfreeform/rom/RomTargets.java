package com.oxohang.fanfreeform.rom;

import de.robv.android.xposed.XposedHelpers;

/**
 * ROM 私有接口的类名目标表 —— 整个项目唯一的类名真相源。
 *
 * <p>候选设计原则：按「谁存在就用谁」的顺序排列，不做机型/系统版本分支。不同大版本的
 * 小米系统会把同一套功能放进不同的包路径（有/没有 multitasking 前缀），候选数组按
 * 较新版本在前、较早版本在后的顺序列出，运行时刻探测第一个真实存在的。
 *
 * <p>以下类名已在真机 SystemUI dex 上逐条核验：
 * <ul>
 *   <li>目标机 thyme / M2102J2SC / HyperOS OS1.0 / V816.0.13.0.UMNCNXM / Android 14
 *       （核验脚本见仓库 docs/thyme-hyperos1-adaptation.md）</li>
 *   <li>OS1 上 miuifreeform / miuimultiwinswitch 均无 multitasking 前缀，
 *       且不存在 wm.shell 事件控制器（MulWinSwitchEventController 全系）</li>
 *   <li>OS1 上控制中心在 com.android.systemui.controlcenter.*，
 *       边缘手势由 com.android.systemui.fsgesture.GestureStubView 分发</li>
 * </ul>
 */
public final class RomTargets {

    private static final String SHELL_MULTITASKING = "com.android.wm.shell.multitasking.";
    private static final String SHELL_FLAT = "com.android.wm.shell.";

    public static final String[] FREEFORM_CONTROLLER = {
            SHELL_MULTITASKING + "miuifreeform.MiuiFreeformModeController",
            SHELL_FLAT + "miuifreeform.MiuiFreeformModeController"
    };

    public static final String[] FREEFORM_PIN_HANDLER = {
            SHELL_MULTITASKING + "miuifreeform.MiuiFreeformModePinHandler",
            SHELL_FLAT + "miuifreeform.MiuiFreeformModePinHandler"
    };

    public static final String[] FREEFORM_ANIMATION = {
            SHELL_MULTITASKING + "miuifreeform.MiuiFreeformModeAnimation",
            SHELL_FLAT + "miuifreeform.MiuiFreeformModeAnimation"
    };

    /** 边缘/多窗手势的原生事件控制器。OS1 上经核验不存在，仅较新 ROM 提供。 */
    public static final String[] SHELL_EVENT_CONTROLLER = {
            SHELL_MULTITASKING + "miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController",
            SHELL_MULTITASKING + "miuimultiwinswitch.MulWinSwitchEventController",
            SHELL_MULTITASKING + "miuimultiwinswitch.MiuiMultiWinSwitchEventController",
            SHELL_FLAT + "miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController"
    };

    public static final String[] CONTROL_CENTER = {
            "com.miui.systemui.controlcenter.ControlCenterImpl",
            "com.android.systemui.controlcenter.ControlCenter",
            "com.android.systemui.controlcenter.policy.ControlCenterControllerImpl"
    };

    public static final String[] NOTIFICATION_SHADE = {
            "com.android.systemui.shade.NotificationShadeWindowView"
    };

    public static final String[] GESTURE_OBSERVER = {
            "com.miui.systemui.controller.GestureObserver"
    };

    /** 桌面（com.miui.home）进程内的最近任务容器。 */
    public static final String[] RECENTS_CONTAINER = {
            "com.miui.home.recents.views.RecentsContainer"
    };

    /** 系统全面屏边缘手势 View。后续接入边缘手势时以它作为事件源扩展点。 */
    public static final String[] SYSTEM_GESTURE_VIEW = {
            "com.android.systemui.fsgesture.GestureStubView"
    };

    private RomTargets() { }

    /** 类存在性探针，便于在单元测试里替换实现、不依赖 Xposed。 */
    public interface ClassExistsProbe {
        boolean exists(String className);
    }

    /** 返回第一个真实存在的类名；全部不存在返回 null。 */
    public static String firstExisting(ClassExistsProbe probe, String... candidates) {
        if (probe == null || candidates == null) return null;
        for (String name : candidates) {
            if (name != null && probe.exists(name)) return name;
        }
        return null;
    }

    /** 用 Xposed 的类加载器探针解析第一个存在的类名。 */
    public static String firstExisting(ClassLoader classLoader, String... candidates) {
        return firstExisting(name -> {
            try {
                return XposedHelpers.findClassIfExists(name, classLoader) != null;
            } catch (Throwable ignored) {
                return false;
            }
        }, candidates);
    }
}
