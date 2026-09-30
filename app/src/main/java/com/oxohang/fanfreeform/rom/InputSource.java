package com.oxohang.fanfreeform.rom;

import android.content.Context;

/**
 * 边缘/系统手势输入源的统一接口 —— 业务层只面对这层契约，不接触 ROM 类名与反射。
 *
 * <p>事件一律以原始 {@code MotionEvent} 透传（用 {@code Object} 声明以兼容单元测试与
 * 多进程），具体手势语义由业务层处理。能否抢占指针取决于实现是否持有系统
 * {@code InputMonitor}：
 * <ul>
 *   <li>{@link Kind#SHELL_EVENT_CONTROLLER}：ROM 自己的事件控制器回调，附 InputMonitor</li>
 *   <li>{@link Kind#FRAMEWORK_GESTURE_MONITOR}：直接向框架注册手势监听，附 InputMonitor</li>
 *   <li>{@link Kind#SYSTEM_GESTURE_VIEW}：只读旁路，拿不到 InputMonitor，无法抢占</li>
 * </ul>
 */
public interface InputSource {

    enum Kind {
        /** 当前没有可用的系统输入源。 */
        NONE,
        /** 系统手势 View（例如 OS1 的 GestureStubView）：只读旁路，无法抢占指针。 */
        SYSTEM_GESTURE_VIEW,
        /** wm.shell 原生事件控制器（较新 ROM，回调附 InputMonitor，可 pilfer）。 */
        SHELL_EVENT_CONTROLLER,
        /**
         * 框架标准手势监听：{@code InputManager.monitorGestureInput()} 建立的
         * 系统级手势监听，自己持有 InputMonitor，可 pilfer。不依赖任何 ROM 私有类，
         * 是 OS3 事件控制器内部所用的同一套机制。
         */
        FRAMEWORK_GESTURE_MONITOR
    }

    interface MotionListener {
        /** 原始 MotionEvent 以 Object 透传，调用方自行 instanceof 判断。 */
        void onMotion(Object motionEvent);
    }

    /** 输入源类型，便于上层决定是否可抢占、是否需自建兜底。 */
    Kind kind();

    /** 输入源当前是否可用（kind != NONE 且底层目标已就绪）。 */
    boolean isAvailable();

    /**
     * 输入源持有的系统 {@code InputMonitor}；不持有时返回 null。
     *
     * <p>框架不在 SDK 中暴露该类，故以 {@code Object} 交接，由业务层反射调用
     * {@code pilferPointers()}。这是"能抢占指针"的唯一凭据：没有它就只能旁听，
     * 无法从系统手势链路里抢走当前手势。
     */
    Object monitorHandle();

    /**
     * 用宿主 Context 启动输入源，并把事件转发给 listener。
     *
     * @param context 宿主进程的 Context（用于取 {@code InputManager} 与所在 display）
     * @return 成功返回 {@code OK}；起点缺失/被框架拒绝时返回对应 Code 与原因，不抛异常
     */
    BridgeResult<Void> start(Context context, MotionListener listener);

    /** 停止输入源并解除转发，释放系统侧资源。 */
    void stop();
}
