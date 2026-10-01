package com.oxohang.fanfreeform.xposed;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.oxohang.fanfreeform.config.ConfigContract;

import java.util.List;

/**
 * 任务中心承载层：按设置的任务布局档位选择视图，并统一管理窗口生命周期。
 *
 * <p><b>所有档位（含第三档卡片列表）都由本类以 overlay 承载</b>（ty=2038）。
 * 这是刻意选择的行为范式，原型是系统「传送门」面板：
 * <ul>
 *   <li>窗口"浮"在当前应用之上，**不改变当前界面身份**（不像 Activity 那样独占、
 *       把下面的应用压掉，用户看到的就是"单独一个界面"）；</li>
 *   <li>带 {@code FLAG_WATCH_OUTSIDE_TOUCH} 收 {@code ACTION_OUTSIDE}，点外部即关
 *       —— 与传送门同款机制，且这是公开 API，无需特权权限；</li>
 *   <li>关闭是 {@code removeViewImmediate}，**瞬时干脆**，不走系统 task 过渡
 *       （系统过渡又慢又卡，实测是上划卡顿的根源）；</li>
 *   <li>"该被系统收起"的联动（下拉通知栏/控制中心/上划/开小窗）由 FanRuntime
 *       订阅系统信号后调 {@link #dismiss()} / {@link #removeNow()}，
 *       关闭时机由我们自己掌握。</li>
 * </ul>
 *
 * <p>历史注记：第三档曾一度改为模块进程的 {@code TaskCenterListActivity}
 * （普通应用窗口，交给系统生命周期收起）。实测该方案有两个不可接受的缺陷：
 * ① 呼出时屏幕会切成一个"独立的列表界面"（应用窗口独占），不是浮层观感；
 * ② 上划时走系统 task 过渡，明显卡顿。故改回 overlay。
 * Activity 相关类保留未删，但不再参与第三档。
 */
final class TaskSwitcherOverlayController {
    /** 任务中心视差卡片对控制器的统一表面。 */
    interface Surface {
        void playEntry();
        void onExternalMove(float x, float y);
        void onExternalUp(float x, float y, boolean cancelled);
        void onExternalCancel();
        void playDismissal();
        void releaseResources();
        Handler getHandler();
    }

    interface Listener {
        void onLaunch(RecentTaskPreview task);
        void onCloseTask(RecentTaskPreview task);
        void onCloseAll();
        void onListAction(int action, RecentTaskPreview task,
                          RecentTaskRepository.ShortcutEntry shortcut);
        void onClosed();
    }

    private final Context context;
    private final Handler mainHandler;
    private final WindowManager windowManager;
    private final Object moveLock = new Object();
    private Surface view;
    private boolean attached;
    private Surface pendingMoveView;
    private float pendingMoveX;
    private float pendingMoveY;
    private boolean moveFrameScheduled;
    private final Runnable deliverPendingMove = () -> {
        Surface target;
        float x;
        float y;
        synchronized (moveLock) {
            target = pendingMoveView;
            x = pendingMoveX;
            y = pendingMoveY;
            pendingMoveView = null;
            moveFrameScheduled = false;
        }
        if (attached && target != null && view == target) target.onExternalMove(x, y);
    };

    TaskSwitcherOverlayController(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler;
        windowManager = context.getSystemService(WindowManager.class);
    }

