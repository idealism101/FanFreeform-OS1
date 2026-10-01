package com.oxohang.fanfreeform.xposed;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * 全屏启动器。
 *
 * <p>反射一律用标准库 {@link java.lang.reflect} 而不是 {@code XposedHelpers}：
 * 本类既在 SystemUI 进程（Xposed 已注入）被调用，也在模块自身进程
 * （任务中心列表 Activity 的宿主，**没有 Xposed 运行时**）被调用 ——
 * 后者类加载器里没有 {@code de.robv.android.xposed.*}，引用即崩。
 */
final class FullscreenAppLauncher {
    private final Context context;

    FullscreenAppLauncher(Context context) {
        this.context = context;
    }

    boolean launch(RuntimeTarget target) {
        return launch(target, false);
    }

    boolean launch(RuntimeTarget target, boolean centeredSystemAnimation) {
        if (target == null) return false;
        try {
            ActivityOptions options = centeredSystemAnimation
                    ? centeredScaleOptions(context) : ActivityOptions.makeBasic();
            try {
                setOptionFlag(options, "setLaunchWindowingMode", 1);
            } catch (Throwable ignored) { }
            try {
                Object display = context.getDisplay();
                if (display != null) options.setLaunchDisplayId(context.getDisplay().getDisplayId());
            } catch (Throwable ignored) { }
            Bundle bundle = options.toBundle();
            if (target.isShortcut()) {
                Intent request = new Intent(target.isLauncherShortcut()
                        ? ShortcutHostRuntime.ACTION_START_LAUNCHER_SHORTCUT
                        : ShortcutHostRuntime.ACTION_START_SHORTCUT)
                        .setPackage(ShortcutHostRuntime.MIUI_HOME)
                        .putExtra(ShortcutHostRuntime.EXTRA_PACKAGE, target.packageName)
                        .putExtra(ShortcutHostRuntime.EXTRA_SHORTCUT_ID, target.shortcutId)
                        .putExtra(ShortcutHostRuntime.EXTRA_OPTIONS, bundle);
                if (target.isLauncherShortcut()) {
                    request.putExtra(ShortcutHostRuntime.EXTRA_INTENT_URI,
                            target.shortcutIntentUri);
                }
                ShortcutHostRuntime.sendSystemUiRequest(context, request);
            } else if (target.component == null) {
                return false;
            } else if (target.userId != 0) {
                Intent request = new Intent(ShortcutHostRuntime.ACTION_START_ACTIVITY)
                        .setPackage(ShortcutHostRuntime.MIUI_HOME)
                        .putExtra(ShortcutHostRuntime.EXTRA_COMPONENT,
                                target.component.flattenToString())
                        .putExtra(ShortcutHostRuntime.EXTRA_USER_ID, target.userId)
                        .putExtra(ShortcutHostRuntime.EXTRA_OPTIONS, bundle);
                ShortcutHostRuntime.sendSystemUiRequest(context, request);
            } else {
                Intent intent = Intent.makeMainActivity(target.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(intent, bundle);
            }
            Log.i("Launched fullscreen " + (target.isShortcut()
                    ? target.packageName + "/" + target.shortcutId
                    : target.component.flattenToShortString()
                    + (target.userId == 0 ? "" : " user=" + target.userId))
                    + " centeredAnimation=" + centeredSystemAnimation);
            return true;
        } catch (Throwable error) {
            Log.e("Cannot launch target fullscreen", error);
            Toast.makeText(context, "应用不可用", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    static ActivityOptions centeredScaleOptions(Context context) {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        Rect display = windowManager == null
                ? new Rect(0, 0, context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels)
                : new Rect(windowManager.getCurrentWindowMetrics().getBounds());
        View source = new View(context);
        source.layout(0, 0, display.width(), display.height());
        int startSize = Math.max(1, Math.round(56f
                * context.getResources().getDisplayMetrics().density));
        int startX = display.centerX() - startSize / 2;
        int startY = display.centerY() - startSize / 2;
        ActivityOptions options = ActivityOptions.makeScaleUpAnimation(
                source, startX, startY, startSize, startSize);
        forceOverrideTaskTransition(options, "centered fullscreen");
        return options;
    }

    // HyperOS normally replaces an app-supplied animation when an existing task is
    // brought to the front. This framework flag asks WindowManager to keep ours.
    private static void forceOverrideTaskTransition(ActivityOptions options, String label) {
        if (setOptionFlag(options, "setOverrideTaskTransition", true)) {
            Log.i("Using " + label + " task animation");
            return;
        }
        // Older HyperOS builds may only expose the backing field.
        try {
            java.lang.reflect.Field field =
                    ActivityOptions.class.getDeclaredField("mOverrideTaskTransition");
            field.setAccessible(true);
            field.setBoolean(options, true);
            Log.i("Using " + label + " task animation (field fallback)");
        } catch (Throwable ignored) {
            Log.i("Cannot force " + label + " task transition");
        }
    }

    /** 反射调用 ActivityOptions 上的隐藏 setter，返回是否成功。 */
    private static boolean setOptionFlag(ActivityOptions options, String method,
                                         boolean value) {
        try {
            Method target = ActivityOptions.class.getDeclaredMethod(method, boolean.class);
            target.setAccessible(true);
            target.invoke(options, value);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 反射调用 ActivityOptions 上的隐藏 setter，返回是否成功。 */
    private static boolean setOptionFlag(ActivityOptions options, String method, int value) {
        try {
            Method target = ActivityOptions.class.getDeclaredMethod(method, int.class);
            target.setAccessible(true);
            target.invoke(options, value);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }
}
