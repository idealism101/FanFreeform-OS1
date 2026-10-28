package com.oxohang.fanfreeform.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 设置界面的设计令牌（单一真源）。
 *
 * <p>页面里不再写字面色值：同一层语义只在这里定义一次。此前"页面底"这一个
 * 概念散着 #F4F5FA / #F7F8FC / #EEF0F8 / #EEF0F7 四个几乎一样的值，
 * "强调淡底"也同时存在 #EEEFFF 与 #F0F1FF —— 都是逐页手写留下的漂移。
 *
 * <p>令牌对外可见（public），因为运行时浮层（{@code xposed} 包，跑在 SystemUI
 * 进程里）也用同一套色：同一个 App 里出现两个蓝（#5A67F2 与 #3478f6）就是
 * 两侧各写一份的结果。工具方法仍留在包内 —— 它们依赖 Activity / 弹窗，
 * 只有设置界面用得到。
 */
public final class Ui {
    // 文字
    public static final int TEXT = Color.rgb(31, 34, 48);          // #1F2230 主文字
    public static final int MUTED = Color.rgb(112, 116, 134);      // #707286 次级，白底 4.73:1（过 AA）
    public static final int DISABLED = 0xff9da3b4;                 // 不可用态（AA 不适用于禁用元素）

    // 强调
    public static final int ACCENT = Color.rgb(90, 103, 242);      // #5A67F2
    public static final int ACCENT_SOFT = 0xffeef0ff;              // 强调淡底：选中行 / 提示条

    // 面与分隔
    public static final int SURFACE = Color.WHITE;                 // 卡片面
    public static final int BACKGROUND = 0xfff4f5fa;               // 页面底
    public static final int SEPARATOR = Color.rgb(235, 237, 244);  // #EBEDF4 分隔线
    public static final int PLACEHOLDER = 0xffeef0f5;              // 图标占位底

    // 预览画布（示意图，非真实界面）
    public static final int PREVIEW_SCREEN = 0xffe8eaf2;
    public static final int PREVIEW_EDGE = 0xffc8ccd9;

    // 状态
    public static final int SUCCESS = 0xff287d4d;
    public static final int WARNING = 0xffa16c20;
    public static final int DANGER = 0xffb14242;

    // 间距栅格（4dp 一档）：此前边距散着 8 / 10 / 12 / 14 / 18 / 24 / 26 七个值。
    // 说明：栅格只用在"外边距"这一类可机械判定的位置上；其余 dp 调用里
    // 同一个数值可能是图标尺寸、圆角或内边距，无法机械判定角色，不做盲改。
    public static final int GAP_XS = 8;
    public static final int GAP_S = 12;
    public static final int GAP_M = 16;
    public static final int GAP_L = 20;
    public static final int GAP_XL = 24;

    // 浮层深色面：扇形 / 蜂窝 / 滚轮这些 SystemUI 浮层用。与上面的浅色页面令牌是
    // 两套 —— 浮层飘在任意内容之上，必须自带深色底才压得住，不能用浅色令牌去套。
    // 此前"浮层底"这一个概念在 FanOverlayView / HoneycombOverlayView 里散着
    // #20263f 的 dd / f0 / c8 / b8 四个透明度，就是逐处手写留下的漂移。
    public static final int OVERLAY_PLATE = 0xdd20263f;         // 药丸 / 名称底（默认）
    public static final int OVERLAY_PLATE_ACTIVE = 0xf020263f;  // 选中 / 激活
    public static final int OVERLAY_PLATE_MUTED = 0xc820263f;   // 未激活
    public static final int OVERLAY_RAIL = 0xb820263f;          // 导轨：比药丸更透
    public static final int OVERLAY_PLATE_SOLID = 0xff17181d;   // 图标底板（不透明）
    public static final int OVERLAY_PLATE_LIGHT = 0xdde7e9f0;   // 亮底应用上的反色底
    public static final int OVERLAY_ICON_LIGHT = 0x99ffffff;    // 亮底应用上的图标底板
    public static final int OVERLAY_TEXT_ON_LIGHT = 0xff202126; // 亮底应用上的文字
    public static final int OVERLAY_ACCENT_FILL = 0x385a67f2;   // 选中填充：强调色 22%

    // 圆角：同一类组件只用一个值。此前"卡片"这一个角色混用了
    // 20 / 16 / 18 / 14 / 13 / 12 六种半径，逐页手写留下的漂移。
    public static final float RADIUS_CARD = 20f;    // 卡片 / 设置行 / 按钮
    public static final float RADIUS_SHEET = 24f;   // 弹层 / 大块面板
    public static final float RADIUS_CHIP = 12f;    // 小标签 / 小控件

    /** 涟漪色：12% 黑，浅色面上是一层克制的灰。 */
    private static final int PRESS_TINT = 0x1f000000;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /**
     * 可按压的圆角底：在原色块之上叠一层平台自带的涟漪（{@link RippleDrawable}）。
     *
     * <p>为什么用系统原生涟漪而不是自己写"按压时换一个色"：涟漪从手指落点扩散，
     * 是"点哪、哪回应"的直接反馈；而且不可点击的容器拿不到 pressed 状态，
     * 不会出现误闪。mask 用同半径圆角，保证涟漪不会溢出卡片圆角。
     */
    static Drawable pressable(Context context, int color, float radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(PRESS_TINT),
                rounded(context, color, radiusDp),
                rounded(context, Color.WHITE, radiusDp));
    }

    static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(Ui.SEPARATOR);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return view;
    }

    static void addResetOption(Activity activity, LinearLayout parent,
                               String message, Runnable resetAction) {
        Button reset = new Button(activity);
        reset.setText("恢复本页默认设置");
        reset.setTextColor(MUTED);
        reset.setAllCaps(false);
        reset.setBackground(pressable(activity, SURFACE, 18));
        reset.setOnClickListener(view -> new AlertDialog.Builder(activity)
                .setTitle("恢复默认设置？")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认恢复", (dialog, which) -> {
                    resetAction.run();
                    Toast.makeText(activity, "已恢复本页默认设置",
                            Toast.LENGTH_SHORT).show();
                    activity.recreate();
                })
                .show());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52));
        params.topMargin = dp(activity, GAP_L);
        parent.addView(reset, params);
    }

    static void addGlobalResetOption(Activity activity, LinearLayout parent,
                                     Runnable resetAction) {
        Button reset = new Button(activity);
        reset.setText("恢复全部默认设置");
        reset.setTextColor(MUTED);
        reset.setAllCaps(false);
        reset.setBackground(pressable(activity, SURFACE, 18));
        reset.setOnClickListener(view -> new AlertDialog.Builder(activity)
                .setTitle("恢复全部默认设置？")
                .setMessage("所有手势、动画、小窗位置和窗外点击参数将恢复默认值；已选应用和快捷方式会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认恢复", (dialog, which) -> {
                    resetAction.run();
                    Toast.makeText(activity, "已恢复全部默认设置",
                            Toast.LENGTH_SHORT).show();
                    activity.recreate();
                })
                .show());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52));
        params.topMargin = dp(activity, GAP_L);
        parent.addView(reset, params);
    }
}
