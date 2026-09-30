package com.oxohang.fanfreeform.rom;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

/**
 * 基于框架标准手势监听的系统输入源 —— 与 OS3 事件控制器内部所用机制同源。
 *
 * <p>链路（与 SystemUI 自己的 Bubbles / ClipboardOverlay / DreamOverlay 完全一致）：
 * <pre>
 *   InputManager.monitorGestureInput(name, displayId)   -> InputMonitor
 *   InputMonitor.getInputChannel()                      -> InputChannel
 *   InputChannelCompat$InputEventReceiver(channel, looper, choreographer, listener)
 *   listener.onInputEvent(InputEvent)                   -> MotionEvent 透传给业务
 *   InputMonitor.pilferPointers()                       -> 抢占指针（业务层调用）
 * </pre>
 *
 * <p>为什么用 {@code InputChannelCompat} 而不是 {@code android.view.InputEventReceiver}：
 * 后者是抽象类且不在 SDK 中，无法在编译期继承；{@code InputChannelCompat} 是 SystemUI
 * 自带的现成具体子类（{@code BatchedInputEventReceiver} 的实现），构造签名已在
 * 目标机型 thyme / HyperOS 1.0 的 SystemUI dex 中逐参数核验。
 *
 * <p>拿不到 InputMonitor 时（框架拒绝注册、隐藏 API 被拦、InputChannelCompat 缺失）
 * 一律如实返回失败原因，不做任何替代实现。
 */
public final class GestureMonitorInputSource implements InputSource {

    /** 监听名，出现在 {@code dumpsys input} 里，便于排查。 */
    private static final String MONITOR_NAME = "fangesture-edge";

    private static final String RECEIVER_CLASS =
            "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver";
    private static final String LISTENER_CLASS =
            "com.android.systemui.shared.system.InputChannelCompat$InputEventListener";
    /** 不在 SDK 中的框架类，只能运行时按名字取。 */
    private static final String INPUT_CHANNEL_CLASS = "android.view.InputChannel";

    private final ClassLoader hostClassLoader;

    private volatile MotionListener listener;
    private volatile Object monitor;
    private volatile Object receiver;
    private volatile int displayId = Display.DEFAULT_DISPLAY;
    private volatile boolean running;

    public GestureMonitorInputSource(ClassLoader hostClassLoader) {
        this.hostClassLoader = hostClassLoader;
    }

    @Override
    public Kind kind() {
        return running ? Kind.FRAMEWORK_GESTURE_MONITOR : Kind.NONE;
    }

    @Override
    public boolean isAvailable() {
        return running && receiver != null && monitor != null;
    }

    /**
     * 业务层用来 {@code pilferPointers()} 的系统 InputMonitor 对象。
     * 未注册成功时返回 null，调用方须自行判空。
     */
    @Override
    public Object monitorHandle() {
        return monitor;
    }

    @Override
    public synchronized BridgeResult<Void> start(Context context, MotionListener listener) {
        if (context == null) {
            return BridgeResult.fail(BridgeResult.Code.INVOCATION_FAILED, "host Context is null");
        }
        if (running) return BridgeResult.ok(null);

        final Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        try {
            Object inputManager = appContext.getSystemService(Context.INPUT_SERVICE);
            if (!(inputManager instanceof InputManager)) {
                return BridgeResult.fail(BridgeResult.Code.CLASS_MISSING,
                        "InputManager service unavailable");
            }
            Method monitorMethod = InputManager.class.getMethod(
                    "monitorGestureInput", String.class, int.class);
            Display display = appContext.getDisplay();
            int targetDisplay = display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
            Object createdMonitor = monitorMethod.invoke(
                    inputManager, MONITOR_NAME, targetDisplay);
            if (createdMonitor == null) {
                return BridgeResult.fail(BridgeResult.Code.INVOCATION_FAILED,
                        "monitorGestureInput returned null");
            }
            Object channel = createdMonitor.getClass()
                    .getMethod("getInputChannel").invoke(createdMonitor);
            Class<?> channelClass = XposedHelpers.findClassIfExists(INPUT_CHANNEL_CLASS,
                    hostClassLoader);
            if (channelClass == null || !channelClass.isInstance(channel)) {
                disposeQuietly(null, createdMonitor);
                return BridgeResult.fail(BridgeResult.Code.CLASS_MISSING,
                        "InputMonitor.getInputChannel returned "
                                + (channel == null ? "null" : channel.getClass().getName()));
            }
            Class<?> receiverClass = XposedHelpers.findClassIfExists(RECEIVER_CLASS,
                    hostClassLoader);
            Class<?> listenerClass = XposedHelpers.findClassIfExists(LISTENER_CLASS,
                    hostClassLoader);
            if (receiverClass == null || listenerClass == null) {
                disposeQuietly(null, createdMonitor);
                return BridgeResult.fail(BridgeResult.Code.CLASS_MISSING,
                        "InputChannelCompat unavailable receiver=" + (receiverClass != null)
                                + " listener=" + (listenerClass != null));
            }
            Constructor<?> receiverConstructor = receiverClass.getConstructor(
                    channelClass, Looper.class, Choreographer.class, listenerClass);
            this.listener = listener;
            this.displayId = targetDisplay;
            Object proxy = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (instance, method, args) -> dispatch(method, args));
            Object createdReceiver = receiverConstructor.newInstance(channel,
                    Looper.getMainLooper(), Choreographer.getInstance(), proxy);
            this.monitor = createdMonitor;
            this.receiver = createdReceiver;
            this.running = true;
            BridgeLog.i("Framework gesture monitor registered name=" + MONITOR_NAME
                    + " display=" + targetDisplay
                    + " receiver=" + RECEIVER_CLASS);
            return BridgeResult.ok(null);
        } catch (NoSuchMethodException error) {
            return BridgeResult.fail(BridgeResult.Code.METHOD_MISSING,
                    "framework gesture monitor API is unavailable on this build", error);
        } catch (Throwable error) {
            return BridgeResult.fail(BridgeResult.Code.INVOCATION_FAILED,
                    "framework gesture monitor registration failed", error);
        }
    }

    @Override
    public synchronized void stop() {
        listener = null;
        disposeQuietly(receiver, monitor);
        receiver = null;
        monitor = null;
        if (running) {
            running = false;
            BridgeLog.i("Framework gesture monitor disposed name=" + MONITOR_NAME);
        }
    }

    private Object dispatch(Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) return "FanGestureInputEventListener";
        if ("hashCode".equals(name)) return System.identityHashCode(this);
        if ("equals".equals(name)) {
            return args != null && args.length == 1 && args[0] != null
                    && Proxy.isProxyClass(args[0].getClass());
        }
        if (!"onInputEvent".equals(name)) return null;

        MotionListener active = listener;
        Object event = args != null && args.length > 0 ? args[0] : null;
        if (!(event instanceof MotionEvent) || active == null) return null;
        MotionEvent motion = (MotionEvent) event;
        try {
            active.onMotion(motion);
        } catch (Throwable error) {
            BridgeLog.e("Gesture monitor listener failed safely", error);
        }
        return null;
    }

    private static void disposeQuietly(Object targetReceiver, Object targetMonitor) {
        if (targetReceiver != null) {
            try {
                targetReceiver.getClass().getMethod("dispose").invoke(targetReceiver);
            } catch (Throwable ignored) {
                // 释放失败不影响后续流程，只记录。
            }
        }
        if (targetMonitor != null) {
            try {
                targetMonitor.getClass().getMethod("dispose").invoke(targetMonitor);
            } catch (Throwable ignored) {
                // 同上。
            }
        }
    }
}
