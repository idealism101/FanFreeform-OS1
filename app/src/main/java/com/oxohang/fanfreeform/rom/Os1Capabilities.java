package com.oxohang.fanfreeform.rom;

/**
 * 当前 ROM 的能力快照。
 *
 * <p>每一项都是「解析到的真实类名」：非空表示该能力在当前 ROM 上可用（并记录实际用的
 * 哪一个候选），null 表示不可用。它是一个不可变数据快照，由 {@link RomBridge} 在
 * attach 时生成，业务代码可以把它直接写进兼容性报告。
 */
public final class Os1Capabilities {

    public final String freeformController;
    public final String freeformPinHandler;
    public final String freeformAnimation;
    public final String controlCenter;
    public final String notificationShade;
    public final String gestureObserver;
    public final String recentsContainer;
    /** wm.shell 原生事件控制器。OS1 上为 null（见 RomTargets 注释），仅较新 ROM 有。 */
    public final String shellEventController;
    /** 系统边缘手势 View（OS1 存在）。后续接入边缘手势时使用。 */
    public final String systemGestureView;

    private Os1Capabilities(Builder b) {
        this.freeformController = b.freeformController;
        this.freeformPinHandler = b.freeformPinHandler;
        this.freeformAnimation = b.freeformAnimation;
        this.controlCenter = b.controlCenter;
        this.notificationShade = b.notificationShade;
        this.gestureObserver = b.gestureObserver;
        this.recentsContainer = b.recentsContainer;
        this.shellEventController = b.shellEventController;
        this.systemGestureView = b.systemGestureView;
    }

    public boolean hasFreeformController() { return freeformController != null; }
    public boolean hasFreeformPinHandler() { return freeformPinHandler != null; }
    public boolean hasFreeformAnimation() { return freeformAnimation != null; }
    public boolean hasControlCenter() { return controlCenter != null; }
    public boolean hasRecentsContainer() { return recentsContainer != null; }

    /** 日志/兼容报告友好的单行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("Os1Capabilities{");
        put(sb, "freeformController", freeformController);
        put(sb, "freeformPinHandler", freeformPinHandler);
        put(sb, "freeformAnimation", freeformAnimation);
        put(sb, "controlCenter", controlCenter);
        put(sb, "notificationShade", notificationShade);
        put(sb, "gestureObserver", gestureObserver);
        put(sb, "recentsContainer", recentsContainer);
        put(sb, "shellEventController", shellEventController);
        put(sb, "systemGestureView", systemGestureView);
        return sb.append('}').toString();
    }

    private static void put(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value == null ? "-" : value).append(' ');
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String freeformController;
        private String freeformPinHandler;
        private String freeformAnimation;
        private String controlCenter;
        private String notificationShade;
        private String gestureObserver;
        private String recentsContainer;
        private String shellEventController;
        private String systemGestureView;

        public Builder freeformController(String v) { freeformController = v; return this; }
        public Builder freeformPinHandler(String v) { freeformPinHandler = v; return this; }
        public Builder freeformAnimation(String v) { freeformAnimation = v; return this; }
        public Builder controlCenter(String v) { controlCenter = v; return this; }
        public Builder notificationShade(String v) { notificationShade = v; return this; }
        public Builder gestureObserver(String v) { gestureObserver = v; return this; }
        public Builder recentsContainer(String v) { recentsContainer = v; return this; }
        public Builder shellEventController(String v) { shellEventController = v; return this; }
        public Builder systemGestureView(String v) { systemGestureView = v; return this; }

        public Os1Capabilities build() {
            return new Os1Capabilities(this);
        }
    }
}
