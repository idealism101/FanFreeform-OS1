package com.oxohang.fanfreeform.rom;

import android.content.Context;

/**
 * OS1 原生能力的对接层门面。
 *
 * <p>用法：宿主在拿到目标进程 ClassLoader 后调用一次
 * {@link #attach(ClassLoader)}，对接层完成所有类名探测并锁定能力快照；之后业务通过
 * <p><b>快照是「按进程」的</b>：模块在每个宿主进程各加载一份，单例互不干扰，因此
 * SystemUI 与桌面应各自 attach 一次。SystemUI 的快照不含桌面私有类（如
 * {@code RecentsContainer}），桌面的快照不含 SystemUI 私有类，缺失项一律记 {@code null}，
 * 由各 Access 的 {@code isAvailable()} 如实反映，不做跨进程猜测。
 * {@link #freeform()} / {@link #controlCenter()} / {@link #recents()} / {@link #input()}
 * 拿到统一接口，不接触类名、反射或 Xposed 细节。
 *
 * <p>当前阶段（仅对接，不做手势）各接口行为：
 * <ul>
 *   <li>{@link FreeformAccess}：解析类名、登记控制器实例、声明 Pin/动画支持</li>
 *   <li>{@link ControlCenterAccess} / {@link RecentsAccess}：声明可用性与真实类名</li>
 *   <li>{@link InputSource}：向框架注册系统手势监听，拿到与 OS3 同源的
 *       {@code InputMonitor}，业务层据此 {@code pilferPointers()}；注册失败时如实报错</li>
 * </ul>
 */
public final class RomBridge {

    private static volatile RomBridge instance;

    public static RomBridge get() {
        RomBridge local = instance;
        if (local == null) {
            synchronized (RomBridge.class) {
                local = instance;
                if (local == null) {
                    local = new RomBridge();
                    instance = local;
                }
            }
        }
        return local;
    }

    private volatile Os1Capabilities capabilities;
    private volatile ClassLoader boundClassLoader;

    private final Os1FreeformAccess freeform = new Os1FreeformAccess();
    private final Os1ControlCenterAccess controlCenter = new Os1ControlCenterAccess();
    private final Os1RecentsAccess recents = new Os1RecentsAccess();
    private final InputSource input = new Os1GestureMonitorInputSource();

    private RomBridge() { }

    /**
     * 用宿主进程的 ClassLoader 探测并锁定能力。重复调用会重新探测（例如 ROM 更新后）。
     *
     * @return 探测快照；ClassLoader 为 null 时返回 {@code INVOCATION_FAILED}。
     */
    public synchronized BridgeResult<Os1Capabilities> attach(ClassLoader classLoader) {
        if (classLoader == null) {
            BridgeLog.e("RomBridge attach failed: null classLoader", null);
            return BridgeResult.fail(BridgeResult.Code.INVOCATION_FAILED,
                    "host ClassLoader is null");
        }
        boundClassLoader = classLoader;
        Os1Capabilities detected = Os1Capabilities.builder()
                .freeformController(RomTargets.firstExisting(classLoader,
                        RomTargets.FREEFORM_CONTROLLER))
                .freeformPinHandler(RomTargets.firstExisting(classLoader,
                        RomTargets.FREEFORM_PIN_HANDLER))
                .freeformAnimation(RomTargets.firstExisting(classLoader,
                        RomTargets.FREEFORM_ANIMATION))
                .controlCenter(RomTargets.firstExisting(classLoader,
                        RomTargets.CONTROL_CENTER))
                .notificationShade(RomTargets.firstExisting(classLoader,
                        RomTargets.NOTIFICATION_SHADE))
                .gestureObserver(RomTargets.firstExisting(classLoader,
                        RomTargets.GESTURE_OBSERVER))
                .recentsContainer(RomTargets.firstExisting(classLoader,
                        RomTargets.RECENTS_CONTAINER))
                .shellEventController(RomTargets.firstExisting(classLoader,
                        RomTargets.SHELL_EVENT_CONTROLLER))
                .systemGestureView(RomTargets.firstExisting(classLoader,
                        RomTargets.SYSTEM_GESTURE_VIEW))
                .build();
        this.capabilities = detected;
        BridgeLog.i("RomBridge attached: " + detected.describe());
        return BridgeResult.ok(detected);
    }

    public boolean isAttached() {
        return capabilities != null;
    }

    /** 探测快照；未 attach 返回 null。 */
    public Os1Capabilities capabilities() {
        return capabilities;
    }

    public FreeformAccess freeform() {
        return freeform;
    }

    public ControlCenterAccess controlCenter() {
        return controlCenter;
    }

    public RecentsAccess recents() {
        return recents;
    }

    public InputSource input() {
        return input;
    }

    /** 便于在 hook 层登记 SystemUI 解析出的 freeform 控制器实例。 */
    void bindFreeformControllerInstance(Object instance) {
        freeform.registerControllerInstance(instance);
    }

