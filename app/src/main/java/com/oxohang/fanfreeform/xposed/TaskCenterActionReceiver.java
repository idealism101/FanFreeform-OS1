package com.oxohang.fanfreeform.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * SystemUI 进程内模块运行时的收件端：接收列表 Activity 发来的用户动作。
 *
 * <p>注册模式与 {@link FanFreeformHook} 中既有的外部启动桥完全一致：
 * {@code Context.RECEIVER_EXPORTED} + {@link BroadcastSenderValidator} 验来源。
 *
 * <p><b>只做动作分派，不做关闭。</b>列表窗口的身份是普通应用窗口，关闭由系统原生
 * 生命周期处理；本类收到的只是"用户点了哪个动作"。
 */
final class TaskCenterActionReceiver {
    private static volatile boolean installed;

    private TaskCenterActionReceiver() { }

    static synchronized void install(Context context) {
        if (installed || context == null) return;
        try {
            IntentFilter filter = new IntentFilter(TaskCenterActionContract.ACTION_SPLIT);
            filter.addAction(TaskCenterActionContract.ACTION_FREEFORM);
            filter.addAction(TaskCenterActionContract.ACTION_STARTED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context receiverContext, Intent intent) {
                    if (!BroadcastSenderValidator.isFromPackage(receiverContext, this,
                            TaskCenterActionContract.SENDER_PACKAGE)) {
                        Log.i("Ignored task center request from unauthorized sender");
                        return;
                    }
                    String action = intent.getAction();
                    FanRuntime active = FanFreeformHook.runtime();
                    if (active == null) return;
                    if (TaskCenterActionContract.ACTION_STARTED.equals(action)) {
                        active.onTaskListStarted();
                    } else if (TaskCenterActionContract.ACTION_SPLIT.equals(action)) {
                        active.onTaskListSplitRequest(
                                intent.getIntExtra(TaskCenterActionContract.EXTRA_TASK_ID, -1));
                    } else if (TaskCenterActionContract.ACTION_FREEFORM.equals(action)) {
                        active.onTaskListFreeformRequest(intent.getStringExtra(
                                TaskCenterActionContract.EXTRA_PACKAGE));
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
            installed = true;
            Log.i("Task center action receiver registered");
        } catch (Throwable error) {
            Log.e("Task center action receiver registration failed", error);
        }
    }
}
