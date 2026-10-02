package com.oxohang.fanfreeform.xposed;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.oxohang.fanfreeform.config.ConfigContract;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务中心 · 卡片列表宿主（第三档）。
 *
 * <p><b>刻意使用普通 Activity（应用窗口 ty=1 BASE_APPLICATION）而不是 overlay。</b>
 * 系统窗口层实测结论：本机所有 overlay（ty=2038/2032）都被标记为不可聚焦，
 * 系统唯一的可聚焦临时全屏界面是系统进程持有的 ty=2040 NotificationShade ——
 * 也就是说 overlay 在任何 ROM 上都改不出"可被系统当当前界面收起"的身份。
 * 只有应用窗口才会被系统按"当前界面"管理：返回键、上划回桌面、
 * 下拉状态栏覆盖 —— 全部由系统原生生命周期处理。本类**不写任何关闭逻辑**。
 *
 * <p>列表数据由本进程从模块自己的 ContentProvider 取一份快照：模块进程与 SystemUI
 * 进程相互独立，且**模块进程没有读取任务列表的权限**（只有 SystemUI 进程持有），
 * 因此数据是 SystemUI 进程读好后写进 Provider 的。图标由本进程按包名重新获取，
 * 保证与当前系统状态一致，也避免跨进程传 Bitmap。
 *
 * <p>由于本 Activity 所在进程与其运行时（{@code FanRuntime}）所在进程不同，
 * 窗口出现后需要向对侧发一条 {@link TaskCenterActionContract#ACTION_STARTED}：
 * 对侧据此收起重叠在列表之上的系统窗口捕获层。这是"列表即将上屏"唯一可靠的信号，
 * 既不会误判成"列表已经打开"，也不存在"列表还没起来就收起捕获层"的竞态。
 */
public final class TaskCenterListActivity extends Activity {
    private RecentTaskRepository repository;
    private List<RecentTaskPreview> previews;
    private TaskCenterListView listView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 系统把本窗口当"当前界面"管理，必须能承载返回键（onKeyDown 做分层关闭）。
        // 这里刻意不加 FLAG_NOT_FOCUSABLE、不加 FLAG_LAYOUT_NO_LIMITS：
        // 前者会让系统失去收起的落点，后者会让窗口飘出系统布局约束之外。
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = 0.45f;
        attrs.gravity = Gravity.CENTER;
        window.setAttributes(attrs);
        window.setWindowAnimations(0);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 面板之外的空白是一块"点一下退出"的区域：这不是手势识别，
        // 而是应用窗口自身最普通的点击处理，与系统设置界面点空白返回同性质。
        root.setOnClickListener(v -> finish());
        setContentView(root);

        int maxCount = getIntent() != null
                ? getIntent().getIntExtra(TaskCenterActionContract.EXTRA_MAX_COUNT, 6) : 6;

        repository = new RecentTaskRepository(this);
        previews = loadTaskSnapshot(maxCount);
        if (previews.isEmpty()) {
            Log.i("Task list activity skipped: no running tasks in snapshot");
            finish();
            return;
        }
        listView = new TaskCenterListView(this);
        // 本 Activity 已废弃（不再参与任何档位），锚点给默认值即可。
        listView.configure(previews, GestureGeometry.Corner.LEFT, 0f, 0f,
                GestureConfig.defaults(),
                new TaskCenterListView.Listener() {
                    @Override public void onLaunch(RecentTaskPreview task) {
                        repository.launch(task);
                        finish();
                    }

                    @Override public void onCloseTask(RecentTaskPreview task) {
                        repository.removeTask(task.taskId);
                    }

                    @Override public void onCloseAll() {
                        repository.removeAllTasks(previews);
                    }

                    @Override public void onListAction(int action, RecentTaskPreview task,
                                                       RecentTaskRepository.ShortcutEntry shortcut) {
                        handleListAction(action, task, shortcut);
                    }

                    @Override public void onToggleLock(RecentTaskPreview task, boolean shouldLock) {
                        // 本 Activity 属废弃承载路径（不参与任何档位），锁定只在
                        // overlay 承载的 {@link TaskCenterListView} 上开放 —— 那里由
                        // FanRuntime 注入 LockedAppStore。此处显式不做，避免出现
                        // "两条路径行为不一致"的隐性分歧。
                        Log.i("Lock toggle unsupported on activity host pkg="
                                + (task == null ? "null" : task.packageName));
                    }

                    @Override public void onClosed() {
                        finish();
                    }
                });
        root.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        listView.playEntry();
        if (TaskCenterActionContract.ACTION_SHOW.equals(intentAction())) {
            Log.i("Task list activity ready tasks=" + previews.size());
        }
    }

    @Override protected void onStart() {
        super.onStart();
        // 窗口已被系统真正显示：通知 SystemUI 进程收起捕获层。
        TaskCenterIpc.sendToPackage(this,
                new Intent(TaskCenterActionContract.ACTION_STARTED),
                TaskCenterActionContract.SYSTEM_UI_PACKAGE);
    }

    private String intentAction() {
        Intent intent = getIntent();
        return intent == null ? null : intent.getAction();
    }

    /**
     * 取 SystemUI 进程发布的任务列表快照。
     *
     * <p>本进程（模块进程）没有 {@code GET_TASKS} 能力，直接查 ActivityManager
     * 只会得到空列表，所以数据必须由持有权限的一方提供。快照是"一次性"的：
     * Provider 取走后即清，且有最大年龄限制，不会显示已经关掉的任务。
     */
    private List<RecentTaskPreview> loadTaskSnapshot(int maxCount) {
        ArrayList<RecentTaskPreview> result = new ArrayList<>();
        try {
            Bundle snapshot = getContentResolver().call(ConfigContract.URI,
                    ConfigContract.METHOD_GET_TASK_LIST, null, null);
            if (snapshot == null) return result;
            int[] taskIds = snapshot.getIntArray(ConfigContract.EXTRA_TASK_IDS);
            ArrayList<String> packages = snapshot.getStringArrayList(
                    ConfigContract.EXTRA_TASK_PACKAGES);
            ArrayList<String> labels = snapshot.getStringArrayList(
                    ConfigContract.EXTRA_TASK_LABELS);
            if (taskIds == null || packages == null) return result;
            int count = Math.min(taskIds.length, packages.size());
            if (labels == null) labels = new ArrayList<>();
            for (int index = 0; index < count && result.size() < maxCount; index++) {
                String packageName = packages.get(index);
                if (packageName == null || packageName.isEmpty()) continue;
                CharSequence label = index < labels.size() && labels.get(index) != null
                        ? labels.get(index) : packageName;
                result.add(new RecentTaskPreview(taskIds[index], packageName, label,
                        null, loadAppIcon(packageName)));
            }
        } catch (Throwable error) {
            Log.e("Cannot read task list snapshot", error);
        }
        Log.i("Task list snapshot consumed count=" + result.size());
        return result;
    }

    /** 应用图标由本进程按包名现取：跨进程传 Bitmap 代价高，且状态可能与当前不一致。 */
    private android.graphics.drawable.Drawable loadAppIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (Throwable error) {
            Log.i("App icon unavailable package=" + packageName);
            return null;
        }
    }

    private void handleListAction(int action, RecentTaskPreview task,
                                  RecentTaskRepository.ShortcutEntry shortcut) {
        switch (action) {
            case TaskCenterListView.ACTION_FREEFORM:
                launchTaskInFreeform(task);
                finish();
                break;
            case TaskCenterListView.ACTION_SPLIT:
                launchTaskInSplit(task);
                finish();
                break;
            case TaskCenterListView.ACTION_DETAILS:
                if (task != null) repository.openAppDetails(task.packageName);
                finish();
                break;
            case TaskCenterListView.ACTION_SHORTCUT:
                repository.launchShortcut(shortcut);
                finish();
                break;
            case TaskCenterListView.ACTION_SEARCH:
                repository.openHomeSearch();
                finish();
                break;
            case TaskCenterListView.ACTION_LOCK:
                // OS1 未找到"锁定任务"的原生入口，此处不伪造实现。
                Log.i("List action pending native entry on HyperOS OS1: lock");
                break;
            default:
                break;
        }
    }

    /** 弹出视图（小窗）：只把"哪个包"回传；具体启动由持有原生链路的 SystemUI 进程做。 */
    private void launchTaskInFreeform(RecentTaskPreview task) {
        if (task == null || task.packageName == null) return;
        Intent request = new Intent(TaskCenterActionContract.ACTION_FREEFORM);
        request.putExtra(TaskCenterActionContract.EXTRA_PACKAGE, task.packageName);
        TaskCenterIpc.sendToPackage(this, request,
                TaskCenterActionContract.SYSTEM_UI_PACKAGE);
        Log.i("Freeform request broadcast package=" + task.packageName);
    }

    /** 分屏：只把 taskId 回传；SoScSplitScreenController 只存在于 SystemUI 进程。 */
    private void launchTaskInSplit(RecentTaskPreview task) {
        if (task == null) return;
        Intent request = new Intent(TaskCenterActionContract.ACTION_SPLIT);
        request.putExtra(TaskCenterActionContract.EXTRA_TASK_ID, task.taskId);
        TaskCenterIpc.sendToPackage(this, request,
                TaskCenterActionContract.SYSTEM_UI_PACKAGE);
        Log.i("Split request broadcast task=" + task.taskId);
    }

    @Override protected void onDestroy() {
        if (repository != null && previews != null) repository.release(previews);
        super.onDestroy();
    }
}
