package com.oxohang.fanfreeform.xposed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import com.oxohang.fanfreeform.ui.Ui;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.OverScroller;

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
        /** 点击行内锁形：切换该应用的锁定状态。 */
        void onToggleLock(RecentTaskPreview task, boolean shouldLock);
        void onClosed();
    }

    static final int ACTION_LOCK = 0;
    static final int ACTION_SPLIT = 1;
    static final int ACTION_FREEFORM = 2;
    static final int ACTION_DETAILS = 3;
    static final int ACTION_SHORTCUT = 4;
    static final int ACTION_SEARCH = 5;

    // 面板改为不透明白：真实投影要从底下透出来，半透明会把投影的暗部也一起透上来。
    private static final int COLOR_PANEL = 0xffffffff;
    private static final int COLOR_PANEL_STROKE = 0xffe3e7ec;
    // 分隔线：Apple separator 落在白底上约 1.7:1（rgba(60,60,67,.29) ≈ #c6c6c7）。
    // 原来 #e8ebee 只有 1.2:1，肉眼几乎看不见，列表就丢了"一行一个"的分隔感。
    private static final int COLOR_DIVIDER = 0xffc3c7ce;
    private static final int COLOR_TEXT = 0xff2c3138;
    // 次级文字：#9aa2ab 对白底只有 2.59:1，远低于 AA 4.5:1；#6b7280 为 4.83:1。
    private static final int COLOR_MUTED = 0xff6b7280;
    private static final int COLOR_DOT = 0xff8e959e;
    // 强调色必须与设置 App 的令牌同值（Ui.ACCENT = #5A67F2）。此前这里是 #3478f6，
    // 同一个模块出现两个蓝；统一后 iOS 语义下也只有一个 accent。
    private static final int COLOR_ACCENT = Ui.ACCENT;
    private static final int COLOR_SEARCH_BG = 0xfff0f2f5;
    // 菜单卡底：iOS 用 secondarySystemBackground（#F2F2F7 一档微灰）与白面板分层，
    // 而不是"同色白 + 描边"。白底上再铺一层纯白是分不出层级的。
    private static final int COLOR_MENU_BG = 0xfff2f3f7;
    private static final int COLOR_SHADOW = 0x2e000000;
    /** 圆角：iOS sheet 一档。原来 18dp 偏"胶囊"，14dp 更贴系统浮层。 */
    private static final float CARD_RADIUS_DP = 14f;
    /**
     * 行尾动作区每段的触摸半径（dp）：±20dp 即 40dp 宽，接近 Apple 最小触摸目标 44dp
     * （面板只有 280dp，再宽就挤掉应用名了）。视觉图形比这个小得多 ——
     * 图标是"看得见"的最小形态，命中区才是"点得中"的。
     */
    private static final float ACTION_HIT_RADIUS_DP = 20f;
    /** 行高亮 = iOS secondarySystemFill：系统灰，不用彩色底，选中态才不刺眼。 */
    private static final int COLOR_ROW_FILL = 0x24787880;

    /** 出场因果：与入场同路径反演（点外部 / 返回键）。 */
    static final int EXIT_RECEDE = 0;
    /** 出场因果：给被启动的目标让位（点应用 / 点菜单项）。 */
    static final int EXIT_YIELD = 1;
    /** 出场因果：跟手上划甩出，继承手速飞离屏幕。 */
    static final int EXIT_FLING = 2;
    /** 出场因果：系统事件（开小窗等）安静让路，不抢注意力。 */
    static final int EXIT_QUIET = 3;

    /** 甩出门槛：位移超过它（或手速够快）就放手放行。 */
    private static final float DISMISS_TRAVEL_DP = 110f;
    /** 快速轻扫阈值（px/s）：手速够快时即使位移很小也放行。 */
    private static final float FLICK_VELOCITY = 1200f;
    /** 不可达方向（下）的橡皮筋系数与上限：拖得动、但拖不远，表示"到底了"。 */
    private static final float RUBBER_BAND = 0.18f;
    private static final float RUBBER_LIMIT_DP = 26f;
    /** 入场起始缩放：面板从手指那一侧长出来。 */
    private static final float ENTRY_SCALE_FROM = 0.94f;
    /** 退位（系统面板展开）时的形态：缩小 + 变暗，但不销毁。 */
    private static final float PAUSED_SCALE = 0.90f;
    private static final float PAUSED_ALPHA = 0.55f;
    /**
     * 侧边返回手势的跟手行程（dp）：手指从边缘往里滑到这么远时，让位进度满格。
     * 取系统与模块共用的一档，只用于视觉跟手，不参与"返回是否触发"的判定。
     */
    static final float BACK_YIELD_TRAVEL_DP = 72f;
    /** 跟手让位的最大横向位移（dp）与最深变暗：够看出在让，但不至于喧宾夺主。 */
    private static final float BACK_YIELD_SHIFT_DP = 26f;
    private static final float BACK_YIELD_ALPHA = 0.45f;
    private static final float BACK_YIELD_SCALE = 0.03f;
    /**
     * 菜单展开时上划收菜单的行程（dp）与放行阈值：滑到行程一半即收，
     * 与列表甩出的 110dp 相比短一半 —— 退一层比退到底应该更轻。
     */
    private static final float MENU_COLLAPSE_TRAVEL_DP = 72f;
    private static final float MENU_COLLAPSE_RELEASE = 0.5f;

    /**
     * 弹簧曲线：WWDC《Designing Fluid Interfaces》给出的 sheet/drawer 参数
     * （damping 0.8 / response 0.3s）在缓动函数上的等效映射。
     * 用它替代自造的减速插值器——这是平台自带的三次贝塞尔实现，无新增依赖。
     */
    private static final TimeInterpolator SPRING = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    private final float density;
    private final int touchSlop;
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 菜单卡底：与白面板差一档灰，取代"同色白 + 描边"。 */
    private final Paint menuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 锁定锁形：描边，与关闭叉同一套笔触语言（都是 1.6dp 圆头描边）。 */
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint itemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint magnifierPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();

    private List<RecentTaskPreview> tasks = Collections.emptyList();
    private Listener listener;
    private boolean hapticEnabled;
    /** 图标是否强制裁圆：与扇形 / 蜂窝同一个开关，不在这里另立一套。 */
    private boolean forceCircularIcons = true;
    /**
     * 锁定状态源（只读快照）：由调用方注入。缺省为空 —— 拿不到状态时列表照常画，
     * 只是不显示锁形，不让增益信息拖垮主流程。
     */
    private LockedAppStore lockedApps;
    private int animationSpeedIndex;
    private GestureGeometry.Corner anchorCorner = GestureGeometry.Corner.LEFT;
    private float anchorX;
    private float anchorY;
    private boolean closing;
    private boolean released;
    private boolean reducedMotion;
    private ValueAnimator entry;
    private ValueAnimator transition;
    private float touchDownX;
    private float touchDownY;
    private VelocityTracker velocityTracker;
    private float dragOffset;
    private boolean dragging;
    private boolean paused;
    private ValueAnimator yieldAnim;
    private float yieldProgress;
    private boolean yieldFromLeft;
    /** 菜单展开时纵向拖拽的归属与进度：菜单在，上划收的是菜单，不是列表。 */
    private boolean draggingMenu;
    private float menuCollapseProgress;
    private ValueAnimator menuAnim;
    private int expandedRow = -1;
    private int pressedRow = -1;
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
    /** 锁定图标位（行尾动作区左半）：见 computeMetrics 说明。 */
    private float lockX;
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
        reducedMotion = readReducedMotion();
        setFocusableInTouchMode(true);
        setClickable(true);
        panelPaint.setColor(COLOR_PANEL);
        // 真实投影：Skia 模糊，取代原来"偏移的实心灰矩形"（那看着像一圈硬边描边）。
        panelPaint.setShadowLayer(dp(16), 0f, dp(5), COLOR_SHADOW);
        panelStroke.setStyle(Paint.Style.STROKE);
        panelStroke.setStrokeWidth(dp(1));
        panelStroke.setColor(COLOR_PANEL_STROKE);
        // 分隔线走发丝线：Apple separator 是 1px 级，1.5dp 在 440dpi 上是 4px，太重。
        dividerPaint.setStrokeWidth(dp(1));
        dividerPaint.setColor(COLOR_DIVIDER);
        dotPaint.setColor(COLOR_DOT);
        namePaint.setColor(COLOR_TEXT);
        // 正文一级：iOS 列表正文 17pt，本面板窄，取次一级 15pt（Apple subhead）为下限。
        namePaint.setTextSize(dp(15));
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(dp(1.6f));
        crossPaint.setStrokeCap(Paint.Cap.ROUND);
        crossPaint.setColor(COLOR_MUTED);
        // 锁形用强调色：它是"状态"不是"动作"，与灰色的关闭叉区分开，
        // 一眼扫过列表就能看出哪几行被锁了。
        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(dp(1.5f));
        lockPaint.setStrokeCap(Paint.Cap.ROUND);
        lockPaint.setStrokeJoin(Paint.Join.ROUND);
        lockPaint.setColor(COLOR_ACCENT);
        rowFillPaint.setColor(COLOR_ROW_FILL);
        itemPaint.setColor(COLOR_TEXT);
        itemPaint.setTextSize(dp(15));
        footerPaint.setColor(COLOR_ACCENT);
        // 与正文同级：iOS 里"全部关闭"这类按钮文字不小于列表正文。
        footerPaint.setTextSize(dp(15));
        footerPaint.setTextAlign(Paint.Align.CENTER);
        menuPaint.setColor(COLOR_MENU_BG);
        menuPaint.setShadowLayer(dp(12), 0f, dp(3), COLOR_SHADOW);
        searchBgPaint.setColor(COLOR_SEARCH_BG);
        magnifierPaint.setStyle(Paint.Style.STROKE);
        magnifierPaint.setStrokeWidth(dp(1.6f));
        magnifierPaint.setStrokeCap(Paint.Cap.ROUND);
        magnifierPaint.setColor(COLOR_ACCENT);
    }

    void configure(List<RecentTaskPreview> tasks, GestureGeometry.Corner corner,
                   float anchorX, float anchorY,
                   GestureConfig config, Listener listener) {
        configure(tasks, corner, anchorX, anchorY, config, listener, null);
    }

    /**
     * @param lockedApps 锁定状态源，可为 null（不显示锁形）。生命周期由调用方持有 ——
     *                   列表视图会被反复创建销毁，状态源不该跟着被回收重建。
     */
    void configure(List<RecentTaskPreview> tasks, GestureGeometry.Corner corner,
                   float anchorX, float anchorY,
                   GestureConfig config, Listener listener,
                   LockedAppStore lockedApps) {
        this.tasks = new ArrayList<>(tasks);
        this.listener = listener;
        this.hapticEnabled = config.haptic;
        this.forceCircularIcons = config.forceCircularIcons;
        this.animationSpeedIndex = config.sideTaskAnimationSpeed;
        // UI 出现在手边：面板以手指位置为锚点生长，而不是双向居中。
        // 这是全局「手势触发点 = UI 锚点」原则的唯一例外修复
        // （扇形/蜂窝/侧列表/滚轮本来就带锚点，唯独这里丢过）。
        this.anchorCorner = corner == GestureGeometry.Corner.RIGHT
                ? GestureGeometry.Corner.RIGHT : GestureGeometry.Corner.LEFT;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.dragOffset = 0f;
        this.dragging = false;
        this.expandedRow = -1;
        this.menuShortcuts = Collections.emptyList();
        this.shortcutsRequestedFor = null;
        this.lockedApps = lockedApps;
        if (lockedApps != null) {
            if (lockedApps.refresh()) invalidate();
            lockedApps.startObserving(this::invalidate);
        }
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
        // 行距 52dp：单行点击目标 ≥ 44dp（Apple 最小触摸目标）。
        rowPitch = dp(52);
        firstRowOffset = dp(30);
        dotX = dp(24);
        // 行首 ⋮ 的点击区 = [0, iconCx-iconRadius]，必须 ≥44dp（Apple 最小触摸目标），
        // 所以图标左缘钉在 46dp —— 这就是"左边距收不到 iOS 16dp"的真实原因：
        // 顶住的是点击区，不是图标本身。要真收到 16dp，得把菜单入口换成 iOS 的长按
        // （或把 ⋮ 挪到行尾），那是设计取舍，不在本次改色改字范围。
        iconCx = dp(58);
        iconRadius = dp(12);
        nameStart = dp(76);
        // 行尾动作区（从右往左排，两段各自 ≥40dp 触摸宽度，互不重叠）：
        //   关闭叉中心 crossX = panelWidth - 28（右侧留 14dp 给叉臂与触摸余量）
        //   锁中心   lockX  = 关闭区左缘 - 4dp 缝隙 - 20dp
        // 之所以从右往左反推而不是拍两个坐标：两个动作挨得近，必须保证
        // "点关闭不会误触锁、点锁不会误触关闭"，靠反推才能让两段的边界可验算。
        // nameEnd 只是排版边界（也是分隔线终点），不是命中分界。
        crossX = panelWidth - dp(28);
        lockX = panelWidth - dp(28) - dp(20) - dp(4) - dp(20);
        nameEnd = lockX - dp(10);
        int rows = rowCount();
        float listHeight = dp(118) + (rows - 1) * rowPitch;
        float margin = dp(28);
        float maxTop = height - listHeight - margin;
        if (maxTop < margin) maxTop = margin;
        // 垂直落在手边：面板中心对齐手指所在的高度，再钳进安全区。
        // 手指（侧边手势的触发点）在下半屏时面板就出现在下半屏，
        // 用户不必把手移到屏幕中段去点。anchorY <= 0 表示调用方没给锚点，回退居中。
        float desired = anchorY > 0f
                ? anchorY - listHeight / 2f
                : (height - listHeight) / 2f;
        // 水平跟随触发侧：左侧滑出靠左、右侧滑出靠右，边距 16dp。
        float sideMargin = dp(16);
        if (anchorCorner == GestureGeometry.Corner.RIGHT) {
            panelLeft = width - panelWidth - sideMargin;
        } else {
            panelLeft = sideMargin;
        }
        panelTop = Math.max(margin, Math.min(desired, maxTop));
        lastDividerY = panelTop + firstRowOffset + (rows - 1) * rowPitch + dp(21);
        footerBaseline = lastDividerY + dp(40);
        searchCy = lastDividerY + dp(35);
        panelHeight = listHeight;
        if (expandedRow < 0 || expandedRow >= rows) return;
        cardLeft = panelLeft + dp(54);
        // 菜单卡右缘跟着面板内边距（14dp），不再咬着文字右边界 —— 文字位因预留锁位
        // 内缩后，菜单会跟着变窄，长菜单项（"在分屏视图中打开"）就顶到边了。
        cardRight = panelLeft + panelWidth - dp(14);
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
        reducedMotion = readReducedMotion();
        // 生长原点钉在手指所在的那条边上：面板从被划出的那一侧长出来，
        // 而不是从屏幕中心放大 —— 方向本身就是"它从哪来"的说明。
        setPivotX(clamp(anchorX, panelLeft, panelLeft + panelWidth));
        setPivotY(clamp(anchorY, panelTop, panelTop + panelHeight));
        if (entry != null) entry.cancel();
        if (reducedMotion) {
            applyEntryVisual(1f);
            return;
        }
        entry = ValueAnimator.ofFloat(0f, 1f);
        entry.setDuration(TaskCenterAnimationPolicy.durationMs(260L, animationSpeedIndex));
        entry.setInterpolator(SPRING);
        entry.addUpdateListener(value -> applyEntryVisual((Float) value.getAnimatedValue()));
        entry.start();
    }

    private void applyEntryVisual(float progress) {
        setAlpha(progress);
        float scale = ENTRY_SCALE_FROM + (1f - ENTRY_SCALE_FROM) * progress;
        setScaleX(scale);
        setScaleY(scale);
        setTranslationY((1f - progress) * dp(10));
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
        playExit(EXIT_RECEDE, 0f);
    }

    /**
     * 四层出场：每种关闭因果有自己的终点形态，动画只负责把因果说清楚。
     *
     * <ul>
     *   <li>{@link #EXIT_RECEDE} 回落 —— 点外部 / 返回键，与入场同路径反演；</li>
     *   <li>{@link #EXIT_YIELD} 让位 —— 点应用 / 菜单项，淡出并缩小让给新界面；</li>
     *   <li>{@link #EXIT_FLING} 甩出 —— 跟手上划，继承手速飞离屏幕；</li>
     *   <li>{@link #EXIT_QUIET} 安静让路 —— 系统事件，只淡出，不抢注意力。</li>
     * </ul>
     *
     * <p>全部只动 translation / scale / alpha：不触发 layout、不重建窗口，
     * 避免在 SystemUI 进程里走上重排路径（本项目反复踩过的坑）。
     */
    void playExit(int reason, float velocityY) {
        if (closing) return;
        closing = true;
        if (entry != null) entry.cancel();
        float toOffset;
        float toScale;
        float toAlpha;
        long duration;
        switch (reason) {
            case EXIT_YIELD:
                toOffset = dp(-12);
                toScale = 0.92f;
                toAlpha = 0f;
                duration = 220L;
                break;
            case EXIT_QUIET:
                toOffset = dragOffset;
                toScale = getScaleX();
                toAlpha = 0f;
                duration = 120L;
                break;
            case EXIT_FLING:
                toOffset = -(panelTop + panelHeight + dp(16));
                toScale = getScaleX();
                toAlpha = 0f;
                duration = flingDuration(toOffset, velocityY);
                break;
            default:
                toOffset = dp(10);
                toScale = ENTRY_SCALE_FROM;
                toAlpha = 0f;
                duration = 200L;
                break;
        }
        animateTo(toOffset, toScale, toAlpha, duration, true);
    }

    /** 从当前视觉状态过渡到目标，可打断（先 cancel 再起播）。 */
    private void animateTo(float toOffset, float toScale, float toAlpha,
                           long duration, boolean closeAfter) {
        if (transition != null) transition.cancel();
        float fromOffset = dragOffset;
        float fromScale = getScaleX();
        float fromAlpha = getAlpha();
        long ms = motion(TaskCenterAnimationPolicy.durationMs(duration, animationSpeedIndex));
        if (ms <= 0L) {
            applyVisual(toOffset, toScale, toAlpha);
            if (closeAfter) notifyClosed();
            return;
        }
        transition = ValueAnimator.ofFloat(0f, 1f);
        transition.setDuration(ms);
        transition.setInterpolator(SPRING);
        transition.addUpdateListener(value -> {
            float p = (Float) value.getAnimatedValue();
            applyVisual(fromOffset + (toOffset - fromOffset) * p,
                    fromScale + (toScale - fromScale) * p,
                    fromAlpha + (toAlpha - fromAlpha) * p);
        });
        if (closeAfter) transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                notifyClosed();
            }
        });
        transition.start();
    }

    private void applyVisual(float offset, float scale, float alpha) {
        dragOffset = offset;
        setTranslationY(offset);
        setScaleX(scale);
        setScaleY(scale);
        setAlpha(alpha);
    }

    private void notifyClosed() {
        if (!released && listener != null) listener.onClosed();
    }

    /** 甩出时长由"剩下多远 / 手有多快"决定，再钳到 180–340ms。 */
    private long flingDuration(float toOffset, float velocityY) {
        float distance = Math.abs(toOffset - dragOffset);
        float speed = Math.max(Math.abs(velocityY), dp(600));
        long ms = Math.round(distance / speed * 1000f);
        return Math.max(180L, Math.min(ms, 340L));
    }

    private void beginDrag() {
        // 入场可打断：从当前值接手，不排队、不重播。
        if (entry != null) entry.cancel();
        setScaleX(1f);
        setScaleY(1f);
        if (transition != null) transition.cancel();
    }

    /**
     * 菜单展开时的纵向拖拽：往上推 = 菜单跟手收缩；往下 = 纹丝不动（到边界了）。
     *
     * <p>为什么往下不做橡皮筋：菜单没有"更大的形态"可以展开，往下拖出位移
     * 就是在撒谎（暗示下面还有东西）。静止本身就是最诚实的边界反馈。
     */
    private void applyMenuDrag(float dy) {
        if (menuAnim != null) {
            menuAnim.cancel();
            menuAnim = null;
        }
        float up = Math.max(0f, -dy);
        menuCollapseProgress = Math.min(1f, up / dp(MENU_COLLAPSE_TRAVEL_DP));
        invalidate();
    }

    private void finishMenuDrag() {
        if (menuCollapseProgress >= MENU_COLLAPSE_RELEASE) {
            menuCollapseProgress = 0f;
            collapseExpand();
            return;
        }
        animateMenuProgress(0f);
    }

    private void settleMenuBack() {
        animateMenuProgress(0f);
    }

    /** 菜单收缩进度回弹：松手没到阈值就原样长回去。 */
    private void animateMenuProgress(float target) {
        if (menuAnim != null) menuAnim.cancel();
        float from = menuCollapseProgress;
        if (Math.abs(from - target) < 0.001f || reducedMotion) {
            menuAnim = null;
            menuCollapseProgress = target;
            invalidate();
            return;
        }
        menuAnim = ValueAnimator.ofFloat(from, target);
        menuAnim.setDuration(motion(TaskCenterAnimationPolicy.durationMs(160L,
                animationSpeedIndex)));
        menuAnim.setInterpolator(SPRING);
        menuAnim.addUpdateListener(value -> {
            menuCollapseProgress = (Float) value.getAnimatedValue();
            invalidate();
        });
        menuAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                menuAnim = null;
            }
        });
        menuAnim.start();
    }

    private void applyDrag(float dy) {
        // 向上是可达方向，1:1 跟手；向下不可达，给橡皮筋——让"到底了"可被感知。
        float offset = dy < 0f ? dy : Math.min(dy * RUBBER_BAND, dp(RUBBER_LIMIT_DP));
        dragOffset = offset;
        setTranslationY(offset);
        setAlpha(alphaForTravel(-Math.min(offset, 0f)));
    }

    /** 位移越大越淡：拖到门槛处剩 45%，继续拖到两倍门槛处归零。 */
    private float alphaForTravel(float travel) {
        float span = dp(DISMISS_TRAVEL_DP);
        float fade = travel <= span
                ? 0.55f * (travel / span)
                : 0.55f + 0.45f * Math.min(1f, (travel - span) / span);
        return 1f - fade;
    }

    private void finishDrag() {
        float velocityY = 0f;
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000);
            velocityY = velocityTracker.getYVelocity();
        }
        releaseTracker();
        dragging = false;
        float travel = -Math.min(dragOffset, 0f);
        float upward = -velocityY;
        boolean escaped = projectedTravel(velocityY) >= dp(DISMISS_TRAVEL_DP)
                || (upward >= FLICK_VELOCITY && travel >= dp(12));
        if (escaped) playExit(EXIT_FLING, velocityY);
        else settleBack();
    }

    /**
     * 动量投射：松手后还会滑多远。用平台自带的 {@link OverScroller}
     * （系统 fling 就是它算的），而不是自己积分 —— 这是"优先用系统原生接口"
     * 的直接落地，也省掉一条自造的物理曲线。
     */
    private float projectedTravel(float velocityY) {
        float travel = -Math.min(dragOffset, 0f);
        if (velocityY >= 0f) return travel;
        OverScroller scroller = new OverScroller(getContext());
        scroller.fling(0, Math.round(-travel), 0, Math.round(velocityY),
                0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return -scroller.getFinalY();
    }

    private void settleBack() {
        animateTo(0f, 1f, 1f, 260L, false);
    }

    /**
     * 退位：系统面板（通知栏 / 控制中心）展开时缩小变暗，但**不销毁**；
     * 面板收起后原样弹回。用户只是去做别的事，列表不该被夺走 —— 这是
     * 「暂停」类事件与「终局」类事件的唯一区别，也是"下拉一下列表就没了"
     * 这种割裂感的根源。
     */
    void setPaused(boolean paused) {
        if (closing || this.paused == paused) return;
        this.paused = paused;
        // 退位与跟手让位互斥：下拉通知栏时清掉跟手横向偏移，避免两种"让"叠在一起。
        setTranslationX(0f);
        yieldProgress = 0f;
        animateTo(dragOffset, paused ? PAUSED_SCALE : 1f,
                paused ? PAUSED_ALPHA : 1f, 180L, false);
    }

    /**
     * 侧边返回手势的跟手让位：手指往里滑，面板朝手势来向被推开并变暗。
     *
     * <p>为什么要有这一段：列表窗口是全屏 overlay，底下应用收不到触摸，
     * 系统返回手势只能靠自己的 InputMonitor 旁听 + {@code pilferPointers()}
     * 抢走指针来执行。返回因此发生在列表下面"看不见"的那一层，
     * 列表本身纹丝不动 —— 用户看到的是"滑了没反应"，返回动画与列表各演各的。
     *
     * <p>这里不抢系统手势（抢了就毁掉返回），只**旁听**它并把进度画出来：
     * 手势进行到哪，面板就让到哪；系统真抢走指针时由
     * {@link #releaseBackYield(boolean)} 收尾，让位动画与系统返回动画同时发生。
     */
    void setBackYieldProgress(float progress, boolean fromLeft) {
        if (closing || paused) return;
        if (yieldAnim != null) {
            yieldAnim.cancel();
            yieldAnim = null;
        }
        yieldProgress = progress;
        yieldFromLeft = fromLeft;
        applyYield(progress, fromLeft);
    }

    /**
     * 侧边返回手势收尾：系统没抢走指针（说明返回没触发）→ 弹回原位。
     *
     * <p>返回确实触发时由 {@code FanRuntime} 走 {@code dismissTaskSurfaceForForegroundChange}
     * 播「让位」，不经过这里 —— 让位与回弹是两条互斥的终局，不要合成一个开关。
     */
    void releaseBackYield() {
        if (closing || paused) return;
        float fromX = getTranslationX();
        float fromAlpha = getAlpha();
        float fromScale = getScaleX();
        float toAlpha = 1f;
        float toScale = 1f;
        long ms = motion(TaskCenterAnimationPolicy.durationMs(180L, animationSpeedIndex));
        yieldProgress = 0f;
        if (yieldAnim != null) yieldAnim.cancel();
        if (ms <= 0L) {
            yieldAnim = null;
            setTranslationX(0f);
            setAlpha(toAlpha);
            setScaleX(toScale);
            setScaleY(toScale);
            return;
        }
        yieldAnim = ValueAnimator.ofFloat(0f, 1f);
        yieldAnim.setDuration(ms);
        yieldAnim.setInterpolator(SPRING);
        yieldAnim.addUpdateListener(value -> {
            float p = (Float) value.getAnimatedValue();
            setTranslationX(fromX * (1f - p));
            setAlpha(fromAlpha + (toAlpha - fromAlpha) * p);
            float scale = fromScale + (toScale - fromScale) * p;
            setScaleX(scale);
            setScaleY(scale);
        });
        yieldAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                yieldAnim = null;
            }
        });
        yieldAnim.start();
    }

    private void applyYield(float progress, boolean fromLeft) {
        float shift = dp(BACK_YIELD_SHIFT_DP) * progress * (fromLeft ? 1f : -1f);
        setTranslationX(shift);
        setAlpha(1f - BACK_YIELD_ALPHA * progress);
        float scale = 1f - BACK_YIELD_SCALE * progress;
        setScaleX(scale);
        setScaleY(scale);
    }

    private void releaseTracker() {
        if (velocityTracker == null) return;
        velocityTracker.recycle();
        velocityTracker = null;
    }

    /** 跟随系统「减弱动效」：时长归零，视觉直接落到终点。 */
    private long motion(long ms) {
        return reducedMotion ? 0L : ms;
    }

    private boolean readReducedMotion() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE) <= 0f;
        } catch (Throwable error) {
            // 读不到（系统未设置 / 无权限）时按"动效开启"处理，这是唯一安全的默认。
            return false;
        }
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(value, high));
    }

    /** 命中判定换算回未变换坐标：入场/出场的缩放位移期间点按也要准。 */
    private float toLocalX(float x) {
        float scale = getScaleX();
        if (scale <= 0f) return x;
        return getPivotX() + (x - getPivotX()) / scale;
    }

    private float toLocalY(float y) {
        float scale = getScaleY();
        if (scale <= 0f) return y;
        return getPivotY() + (y - getTranslationY() - getPivotY()) / scale;
    }

    public void releaseResources() {
        released = true;
        listener = null;
        // 监听要停：视图会被反复创建销毁，观察者留着会泄漏且回调到已死的视图。
        // 状态源本体由调用方持有，这里只退订，不销毁。
        if (lockedApps != null) lockedApps.stopObserving();
        if (entry != null) entry.cancel();
        if (transition != null) transition.cancel();
        if (yieldAnim != null) yieldAnim.cancel();
        if (menuAnim != null) menuAnim.cancel();
        releaseTracker();
        tasks = Collections.emptyList();
        menuShortcuts = Collections.emptyList();
    }

    private void tickHaptic() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closing) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                dragging = false;
                // 按下即反馈：手一落下行就亮，不用等到抬手才知道点中了什么。
                setPressedRow(rowAt(event.getX(), event.getY()));
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (!dragging) {
                    float dx = event.getX() - touchDownX;
                    float dy = event.getY() - touchDownY;
                    // 只有明确的纵向意图才进入拖拽，横向滑动留给点选。
                    if (Math.abs(dy) <= touchSlop || Math.abs(dy) <= Math.abs(dx)) return true;
                    dragging = true;
                    setPressedRow(-1);
                    // 层级优先：一个手势只退一层。菜单展开时纵向拖拽作用于菜单，
                    // 列表本体不动 —— 否则上划会把菜单连同整个列表一起甩掉。
                    if (expandedRow >= 0) {
                        draggingMenu = true;
                    } else {
                        beginDrag();
                    }
                }
                if (draggingMenu) applyMenuDrag(event.getY() - touchDownY);
                else applyDrag(event.getY() - touchDownY);
                return true;
            case MotionEvent.ACTION_UP:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (dragging) {
                    if (draggingMenu) finishMenuDrag();
                    else finishDrag();
                    draggingMenu = false;
                    return true;
                }
                setPressedRow(-1);
                float upX = event.getX();
                float upY = event.getY();
                if (Math.abs(upX - touchDownX) <= touchSlop
                        && Math.abs(upY - touchDownY) <= touchSlop) {
                    handleTap(upX, upY);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (draggingMenu) settleMenuBack();
                else if (dragging) settleBack();
                dragging = false;
                draggingMenu = false;
                setPressedRow(-1);
                releaseTracker();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !closing) {
            popLevel(EXIT_RECEDE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setPressedRow(int row) {
        if (pressedRow == row) return;
        pressedRow = row;
        invalidate();
    }

    /** 收起展开菜单，列表保持。 */
    private void collapseExpand() {
        if (expandedRow < 0) return;
        expandedRow = -1;
        menuShortcuts = Collections.emptyList();
        shortcutsRequestedFor = null;
        resetMenuProgress();
        computeMetrics();
        invalidate();
    }

    /**
     * 退一层：菜单在就只收菜单，已在最外层才关列表。
     *
     * <p>苹果的分层原则 —— 一个手势只退一层。点面板外、返回键、菜单展开时的上划
     * 三条通路共用这一处判据，不再各自复制一遍「菜单在不在」。
     */
    private void popLevel(int cause) {
        if (expandedRow >= 0) {
            collapseExpand();
            return;
        }
        playExit(cause, 0f);
    }

    /** 菜单进度归零：展开、收起、列表重建都要清，否则下次展开会带着上一次的收缩量。 */
    private void resetMenuProgress() {
        if (menuAnim != null) {
            menuAnim.cancel();
            menuAnim = null;
        }
        menuCollapseProgress = 0f;
        draggingMenu = false;
    }

    private void handleTap(float x, float y) {
        // 入场/出场动画期间视图带缩放与位移，命中判定换算回未变换的坐标。
        x = toLocalX(x);
        y = toLocalY(y);
        if (expandedRow >= 0 && x >= cardLeft && x <= cardRight
                && y >= cardTop && y <= cardBottom) {
            int index = (int) Math.floor((y - cardTop - dp(31) + dp(22.5f)) / dp(45));
            List<MenuEntry> menu = buildMenu();
            if (index >= 0 && index < menu.size()) {
                MenuEntry entry = menu.get(index);
                tickHaptic();
                // 出场交给控制器：动作已派发，面板按「让位」退走。
                listener.onListAction(entry.action, tasks.get(expandedRow), entry.shortcut);
            }
            return;
        }
        if (x < panelLeft || x > panelLeft + panelWidth
                || y < panelTop || y > panelTop + panelHeight) {
            popLevel(EXIT_RECEDE);
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
            // 行尾动作区分两段：先判锁（点击切换锁定），再判关闭。两段边界严格按
            // 锁区右缘切开，各自 40dp 宽、中间留 4dp 缝（见 computeMetrics 的反推）。
            // 锁定动作只在有状态源时才存在 —— 没有状态源就没有"锁/不锁"的概念，
            // 那一段整块回退成关闭区，不让点击落空。
            float closeFrom = panelLeft + nameEnd;
            if (lockedApps != null) {
                float lockLeft = panelLeft + lockX - dp(ACTION_HIT_RADIUS_DP);
                float lockRight = panelLeft + lockX + dp(ACTION_HIT_RADIUS_DP);
                if (x >= lockLeft && x <= lockRight) {
                    RecentTaskPreview task = tasks.get(i);
                    tickHaptic();
                    boolean nowLocked = lockedApps.isLocked(task.packageName);
                    listener.onToggleLock(task, !nowLocked);
                    invalidate();
                    return;
                }
                closeFrom = lockRight;
            }
            if (x >= closeFrom) {
                RecentTaskPreview task = tasks.get(i);
                tickHaptic();
                listener.onCloseTask(task);
                tasks.remove(i);
                expandedRow = -1;
                menuShortcuts = Collections.emptyList();
                shortcutsRequestedFor = null;
                if (tasks.isEmpty()) {
                    // 最后一行被关掉，面板无事可做 —— 按「让位」退走。
                    playExit(EXIT_YIELD, 0f);
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
                }
                return;
            }
            float footerCy = footerBaseline - dp(5);
            if (Math.abs(x - searchCenterX()) <= dp(22)
                    && Math.abs(y - searchCy) <= dp(22)) {
                tickHaptic();
                listener.onListAction(ACTION_SEARCH, null, null);
                return;
            }
            if (Math.abs(y - footerCy) <= dp(22)
                    && Math.abs(x - (panelLeft + panelWidth / 2f)) <= dp(70)) {
                tickHaptic();
                listener.onCloseAll();
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

    /** 关闭叉：描边绘制，比文字字形利落，也与行首 ⋮ 的圆点同一套笔触语言。 */
    /**
     * 等比缩放后再裁形 —— 与扇形 {@code FanOverlayView.drawCircularIcon} 同一套系数
     * （裁圆 1.16 铺满 / 不裁 0.94 内接）。
     *
     * <p>此前这里直接 {@code setBounds} 成 24dp 正方形：非正方形的应用图标会被
     * **拉变形**，且无视"是否强制裁圆"的开关（列表永远裁圆，扇形/蜂窝会跟开关）。
     * 自适应图标（AdaptiveIconDrawable）尤其明显 —— 它自带一层遮罩，
     * 放大系数不对会在圆里留一圈白边。
     */
    private void drawFittedIcon(Canvas canvas, Drawable icon, float cx, float cy,
                                float diameter) {
        int intrinsicWidth = Math.max(1, icon.getIntrinsicWidth());
        int intrinsicHeight = Math.max(1, icon.getIntrinsicHeight());
        float target = diameter * (forceCircularIcons ? 1.16f : 0.94f);
        float scale = forceCircularIcons
                ? Math.max(target / intrinsicWidth, target / intrinsicHeight)
                : Math.min(target / intrinsicWidth, target / intrinsicHeight);
        int width = Math.round(intrinsicWidth * scale);
        int height = Math.round(intrinsicHeight * scale);
        icon.setBounds(Math.round(cx - width / 2f), Math.round(cy - height / 2f),
                Math.round(cx + width / 2f), Math.round(cy + height / 2f));
        icon.draw(canvas);
    }

    private void drawCross(Canvas canvas, float cx, float cy) {
        float arm = dp(5);
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint);
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, crossPaint);
    }

    /**
     * 锁形：上方的锁梁（半圆环）+ 下方的锁体（圆角矩形）。
     *
     * <p>尺寸对齐关闭叉的视觉重量（叉臂 ±5dp），不额外加粗——列表里每行都已经有
     * 图标、⋮、叉三组元素，锁的职责是"可辨认"而不是"抢眼"。
     *
     * @param cx 锁的中心横坐标（= {@link #lockX}）
     */
    private void drawLock(Canvas canvas, float cx, float cy) {
        // 锁体：宽 11dp、高 9dp，落在中心线以下；圆角 2dp。
        float bodyHalfWidth = dp(5.5f);
        float bodyTop = cy + dp(0.5f);
        float bodyBottom = cy + dp(9.5f);
        rect.set(cx - bodyHalfWidth, bodyTop, cx + bodyHalfWidth, bodyBottom);
        canvas.drawRoundRect(rect, dp(2f), dp(2f), lockPaint);
        // 锁梁：半径 3.6dp 的半圆，圆心落在锁体上边缘，画 180°~360°（上半圈）。
        float shackleRadius = dp(3.6f);
        rect.set(cx - shackleRadius, bodyTop - shackleRadius * 2f,
                cx + shackleRadius, bodyTop);
        canvas.drawArc(rect, 180f, 180f, false, lockPaint);
    }

    /** 命中行号；不在面板行区域内返回 -1。 */
    private int rowAt(float x, float y) {
        if (x < panelLeft || x > panelLeft + panelWidth) return -1;
        int rows = rowCount();
        for (int i = 0; i < rows; i++) {
            if (Math.abs(y - rowCy(i)) <= rowPitch / 2f) return i;
        }
        return -1;
    }

    private void toggleExpand(int index) {
        tickHaptic();
        if (expandedRow == index) {
            collapseExpand();
            return;
        }
        expandedRow = index;
        resetMenuProgress();
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
        // 面板底板：真实投影（在 panelPaint 上）+ 白色圆角矩形 + 描边
        float panelBottom = panelTop + panelHeight;
        rect.set(panelLeft, panelTop, panelLeft + panelWidth, panelBottom);
        canvas.drawRoundRect(rect, dp(CARD_RADIUS_DP), dp(CARD_RADIUS_DP), panelPaint);
        canvas.drawRoundRect(rect, dp(CARD_RADIUS_DP), dp(CARD_RADIUS_DP), panelStroke);
        for (int i = 0; i < rows; i++) {
            float cy = rowCy(i);
            // 按压 / 展开中：系统灰高亮，不用彩色底 —— 这是"选中态不刺眼"的关键。
            if (i == pressedRow || i == expandedRow) {
                float inset = dp(6);
                rect.set(panelLeft + inset, cy - rowPitch / 2f + dp(3),
                        panelLeft + panelWidth - inset, cy + rowPitch / 2f - dp(3));
                canvas.drawRoundRect(rect, dp(10), dp(10), rowFillPaint);
            }
            for (int dot = -1; dot <= 1; dot++) {
                canvas.drawCircle(panelLeft + dotX, cy + dot * dp(7), dp(1.8f), dotPaint);
            }
            RecentTaskPreview task = tasks.get(i);
            Drawable icon = task.icon;
            if (icon != null) {
                int saveCount = canvas.save();
                if (forceCircularIcons) {
                    clipPath.reset();
                    clipPath.addCircle(panelLeft + iconCx, cy, iconRadius, Path.Direction.CW);
                    canvas.clipPath(clipPath);
                }
                drawFittedIcon(canvas, icon, panelLeft + iconCx, cy, iconRadius * 2f);
                canvas.restoreToCount(saveCount);
            }
            CharSequence label = task.label != null ? task.label : task.packageName;
            CharSequence fitted = TextUtils.ellipsize(label, namePaint,
                    nameEnd - nameStart - dp(14), TextUtils.TruncateAt.END);
            canvas.drawText(fitted, 0, fitted.length(),
                    panelLeft + nameStart, cy + dp(5), namePaint);
            drawCross(canvas, panelLeft + crossX, cy);
            // 锁形只在被锁定该行出现：没锁的行不画占位，列表才不显杂乱。
            if (lockedApps != null && lockedApps.isLocked(task.packageName)) {
                drawLock(canvas, panelLeft + lockX, cy);
            }
            // 分隔线画到 nameEnd 为止（= 锁区左缘再内缩 10dp）：锁与叉是行尾动作区，
            // 线画到它们下面会把两个动作图标串成一排，动作区就糊了。
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
        // 跟手收缩：菜单高度按进度收，菜单项超出收口的部分不画 —— 视觉上就是
        // 手指把菜单"推"了回去，而不是整块一起消失。
        float cardBottomNow = cardBottom - (cardBottom - cardTop) * menuCollapseProgress;
        rect.set(cardLeft, cardTop, cardRight, cardBottomNow);
        // 只铺底、不描边：层级靠"底色差一级 + 投影"，描边在灰底上只会糊成一圈脏边。
        canvas.drawRoundRect(rect, dp(CARD_RADIUS_DP), dp(CARD_RADIUS_DP), menuPaint);
        List<MenuEntry> menu = buildMenu();
        float textX = cardLeft + dp(16);
        for (int i = 0; i < menu.size(); i++) {
            float baseline = cardTop + dp(31) + i * dp(45);
            if (baseline > cardBottomNow - dp(6)) break;
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
