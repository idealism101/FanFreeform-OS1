package com.oxohang.fanfreeform.xposed;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/**
 * 任务中心承载层：按设置的任务布局档位选择视图，并统一管理窗口生命周期。
 *
 * <p>第三档（三星同款卡片列表）**不在这里**：列表必须是普通应用窗口才能被系统
 * 当"当前界面"收起，而本类是 overlay 承载层（ty=2038，系统窗口层实测不可聚焦），
 * 因此卡片列表交给模块进程的 {@code TaskCenterListActivity}。
 * 本类只承载视差卡片（第一/第二档）。
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
        Surface next = createCardSurface(tasks, corner, anchorX, anchorY, config, listener);
        Log.i("Task switcher surface layout=" + config.sideTaskLayoutMode
                + " tasks=" + tasks.size() + " kind=card");
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