    boolean show(List<RecentTaskPreview> tasks, GestureGeometry.Corner corner,
                 float anchorX, float anchorY,
                 GestureConfig config, Listener listener) {
        removeNow();
        if (windowManager == null || tasks == null || tasks.isEmpty()) return false;
        // 第三档（三星同款卡片列表）用独立的列表表面；其余档位用视差卡片。
        Surface next = config.sideTaskLayoutMode == ConfigContract.SIDE_TASK_LAYOUT_SAMSUNG_LIST
                ? createListSurface(tasks, config, listener)
                : createCardSurface(tasks, corner, anchorX, anchorY, config, listener);
        Log.i("Task switcher surface layout=" + config.sideTaskLayoutMode
                + " tasks=" + tasks.size()
                + " kind=" + (config.sideTaskLayoutMode
                        == ConfigContract.SIDE_TASK_LAYOUT_SAMSUNG_LIST ? "list" : "card"));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                windowManager.getCurrentWindowMetrics().getBounds().height(),
                2038,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setFitInsetsTypes(0);
        params.setTitle("HyperGestureTaskSwitcher");
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            windowManager.addView((View) next, params);
            view = next;
            attached = true;
            next.playEntry();
            return true;
        } catch (Throwable error) {
            Log.e("Cannot attach task switcher", error);
            next.releaseResources();
            view = null;
            attached = false;
            return false;
        }
    }

    private Surface createCardSurface(List<RecentTaskPreview> tasks,
                                      GestureGeometry.Corner corner,
                                      float anchorX, float anchorY,
                                      GestureConfig config, Listener listener) {
        TaskSwitcherOverlayView card = new TaskSwitcherOverlayView(context);
        card.configure(tasks, corner, anchorX, anchorY, config,
                new TaskSwitcherOverlayView.Listener() {
                    @Override public void onLaunch(RecentTaskPreview task) {
                        removeNow();
                        listener.onLaunch(task);
                    }

                    @Override public void onClosed() {
                        removeNow();
                        listener.onClosed();
                    }
                });
        return card;
    }

    /**
     * 第三档：三星同款卡片列表（{@link TaskCenterListView}）。
     *
     * <p>该视图已实现 {@link Surface}，与卡片共用同一套窗口生命周期与关闭通路：
     * 点外部 / 面板展开 / 上划 / 开小窗统一收敛到 {@link #dismiss()} 或
     * {@link #removeNow()}，视图自身只负责界面与点击分派。
     */
    private Surface createListSurface(List<RecentTaskPreview> tasks,
                                      GestureConfig config, Listener listener) {
        TaskCenterListView list = new TaskCenterListView(context);
        list.configure(tasks, config, new TaskCenterListView.Listener() {
            @Override public void onLaunch(RecentTaskPreview task) {
                removeNow();
                listener.onLaunch(task);
            }

            @Override public void onCloseTask(RecentTaskPreview task) {
                listener.onCloseTask(task);
            }

            @Override public void onCloseAll() {
                listener.onCloseAll();
            }

            @Override public void onListAction(int action, RecentTaskPreview task,
                                               RecentTaskRepository.ShortcutEntry shortcut) {
                // 动作类条目（分屏/小窗/详情/快捷方式/搜索）都是"面板的终局"：
                // 先撤窗口再派发，避免动作执行期间列表仍浮在上面。
                removeNow();
                listener.onListAction(action, task, shortcut);
            }

            @Override public void onClosed() {
                removeNow();
                listener.onClosed();
            }
        });
        return list;
    }

    boolean isVisible() { return attached && view != null; }

    void externalMove(float x, float y) {
        Surface current = view;
        if (!attached || current == null) return;
        synchronized (moveLock) {
            pendingMoveView = current;
            pendingMoveX = x;
            pendingMoveY = y;
            if (moveFrameScheduled) return;
            moveFrameScheduled = true;
        }
        ((View) current).postOnAnimation(deliverPendingMove);
    }

    void externalUp(float x, float y, boolean cancelled) {
        Surface current = view;
        cancelPendingMove(current);
        runOnViewThread(current, () -> current.onExternalUp(x, y, cancelled));
    }

    void externalCancel() {
        Surface current = view;
        cancelPendingMove(current);
        runOnViewThread(current, current::onExternalCancel);
    }

    void dismiss() {
        Surface current = view;
        runOnViewThread(current, current::playDismissal);
    }

    /** 供诊断用：窗口当前是否挂在 WindowManager 上。 */
    boolean isAttached() {
        return attached && view != null;
    }

    void removeNow() {
        Surface current = view;
        cancelPendingMove(current);
        view = null;
        if (!attached || current == null || windowManager == null) {
            attached = false;
            return;
        }
        attached = false;
        mainHandler.removeCallbacksAndMessages(current);
        Runnable removal = () -> {
            try {
                windowManager.removeViewImmediate((View) current);
            } catch (Throwable error) {
                Log.e("Cannot remove task switcher", error);
            } finally {
                current.releaseResources();
            }
        };
        Handler owner = current.getHandler();
        if (owner != null && owner.getLooper() != Looper.myLooper()) owner.post(removal);
        else removal.run();
    }

    private void runOnViewThread(Surface current, Runnable action) {
        if (!attached || current == null) return;
        Handler owner = current.getHandler();
        if (owner != null && owner.getLooper() != Looper.myLooper()) {
            owner.post(() -> {
                if (attached && view == current) action.run();
            });
        } else if (attached && view == current) action.run();
    }

    private void cancelPendingMove(Surface current) {
        if (current != null) current.getHandler().removeCallbacks(deliverPendingMove);
        synchronized (moveLock) {
            if (pendingMoveView == current) pendingMoveView = null;
            moveFrameScheduled = false;
        }
    }
}
