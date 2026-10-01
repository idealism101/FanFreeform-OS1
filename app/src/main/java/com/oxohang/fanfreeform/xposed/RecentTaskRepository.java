package com.oxohang.fanfreeform.xposed;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class RecentTaskRepository {
    private final Context context;
    private final ActivityManager activityManager;
    private final PackageManager packageManager;

    RecentTaskRepository(Context context) {
        this.context = context;
        activityManager = context.getSystemService(ActivityManager.class);
        packageManager = context.getPackageManager();
    }

    List<RecentTaskPreview> loadRunningTasks(int maximum, boolean includeSnapshots) {
        ArrayList<RecentTaskPreview> result = new ArrayList<>();
        if (activityManager == null) return result;
        int limit = Math.max(2, Math.min(10, maximum));
        try {
            List<ActivityManager.RecentTaskInfo> recents = activityManager.getRecentTasks(
                    30, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (ActivityManager.RecentTaskInfo task : recents) {
                if (result.size() >= limit) break;
                if (task == null || task.taskId < 0 || !task.isRunning
                        || task.numActivities <= 0) continue;
                ComponentName component = task.topActivity != null
                        ? task.topActivity : task.baseActivity;
                if (component == null && task.baseIntent != null) {
                    component = task.baseIntent.getComponent();
                }
                if (component == null) continue;
                String packageName = component.getPackageName();
                // 排除项用写死的模块包名而不是 context.getPackageName()：本类在
                // SystemUI 进程里被调用时，后者返回的是 com.android.systemui，
                // 会让「模块自身任务」漏进列表。
                if (packageName.equals(TaskCenterActionContract.HOST_PACKAGE)
                        || packageName.equals(TaskCenterActionContract.SYSTEM_UI_PACKAGE)
                        || packageName.equals("com.miui.home")) continue;
                // A single unavailable package must not abort the whole list.
                try {
                    ApplicationInfo app = packageManager.getApplicationInfo(packageName, 0);
                    CharSequence label = packageManager.getApplicationLabel(app);
                    Drawable icon = packageManager.getApplicationIcon(app);
                    Bitmap snapshot = includeSnapshots ? loadSnapshot(task.taskId) : null;
                    result.add(new RecentTaskPreview(task.taskId, packageName, label,
                            snapshot, icon));
                } catch (Throwable itemError) {
                    Log.i("Skipping unavailable recent task package=" + packageName
                            + " error=" + itemError.getClass().getSimpleName());
                }
            }
        } catch (Throwable error) {
            Log.e("Cannot load running task previews", error);
        }
        Log.i("Running task previews loaded count=" + result.size()
                + " snapshots=" + includeSnapshots);
        return result;
    }

    void release(List<RecentTaskPreview> previews) {
        if (previews == null) return;
        for (RecentTaskPreview preview : previews) {
            if (preview == null || preview.snapshot == null
                    || preview.snapshot.isRecycled()) continue;
            preview.snapshot.recycle();
        }
    }

    /**
     * 取当前前台任务 id。用于分屏：唤起列表的瞬间快照前台任务，
     * 分屏时把它填到另一侧 stage。必须在列表打开时调用，不能等启动瞬间
     * （那时 getTopFullScreenTask 返回的已是目标任务自身）。
     */
    int foregroundTaskId() {
        if (activityManager == null) return -1;
        try {
            List<ActivityManager.RecentTaskInfo> recents = activityManager.getRecentTasks(
                    5, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (ActivityManager.RecentTaskInfo task : recents) {
                if (task == null || task.taskId < 0 || !task.isRunning
                        || task.numActivities <= 0) continue;
                return task.taskId;
            }
        } catch (Throwable error) {
            Log.i("Cannot read foreground task id error="
                    + error.getClass().getSimpleName());
        }
        return -1;
    }

    /** 原生移除单个最近任务（SystemUI 进程持有 MANAGE_ACTIVITY_TASKS 权限）。 */
    boolean removeTask(int taskId) {
        try {
            Object result = invoke(activityTaskManagerService(), "removeTask", taskId);
            boolean ok = !(result instanceof Boolean) || (Boolean) result;
            if (ok) Log.i("Recent task removed id=" + taskId);
            else Log.i("Recent task remove reported failure id=" + taskId);
            return ok;
        } catch (Throwable error) {
            Log.e("Cannot remove recent task id=" + taskId, error);
            return false;
        }
    }

    int removeAllTasks(List<RecentTaskPreview> tasks) {
        int removed = 0;
        if (tasks == null) return 0;
        for (RecentTaskPreview task : tasks) {
            if (task != null && removeTask(task.taskId)) removed++;
        }
        Log.i("Close-all removed count=" + removed + "/" + tasks.size());
        return removed;
    }

    boolean openAppDetails(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            Log.e("Cannot open app details package=" + packageName, error);
            return false;
        }
    }

    /** 桌面搜索框：优先负一屏（HyperOS 全局搜索宿主）。 */
    boolean openHomeSearch() {
        try {
            Intent intent = packageManager.getLaunchIntentForPackage(
                    "com.miui.personalassistant");
            if (intent == null) {
                Log.i("Home search target unavailable package=com.miui.personalassistant");
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            Log.e("Cannot open home search", error);
            return false;
        }
    }

    /** 应用自己发布的桌面快捷方式（菜单第 5 项起，随应用不同）。 */
    static final class ShortcutEntry {
        final String packageName;
        final String id;
        final CharSequence label;
        final UserHandle user;

        ShortcutEntry(String packageName, String id, CharSequence label, UserHandle user) {
            this.packageName = packageName;
            this.id = id;
            this.label = label;
            this.user = user;
        }
    }

    List<ShortcutEntry> loadLauncherShortcuts(String packageName) {
        ArrayList<ShortcutEntry> result = new ArrayList<>();
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        if (launcherApps == null || packageName == null || packageName.isEmpty()) {
            return result;
        }
        try {
            UserHandle user = Process.myUserHandle();
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(packageName);
            query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
            List<ShortcutInfo> found = launcherApps.getShortcuts(query, user);
            if (found == null) return result;
            for (ShortcutInfo info : found) {
                if (info == null || info.getId() == null) continue;
                CharSequence label = info.getShortLabel() != null
                        ? info.getShortLabel() : info.getLongLabel();
                if (label == null) continue;
                result.add(new ShortcutEntry(packageName, info.getId(), label, user));
            }
            Log.i("Launcher shortcuts loaded package=" + packageName
                    + " count=" + result.size());
        } catch (Throwable error) {
            Log.i("Launcher shortcuts unavailable package=" + packageName
                    + " error=" + error.getClass().getSimpleName());
        }
        return result;
    }

    boolean launchShortcut(ShortcutEntry entry) {
        if (entry == null) return false;
        try {
            LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
            if (launcherApps == null) return false;
            launcherApps.startShortcut(entry.packageName, entry.id, null, null, entry.user);
            Log.i("Shortcut launched package=" + entry.packageName + " id=" + entry.id);
            return true;
        } catch (Throwable error) {
            Log.e("Cannot launch shortcut package=" + entry.packageName
                    + " id=" + entry.id, error);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    boolean launch(RecentTaskPreview task) {
        if (task == null) return false;
        Bundle centered = FullscreenAppLauncher.centeredScaleOptions(context).toBundle();
        if (launchViaRecents(task, centered)) return true;
        if (launchViaRecents(task, ActivityOptions.makeBasic().toBundle())) return true;
        try {
            activityManager.moveTaskToFront(task.taskId,
                    ActivityManager.MOVE_TASK_WITH_HOME);
            Log.i("Recent task moved to front id=" + task.taskId);
            return true;
        } catch (Throwable finalFallback) {
            Log.e("Cannot launch recent task id=" + task.taskId, finalFallback);
            return false;
        }
    }

    private boolean launchViaRecents(RecentTaskPreview task, Bundle options) {
        try {
            Object service = activityTaskManagerService();
            Object result = startFromRecents(service, task.taskId, options);
            if (startResultFailed(result)) {
                Log.i("Recent task start reported failure id=" + task.taskId
                        + " result=" + result);
                return false;
            }
            Log.i("Recent task launched id=" + task.taskId + " result=" + result);
            return true;
        } catch (Throwable error) {
            Log.i("Recent task start threw id=" + task.taskId
                    + " error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean startResultFailed(Object result) {
        return result instanceof Number && ((Number) result).intValue() < 0;
    }

    private static Object startFromRecents(Object service, int taskId, Bundle options) {
        return invoke(service, "startActivityFromRecents", taskId, options);
    }

    private Bitmap loadSnapshot(int taskId) {
        HardwareBuffer buffer = null;
        try {
            Object service = activityTaskManagerService();
            Object snapshot = invoke(service, "getTaskSnapshot", taskId, true);
            if (snapshot == null) {
                snapshot = invoke(service, "takeTaskSnapshot", taskId, true);
            }
            if (snapshot == null) return null;
            buffer = (HardwareBuffer) invoke(snapshot, "getHardwareBuffer");
            ColorSpace colorSpace = (ColorSpace) invoke(snapshot, "getColorSpace");
            if (buffer == null || buffer.isClosed()) return null;
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardware == null) return null;
            hardware.setHasMipMap(true);
            return hardware;
        } catch (Throwable error) {
            Log.e("Cannot load task snapshot id=" + taskId, error);
            return null;
        } finally {
            if (buffer != null && !buffer.isClosed()) buffer.close();
        }
    }

    /**
     * 反射调用系统服务的方法。
     *
     * <p>用标准库 {@link java.lang.reflect} 而不是 {@code XposedHelpers}：
     * 本类既在 SystemUI 进程（Xposed 已注入）被调用，也在模块自身进程
     * （任务中心列表 Activity 的宿主，**没有 Xposed 运行时**）被调用 ——
     * 后者的类加载器里根本没有 {@code de.robv.android.xposed.*}，
     * 一旦引用就会 {@code NoClassDefFoundError} 并把宿主 Activity 带崩。
     * 反射所需能力标准库完全覆盖，无需引入额外依赖。
     */
    private static Object invoke(Object target, String method, Object... args) {
        if (target == null) return null;
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            Object argument = args[index];
            if (argument instanceof Integer) types[index] = int.class;
            else if (argument instanceof Boolean) types[index] = boolean.class;
            else if (argument instanceof Long) types[index] = long.class;
            else if (argument instanceof Float) types[index] = float.class;
            else if (argument instanceof Double) types[index] = double.class;
            else types[index] = argument == null ? null : argument.getClass();
        }
        for (Class<?> owner = target.getClass(); owner != null;
                owner = owner.getSuperclass()) {
            try {
                Method found = owner.getDeclaredMethod(method, types);
                found.setAccessible(true);
                return found.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // 继续沿父类查找：系统内部类常用包级实现覆写父类公开方法。
            } catch (Throwable error) {
                Log.i("Reflection call failed method=" + method
                        + " error=" + error.getClass().getSimpleName());
                return null;
            }
        }
        Log.i("Reflection target method unavailable method=" + method);
        return null;
    }

    private static Object activityTaskManagerService() {
        try {
            Class<?> manager = Class.forName("android.app.ActivityTaskManager");
            Method getService = manager.getDeclaredMethod("getService");
            getService.setAccessible(true);
            return getService.invoke(null);
        } catch (Throwable error) {
            Log.i("ActivityTaskManager service unavailable error="
                    + error.getClass().getSimpleName());
            return null;
        }
    }
}
