package com.oxohang.fanfreeform.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 应用锁定状态 —— 直读系统权威源头，不自建状态。
 *
 * <p><b>原生接口（设备实测，2026-10-02）</b>：MIUI 的"任务锁定"是<b>应用级锁定</b>，
 * 权威状态存在 {@link Settings.System} 的 {@code locked_apps} 键（String，JSON 数组），
 * 由 system_server 内的 {@code ProcessManagerService}/{@code ProcessPolicy} 维护
 * （落盘 {@code /data/system/users/0/settings_system.xml}）。整条链：
 *
 * <pre>
 * Settings.System("locked_apps")                              ← 本类直读这一层
 *   ↑ saveLockedAppIntoSettings / loadLockedAppFromSettings
 * ProcessPolicy.sLockedApplicationList (HashMap)              ← system_server 内存态
 *   ↑
 * ProcessManagerService.getLockedApplication(int)             ← miui-services.jar
 *   ↑ IProcessManager（Binder，服务名 "ProcessManager"）
 * miui.process.ProcessManager.isLockedApplication(String,int) ← miui-framework.jar
 *   ↑
 * Task.isLocked / TaskView.onLockedChanged                    ← launcher 缓存副本（UI 读这层）
 * </pre>
 *
 * <p><b>为什么不走反射 / hook</b>：直读 Settings 一次拿全量、格式稳定、零反射零 hook、
 * 不依赖桌面版本；反射 {@code miui.process.ProcessManager} 只能查单包且要加载 system_ext 的类，
 * hook launcher 的 {@code Task.isLocked} 则强耦合桌面版本。降级关系明确：本层是首选，
 * 反射是次选，hook 不用。
 *
 * <p><b>数据格式</b>：{@code [{"u":0,"pkgs":["pkgA","pkgB"]},{"u":-100,"pkgs":["default.pkg"]}]}
 * <ul>
 *   <li>{@code u} = userId，{@code 0} 为当前用户</li>
 *   <li>{@code u = -100} 是<b>系统预置默认锁定项</b>（{@code defaultLockedApps}），
 *       不是用户手动锁的 —— 本类按调用方传入的 userId 过滤，默认只认当前用户，
 *       预置项单独用 {@link #DEFAULT_USER_ID} 判断</li>
 * </ul>
 *
 * <p><b>写入</b>：模组侧通过 {@link #setLocked} 直接写回同一个键。系统侧的
 * {@code ProcessPolicy.registerContentObserver()} 会监听到并同步它自己的内存态与进程 adj，
 * 所以这里不需要通知任何服务 —— 写 Settings 就是唯一的、也是系统认的入口。
 */
final class LockedAppStore {

    /** {@code locked_apps} 里的系统预置用户标识（预置默认锁定项）。 */
    static final int DEFAULT_USER_ID = -100;

    private static final String KEY_LOCKED_APPS = "locked_apps";
    private static final String FIELD_USER = "u";
    private static final String FIELD_PACKAGES = "pkgs";

    private final Context context;
    private final ContentResolver resolver;
    private final int userId;
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private volatile Set<String> locked = Collections.emptySet();
    private ContentObserver observer;
    private Runnable onChanged;

    LockedAppStore(Context context, int userId) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.userId = userId;
    }

    /**
     * 取当前用户 id。
     *
     * <p>{@code Context.getUserId()} 与 {@code UserHandle.myUserId()} 在编译 SDK 上
     * 都是 {@code @hide}：编译期不可见，运行期存在。这里反射取，失败兜底主用户 0
     * —— 模块只在主用户下运行，兜底值与真实值一致，不会读到别的用户的分组。
     */
    static int currentUserId() {
        try {
            Class<?> handle = Class.forName("android.os.UserHandle");
            Object result = handle.getMethod("myUserId").invoke(null);
            if (result instanceof Integer) return (Integer) result;
        } catch (Throwable ignored) {
            // 落到兜底
        }
        return 0;
    }

    /**
     * 读一次当前锁定列表。
     *
     * @return 当前用户下被锁定的包名集合；读不到或解析失败返回空集合（不是 null）。
     */
    Set<String> load() {
        Set<String> result = new HashSet<>();
        try {
            String raw = Settings.System.getString(resolver, KEY_LOCKED_APPS);
            if (raw == null || raw.isEmpty()) return result;
            JSONArray groups = new JSONArray(raw);
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null || group.optInt(FIELD_USER, Integer.MIN_VALUE) != userId) {
                    continue;
                }
                JSONArray packages = group.optJSONArray(FIELD_PACKAGES);
                if (packages == null) continue;
                for (int j = 0; j < packages.length(); j++) {
                    String name = packages.optString(j, null);
                    if (name != null && !name.isEmpty()) result.add(name);
                }
            }
        } catch (Throwable error) {
            // 解析失败按"无锁定"处理：锁定图标是增益信息，读不到不该让列表整体失效。
            Log.i("LockedAppStore load failed error=" + error.getClass().getSimpleName());
        }
        return result;
    }

    /** 上次 {@link #load()} 的结果（供 onDraw 每帧读取，不重复解析）。 */
    Set<String> snapshot() {
        return locked;
    }

    boolean isLocked(String packageName) {
        return packageName != null && locked.contains(packageName);
    }

    /** 刷新快照。返回是否发生变化（变了才需要重绘）。 */
    boolean refresh() {
        Set<String> latest = load();
        if (latest.equals(locked)) return false;
        locked = latest;
        Log.i("LockedAppStore refresh userId=" + userId + " count=" + latest.size()
                + " pkgs=" + latest);
        return true;
    }

    /**
     * 上锁 / 解锁。
     *
     * <p><b>首选原生接口</b>：{@link LockedAppBridge#setLocked} →
     * {@code miui.process.ProcessManager.updateApplicationLockedState(pkg, userId, locked)}。
     * 这是系统自己的入口（桌面/设置页走同一条），通知链完整 —— 写完 system_server 会
     * 反向通知 launcher 刷新 {@code Task.isLocked}，"模块里锁上、原生任务中心也看得到"。
     *
     * <p><b>降级</b>：原生接口不可用（类/方法不存在或调用抛错）时，退回直接写
     * {@code Settings.System("locked_apps")}。这条是纯数据旁路：能改状态、system_server
     * 内存态也会更新，但 <b>launcher 缓存不会刷新</b>。日志会区分走的是哪条。
     *
     * <p>写入成功后本地快照立即跟进（原生路径也一样），这样用户连点不会因为快照滞后
     * 而被 {@code !changed} 短路；真正的权威值随后由 {@code ContentObserver} 校正。
     *
     * @return 是否至少成功送达一条路径。
     */
    boolean setLocked(String packageName, boolean shouldLock) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Set<String> current = new HashSet<>(locked);
            boolean changed = shouldLock
                    ? current.add(packageName) : current.remove(packageName);
            if (!changed) return true;

            // 首选：系统自己的锁定入口，通知链完整。
            boolean native_ok = LockedAppBridge.setLocked(packageName, userId, shouldLock);
            boolean ok = native_ok;
            if (!native_ok) {
                // 降级：直接写 Settings（能改到权威源头，但不会通知 launcher 刷新缓存）。
                String raw = rewrite(current);
                ok = Settings.System.putString(resolver, KEY_LOCKED_APPS, raw);
                Log.i("LockedAppStore setLocked fallback-to-settings pkg=" + packageName
                        + " locked=" + shouldLock + " ok=" + ok);
            }
            if (ok) {
                locked = current;
                Log.i("LockedAppStore setLocked pkg=" + packageName
                        + " locked=" + shouldLock + " total=" + current.size()
                        + " via=" + (native_ok ? "ProcessManager" : "Settings"));
            } else {
                Log.i("LockedAppStore setLocked rejected pkg=" + packageName);
            }
            return ok;
        } catch (Throwable error) {
            Log.e("LockedAppStore setLocked failed pkg=" + packageName, error);
            return false;
        }
    }

    /**
     * 把"当前用户的锁定集合"合回整条 JSON：保留其他用户分组原样不动，
     * 只重写自己那一组。直接整串覆盖会把 {@code u=-100} 的预置项抹掉。
     */
    private String rewrite(Set<String> currentUser) throws Exception {
        JSONArray groups = new JSONArray();
        String raw = Settings.System.getString(resolver, KEY_LOCKED_APPS);
        boolean replaced = false;
        if (raw != null && !raw.isEmpty()) {
            JSONArray existing = new JSONArray(raw);
            for (int i = 0; i < existing.length(); i++) {
                JSONObject group = existing.optJSONObject(i);
                if (group == null) continue;
                if (group.optInt(FIELD_USER, Integer.MIN_VALUE) == userId) {
                    groups.put(groupFor(currentUser));
                    replaced = true;
                } else {
                    groups.put(group);
                }
            }
        }
        if (!replaced) groups.put(groupFor(currentUser));
        return groups.toString();
    }

    private JSONObject groupFor(Set<String> packages) throws Exception {
        JSONObject group = new JSONObject();
        group.put(FIELD_USER, userId);
        JSONArray array = new JSONArray();
        for (String name : packages) array.put(name);
        group.put(FIELD_PACKAGES, array);
        return group;
    }

    /**
     * 订阅变化：系统/桌面改锁定时，本模块的图标同步跟上。
     *
     * <p>用 {@link ContentObserver} 而不是轮询 —— 系统侧的 {@code ProcessPolicy}
     * 也是用同一套机制监听这个键的，跟着权威源头走同一条路。
     */
    void startObserving(Runnable listener) {
        stopObserving();
        onChanged = listener;
        observer = new ContentObserver(mainThread) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (released) return;
                if (refresh() && onChanged != null) onChanged.run();
            }
        };
        try {
            resolver.registerContentObserver(
                    Settings.System.getUriFor(KEY_LOCKED_APPS), false, observer);
            refresh();
        } catch (Throwable error) {
            Log.i("LockedAppStore observe failed error=" + error.getClass().getSimpleName());
        }
    }

    private volatile boolean released;

    void stopObserving() {
        if (observer != null) {
            try {
                resolver.unregisterContentObserver(observer);
            } catch (Throwable ignored) {
                // 已注销 / 进程正在退出：忽略。
            }
            observer = null;
        }
    }

    void release() {
        released = true;
        stopObserving();
        onChanged = null;
        locked = Collections.emptySet();
    }
}
