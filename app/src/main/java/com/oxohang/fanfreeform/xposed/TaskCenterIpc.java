package com.oxohang.fanfreeform.xposed;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import com.oxohang.fanfreeform.config.ConfigContract;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务中心列表跨进程消息的发送端与接收端注册。
 *
 * <p>两个进程（模块进程 / SystemUI 进程）都要用同一套"显式指定收件包 + 携带发送方
 * 身份"的发送手法，因此收在一处，避免两侧各写一遍出现不对称。
 *
 * <p>发送方身份用 {@link BroadcastOptions#setShareIdentityEnabled(boolean)} 共享
 * （与 {@link ShortcutHostRuntime#sendSystemUiRequest} 同一机制）：接收端
 * {@link BroadcastSenderValidator} 需要 {@code getSentFromPackage()} 才能验出来源，
 * 否则匿名广播会被直接判为非授权来源而丢弃。
 *
 * <p>另有一类消息不能走广播：<b>任务列表数据本身</b>。承载列表窗口的 Activity
 * 必须运行在模块进程（只有应用窗口才会被系统当"当前界面"管理），但任务列表
 * 只有 SystemUI 进程读得到（它持有 GET_TASKS 能力，模块进程连 CAP 位都是 0）。
 * 因此数据走模块自己的 {@link ConfigContract} ContentProvider：
 * SystemUI 进程写、模块进程的 Activity 取，与既有快捷方式图标交接同一机制。
 */
final class TaskCenterIpc {
    private TaskCenterIpc() { }

    /** 把请求发给指定包的进程，并共享发送方身份供对方校验。 */
    static void sendToPackage(Context context, Intent request, String targetPackage) {
        if (context == null || request == null || targetPackage == null) return;
        request.setPackage(targetPackage);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Bundle options = BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true).toBundle();
                context.sendBroadcast(request, null, options);
            } else {
                context.sendBroadcast(request);
            }
        } catch (Throwable error) {
            Log.e("Cannot send task center request action=" + request.getAction(), error);
        }
    }

    /**
     * 把本进程读到的任务列表发布到模块的 ContentProvider，供列表 Activity 取用。
     *
     * <p>只传 taskId / 包名 / 应用名三项纯数据。原因有二：
     * <ul>
     *   <li>图标与快照是 {@code Bitmap}/{@code Drawable}，可以跨进程但代价高，
     *       且列表 Activity 自己就能按包名取应用图标（不需要额外权限）。</li>
     *   <li>列表窗口必须自己重新读一遍应用图标，才能保证与当前系统状态一致。</li>
     * </ul>
     */
    static void publishTaskList(Context context, List<RecentTaskPreview> previews) {
        if (context == null || previews == null) return;
        try {
            int[] taskIds = new int[previews.size()];
            ArrayList<String> packages = new ArrayList<>(previews.size());
            ArrayList<String> labels = new ArrayList<>(previews.size());
            for (int index = 0; index < previews.size(); index++) {
                RecentTaskPreview preview = previews.get(index);
                taskIds[index] = preview.taskId;
                packages.add(preview.packageName);
                labels.add(preview.label == null ? null : preview.label.toString());
            }
            Bundle extras = new Bundle();
            extras.putIntArray(ConfigContract.EXTRA_TASK_IDS, taskIds);
            extras.putStringArrayList(ConfigContract.EXTRA_TASK_PACKAGES, packages);
            extras.putStringArrayList(ConfigContract.EXTRA_TASK_LABELS, labels);
            context.getContentResolver().call(ConfigContract.URI,
                    ConfigContract.METHOD_REPORT_TASK_LIST, null, extras);
            Log.i("Task list snapshot published tasks=" + previews.size());
        } catch (Throwable error) {
            Log.e("Cannot publish task list snapshot", error);
        }
    }
}
