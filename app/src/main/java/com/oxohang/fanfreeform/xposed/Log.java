package com.oxohang.fanfreeform.xposed;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import com.oxohang.fanfreeform.config.ConfigContract;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

final class Log {
    private static final String PREFIX = "[FanFreeform] ";
    private static final Object LOCK = new Object();
    private static final Object RATE_LOCK = new Object();
    private static final long RATE_LIMIT_MS = 30000L;
    private static final HashMap<String, Long> LAST_LOG = new HashMap<>();
    private static final HashMap<String, Integer> SUPPRESSED = new HashMap<>();
    private static final AtomicBoolean PUBLISH_IN_FLIGHT = new AtomicBoolean();
    private static volatile boolean publishNeeded;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final int MAX_RECENT = 100;
    private static final long INFO_PUBLISH_DELAY_MS = 5000L;
    private static final Runnable PUBLISH_TASK = Log::publish;
    private static final ExecutorService PUBLISH_EXECUTOR = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "fanfreeform-diagnostics"));
    private static final ThreadLocal<SimpleDateFormat> FORMATTER =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };
    private static Context reporterContext;
    /** XposedBridge 在本进程是否可用（模块自身进程没有 Xposed 桥）。 */
    private static volatile boolean hostSinkAvailable = true;
    private static Handler reporterHandler;
    private static boolean publishScheduled;
    private static long publishAtUptime;
    private Log() {}

    static void i(String message) {
        remember("I", message, null, false);
    }

    static void e(String message, Throwable throwable) {
        remember("E", message, throwable, true);
    }

    static void attachReporter(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        synchronized (LOCK) {
            reporterContext = appContext;
            reporterHandler = new Handler(appContext.getMainLooper());
            schedulePublishLocked(0L);
        }
    }

    /**
     * 把日志送进 LSPosed 的宿主机日志。
     *
     * <p><b>本类会在两个进程里被调用</b>：SystemUI 进程（模块运行时，Xposed 已注入，
     * {@link XposedBridge} 可用）与模块自身进程（例如任务中心列表 Activity 的宿主，
     * 那里没有 Xposed 桥）。后者直接引用 {@link XposedBridge} 会
     * {@code NoClassDefFoundError} 并连带把宿主 Activity 一起带崩，
     * 因此必须按"能用就用、不能用就降级"的方式调用，且降级后不再反复试。
     */
    private static void writeToHostSink(String line) {
        if (!hostSinkAvailable) return;
        try {
            XposedBridge.log(line);
        } catch (Throwable unavailable) {
            // 首次在无 Xposed 的进程里被调用：关掉该通道，后续只走 logcat 与诊断上报。
            hostSinkAvailable = false;
        }
    }

    private static void remember(String level, String message, Throwable throwable,
                                 boolean urgent) {
        String key = level + "\n" + (message == null ? "" : message);
        synchronized (RATE_LOCK) {
            long now = SystemClock.uptimeMillis();
            Long last = LAST_LOG.get(key);
            if (last != null && now - last < RATE_LIMIT_MS) {
                SUPPRESSED.merge(key, 1, Integer::sum);
                return;
            }
            LAST_LOG.put(key, now);
        }
        writeToHostSink(PREFIX + message + (throwable == null ? "" : ": " + throwable));
        StringBuilder line = new StringBuilder();
        line.append(FORMATTER.get().format(new Date())).append(' ').append(level).append(' ')
                .append(message == null ? "" : message);
        if (throwable != null) {
            line.append(" | ").append(throwable.getClass().getName());
            if (throwable.getMessage() != null && !throwable.getMessage().isEmpty()) {
                line.append(": ").append(throwable.getMessage());
            }
            StackTraceElement[] trace = throwable.getStackTrace();
            for (int i = 0; i < Math.min(4, trace.length); i++) {
                line.append("\n    at ").append(trace[i]);
            }
        }
        synchronized (LOCK) {
            RECENT.addLast(line.toString());
            while (RECENT.size() > MAX_RECENT) RECENT.removeFirst();
            schedulePublishLocked(urgent ? 0L : INFO_PUBLISH_DELAY_MS);
        }
    }

    private static void schedulePublishLocked(long delayMs) {
        if (reporterContext == null || reporterHandler == null) return;
        long target = SystemClock.uptimeMillis() + Math.max(0L, delayMs);
        if (publishScheduled && target >= publishAtUptime) return;
        if (publishScheduled) reporterHandler.removeCallbacks(PUBLISH_TASK);
        publishScheduled = true;
        publishAtUptime = target;
        reporterHandler.postDelayed(PUBLISH_TASK, Math.max(0L, delayMs));
    }

    private static void publish() {
        Context context;
        String text;
        synchronized (LOCK) {
            publishScheduled = false;
            publishAtUptime = 0L;
            context = reporterContext;
            StringBuilder snapshot = new StringBuilder();
            for (String line : RECENT) {
                if (snapshot.length() > 0) snapshot.append('\n');
                snapshot.append(line);
            }
            text = snapshot.toString();
        }
        if (context == null) return;
        if (context == null) return;
        if (!PUBLISH_IN_FLIGHT.compareAndSet(false, true)) {
            publishNeeded = true;
            return;
        }
        PUBLISH_EXECUTOR.execute(() -> {
            try {
                publishNow(context, text);
            } finally {
                PUBLISH_IN_FLIGHT.set(false);
                if (publishNeeded) {
                    publishNeeded = false;
                    Handler handler = reporterHandler;
                    if (handler != null) handler.post(PUBLISH_TASK);
                }
            }
        });
    }

    private static void publishNow(Context context, String text) {
        try {
            Bundle extras = new Bundle();
            extras.putString(ConfigContract.EXTRA_DIAGNOSTIC_PROCESS,
                    context.getPackageName());
            extras.putString(ConfigContract.EXTRA_DIAGNOSTIC_TEXT, text);
            context.getContentResolver().call(ConfigContract.URI,
                    "report_diagnostics", null, extras);
        } catch (Throwable error) {
            writeToHostSink(PREFIX + "Cannot publish compatibility diagnostics: " + error);
        }
    }
}