    // ------------------------------------------------------------------
    // OS1 实现。实现类持有的是 capabilities 快照与 ClassLoader，不硬编码 ROM 分支。
    // ------------------------------------------------------------------

    private Os1Capabilities requireCaps() {
        Os1Capabilities caps = capabilities;
        if (caps == null) {
            ClassLoader cl = boundClassLoader;
            if (cl != null) {
                BridgeResult<Os1Capabilities> result = attach(cl);
                caps = result.value();
            }
        }
        return caps;
    }

    private final class Os1FreeformAccess implements FreeformAccess {
        private volatile Object instance;

        @Override public boolean isAvailable() {
            Os1Capabilities caps = requireCaps();
            return caps != null && caps.hasFreeformController();
        }

        @Override public String resolvedClassName() {
            Os1Capabilities caps = requireCaps();
            return caps == null ? null : caps.freeformController;
        }

        @Override public void registerControllerInstance(Object controllerInstance) {
            this.instance = controllerInstance;
        }

        @Override public BridgeResult<Object> controllerInstance() {
            Os1Capabilities caps = requireCaps();
            if (caps == null || !caps.hasFreeformController()) {
                return BridgeResult.fail(BridgeResult.Code.UNSUPPORTED,
                        "freeform controller class not present on this ROM");
            }
            Object current = instance;
            if (current == null) {
                return BridgeResult.fail(BridgeResult.Code.NOT_READY,
                        "freeform controller class resolved but instance not created yet");
            }
            return BridgeResult.ok(current);
        }

        @Override public boolean supportsPinHandler() {
            Os1Capabilities caps = requireCaps();
            return caps != null && caps.hasFreeformPinHandler();
        }

        @Override public boolean supportsAnimation() {
            Os1Capabilities caps = requireCaps();
            return caps != null && caps.hasFreeformAnimation();
        }
    }

    private final class Os1ControlCenterAccess implements ControlCenterAccess {
        @Override public boolean isAvailable() {
            Os1Capabilities caps = requireCaps();
            return caps != null && caps.hasControlCenter();
        }

        @Override public String resolvedClassName() {
            Os1Capabilities caps = requireCaps();
            return caps == null ? null : caps.controlCenter;
        }

        @Override public boolean supportsExpansionObserver() {
            // 观察挂接属于手势阶段，本阶段未实现。
            return false;
        }
    }

    private final class Os1RecentsAccess implements RecentsAccess {
        @Override public boolean isAvailable() {
            Os1Capabilities caps = requireCaps();
            return caps != null && caps.hasRecentsContainer();
        }

        @Override public String resolvedClassName() {
            Os1Capabilities caps = requireCaps();
            return caps == null ? null : caps.recentsContainer;
        }
    }

    /**
     * 系统手势输入源：向框架注册 {@code monitorGestureInput} 手势监听。
     *
     * <p>OS1 上没有 wm.shell 原生事件控制器，但框架标准的手势监听机制是可用的，
     * 且正是 OS3 事件控制器内部所用的同一套：注册后框架把手势事件直接投递给本进程，
     * 并返回 {@code InputMonitor}，业务层因此具备与 OS3 完全一致的
     * {@code pilferPointers()} 抢占能力。不依赖任何 ROM 私有类名。
     *
     * <p>注册失败（框架拒绝、隐藏 API 被拦、{@code InputChannelCompat} 缺失）时
     * 如实返回失败原因，由调用方决定是否退回自建兜底窗口，此处不做任何替代实现。
     */
    private final class Os1GestureMonitorInputSource implements InputSource {
        private volatile GestureMonitorInputSource delegate;

        @Override public Kind kind() {
            GestureMonitorInputSource active = delegate;
            return active == null ? Kind.NONE : active.kind();
        }

        @Override public boolean isAvailable() {
            GestureMonitorInputSource active = delegate;
            return active != null && active.isAvailable();
        }

        @Override public Object monitorHandle() {
            GestureMonitorInputSource active = delegate;
            return active == null ? null : active.monitorHandle();
        }

        @Override public synchronized BridgeResult<Void> start(Context context,
                                                              MotionListener listener) {
            ClassLoader classLoader = boundClassLoader;
            if (classLoader == null) {
                return BridgeResult.fail(BridgeResult.Code.INVOCATION_FAILED,
                        "RomBridge is not attached; call attach(ClassLoader) first");
            }
            GestureMonitorInputSource existing = delegate;
            if (existing != null && existing.isAvailable()) return BridgeResult.ok(null);
            GestureMonitorInputSource created = new GestureMonitorInputSource(classLoader);
            BridgeResult<Void> started = created.start(context, listener);
            if (started.isOk()) {
                delegate = created;
            } else {
                created.stop();
            }
            return started;
        }

        @Override public synchronized void stop() {
            GestureMonitorInputSource active = delegate;
            delegate = null;
            if (active != null) active.stop();
        }
    }
}
