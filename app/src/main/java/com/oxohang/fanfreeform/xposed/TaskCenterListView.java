package com.oxohang.fanfreeform.xposed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 卡片列表 · 三星同款：纵向白面板行列表（设置→任务中心→任务布局第三档）。
 *
 * <p>版式：面板 280dp、行距 46dp、分隔线只覆盖应用名称列、行首 ⋮ 点按展开菜单卡
 * （覆盖名称列区域）、底部「全部关闭」+ 搜索。
 *
 * <p>本视图是**纯界面**：不判断系统导航手势、不感知窗口是否被覆盖、不做任何
 * 轮询。窗口身份（无 LAYOUT_NO_LIMITS + WATCH_OUTSIDE_TOUCH）与"系统面板展开
 * 即销毁"的联动由上层 {@link TaskSwitcherOverlayController} 与 FanRuntime 负责。
 * 所有动作经 {@link Listener} 回调出去，由 FanRuntime 走系统原生接口。
 */
final class TaskCenterListView extends View
        implements TaskSwitcherOverlayController.Surface {
    interface Listener {
        void onLaunch(RecentTaskPreview task);
        void onCloseTask(RecentTaskPreview task);
        void onCloseAll();
        void onListAction(int action, RecentTaskPreview task,
                          RecentTaskRepository.ShortcutEntry shortcut);
        void onClosed();
    }

    static final int ACTION_LOCK = 0;
    static final int ACTION_SPLIT = 1;
    static final int ACTION_FREEFORM = 2;
    static final int ACTION_DETAILS = 3;
    static final int ACTION_SHORTCUT = 4;
    static final int ACTION_SEARCH = 5;

    private static final int COLOR_PANEL = 0xf2ffffff;
    private static final int COLOR_PANEL_STROKE = 0xffe3e7ec;
    private static final int COLOR_DIVIDER = 0xffe8ebee;
    private static final int COLOR_TEXT = 0xff2c3138;
    private static final int COLOR_MUTED = 0xff9aa2ab;
    private static final int COLOR_DOT = 0xffa5adb6;
    private static final int COLOR_ACCENT = 0xff3478f6;
    private static final int COLOR_SEARCH_BG = 0xfff0f2f5;
    private static final int COLOR_SHADOW = 0x24000000;

    private final float density;
    private final int touchSlop;
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint itemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint magnifierPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();

    private List<RecentTaskPreview> tasks = Collections.emptyList();
    private Listener listener;
    private boolean hapticEnabled;
    private boolean closing;
    private boolean released;
    private ValueAnimator transition;
    private float touchDownX;
    private float touchDownY;
    private int expandedRow = -1;
    private List<RecentTaskRepository.ShortcutEntry> menuShortcuts = Collections.emptyList();
    private String shortcutsRequestedFor;

    private float panelLeft;
    private float panelTop;
    private float panelWidth;
    private float panelHeight;
    private float rowPitch;
    private float firstRowOffset;
    private float dotX;
    private float iconCx;
    private float iconRadius;
    private float nameStart;
    private float nameEnd;
    private float crossX;
    private float lastDividerY;
    private float footerBaseline;
    private float searchCy;
    private float cardLeft;
    private float cardTop;
    private float cardRight;
    private float cardBottom;
    private float cardWidth;

    TaskCenterListView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusableInTouchMode(true);
        setClickable(true);
        panelPaint.setColor(COLOR_PANEL);
        panelStroke.setStyle(Paint.Style.STROKE);
        panelStroke.setStrokeWidth(dp(1));
        panelStroke.setColor(COLOR_PANEL_STROKE);
        shadowPaint.setColor(COLOR_SHADOW);
        dividerPaint.setStrokeWidth(dp(1.5f));
        dividerPaint.setColor(COLOR_DIVIDER);
        dotPaint.setColor(COLOR_DOT);
        namePaint.setColor(COLOR_TEXT);
        namePaint.setTextSize(dp(13));
        crossPaint.setColor(COLOR_MUTED);
        crossPaint.setTextSize(dp(16));
        crossPaint.setTextAlign(Paint.Align.CENTER);
        itemPaint.setColor(COLOR_TEXT);
        itemPaint.setTextSize(dp(15));
        footerPaint.setColor(COLOR_ACCENT);
        footerPaint.setTextSize(dp(13));
        footerPaint.setTextAlign(Paint.Align.CENTER);
        searchBgPaint.setColor(COLOR_SEARCH_BG);
        magnifierPaint.setStyle(Paint.Style.STROKE);
        magnifierPaint.setStrokeWidth(dp(1.6f));
        magnifierPaint.setStrokeCap(Paint.Cap.ROUND);
        magnifierPaint.setColor(COLOR_ACCENT);
    }

    void configure(List<RecentTaskPreview> tasks, GestureConfig config, Listener listener) {
        this.tasks = new ArrayList<>(tasks);
        this.listener = listener;
        this.hapticEnabled = config.haptic;
        this.expandedRow = -1;
        this.menuShortcuts = Collections.emptyList();
        this.shortcutsRequestedFor = null;
    }

    @Override protected void onSizeChanged(int width, int height, int oldW, int oldH) {
        super.onSizeChanged(width, height, oldW, oldH);
        computeMetrics();
        invalidate();
    }

    @Override protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        computeMetrics();
        invalidate();
    }

    private float dp(float value) {
        return value * density;
    }

    private int rowCount() {
        return tasks.size();
    }

    private float rowCy(int index) {
        return panelTop + firstRowOffset + index * rowPitch;
    }

    private void computeMetrics() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        panelWidth = Math.min(dp(280), Math.round(width * 0.78f));
        rowPitch = dp(46);
        firstRowOffset = dp(30);
        dotX = dp(24);
        iconCx = dp(54);
        iconRadius = dp(12);
        nameStart = dp(76);
        nameEnd = panelWidth - dp(52);
        crossX = panelWidth - dp(28);
        int rows = rowCount();
        float listHeight = dp(118) + (rows - 1) * rowPitch;
        float baseTop = (height - listHeight) / 2f;
        baseTop = Math.max(dp(28), Math.min(baseTop, height - listHeight - dp(28)));
        panelLeft = (width - panelWidth) / 2f;
        panelTop = baseTop;
        lastDividerY = panelTop + firstRowOffset + (rows - 1) * rowPitch + dp(21);
        footerBaseline = lastDividerY + dp(40);
        searchCy = lastDividerY + dp(35);
        panelHeight = listHeight;
        if (expandedRow < 0 || expandedRow >= rows) return;
        cardLeft = panelLeft + dp(54);
        cardRight = panelLeft + nameEnd;
        cardWidth = cardRight - cardLeft;
        int itemCount = menuItemCount();
        float cardHeight = dp(31) + (itemCount - 1) * dp(45) + dp(17);
        // 菜单浮盖在列表上方：以该应用行中心为锚，上下各延伸约一半。
        float anchorCy = rowCy(expandedRow);
        cardTop = anchorCy - cardHeight / 2f;
        cardBottom = cardTop + cardHeight;
        // 边界钳制：不超出屏幕安全区
        float topLimit = dp(20);
        float bottomLimit = height - dp(24);
        if (cardTop < topLimit) {
            cardTop = topLimit;
            cardBottom = cardTop + cardHeight;
        } else if (cardBottom > bottomLimit) {
            cardBottom = bottomLimit;
            cardTop = cardBottom - cardHeight;
        }
    }

    private int menuItemCount() {
        return 4 + menuShortcuts.size();
    }

    private List<MenuEntry> buildMenu() {
        List<MenuEntry> items = new ArrayList<>();
        items.add(new MenuEntry("锁定此应用程序", ACTION_LOCK, null));
        items.add(new MenuEntry("在分屏视图中打开", ACTION_SPLIT, null));
        items.add(new MenuEntry("在弹出视图中打开", ACTION_FREEFORM, null));
        items.add(new MenuEntry("应用程序信息", ACTION_DETAILS, null));
        for (RecentTaskRepository.ShortcutEntry shortcut : menuShortcuts) {
            items.add(new MenuEntry(shortcut.label.toString(), ACTION_SHORTCUT, shortcut));
        }
        return items;
    }

    public void playEntry() {
        requestFocus();
        animateTransition(0f, 1f, 200L, false);
    }

    public void onExternalMove(float x, float y) {
        // 列表态不跟手：触发手势期间的移动事件直接忽略。
    }

    public void onExternalUp(float x, float y, boolean cancelled) {
        // 触发手势抬指不关闭面板，交给用户点按。
    }

    public void onExternalCancel() {
        // 面板独立于触发手势，系统取消流不关闭它。
    }

    public void playDismissal() {
        if (closing) return;
        closing = true;
        animateTransition(1f, 0f, 150L, true);
    }

    boolean isClosing() {
        return closing;
    }

    public void releaseResources() {
        released = true;
        listener = null;
        if (transition != null) transition.cancel();
        tasks = Collections.emptyList();
        menuShortcuts = Collections.emptyList();
    }

    private void animateTransition(float from, float to, long duration, boolean closeAfter) {
        if (transition != null) transition.cancel();
        transition = ValueAnimator.ofFloat(from, to);
        transition.setDuration(duration);
        transition.setInterpolator(new DecelerateInterpolator(1.25f));
        transition.addUpdateListener(value -> {
            float progress = (Float) value.getAnimatedValue();
            setAlpha(progress);
            setTranslationY((1f - progress) * dp(18));
        });
        if (closeAfter) transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!released && listener != null) listener.onClosed();
            }
        });
        transition.start();
    }

    private void tickHaptic() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closing) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();
            boolean steady = Math.abs(x - touchDownX) <= touchSlop
                    && Math.abs(y - touchDownY) <= touchSlop;
            if (steady) handleTap(x, y);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) return true;
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !closing) {
            // 分层关闭：菜单展开时先只收菜单，再次返回才关面板。
            if (expandedRow >= 0) collapseExpand();
            else playDismissal();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 收起展开菜单，列表保持。 */
    private void collapseExpand() {
        if (expandedRow < 0) return;
        expandedRow = -1;
        menuShortcuts = Collections.emptyList();
        shortcutsRequestedFor = null;
        computeMetrics();
        invalidate();
    }

    private void handleTap(float x, float y) {
        if (expandedRow >= 0 && x >= cardLeft && x <= cardRight
                && y >= cardTop && y <= cardBottom) {
            int index = (int) Math.floor((y - cardTop - dp(31) + dp(22.5f)) / dp(45));
            List<MenuEntry> menu = buildMenu();
            if (index >= 0 && index < menu.size()) {
                MenuEntry entry = menu.get(index);
                tickHaptic();
                listener.onListAction(entry.action, tasks.get(expandedRow), entry.shortcut);
                playDismissal();
            }
            return;
        }
        if (x < panelLeft || x > panelLeft + panelWidth
                || y < panelTop || y > panelTop + panelHeight) {
            // 分层关闭：菜单展开时先只收菜单，再次点击才关面板。
            if (expandedRow >= 0) collapseExpand();
            else playDismissal();
            return;
        }
        int rows = rowCount();
        for (int i = 0; i < rows; i++) {
            float cy = rowCy(i);
            if (Math.abs(y - cy) > rowPitch / 2f) continue;
            if (x <= panelLeft + iconCx - iconRadius) {
                toggleExpand(i);
                return;
            }
            if (x >= panelLeft + nameEnd) {
                RecentTaskPreview task = tasks.get(i);
                tickHaptic();
                listener.onCloseTask(task);
                tasks.remove(i);
                expandedRow = -1;
                menuShortcuts = Collections.emptyList();
                shortcutsRequestedFor = null;
                if (tasks.isEmpty()) {
                    playDismissal();
                } else {
                    computeMetrics();
                    invalidate();
                }
                return;
            }
            tickHaptic();
            listener.onLaunch(tasks.get(i));
            return;
        }
        if (y > lastDividerY) {
            if (expandedRow >= 0) {
                if (Math.abs(x - searchCenterX()) <= dp(22)
                        && Math.abs(y - searchCy) <= dp(22)) {
                    tickHaptic();
                    listener.onListAction(ACTION_SEARCH, null, null);
                    playDismissal();
                }
                return;
            }
            float footerCy = footerBaseline - dp(5);
            if (Math.abs(x - searchCenterX()) <= dp(22)
                    && Math.abs(y - searchCy) <= dp(22)) {
                tickHaptic();
                listener.onListAction(ACTION_SEARCH, null, null);
                playDismissal();
                return;
            }
            if (Math.abs(y - footerCy) <= dp(22)
                    && Math.abs(x - (panelLeft + panelWidth / 2f)) <= dp(70)) {
                tickHaptic();
                listener.onCloseAll();
                playDismissal();
                return;
            }
            if (expandedRow >= 0) collapseExpand();
            return;
        }
        if (expandedRow >= 0) collapseExpand();
    }

    private float searchCenterX() {
        return panelLeft + crossX;
    }

    private void toggleExpand(int index) {
        tickHaptic();
        if (expandedRow == index) {
            collapseExpand();
            return;
        }
        expandedRow = index;
        String packageName = tasks.get(index).packageName;
        if (!packageName.equals(shortcutsRequestedFor)) {
            menuShortcuts = Collections.emptyList();
            shortcutsRequestedFor = packageName;
            requestShortcutsAsync(packageName);
        }
        computeMetrics();
        invalidate();
    }

    private void requestShortcutsAsync(String packageName) {
        new Thread(() -> {
            List<RecentTaskRepository.ShortcutEntry> found = queryShortcuts(packageName);
            mainThread.post(() -> {
                if (released || expandedRow < 0 || expandedRow >= tasks.size()) return;
                if (!tasks.get(expandedRow).packageName.equals(packageName)) return;
                menuShortcuts = found;
                computeMetrics();
                invalidate();
            });
        }, "fan-task-center-shortcuts").start();
    }

    private List<RecentTaskRepository.ShortcutEntry> queryShortcuts(String packageName) {
        ArrayList<RecentTaskRepository.ShortcutEntry> result = new ArrayList<>();
        android.content.pm.LauncherApps launcherApps =
                getContext().getSystemService(android.content.pm.LauncherApps.class);
        if (launcherApps == null || packageName == null || packageName.isEmpty()) {
            return result;
        }
        try {
            android.os.UserHandle user = android.os.Process.myUserHandle();
            android.content.pm.LauncherApps.ShortcutQuery query =
                    new android.content.pm.LauncherApps.ShortcutQuery();
            query.setPackage(packageName);
            query.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
            List<android.content.pm.ShortcutInfo> found =
                    launcherApps.getShortcuts(query, user);
            if (found == null) return result;
            for (android.content.pm.ShortcutInfo info : found) {
                if (info == null || info.getId() == null) continue;
                CharSequence label = info.getShortLabel() != null
                        ? info.getShortLabel() : info.getLongLabel();
                if (label == null) continue;
                result.add(new RecentTaskRepository.ShortcutEntry(
                        packageName, info.getId(), label, user));
            }
        } catch (Throwable error) {
            Log.i("Shortcut menu unavailable package=" + packageName
                    + " error=" + error.getClass().getSimpleName());
        }
        return result;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int rows = rowCount();
        if (rows <= 0) return;
        // 面板底板：阴影 + 白色圆角矩形 + 描边
        float panelBottom = panelTop + panelHeight;
        canvas.drawRoundRect(panelLeft + dp(1), panelTop + dp(3),
                panelLeft + panelWidth + dp(1), panelBottom + dp(3),
                dp(18), dp(18), shadowPaint);
        rect.set(panelLeft, panelTop, panelLeft + panelWidth, panelBottom);
        canvas.drawRoundRect(rect, dp(18), dp(18), panelPaint);
        canvas.drawRoundRect(rect, dp(18), dp(18), panelStroke);
        for (int i = 0; i < rows; i++) {
            float cy = rowCy(i);
            for (int dot = -1; dot <= 1; dot++) {
                canvas.drawCircle(panelLeft + dotX, cy + dot * dp(7), dp(1.8f), dotPaint);
            }
            RecentTaskPreview task = tasks.get(i);
            Drawable icon = task.icon;
            if (icon != null) {
                int saveCount = canvas.save();
                clipPath.reset();
                clipPath.addCircle(panelLeft + iconCx, cy, iconRadius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                icon.setBounds(Math.round(panelLeft + iconCx - iconRadius),
                        Math.round(cy - iconRadius),
                        Math.round(panelLeft + iconCx + iconRadius),
                        Math.round(cy + iconRadius));
                icon.draw(canvas);
                canvas.restoreToCount(saveCount);
            }
            CharSequence label = task.label != null ? task.label : task.packageName;
            CharSequence fitted = TextUtils.ellipsize(label, namePaint,
                    nameEnd - nameStart - dp(14), TextUtils.TruncateAt.END);
            canvas.drawText(fitted, 0, fitted.length(),
                    panelLeft + nameStart, cy + dp(5), namePaint);
            canvas.drawText("×", panelLeft + crossX, cy + dp(6), crossPaint);
            canvas.drawLine(panelLeft + nameStart, cy + dp(21),
                    panelLeft + nameEnd, cy + dp(21), dividerPaint);
        }
        boolean expanded = expandedRow >= 0;
        if (!expanded) {
            canvas.drawText("全部关闭", panelLeft + panelWidth / 2f,
                    footerBaseline, footerPaint);
        }
        float searchX = searchCenterX();
        float searchY = searchCy;
        canvas.drawCircle(searchX, searchY, dp(14), searchBgPaint);
        canvas.drawCircle(searchX - dp(1), searchY - dp(1), dp(5), magnifierPaint);
        canvas.drawLine(searchX + dp(3), searchY + dp(3),
                searchX + dp(7), searchY + dp(7), magnifierPaint);
        if (!expanded) return;
        canvas.drawRoundRect(cardLeft + dp(1), cardTop + dp(3),
                cardRight + dp(1), cardBottom + dp(3), dp(14), dp(14), shadowPaint);
        rect.set(cardLeft, cardTop, cardRight, cardBottom);
        canvas.drawRoundRect(rect, dp(14), dp(14), panelPaint);
        canvas.drawRoundRect(rect, dp(14), dp(14), panelStroke);
        List<MenuEntry> menu = buildMenu();
        float textX = cardLeft + dp(20);
        for (int i = 0; i < menu.size(); i++) {
            float baseline = cardTop + dp(31) + i * dp(45);
            canvas.drawText(menu.get(i).label, textX, baseline, itemPaint);
        }
    }

    private static final class MenuEntry {
        final String label;
        final int action;
        final RecentTaskRepository.ShortcutEntry shortcut;

        MenuEntry(String label, int action, RecentTaskRepository.ShortcutEntry shortcut) {
            this.label = label;
            this.action = action;
            this.shortcut = shortcut;
        }
    }
}
