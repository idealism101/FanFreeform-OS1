package com.oxohang.fanfreeform.xposed;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.oxohang.fanfreeform.config.ConfigContract;
import com.oxohang.fanfreeform.config.AppTarget;
import com.oxohang.fanfreeform.config.PressureGesturePolicy;
import com.oxohang.fanfreeform.config.PressureHapticFeedback;
import com.oxohang.fanfreeform.config.PressurePulseRecognizer;
import com.oxohang.fanfreeform.config.PressureTrigger;
import com.oxohang.fanfreeform.config.ShortcutIconLoader;
import com.oxohang.fanfreeform.rom.BridgeResult;
import com.oxohang.fanfreeform.rom.InputSource;
import com.oxohang.fanfreeform.rom.RomBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

final class FanRuntime {
    private static final float BOTTOM_HONEYCOMB_SETTLE_DP = 8f;
    private static final float BOTTOM_HONEYCOMB_FAN_GAP_DP = 10f;
    /** 列表态下，判定"底部上划"的起始热区高度：距屏幕底边该距离内算起点在底部。 */
    private static final float BOTTOM_SWIPE_START_ZONE_DP = 96f;
    /** 列表态下，纵向位移超过该距离且明显大于横向，判定为一次底部上划。 */
    private static final float BOTTOM_SWIPE_MIN_DISTANCE_DP = 48f;
    /**
     * 侧边返回手势的跟手行程（dp）：手指从边缘往里滑到这么远时，让位进度满格。
     *
     * <p>这是<b>手势阈值</b>，不是面板布局参数 —— 面板只接收 0..1 的进度，
     * 不关心手指实际滑了多远。因此本常量归编排层，与 {@code BOTTOM_SWIPE_*} 同处。
     * 只用于视觉跟手，不参与"返回是否触发"的判定（判定看 {@code ACTION_CANCEL}）。
     */
    private static final float BACK_YIELD_TRAVEL_DP = 72f;
    /**
     * 前台任务看护的轮询间隔（毫秒）。
     *
     * <p>只在列表窗口在场期间运行，量很小；200ms 足以在手势停顿前后捕捉到
     * 前台切换，又不会给 SystemUI 主线程造成可见负担。
     * 用系统查询（{@code getRecentTasks}）而不是自建 hook，属于"系统级兜底"：
     * 若后续确认 shell 有可靠的前台过渡回调，可替换为原生回调，轮询退为备份。
     */
    private static final long FOREGROUND_WATCH_INTERVAL_MS = 200L;
    /** 连续读到同一个新前台任务多少次才认定切换完成（避开切换瞬间的抖动）。 */
    private static final int FOREGROUND_WATCH_CONFIRM_HITS = 2;
    private static final long PRESSURE_CANDIDATE_TIMEOUT_MS = 5000L;
    private static final long PRESSURE_SELECTION_SENSOR_IDLE_MS = 180L;
    private static final long PRESSURE_CALIBRATION_INACTIVITY_TIMEOUT_MS = 120_000L;
    private static final long SYSTEM_RECENTS_FAST_WATCH_MS = 5000L;
    private static final long SYSTEM_RECENTS_SLOW_WATCH_MS = 120_000L;
    private static final long SYSTEM_RECENTS_IDLE_WATCH_MS = 10_000L;
    private static final long SYSTEM_RECENTS_MAX_WATCH_MS = 10L * 60L * 1000L;
    private enum State {
        IDLE, ARMED, SIDE_ARMED, CLAIMED, ACTIVE, HONEYCOMB,
        TASKS_LOADING, TASKS, CANCELLED
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler configHandler;
    private final FanOverlayController overlay;
    private final HoneycombOverlayController honeycombOverlay;
    private final PressureDebugOverlayController pressureDebugOverlay;
    private final TaskSwitcherOverlayController taskSwitcherOverlay;
    private final RecentTaskRepository recentTaskRepository;
    /**
     * 应用锁定状态源：读 {@code Settings.System("locked_apps")}（系统权威源头，
     * 由 system_server 的 ProcessManagerService 维护）。进程级单例 —— 跨多次列表呼出复用，
     * 不跟着视图创建销毁。
     */
    private final LockedAppStore lockedApps;
    private final SystemRecentsLauncher systemRecentsLauncher;
    private final FullscreenAppLauncher fullscreenLauncher;
    private final FanTriggerCapture triggerCapture;
    private final OutsideTouchCapture outsideCapture;
    private final HyperOsFreeformBridge freeform;
    private final OutsideGestureRecognizer outsideGestures;
    private final GestureArbitrator gestureArbitrator = new GestureArbitrator();
    private final SideGestureArbitrator sideGestureArbitrator = new SideGestureArbitrator();
    private final GestureReplayGuard gestureReplayGuard = new GestureReplayGuard();
    private final HoneycombGestureState honeycombGestureState = new HoneycombGestureState();
    private final InputSourceCoordinator inputSources = new InputSourceCoordinator();
    // 原生输入通道"挂上 ≠ 在收事件"。framework gesture monitor 注册成功后，
    // inputflinger 侧可能要到下一帧才真正投递（实测 SystemUI 刚重启时偶发数分钟空窗）。
    // 这里用一个可重置的看护：挂上后 CHANNEL_VERIFY_TIMEOUT_MS 内若一条 MotionEvent 都没来，
    // 判定为"挂名通道"，主动降级回 corner fallback 并请求重建，避免用户对着不响应的手势干瞪眼。
    private static final long CHANNEL_VERIFY_TIMEOUT_MS = 8000L;
    private final Runnable channelVerifyTimeout = this::onChannelVerifyTimeout;
    private boolean channelVerifyPending;
    /** 上次已处理的"用户请求重连输入通道"时间戳，用于区分"新请求"与"同一请求重复送达"。 */
    private long lastInputReconnectAt;
    private final float density;
    private final float directionDecisionDistance;
    private final float cornerTapSlop;
    private final long cornerTapTimeoutMs;
    private final float sideDirectionSlop;
    private final float sideVerticalFloor;
    private final Rect cachedDisplayBounds = new Rect();
    private Insets cachedDisplaySafeInsets = Insets.NONE;
    private Insets cachedSideGestureReserves = Insets.NONE;
    private Insets cachedSystemSideGestureInsets = Insets.NONE;
    private int cachedStatusBarGestureHeight;
    private volatile GestureConfig config = GestureConfig.defaults();
    private volatile List<RuntimeTarget> targets = Collections.emptyList();
    private volatile List<RuntimeTarget> honeycombTargets = Collections.emptyList();
    private volatile List<RuntimeTarget> sideTargets = Collections.emptyList();
    private volatile List<RuntimeTarget> pressureTargets = Collections.emptyList();
    private volatile Map<Integer, RuntimeTarget> pressureActionTargets = Collections.emptyMap();
    private List<RuntimeTarget> activeTargets = Collections.emptyList();
    /** 列表打开这一刻的前台任务 id（分屏填充对面 stage 用；必须快照，不能启动时再读）。 */
    private int foregroundTaskIdAtListOpen = -1;
    /** 列表态下的底部上划检测：起点 y / 起点 x / 是否已进入上划候选。 */
    private float bottomSwipeDownY;
    private float bottomSwipeDownX;
    private boolean bottomSwipeArmed;
    /** 侧边返回手势跟手让位：手势所在边 / 起点 x / 已达成的最大进度。 */
    private GestureGeometry.Corner backYieldSide;
    private float backYieldDownX;
    private float backYieldProgress;
    /**
     * 前台任务看护：列表窗口在场期间，盯住系统前台任务是否被换掉
     * （从别处跳转应用/回桌面/通知进入），一旦变化立刻让列表退场。
     *
     * <p>这是「列表该关而没关」的第五条通路 —— 前四条（底部上划 / 开小窗 /
     * 系统面板 / 点卡片启动）都各自绑定了具体动作，唯独"用户从旁路切走了
     * 前台应用"没有任何信号，列表会一直浮在新应用之上。
     */
    private int foregroundWatchTaskId = -1;
    private boolean foregroundWatchActive;
    private int foregroundWatchPendingId = -1;
    private int foregroundWatchPendingHits;
    private final Runnable foregroundWatchTick = this::runForegroundWatch;
    /** 当前列表承载的任务集合，供「全部关闭」使用。 */
    private volatile List<RecentTaskPreview> activeRecentTasks;
    /** 第三档卡片列表的宿主是模块进程的 Activity，此处只记录它是否正在前台。 */
    private volatile boolean taskListActivityShown;
    /** 弹出视图请求的目标包（小窗启动是异步的，链路不依赖列表窗口存活）。 */
    private volatile String pendingFreeformPackage;
    /** SoSc 分屏控制器（系统原生 com.android.wm.shell.sosc.SoScSplitScreenController）。 */
    private Object soScSplitController;
    /** SoSc stage 协调器，用于运行期读 side stage 物理位置。 */
    private Object soScStageCoordinator;
    private State state = State.IDLE;
    private volatile int configRetryCount;
    private volatile boolean configRetryScheduled;
    private volatile boolean configLoadQueued;
    private volatile boolean configReloadPending;
    private volatile boolean cornerFallbackAvailable;
    private long lastOnDemandConfigReload;
    private GestureGeometry.Corner corner;
    private float downX;
    private float downY;
    private int selected = -1;
    private boolean startedWithTriggerCapture;
    private float capturedCornerTapMaxDistance;
    private long capturedCornerTapDownTime;
    private boolean cornerTapIsOutsideWindow;
    private boolean sideTapIsOutsideWindow;
    private boolean sideBackRecognized;
    private boolean sideSequenceCaptured;
    private boolean sideListAllowed;
    private boolean imeDismissTap;
    private boolean capturedImeDismissTap;
    private long ignoredCaptureDownTime = -1L;
    private long ignoredPassthroughDownTime = -1L;
    private long systemPanelInputDownTime = -1L;
    private boolean activeSideList;
    private boolean activeSideFanList;
    private boolean activeSideRingList;
    private float sideListCenterX;
    private float sideListTop;
    private float sideRowHeight;
    private float sideIconDiameter;
    private float sideListHitWidth;
    private float sideListActivationX;
    private float sideReverseCancelDistance;
    private float sideFanRadius;
    private float sideFanCenterY;
    private float sideRingRadius;
    private float sideRingCenterX;
    private float sideRingCenterY;
    private boolean sideListEntered;
    private boolean sideHoldScheduled;
    private float sideHoldAnchorX;
    private float sideHoldAnchorY;
    private float sideHoldCurrentX;
    private float sideHoldCurrentY;
    private Object sideHoldInputMonitor;
    private Handler sideHoldHandler;
    private final Runnable sideHoldTrigger = this::triggerHeldSideList;
    private final SensorManager pressureSensorManager;
    private final Sensor pressureSensor;
    private final HandlerThread pressureSensorThread;
    private final Handler pressureSensorHandler;
    private final Object pressureSampleLock = new Object();
    private float pendingPressureSample;
    private boolean pressureSampleDispatchScheduled;
    private final Runnable dispatchPendingPressureSample = this::dispatchPendingPressureSample;
    private boolean pressureSensorListening;
    private boolean pressureSensorHighRate;
    private int pressureSensorMaxDelayUs = 31250;
    private boolean pressureHasSample;
    private float pressureLatestValue;
    private boolean pressureCandidate;
    private boolean pressureClaimed;
    private boolean pressureOrbShown;
    private boolean pressureOrbSuppressedForMotion;
    private boolean pressureHoneycombActive;
    private boolean pressureHoneycombSessionActive;
    private boolean pressureHoneycombSelectionMoved;
    private boolean pressureCircularActive;
    private boolean pressureOverlayCaptureSuppressed;
    private PressureTrigger activePressureTrigger;
    private PressureTrigger pressureSelectionTrigger;
    private PressureTrigger pressureCalibrationTrigger;
    private float bottomSelectionPressureBaseline;
    private boolean bottomPressureBaselinePending;
    private boolean bottomSelectionPressureArmed;
    private boolean bottomSelectionPressureConsumed;
    private RuntimeTarget bottomHoneycombSelectedTarget;
    private boolean bottomHoneycombPressureArmed;
    private boolean bottomHoneycombPressureConsumed;
    private float pressureSelectionPressureBaseline;
    private boolean pressureSelectionPressureBaselinePending;
    private RuntimeTarget pressureSelectionPressureTarget;
    private boolean pressureSelectionPressureArmed;
    private boolean pressureSelectionPressureConsumed;
    private float pressureDownX;
    private float pressureDownY;
    private float pressureCurrentX;
    private float pressureCurrentY;
    private boolean pressureInputFromCornerCapture;
    private float pressureBaseline;
    /** The sensor is on-demand, so the first sample after touch must become the baseline. */
    private boolean pressureBaselinePending;
    private Object pressureInputMonitor;
    private final PressurePulseRecognizer pressurePulseRecognizer =
            new PressurePulseRecognizer();
    private final Runnable pressureCandidateTimeout = this::onPressureCandidateTimeout;
    private final Runnable pressureSelectionSensorStop = this::stopIdlePressureSelectionSensor;
    private boolean pressureCalibrationPressActive;
    private boolean pressureCalibrationMovedOutside;
    private float pressureCalibrationBaseline;
    private float pressureCalibrationPeakDelta;
    private int pressureCalibrationAttempts;
    private final List<Float> pressureCalibrationDeltas = new ArrayList<>();
    private boolean pressureCalibrationTimedOut;
    private final Runnable pressureCalibrationTimeout = this::onPressureCalibrationTimeout;
    private final SensorEventListener pressureSensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_PRESSURE || event.values.length == 0) {
                return;
            }
            float value = event.values[0];
            if (!Float.isFinite(value)) return;
            postPressureSample(value);
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    /** Sensor callbacks arrive off the SystemUI looper; keep them out of the touch frame path. */
    private void postPressureSample(float value) {
        synchronized (pressureSampleLock) {
            pendingPressureSample = value;
            if (pressureSampleDispatchScheduled) return;
            pressureSampleDispatchScheduled = true;
        }
        if (!mainHandler.post(dispatchPendingPressureSample)) {
            synchronized (pressureSampleLock) {
                pressureSampleDispatchScheduled = false;
            }
        }
    }

    /** Coalesces pressure samples so a busy SystemUI main looper only sees the newest value. */
    private void dispatchPendingPressureSample() {
        float value;
        synchronized (pressureSampleLock) {
            value = pendingPressureSample;
            pressureSampleDispatchScheduled = false;
        }
        if (!pressureSensorListening) return;
        handlePressureSample(value);
    }

    private void handlePressureSample(float value) {
        pressureLatestValue = value;
        pressureHasSample = true;
        if (pressureCalibrationPressActive && !pressureCalibrationMovedOutside) {
            pressureCalibrationPeakDelta = Math.max(pressureCalibrationPeakDelta,
                    PressureGesturePolicy.positiveDelta(pressureCalibrationBaseline, value));
        }
        if (pressureCandidate) {
            if (pressureBaselinePending) {
                pressureBaseline = value;
                pressureBaselinePending = false;
                pressureDebugOverlay.setOrbIntensity(0f);
                Log.i("Pressure baseline captured after touch=" + pressureBaseline);
            } else {
                float delta = PressureGesturePolicy.positiveDelta(pressureBaseline, value);
                float normalized = config.pressureThreshold > 0f
                        ? Math.min(1f, delta / config.pressureThreshold) : 0f;
                if (config.pressureThemeEnabled && !pressureOrbSuppressedForMotion
                        && config.pressureThreshold > 0f
                        && delta >= Math.max(0.08f, config.pressureThreshold * 0.12f)) {
                    if (!pressureOrbShown) {
                        pressureDebugOverlay.showOrb(activePressureTrigger.centerXPercent,
                                activePressureTrigger.centerYPercent,
                                activePressureTrigger.radiusPercent,
                                config.pressureShowPosition, config.pressureOrbTheme,
                                config.pressureOrbSizePercent);
                        pressureOrbShown = true;
                    }
                    pressureDebugOverlay.setOrbIntensity(normalized);
                }
                PressurePulseRecognizer.Signal signal = pressurePulseRecognizer.onPressure(
                        delta, config.pressureThreshold,
                        android.os.SystemClock.uptimeMillis());
                handlePressureSignal(signal, true, true);
            }
        }
        if (bottomPressureBaselinePending) {
            bottomSelectionPressureBaseline = value;
            bottomPressureBaselinePending = false;
        }
        if (bottomSelectionPressureArmed && !bottomSelectionPressureConsumed
                && state == State.ACTIVE && corner != null
                && !activeSideList && !pressureCircularActive
                && selected >= 0 && config.pressureThreshold > 0f) {
            float delta = PressureGesturePolicy.positiveDelta(
                    bottomSelectionPressureBaseline, value);
            if (delta >= config.pressureThreshold) {
                triggerBottomSelectionPressure();
            }
        }
        if (bottomHoneycombPressureArmed && !bottomHoneycombPressureConsumed
                && state == State.HONEYCOMB && corner != null
                && bottomHoneycombSelectedTarget != null
                && config.pressureThreshold > 0f) {
            float delta = PressureGesturePolicy.positiveDelta(
                    bottomSelectionPressureBaseline, value);
            if (delta >= config.pressureThreshold) {
                triggerBottomHoneycombPressure();
            }
        }
        if (pressureSelectionPressureArmed && !pressureSelectionPressureConsumed
                && pressureSelectionIsActive() && pressureSelectionPressureTarget != null
                && config.pressureThreshold > 0f) {
            if (pressureSelectionPressureBaselinePending) {
                pressureSelectionPressureBaseline = value;
                pressureSelectionPressureBaselinePending = false;
            } else {
                float delta = PressureGesturePolicy.positiveDelta(
                        pressureSelectionPressureBaseline, value);
                if (delta >= config.pressureThreshold) {
                    triggerPressureSelectionPressure();
                }
            }
        }
    }
    private boolean bottomHoneycombSettleScheduled;
    private float bottomHoneycombSettleAnchorX;
    private float bottomHoneycombSettleAnchorY;
    private float bottomHoneycombCurrentX;
    private float bottomHoneycombCurrentY;
    private int bottomHoneycombDisplayWidth;
    private int bottomHoneycombDisplayHeight;
    private Handler bottomHoneycombSettleHandler;
    private final Runnable bottomHoneycombSettleTrigger = this::triggerSettledBottomHoneycomb;
    private volatile boolean wheelSessionActive;
    private volatile boolean imeVisible;
    private volatile int imeHeight;
    private volatile boolean shadeExpanded;
    private volatile boolean controlCenterExpanded;
    private long shadeStateVersion;
    private long controlCenterStateVersion;
    private long authoritativePanelStateVersion;
    private volatile long systemPanelTouchBlockUntil;
    private int lastHapticSelection = -1;
    private GestureGeometry.FanLayout cachedFanLayout;
    private float activeHoneycombReturnThreshold;
    private boolean geometryRefreshQueued;
    private long lastGeometryRefreshUptime;
    private boolean outsideCaptureWindowTransitionBlocked;
    private long outsideCaptureWindowTransitionGeneration;
    private long systemRecentsWatchVersion;
    private int taskLoadGeneration;
    private float taskLoadCurrentX;
    private float taskLoadCurrentY;

