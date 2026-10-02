package com.oxohang.fanfreeform.xposed;

import java.lang.reflect.Method;

/**
 * 应用锁定 <b>写</b> 接口 —— 调 MIUI 任务中心自己的锁定入口，不自造旁路。
 *
 * <p><b>原生接口（设备反编译实测，2026-10-02）</b>：MIUI 的"任务锁定"在框架层提供了一条
 * 专用写接口，桌面（上滑锁定）与系统设置（应用锁）走的都是它：
 *
 * <pre>
 * miui.process.ProcessManager.updateApplicationLockedState(
 *         String packageName, int userId, boolean locked)      ← 本类反射调用这一层
 *   ↓
 * miui.process.ProcessManagerNative / ProcessManagerProxy      （framework，处理 Binder 编解码）
 *   ↓
 * miui.process.IProcessManager.UPDATE_APPLICATION_LOCKED_STATE （Binder 接口常量）
 *   ↓
 * com.android.server.am.ProcessManagerService.updateApplicationLockedState  （system_server）
 *   ↓
 * ProcessPolicy（内存态 sLockedApplicationList）→ 落盘 Settings.System("locked_apps")
 *   ↓
 * 反向通知链：ProcessPolicy → launcher Task.isLocked / TaskView.onLockedChanged
 * </pre>
 *
 * <p><b>为什么必须走这条而不是直接写 Settings</b>（2026-10-02 实测教训）：
 * 直接写 {@code Settings.System("locked_apps")} 虽然能改到"权威源头"，但它是
 * <b>数据的旁路</b>——绕过 ProcessManagerService 的业务处理。后果是：
 * system_server 的内存态会更新（{@code service call ProcessManager 12} 能查到），
 * 但 <b>launcher 侧的 {@code Task.isLocked} 缓存不会被通知刷新</b>，
 * 导致"模块列表里锁上了，原生任务中心看不到锁"。
 * 走 {@code updateApplicationLockedState} 才是系统自己的入口，通知链完整闭合。
 *
 * <p><b>降级链</b>：首选本类（原生接口）→ 不可用时由调用方回退
 * {@link LockedAppStore#setLocked}（直接写 Settings，能改状态但通知链不完整）。
 * 两者都会打日志区分，便于定位走的是哪一条。
 */
final class LockedAppBridge {

    private static final String CLASS_PROCESS_MANAGER = "miui.process.ProcessManager";
    private static final String METHOD_UPDATE_LOCKED = "updateApplicationLockedState";

    /** 反射解析一次后缓存，避免热路径反复查方法。 */
    private static volatile Method updateMethod;
    /** 解析失败的终态标记（全部候选签名都不存在），避免每帧重试抛异常。 */
    private static volatile boolean updateUnavailable;

    private LockedAppBridge() {
    }

    /**
     * 调原生接口设置某个应用的锁定状态。
     *
     * @return true = 已通过原生接口送达；false = 原生接口不可用，调用方应回退写 Settings。
     */
    static boolean setLocked(String packageName, int userId, boolean locked) {
        if (packageName == null || packageName.isEmpty()) return false;
        Method method = resolve();
        if (method == null) return false;
        try {
            method.invoke(null, packageName, userId, locked);
            Log.i("LockedAppBridge updateApplicationLockedState pkg=" + packageName
                    + " userId=" + userId + " locked=" + locked);
            return true;
        } catch (Throwable error) {
            // 调用失败不再回退到"解析别的签名"：签名是唯一的，失败通常是权限/服务未就绪，
            // 这种情况写 Settings 也救不了，直接交给调用方走降级链并打日志。
            Log.i("LockedAppBridge invoke failed pkg=" + packageName
                    + " error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    /** 原生接口当前是否可用（探测过一次即可判定）。 */
    static boolean isAvailable() {
        return resolve() != null;
    }

    private static Method resolve() {
        if (updateUnavailable) return null;
        Method cached = updateMethod;
        if (cached != null) return cached;
        synchronized (LockedAppBridge.class) {
            if (updateUnavailable) return null;
            if (updateMethod != null) return updateMethod;
            try {
                Class<?> clazz = Class.forName(CLASS_PROCESS_MANAGER);
                Method method = clazz.getMethod(METHOD_UPDATE_LOCKED,
                        String.class, int.class, boolean.class);
                method.setAccessible(true);
                updateMethod = method;
                Log.i("LockedAppBridge resolved " + CLASS_PROCESS_MANAGER
                        + "." + METHOD_UPDATE_LOCKED);
            } catch (Throwable error) {
                // 类/方法不存在：本 ROM 没有这条接口，标记终态不再重试。
                updateUnavailable = true;
                Log.i("LockedAppBridge unavailable error=" + error.getClass().getSimpleName()
                        + " detail=" + error.getMessage());
            }
            return updateMethod;
        }
    }
}
