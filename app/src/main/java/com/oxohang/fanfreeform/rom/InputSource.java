package com.oxohang.fanfreeform.rom;

/**
 * 边缘/系统手势输入源的统一接口 —— 预留给后续侧滑与边缘手势接入的扩展点。
 *
 * <p>本阶段只定义契约：事件一律以原始 {@code MotionEvent} 透传（用 {@code Object}
 * 声明以兼容单元测试与多进程），具体手势语义由业务层处理。OS1 上没有 wm.shell 原生
 * 事件控制器，因此本阶段的实现恒为 {@link Kind#NONE}、{@link #start} 返回
 * {@code UNSUPPORTED}；后续可以基于 {@code com.android.systemui.fsgesture.GestureStubView}
 * 实现一个 {@code SYSTEM_GESTURE_VIEW} 版本而无需改动调用方。
 */
public interface InputSource {

    enum Kind {
        /** 当前 ROM 上没有可用的系统输入源。 */
        NONE,
        /** 系统手势 View（例如 OS1 的 GestureStubView）。 */
        SYSTEM_GESTURE_VIEW,
        /** wm.shell 原生事件控制器（较新 ROM，附 InputMonitor，可 pilfer）。 */
        SHELL_EVENT_CONTROLLER
    }

    interface MotionListener {
        /** 原始 MotionEvent 以 Object 透传，调用方自行 instanceof 判断。 */
        void onMotion(Object motionEvent);
    }

    /** 输入源类型，便于上层决定是否可抢占、是否需自建兜底。 */
    Kind kind();

    /** 输入源当前是否可用（kind != NONE 且底层目标已就绪）。 */
    boolean isAvailable();

    /** 启动输入源并开始把事件转发给 listener；本阶段 OS1 实现恒返回 UNSUPPORTED。 */
    BridgeResult<Void> start(MotionListener listener);

    /** 停止输入源并解除转发。 */
    void stop();
}