    FanRuntime(Context context, ClassLoader classLoader) {
        this.context = context;
        Log.attachReporter(context);
        HandlerThread configThread = new HandlerThread("FanFreeformConfig");
        configThread.start();
        configHandler = new Handler(configThread.getLooper());
        pressureSensorThread = new HandlerThread("FanFreeformPressureSensor");
        pressureSensorThread.start();
        pressureSensorHandler = new Handler(pressureSensorThread.getLooper());
        density = context.getResources().getDisplayMetrics().density;
        windowManager = context.getSystemService(WindowManager.class);
        pressureSensorManager = context.getSystemService(SensorManager.class);
        pressureSensor = pressureSensorManager == null ? null
                : pressureSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressureSensor != null) {
            // 采样周期取传感器支持的最快速率：Sensor.getMaxDelay() 表示最小延迟，
            // 即最快的采样间隔。目标机型（thyme）气压计为 bosch_bmp285，标称
            // 1Hz~25Hz 且为 non-wakeup，因此最快约每 40ms 一个样本。
            // 个别 ROM 会返回 0（能力未知），此时回退到 32Hz；同时用传感器
            // 声明的最慢间隔兜底，避免请求超出硬件能力。
            int fastest = pressureSensor.getMaxDelay();
            if (fastest <= 0) fastest = 31250;
            int slowest = pressureSensor.getMinDelay();
            if (slowest > 0 && fastest > slowest) fastest = slowest;
            pressureSensorMaxDelayUs = fastest;
        }
        refreshDisplayGeometry();
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        cornerTapSlop = viewConfiguration.getScaledTouchSlop();
        cornerTapTimeoutMs = ViewConfiguration.getLongPressTimeout();
        directionDecisionDistance = Math.max(cornerTapSlop, 10 * density);
        sideDirectionSlop = cornerTapSlop;
        sideVerticalFloor = 24 * density;
        overlay = new FanOverlayController(context, mainHandler);
        honeycombOverlay = new HoneycombOverlayController(context, mainHandler);
        pressureDebugOverlay = new PressureDebugOverlayController(context, mainHandler);
        taskSwitcherOverlay = new TaskSwitcherOverlayController(context, mainHandler);
        recentTaskRepository = new RecentTaskRepository(context);
        // userId 反射取（Context.getUserId / UserHandle.myUserId 都是 @hide，
        // 编译期不可见但运行期存在）。locked_apps 是"按 userId 分组"的结构，
        // 必须与系统写的那一组对齐，否则一项都读不到。
        lockedApps = new LockedAppStore(context, LockedAppStore.currentUserId());
        // 启动即探测原生锁定写接口是否可用：日志一眼可见本机走的是
        // ProcessManager.updateApplicationLockedState（原生入口，通知链完整）
        // 还是降级到直接写 Settings（旁路，launcher 缓存不会刷新）。
        Log.i("LockedAppBridge available=" + LockedAppBridge.isAvailable()
                + " userId=" + LockedAppStore.currentUserId());
        systemRecentsLauncher = new SystemRecentsLauncher(context);
        fullscreenLauncher = new FullscreenAppLauncher(context);
        triggerCapture = new FanTriggerCapture(context, mainHandler,
                new FanTriggerCapture.Listener() {
                    @Override public void onTouch(MotionEvent event) {
                        onCornerFallbackTouch(event);
                    }

                    @Override public void onAvailabilityChanged(boolean available) {
                        onCornerFallbackAvailabilityChanged(available);
                    }
                });
        outsideCapture = new OutsideTouchCapture(context, mainHandler,
                this::onCapturedOutsideTouch);
        freeform = new HyperOsFreeformBridge(context, classLoader, mainHandler);
        outsideGestures = new OutsideGestureRecognizer(mainHandler,
                viewConfiguration, density, this::performOutsideAction);
        requestConfigReload();
        context.getContentResolver().registerContentObserver(ConfigContract.RUNTIME_URI, false,
                new ContentObserver(mainHandler) {
                    @Override public void onChange(boolean selfChange) { requestConfigReload(); }
                });
        try {
            IntentFilter lifecycleFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            lifecycleFilter.addAction(Intent.ACTION_USER_PRESENT);
            lifecycleFilter.addAction(Intent.ACTION_SCREEN_ON);
            lifecycleFilter.addAction(Intent.ACTION_SCREEN_OFF);
            lifecycleFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context receiverContext, Intent intent) {
                    mainHandler.post(FanRuntime.this::refreshTriggerCapture);
                    String action = intent.getAction();
                    if (Intent.ACTION_WALLPAPER_CHANGED.equals(action)) {
                        BlurredWallpaperCache.clear();
                    }
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        systemRecentsWatchVersion++;
                        wheelSessionActive = false;
                        mainHandler.post(overlay::removeNow);
                        mainHandler.post(honeycombOverlay::removeNow);
                        mainHandler.post(taskSwitcherOverlay::removeNow);
                        mainHandler.post(pressureDebugOverlay::remove);
                        resetFan(false);
                        stopPressureSensor();
                        outsideCapture.remove();
                    }
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        mainHandler.post(FanRuntime.this::refreshPressureSensor);
                        mainHandler.post(FanRuntime.this::syncPressureDebugOverlay);
                        mainHandler.postDelayed(FanRuntime.this::refreshTriggerCapture, 750L);
                        mainHandler.postDelayed(FanRuntime.this::refreshTriggerCapture, 1500L);
                        mainHandler.postDelayed(FanRuntime.this::refreshOutsideCapture, 750L);
                    }
                    if (!Intent.ACTION_USER_UNLOCKED.equals(action)
                            && !Intent.ACTION_USER_PRESENT.equals(action)) return;
                    configHandler.post(() -> {
                        configRetryCount = 0;
                        configRetryScheduled = false;
                        requestConfigReload();
                        updateInterfaceStatus();
                    });
                }
            }, lifecycleFilter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable error) {
            Log.e("Cannot register user-unlocked configuration reload", error);
        }
        Context callbackContext = this.context;
        if (callbackContext != null) try {
            callbackContext.registerComponentCallbacks(new ComponentCallbacks() {
                @Override public void onConfigurationChanged(Configuration newConfig) {
                    mainHandler.post(() -> {
                        BlurredWallpaperCache.clear();
                        overlay.removeNow();
                        honeycombOverlay.removeNow();
                        taskSwitcherOverlay.removeNow();
                        pressureDebugOverlay.remove();
                        resetFan(false);
                        refreshGeometryAndCaptures("configuration immediate");
                        FanRuntime.this.syncPressureDebugOverlay();
                        mainHandler.post(() -> refreshGeometryAndCaptures(
                                "configuration next frame"));
                        mainHandler.postDelayed(() -> refreshGeometryAndCaptures(
                                "configuration settled"), 180L);
                    });
                }

                @Override public void onLowMemory() { BlurredWallpaperCache.clear(); }
            });
        } catch (Throwable error) {
            // A vendor ContextWrapper can reject callback registration during the
            // SystemUI attach window. Do not let that abort construction after the
            // input/capture workers have already started.
            Log.e("Cannot register SystemUI component callbacks; continuing", error);
        }
        Log.i("Input capability runtime=ready freeform=waiting native_input=waiting"
                + " corner_fallback=waiting");
    }

    void setFreeformController(Object controller) {
        freeform.setController(controller);
        mainHandler.postDelayed(this::refreshOutsideCapture, 120L);
        updateInterfaceStatusAsync();
        Log.i("Input capability freeform=ready native_input="
                + (inputSources.usesNativeInput() ? "ready" : "waiting"));
    }

    boolean beginNativeInputRegistration() {
        boolean started = inputSources.beginNativeRegistration();
        if (started) {
            Log.i("Input capability native_input=switching corner_fallback=inactive");
        }
        return started;
    }

    void completeNativeInputRegistration() {
        inputSources.completeNativeRegistration();
        updateInterfaceStatusAsync();
        mainHandler.post(this::refreshTriggerCapture);
        Log.i("Input capability native_input=ready corner_fallback=inactive");
    }

    void failNativeInputRegistration() {
        inputSources.failNativeRegistration();
        updateInterfaceStatusAsync();
        mainHandler.post(this::refreshTriggerCapture);
        Log.i("Input capability native_input=failed corner_fallback="
                + (cornerFallbackAvailable ? "ready" : "waiting"));
    }

    /**
     * 向框架注册系统手势监听，把全局手势流接进业务层。
     *
     * <p>这条通道与 OS3 的 wm.shell 事件控制器同源：框架把手势事件投递到本进程，
     * 同时返回一个 {@code InputMonitor}，业务层拿到它就能 {@code pilferPointers()}，
     * 从系统手势链路里抢走当前手势 —— 与 OS3 上由系统回调附带 InputMonitor 的效果一致。
     *
     * <p>已经由 OS3 事件控制器接管时直接跳过，避免同一手势被两条通道重复投递。
     * 注册失败不静默降级：如实登记失败并保留自建兜底窗口，原因写进日志。
     */
    void attachSystemGestureMonitor(Context hostContext) {
        if (hostContext == null) return;
        if (usesNativeInput()) return;
        if (!beginNativeInputRegistration()) return;
        InputSource source = RomBridge.get().input();
        BridgeResult<Void> started = source.start(hostContext, event -> {
            if (!(event instanceof MotionEvent)) return;
            onMotion((MotionEvent) event, source.monitorHandle());
        });
        if (started.isOk()) {
            completeNativeInputRegistration();
            beginChannelVerification();
            Log.i("System gesture monitor attached kind=" + source.kind()
                    + " monitor=" + (source.monitorHandle() != null));
        } else {
            failNativeInputRegistration();
            Log.e("System gesture monitor unavailable code=" + started.code()
                    + " detail=" + started.detail(), new IllegalStateException(
                    "framework gesture monitor registration failed"));
        }
    }

    void reportNativeInputMissing() {
        if (inputSources.usesNativeInput()) return;
        updateInterfaceStatusAsync();
        Log.i("Input capability native_input=missing corner_fallback="
                + (cornerFallbackAvailable ? "ready" : "waiting"));
    }

    /**
     * 原生通道挂载成功后开启"是否真的在收事件"观察窗。
     *
     * <p>背景：{@code start()} 返回成功只代表 InputMonitor 注册动作被接受，
     * 不代表 inputflinger 已在往本进程投递。实测 SystemUI 刚重建时出现过
     * 注册成功但数分钟无任何手势日志的情况（用户表现为"底角滑轮和任务列表都打不开"）。
     *
     * <p>做法：起一个观察任务，期间只要来一条 MotionEvent 就撤销并记一条
     * {@code verified alive}；窗口内无事件则记一条 {@code silent=1} 供排查对时。
     * <b>只记录，不自动改行为</b>——理由见 {@link #onChannelVerifyTimeout()}。
     */
    private void beginChannelVerification() {
        channelVerifyPending = true;
        mainHandler.removeCallbacks(channelVerifyTimeout);
        mainHandler.postDelayed(channelVerifyTimeout, CHANNEL_VERIFY_TIMEOUT_MS);
    }

    private void confirmChannelAlive() {
        if (!channelVerifyPending) return;
        channelVerifyPending = false;
        mainHandler.removeCallbacks(channelVerifyTimeout);
        Log.i("Native input channel verified alive");
    }

    private void onChannelVerifyTimeout() {
        if (!channelVerifyPending) return;
        channelVerifyPending = false;
        if (!inputSources.usesNativeInput()) return;
        // 观察窗口内一条事件都没收到。**只诊断，不自动改行为**：
        // 窗口内无事件也可能是"用户根本没碰屏幕"，模块无法从通道侧区分
        // "通道坏了"与"用户没操作"。据此自动重挂会把健康的原生通道反复重建，
        // 甚至误降级 —— 那才是真回归。这里只留一条可检索证据（silent=1）供对时。
        // 依据见 2026-10-02 记忆：该现象未能复现，代码路径三次实测均 6~13 秒内正常。
        Log.i("Native input channel accepted but no MotionEvent observed within "
                + CHANNEL_VERIFY_TIMEOUT_MS + "ms silent=1 (diagnostic only, no action)");
    }

    private void onCornerFallbackTouch(MotionEvent event) {
        if (!inputSources.shouldDispatchCornerFallback()) return;
        onMotion(event, null);
    }

    private void onCornerFallbackAvailabilityChanged(boolean available) {
        cornerFallbackAvailable = available;
        updateInterfaceStatusAsync();
        Log.i("Input capability corner_fallback="
                + (inputSources.shouldDispatchCornerFallback()
                ? (available ? "ready" : "unavailable") : "inactive")
                + " native_input=" + (inputSources.usesNativeInput() ? "ready" : "missing"));
    }

    void launchExternalRequest(String packageName, String componentName, String intentUri) {
        if (packageName == null || packageName.trim().isEmpty()) {
            Log.i("Rejected external launch without package");
            return;
        }
        mainHandler.post(() -> {
            try {
                Intent launch = buildExternalLaunchIntent(packageName.trim(), componentName,
                        intentUri);
                if (launch == null) return;
                boolean launched = freeform.launchExternalIntent(packageName.trim(), launch,
                        config, this::refreshOutsideCapture);
                if (launched) {
                    refreshOutsideCaptureAfterLaunch();
                } else {
                    Log.i("External launch was not armed package=" + packageName);
                }
            } catch (Throwable error) {
                Log.e("External launch request failed package=" + packageName, error);
            }
        });
    }

    private Intent buildExternalLaunchIntent(String packageName, String componentName,
                                             String intentUri) {
        Intent launch;
        if (intentUri != null && !intentUri.trim().isEmpty()) {
            String raw = intentUri.trim();
            try {
                launch = Intent.parseUri(raw, 0);
            } catch (Throwable firstError) {
                try {
                    launch = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
                } catch (Throwable secondError) {
                    try {
                        launch = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
                    } catch (Throwable ignored) {
                        Log.e("Cannot parse external shortcut URI " + raw, firstError);
                        return null;
                    }
                }
            }
        } else {
            ComponentName component = componentName == null ? null
                    : ComponentName.unflattenFromString(componentName);
            if (component == null) {
                Intent launcher = context.getPackageManager()
                        .getLaunchIntentForPackage(packageName);
                if (launcher == null) {
                    Log.i("No launcher activity for external package=" + packageName);
                    return null;
                }
                launch = launcher;
            } else {
                launch = Intent.makeMainActivity(component);
            }
        }
        ComponentName requested = launch.getComponent();
        if (requested != null && !packageName.equals(requested.getPackageName())) {
            Log.i("Rejected external intent package mismatch expected=" + packageName
                    + " actual=" + requested.getPackageName());
            return null;
        }
        if (launch.getComponent() == null && launch.getPackage() == null) {
            launch.setPackage(packageName);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return launch;
    }

    void onTaskAppeared(int taskId) {
        freeform.onTaskAppeared(taskId);
        // 小窗（freeform）出现 = 用户主动要了一个新容器，属于「让位」：
        // 列表有序淡出并缩小，把屏幕交给小窗，而不是凭空消失。
        // 撤窗仍由本进程完成，不走系统 task 过渡（过渡期卡顿的根源）。
        dismissTaskSurfaceForForegroundChange("freeform task appeared",
                TaskPanelExit.YIELD);
        mainHandler.postDelayed(this::refreshOutsideCapture, 60L);
    }

    void onTaskInfo(Object info) {
        freeform.onTaskInfo(info);
        releaseOutsideCaptureTransitionIfSettled();
        refreshOutsideCapture();
        // HyperOS reports the normal state before the mini-to-freeform animation has
        // committed its final bounds. Re-read the live bounds while that animation settles.
        mainHandler.postDelayed(this::refreshOutsideCapture, 100L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 320L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 760L);
        if (freeform.trackedTaskId() < 0 && freeform.pendingLaunchBounds() == null) {
            outsideGestures.clearAll();
        }
    }

    void onTaskVanished(int taskId) {
        freeform.onTaskVanished(taskId);
        releaseOutsideCaptureTransitionIfSettled();
        refreshOutsideCapture();
        if (freeform.trackedTaskId() < 0 && freeform.pendingLaunchBounds() == null) {
            outsideGestures.clearAll();
        }
    }

    void onNativeFreeformMotion(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            if (geometryRefreshQueued) return;
            geometryRefreshQueued = true;
            long elapsed = android.os.SystemClock.uptimeMillis() - lastGeometryRefreshUptime;
            mainHandler.postDelayed(() -> {
                geometryRefreshQueued = false;
                lastGeometryRefreshUptime = android.os.SystemClock.uptimeMillis();
                refreshOutsideCapture();
            }, Math.max(0L, 16L - elapsed));
            return;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            refreshOutsideCapture();
            mainHandler.postDelayed(this::refreshOutsideCapture, 32L);
            mainHandler.postDelayed(this::refreshOutsideCapture, 100L);
            mainHandler.postDelayed(this::refreshOutsideCapture, 240L);
            mainHandler.postDelayed(this::refreshOutsideCapture, 500L);
        }
    }

    void onNativeFreeformLeavingInteractiveState(String reason) {
        suspendOutsideCaptureForWindowTransition("native " + reason);
    }

    void onImeVisibilityChanged(boolean visible, int height) {
        imeVisible = visible;
        imeHeight = visible ? Math.max(0, height) : 0;
        cancelPressureGesture();
        syncPressureDebugOverlay();
        if (visible && (state == State.ARMED || state == State.CLAIMED
                || (state == State.ACTIVE && !activeSideList)
                || state == State.HONEYCOMB)) {
            resetFan();
            Log.i("Bottom application gesture cancelled because input method became visible");
        } else if (visible && (activeSideList || state == State.SIDE_ARMED)) {
            resetFan();
            Log.i("Side application gesture cancelled because input method became visible");
        }
        Log.i("IME visibility changed visible=" + visible + " height=" + imeHeight);
        refreshTriggerCapture();
        refreshOutsideCapture();
        mainHandler.postDelayed(this::refreshTriggerCapture, 60L);
        mainHandler.postDelayed(this::refreshTriggerCapture, 180L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 60L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 180L);
        mainHandler.postDelayed(this::syncPressureDebugOverlay, 240L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 420L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 800L);
    }

    void onShadeExpansionChanged(float fraction, boolean expanded) {
        boolean next = expanded || fraction > 0.01f;
        long now = android.os.SystemClock.uptimeMillis();
        if (next && !SystemPanelArbitrator.acceptShadeExpansion(
                shadeExpanded, now, systemPanelTouchBlockUntil)) {
            // Heads-up notifications and Dynamic Island also animate the shade's
            // expansion fraction. Without a real top-edge touch they must not remove
            // the freeform guard or pause an active honeycomb session.
            Log.i("Ignored notification-driven shade expansion fraction=" + fraction);
            return;
        }
        long version = ++shadeStateVersion;
        if (next) {
            if (!shadeExpanded) {
                shadeExpanded = true;
                applySystemPanelCaptureState("notification shade");
            } else {
                outsideCapture.remove();
            }
            return;
        }
        mainHandler.postDelayed(() -> {
            if (shadeStateVersion != version || !shadeExpanded) return;
            shadeExpanded = false;
            applySystemPanelCaptureState("notification shade");
        }, 480L);
    }

    void onControlCenterExpansionChanged(boolean expanded) {
        long version = ++controlCenterStateVersion;
        if (expanded) {
            if (!controlCenterExpanded) {
                controlCenterExpanded = true;
                applySystemPanelCaptureState("control center");
            } else {
                outsideCapture.remove();
            }
            return;
        }
        mainHandler.postDelayed(() -> {
            if (controlCenterStateVersion != version || !controlCenterExpanded) return;
            controlCenterExpanded = false;
            applySystemPanelCaptureState("control center");
        }, 620L);
    }

    void onAuthoritativeSystemPanelState(boolean notificationExpanded,
                                         boolean controlExpanded) {
        if (!mainHandler.getLooper().isCurrentThread()) {
            mainHandler.post(() -> onAuthoritativeSystemPanelState(
                    notificationExpanded, controlExpanded));
            return;
        }
        long version = ++authoritativePanelStateVersion;
        ++shadeStateVersion;
        ++controlCenterStateVersion;
        shadeExpanded = notificationExpanded;
        controlCenterExpanded = controlExpanded;
        Log.i("Authoritative system panel state notification=" + notificationExpanded
                + " control=" + controlExpanded);
        if (notificationExpanded || controlExpanded) {
            applySystemPanelCaptureState("authoritative system panel state");
            return;
        }
        mainHandler.postDelayed(() -> {
            // Expansion progress callbacks are noisy and can arrive after HyperOS has already
            // committed the final collapsed state. Only a newer authoritative state may cancel
            // this restore; stale progress=0 callbacks must not leave the outside layer detached.
            if (authoritativePanelStateVersion != version) return;
            shadeExpanded = false;
            controlCenterExpanded = false;
            systemPanelTouchBlockUntil = 0L;
            applySystemPanelCaptureState("authoritative system panel state");
        }, 220L);
    }

    void onSystemPanelTouchStarted(String source) {
        long until = android.os.SystemClock.uptimeMillis() + 1800L;
        systemPanelTouchBlockUntil = until;
        cancelPressureGesture();
        syncPressureDebugOverlay();
        outsideCapture.remove();
        honeycombOverlay.setPaused(true);
        outsideGestures.clearAll();
        // 用户从顶部下拉通知栏/控制中心，第一信号是"要去做别的事"，但**还不是终局**：
        // 手指按下就撤走列表会导致"轻轻一碰列表就没了"。这里只做「退位」（缩小变暗），
        // 真正展开由 applySystemPanelCaptureState 接管；若最终没展开，下面到点弹回原位。
        setTaskSurfacePaused(true, "system panel touch " + source);
        mainHandler.postDelayed(() -> {
            if (systemPanelTouchBlockUntil != until) return;
            systemPanelTouchBlockUntil = 0L;
            syncPressureDebugOverlay();
            if (!shadeExpanded && !controlCenterExpanded) {
                honeycombOverlay.setPaused(false);
                setTaskSurfacePaused(false, "system panel touch " + source);
                refreshOutsideCapture();
                mainHandler.postDelayed(this::refreshOutsideCapture, 160L);
            }
        }, 1850L);
        Log.i("Outside capture paused at " + source + " touch down");
    }

    /**
     * 前台场景发生变化（小窗出现 / 切走应用 / 上划等）时，按因果撤走任务表面。
     *
     * <p>这是复刻传送门交互范式的关键一步：**不由系统生命周期代劳，而是本进程
     * 观测到信号后主动撤走**。好处有三个：
     * <ul>
     *   <li>撤窗动作是外加的 {@code removeViewImmediate}，不走系统 task 过渡，
     *       没有过渡期卡顿（上划卡顿的根源正在于此）；</li>
     *   <li>时机由信号决定，不依赖系统把谁提到前台，因此不会出现
     *       "上划后露出的是下面的 App"这种观感问题；</li>
     *   <li>状态归位同步完成（{@link #resetFan(boolean)}），下一次呼出不会残留。</li>
     * </ul>
     *
     * <p>与旧实现唯一的差别是"怎么走"：不再瞬间消失，而是按 {@code exitReason}
     * 播一段与因果相符的出场动画（让位 / 安静让路），动画结束再撤窗。
     * 覆盖档位：视差卡片与第三档卡片列表共用同一表面控制器，一并收起。
     */
    private void dismissTaskSurfaceForForegroundChange(String reason, TaskPanelExit exitReason) {
        // 判据必须落在「窗口是否真的在屏幕上」，不能落在 state：
        // 侧边栏拉出列表后手指抬起即走 ACTION_UP → resetFan(false)，state 会回到 IDLE，
        // 但列表窗口被刻意保留（hideOverlay=false，抬手后可继续点击）。
        // 若此处用 state==TASKS 判定，从那一刻起所有关闭信号都会被判为"无需处理"，
        // 列表变成关不掉的僵尸层 —— 这正是此前「上划/开小窗/下拉都不生效」的根因。
        if (!taskSwitcherOverlay.isAttached()) {
            Log.i("Task surface dismiss ignored (not attached) reason=" + reason
                    + " state=" + state);
            stopForegroundWatch();
            return;
        }
        taskSwitcherOverlay.playExit(exitReason);
        stopForegroundWatch();
        boolean wasTasks = state == State.TASKS;
        // 僵尸态（列表在场但 state 已归位）也要清一遍手势标志，否则残留状态会影响下次呼出。
        resetFan(false);
        Log.i("Task surface dismissed: " + reason + " exit=" + exitReason
                + " wasTasks=" + wasTasks + " state=" + state);
    }

    /**
     * 「暂停」类系统事件（下拉通知栏 / 控制中心）：列表退位但不销毁，
     * 面板收起后原样弹回。
     *
     * <p>判据是"用户有没有要求它消失"：下拉看一眼通知不等于关掉列表，
     * 所以这里只是缩小变暗让出视觉重心。真正的终局（切走应用 / 开小窗）
     * 走 {@link #dismissTaskSurfaceForForegroundChange}。
     */
    private void setTaskSurfacePaused(boolean paused, String reason) {
        if (!taskSwitcherOverlay.isAttached()) return;
        taskSwitcherOverlay.setPaused(paused);
        Log.i("Task surface " + (paused ? "yielded" : "restored") + ": " + reason);
    }

    /**
     * 启动前台任务看护：列表窗口在场期间，周期性比对系统前台任务。
     *
     * <p>解决的是「列表开着时，用户从旁路（桌面/通知/其他 App）切走前台应用，
     * 列表仍浮在新应用之上」——这是底部上划 / 开小窗 / 系统面板 / 点卡片
     * 四条既有通路都覆盖不到的场景。
     *
     * <p>基准取"列表打开那一刻的前台任务"，用户在列表里的操作（点卡片启动）
     * 会先经 {@link #dismissTaskSurfaceForForegroundChange} 收掉列表并停表，
     * 因此不会误判。
     */
    private void startForegroundWatch() {
        foregroundWatchTaskId = recentTaskRepository.currentForegroundTaskId();
        foregroundWatchPendingId = -1;
        foregroundWatchPendingHits = 0;
        mainHandler.removeCallbacks(foregroundWatchTick);
        if (foregroundWatchTaskId < 0) {
            // 基准读不到就干脆不启动：宁可漏掉一次"从前台切走"的自动关闭
            // （用户仍可用上划/点外部等既有通路），也不能让"首次读到有效值"
            // 被误判成一次切换而把列表错误收掉。
            foregroundWatchActive = false;
            Log.i("Foreground watch skipped: baseline unavailable");
            return;
        }
        foregroundWatchActive = true;
        mainHandler.postDelayed(foregroundWatchTick, FOREGROUND_WATCH_INTERVAL_MS);
        Log.i("Foreground watch started baseline=" + foregroundWatchTaskId);
    }

    private void stopForegroundWatch() {
        if (!foregroundWatchActive) return;
        foregroundWatchActive = false;
        foregroundWatchPendingId = -1;
        foregroundWatchPendingHits = 0;
        mainHandler.removeCallbacks(foregroundWatchTick);
    }

    private void runForegroundWatch() {
        if (!foregroundWatchActive) return;
        // 窗口已经不在了就没有看护的必要（其它通路可能已收掉它）。
        if (!taskSwitcherOverlay.isAttached()) {
            stopForegroundWatch();
            return;
        }
        int current = recentTaskRepository.currentForegroundTaskId();
        if (current < 0 || current == foregroundWatchTaskId) {
            // 读不到或没变：清掉待确认计数，继续观察。
            foregroundWatchPendingId = -1;
            foregroundWatchPendingHits = 0;
        } else if (current == foregroundWatchPendingId) {
            foregroundWatchPendingHits++;
            if (foregroundWatchPendingHits >= FOREGROUND_WATCH_CONFIRM_HITS) {
                Log.i("Foreground task changed " + foregroundWatchTaskId + " -> " + current);
                stopForegroundWatch();
                // 用户真的去了别处：列表「让位」退走，而不是凭空消失。
                dismissTaskSurfaceForForegroundChange("foreground task changed",
                        TaskPanelExit.YIELD);
                return;
            }
        } else {
            // 第一次读到新 id：先记为待确认，避免切换瞬间的抖动误触。
            foregroundWatchPendingId = current;
            foregroundWatchPendingHits = 1;
        }
        mainHandler.postDelayed(foregroundWatchTick, FOREGROUND_WATCH_INTERVAL_MS);
    }

    private void applySystemPanelCaptureState(String source) {
        if (shadeExpanded || controlCenterExpanded) {
            cancelPressureGesture();
            if (pressureHoneycombActive) {
                honeycombOverlay.removeNow();
                resetFan(false);
            }
            syncPressureDebugOverlay();
            outsideCapture.remove();
            honeycombOverlay.setPaused(true);
            outsideGestures.clearAll();
            // 系统面板（下拉通知栏 / 控制中心）展开 = 用户要去做别的事，但**还没让列表消失**：
            // 这里只是「退位」（缩小变暗），时机仍由我们自己掌握，不走系统过渡。
            // 真的切走由前台看护按「让位」收尾。
            setTaskSurfacePaused(true, "system panel " + source);
            Log.i("Outside capture paused while " + source + " is expanded");
        } else {
            // 面板收起：列表弹回原位。旧实现在这里补一刀撤走列表，那是
            // "下拉看一眼通知，列表就没了"这种割裂感的来源。
            // 注意：从面板点了「当前已在前台的那个应用」时 taskId 不变，
            // 前台看护无从判断，列表会照原样弹回 —— 这是刻意的选择，
            // 用户没有要求它消失。（若实测不喜欢，可在这一支改成按 EXIT_YIELD 退场。）
            setTaskSurfacePaused(false, "system panel collapsed " + source);
            syncPressureDebugOverlay();
            honeycombOverlay.setPaused(false);
            refreshOutsideCapture();
            mainHandler.postDelayed(this::refreshOutsideCapture, 90L);
            mainHandler.postDelayed(this::refreshOutsideCapture, 260L);
            Log.i("Outside capture restored after " + source + " collapsed");
        }
    }

    void adjustMiniTargetIfNeeded(int animationType, Object info, Object target) {
        freeform.adjustMiniTargetIfNeeded(animationType, info, target);
    }
    void adjustEdgePinRestoreTarget(Object info, Object target, Object transaction) {
        freeform.adjustEdgePinRestoreTarget(info, target, transaction);
    }

    void setSoScSplitController(Object controller) {
        soScSplitController = controller;
    }

    void setSoScStageCoordinator(Object coordinator) {
        soScStageCoordinator = coordinator;
    }

    boolean stabilizeLandscapeWindowShape(Object info) {
        return freeform.stabilizeLandscapeWindowShape(info);
    }

    boolean suppressLandscapeShapeChange(int taskId, int orientation) {
        return freeform.suppressLandscapeShapeChange(taskId, orientation);
    }

    void prepareShortcutEnterAnimation(Object change, Object taskInfo) {
        freeform.prepareShortcutEnterAnimation(change, taskInfo);
    }

    void onMotion(MotionEvent event, Object inputMonitor) {
        // 收到真实事件即证明输入通道在投递，撤销"挂名通道"自检。
        confirmChannelAlive();
        try {
            handleMotion(event, inputMonitor);
        } catch (Throwable error) {
            resetFan();
            outsideGestures.onCancel();
            Log.e("Gesture event failed safely", error);
        }
    }

    /** 已经接上系统原生输入通道（wm.shell 事件控制器）时为真。 */
    boolean usesNativeInput() {
        return inputSources.usesNativeInput();
    }

    private void handleMotion(MotionEvent event, Object inputMonitor) {
        int action = event.getActionMasked();
        if (triggerCapture.isInjectedEvent(event)) return;
        if (event.getDownTime() == ignoredPassthroughDownTime) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoredPassthroughDownTime = -1L;
            }
            return;
        }
        if (triggerCapture.isPassthroughInProgress()) {
            ignoredPassthroughDownTime = event.getDownTime();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoredPassthroughDownTime = -1L;
            }
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            ensureDisplayGeometryCurrent();
            pressureSelectionTrigger = null;
            bottomSelectionPressureBaseline = pressureLatestValue;
            bottomPressureBaselinePending = false;
            bottomSelectionPressureArmed = false;
            bottomSelectionPressureConsumed = false;
            bottomHoneycombSelectedTarget = null;
            bottomHoneycombPressureArmed = false;
            bottomHoneycombPressureConsumed = false;
            preparePressureSelectionForTouch();
        }
        int width = displayBounds().width();
        int height = displayBounds().height();
        float x = event.getX();
        float y = event.getY();

        updateCapturedCornerTap(event, x, y);
        if (config.pressureCalibrationActive) {
            handlePressureCalibrationMotion(event, width, height);
            return;
        }
        if (pressureCandidate && pressureInteractionStateBlocked()) {
            cancelPressureGesture();
            pressureDebugOverlay.remove();
        }
        if (action != MotionEvent.ACTION_DOWN) updatePressureCandidate(x, y, width, height);
        if (pressureCandidate && action == MotionEvent.ACTION_MOVE
                && GestureGeometry.distance(pressureDownX, pressureDownY, x, y)
                > directionDecisionDistance) {
            suppressPressureOrbForMotion();
        }
        if (pressureCandidate && action == MotionEvent.ACTION_UP) {
            pressurePulseRecognizer.onUp();
            cancelPressureGesture();
        } else if (pressureCandidate && action == MotionEvent.ACTION_CANCEL) {
            cancelPressureGesture();
        }
        if ((bottomSelectionPressureConsumed || bottomHoneycombPressureConsumed)
                && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            bottomSelectionPressureConsumed = false;
            bottomSelectionPressureArmed = false;
            bottomHoneycombPressureConsumed = false;
            bottomHoneycombPressureArmed = false;
            return;
        }
        if (state == State.TASKS_LOADING) {
            taskLoadCurrentX = x;
            taskLoadCurrentY = y;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                taskLoadGeneration++;
                resetFan(false);
                refreshOutsideCapture();
            }
            return;
        }
        if (state == State.TASKS) {
            if (action == MotionEvent.ACTION_MOVE) taskSwitcherOverlay.externalMove(x, y);
            if (action == MotionEvent.ACTION_CANCEL) {
                taskSwitcherOverlay.externalCancel();
                resetFan(false);
            }
            if (action == MotionEvent.ACTION_UP) {
                taskSwitcherOverlay.externalUp(x, y, false);
                resetFan(false);
            }
            return;
        }
        if (state == State.HONEYCOMB) {
            if (action == MotionEvent.ACTION_MOVE) {
                float distance = GestureGeometry.distance(downX, downY, x, y);
                if (pressureHoneycombActive && !pressureHoneycombSelectionMoved) {
                    if (distance <= directionDecisionDistance) return;
                    pressureHoneycombSelectionMoved = true;
                    Log.i("Pressure honeycomb selection unlocked after finger movement");
                }
                if (!pressureHoneycombSessionActive && !pressureHoneycombActive
                        && honeycombGestureState.shouldExit(distance,
                        activeHoneycombReturnThreshold)) {
                    honeycombOverlay.removeNow();
                    state = State.ACTIVE;
                    float selectionRadius = selectionRadius(width, height);
                    float iconDiameter = iconDiameter(selectionRadius);
                    overlay.show(targets, corner, selectionRadius, iconDiameter,
                            config.showSelectedAppName, config.fanShadow,
                            config.fanAnimationsEnabled, config.bottomAnimationSpeed,
                            config.fanRevealAmount, config.fanRotationDegrees,
                            config.fanSelectionScalePercent, config.fanSelectionRing,
                            config.fanLayoutMode, config.fanCustomOuterCount,
                            config.fanCustomMiddleCount, config.fanCustomInnerCount,
                            config.forceCircularIcons);
                    updateSelection(x, y, width, height, selectionRadius, iconDiameter);
                    Log.i("Honeycomb retreated through hysteresis; fan restored");
                    return;
                }
                honeycombOverlay.externalMove(x, y);
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                // A heads-up notification can cancel the monitor's current stream even
                // though the user never opened a system panel. Keep the honeycomb alive;
                // its own full-screen view will accept the next touch immediately.
                honeycombOverlay.externalCancel();
                resetFan(false);
                Log.i("Honeycomb input stream cancelled externally; overlay retained");
                return;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean allowPressureSelection = !pressureHoneycombActive
                        || pressureHoneycombSelectionMoved
                        || GestureGeometry.distance(downX, downY, x, y)
                        > directionDecisionDistance;
                if (pressureHoneycombActive && allowPressureSelection) {
                    pressureHoneycombSelectionMoved = true;
                }
                honeycombOverlay.externalUp(x, y, !allowPressureSelection);
                resetFan(false);
            }
            return;
        }
        // 底部上划检测：列表窗口在场即生效，**不依赖 state**。
        //
        // 为什么不能挂在 state==TASKS 上：侧边栏拉出列表后手指抬起就走 ACTION_UP，
        // 该分支会把 state 归位到 IDLE（列表窗口被刻意保留，抬手后仍可点击）。
        // 若判定依赖 state，抬手之后的所有上划都会落进 IDLE 分支被 1128 行拦掉，
        // 表现就是「上划对列表毫无反应」。
        //
        // 判据保留保守版：起点贴近屏幕底边 + 纵向位移明显大于横向，
        // 避免与横向滑动选择卡片冲突。
        if (taskSwitcherOverlay.isVisible()) {
            if (action == MotionEvent.ACTION_DOWN) {
                bottomSwipeDownY = y;
                bottomSwipeDownX = x;
                bottomSwipeArmed = y >= height - BOTTOM_SWIPE_START_ZONE_DP * density;
            } else if (bottomSwipeArmed && action == MotionEvent.ACTION_MOVE) {
                float dy = bottomSwipeDownY - y;
                float dx = Math.abs(x - bottomSwipeDownX);
                if (dy > BOTTOM_SWIPE_MIN_DISTANCE_DP * density && dy > dx * 1.5f) {
                    bottomSwipeArmed = false;
                    Log.i("Bottom swipe-up dismissed task surface dy=" + (int) dy);
                    // 底部上划是"系统要走了"（回桌面 / 手势导航），列表安静让路：
                    // 只淡出，不缩放不位移，不跟系统导航抢注意力。
                    dismissTaskSurfaceForForegroundChange("bottom swipe-up",
                            TaskPanelExit.QUIET);
                    return;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                bottomSwipeArmed = false;
            }
        } else {
            bottomSwipeArmed = false;
        }
        // 侧边返回手势：列表在场时跟手让路（形态实现见
        // TaskCenterListView#setBackYieldProgress）。
        //
        // 为什么只能"旁听"、绝不能在这里 pilfer：列表窗口是全屏 overlay，
        // 底下应用收不到触摸，系统返回手势靠自己的 InputMonitor 旁听 +
        // pilferPointers() 抢指针来执行。模块若抢，用户的返回就没了。
        //
        // 系统一旦抢走指针，本通道会收到 ACTION_CANCEL —— 这是"返回确实触发了"
        // 的可靠信号，比等前台看护（200ms 一趟 × 2 次确认）快得多，
        // 也让列表退场与系统返回动画同时发生，而不是各演各的。
        //
        // 同样必须放在下面那行 return 之前（与底部上划同理）：
        // 列表可见时触摸会先被那行拦掉，挂不到任何分支。
        if (taskSwitcherOverlay.isVisible()) {
            if (action == MotionEvent.ACTION_DOWN) {
                Insets sides = systemSideGestureInsets();
                boolean inBottomZone = y >= height - BOTTOM_SWIPE_START_ZONE_DP * density;
                backYieldSide = inBottomZone ? null
                        : GestureGeometry.sideAt(x, width, sides.left, sides.right);
                backYieldDownX = x;
                backYieldProgress = 0f;
            } else if (backYieldSide != null && action == MotionEvent.ACTION_MOVE) {
                float inward = backYieldSide == GestureGeometry.Corner.LEFT
                        ? x - backYieldDownX : backYieldDownX - x;
                float p = Math.max(0f, Math.min(1f,
                        inward / (BACK_YIELD_TRAVEL_DP * density)));
                // 只增不减：手指回撤时面板不跟着缩回来，否则来回抖。
                if (p > backYieldProgress) {
                    backYieldProgress = p;
                    taskSwitcherOverlay.setBackYield(p,
                            backYieldSide == GestureGeometry.Corner.LEFT);
                }
            } else if (backYieldSide != null
                    && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                // CANCEL 且确实滑出过一段 = 系统抢走了指针 = 返回已触发 → 让位；
                // UP = 系统没抢 = 没触发返回 → 弹回原位，列表继续留着。
                boolean committed = action == MotionEvent.ACTION_CANCEL
                        && backYieldProgress >= 0.12f;
                float progress = backYieldProgress;
                backYieldSide = null;
                backYieldProgress = 0f;
                if (committed) {
                    Log.i("Back gesture yielded task surface progress="
                            + Math.round(progress * 100f));
                    dismissTaskSurfaceForForegroundChange("back gesture",
                            TaskPanelExit.YIELD);
                    return;
                }
                taskSwitcherOverlay.releaseBackYield();
            }
        } else {
            backYieldSide = null;
            backYieldProgress = 0f;
        }
        if (honeycombOverlay.isVisible() || taskSwitcherOverlay.isVisible()) return;
        if (wheelSessionActive || overlay.isWheelVisible()) return;
        if (action == MotionEvent.ACTION_DOWN && y <= statusBarGestureHeight()) {
            systemPanelInputDownTime = event.getDownTime();
            onSystemPanelTouchStarted("top system panel input");
            Log.i("Top system panel gesture excluded from global input takeover");
            return;
        }
        if (event.getDownTime() == systemPanelInputDownTime) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                systemPanelInputDownTime = -1L;
            }
            return;
        }
        if (systemPanelInputBlocked()) {
            cancelPressureGesture();
            if (state != State.IDLE) resetFan();
            outsideGestures.clearAll();
            return;
        }
        boolean nativeSideEdge = action == MotionEvent.ACTION_DOWN
                && GestureGeometry.sideAt(x, width, systemSideGestureInsets().left,
                systemSideGestureInsets().right) != null;
        if (action == MotionEvent.ACTION_DOWN && outsideCapture.captures(x, y)
                && !nativeSideEdge) {
            ignoredCaptureDownTime = event.getDownTime();
            return;
        }
        if (event.getDownTime() == ignoredCaptureDownTime) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoredCaptureDownTime = -1L;
            }
            return;
        }

        if (state != State.IDLE) {
            boolean replayedDown = action == MotionEvent.ACTION_DOWN
                    && gestureReplayGuard.isRepeatedDown(event.getDownTime());
            boolean staleTerminal = (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL)
                    && gestureReplayGuard.isStaleTerminal(
                    event.getDownTime(), event.getEventTime());
            if (replayedDown || staleTerminal) {
                Log.i("Ignored HyperOS replayed gesture boundary action=" + action
                        + " downTime=" + event.getDownTime()
                        + " eventTime=" + event.getEventTime());
                return;
            }
            gestureReplayGuard.record(event.getDownTime(), event.getEventTime());
        }

        if (action == MotionEvent.ACTION_DOWN) {
            requestOnDemandConfigReload();
            resetFan();
            boolean landscape = width > height;
            boolean outsideEnabled = config.outsideEnabledFor(landscape);
            float hotWidth = width * config.hotWidthPercent / 100f;
            float hotHeight = height * config.hotHeightPercent / 100f;
            Insets systemSides = systemSideGestureInsets();
            GestureGeometry.Corner downCorner = config.bottomEnabledFor(landscape)
                    ? GestureGeometry.cornerAt(x, y, width, height, hotWidth, hotHeight)
                    : null;
            float sideSafeTop = sideSafeTop(height);
            float sideSafeBottom = sideSafeBottom(height, hotHeight);
            GestureGeometry.Corner systemEdgeSide = downCorner == null
                    ? GestureGeometry.sideAt(x, width, systemSides.left, systemSides.right)
                    : null;
            int sideLayoutMode = config.sideLayoutModeFor(landscape);
            boolean sideSourceAvailable = sideLayoutMode
                    == ConfigContract.SIDE_LAYOUT_TASKS
                    || sideLayoutMode == ConfigContract.SIDE_LAYOUT_SYSTEM_RECENTS
                    || (sideLayoutMode == ConfigContract.SIDE_LAYOUT_HONEYCOMB
                    ? config.honeycombEnabledFor(landscape) && !honeycombTargets.isEmpty()
                    : !sideTargets.isEmpty());
            GestureGeometry.Corner listSide = config.sideEnabledFor(landscape)
                    && sideSourceAvailable && downCorner == null
                    ? GestureGeometry.sideAt(x, y, width, systemSides.left,
                    systemSides.right, sideSafeTop, sideSafeBottom, 96 * density)
                    : null;
            GestureGeometry.Corner downSide = listSide;
            if (!inputSources.usesNativeInput() && !triggerCapture.isCapturing()
                    && canStartBottom()) {
                triggerCapture.update(true, config.hotWidthPercent, config.hotHeightPercent,
                        0, 0);
            }
            Rect pendingLaunch = freeform.pendingLaunchBounds();
            Rect tracked = freeform.trackedBounds();
            Rect ime = tracked == null ? null : visibleImeBounds();
            boolean freeformActive = pendingLaunch != null || tracked != null
                    || freeform.hasInteractiveFreeform();
            if (pendingLaunch != null) {
                if (outsideEnabled && GestureGeometry.outsideRegion(x, y,
                        pendingLaunch.left,
                        pendingLaunch.top, pendingLaunch.right, pendingLaunch.bottom)
                        == GestureGeometry.OutsideRegion.OUTSIDE) {
                    Insets reserves = sideGestureReserves();
                    if (outsideGestures.onDown(x, y, pendingLaunch, width,
                            reserves.left, reserves.right, event.getEventTime())) {
                        pilfer(inputMonitor);
                    }
                    Log.i("Startup outside tap armed bounds=" + pendingLaunch);
                }
                return;
            }
            boolean outsideTracked = tracked != null
                    && !insideTrackedOrIme(x, y, tracked, ime);
            boolean imeActive = imeVisible || ime != null;
            if (imeActive || freeformActive) downSide = null;
            PressureTrigger pressureTriggerAtDown = pressureGestureReady()
                    ? pressureTriggerAt(x, y, width, height, false) : null;
            if (pressureTriggerAtDown != null
                    && beginPressureCandidate(x, y, inputMonitor,
                    pressureTriggerAtDown)) {
                boolean capturedByCorner = triggerCapture.isCapturing()
                        && downCorner != null;
                pressureInputFromCornerCapture = capturedByCorner;
                if (capturedByCorner) {
                    if (outsideEnabled && tracked != null && outsideTracked) {
                        Insets reserves = sideGestureReserves();
                        outsideGestures.onDown(x, y, tracked, width,
                                reserves.left, reserves.right, event.getEventTime());
                        imeDismissTap = imeActive;
                    } else {
                        beginCapturedCornerTap(x, y, event.getDownTime());
                    }
                }
                Log.i("Pressure trigger reserved overlapping corner/side gesture area id="
                        + pressureTriggerAtDown.id);
                return;
            }
            if (outsideEnabled && outsideTracked && imeActive) {
                imeDismissTap = true;
                if (systemEdgeSide == null) pilfer(inputMonitor);
            }
            if (outsideEnabled && outsideTracked && systemEdgeSide != null
                    && canStartSide()) {
                Insets reserves = sideGestureReserves();
                outsideGestures.onDown(x, y, tracked, width,
                        reserves.left, reserves.right, event.getEventTime());
                sideTapIsOutsideWindow = true;
                sideSequenceCaptured = false;
                sideListAllowed = !imeActive && !freeformActive && listSide != null;
                armSide(systemEdgeSide, x, y, event.getDownTime(), event.getEventTime());
                Log.i("Captured outside side sequence side=" + systemEdgeSide
                        + " list=" + sideListAllowed);
                return;
            }
            if (downSide != null && canStartSide()) {
                if (outsideTracked) {
                    Insets reserves = sideGestureReserves();
                    outsideGestures.onDown(x, y, tracked, width,
                            reserves.left, reserves.right, event.getEventTime());
                    sideTapIsOutsideWindow = true;
                } else {
                    outsideGestures.onCancel();
                }
                // This path is used on the normal desktop/app surface where no
                // freeform window is being tracked. The side source was already
                // validated when downSide was calculated, so the list is allowed.
                sideListAllowed = listSide != null;
                armSide(downSide, x, y, event.getDownTime(), event.getEventTime());
                Log.i("Side-distance gesture armed side=" + downSide
                        + " threshold=" + config.sideTriggerPercent + "% range="
                        + Math.round(sideSafeTop) + ".." + Math.round(sideSafeBottom));
                return;
            }
            if (tracked != null) {
                if (!outsideTracked) return;
                if (downCorner != null && canStartBottom()) {
                    startPressureSensorForBottomGesture();
                    if (outsideEnabled) {
                        Insets reserves = sideGestureReserves();
                        outsideGestures.onDown(x, y, tracked, width,
                                reserves.left, reserves.right, event.getEventTime());
                    }
                    if (triggerCapture.isCapturing()) {
                        pilfer(inputMonitor);
                        Log.i("Fan input claimed on down through corner capture window");
                    }
                    arm(downCorner, x, y, event.getDownTime(), event.getEventTime());
                    cornerTapIsOutsideWindow = outsideEnabled;
                    Log.i("Fan hot zone armed on down corner=" + downCorner);
                    return;
                }
                if (!outsideEnabled) return;
                Insets reserves = sideGestureReserves();
                if (outsideGestures.onDown(x, y, tracked, width,
                        reserves.left, reserves.right, event.getEventTime())) {
                    pilfer(inputMonitor);
                }
                return;
            }
            if (downCorner != null && canStartBottom()) {
                startPressureSensorForBottomGesture();
                if (triggerCapture.isCapturing()) {
                    pilfer(inputMonitor);
                    Log.i("Fan input claimed on down through corner capture window");
                }
                arm(downCorner, x, y, event.getDownTime(), event.getEventTime());
                Log.i("Fan hot zone armed on down corner=" + downCorner);
            }
            return;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            cancelBottomHoneycombSettle();
            resetFan();
            outsideGestures.onCancel();
            return;
        }

        if (state == State.SIDE_ARMED && action == MotionEvent.ACTION_MOVE) {
            if (sideTapIsOutsideWindow) outsideGestures.onMove(x, y);
            if (sideTapIsOutsideWindow && GestureGeometry.isIntentionalSideSwipe(
                    corner, downX, downY, x, y, directionDecisionDistance)) {
                sideBackRecognized = true;
            }
            float configuredSideDistance = width * config.sideTriggerPercent / 100f;
            SideGestureArbitrator.Decision decision = sideGestureArbitrator.update(
                    corner, downX, downY, x, y, configuredSideDistance,
                    sideDirectionSlop, sideVerticalFloor,
                    config.sideDirectionHorizontal, config.sideDirectionUp,
                    config.sideDirectionDown);
            if (decision == SideGestureArbitrator.Decision.PENDING) {
                cancelSideHold();
                return;
            }
            if (decision == SideGestureArbitrator.Decision.CANCELLED) {
                cancelSideHold();
                state = State.CANCELLED;
                Log.i("Side-distance candidate yielded to native input");
                return;
            }
            if (waitForSideHold(x, y, inputMonitor)) return;
            cancelSideHold();
            if (!sideListAllowed && sideTapIsOutsideWindow) {
                sideBackRecognized = true;
                return;
            }
            if (!pilfer(inputMonitor)) {
                state = State.CANCELLED;
                Log.i("Side-distance takeover unavailable; native back preserved");
                return;
            }
            outsideGestures.onCancel();
            activateSideList(x, y, width, height);
            Log.i("Side-distance gesture took over native back distance="
                    + Math.round(GestureGeometry.distance(downX, downY, x, y)));
            return;
        }

        if (state == State.ARMED && action == MotionEvent.ACTION_MOVE) {
            if (cornerTapIsOutsideWindow) outsideGestures.onMove(x, y);
            float distance = GestureGeometry.distance(downX, downY, x, y);
            GestureArbitrator.Decision decision = gestureArbitrator.update(
                    corner, downX, downY, x, y, directionDecisionDistance);
            if (decision == GestureArbitrator.Decision.PENDING) return;
            if (decision == GestureArbitrator.Decision.CANCELLED) {
                state = State.CANCELLED;
                if (startedWithTriggerCapture) {
                    int displayId = context.getDisplay() == null
                            ? 0 : context.getDisplay().getDisplayId();
                    triggerCapture.passthroughCapturedGesture(displayId);
                    cancelCapturedCornerTap();
                }
                Log.i("Fan input cancelled outside inward-upward fan direction");
                return;
            }
            if (!triggerCapture.isCapturing()) pilfer(inputMonitor);
            outsideGestures.onCancel();
            state = State.CLAIMED;
            cancelCapturedCornerTap();
            Log.i("Fan input claimed after inward-upward direction distance="
                    + Math.round(distance) + " capture="
                    + (triggerCapture.isCapturing() ? "window" : "pilfer"));
            activateFanIfReady(distance, x, y, width, height);
            return;
        }

        if (state == State.CLAIMED && action == MotionEvent.ACTION_MOVE) {
            float distance = GestureGeometry.distance(downX, downY, x, y);
            activateFanIfReady(distance, x, y, width, height);
            return;
        }

        if (state == State.CANCELLED && action == MotionEvent.ACTION_MOVE) return;

        if (state == State.ACTIVE && action == MotionEvent.ACTION_MOVE) {
            if (activeSideList || pressureCircularActive) {
                updateSideListSelection(x, y);
            } else {
                float selectionRadius = selectionRadius(width, height);
                float activeIconDiameter = iconDiameter(selectionRadius);
                int next = updateSelection(x, y, width, height, selectionRadius,
                        activeIconDiameter);
                updateBottomHoneycombSettleCandidate(x, y, width, height, next,
                        selectionRadius, activeIconDiameter);
            }
            return;
        }

        if (state == State.IDLE && action == MotionEvent.ACTION_MOVE) {
            outsideGestures.onMove(x, y);
            return;
        }

        if (action == MotionEvent.ACTION_UP) {
            cancelBottomHoneycombSettle();
            boolean capturedApplicationTap = shouldPassthroughCapturedCornerTap(event);
            if (state == State.ACTIVE) {
                if (activeSideList || pressureCircularActive) {
                    updateSideListSelection(x, y);
                    if (state == State.CANCELLED) {
                        resetFan();
                        return;
                    }
                } else {
                    float selectionRadius = selectionRadius(width, height);
                    updateSelection(x, y, width, height, selectionRadius,
                            iconDiameter(selectionRadius));
                }
                if (selected >= 0 && selected < activeTargets.size()) {
                    if (activeSideList && !pressureCircularActive && config.sideWheelMode) {
                        showPersistentWheel();
                        resetFan(false);
                        return;
                    } else {
                        RuntimeTarget target = activeTargets.get(selected);
                        overlay.confirmAndHide(selected);
                        boolean landscape = isLandscape();
                        boolean fullscreen = pressureCircularActive
                                ? !(pressureSelectionTrigger != null
                                ? pressureSelectionTrigger.openAsFreeform
                                : config.pressureOpenAsFreeform)
                                : activeSideList
                                ? config.sideFullscreenFor(landscape)
                                : config.bottomFullscreenFor(landscape);
                        if (pressureCircularActive) clearPressureSelectionPressure(true);
                        if (fullscreen) {
                            launchFullscreenTarget(target, false);
                        } else {
                            freeform.launch(target, config, this::refreshOutsideCapture);
                            refreshOutsideCaptureAfterLaunch();
                        }
                        outsideGestures.clearAll();
                        resetFan(false);
                        return;
                    }
                } else {
                    Log.i("Fan released without icon hit; launch cancelled");
                }
            } else if (state == State.IDLE) {
                if (capturedApplicationTap) replayCapturedCornerTap();
                else finishOutsideTap(x, y, event.getEventTime(), inputMonitor);
            } else if (state == State.ARMED && cornerTapIsOutsideWindow) {
                finishOutsideTap(x, y, event.getEventTime(), inputMonitor);
            } else if (state == State.SIDE_ARMED && sideTapIsOutsideWindow) {
                if (sideBackRecognized) {
                    outsideGestures.onCancel();
                    if (sideSequenceCaptured) {
                        int displayId = context.getDisplay() == null
                                ? 0 : context.getDisplay().getDisplayId();
                        triggerCapture.dispatchBack(displayId);
                    }
                } else {
                    if (!sideSequenceCaptured) pilfer(inputMonitor);
                    finishOutsideTap(x, y, event.getEventTime(), inputMonitor);
                }
            } else if ((state == State.ARMED || state == State.CANCELLED
                    || state == State.CLAIMED) && capturedApplicationTap) {
                replayCapturedCornerTap();
            }
            if (state == State.IDLE) cancelPressureGesture();
            resetFan();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelBottomHoneycombSettle();
            resetFan();
            outsideGestures.onCancel();
        }
    }

    private void activateFanIfReady(float distance, float x, float y, int width, int height) {
        float threshold = Math.min(width, height) * config.triggerPercent / 100f;
        if (distance >= threshold) {
            state = State.ACTIVE;
            activeSideList = false;
            float selectionRadius = selectionRadius(width, height);
            float iconDiameter = iconDiameter(selectionRadius);
            overlay.show(targets, corner, selectionRadius, iconDiameter,
                    config.showSelectedAppName, config.fanShadow,
                    config.fanAnimationsEnabled, config.bottomAnimationSpeed,
                    config.fanRevealAmount, config.fanRotationDegrees,
                    config.fanSelectionScalePercent,
                    config.fanSelectionRing, config.fanLayoutMode,
                    config.fanCustomOuterCount, config.fanCustomMiddleCount,
                    config.fanCustomInnerCount,
                    config.forceCircularIcons);
            overlay.setSelectionTransformLevel(config.selectionTransformLevel);
            if (BottomTriggerHapticPolicy.shouldVibrate(
                    config.haptic, config.bottomTriggerHaptic)) vibrateTick();
            int next = updateSelection(x, y, width, height, selectionRadius,
                    iconDiameter, false);
            updateBottomHoneycombSettleCandidate(x, y, width, height, next,
                    selectionRadius, iconDiameter);
        }
    }

    private boolean activateBottomHoneycomb(float x, float y, int width, int height) {
        boolean landscape = isLandscape();
        if (!config.bottomSecondStageEnabledFor(landscape)
                || !config.honeycombEnabledFor(landscape)
                || honeycombTargets.isEmpty() || activeSideList) return false;
        pressureHoneycombActive = false;
        bottomHoneycombSelectedTarget = null;
        bottomHoneycombPressureArmed = false;
        bottomHoneycombPressureConsumed = false;
        honeycombGestureState.enter();
        GestureGeometry.Corner launchCorner = corner;
        overlay.hide();
        selected = -1;
        outsideCapture.remove();
        outsideGestures.clearAll();
        boolean shown = honeycombOverlay.show(honeycombTargets, launchCorner, x, y, config,
                new HoneycombOverlayController.Listener() {
                    @Override public void onLaunch(RuntimeTarget target) {
                        if (config.bottomHoneycombFreeformFor(isLandscape())) {
                            freeform.launch(target, config,
                                    FanRuntime.this::refreshOutsideCapture);
                            refreshOutsideCaptureAfterLaunch();
                        } else {
                            launchHoneycombFullscreenTarget(target);
                        }
                    }

                    @Override public void onClosed() {
                        bottomHoneycombPressureArmed = false;
                        refreshOutsideCapture();
                    }

                    @Override public void onSelectionChanged(RuntimeTarget target) {
                        if (!config.bottomSecondPressureLaunchFor(isLandscape())) return;
                        if (target == null) {
                            bottomHoneycombSelectedTarget = null;
                            bottomHoneycombPressureArmed = false;
                            return;
                        }
                        bottomHoneycombSelectedTarget = target;
                        bottomSelectionPressureBaseline = pressureLatestValue;
                        bottomHoneycombPressureArmed = true;
                    }
                });
        if (!shown) {
            honeycombGestureState.reset();
            return false;
        }
        activeHoneycombReturnThreshold = Math.min(width, height)
                * config.triggerPercent / 100f;
        state = State.HONEYCOMB;
        honeycombOverlay.externalMove(x, y);
        Log.i("Honeycomb activated targets=" + honeycombTargets.size()
                + " mode=" + config.honeycombMode + " after blank-area settle");
        return true;
    }

    private boolean activatePressureHoneycomb(float x, float y, int width, int height,
                                               boolean persistent) {
        if (!config.honeycombEnabledFor(isLandscape()) || honeycombTargets.isEmpty()) {
            return false;
        }
        clearPressureSelectionPressure(true);
        pressureHoneycombActive = false;
        pressureHoneycombSessionActive = true;
        pressureSelectionTrigger = activePressureTrigger;
        pressureHoneycombSelectionMoved = persistent;
        pressureOverlayCaptureSuppressed = true;
        honeycombGestureState.enter();
        overlay.removeNow();
        selected = -1;
        activeSideList = false;
        outsideCapture.remove();
        triggerCapture.remove();
        outsideGestures.clearAll();
        downX = pressureDownX;
        downY = pressureDownY;
        corner = null;
        boolean shown = honeycombOverlay.show(honeycombTargets, null, x, y, config, persistent,
                !persistent, new HoneycombOverlayController.Listener() {
                    @Override public void onLaunch(RuntimeTarget target) {
                        launchPressureTarget(target, false, pressureSelectionTrigger);
                        resetFan(false);
                        refreshTriggerCapture();
                    }

                    @Override public void onSelectionChanged(RuntimeTarget target) {
                        if (target == null) clearPressureSelectionPressure(false);
                        else armPressureSelectionPressure(target);
                    }

                    @Override public void onClosed() {
                        clearPressureSelectionPressure(true);
                        pressureHoneycombSessionActive = false;
                        pressureHoneycombSelectionMoved = false;
                        pressureOverlayCaptureSuppressed = false;
                        if (pressureHoneycombActive) resetFan(false);
                        refreshTriggerCapture();
                        refreshOutsideCapture();
                    }
                });
        if (!shown) {
            honeycombGestureState.reset();
            pressureHoneycombSessionActive = false;
            pressureOverlayCaptureSuppressed = false;
            refreshTriggerCapture();
            return false;
        }
        activeHoneycombReturnThreshold = Math.min(width, height)
                * config.triggerPercent / 100f;
        if (persistent) {
            pressureHoneycombActive = false;
            honeycombGestureState.reset();
            state = State.IDLE;
            honeycombOverlay.externalMove(x, y);
        } else {
            pressureHoneycombActive = true;
            state = State.HONEYCOMB;
            // Do not select the icon under the pressure point. The user must move the
            // finger after the honeycomb appears before selection and second pressure
            // detection become active.
        }
        Log.i("Honeycomb activated by pressure targets=" + honeycombTargets.size()
                + " anchor=" + Math.round(x) + "," + Math.round(y)
                + " persistent=" + persistent);
        return true;
    }

    private void launchFullscreenTarget(RuntimeTarget target, boolean centeredSystemAnimation) {
        outsideCapture.remove();
        outsideGestures.clearAll();
        boolean closingFreeform = freeform.dismissTracked();
        Runnable launch = () -> {
            boolean launched = fullscreenLauncher.launch(target, centeredSystemAnimation);
            if (!launched) Log.i("Fullscreen target was unavailable");
        };
        if (closingFreeform) mainHandler.postDelayed(launch, 220L);
        else launch.run();
    }

    private void launchHoneycombFullscreenTarget(RuntimeTarget target) {
        launchFullscreenTarget(target, config.honeycombCenteredSystemAnimation);
    }

    private void activateSideList(float x, float y, int width, int height) {
        int sideLayoutMode = currentSideLayoutMode();
        if (sideLayoutMode == ConfigContract.SIDE_LAYOUT_SYSTEM_RECENTS) {
            activateSystemRecents();
            return;
        }
        if (sideLayoutMode == ConfigContract.SIDE_LAYOUT_TASKS) {
            activateSideTasks(x, y);
            return;
        }
        if (sideLayoutMode == ConfigContract.SIDE_LAYOUT_HONEYCOMB) {
            activateSideHoneycomb(x, y);
            return;
        }
        state = State.ACTIVE;
        activeSideList = true;
        activeSideFanList = sideLayoutMode == ConfigContract.SIDE_LAYOUT_FAN;
        activeSideRingList = sideLayoutMode == ConfigContract.SIDE_LAYOUT_RING;
        float hotHeight = height * config.hotHeightPercent / 100f;
        float safeTop = sideSafeTop(height);
        float safeBottom = sideSafeBottom(height, hotHeight);
        float availablePerItem = (safeBottom - safeTop) / Math.max(1, activeTargets.size());
        sideIconDiameter = Math.min(config.sideIconSizeDp * density,
                Math.max(28 * density, availablePerItem - 4 * density));
        sideRowHeight = Math.min(sideIconDiameter + 10 * density, availablePerItem);
        int anchor = (activeTargets.size() - 1) / 2;
        sideListTop = GestureGeometry.sideListTopForAnchor(y, activeTargets.size(), anchor,
                sideRowHeight, safeTop, safeBottom);
        sideListCenterX = GestureGeometry.sideListCenterX(corner, x, width,
                sideIconDiameter, 14 * density, config.sideFollowFinger);
        sideListHitWidth = Math.max(sideIconDiameter + 56 * density, 96 * density);
        sideListActivationX = GestureGeometry.sideListOuterBoundary(
                corner, sideListCenterX, sideListHitWidth);
        // The list stays available while the finger explores the screen. Only returning
        // to the physical edge dismisses it, with the fade spanning that whole distance.
        sideReverseCancelDistance = Math.max(1f, corner == GestureGeometry.Corner.LEFT
                ? sideListActivationX : width - sideListActivationX);
        sideListEntered = false;
        selected = -1;
        lastHapticSelection = -1;
        activeHoneycombReturnThreshold = 0f;
        if (activeSideRingList) {
            float safeLeft = 12 * density;
            float safeRight = width - 12 * density;
            float gap = 8 * density;
            float minimumIcon = 28 * density;
            float maxOuter = Math.max(1f, Math.min(
                    (safeRight - safeLeft) / 2f,
                    (safeBottom - safeTop) / 2f));
            sideIconDiameter = Math.min(config.sideIconSizeDp * density,
                    Math.max(minimumIcon, maxOuter / 2f));
            float automaticRadius = GestureGeometry.sideRingRadius(
                    activeTargets.size(), sideIconDiameter, gap);
            sideRingRadius = GestureGeometry.scaledSideRingRadius(
                    automaticRadius, config.sideRingSizePercent,
                    maxOuter - sideIconDiameter / 2f);
            GestureGeometry.Point center = GestureGeometry.sideRingCenter(
                    x, y, sideRingRadius, sideIconDiameter,
                    safeLeft, safeTop, safeRight, safeBottom);
            sideRingCenterX = center.x;
            sideRingCenterY = center.y;
            sideListCenterX = center.x;
            sideListTop = center.y;
            overlay.showSideRingList(activeTargets, corner,
                    sideRingCenterX, sideRingCenterY, sideRingRadius,
                    sideIconDiameter, config.showSelectedAppName,
                    false, config.sideAnimationsEnabled,
                    config.sideAnimationSpeed, config.sideRevealAmount,
                    config.sideRotationDegrees, config.sideSelectionScalePercent,
                    config.sideSelectionRing, config.forceCircularIcons);
        } else if (activeSideFanList) {
            sideListCenterX = x;
            sideFanRadius = Math.max(sideIconDiameter * 1.35f,
                    corner == GestureGeometry.Corner.LEFT ? x : width - x);
            sideFanCenterY = GestureGeometry.sideFanCenterY(y, activeTargets.size(),
                    sideFanRadius, sideIconDiameter, safeTop, safeBottom);
            overlay.showSideFanList(activeTargets, corner, sideListCenterX, sideFanCenterY,
                    sideFanRadius, sideIconDiameter, config.showSelectedAppName,
                    false, config.sideAnimationsEnabled,
                    config.sideAnimationSpeed, config.sideRevealAmount,
                    config.sideRotationDegrees, config.sideSelectionScalePercent,
                    config.sideSelectionRing, config.forceCircularIcons);
        } else {
            overlay.showSideList(activeTargets, corner, sideListCenterX, sideListTop,
                    sideRowHeight, sideIconDiameter, config.showSelectedAppName,
                    false, config.sideAnimationsEnabled,
                    config.sideAnimationSpeed, config.sideRevealAmount,
                    config.sideRotationDegrees, config.sideSelectionScalePercent,
                    config.sideSelectionRing, config.forceCircularIcons);
        }
        overlay.setSelectionTransformLevel(config.selectionTransformLevel);
        if (config.haptic) vibrateTick();
        updateSideListSelection(x, y);
        Log.i("Side list shown center=" + Math.round(sideListCenterX) + ","
                + Math.round(activeSideRingList ? sideRingCenterY
                : activeSideFanList ? sideFanCenterY
                : sideListTop + (anchor + 0.5f) * sideRowHeight)
                + " selected=" + selected + " follow=" + config.sideFollowFinger
                + " layout=" + sideLayoutMode + " wheel=" + config.sideWheelMode);
    }

    private void activateSideHoneycomb(float x, float y) {
        state = State.ACTIVE;
        activeSideList = true;
        activeTargets = honeycombTargets;
        outsideCapture.remove();
        outsideGestures.clearAll();
        activeHoneycombReturnThreshold = 0f;
        boolean shown = honeycombOverlay.show(activeTargets, corner, x, y, config,
                new HoneycombOverlayController.Listener() {
                    @Override public void onLaunch(RuntimeTarget target) {
                        if (config.sideFullscreenFor(isLandscape())) {
                            launchHoneycombFullscreenTarget(target);
                        }
                        else {
                            freeform.launch(target, config,
                                    FanRuntime.this::refreshOutsideCapture);
                            refreshOutsideCaptureAfterLaunch();
                        }
                    }
                    @Override public void onClosed() { refreshOutsideCapture(); }
                });
        if (shown) {
            state = State.HONEYCOMB;
            honeycombOverlay.externalMove(x, y);
            if (config.haptic) vibrateTick();
        } else state = State.CANCELLED;
    }

    private void activateSideTasks(float x, float y) {
        // 唤起列表的瞬间这里仍是「刚才的前台应用」，必须在切走前快照，
        // 供分屏动作填充另一个 stage（见 SoSc startTask 语义）。
        foregroundTaskIdAtListOpen = recentTaskRepository.foregroundTaskId();
        state = State.TASKS_LOADING;
        activeSideList = true;
        outsideCapture.remove();
        outsideGestures.clearAll();
        taskLoadCurrentX = x;
        taskLoadCurrentY = y;
        int generation = ++taskLoadGeneration;
        GestureGeometry.Corner taskCorner = corner;
        GestureConfig taskConfig = config;
        configHandler.post(() -> {
            List<RecentTaskPreview> previews = recentTaskRepository.loadRunningTasks(
                    taskConfig.sideTaskMaxCount,
                    taskConfig.sideTaskLayoutMode == ConfigContract.SIDE_TASK_LAYOUT_FLAT);
            mainHandler.post(() -> showLoadedSideTasks(previews, taskCorner, x, y,
                    taskConfig, generation));
        });
    }

    /**
     * 任务列表（含第三档卡片列表）统一走本进程的 overlay 承载。
     *
     * <p>历史注记：第三档曾改为「模块进程的普通 Activity + 系统生命周期收起」，
     * 实测两个不可接受缺陷 —— ① 应用窗口独占，呼出时屏幕变成"独立列表界面"
     * 而非浮层；② 上划走系统 task 过渡，明显卡顿。故改回 overlay，
     * 关闭由 {@link TaskSwitcherOverlayController} 瞬时 removeView 完成。
     * 相关 Activity 类保留未删，但不再参与任何档位。
     */
    void onTaskListStarted() {
        // 旧 Activity 通道的回调，保留为无操作占位：外部（TaskCenterActionReceiver）
        // 仍可能收到历史广播，这里不再做任何状态变更。
        Log.i("Task list activity channel is retired; overlay path is in use");
    }

    /** 列表里选了「在分屏视图中打开」：SoSc 控制器只存在于本进程，必须在这里调。 */
    void onTaskListSplitRequest(int taskId) {
        if (taskId < 0) return;
        Log.i("Task list split request task=" + taskId
                + " foreground=" + foregroundTaskIdAtListOpen);
        int remembered = foregroundTaskIdAtListOpen;
        // 列表已自行关闭，快照用后即清；分屏动作本身就是"列表的终局"。
        foregroundTaskIdAtListOpen = -1;
        taskListActivityShown = false;
        launchTaskInSplit(taskId, remembered);
        mainHandler.postDelayed(this::refreshOutsideCapture, 800L);
    }

    /** 列表里选了「在弹出视图中打开」：走本进程已有的 MIUI 原生小窗链路。 */
    void onTaskListFreeformRequest(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        pendingFreeformPackage = packageName;
        taskListActivityShown = false;
        foregroundTaskIdAtListOpen = -1;
        mainHandler.post(this::launchPendingFreeformPackage);
    }

    private void launchPendingFreeformPackage() {
        String packageName = pendingFreeformPackage;
        if (packageName == null) return;
        pendingFreeformPackage = null;
        try {
            Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Log.i("Freeform launch unavailable package=" + packageName);
                return;
            }
            freeform.launchExternalIntent(packageName, intent, config,
                    FanRuntime.this::refreshOutsideCapture);
        } catch (Throwable error) {
            Log.e("Cannot launch package in freeform package=" + packageName, error);
        }
    }

    private void showLoadedSideTasks(List<RecentTaskPreview> previews,
                                     GestureGeometry.Corner taskCorner,
                                     float anchorX, float anchorY,
                                     GestureConfig taskConfig, int generation) {
        activeRecentTasks = null;
        if (state != State.TASKS_LOADING || generation != taskLoadGeneration) {
            recentTaskRepository.release(previews);
            return;
        }
        if (previews.isEmpty()) {
            state = State.CANCELLED;
            Log.i("Task switcher skipped because no running tasks were available");
            refreshOutsideCapture();
            return;
        }
        activeRecentTasks = previews;
        boolean shown = taskSwitcherOverlay.show(previews, taskCorner, anchorX, anchorY,
                taskConfig,
                new TaskSwitcherOverlayController.Listener() {                    @Override public void onLaunch(RecentTaskPreview task) {
                        recentTaskRepository.launch(task);
                        activeRecentTasks = null;
                        if (state == State.TASKS) state = State.IDLE;
                        mainHandler.postDelayed(FanRuntime.this::refreshOutsideCapture, 500L);
                    }

                    @Override public void onCloseTask(RecentTaskPreview task) {
                        recentTaskRepository.removeTask(task.taskId);
                    }

                    @Override public void onCloseAll() {
                        recentTaskRepository.removeAllTasks(activeRecentTasks);
                    }

                    @Override public void onListAction(TaskPanelAction action, RecentTaskPreview task,
                            RecentTaskRepository.ShortcutEntry shortcut) {
                        handleListAction(action, task, shortcut);
                    }

                    @Override public void onToggleLock(RecentTaskPreview task, boolean shouldLock) {
                        applyLockChange(task, shouldLock);
                    }

                    @Override public void onClosed() {
                        activeRecentTasks = null;
                        foregroundTaskIdAtListOpen = -1;
                        if (state == State.TASKS) state = State.IDLE;
                        refreshOutsideCapture();
                    }
                }, lockedApps);
        if (shown) {
            state = State.TASKS;
            taskSwitcherOverlay.externalMove(taskLoadCurrentX, taskLoadCurrentY);
            if (taskConfig.haptic) vibrateTick();
            startForegroundWatch();
        } else {
            activeRecentTasks = null;
            foregroundTaskIdAtListOpen = -1;
            recentTaskRepository.release(previews);
            state = State.CANCELLED;
            refreshOutsideCapture();
        }
    }

    /**
     * 面板菜单动作 → 系统原生接口。
     *
     * <p>动作类型是 {@link TaskPanelAction}（业务语义），不是某个视图类的 {@code int} 常量 ——
     * 换一个面板实现，本方法不需要任何改动。
     */
    private void handleListAction(TaskPanelAction action, RecentTaskPreview task,
                                  RecentTaskRepository.ShortcutEntry shortcut) {
        switch (action) {
            case FREEFORM:
                if (task != null && task.packageName != null) {
                    freeformTaskByPackage(task.packageName);
                }
                break;
            case SPLIT:
                if (task != null) {
                    launchTaskInSplit(task.taskId, foregroundTaskIdAtListOpen);
                    foregroundTaskIdAtListOpen = -1;
                }
                break;
            case DETAILS:
                if (task != null) recentTaskRepository.openAppDetails(task.packageName);
                break;
            case SHORTCUT:
                recentTaskRepository.launchShortcut(shortcut);
                break;
            case SEARCH:
                recentTaskRepository.openHomeSearch();
                break;
            case LOCK:
                // 菜单里的"锁定此应用程序"= 行内锁形同一个动作，走同一条写入路径。
                if (task != null) {
                    applyLockChange(task, !lockedApps.isLocked(task.packageName));
                }
                break;
            default:
                break;
        }
    }

    /**
     * 切换应用锁定：写 {@code Settings.System("locked_apps")}。
     *
     * <p><b>为什么写 Settings 就是"完整动作"</b>：系统侧的
     * {@code ProcessPolicy.registerContentObserver()} 正监听这个键，写入后它自己会
     * 重新加载内存列表、调整目标进程的 adj（实测：锁定 → {@code maxAdj +1}，
     * 解锁 → 回落 {@code cch+101}）。所以模块不需要通知任何服务，也不需要
     * 自己维护"锁定"这个概念 —— 写进系统认的那个键，就是让系统按它的规则办事。
     */
    private void applyLockChange(RecentTaskPreview task, boolean shouldLock) {
        if (task == null || task.packageName == null) return;
        if (!lockedApps.setLocked(task.packageName, shouldLock)) {
            Log.i("Lock change rejected pkg=" + task.packageName + " locked=" + shouldLock);
        }
    }

    /** 弹出视图（小窗）：复用 HyperOsFreeformBridge 既有的小窗启动链路（MIUI 原生体系）。 */
    private void freeformTaskByPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Log.i("Freeform launch unavailable package=" + packageName);
            return;
        }
        freeform.launchExternalIntent(packageName, intent, config,
                FanRuntime.this::refreshOutsideCapture);
    }

    /**
     * 在分屏视图中打开：目标任务进 main stage，唤起列表时的前台应用进 side stage。
     *
     * <p>stage 语义为设备实测结论（不靠推断）：
     * <ul>
     *   <li>{@code startTask(taskId, position, options)}：{@code position==0 → SoScMainStage}，
     *       {@code position==1 → SoScSideStage}</li>
     *   <li>{@code SoScStageCoordinator.getSideStagePosition()} 返回 side stage 的物理位置
     *       （0=top/left，1=bottom/right），用户可交换且系统记忆，物理位置必须运行期读</li>
     * </ul>
     *
     * @param foregroundTaskId 唤起列表那一刻的前台任务 id（必须由调用方快照传入，
     *                         不能在这里读实时前台：那时前台已变成列表自己）。
     */
    private void launchTaskInSplit(int taskId, int foregroundTaskId) {
        Object controller = soScSplitController;
        if (taskId < 0 || controller == null) {
            Log.i("Split launch unavailable controller=" + (controller != null)
                    + " task=" + taskId);
            return;
        }
        try {
            int sideStagePosition = 0;
            Object coordinator = soScStageCoordinator;
            if (coordinator != null) {
                try {
                    sideStagePosition = (int) XposedHelpers.callMethod(
                            coordinator, "getSideStagePosition");
                } catch (Throwable positionError) {
                    Log.i("Split stage position unreadable error="
                            + positionError.getClass().getSimpleName());
                }
            }
            final int mainStagePosition = 0;
            final int sideStagePositionArg = 1;
            Log.i("Split launch target=" + taskId
                    + " mainPosition=" + mainStagePosition
                    + " sidePosition=" + sideStagePositionArg
                    + " sideStagePhysical=" + sideStagePosition
                    + " foreground=" + foregroundTaskId);
            // 第一步：目标任务进 main stage。系统会自行带出分屏过渡。
            XposedHelpers.callMethod(controller, "startTask", taskId,
                    mainStagePosition, null);
            // 第二步：把唤起列表时的前台应用放进 side stage。必须用快照而不是
            // getTopFullScreenTask()：后者在此刻返回的就是目标任务自身，会导致
            // 对面 stage 落空、系统塞入桌面。
            if (foregroundTaskId >= 0 && foregroundTaskId != taskId) {
                XposedHelpers.callMethod(controller, "startTask", foregroundTaskId,
                        sideStagePositionArg, null);
                Log.i("Split side stage filled with foreground task=" + foregroundTaskId);
            } else {
                Log.i("Split side stage left to system: foreground=" + foregroundTaskId
                        + " target=" + taskId);
            }
        } catch (Throwable error) {
            Log.i("Split launch threw id=" + taskId
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private void activateSystemRecents() {
        state = State.CANCELLED;
        activeSideList = false;
        outsideCapture.remove();
        outsideGestures.clearAll();
        boolean shown = systemRecentsLauncher.show();
        if (!shown) {
            Log.i("HyperOS system recents request failed safely");
            refreshOutsideCapture();
            return;
        }
        if (config.haptic) vibrateTick();
        long version = ++systemRecentsWatchVersion;
        long startedAt = android.os.SystemClock.uptimeMillis();
        mainHandler.postDelayed(() -> watchSystemRecents(
                version, 0, false, startedAt), 100L);
    }

    private void watchSystemRecents(long version, int attempts, boolean wasVisible,
                                    long startedAt) {
        if (version != systemRecentsWatchVersion) return;
        long elapsed = android.os.SystemClock.uptimeMillis() - startedAt;
        boolean visible = systemRecentsLauncher.isVisible();
        if (elapsed > SYSTEM_RECENTS_MAX_WATCH_MS) {
            refreshOutsideCapture();
            Log.i("System recents watch reached hard timeout; polling stopped");
            return;
        }
        if (visible) {
            long delay = elapsed < SYSTEM_RECENTS_FAST_WATCH_MS ? 450L
                    : elapsed < SYSTEM_RECENTS_SLOW_WATCH_MS ? 1500L
                    : SYSTEM_RECENTS_IDLE_WATCH_MS;
            mainHandler.postDelayed(
                    () -> watchSystemRecents(version, attempts + 1, true, startedAt), delay);
            return;
        }
        if (!wasVisible && attempts < 12) {
            mainHandler.postDelayed(
                    () -> watchSystemRecents(version, attempts + 1, false, startedAt), 100L);
            return;
        }
        refreshOutsideCapture();
        Log.i("Outside capture refreshed after HyperOS system recents closed");
    }

    private void arm(GestureGeometry.Corner corner, float x, float y,
                     long downTime, long eventTime) {
        gestureArbitrator.reset();
        gestureReplayGuard.begin(downTime, eventTime);
        state = State.ARMED;
        activeTargets = targets;
        this.corner = corner;
        downX = x;
        downY = y;
        if (triggerCapture.isCapturing()) beginCapturedCornerTap(x, y, downTime);
        else cancelCapturedCornerTap();
    }

    private void beginCapturedCornerTap(float x, float y, long downTime) {
        startedWithTriggerCapture = true;
        capturedCornerTapMaxDistance = 0f;
        capturedCornerTapDownTime = downTime;
        downX = x;
        downY = y;
    }

    private void updateCapturedCornerTap(MotionEvent event, float x, float y) {
        if (!startedWithTriggerCapture || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return;
        }
        int action = event.getActionMasked();
        if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            cancelCapturedCornerTap();
            return;
        }
        capturedCornerTapMaxDistance = Math.max(capturedCornerTapMaxDistance,
                GestureGeometry.distance(downX, downY, x, y));
        for (int historyIndex = 0; historyIndex < event.getHistorySize(); historyIndex++) {
            capturedCornerTapMaxDistance = Math.max(capturedCornerTapMaxDistance,
                    GestureGeometry.distance(downX, downY,
                            event.getHistoricalX(0, historyIndex),
                            event.getHistoricalY(0, historyIndex)));
        }
    }

    private boolean shouldPassthroughCapturedCornerTap(MotionEvent event) {
        if (!startedWithTriggerCapture || event.getPointerCount() != 1) return false;
        long duration = event.getEventTime() - capturedCornerTapDownTime;
        return duration >= 0L && duration <= cornerTapTimeoutMs
                && capturedCornerTapMaxDistance <= cornerTapSlop;
    }

    private void replayCapturedCornerTap() {
        int displayId = context.getDisplay() == null ? 0 : context.getDisplay().getDisplayId();
        triggerCapture.passthroughTap(downX, downY, displayId);
    }

    private void cancelCapturedCornerTap() {
        startedWithTriggerCapture = false;
        capturedCornerTapMaxDistance = 0f;
        capturedCornerTapDownTime = 0L;
    }

    private void armSide(GestureGeometry.Corner side, float x, float y,
                         long downTime, long eventTime) {
        cancelSideHold();
        sideGestureArbitrator.reset();
        gestureReplayGuard.begin(downTime, eventTime);
        state = State.SIDE_ARMED;
        activeTargets = currentSideLayoutMode() == ConfigContract.SIDE_LAYOUT_HONEYCOMB
                ? honeycombTargets : sideTargets;
        corner = side;
        downX = x;
        downY = y;
    }

    private void updateSideHoldCandidate(float x, float y, Object inputMonitor) {
        if (!config.sideHoldEnabled || state != State.SIDE_ARMED || corner == null) {
            cancelSideHold();
            return;
        }
        if (sideTapIsOutsideWindow && !sideListAllowed) {
            cancelSideHold();
            return;
        }
        float inward = corner == GestureGeometry.Corner.LEFT ? x - downX : downX - x;
        float minimum = displayBounds().width() * config.sideTriggerPercent / 100f;
        if (inward < minimum) {
            cancelSideHold();
            return;
        }
        sideHoldCurrentX = x;
        sideHoldCurrentY = y;
        sideHoldInputMonitor = inputMonitor;
        boolean firstSchedule = !sideHoldScheduled;
        boolean moved = !sideHoldScheduled || Math.hypot(
                x - sideHoldAnchorX, y - sideHoldAnchorY) > sideDirectionSlop;
        if (!moved) return;
        Handler currentHandler = sideHoldHandler;
        Looper currentLooper = Looper.myLooper();
        if (currentLooper == null) currentHandler = mainHandler;
        else if (currentHandler == null || currentHandler.getLooper() != currentLooper) {
            if (currentHandler != null) currentHandler.removeCallbacks(sideHoldTrigger);
            currentHandler = new Handler(currentLooper);
        }
        sideHoldHandler = currentHandler;
        currentHandler.removeCallbacks(sideHoldTrigger);
        sideHoldAnchorX = x;
        sideHoldAnchorY = y;
        sideHoldScheduled = true;
        currentHandler.postDelayed(sideHoldTrigger, config.sideHoldDelayMs);
        if (firstSchedule) {
            Log.i("Side dwell timer started distance=" + Math.round(minimum)
                    + " position=" + Math.round(inward)
                    + " delay=" + config.sideHoldDelayMs + "ms");
        }
    }

    private boolean waitForSideHold(float x, float y, Object inputMonitor) {
        if (!config.sideHoldEnabled || !sideListAllowed) return false;
        updateSideHoldCandidate(x, y, inputMonitor);
        // Dwell mode uses the normal side-distance trigger. Once that distance is
        // crossed, continued movement restarts the timer; the side list opens at the
        // settled finger position.
        return true;
    }

    private void triggerHeldSideList() {
        sideHoldScheduled = false;
        sideHoldHandler = null;
        if (!config.sideHoldEnabled
                || state != State.SIDE_ARMED || corner == null) return;
        if (Math.hypot(sideHoldCurrentX - sideHoldAnchorX,
                sideHoldCurrentY - sideHoldAnchorY) > sideDirectionSlop * 1.5f) return;
        if (sideTapIsOutsideWindow && !sideListAllowed) return;
        Object inputMonitor = sideHoldInputMonitor;
        sideHoldInputMonitor = null;
        if (!pilfer(inputMonitor)) {
            Log.i("Side hold takeover unavailable; native back preserved");
            return;
        }
        sideSequenceCaptured = true;
        outsideGestures.onCancel();
        activateSideList(sideHoldCurrentX, sideHoldCurrentY,
                displayBounds().width(), displayBounds().height());
        Log.i("Side list opened after dwell distance=" + Math.round(
                GestureGeometry.distance(downX, downY,
                        sideHoldCurrentX, sideHoldCurrentY)));
    }

    private void cancelSideHold() {
        Handler currentHandler = sideHoldHandler;
        if (currentHandler != null) currentHandler.removeCallbacks(sideHoldTrigger);
        mainHandler.removeCallbacks(sideHoldTrigger);
        sideHoldScheduled = false;
        sideHoldHandler = null;
        sideHoldInputMonitor = null;
    }

    private void updateBottomHoneycombSettleCandidate(float x, float y, int width, int height,
                                                       int selection, float fanRadius,
                                                       float fanIconDiameter) {
        if (selection >= 0 || state != State.ACTIVE || activeSideList
                || pressureCircularActive
                || !config.bottomSecondStageEnabledFor(isLandscape())
                || !config.honeycombEnabledFor(isLandscape())
                || honeycombTargets.isEmpty()) {
            cancelBottomHoneycombSettle();
            return;
        }
        float minimumDistance = GestureGeometry.fanOutermostEdgeDistance(
                corner, activeTargets.size(), width, height, fanRadius,
                fanIconDiameter, BOTTOM_HONEYCOMB_FAN_GAP_DP * density,
                config.fanLayoutMode, config.fanCustomOuterCount,
                config.fanCustomMiddleCount, config.fanCustomInnerCount);
        if (!GestureGeometry.beyondFanEdge(
                corner, x, y, width, height, minimumDistance)) {
            cancelBottomHoneycombSettle();
            return;
        }
        bottomHoneycombCurrentX = x;
        bottomHoneycombCurrentY = y;
        bottomHoneycombDisplayWidth = width;
        bottomHoneycombDisplayHeight = height;
        float settleSlop = BOTTOM_HONEYCOMB_SETTLE_DP * density;
        boolean moved = !bottomHoneycombSettleScheduled || Math.hypot(
                x - bottomHoneycombSettleAnchorX,
                y - bottomHoneycombSettleAnchorY) > settleSlop;
        if (!moved) return;
        Handler currentHandler = bottomHoneycombSettleHandler;
        Looper currentLooper = Looper.myLooper();
        if (currentLooper == null) currentHandler = mainHandler;
        else if (currentHandler == null || currentHandler.getLooper() != currentLooper) {
            if (currentHandler != null) {
                currentHandler.removeCallbacks(bottomHoneycombSettleTrigger);
            }
            currentHandler = new Handler(currentLooper);
        }
        bottomHoneycombSettleHandler = currentHandler;
        currentHandler.removeCallbacks(bottomHoneycombSettleTrigger);
        bottomHoneycombSettleAnchorX = x;
        bottomHoneycombSettleAnchorY = y;
        bottomHoneycombSettleScheduled = true;
        currentHandler.postDelayed(bottomHoneycombSettleTrigger,
                Math.max(0L, config.bottomHoneycombSettleMs));
    }

    private void triggerSettledBottomHoneycomb() {
        bottomHoneycombSettleScheduled = false;
        bottomHoneycombSettleHandler = null;
        float settleSlop = BOTTOM_HONEYCOMB_SETTLE_DP * density;
        if (state != State.ACTIVE || activeSideList || selected >= 0
                || Math.hypot(bottomHoneycombCurrentX - bottomHoneycombSettleAnchorX,
                bottomHoneycombCurrentY - bottomHoneycombSettleAnchorY) > settleSlop) {
            return;
        }
        activateBottomHoneycomb(bottomHoneycombCurrentX, bottomHoneycombCurrentY,
                bottomHoneycombDisplayWidth, bottomHoneycombDisplayHeight);
    }

    private void cancelBottomHoneycombSettle() {
        Handler currentHandler = bottomHoneycombSettleHandler;
        if (currentHandler != null) {
            currentHandler.removeCallbacks(bottomHoneycombSettleTrigger);
        }
        mainHandler.removeCallbacks(bottomHoneycombSettleTrigger);
        bottomHoneycombSettleScheduled = false;
        bottomHoneycombSettleHandler = null;
    }

    private int updateSelection(float x, float y, int width, int height,
                                float radius, float iconDiameter) {
        return updateSelection(x, y, width, height, radius, iconDiameter, true);
    }

    private int updateSelection(float x, float y, int width, int height,
                                float radius, float iconDiameter,
                                boolean emitHaptic) {
        int previous = selected;
        if (cachedFanLayout == null || cachedFanLayout.itemCount != activeTargets.size()
                || cachedFanLayout.width != width || cachedFanLayout.height != height
                || cachedFanLayout.radius != radius || cachedFanLayout.corner != corner
                || cachedFanLayout.layoutMode != config.fanLayoutMode
                || cachedFanLayout.outerCapacity != config.fanCustomOuterCount
                || cachedFanLayout.middleCapacity != config.fanCustomMiddleCount
                || cachedFanLayout.innerCapacity != config.fanCustomInnerCount) {
            cachedFanLayout = GestureGeometry.fanLayout(corner, activeTargets.size(),
                    width, height, radius, config.fanLayoutMode, config.fanCustomOuterCount,
                    config.fanCustomMiddleCount, config.fanCustomInnerCount);
        }
        int next = cachedFanLayout.selection(x, y, iconDiameter, 6 * density);
        if (emitHaptic && config.haptic && next >= 0 && next != lastHapticSelection) {
            vibrateTick();
        }
        lastHapticSelection = next;
        selected = next;
        if (previous != next && corner != null
                && config.bottomFirstPressureLaunchFor(isLandscape())
                && pressureGestureReady()) {
            if (next >= 0) {
                bottomSelectionPressureBaseline = pressureLatestValue;
                bottomSelectionPressureArmed = true;
            } else {
                bottomSelectionPressureArmed = false;
            }
        }
        overlay.update(selected, x, y);
        return next;
    }

    private void triggerBottomSelectionPressure() {
        if (!bottomSelectionPressureArmed || bottomSelectionPressureConsumed
                || state != State.ACTIVE || activeSideList || pressureCircularActive
                || selected < 0 || selected >= activeTargets.size()) return;
        RuntimeTarget target = activeTargets.get(selected);
        int selectedIndex = selected;
        bottomSelectionPressureConsumed = true;
        bottomSelectionPressureArmed = false;
        cancelBottomHoneycombSettle();
        if (config.haptic && config.pressureSecondHapticEnabled) {
            vibratePressureStage();
        }
        overlay.confirmAndHide(selectedIndex);
        boolean fullscreen = config.bottomFullscreenFor(isLandscape());
        if (config.bottomFirstPressureLaunchFor(isLandscape())) fullscreen = !fullscreen;
        if (fullscreen) {
            launchFullscreenTarget(target, false);
        } else {
            freeform.launch(target, config, this::refreshOutsideCapture);
            refreshOutsideCaptureAfterLaunch();
        }
        outsideGestures.clearAll();
        resetFan(false);
        Log.i("Bottom fan selected pressure opened fullscreen index=" + selectedIndex);
    }

    private void triggerBottomHoneycombPressure() {
        if (!bottomHoneycombPressureArmed || bottomHoneycombPressureConsumed
                || state != State.HONEYCOMB || corner == null
                || bottomHoneycombSelectedTarget == null) return;
        RuntimeTarget target = bottomHoneycombSelectedTarget;
        bottomHoneycombPressureConsumed = true;
        bottomHoneycombPressureArmed = false;
        honeycombOverlay.removeNow();
        if (config.haptic && config.pressureSecondHapticEnabled) {
            vibratePressureStage();
        }
        boolean openFreeform = config.bottomHoneycombFreeformFor(isLandscape());
        if (config.bottomSecondPressureLaunchFor(isLandscape())) openFreeform = !openFreeform;
        if (openFreeform) {
            freeform.launch(target, config, this::refreshOutsideCapture);
            refreshOutsideCaptureAfterLaunch();
        } else {
            launchHoneycombFullscreenTarget(target);
        }
        outsideGestures.clearAll();
        resetFan(false);
        Log.i("Bottom honeycomb selected pressure launched target mode="
                + (openFreeform ? "freeform" : "fullscreen"));
    }

    private void updateSideListSelection(float x, float y) {
        if (activeSideRingList) {
            overlay.setOpacity(1f);
            int previous = selected;
            int next = GestureGeometry.sideRingSelection(x, y, activeTargets.size(),
                    sideRingCenterX, sideRingCenterY, sideRingRadius,
                    sideIconDiameter, 10 * density);
            selected = next;
            if (config.haptic && next >= 0 && next != lastHapticSelection) {
                vibrateTick();
                lastHapticSelection = next;
            }
            if (pressureCircularActive && previous != next) {
                if (next >= 0 && next < activeTargets.size()) {
                    armPressureSelectionPressure(activeTargets.get(next));
                } else {
                    clearPressureSelectionPressure(false);
                }
            }
            overlay.update(selected, x, y);
            return;
        }
        if (activeSideFanList) {
            overlay.setOpacity(1f);
            int width = displayBounds().width();
            int next = GestureGeometry.sideFanSelection(corner, x, y, width,
                    activeTargets.size(), sideListCenterX, sideFanCenterY, sideFanRadius,
                    sideIconDiameter, 10 * density);
            selected = next;
            if (config.haptic && next >= 0 && next != lastHapticSelection) {
                vibrateTick();
                lastHapticSelection = next;
            }
            overlay.update(selected, x, y);
            return;
        }
        float reverseDistance = GestureGeometry.sideReverseDistance(
                corner, sideListActivationX, x);
        if (reverseDistance > 0f) {
            selected = -1;
            float opacity = GestureGeometry.sideListOpacity(
                    reverseDistance, sideReverseCancelDistance);
            overlay.update(-1, x, y);
            overlay.setOpacity(opacity);
            if (reverseDistance >= sideReverseCancelDistance) {
                state = State.CANCELLED;
                overlay.hide();
                Log.i("Side list cancelled at reverse distance="
                        + Math.round(reverseDistance));
            }
            return;
        }
        overlay.setOpacity(1f);
        boolean inside = GestureGeometry.insideSideList(x, y, sideListCenterX,
                sideListHitWidth, activeTargets.size(), sideListTop, sideRowHeight);
        if (!inside) {
            selected = -1;
            overlay.update(-1, x, y);
            return;
        }
        sideListEntered = true;
        int next = GestureGeometry.sideListSelection(
                y, activeTargets.size(), sideListTop, sideRowHeight);
        selected = next;
        if (config.haptic && next >= 0 && next != lastHapticSelection) {
            vibrateTick();
            lastHapticSelection = next;
        }
        overlay.update(selected, x, y);
    }

    private void showPersistentWheel() {
        int initialSelection = selected;
        float wheelCenterY = sideListTop + (initialSelection + 0.5f) * sideRowHeight;
        List<RuntimeTarget> wheelTargets = activeTargets;
        GestureGeometry.Corner wheelSide = corner;
        wheelSessionActive = true;
        overlay.showWheel(wheelTargets, wheelSide, sideListCenterX, wheelCenterY,
                sideRowHeight, sideIconDiameter, initialSelection,
                config.showSelectedAppName, false,
                config.forceCircularIcons,
                new FanOverlayController.WheelListener() {
                    @Override public void onLaunch(int index) {
                        wheelSessionActive = false;
                        if (index < 0 || index >= wheelTargets.size()) return;
                        RuntimeTarget target = wheelTargets.get(index);
                        if (config.sideFullscreenFor(isLandscape())) {
                            launchFullscreenTarget(target, false);
                        } else {
                            freeform.launch(target, config,
                                    FanRuntime.this::refreshOutsideCapture);
                            refreshOutsideCaptureAfterLaunch();
                        }
                        outsideGestures.clearAll();
                        Log.i("Side wheel launched index=" + index);
                    }

                    @Override public void onDismiss() {
                        wheelSessionActive = false;
                        Log.i("Side wheel dismissed");
                    }

                    @Override public void onSelectionChanged(int index) {
                        if (config.haptic) vibrateTick();
                    }
                });
        Log.i("Side wheel retained selected=" + initialSelection);
    }

    private float selectionRadius(int width, int height) {
        float configured = Math.min(width, height) * config.selectionRadiusPercent / 100f;
        return GestureGeometry.effectiveRadius(activeTargets.size(), configured,
                config.iconSizeDp * density, 6 * density, config.fanLayoutMode,
                config.fanCustomOuterCount, config.fanCustomMiddleCount,
                config.fanCustomInnerCount);
    }

    private float iconDiameter(float radius) {
        return GestureGeometry.effectiveIconDiameter(activeTargets.size(), radius,
                config.iconSizeDp * density, 28 * density, 6 * density,
                config.fanLayoutMode, config.fanCustomOuterCount,
                config.fanCustomMiddleCount, config.fanCustomInnerCount);
    }

    private void resetFan() {
        resetFan(true);
    }

    private void resetFan(boolean hideOverlay) {
        // 注意：这里**不能**停掉前台看护。
        // 侧边栏拉出列表后手指抬起就会走 ACTION_UP → resetFan(false)，
        // 此时列表窗口仍按设计留在屏幕上（抬手后继续可点），看护必须继续跑。
        // 停表统一由 dismissTaskSurfaceForForegroundChange 负责，
        // 看护自身发现窗口不在场时也会自行退出。
        cancelPressureGesture();
        cancelSideHold();
        cancelBottomHoneycombSettle();
        if (hideOverlay && state == State.ACTIVE) overlay.hide();
        if (hideOverlay && state == State.HONEYCOMB) honeycombOverlay.removeNow();
        // 列表看窗口，不看 state：抬手之后列表窗口刻意保留而 state 已经归位到 IDLE，
        // 用 state == TASKS 判断会在那一刻之后彻底关不掉它（僵尸层的根因）。
        // 同时改成走四层退场，不再 removeNow 让它凭空消失。
        // 这里传 hideOverlay=true 的调用方都是"手势流程归位"，
        // 内部 resetFan(false) 不会再回到本分支，不存在递归。
        if (hideOverlay && taskSwitcherOverlay.isAttached()) {
            dismissTaskSurfaceForForegroundChange("gesture reset",
                    TaskPanelExit.RECEDE);
        }
        if (state == State.TASKS_LOADING) taskLoadGeneration++;
        taskListActivityShown = false;
        pendingFreeformPackage = null;
        gestureArbitrator.reset();
        sideGestureArbitrator.reset();
        gestureReplayGuard.reset();
        honeycombGestureState.reset();
        state = State.IDLE;
        corner = null;
        selected = -1;
        cancelCapturedCornerTap();
        cornerTapIsOutsideWindow = false;
        sideTapIsOutsideWindow = false;
        sideBackRecognized = false;
        sideSequenceCaptured = false;
        sideListAllowed = false;
        imeDismissTap = false;
        activeSideList = false;
        activeSideFanList = false;
        activeSideRingList = false;
        sideListCenterX = 0f;
        sideListTop = 0f;
        sideRowHeight = 0f;
        sideIconDiameter = 0f;
        sideListHitWidth = 0f;
        sideListActivationX = 0f;
        sideReverseCancelDistance = 0f;
        sideFanRadius = 0f;
        sideFanCenterY = 0f;
        sideRingRadius = 0f;
        sideRingCenterX = 0f;
        sideRingCenterY = 0f;
        sideListEntered = false;
        lastHapticSelection = -1;
        pressureHoneycombActive = false;
        pressureHoneycombSessionActive = false;
        pressureHoneycombSelectionMoved = false;
        pressureCircularActive = false;
        pressureOverlayCaptureSuppressed = false;
        activePressureTrigger = null;
        pressureCalibrationTrigger = null;
        bottomSelectionPressureArmed = false;
        bottomSelectionPressureConsumed = false;
        bottomHoneycombPressureArmed = false;
        bottomHoneycombPressureConsumed = false;
        bottomHoneycombSelectedTarget = null;
        bottomPressureBaselinePending = false;
        clearPressureSelectionPressure(true);
        if (!config.pressureShowPosition) pressureDebugOverlay.remove();
    }

    private void onPressureCandidateTimeout() {
        if (!pressureCandidate) return;
        cancelPressureGesture();
        Log.i("Pressure candidate timed out; sensor and orb released");
    }

    private void stopIdlePressureSelectionSensor() {
        if (pressureSelectionPressureArmed || pressureSelectionPressureTarget != null
                || pressureCandidate || bottomSelectionPressureArmed
                || bottomHoneycombPressureArmed || config.pressureCalibrationActive) return;
        setPressureSensorRate(false);
    }

    private void onPressureCalibrationTimeout() {
        if (!config.pressureCalibrationActive || pressureCalibrationTimedOut) return;
        pressureCalibrationTimedOut = true;
        pressureCalibrationPressActive = false;
        pressureCalibrationMovedOutside = false;
        pressureCalibrationTrigger = null;
        stopPressureSensor();
        pressureDebugOverlay.remove();
        finishPressureCalibration();
        Log.i("Pressure calibration expired after inactivity; sensor released");
    }

    private boolean pressureGestureReady() {
        if (!config.pressureGestureEnabled || !config.pressureCalibrated
                || !(config.pressureThreshold > 0f) || config.pressureTriggers.isEmpty()
                || pressureSensor == null) return false;
        return runtimeReady() && !pressureInteractionBlocked();
    }

    private boolean pressureSensorConfigured() {
        return pressureSensor != null && config.enabled
                && config.pressureGestureEnabled && config.pressureCalibrated
                && config.pressureThreshold > 0f;
    }

    private void startPressureSensorForBottomGesture() {
        if (!pressureSensorConfigured() || isLandscape()
                || (!config.bottomFirstPressureLaunchFor(false)
                && !config.bottomSecondPressureLaunchFor(false))) return;
        bottomPressureBaselinePending = true;
        startPressureSensor(false);
    }

    private void handlePressureCalibrationMotion(MotionEvent event, int width, int height) {
        int action = event.getActionMasked();
        if (!displayInteractiveUnlocked()) {
            pressureCalibrationPressActive = false;
            pressureCalibrationMovedOutside = false;
            pressureCalibrationTrigger = null;
            stopPressureSensor();
            return;
        }
        if (pressureCalibrationTimedOut || pressureInteractionStateBlocked()
                || (action == MotionEvent.ACTION_DOWN && inputMethodActive())) {
            pressureCalibrationPressActive = false;
            pressureCalibrationMovedOutside = false;
            pressureCalibrationTrigger = null;
            setPressureSensorRate(false);
            return;
        }
        if (event.getPointerCount() > 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            pressureCalibrationPressActive = false;
            pressureCalibrationMovedOutside = true;
            pressureCalibrationTrigger = null;
            setPressureSensorRate(false);
            return;
        }
        float x = event.getX();
        float y = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            pressureCalibrationMovedOutside = false;
            pressureCalibrationTrigger = pressureTriggerAt(x, y, width, height, false);
            if (!pressureHasSample || pressureCalibrationTrigger == null) {
                pressureCalibrationPressActive = false;
                Log.i("Pressure calibration ignored touch outside configured circle x="
                        + Math.round(x) + " y=" + Math.round(y));
                return;
            }
            pressureCalibrationPressActive = true;
            pressureCalibrationBaseline = pressureLatestValue;
            pressureCalibrationPeakDelta = 0f;
            setPressureSensorRate(true);
            Log.i("Pressure calibration press started x=" + Math.round(x)
                    + " y=" + Math.round(y) + " baseline=" + pressureCalibrationBaseline);
            return;
        }
        if (pressureCalibrationPressActive && action == MotionEvent.ACTION_MOVE
                && !pressurePointInside(x, y, width, height, pressureCalibrationTrigger)) {
            pressureCalibrationMovedOutside = true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && pressureCalibrationPressActive
                    && !pressureCalibrationMovedOutside) {
                recordPressureCalibrationAttempt(pressureCalibrationPeakDelta);
            }
            pressureCalibrationPressActive = false;
            pressureCalibrationMovedOutside = false;
            pressureCalibrationTrigger = null;
            setPressureSensorRate(false);
        }
    }

    private void recordPressureCalibrationAttempt(float delta) {
        float safeDelta = Float.isFinite(delta) && delta > 0f ? delta : 0f;
        boolean valid = safeDelta > 0f;
        pressureCalibrationAttempts = Math.min(5, pressureCalibrationAttempts + 1);
        if (valid) pressureCalibrationDeltas.add(safeDelta);
        Bundle extras = new Bundle();
        extras.putInt(ConfigContract.KEY_PRESSURE_CALIBRATION_ATTEMPTS,
                pressureCalibrationAttempts);
        extras.putFloat(ConfigContract.KEY_PRESSURE_CALIBRATION_LAST_DELTA, safeDelta);
        extras.putBoolean(ConfigContract.KEY_PRESSURE_CALIBRATION_LAST_VALID, valid);
        reportPressureCalibration("report_pressure_calibration_sample", extras);
        Log.i("Pressure calibration sample attempt=" + pressureCalibrationAttempts
                + " valid=" + valid + " delta=" + safeDelta);
        if (pressureCalibrationAttempts >= 5) finishPressureCalibration();
        else {
            setPressureSensorRate(false);
            schedulePressureCalibrationTimeout();
        }
    }

    private void finishPressureCalibration() {
        mainHandler.removeCallbacks(pressureCalibrationTimeout);
        PressureGesturePolicy.CalibrationResult result =
                PressureGesturePolicy.calibrate(pressureCalibrationDeltas);
        Bundle extras = new Bundle();
        extras.putFloat(ConfigContract.KEY_PRESSURE_THRESHOLD,
                result.valid ? result.threshold : ConfigContract.DEFAULT_PRESSURE_THRESHOLD);
        extras.putInt(ConfigContract.KEY_PRESSURE_CALIBRATION_VALID_COUNT,
                result.validCount);
        extras.putBoolean(ConfigContract.KEY_PRESSURE_CALIBRATED, result.valid);
        reportPressureCalibration("report_pressure_calibration_result", extras);
        pressureCalibrationAttempts = 0;
        pressureCalibrationDeltas.clear();
        Log.i("Pressure calibration finished valid=" + result.valid
                + " validCount=" + result.validCount + " threshold=" + result.threshold);
    }

    private void reportPressureCalibration(String method, Bundle extras) {
        try {
            context.getContentResolver().call(ConfigContract.URI, method, null, extras);
        } catch (Throwable error) {
            Log.e("Cannot report pressure calibration", error);
        }
    }

    private boolean pressureInteractionBlocked() {
        return pressureInteractionStateBlocked() || inputMethodActive();
    }

    private boolean pressureInteractionStateBlocked() {
        return isLandscape() || imeVisible || shadeExpanded || controlCenterExpanded
                || android.os.SystemClock.uptimeMillis() < systemPanelTouchBlockUntil;
    }

    private void syncPressureDebugOverlay() {
        boolean positionReady = config.pressureShowPosition && runtimeReady();
        boolean calibrationReady = config.pressureCalibrationActive
                && displayInteractiveUnlocked();
        if (pressureCalibrationTimedOut || (!positionReady && !calibrationReady)
                || pressureInteractionBlocked()) {
            pressureDebugOverlay.remove();
            return;
        }
        if (!config.pressureTriggers.isEmpty()) {
            pressureDebugOverlay.show(config.pressureTriggers);
        } else {
            pressureDebugOverlay.remove();
        }
    }

    private boolean pressurePointInside(float x, float y, int width, int height) {
        PressureTrigger trigger = activePressureTrigger;
        return trigger != null && pressurePointInside(x, y, width, height, trigger);
    }

    private boolean pressurePointInside(float x, float y, int width, int height,
                                        PressureTrigger trigger) {
        return trigger != null && PressureGesturePolicy.isInsideCircle(x, y, width, height,
                trigger.centerXPercent, trigger.centerYPercent, trigger.radiusPercent);
    }

    private PressureTrigger pressureTriggerAt(float x, float y, int width, int height,
                                               boolean includeDisabled) {
        for (PressureTrigger trigger : config.pressureTriggers) {
            if ((includeDisabled || trigger.enabled)
                    && pressurePointInside(x, y, width, height, trigger)) return trigger;
        }
        return null;
    }

    private boolean beginPressureCandidate(float x, float y, Object inputMonitor,
                                           PressureTrigger trigger) {
        if (trigger == null) return false;
        pressureCandidate = true;
        pressureClaimed = false;
        pressureOrbShown = false;
        pressureOrbSuppressedForMotion = false;
        pressureDownX = x;
        pressureDownY = y;
        pressureCurrentX = x;
        pressureCurrentY = y;
        activePressureTrigger = trigger;
        // Do not reuse a sample from an earlier gesture. Sensor listening starts below,
        // and the first sample delivered after this touch establishes a fresh baseline.
        pressureBaseline = 0f;
        pressureBaselinePending = true;
        pressureInputMonitor = inputMonitor;
        pressureInputFromCornerCapture = inputMonitor == null
                && triggerCapture.isCapturing();
        pressurePulseRecognizer.start();
        // Do not start the expensive full-screen theme animation on every down. It is
        // shown lazily after a real pressure rise, so an ordinary fast swipe stays cheap.
        pressureDebugOverlay.hideOrb();
        if (config.pressureShowPosition) {
            pressureDebugOverlay.show(config.pressureTriggers);
        }
        setPressureSensorRate(true);
        mainHandler.removeCallbacks(pressureCandidateTimeout);
        mainHandler.postDelayed(pressureCandidateTimeout, PRESSURE_CANDIDATE_TIMEOUT_MS);
        Log.i("Pressure candidate armed x=" + Math.round(x) + " y=" + Math.round(y)
                + " baseline=" + pressureBaseline);
        return true;
    }

    private void updatePressureCandidate(float x, float y, int width, int height) {
        if (!pressureCandidate) return;
        if (!pressurePointInside(x, y, width, height, activePressureTrigger)) {
            cancelPressureGesture();
            return;
        }
        pressureCurrentX = x;
        pressureCurrentY = y;
    }

    private void cancelPressureGesture() {
        mainHandler.removeCallbacks(pressureCandidateTimeout);
        pressurePulseRecognizer.cancel();
        pressureOrbShown = false;
        pressureOrbSuppressedForMotion = false;
        pressureDebugOverlay.hideOrb();
        pressureCandidate = false;
        pressureClaimed = false;
        pressureInputMonitor = null;
        pressureBaseline = 0f;
        pressureBaselinePending = false;
        activePressureTrigger = null;
        pressureInputFromCornerCapture = false;
        setPressureSensorRate(false);
        pressureSelectionTrigger = null;
    }

    private void finishPressureCandidate() {
        mainHandler.removeCallbacks(pressureCandidateTimeout);
        pressurePulseRecognizer.cancel();
        pressureOrbShown = false;
        pressureOrbSuppressedForMotion = false;
        pressureDebugOverlay.hideOrb();
        pressureCandidate = false;
        pressureInputMonitor = null;
        pressureBaselinePending = false;
        activePressureTrigger = null;
        pressureInputFromCornerCapture = false;
        setPressureSensorRate(false);
        pressureSelectionTrigger = null;
    }

    private void suppressPressureOrbForMotion() {
        if (pressureOrbSuppressedForMotion) return;
        pressureOrbSuppressedForMotion = true;
        if (pressureOrbShown) {
            pressureOrbShown = false;
            pressureDebugOverlay.hideOrb();
        }
    }

    private void handlePressureSignal(PressurePulseRecognizer.Signal signal,
                                      boolean touchActive, boolean pressureWave) {
        if (signal == PressurePulseRecognizer.Signal.NONE) return;
        if (config.haptic && pressureWave && config.pressureSecondHapticEnabled) {
            vibratePressureStage();
        }
        PressureTrigger trigger = activePressureTrigger;
        executePressureAction(trigger == null ? config.pressureAction : trigger.action,
                touchActive, trigger);
    }

    private void executePressureAction(int action, boolean touchActive,
                                       PressureTrigger trigger) {
        if (!pressureCandidate) return;
        RuntimeTarget singleTarget = trigger == null ? null
                : pressureActionTargets.get(trigger.id);
        boolean actionAvailable = action == ConfigContract.PRESSURE_ACTION_HOME
                || action == ConfigContract.PRESSURE_ACTION_LOCK
                || action == ConfigContract.PRESSURE_ACTION_SCREENSHOT
                || action == ConfigContract.PRESSURE_ACTION_BACK
                || (action == ConfigContract.PRESSURE_ACTION_SINGLE_TARGET
                ? singleTarget != null
                : action == ConfigContract.PRESSURE_ACTION_CIRCULAR
                ? !pressureTargets.isEmpty()
                : !honeycombTargets.isEmpty()
                && config.honeycombEnabledFor(isLandscape()));
        if (!actionAvailable || pressureInteractionBlocked()) {
            Log.i("Pressure action unavailable action=" + action
                    + " honeycombTargets=" + honeycombTargets.size()
                    + " circularTargets=" + pressureTargets.size());
            finishPressureCandidate();
            return;
        }
        if (touchActive) {
            boolean inputTaken = pressureInputFromCornerCapture
                    || pilfer(pressureInputMonitor);
            if (!inputTaken) {
                Log.i("Pressure threshold reached but input takeover unavailable");
                finishPressureCandidate();
                return;
            }
            pressureClaimed = true;
            cancelCapturedCornerTap();
            outsideGestures.clearAll();
            imeDismissTap = false;
        } else {
            pressureClaimed = false;
        }
        boolean activated;
        if (action == ConfigContract.PRESSURE_ACTION_HOME) {
            activated = launchHomeScreen();
            if (activated) {
                finishPressureCandidate();
                resetFan();
                Log.i("Pressure gesture returned to home");
            }
        } else if (action == ConfigContract.PRESSURE_ACTION_LOCK) {
            activated = lockScreen();
            if (activated) finishPressureCandidate();
        } else if (action == ConfigContract.PRESSURE_ACTION_SCREENSHOT) {
            activated = takeScreenshot();
            if (activated) finishPressureCandidate();
        } else if (action == ConfigContract.PRESSURE_ACTION_BACK) {
            activated = dispatchBack();
            if (activated) finishPressureCandidate();
        } else if (action == ConfigContract.PRESSURE_ACTION_SINGLE_TARGET) {
            activated = singleTarget != null;
            if (activated) {
                launchPressureTarget(singleTarget, false, trigger);
                finishPressureCandidate();
            }
        } else if (action == ConfigContract.PRESSURE_ACTION_CIRCULAR) {
            activated = activatePressureCircular(pressureCurrentX, pressureCurrentY,
                    displayBounds().width(), displayBounds().height(), !touchActive);
            if (activated) {
                finishPressureCandidate();
                Log.i("Pressure gesture activated circular picker targets="
                        + pressureTargets.size() + " persistent=" + !touchActive);
            }
        } else {
            activated = activatePressureHoneycomb(pressureCurrentX, pressureCurrentY,
                    displayBounds().width(), displayBounds().height(), !touchActive);
            if (activated) {
                finishPressureCandidate();
                Log.i("Pressure gesture activated honeycomb persistent=" + !touchActive);
            }
        }
        if (!activated) {
            pressureClaimed = false;
            resetFan();
        }
    }

    private boolean launchHomeScreen() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(home);
            return true;
        } catch (Throwable error) {
            Log.e("Cannot return to home from pressure gesture", error);
            return false;
        }
    }

    private boolean lockScreen() {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power == null) return false;
            XposedHelpers.callMethod(power, "goToSleep", android.os.SystemClock.uptimeMillis());
            Log.i("Pressure gesture requested screen lock");
            return true;
        } catch (Throwable error) {
            Log.e("Cannot lock screen from pressure gesture", error);
            return false;
        }
    }

    private boolean dispatchBack() {
        int displayId = context.getDisplay() == null ? 0
                : context.getDisplay().getDisplayId();
        triggerCapture.dispatchBack(displayId);
        return true;
    }

    private boolean takeScreenshot() {
        if (takeScreenshotThroughCommandQueue()) return true;
        int displayId = context.getDisplay() == null ? 0
                : context.getDisplay().getDisplayId();
        // SYSRQ is the system screenshot key on AOSP and is a useful fallback on
        // HyperOS builds where the CommandQueue method was renamed.
        triggerCapture.dispatchKeyCode(android.view.KeyEvent.KEYCODE_SYSRQ, displayId);
        Log.i("Pressure gesture requested screenshot fallback");
        return true;
    }

    private boolean takeScreenshotThroughCommandQueue() {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> dependencyClass = XposedHelpers.findClass(
                    "com.android.systemui.Dependency", loader);
            Class<?> commandQueueClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.CommandQueue", loader);
            Object dependency = XposedHelpers.getStaticObjectField(
                    dependencyClass, "sDependency");
            if (dependency == null) return false;
            Object commandQueue = XposedHelpers.callMethod(
                    dependency, "getDependencyInner", commandQueueClass);
            if (commandQueue == null) return false;
            for (java.lang.reflect.Method method : commandQueue.getClass().getMethods()) {
                if (!"takeScreenshot".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length > 5) continue;
                Object[] args = new Object[types.length];
                int integerIndex = 0;
                for (int i = 0; i < types.length; i++) {
                    Class<?> type = types[i];
                    if (type == int.class || type == Integer.TYPE) {
                        args[i] = integerIndex++ == 0 ? 1 : 0;
                    } else if (type == boolean.class || type == Boolean.TYPE) {
                        args[i] = true;
                    } else if (type == long.class || type == Long.TYPE) {
                        args[i] = 0L;
                    } else {
                        args[i] = null;
                    }
                }
                method.setAccessible(true);
                method.invoke(commandQueue, args);
                Log.i("Pressure gesture requested screenshot through CommandQueue method="
                        + method);
                return true;
            }
        } catch (Throwable error) {
            Log.e("CommandQueue screenshot entry unavailable", error);
        }
        return false;
    }

    private boolean activatePressureCircular(float x, float y, int width, int height,
                                               boolean persistent) {
        if (pressureTargets.isEmpty()) return false;
        PressureTrigger trigger = activePressureTrigger;
        if (trigger == null) return false;
        pressureSelectionTrigger = trigger;
        pressureCircularActive = false;
        pressureHoneycombActive = false;
        pressureOverlayCaptureSuppressed = true;
        triggerCapture.remove();
        activeTargets = pressureTargets;
        activeSideList = true;
        activeSideFanList = false;
        activeSideRingList = true;
        corner = width * trigger.centerXPercent / 100f <= width / 2f
                ? GestureGeometry.Corner.LEFT
                : GestureGeometry.Corner.RIGHT;
        downX = pressureDownX;
        downY = pressureDownY;
        selected = -1;
        outsideCapture.remove();
        outsideGestures.clearAll();
        float centerX = width * trigger.centerXPercent / 100f;
        float centerY = height * trigger.centerYPercent / 100f;
        float pressureRadius = Math.min(width, height)
                * trigger.radiusPercent / 100f;
        sideIconDiameter = Math.max(28 * density,
                Math.min(config.iconSizeDp * density, 96 * density));
        float gap = 10 * density;
        float automaticRadius = GestureGeometry.sideRingRadius(
                activeTargets.size(), sideIconDiameter, gap);
        float desiredRadius = Math.max(pressureRadius + sideIconDiameter / 2f + gap,
                automaticRadius);
        float safeLeft = 12 * density;
        float safeTop = 12 * density;
        float safeRight = width - safeLeft;
        float safeBottom = height - 12 * density;
        float maximumRadius = Math.min(Math.min(centerX - safeLeft,
                        safeRight - centerX),
                Math.min(centerY - safeTop, safeBottom - centerY))
                - sideIconDiameter / 2f;
        sideRingRadius = Math.max(sideIconDiameter * 1.45f,
                Math.min(desiredRadius, Math.max(1f, maximumRadius)));
        sideRingCenterX = centerX;
        sideRingCenterY = centerY;
        sideListCenterX = centerX;
        sideListTop = centerY;
        sideRingRadius = Math.max(1f, sideRingRadius);
        if (persistent) {
            List<RuntimeTarget> circularTargets = activeTargets;
            overlay.showTouchableSideRingList(circularTargets, corner, sideRingCenterX,
                    sideRingCenterY, sideRingRadius, sideIconDiameter,
                    config.showSelectedAppName, false, config.fanAnimationsEnabled,
                    config.bottomAnimationSpeed, config.fanRevealAmount,
                    config.fanRotationDegrees, config.fanSelectionScalePercent,
                    config.fanSelectionRing, config.forceCircularIcons,
                    new FanOverlayController.WheelListener() {
                        @Override public void onLaunch(int index) {
                            if (index < 0 || index >= circularTargets.size()) return;
                            launchPressureTarget(circularTargets.get(index), false,
                                    pressureSelectionTrigger);
                            resetFan(false);
                            refreshTriggerCapture();
                        }

                        @Override public void onDismiss() {
                            clearPressureSelectionPressure(true);
                            pressureCircularActive = false;
                            pressureOverlayCaptureSuppressed = false;
                            refreshTriggerCapture();
                            refreshOutsideCapture();
                        }

                        @Override public void onSelectionChanged(int index) {
                            if (config.haptic) vibrateTick();
                            if (index >= 0 && index < circularTargets.size()) {
                                armPressureSelectionPressure(circularTargets.get(index));
                            } else {
                                clearPressureSelectionPressure(false);
                            }
                        }
                    });
            state = State.IDLE;
            pressureCircularActive = false;
            return true;
        }
        overlay.showSideRingList(activeTargets, corner, sideRingCenterX,
                sideRingCenterY, sideRingRadius, sideIconDiameter,
                config.showSelectedAppName, false, config.fanAnimationsEnabled,
                config.bottomAnimationSpeed, config.fanRevealAmount,
                config.fanRotationDegrees, config.fanSelectionScalePercent,
                config.fanSelectionRing, config.forceCircularIcons);
        overlay.setSelectionTransformLevel(config.selectionTransformLevel);
        state = State.ACTIVE;
        pressureCircularActive = true;
        triggerCapture.remove();
        lastHapticSelection = -1;
        updateSideListSelection(x, y);
        return true;
    }

    private void launchPressureTarget(RuntimeTarget target) {
        launchPressureTarget(target, false);
    }

    private void launchPressureTarget(RuntimeTarget target, boolean invertLaunchMode) {
        clearPressureSelectionPressure(true);
        launchPressureTarget(target, invertLaunchMode, null);
    }

    private void launchPressureTarget(RuntimeTarget target, boolean invertLaunchMode,
                                      PressureTrigger trigger) {
        clearPressureSelectionPressure(true);
        boolean openAsFreeform = trigger != null
                ? trigger.openAsFreeform : config.pressureOpenAsFreeform;
        if (invertLaunchMode) openAsFreeform = !openAsFreeform;
        if (openAsFreeform) {
            freeform.launch(target, config, this::refreshOutsideCapture);
            refreshOutsideCaptureAfterLaunch();
        } else {
            launchFullscreenTarget(target, false);
        }
    }

    private boolean pressureSelectionIsActive() {
        return pressureCircularActive || pressureHoneycombActive
                || honeycombOverlay.isVisible() || overlay.isWheelVisible();
    }

    private boolean pressureHeavyLaunchEnabledFor(PressureTrigger trigger) {
        return trigger != null ? trigger.heavyLaunchEnabled
                : config.pressureHeavyLaunchEnabled;
    }

    private void preparePressureSelectionForTouch() {
        boolean pickerActive = pressureSelectionIsActive();
        if (pickerActive && pressureSelectionPressureTarget != null
                && pressureHeavyLaunchEnabledFor(pressureSelectionTrigger)
                && pressureSensorConfigured()) {
            mainHandler.removeCallbacks(pressureSelectionSensorStop);
            pressureSelectionPressureBaseline = pressureLatestValue;
            pressureSelectionPressureBaselinePending = true;
            pressureSelectionPressureArmed = true;
            pressureSelectionPressureConsumed = false;
            setPressureSensorRate(true);
            return;
        }
        clearPressureSelectionPressure(!pickerActive
                && !config.pressureCalibrationActive);
    }

    private void armPressureSelectionPressure(RuntimeTarget target) {
        mainHandler.removeCallbacks(pressureSelectionSensorStop);
        if (target == null || !pressureHeavyLaunchEnabledFor(pressureSelectionTrigger)
                || !pressureSensorConfigured()) {
            clearPressureSelectionPressure(true);
            return;
        }
        pressureSelectionPressureTarget = target;
        pressureSelectionPressureBaseline = pressureLatestValue;
        pressureSelectionPressureBaselinePending = true;
        pressureSelectionPressureArmed = true;
        pressureSelectionPressureConsumed = false;
        setPressureSensorRate(true);
    }

    private void clearPressureSelectionPressure(boolean stopSensor) {
        mainHandler.removeCallbacks(pressureSelectionSensorStop);
        pressureSelectionPressureArmed = false;
        pressureSelectionPressureBaselinePending = false;
        pressureSelectionPressureTarget = null;
        pressureSelectionPressureConsumed = false;
        if (stopSensor) setPressureSensorRate(false);
        else mainHandler.postDelayed(pressureSelectionSensorStop,
                PRESSURE_SELECTION_SENSOR_IDLE_MS);
    }

    private void triggerPressureSelectionPressure() {
        if (!pressureSelectionPressureArmed || pressureSelectionPressureConsumed
                || !pressureSelectionIsActive() || pressureSelectionPressureTarget == null) {
            return;
        }
        RuntimeTarget target = pressureSelectionPressureTarget;
        pressureSelectionPressureConsumed = true;
        if (config.haptic && config.pressureSecondHapticEnabled) {
            vibratePressureStage();
        }
        honeycombOverlay.removeNow();
        overlay.removeNow();
        pressureHoneycombActive = false;
        pressureCircularActive = false;
        launchPressureTarget(target, true, pressureSelectionTrigger);
        outsideGestures.clearAll();
        resetFan(false);
        refreshTriggerCapture();
        Log.i("Pressure selection heavy launch toggled mode package=" + target.packageName);
    }

    private void refreshPressureSensor() {
        if (!displayInteractiveUnlocked()) {
            cancelPressureGesture();
            stopPressureSensor();
            return;
        }
        if (config.pressureCalibrationActive && !pressureCalibrationTimedOut
                && pressureSensor != null) {
            startPressureSensor(false);
        } else {
            cancelPressureGesture();
        }
    }

    private int sensorPeriodUs(boolean highRate) {
        int mode = config == null ? ConfigContract.PRESSURE_SENSOR_RATE_BALANCED
                : config.pressureSensorRateMode;
        if (mode == ConfigContract.PRESSURE_SENSOR_RATE_ECO) return 125_000;
        return 66_667;
    }

    private void startPressureSensor(boolean highRate) {
        if (!displayInteractiveUnlocked()
                || pressureSensorManager == null || pressureSensor == null) return;
        if (pressureSensorListening && pressureSensorHighRate == highRate) return;
        if (pressureSensorListening) {
            try { pressureSensorManager.unregisterListener(pressureSensorListener); }
            catch (Throwable ignored) { }
            pressureSensorListening = false;
        }
        try {
            pressureSensorListening = pressureSensorManager.registerListener(
                    pressureSensorListener, pressureSensor,
                    sensorPeriodUs(highRate),
                    pressureSensorHandler);
            pressureSensorHighRate = pressureSensorListening && highRate;
        } catch (Throwable error) {
            pressureSensorListening = false;
            pressureSensorHighRate = false;
            Log.e("Cannot register pressure sensor", error);
        }
    }

    private void setPressureSensorRate(boolean highRate) {
        if (!highRate) {
            if (highRatePressureSensorRequired()) {
                startPressureSensor(true);
            } else if (bottomSelectionPressureArmed || bottomHoneycombPressureArmed
                    || (config.pressureCalibrationActive && !pressureCalibrationTimedOut
                    && displayInteractiveUnlocked())) {
                startPressureSensor(false);
            } else {
                stopPressureSensor();
            }
            return;
        }
        if (!pressureSensorConfigured() && !config.pressureCalibrationActive) return;
        if (!pressureSensorListening || pressureSensorHighRate != highRate) {
            startPressureSensor(highRate);
        }
    }

    private boolean highRatePressureSensorRequired() {
        if (!displayInteractiveUnlocked()) return false;
        if (pressureCandidate) return true;
        if (!pressureSensorConfigured()) return false;
        return pressureSelectionPressureArmed && pressureSelectionPressureTarget != null
                && pressureHeavyLaunchEnabledFor(pressureSelectionTrigger)
                && pressureSelectionIsActive();
    }

    private void stopPressureSensor() {
        if (!pressureSensorListening || pressureSensorManager == null) return;
        try { pressureSensorManager.unregisterListener(pressureSensorListener); }
        catch (Throwable ignored) { }
        pressureSensorListening = false;
        pressureSensorHighRate = false;
    }

    private boolean runtimeReady() {
        return config.enabled && displayInteractiveUnlocked();
    }

    private boolean displayInteractiveUnlocked() {
        if (context.getDisplay() != null && context.getDisplay().getDisplayId() != 0) {
            return false;
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return (power == null || power.isInteractive())
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private boolean canStartBottom() {
        return runtimeReady() && !imeVisible && targets.size() >= 3
                && config.bottomEnabledFor(isLandscape());
    }

    private boolean canStartSide() {
        if (!runtimeReady() || !config.sideEnabledFor(isLandscape())) return false;
        int sideLayoutMode = currentSideLayoutMode();
        if (sideLayoutMode == ConfigContract.SIDE_LAYOUT_TASKS
                || sideLayoutMode == ConfigContract.SIDE_LAYOUT_SYSTEM_RECENTS) {
            return true;
        }
        return sideLayoutMode == ConfigContract.SIDE_LAYOUT_HONEYCOMB
                ? config.honeycombEnabledFor(isLandscape()) && !honeycombTargets.isEmpty()
                : !sideTargets.isEmpty();
    }

    private int currentSideLayoutMode() {
        return config.sideLayoutModeFor(isLandscape());
    }

    private boolean isLandscape() {
        Rect display = displayBounds();
        return display.width() > display.height();
    }

    private void refreshTriggerCapture() {
        if (inputSources.usesNativeInput() || pressureOverlayCaptureSuppressed
                || pressureHoneycombActive || pressureCircularActive) {
            triggerCapture.update(false, config.hotWidthPercent, config.hotHeightPercent,
                    0, 0);
            return;
        }
        triggerCapture.update(canStartBottom(), config.hotWidthPercent, config.hotHeightPercent,
                0, 0);
    }

    private void refreshGeometryAndCaptures(String reason) {
        refreshDisplayGeometry();
        refreshTriggerCapture();
        refreshOutsideCapture();
        Log.i("Display geometry refreshed reason=" + reason + " bounds="
                + cachedDisplayBounds + " landscape=" + isLandscape());
    }

    private void ensureDisplayGeometryCurrent() {
        if (windowManager == null) return;
        try {
            Rect actual = windowManager.getCurrentWindowMetrics().getBounds();
            if (actual.width() == cachedDisplayBounds.width()
                    && actual.height() == cachedDisplayBounds.height()) return;
            refreshDisplayGeometry();
            refreshTriggerCapture();
            Log.i("Display geometry corrected on input bounds=" + cachedDisplayBounds);
        } catch (Throwable ignored) { }
    }

    private void refreshOutsideCaptureAfterLaunch() {
        refreshOutsideCapture();
        mainHandler.postDelayed(this::refreshOutsideCapture, 80L);
        mainHandler.postDelayed(this::refreshOutsideCapture, 240L);
        mainHandler.postDelayed(this::clearFailedLaunchCapture, 6100L);
    }

    private void clearFailedLaunchCapture() {
        if (!freeform.abandonStalePendingLaunch(6000L)) return;
        outsideCapture.remove();
        outsideGestures.clearAll();
        refreshOutsideCapture();
        Log.i("Outside capture removed because selected app did not enter freeform");
    }

    private void refreshOutsideCapture() {
        if (!runtimeReady() || !outsideEnabledForCurrentDisplay()) {
            outsideCapture.remove();
            outsideGestures.clearAll();
            return;
        }
        if (outsideCaptureWindowTransitionBlocked) {
            outsideCapture.remove();
            return;
        }
        if (honeycombOverlay.isVisible() || shadeExpanded || controlCenterExpanded
                || android.os.SystemClock.uptimeMillis() < systemPanelTouchBlockUntil) {
            outsideCapture.remove();
            return;
        }
        Rect pending = freeform.pendingLaunchBounds();
        List<Rect> visibleWindows = freeform.visibleFreeformBounds();
        if (pending != null && !visibleWindows.contains(pending)) {
            visibleWindows.add(pending);
        }
        boolean hasInteractiveWindow = freeform.hasInteractiveFreeform();
        if (pending == null && !hasInteractiveWindow) visibleWindows.clear();
        boolean inputMethodActive = hasInteractiveWindow && inputMethodActive();
        Rect ime = hasInteractiveWindow ? visibleImeBounds() : null;
        if (inputMethodActive && ime == null) {
            // A floating IME often reports visibility without a stable inset rectangle.
            // A guessed full-width bottom cutout would cover the real keyboard position,
            // so pause capture until HyperOS reports a reliable frame or hides the IME.
            outsideCapture.remove();
            return;
        }
        if (!visibleWindows.isEmpty()) {
            Rect display = displayBounds();
            int statusBarHeight = statusBarGestureHeight();
            visibleWindows.add(new Rect(display.left, display.top,
                    display.right, Math.min(display.bottom,
                    display.top + statusBarHeight)));
        }
        if (ime == null && !visibleWindows.isEmpty()
                && (triggerCapture.isCapturing() || inputSources.usesNativeInput())) {
            Rect display = displayBounds();
            int hotWidth = Math.round(display.width() * config.hotWidthPercent / 100f);
            int hotHeight = Math.round(display.height() * config.hotHeightPercent / 100f);
            visibleWindows.add(new Rect(display.left, display.bottom - hotHeight,
                    display.left + hotWidth, display.bottom));
            visibleWindows.add(new Rect(display.right - hotWidth,
                    display.bottom - hotHeight, display.right, display.bottom));
        }
        // Edge DOWN events must be captured too; waiting until UP to pilfer lets the
        // application underneath receive a real click. Side swipes are handled below.
        outsideCapture.update(visibleWindows, ime, 0, 0);
    }

    private int statusBarGestureHeight() {
        return cachedStatusBarGestureHeight;
    }

    private void refreshDisplayGeometry() {
        int fallbackSide = Math.round(32 * density);
        int sideExtra = Math.round(8 * density);
        try {
            WindowMetrics metrics = windowManager == null ? null
                    : windowManager.getCurrentWindowMetrics();
            if (metrics == null) throw new IllegalStateException("Window metrics unavailable");
            cachedDisplayBounds.set(metrics.getBounds());
            WindowInsets insets = metrics.getWindowInsets();
            cachedDisplaySafeInsets = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
            int systemLeft = gestures.left > 0 ? gestures.left : fallbackSide;
            int systemRight = gestures.right > 0 ? gestures.right : fallbackSide;
            cachedSystemSideGestureInsets = Insets.of(systemLeft, 0, systemRight, 0);
            cachedSideGestureReserves = Insets.of(
                    gestures.left > 0 ? gestures.left + sideExtra : fallbackSide, 0,
                    gestures.right > 0 ? gestures.right + sideExtra : fallbackSide, 0);
        } catch (Throwable error) {
            cachedDisplayBounds.set(0, 0,
                    context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels);
            cachedDisplaySafeInsets = Insets.of(0, Math.round(48 * density), 0,
                    Math.round(32 * density));
            cachedSystemSideGestureInsets = Insets.of(fallbackSide, 0, fallbackSide, 0);
            cachedSideGestureReserves = Insets.of(fallbackSide, 0, fallbackSide, 0);
        }
        int resourceHeight = 0;
        try {
            int resourceId = context.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (resourceId != 0) {
                resourceHeight = context.getResources().getDimensionPixelSize(resourceId);
            }
        } catch (Throwable ignored) {}
        cachedStatusBarGestureHeight = Math.max(cachedDisplaySafeInsets.top,
                Math.max(resourceHeight, Math.round(48 * density)));
    }

    private float sideSafeTop(int height) {
        Insets safe = displaySafeInsets();
        return Math.max(safe.top, height * config.sideTopSafeMarginPercent / 100f);
    }

    private float sideSafeBottom(int height, float hotHeight) {
        Insets safe = displaySafeInsets();
        return Math.min(height - safe.bottom,
                height - hotHeight - 24 * density);
    }

    private Rect displayBounds() {
        return cachedDisplayBounds;
    }

    private boolean outsideEnabledForCurrentDisplay() {
        Rect display = displayBounds();
        return config.outsideEnabledFor(display.width() > display.height());
    }

    private Insets sideGestureReserves() {
        return cachedSideGestureReserves;
    }

    private Insets systemSideGestureInsets() {
        return cachedSystemSideGestureInsets;
    }

    private Insets displaySafeInsets() {
        return cachedDisplaySafeInsets;
    }

    private Rect visibleImeBounds() {
        try {
            WindowMetrics metrics = context.getSystemService(WindowManager.class)
                    .getCurrentWindowMetrics();
            WindowInsets windowInsets = metrics.getWindowInsets();
            HyperOsFreeformBridge.ImeState shellIme = freeform.inputMethodState();
            boolean insetsVisible = windowInsets.isVisible(WindowInsets.Type.ime());
            boolean visible = shellIme.visible || insetsVisible || imeVisible;
            if (!visible) return null;
            Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
            Rect display = new Rect(metrics.getBounds());
            // A docked keyboard contributes a bottom inset. Floating keyboards usually
            // only report visibility/height, not their screen position; do not invent a
            // bottom-aligned rectangle for them.
            int visibleHeight = Math.max(ime.bottom, shellIme.bottomInset);
            if (visibleHeight <= 0 || visibleHeight >= display.height()) return null;
            return new Rect(display.left, display.bottom - visibleHeight,
                    display.right, display.bottom);
        } catch (Throwable error) {
            return null;
        }
    }

    private boolean inputMethodActive() {
        HyperOsFreeformBridge.ImeState shellIme = freeform.inputMethodState();
        if (shellIme.visible || imeVisible) return true;
        try {
            WindowMetrics metrics = context.getSystemService(WindowManager.class)
                    .getCurrentWindowMetrics();
            return metrics.getWindowInsets().isVisible(WindowInsets.Type.ime());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean insideTrackedOrIme(float x, float y, Rect tracked, Rect ime) {
        return GestureGeometry.insideEither(x, y,
                tracked.left, tracked.top, tracked.right, tracked.bottom,
                ime == null ? 0 : ime.left,
                ime == null ? 0 : ime.top,
                ime == null ? 0 : ime.right,
                ime == null ? 0 : ime.bottom,
                ime != null);
    }

    private void finishOutsideTap(float x, float y, long eventTime, Object inputMonitor) {
        Runnable claimInput = () -> pilfer(inputMonitor);
        if (imeDismissTap) {
            outsideGestures.onUpImmediate(x, y, eventTime, claimInput, () -> {
                outsideGestures.clearAll();
                freeform.hideInputMethodIfNeeded();
                Log.i("Outside tap consumed to hide current input method");
            });
            return;
        }
        outsideGestures.onUp(x, y, eventTime, claimInput);
    }

    private void onCapturedOutsideTouch(int action, float x, float y,
                                        long downTime, long eventTime) {
        if (!outsideEnabledForCurrentDisplay()) {
            outsideCapture.remove();
            outsideGestures.clearAll();
            clearCapturedOutsideState();
            return;
        }
        if (systemPanelInputBlocked()) {
            outsideCapture.remove();
            outsideGestures.clearAll();
            clearCapturedOutsideState();
            return;
        }
        int width = displayBounds().width();
        if (action == MotionEvent.ACTION_DOWN) {
            Insets systemSides = systemSideGestureInsets();
            boolean nativeSideEdge = GestureGeometry.sideAt(x, width,
                    systemSides.left, systemSides.right) != null;
            // The capture window owns this touch stream. A side-edge tap therefore
            // still has to enter the outside-tap recognizer; consuming it here without
            // arming the recognizer leaves an apparently dead strip on both sides.
            // Keep the wider side tolerance so a real back swipe yields instead of
            // being mistaken for a tap. The global input monitor continues handling
            // that yielded swipe and preserves the system back gesture.
            if (!nativeSideEdge) ignoredCaptureDownTime = downTime;
            capturedImeDismissTap = false;
            Rect ime = visibleImeBounds();
            boolean imeActive = imeVisible || ime != null;
            float leftReserve = nativeSideEdge ? systemSides.left : 0f;
            float rightReserve = nativeSideEdge ? systemSides.right : 0f;
            Rect pending = freeform.pendingLaunchBounds();
            if (pending != null) {
                outsideGestures.onDown(x, y, pending, width,
                        leftReserve, rightReserve, eventTime);
                return;
            }
            Rect tracked = freeform.trackedBounds();
            if (tracked == null) {
                outsideGestures.onCancel();
                return;
            }
            if (insideTrackedOrIme(x, y, tracked, ime)) {
                outsideGestures.onCancel();
                return;
            }
            capturedImeDismissTap = imeActive;
            outsideGestures.onDown(x, y, tracked, width,
                    leftReserve, rightReserve, eventTime);
            if (nativeSideEdge) {
                Log.i("Outside side-edge tap armed through capture window");
            }
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            outsideGestures.onMove(x, y);
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (capturedImeDismissTap) {
                outsideGestures.onUpImmediate(x, y, eventTime, () -> {}, () -> {
                    outsideGestures.clearAll();
                    freeform.hideInputMethodIfNeeded();
                    mainHandler.postDelayed(this::refreshOutsideCapture, 80L);
                    mainHandler.postDelayed(this::refreshOutsideCapture, 260L);
                    mainHandler.postDelayed(this::refreshOutsideCapture, 700L);
                    Log.i("Captured outside tap consumed to hide current input method");
                });
            } else {
                outsideGestures.onUp(x, y, eventTime, () -> {});
            }
            clearCapturedOutsideState();
            return;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            clearCapturedOutsideState();
            outsideGestures.onCancel();
        }
    }

    private void clearCapturedOutsideState() {
        capturedImeDismissTap = false;
    }

    private boolean systemPanelInputBlocked() {
        return shadeExpanded || controlCenterExpanded
                || android.os.SystemClock.uptimeMillis() < systemPanelTouchBlockUntil;
    }

    private void performOutsideAction(boolean doubleTap) {
        if (!outsideEnabledForCurrentDisplay()) {
            outsideGestures.clearAll();
            return;
        }
        if (systemPanelInputBlocked()) {
            outsideGestures.clearAll();
            Log.i("Ignored pending outside action while a system panel is active");
            return;
        }
        int action = doubleTap ? config.outsideDoubleAction : config.outsideSingleAction;
        boolean handled;
        Log.i("Outside tap=" + (doubleTap ? "double" : "single") + " action=" + action);
        switch (action) {
            case ConfigContract.ACTION_CLOSE:
                handled = freeform.dismissTrackedOrPendingLaunch();
                break;
            case ConfigContract.ACTION_PIN:
                handled = freeform.miniTrackedOrPendingLaunch();
                break;
            case ConfigContract.ACTION_FULLSCREEN:
                handled = freeform.fullscreenTrackedOrPendingLaunch();
                break;
            case ConfigContract.ACTION_EDGE_PIN:
                handled = freeform.edgePinTrackedOrPendingLaunch();
                break;
            default:
                Log.i("Outside " + (doubleTap ? "double" : "single") + " tap: no action");
                return;
        }
        if (handled) {
            outsideGestures.clearAll();
            suspendOutsideCaptureForWindowTransition("outside action=" + action);
        }
    }

    private void suspendOutsideCaptureForWindowTransition(String reason) {
        outsideCaptureWindowTransitionBlocked = true;
        long generation = ++outsideCaptureWindowTransitionGeneration;
        outsideCapture.remove();
        outsideGestures.clearAll();
        mainHandler.postDelayed(() -> settleOutsideCaptureWindowTransition(generation), 180L);
        mainHandler.postDelayed(() -> settleOutsideCaptureWindowTransition(generation), 500L);
        mainHandler.postDelayed(() -> settleOutsideCaptureWindowTransition(generation), 900L);
        mainHandler.postDelayed(() -> finishOutsideCaptureWindowTransition(generation), 1400L);
        Log.i("Outside capture paused for window transition reason=" + reason);
    }

    private void settleOutsideCaptureWindowTransition(long generation) {
        if (generation != outsideCaptureWindowTransitionGeneration
                || !outsideCaptureWindowTransitionBlocked) return;
        if (!freeform.hasInteractiveFreeform()) {
            outsideCaptureWindowTransitionBlocked = false;
            refreshOutsideCapture();
            Log.i("Outside capture transition settled in non-interactive state");
        }
    }

    private void finishOutsideCaptureWindowTransition(long generation) {
        if (generation != outsideCaptureWindowTransitionGeneration
                || !outsideCaptureWindowTransitionBlocked) return;
        outsideCaptureWindowTransitionBlocked = false;
        refreshOutsideCapture();
        Log.i("Outside capture transition timeout restored current state");
    }

    private void releaseOutsideCaptureTransitionIfSettled() {
        if (!outsideCaptureWindowTransitionBlocked || freeform.hasInteractiveFreeform()) return;
        outsideCaptureWindowTransitionBlocked = false;
        outsideCaptureWindowTransitionGeneration++;
    }

    private boolean pilfer(Object inputMonitor) {
        if (inputMonitor == null) return false;
        try {
            XposedHelpers.callMethod(inputMonitor, "pilferPointers");
            return true;
        } catch (Throwable error) {
            Log.e("Cannot pilfer input pointers", error);
            return false;
        }
    }

    private void vibrateTick() {
        try {
            VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = context.getSystemService(VibratorManager.class);
                if (manager != null) manager.getDefaultVibrator().vibrate(effect);
            } else {
                Vibrator vibrator = context.getSystemService(Vibrator.class);
                if (vibrator != null) vibrator.vibrate(effect);
            }
        } catch (Throwable error) {
            Log.e("Haptic feedback failed", error);
        }
    }

    private void vibratePressureStage() {
        if (!config.pressureSecondHapticEnabled) return;
        try {
            PressureHapticFeedback.vibrate(context);
        } catch (Throwable error) {
            Log.e("Pressure haptic feedback failed", error);
        }
    }

    private void reloadConfig() {
        configReloadPending = false;
        try {
            Bundle bundle = context.getContentResolver().call(ConfigContract.URI, "get", null, null);
            GestureConfig next = GestureConfig.from(bundle);
            // 用户在设置页点了「重新连接手势输入」时会写一个递增时间戳；此处检出并处理。
            // 放在配置加载里而不是只挂 ContentObserver，是因为 Bundle 就是唯一数据源，
            // 判定"值是否变化"最可靠（避免 observer 被多次触发时重复重连）。
            consumeUserRequestedInputReconnect(bundle);
            ArrayList<RuntimeTarget> resolved = new ArrayList<>();
            ArrayList<RuntimeTarget> resolvedHoneycomb = new ArrayList<>();
            ArrayList<RuntimeTarget> resolvedSide = new ArrayList<>();
            ArrayList<RuntimeTarget> resolvedPressure = new ArrayList<>();
            Map<Integer, RuntimeTarget> resolvedPressureActionTargets = new HashMap<>();
            PackageManager packageManager = context.getPackageManager();
            Map<String, RuntimeTarget> targetCache = new HashMap<>();
            for (GestureConfig.TargetSpec target : next.targets) {
                try {
                    RuntimeTarget runtimeTarget = resolveTarget(packageManager, target,
                            targetCache);
                    if (runtimeTarget != null) resolved.add(runtimeTarget);
                } catch (Throwable error) {
                    Log.e("Configured target is unavailable", error);
                }
            }
            for (GestureConfig.TargetSpec target : next.honeycombTargets) {
                try {
                    RuntimeTarget runtimeTarget = resolveTarget(packageManager, target,
                            targetCache);
                    if (runtimeTarget != null) resolvedHoneycomb.add(runtimeTarget);
                } catch (Throwable error) {
                    Log.e("Configured honeycomb target is unavailable", error);
                }
            }
            for (GestureConfig.TargetSpec target : next.sideTargets) {
                try {
                    RuntimeTarget runtimeTarget = resolveTarget(packageManager, target,
                            targetCache);
                    if (runtimeTarget != null) resolvedSide.add(runtimeTarget);
                } catch (Throwable error) {
                    Log.e("Configured side target is unavailable", error);
                }
            }
            for (GestureConfig.TargetSpec target : next.pressureTargets) {
                try {
                    RuntimeTarget runtimeTarget = resolveTarget(packageManager, target,
                            targetCache);
                    if (runtimeTarget != null) resolvedPressure.add(runtimeTarget);
                } catch (Throwable error) {
                    Log.e("Configured pressure target is unavailable", error);
                }
            }
            for (PressureTrigger trigger : next.pressureTriggers) {
                if (trigger.target == null) continue;
                try {
                    RuntimeTarget runtimeTarget = resolveAppTarget(packageManager,
                            trigger.target, targetCache);
                    if (runtimeTarget != null) {
                        resolvedPressureActionTargets.put(trigger.id, runtimeTarget);
                    }
                } catch (Throwable error) {
                    Log.e("Configured pressure single target is unavailable", error);
                }
            }
            if (resolved.size() > next.fanMaxTargets) {
                resolved.subList(next.fanMaxTargets, resolved.size()).clear();
            }
            if (resolvedHoneycomb.size() > next.honeycombMaxTargets) {
                resolvedHoneycomb.subList(next.honeycombMaxTargets,
                        resolvedHoneycomb.size()).clear();
            }
            if (resolvedSide.size() > next.sideMaxTargets) {
                resolvedSide.subList(next.sideMaxTargets, resolvedSide.size()).clear();
            }
            if (resolvedPressure.size() > next.fanMaxTargets) {
                resolvedPressure.subList(next.fanMaxTargets, resolvedPressure.size()).clear();
            }
            List<RuntimeTarget> nextTargets = Collections.unmodifiableList(resolved);
            List<RuntimeTarget> nextHoneycombTargets = Collections.unmodifiableList(
                    resolvedHoneycomb);
            List<RuntimeTarget> nextSideTargets = Collections.unmodifiableList(resolvedSide);
            List<RuntimeTarget> nextPressureTargets = Collections.unmodifiableList(
                    resolvedPressure);
            configRetryCount = 0;
            configRetryScheduled = false;
            mainHandler.post(() -> {
                if (wheelSessionActive || overlay.isWheelVisible()) {
                    wheelSessionActive = false;
                    overlay.removeNow();
                }
                syncPressureCalibrationSessions(config, next);
                config = next;
                pressureDebugOverlay.setOrbTheme(next.pressureOrbTheme);
                pressureDebugOverlay.setOrbSizePercent(next.pressureOrbSizePercent);
                outsideGestures.setDoubleTapTimeout(next.outsideTapWindowMs);
                targets = nextTargets;
                honeycombTargets = nextHoneycombTargets;
                sideTargets = nextSideTargets;
                pressureTargets = nextPressureTargets;
                pressureActionTargets = Collections.unmodifiableMap(
                        new HashMap<>(resolvedPressureActionTargets));
                if (activeTargets.isEmpty()) activeTargets = nextTargets;
                freeform.updateConfig(next);
                refreshPressureSensor();
                syncPressureDebugOverlay();
                if (!next.honeycombEnabled && honeycombOverlay.isVisible()) {
                    honeycombOverlay.dismiss();
                }
                if (!next.enabled || nextTargets.size() < 3) resetFan();
                refreshTriggerCapture();
                refreshOutsideCapture();
                Log.i("Configuration loaded targets=" + nextTargets.size() + " trigger="
                        + next.triggerPercent + "% selection=" + next.selectionRadiusPercent
                        + "% hot=" + next.hotWidthPercent + "x" + next.hotHeightPercent
                        + "% icon=" + next.iconSizeDp + "dp side="
                        + next.sideGestureEnabled + "@" + next.sideTriggerPercent
                        + "% sideHold=" + next.sideHoldEnabled + "@"
                        + next.sideHoldDelayMs + "ms"
                        + " sideIcon=" + next.sideIconSizeDp + "dp safeTop="
                        + next.sideTopSafeMarginPercent + "% names="
                        + next.showSelectedAppName + " follow=" + next.sideFollowFinger
                        + " layout=" + next.sidePortraitLayoutMode + "/"
                        + next.sideLandscapeLayoutMode + " ringSize="
                        + next.sideRingSizePercent + "%"
                        + " wheel=" + next.sideWheelMode + " reverseCancel="
                        + next.sideReverseCancelPercent + "% animation="
                        + next.fanAnimationsEnabled + "@" + next.fanAnimationSpeed
                        + "% reveal=" + next.fanRevealAmount + "% rotation="
                        + next.fanRotationDegrees + "deg ring=" + next.fanSelectionRing
                        + " circularIcons=" + next.forceCircularIcons
                        + " honeycomb=" + next.honeycombEnabled + "/"
                        + nextHoneycombTargets.size() + " mode=" + next.honeycombMode
                        + " bottomFullscreen=" + next.bottomPortraitFullscreen + "/"
                        + next.bottomLandscapeFullscreen
                        + " sideFullscreen=" + next.sidePortraitFullscreen + "/"
                        + next.sideLandscapeFullscreen
                        + " bottomFreeform=" + next.bottomPortraitHoneycombFreeform + "/"
                        + next.bottomLandscapeHoneycombFreeform
                        + " blankSettle=" + next.bottomHoneycombSettleMs
                        + "ms returnToFan=" + next.triggerPercent + "% taskLayout="
                        + next.sideTaskLayoutMode + " outsideTap="
                        + next.outsideTapWindowMs + "ms outside="
                        + next.outsidePortraitEnabled + "/"
                        + next.outsideLandscapeEnabled + " honeycombFollow="
                        + next.honeycombFollowFinger + " pressure="
                        + next.pressureGestureEnabled + "/" + next.pressureCalibrated
                        + " threshold=" + next.pressureThreshold
                        + " action=" + next.pressureAction + " freeform="
                        + next.pressureOpenAsFreeform
                        + " heavyLaunch=" + next.pressureHeavyLaunchEnabled
                        + " circularTargets=" + nextPressureTargets.size());
            });
        } catch (Throwable error) {
            Log.e("Cannot read module configuration", error);
            scheduleConfigRetry();
        } finally {
            configLoadQueued = false;
            if (configReloadPending) requestConfigReload();
        }
    }

    private void syncPressureCalibrationSessions(GestureConfig previous,
                                                 GestureConfig next) {
        if (next.pressureCalibrationActive && !previous.pressureCalibrationActive) {
            cancelPressureGesture();
            pressureCalibrationTimedOut = false;
            pressureCalibrationAttempts = 0;
            pressureCalibrationDeltas.clear();
            pressureCalibrationPressActive = false;
            pressureCalibrationMovedOutside = false;
            pressureCalibrationPeakDelta = 0f;
            schedulePressureCalibrationTimeout();
        } else if (!next.pressureCalibrationActive) {
            pressureCalibrationTimedOut = false;
            mainHandler.removeCallbacks(pressureCalibrationTimeout);
            pressureCalibrationPressActive = false;
            pressureCalibrationMovedOutside = false;
            if (previous.pressureCalibrationActive) {
                pressureCalibrationAttempts = 0;
                pressureCalibrationDeltas.clear();
            }
        }
    }

    private void schedulePressureCalibrationTimeout() {
        mainHandler.removeCallbacks(pressureCalibrationTimeout);
        mainHandler.postDelayed(pressureCalibrationTimeout,
                PRESSURE_CALIBRATION_INACTIVITY_TIMEOUT_MS);
    }

    private synchronized void requestConfigReload() {
        if (configLoadQueued) {
            configReloadPending = true;
            return;
        }
        configLoadQueued = true;
        configHandler.post(this::reloadConfig);
    }

    /**
     * 处理用户在设置里发起的「重新连接手势输入」请求。
     *
     * <p>设置 App 与模块不在同一进程，无法直调本类方法；用户在设置页点按钮时会写一个
     * 递增的时间戳到 {@link ConfigContract#KEY_INPUT_RECONNECT_AT}，并通过既有的
     * {@code notifyChange(RUNTIME_URI)} 通路送达。本方法观察该键是否发生了变化，
     * 变化才执行重连，避免每次配置刷新都无谓重建通道。
     *
     * <p>为什么需要这个入口：原生手势通道存在"注册动作成功、inputflinger 未真正投递"
     * 的情况（见 {@link #onChannelVerifyTimeout()} 的说明）。模块无从可靠判断
     * "通道是否真的坏了"（用户没碰屏幕时同样收不到事件），**但用户能直接感知手势灵不灵**。
     * 因此把判据交给用户、把动作留给模块：这是最诚实、也最不需要猜测的设计。
     */
    private void consumeUserRequestedInputReconnect(Bundle bundle) {
        if (bundle == null) return;
        long requestedAt = bundle.getLong(ConfigContract.KEY_INPUT_RECONNECT_AT, 0L);
        if (requestedAt <= 0 || requestedAt == lastInputReconnectAt) return;
        lastInputReconnectAt = requestedAt;
        Log.i("User requested input channel reconnect at=" + requestedAt);
        mainHandler.post(this::reconnectInputChannel);
    }

    /**
     * 重建原生手势输入通道（用户主动触发的修复动作）。
     *
     * <p>链路：先停掉当前监听（释放 InputMonitor 与 InputEventReceiver），
     * 再重新挂载；成功则重开观察窗确认"真的在收事件"。
     * 若原生通道本就未接入（corner fallback 模式），只重载配置并如实告知。
     */
    private void reconnectInputChannel() {
        InputSource source = RomBridge.get().input();
        boolean wasNative = inputSources.usesNativeInput();
        boolean reattached = false;
        if (wasNative) {
            try {
                source.stop();
            } catch (Throwable error) {
                Log.e("Cannot stop gesture monitor before reconnect", error);
            }
            failNativeInputRegistration();
            BridgeResult<Void> restarted = source.start(context, event -> {
                if (!(event instanceof MotionEvent)) return;
                onMotion((MotionEvent) event, source.monitorHandle());
            });
            if (restarted.isOk()) {
                completeNativeInputRegistration();
                beginChannelVerification();
                reattached = true;
            } else {
                Log.e("Input channel reconnect failed code=" + restarted.code()
                        + " detail=" + restarted.detail(), new IllegalStateException(
                        "user-requested gesture channel reconnect failed"));
            }
        }
        // 无论重挂成败都要刷新几何与捕获窗口：用户点这个按钮多半是因为手势没反应，
        // 顺手把显示几何、触发捕获、外面的挖洞层都重算一遍，覆盖尽可能多的失效情形。
        refreshGeometryAndCaptures("user requested input reconnect");
        refreshPressureSensor();
        boolean done = reattached || !wasNative;
        reportStatus(done ? (reattached ? "已重新连接手势输入" : "手势输入已刷新")
                : "手势输入重连失败，请尝试重启系统界面");
        Log.i("Input channel reconnect finished reattached=" + reattached
                + " wasNative=" + wasNative);
    }

    private RuntimeTarget resolveTarget(PackageManager packageManager,
                                        GestureConfig.TargetSpec target) throws Exception {
        if (target.isShortcut()) {
            return new RuntimeTarget(target.packageName, target.shortcutId,
                    target.shortcutIntentUri,
                    target.shortcutLabel.isEmpty() ? target.shortcutId
                            : target.shortcutLabel,
                    ShortcutIconLoader.load(context, target.packageName,
                            target.shortcutId, target.userId), target.userId);
        }
        ComponentName component = ComponentName.unflattenFromString(target.component);
        if (component == null) return null;
        ActivityInfo info = packageManager.getActivityInfo(component, 0);
        CharSequence label = info.loadLabel(packageManager);
        return new RuntimeTarget(component,
                label == null ? component.getPackageName() : label.toString(),
                info.loadIcon(packageManager), target.userId);
    }

    private RuntimeTarget resolveTarget(PackageManager packageManager,
                                        GestureConfig.TargetSpec target,
                                        Map<String, RuntimeTarget> cache) throws Exception {
        String key = target.component + '\u0000' + target.packageName + '\u0000'
                + target.shortcutId + '\u0000' + target.shortcutIntentUri + '\u0000'
                + target.userId;
        RuntimeTarget cached = cache.get(key);
        if (cached != null) return cached;
        RuntimeTarget resolved = resolveTarget(packageManager, target);
        if (resolved != null) cache.put(key, resolved);
        return resolved;
    }

    private RuntimeTarget resolveAppTarget(PackageManager packageManager, AppTarget target,
                                           Map<String, RuntimeTarget> cache) throws Exception {
        String key = target.component + '\u0000' + target.shortcutPackage + '\u0000'
                + target.shortcutId + '\u0000' + target.shortcutIntentUri + '\u0000'
                + target.userId;
        RuntimeTarget cached = cache.get(key);
        if (cached != null) return cached;
        RuntimeTarget resolved;
        if (target.isShortcut()) {
            resolved = new RuntimeTarget(target.shortcutPackage, target.shortcutId,
                    target.shortcutIntentUri,
                    target.shortcutLabel == null || target.shortcutLabel.isEmpty()
                            ? target.shortcutId : target.shortcutLabel,
                    ShortcutIconLoader.load(context, target.shortcutPackage,
                            target.shortcutId, target.userId), target.userId);
        } else {
            ComponentName component = target.componentName();
            if (component == null) return null;
            ActivityInfo info = packageManager.getActivityInfo(component, 0);
            CharSequence label = info.loadLabel(packageManager);
            resolved = new RuntimeTarget(component,
                    label == null ? component.getPackageName() : label.toString(),
                    info.loadIcon(packageManager), target.userId);
        }
        if (resolved != null) cache.put(key, resolved);
        return resolved;
    }


    private void scheduleConfigRetry() {
        if (configRetryScheduled || configRetryCount >= 5) return;
        long delay = Math.min(8000, 1000L << configRetryCount);
        configRetryCount++;
        configRetryScheduled = true;
        configHandler.postDelayed(() -> {
            configRetryScheduled = false;
            requestConfigReload();
        }, delay);
        Log.i("Configuration reload retry scheduled attempt=" + configRetryCount
                + " delayMs=" + delay);
    }

    private void requestOnDemandConfigReload() {
        if (!targets.isEmpty() || configRetryScheduled) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOnDemandConfigReload < 2000) return;
        lastOnDemandConfigReload = now;
        requestConfigReload();
    }

    private void updateInterfaceStatusAsync() {
        configHandler.post(this::updateInterfaceStatus);
    }

    private void updateInterfaceStatus() {
        if (inputSources.usesNativeInput()) {
            reportStatus(freeform.hasController()
                    ? "HyperOS 原生接口已连接" : "手势接口已连接，等待小窗控制器");
            return;
        }
        if (cornerFallbackAvailable) {
            reportStatus(freeform.hasController()
                    ? "底角兼容输入已连接" : "底角兼容输入已连接，等待小窗控制器");
            return;
        }
        reportStatus(freeform.hasController()
                ? "小窗接口已连接，等待手势输入" : "底角兼容输入不可用");
    }

    private void reportStatus(String value) {
        try {
            Bundle extras = new Bundle();
            extras.putString(ConfigContract.KEY_INTERFACE_STATUS, value);
            context.getContentResolver().call(ConfigContract.URI, "report", null, extras);
        } catch (Throwable error) {
            Log.e("Cannot report interface status", error);
        }
    }
}
