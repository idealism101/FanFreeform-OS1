package com.oxohang.fanfreeform.rom;

/**
 * 对接层统一日志出口。
 *
 * <p>对接层刻意不直接依赖宿主进程的日志实现：默认输出到 Xposed 的日志通道，宿主可以在
 * 初始化时注入 {@link Sink}，把日志转发到自己的记录器（例如模块内的日志/兼容报告）。
 */
public final class BridgeLog {

    public interface Sink {
        void info(String message);
        void error(String message, Throwable cause);
    }

    private static final String TAG = "FanFreeformROM";

    private static final Sink DEFAULT = new Sink() {
        @Override public void info(String message) {
            android.util.Log.i(TAG, message);
            try {
                de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
            } catch (Throwable ignored) {
                // Xposed 通道不可用时（例如单元测试）不构成错误。
            }
        }

        @Override public void error(String message, Throwable cause) {
            android.util.Log.e(TAG, message, cause);
            try {
                de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
                if (cause != null) de.robv.android.xposed.XposedBridge.log(cause);
            } catch (Throwable ignored) {
                // 同上。
            }
        }
    };

    private static final Sink sink = DEFAULT;

    private BridgeLog() { }

    public static void i(String message) {
        try {
            sink.info(message);
        } catch (Throwable ignored) {
            // 日志永远不应该影响功能路径。
        }
    }

    public static void e(String message, Throwable cause) {
        try {
            sink.error(message, cause);
        } catch (Throwable ignored) {
            // 同上。
        }
    }
}
