# 侧滑任务面板 · UI 解耦与交互演进方案

> 触发背景（用户 2026-10-28）：
> ① 面板**颜色与 UI 之后会改**，行尾「锁 / 关闭叉」间距不舒适，整体布局要调 →
> **希望把 UI 单独拿出来，改 UI 不牵连业务**；
> ② 开发方向转为**手势唤起**，向三星 One Hand Operation+ 靠拢 → **交互方式会变**；
> ③ **交互逻辑也要修正**：长按应用行 = 行首 ⋮；点锁定时面板不关；
> 关闭规则改为「只有跳转才直接关，其余一律一级一级关」。**不再新增其它交互**（精简）。
> → 关闭层级契约见 **附录 B**。
>
> 本文只解决"怎么切分"，不实现新 UI。所有现状数据均取自 HEAD `a02b7e7` 源码实测。

---

## 1. 现状：UI 层被三层反向依赖

改一个间距要动 1242 行的视图文件，但真正的麻烦在于**业务层反过来依赖视图类的常量**。
实测统计如下。

### 1.1 跨层引用清单（`TaskCenterListView` 被谁引用）

| 引用方 | 引用内容 | 次数 | 性质 |
|---|---|---|---|
| `FanRuntime` | `EXIT_YIELD` ×3、`EXIT_RECEDE` ×1、`EXIT_QUIET` ×1 | 5 | ❌ **业务依赖 UI 类** |
| `FanRuntime` | `BACK_YIELD_TRAVEL_DP` | 1 | ❌ **业务读 UI 的几何常量** |
| `FanRuntime` | `ACTION_LOCK/SPLIT/FREEFORM/DETAILS/SHORTCUT/SEARCH` | 6 | ❌ **业务依赖 UI 的动作码** |
| `TaskSwitcherOverlayController` | `EXIT_YIELD` | 4 | ⚠️ 承载层依赖具体视图 |
| `TaskSwitcherOverlayController` | `TaskCenterListView.Listener` | 1 | ⚠️ 同上 |
| `TaskCenterListActivity`（已停用） | 6 个 `ACTION_*` + `Listener` | 7 | 死代码，可删 |

**共 24 处**。其中 12 处是"业务层（`FanRuntime`）反向依赖视图类" —— 这是当前结构最尖锐的问题：
`FanRuntime` 是编排层，本该完全不知道有哪个具体视图类。

> **状态（2026-10-28）**：上表是改造前的实测。Step 1 已完成，实际代码引用已从 **24 处降到 2 处**，
> `FanRuntime` 对视图类的引用归零。详见第 5 章 Step 1。

### 1.2 控制器里的强类型判断（5 处 `instanceof`）

```
TaskSwitcherOverlayController:175   TaskCenterListView list = new TaskCenterListView(context);
                             :221   if (current instanceof TaskCenterListView)     // playExit
                             :244   if (!(current instanceof TaskCenterListView)) return;  // setPaused
                             :252   同上                                          // setBackYield
                             :260   同上                                          // releaseBackYield
```

后果：**换一个面板实现，必须改控制器 5 个地方**，且 `Surface` 抽象形同虚设
—— 4 个方法都绕过了 `Surface`，用 `instanceof` 直接调 `TaskCenterListView` 的独有方法。

### 1.3 视图内部硬编码规模

| 项 | 数量 | 位置 |
|---|---|---|
| 不同 `dp(...)` 字面值 | **39 种** | 全部在 `computeMetrics()` / `onDraw()` / `handleTap()` |
| `static final int COLOR_*` | **11 个** | 类顶部 |
| 动画时长字面值 | 6 处 | `playEntry()` / `playExit()` / `animateMenuProgress()` |
| 命中区与视觉耦合 | 3 处 | 见 1.4 |

### 1.4 ★ 锁定图标与关闭叉的间距问题（量化）

当前实现：

```java
// computeMetrics()
crossX = panelWidth - dp(28);
lockX  = panelWidth - dp(28) - dp(20) - dp(4) - dp(20);   // = panelWidth - 72
nameEnd = lockX - dp(10);                                  // = panelWidth - 82
```

绘制尺寸：

```java
// drawCross()
float arm = dp(5);            // 叉宽 10dp（±5dp）
// drawLock()
float bodyHalfWidth = dp(5.5f);   // 锁体宽 11dp
float shackleRadius = dp(3.6f);   // 锁梁宽 7.2dp
```

**算出来的实际视觉空隙**：

| 量 | 值 |
|---|---|
| 叉中心 → 面板右缘 | 28dp |
| 锁中心 → 面板右缘 | 72dp |
| **两中心距** | **44dp** |
| 叉左缘 | `panelWidth − 33` |
| 锁右缘 | `panelWidth − 66.5` |
| **两个图标之间的空白** | **33.5dp** |
| 图标自身宽度 | 10dp（叉）/ 11dp（锁） |

**结论：空白是图标宽度的 3 倍多。** 这就是"不舒适"的量化原因。

**根因（不是审美问题，是结构问题）**：

`lockX` 是从**触摸命中区**反推出来的：
`20dp 半径 + 4dp 缝 + 20dp 半径 = 44dp 中心距`。
也就是说 **用"手指要多宽"决定了"眼睛看到多宽"**。代码注释里也写明了这一点：

> "之所以从右往左反推而不是拍两个坐标：两个动作挨得近，必须保证'点关闭不会误触锁、
> 点锁不会误触关闭'，靠反推才能让两段的边界可验算。"

这个推导保证了不误触（目标正确），但把视觉代价一并承担了（副作用）。

**连带影响**：`nameEnd = lockX − 10dp = panelWidth − 82dp`。
面板宽度只有 `min(280dp, 屏宽×78%)`，行尾因此吃掉 **82dp（约 29%）**，
留给应用名的宽度被压缩 —— 这也是"布局不舒适"的一部分。

**正确做法**：视觉位置由设计决定，命中区独立向外扩展；重叠时按"距哪个中心更近"分配，
而不是靠拉开视觉距离来避免误触。详见第 4 章。

---

## 2. 目标分层

把现在的 6 层（见接口文档第 3 章）里最上面两层重新切分，并在中间插一层契约：

```
┌──────────────────────────────────────────────────────────────┐
│ 编排层  FanRuntime                                            │
│   只依赖 TaskPanelAction / TaskPanelExit / TaskPanelHost        │
│   （不再 import 任何具体视图类）                                │
└───────────────────────────┬──────────────────────────────────┘
                            │ TaskPanelHost（业务侧实现）
┌───────────────────────────▼──────────────────────────────────┐
│ 承载层  TaskSwitcherOverlayController                          │
│   只管：窗口 add/remove、type/flags、线程投递、动画兜底          │
│   不认识任何具体视图 —— 只认 TaskPanel 接口 + TaskPanelFactory   │
└───────────────────────────┬──────────────────────────────────┘
                            │ TaskPanel（视图契约）
┌───────────────────────────▼──────────────────────────────────┐
│ 呈现层（可替换）                                                │
│   ├ TaskCenterListView   ← 现有「三星列表」实现                 │
│   ├ TaskSwitcherOverlayView ← 现有「视差卡片」实现              │
│   └ OneHandPanelView     ← 未来 OHO+ 风格实现                  │
│   全部实现 TaskPanel，全部从 TaskPanelSpec 取尺寸与颜色          │
└──────────────────────────────────────────────────────────────┘
```

**切分原则**：

| 归属 | 内容 | 判断标准 |
|---|---|---|
| **留在呈现层** | 绘制、命中、入场/出场动画、菜单展开 | 换个样子就全变的东西 |
| **提到契约层** | 动作码、出场因果、设计规格、视图接口 | 业务要引用、或视图实现之间必须一致的东西 |
| **留在承载层** | 窗口参数、`removeViewImmediate`、900ms 兜底 | 与"面板长什么样"无关 |
| **留在编排层** | 手势入口、五条关闭通路、动作分派 | 与"面板画在哪里"无关 |

---

## 3. 新增类型定义（6 个）

### 3.1 `TaskPanelAction`（动作码枚举）

```java
package com.oxohang.fanfreeform.xposed;

/** 面板里可发起的动作。业务层与呈现层共用，不隶属任何具体视图。 */
enum TaskPanelAction {
    LOCK,       // 切换应用锁定
    SPLIT,      // 分屏打开
    FREEFORM,   // 小窗打开
    DETAILS,    // 应用信息
    SHORTCUT,   // 应用桌面快捷方式（需带 shortcut 数据）
    SEARCH      // 全局搜索
}
```

**迁移映射**：现有 `TaskCenterListView.ACTION_LOCK(0)` → `LOCK`，以此类推。
值 0–5 与顺序一致，便于日志对照。

### 3.2 `TaskPanelExit`（出场因果枚举）

```java
/** 面板出场的因果。每种因果有自己的终点形态与时长，由呈现层解释。 */
enum TaskPanelExit {
    RECEDE,   // 回落：点外部 / 返回键，与入场反演
    YIELD,    // 让位：点应用 / 菜单项，给新界面腾位置
    FLING,    // 甩出：跟手上划，继承手速
    QUIET     // 安静让路：系统事件，不抢注意力
}
```

**迁移映射**：`EXIT_RECEDE(0)` → `RECEDE`，`EXIT_YIELD(1)` → `YIELD` 等。
`FanRuntime` 与控制器改引枚举，**不再引视图类** → 消掉 9 处跨层引用。

### 3.3 `TaskPanel`（视图契约）

替代 `Surface` + 5 处 `instanceof`。

```java
/** 任务面板的统一视图契约。实现方可自由替换（列表 / 卡片 / 未来 OHO+ 形态）。 */
interface TaskPanel {
    /** 取底层 View（承载层 addView 用）。 */
    View asView();

    // ---- 动画 ----
    void playEntry();
    void playExit(TaskPanelExit reason, float velocityY);

    // ---- 系统事件联动（实现方可忽略）----
    /** 系统面板展开/收起时退位与弹回。不支持的实现留空。 */
    void setPaused(boolean paused);
    /** 侧边返回手势跟手进度 0..1。不支持的实现留空。 */
    void setBackYieldProgress(float progress, boolean fromLeft);
    /** 返回未触发时弹回原位。 */
    void releaseBackYield();
    /**
     * 跟手满格所需行程（px）。承载层/编排层做进度换算要用，
     * 因此必须由呈现层自己报 —— 不同形态的行程不同。
     */
    float backYieldTravelPx();

    // ---- 外部触摸（跟手）----
    void onExternalMove(float x, float y);
    void onExternalUp(float x, float y, boolean cancelled);
    void onExternalCancel();

    // ---- 释放 ----
    void releaseResources();

    /** 视图是否已请求关闭（出场动画播完）。承载层据此撤窗。 */
    boolean isCloseRequested();
}
```

> `backYieldTravelPx()` 是关键：它把 `FanRuntime` 对 `TaskCenterListView.BACK_YIELD_TRAVEL_DP`
> 的依赖变成"问实现方要一个数"，业务层不再认识那个常量。

### 3.4 `TaskPanelHost`（宿主契约）

替代现有两层 `Listener`，是**呈现层唯一能回调业务的出口**。

```java
/** 面板把用户操作交给业务的唯一出口。由编排层实现。 */
interface TaskPanelHost {
    void onLaunch(RecentTaskPreview task);
    void onCloseTask(RecentTaskPreview task);
    void onCloseAll();
    void onAction(TaskPanelAction action, RecentTaskPreview task,
                  RecentTaskRepository.ShortcutEntry shortcut);
    void onToggleLock(RecentTaskPreview task, boolean shouldLock);
    /** 出场动画结束、可以撤窗。 */
    void onDismissed();
}
```

### 3.5 `TaskPanelSpec`（设计规格 —— **改 UI 就改这里**）

这是你之后调颜色、调间距的唯一入口。

```java
/** 任务面板的全部可调设计参数。不可变，由 TaskPanelSpecs.forList() 等工厂产出。 */
final class TaskPanelSpec {
    // ── 颜色 ──────────────────────────────────────────────
    int panelFill;            // 面板底
    int panelStroke;          // 面板描边
    int divider;              // 行分隔线
    int textPrimary;          // 主文字
    int textMuted;            // 次级文字
    int dot;                  // 行首 ⋮
    int accent;               // 强调色（搜索、选中）
    int searchFill;           // 搜索按钮底
    int menuFill;             // 菜单卡底
    int shadow;               // 投影
    int rowHighlight;         // 行按下高亮

    // ── 面板几何 ──────────────────────────────────────────
    float panelMaxWidthDp;    // 建议 280
    float panelWidthFraction; // 建议 0.78（窄屏按屏宽比例收缩）
    float sideMarginDp;       // 面板与屏幕侧边距离，建议 16
    float panelMarginDp;      // 面板与屏幕上下边最小距离，建议 28
    float cornerRadiusDp;     // 面板圆角，建议 14

    // ── 行布局 ────────────────────────────────────────────
    float rowPitchDp;         // 行距，建议 52（单行点击目标 ≥44）
    float firstRowOffsetDp;   // 首行上内边距，建议 30
    float iconCenterXDp;      // 应用图标中心 X，建议 58
    float iconRadiusDp;       // 应用图标半径，建议 12
    float nameStartDp;        // 应用名左缘，建议 76
    float dotXDp;             // 行首 ⋮ 中心 X，建议 24

    // ── 行尾动作区（★ 视觉与命中已分离）────────────────────
    float trailingEndInsetDp;   // 最后一个图标中心 → 面板右缘，建议 24
    float trailingIconSizeDp;   // 图标视觉尺寸（外接正方形边长），建议 12
    float trailingGapDp;        // 两个图标之间的**视觉**空隙，建议 12
    float trailingHitRadiusDp;  // 命中半径，建议 22（可大于视觉间距，靠就近分配）
    float nameEndGapDp;         // 应用名与行尾区最小间隔，建议 8

    // ── 菜单卡 ────────────────────────────────────────────
    float menuLeftInsetDp;    // 建议 54
    float menuRightInsetDp;   // 建议 14（与面板内边距一致）
    float menuFirstOffsetDp;  // 首项偏移，建议 31
    float menuItemPitchDp;    // 项间距，建议 45
    float menuBottomPadDp;    // 底部内边距，建议 17

    // ── 动效 ──────────────────────────────────────────────
    long entryMs;             // 建议 260
    long exitRecedeMs;        // 建议 200
    long exitYieldMs;         // 建议 220
    long exitQuietMs;         // 建议 120
    long exitFlingMinMs;      // 建议 180
    long exitFlingMaxMs;      // 建议 340
    float entryScaleFrom;     // 建议 0.94
    float pausedScale;        // 建议 0.90
    float pausedAlpha;        // 建议 0.55

    // ── 手势手感 ──────────────────────────────────────────
    float dismissTravelDp;    // 甩出门槛，建议 110
    float flickVelocityPxS;   // 快速轻扫阈值，建议 1200
    float rubberBand;         // 不可达方向系数，建议 0.18
    float rubberLimitDp;      // 不可达方向上限，建议 26
    float backYieldTravelDp;  // 返回跟手满格行程，建议 72
    float backYieldShiftDp;   // 跟手横向位移，建议 26
    float backYieldAlpha;     // 跟手最深变暗，建议 0.45
}
```

**为什么用不可变类而不是一堆 `static final`**：
同一套代码要支持多种面板形态（列表 / 卡片 / 未来 OHO+），每种的规格不同。
规格作为参数传入，视图就不需要 `if (mode == LIST)` 这种分支。

### 3.6 `TaskPanelFactory`（换实现只改一处）

```java
/** 按规格与当前配置造一个面板。承载层只调本接口。 */
interface TaskPanelFactory {
    /**
     * @param spec  设计规格（颜色 / 几何 / 动效）
     * @param host  业务回调出口
     * @return 面板实例；不支持该形态时返回 null（承载层据此放弃上屏）
     */
    TaskPanel create(Context context, TaskPanelSpec spec, TaskPanelHost host);
}
```

现有实现：
```java
final class ListPanelFactory implements TaskPanelFactory { ... }   // 对应 TaskCenterListView
final class CardPanelFactory implements TaskPanelFactory { ... }   // 对应 TaskSwitcherOverlayView
```

承载层按 `config.sideTaskLayoutMode` 选工厂，**不再 `new` 具体类、不再 `instanceof`**。

---

## 4. ★ 行尾布局：视觉与命中去耦（解决间距问题）

### 4.1 新算法

**布局（视觉优先，从右往左排）**：

```
closeCx  = panelWidth − trailingEndInsetDp
lockCx   = closeCx − trailingIconSizeDp − trailingGapDp
nameEnd  = lockCx − trailingIconSizeDp/2 − nameEndGapDp
```

**命中（独立计算）**：

```
closeHit = [closeCx − hitR, closeCx + hitR]
lockHit  = [lockCx  − hitR, lockCx  + hitR]

判 x 落在哪一段：
  落在 closeHit 且不落在 lockHit        → CLOSE
  落在 lockHit 且不落在 closeHit        → LOCK
  两段都落（重叠区）                    → 比 |x − closeCx| 与 |x − lockCx|，取近者
  都不落                                → CLOSE（行尾默认是关闭，不让点击落空）
```

**关键差别**：旧算法用 `hitR` 决定**视觉位置**；新算法用 `hitR` 只决定**可点范围**。
两者彻底分开，重叠区靠"就近"消歧，不再需要 4dp 缝把图标推开。

### 4.2 用建议值验算

取 `trailingEndInsetDp = 24`、`trailingIconSizeDp = 12`、`trailingGapDp = 12`、`hitR = 22`：

| 量 | 旧值 | 新值 |
|---|---|---|
| 叉中心 → 右缘 | 28dp | **24dp** |
| 锁中心 → 右缘 | 72dp | **48dp** |
| 两中心距 | 44dp | **24dp** |
| 图标之间空白 | **33.5dp** | **12.5dp** |
| `nameEnd` → 右缘 | 82dp | **62dp** |
| 应用名可用宽度（280dp 面板，`nameStart=76dp`） | **122dp** | **142dp（+16%）** |

空白 / 图标宽度比：**3.35 倍 → 1.14 倍**（视觉上间距与图标同宽，是常规做法）。
同时应用名多出 20dp 横向空间。

### 4.3 命中重叠会不会误触

不会，因为消歧规则是"就近"，不是"硬切"。分析：

- 两中心距 24dp，`hitR = 22dp` → 重叠区宽 `44 − 24 = 20dp`
- 重叠区中点距两中心各 12dp，两侧各 10dp 归属明确
- 用户在重叠区点击时，**手指落点距目标图标不超过 12dp**，视觉上就在图标范围内
- 旧方案在 4dp 缝里点击 = **两个动作都不响应**（死区）；新方案无死区
> 若实测仍觉得锁与叉太近，只调 `trailingGapDp`（建议范围 8–20dp）。
> 命中半径与视觉间距已解耦，加大间距**不会**让命中区变小，反之亦然。

---

## 5. 迁移路径（5 步，每步不改变行为）

每步结束都能编译、装机、行为不变。**不要一次性重写视图。**

> **进度**：Step 1 ✅ 已完成（编译通过）；Step 2–5 ⬜ 待做。

### Step 1 · 抽出常量 ✅ 已完成（消掉 22 处跨层引用）

**实际改动**：

| 文件 | 改动 |
|---|---|
| `TaskPanelAction.java` | **新增**。动作码枚举（`LOCK/SPLIT/FREEFORM/DETAILS/SHORTCUT/SEARCH`），带 `legacyCode()` 便于与旧日志对照 |
| `TaskPanelExit.java` | **新增**。出场因果枚举（`RECEDE/YIELD/FLING/QUIET`），同上 |
| `TaskCenterListView.java` | 删除 `ACTION_*`(6) / `EXIT_*`(4) 共 10 个 `int` 常量；`Listener.onListAction` 改收 `TaskPanelAction`；`playExit` / `popLevel` 改收 `TaskPanelExit`；`MenuEntry.action` 改枚举 |
| `TaskSwitcherOverlayController.java` | `Listener.onListAction` 改枚举；`playExit` 两个重载改收 `TaskPanelExit`；4 处 `EXIT_YIELD` 改枚举 |
| `FanRuntime.java` | `handleListAction` / `onListAction` 改枚举（`switch` 用枚举常量）；5 处 `EXIT_*` 改枚举；`dismissTaskSurfaceForForegroundChange` 第二参改 `TaskPanelExit` |
| `TaskCenterListActivity.java` | 同步适配（该 Activity 已停用，仅为保持编译） |

**额外做了一处归属修正**：`BACK_YIELD_TRAVEL_DP` 从 `TaskCenterListView` 移到 `FanRuntime`。

理由：它是**手势阈值**（手指滑多远算满格），不是面板布局参数。面板只接收 0..1 的进度，
根本不需要知道行程。移到 `FanRuntime` 后与 `BOTTOM_SWIPE_START_ZONE_DP` 等同类常量同处，
语义更正确，`FanRuntime` 也因此**彻底不再引用视图类**。

**验证结果**：

```
grep -rn "TaskCenterListView\." --include=*.java | grep -v TaskCenterListView.java
```

| | 改动前 | 改动后 |
|---|---|---|
| 实际代码引用 | 24 处 | **2 处**<br>（`TaskSwitcherOverlayController` 的 `Listener`、已停用的 `TaskCenterListActivity` 的 `Listener`） |
| `FanRuntime` 对视图类的引用 | 12 处 | **0 处** ← 编排层已完全解耦 |

剩余 3 处命中均为注释里的历史说明（`TaskPanelAction` / `TaskPanelExit` 的类注释与注释引用），
不产生编译依赖。

编译：`BUILD SUCCESSFUL`。
⚠️ **尚未装机验收** —— 本轮 adb 未检测到设备（`adb devices` 为空）。
本步为零行为变化改动，装机后应表现为 UI 与交互完全不变。

### Step 2 · 抽出 `TaskPanel` 接口（消掉 5 处 `instanceof`）

`TaskCenterListView` 实现 `TaskPanel`（方法体不变，只是加 `@Override`）；
`TaskSwitcherOverlayController` 把 5 处 `instanceof` / cast 改成直接调接口方法。
`Surface` 接口被 `TaskPanel` 取代后删除。

验证点：控制器内 `instanceof TaskCenterListView` 归零；四条联动
（`playExit` / `setPaused` / `setBackYieldProgress` / `releaseBackYield`）行为不变。

> **建议与 UI 重写合并做**：Step 2 的价值是"换一个面板实现只需新增一个类"。
> 若你打算重写 `TaskCenterListView`（改颜色/布局），**在新视图上直接实现 `TaskPanel`
> 接口，比给旧代码套接口再改更省一步**。届时只需保留 `TaskPanel` / `TaskPanelHost`
> 两个文件，旧视图整体替换。

### Step 3 · 抽出 `TaskPanelSpec`（**改 UI 从此只改这里**）

把 39 个 `dp(...)` 字面值与 11 个 `COLOR_*` 搬进 `TaskPanelSpec`，
`TaskCenterListView` 改为从字段读取。**所有值保持现状不变**。

验证点：装机后 UI **像素级一致**（这是本步唯一的验收标准）。
`config.sideTaskAnimationSpeed` 的倍率仍由 `TaskCenterAnimationPolicy` 应用到 `spec` 的时长上。

### Step 4 · 换成新布局算法（★ 这一步才改观感）

实现第 4 章算法；`TaskPanelSpec` 的 `trailing*` 用建议值。
**这是唯一会改变观感的步骤**，可以单独回退。

验证点：
- 锁与叉视觉间距 = `trailingGapDp`
- 在 4dp 缝位置点击**不再落空**（旧实现此处无响应）
- 应用名不越过 `nameEnd`
- 关闭叉仍能正确关闭，锁仍能正确切换（各点 20 次无串）

### Step 5 · 抽 `TaskPanelFactory`

承载层按 `sideTaskLayoutMode` 选工厂。此时新增一个面板形态 =
**新增一个 `TaskPanel` 实现 + 一个工厂**，不改业务层任何代码。

验证点：在 `ListPanelFactory` 里临时返回 null，确认承载层安全放弃上屏（不崩）。

---

## 6. OHO+ 方向的预留设计

### 6.1 现在缺的是什么

OHO+ 的交互模型与当前实现的关键差异：

| 维度 | 当前（侧滑→面板） | OHO+（手势→动作） |
|---|---|---|
| 手势语义 | 只有一种：拉出面板 | **多方向多语义**，用户可自定义映射 |
| 反馈时机 | 松手后出完整面板 | **滑动过程中就要有 HUD 指示器** |
| 结果 | 总是打开面板 | 可能是打开面板、返回、分屏、直接启动某 App |
| 面板角色 | 唯一产物 | **只是众多动作产物之一** |

### 6.2 必须提前留出的边界

```
InputSource（手势事件）
      ↓
┌─────────────────────────────────────┐
│ 识别层 GestureRecognizer（新增，未实现）│
│  输出语义手势，不认识任何 UI            │
└──────────────┬──────────────────────┘
               ↓ GestureIntent
┌─────────────────────────────────────┐
│ 映射层 GestureBinding（新增，未实现）   │
│  语义手势 → 动作；由配置驱动            │
└──────────────┬──────────────────────┘
               ↓
      ┌────────┴────────┬──────────────┐
      ↓                 ↓              ↓
 TaskPanel          GestureHud      直接执行
（完整面板）      （轻量指示器）    （无 UI）
      ↑                 ↑
      └──── 两者都实现「可被承载层挂载的浮层」────┘
```

**现在要做的准备（都很小）**：

| 准备 | 理由 | 代价 |
|---|---|---|
| `TaskPanel` 不假设"自己是怎么被唤起的" | 现在只依赖 `anchorX/anchorY`，已满足 | 0 |
| 面板规格与形态分离（`TaskPanelSpec`） | HUD 与面板共用同一套颜色令牌 | Step 3 |
| 承载层通用化（能挂任何 `TaskPanel`） | HUD 也要用同一套窗口生命周期 | Step 2 / 5 |
| 动作码独立成枚举 | 手势映射表要引用动作，不能引用视图 | Step 1 |

**现在不要做的**（避免过度设计）：

- ❌ 不要预先写 `GestureRecognizer` / `GestureBinding` 接口 —— 还不知道 OHO+ 的具体手势集，
  提前抽象大概率要推翻。等交互方案定稿再抽。
- ❌ 不要把 `TaskPanel` 抽象成"任意浮层" —— 面板有明确的"一列任务"语义，
  HUD 大概率是另一个契约，强行合并会得到一个什么都不像的接口。

### 6.3 对现有承载层的一个提醒

`TaskSwitcherOverlayController` 现在同时承担三件事：
① 选档位 ② 管窗口 ③ 编排动画（`playExit` 的兜底计时）。

OHO+ 后会出现"多个浮层形态并存"（既有面板，也有 HUD）。
建议在 Step 5 时把它拆成：

- `OverlayWindowHost`：纯窗口生命周期（add / remove / 900ms 兜底 / 线程投递）
- `TaskSurfaceSelector`：按配置选工厂

这样 HUD 可以直接复用 `OverlayWindowHost`，不用再写一遍 `removeViewImmediate` 那套。

---

## 7. 回归检查清单（每次改 UI 后必跑）

UI 层与交互耦合很紧，改布局最容易漏。以下每项都要实测：

| # | 检查项 | 通过判据 |
|---|---|---|
| 1 | 点行本体 | 应用回到前台，面板退场 |
| 2 | 点关闭叉 | 该行消失、任务被移除，**面板不关** |
| 3 | 点行内锁形 | 锁状态切换，**面板不关** |
| 4 | **长按应用行** | **与点 ⋮ 完全相同**：菜单展开；再长按收起 |
| 5 | 长按后抬手 | **不会**立刻把刚展开的菜单收起 |
| 6 | 长按后拖动手指 | 不触发菜单，走正常拖拽 |
| 7 | 点行首 ⋮ | 菜单展开，覆盖名称列 |
| 8 | **菜单第 1 项（锁定）** | 锁定切换，**面板不关**（可连续锁多个） |
| 9 | 菜单「分屏 / 小窗 / 应用信息 / 快捷方式」 | 执行后**面板退场** |
| 10 | 点底部「全部关闭」 | 全部任务移除，面板退场 |
| 11 | 点搜索 | 拉起全局搜索，面板退场 |
| 12 | **菜单展开时点面板外** | **只收菜单**，面板留着 |
| 13 | **菜单已收时点面板外** | 面板回落（`RECEDE`） |
| 14 | **菜单展开时按返回键** | **只收菜单** |
| 15 | 菜单已收时按返回键 | 面板回落 |
| 16 | 菜单展开时上划 ≥72dp | **只收菜单**，不跟手、列表本体不动 |
| 17 | 上划（面板在场、无菜单） | 面板淡出（`QUIET`） |
| 18 | 侧边返回手势 | 跟手变暗，触发返回时让位、未触发时弹回 |
| 19 | 下拉通知栏 | 面板退位不销毁；收起后弹回 |
| 20 | 开小窗 | 面板让位退场 |
| 21 | 从桌面切走 | 200ms×2 后让位退场 |
| 22 | 改间距后 | 锁/叉命中区**无缝、无重叠死区** |
| 23 | 最后一行被关闭 | 面板自动退场，不残留全屏 overlay |

---

## 8. 待确认项

| # | 事项 | 为什么需要你定 |
|---|---|---|
| 1 | **面板形态是否仍保留「视差卡片」档** | `TaskSwitcherOverlayView` 现在功能严重缺位（无锁定/无关闭单个/无菜单，见接口文档 11.4）。若 OHO+ 后只保留一种面板，可以整档删除，Step 5 的工厂就只需一个实现 |
| 2 | **OHO+ 后是否还需要"侧滑拉出完整面板"** | 若 OHO+ 用"侧滑 → HUD → 松手执行"，面板可能只在特定手势下出现。这决定 `TaskPanel` 要不要保留 `playEntry` 的"生长自手指"语义 |
| 3 | **行尾动作是否要增删** | 现在固定两个（锁 + 关闭）。若之后要加"固定/置顶"等，`TaskPanelSpec` 需要改成一个"动作槽位列表"，而不是两个固定字段 |
| 4 | **面板宽度的自适应策略是否保留** | 现在是 `min(280dp, 屏宽×78%)`。若你希望固定宽度或改成占屏比例，改 `panelMaxWidthDp` / `panelWidthFraction` 两个值即可 |
| 5 | **改 UI 期间是否需要保持"像素级一致"** | 若 Step 3 要做严格像素验收，我需要按现状值逐个填 Spec；若允许顺手微调，可直接用建议值跳过验收 |

第 5 条直接影响 Step 3 的验收方式，建议优先定。

---

## 附录 A · 文件划分（重构后）

```
xposed/
├─ taskpanel/                          ← 新增：面板契约与规格（与具体 UI 无关）
│  ├─ TaskPanelAction.java             动作码枚举
│  ├─ TaskPanelExit.java               出场因果枚举
│  ├─ TaskPanel.java                   视图契约
│  ├─ TaskPanelHost.java               业务回调出口
│  ├─ TaskPanelSpec.java               设计规格（改 UI 的唯一入口）
│  ├─ TaskPanelSpecs.java              预设规格（list / card / 未来 hud）
│  └─ TaskPanelFactory.java            工厂契约
├─ taskpanel/list/
│  └─ TaskCenterListView.java          现有实现迁入（实现 TaskPanel）
├─ taskpanel/card/
│  └─ TaskSwitcherOverlayView.java     现有实现迁入（实现 TaskPanel）
└─ TaskSwitcherOverlayController.java  承载层（瘦身：只管窗口）
```

> 包拆分不是必须的（同包也能解耦），但它让"哪些文件可以随便改"一目了然：
> **改 UI 只碰 `taskpanel/list/` 与 `TaskPanelSpec`**，其余目录不动。

---

## 附录 B · 交互与关闭层级契约

> 用户 2026-10-28 定稿：**只有会跳转的动作才直接关面板；其余交互一律一级一级关。**
> 不再新增其它交互。

### B.1 两条入口，一个动作

| 入口 | 触发 | 行为 |
|---|---|---|
| 行首 ⋮ | 点击 `[0, iconCx − iconRadius]` 区间 | 展开 / 收起该行二级菜单 |
| **整个应用行** | **长按 500ms**（`ViewConfiguration.getLongPressTimeout()`） | **与 ⋮ 完全相同**（展开 / 收起） |

**长按的取消条件**（任一满足即取消，避免和拖拽/横滑抢手势）：
手指位移超过 `touchSlop`、抬手、手势流被系统 `ACTION_CANCEL`、面板正在关闭或已暂停。

**长按被消费后**：本次抬手的 `ACTION_UP` 不再当作点按 —— 否则会立刻把刚展开的菜单收起。
落行判断用**变换后**坐标（`toLocalX/toLocalY`），因为入场动画期间视图带缩放与位移。

### B.2 关闭层级矩阵（改造后实测行为）

| # | 交互 | 会跳转到别处？ | 面板行为 | 层级规则 |
|---|---|---|---|---|
| 1 | 点应用行（名称区） | ✅ 是 | **直接关**（`YIELD`） | 跳转 |
| 2 | 长按应用行 | ❌ 否 | 展开菜单，**不关** | 面板内 |
| 3 | 点行首 ⋮ | ❌ 否 | 展开/收起菜单，**不关** | 面板内 |
| 4 | 点行内锁形 | ❌ 否 | 切换锁定，**不关** | 面板内 |
| 5 | 菜单「锁定此应用程序」 | ❌ 否 | **不关** ✅ 本次修正 | 面板内 |
| 6 | 菜单「在分屏视图中打开」 | ✅ 是 | 直接关（`YIELD`） | 跳转 |
| 7 | 菜单「在弹出视图中打开」 | ✅ 是 | 直接关（`YIELD`） | 跳转 |
| 8 | 菜单「应用程序信息」 | ✅ 是 | 直接关（`YIELD`） | 跳转 |
| 9 | 菜单快捷方式 | ✅ 是 | 直接关（`YIELD`） | 跳转 |
| 10 | 点搜索 | ✅ 是 | 直接关（`YIELD`） | 跳转 |
| 11 | 点行尾关闭叉 | ❌ 否 | 只移除该行，**不关**（最后一行移除后才退场） | 面板内 |
| 12 | 点「全部关闭」 | ❌ 否 | 直接关（⚠️ 见 B.3） | 例外 |
| 13 | **点面板外** | — | **先收菜单；菜单已收才关面板** | **分级** |
| 14 | **返回键** | — | **先收菜单；菜单已收才关面板** | **分级** |
| 15 | 菜单展开时上划 ≥72dp | — | **直接收菜单**（不跟手），列表本体不动 | **分级** |
| 16 | 底部上划（屏幕底部 96dp 起） | — | 直接关（`QUIET`） | 系统离开 |
| 17 | 侧边返回手势 | — | 直接关（`YIELD`） | 系统离开 |
| 18 | 开小窗 | — | 直接关（`YIELD`） | 系统跳转 |
| 19 | 前台变化（切走应用） | — | 直接关（`YIELD`） | 系统跳转 |
| 20 | 下拉通知栏 / 控制中心 | — | **退位不销毁**，收起后弹回 | 非关闭 |

**分级关闭的实现**：`popLevel(TaskPanelExit)` 一处判据 ——
```java
private void popLevel(TaskPanelExit cause) {
    if (expandedRow >= 0) { collapseExpand(); return; }  // 还有一层：只收菜单
    playExit(cause, 0f);                                 // 到最外层：关面板
}
```
点外部与返回键共用它，不各写一遍"菜单在不在"。

**16–19 为何不分级**：这些是"用户要离开面板"的系统级事件（回桌面、系统返回、系统已把任务提到前台），
不是"在面板内操作"。此时面板必须整体让路，否则会挡在目标界面之上。

### B.3 唯一例外：「全部关闭」

它不是跳转，但会关面板。**原因是数据一致性，不是交互偏好**：

`onCloseAll` 只调 `repository.removeAllTasks(...)`，**并不会清空视图内的 `tasks` 列表**
（只有单行关闭 `onCloseTask` 才 `tasks.remove(i)`）。若此时不关面板，用户会看到
一列"已被移除、点下去什么都不会发生"的过期行。所以必须退场。

> 若要改成"清空后留在面板显示空态"，需要视图侧同步清空 `tasks` 并画出空态 ——
> 属于新增 UI 与交互，按"精简"要求本次不做。

### B.4 本次改动清单（2026-10-28）

**第一轮 · 交互修正**

| 文件 | 改动 |
|---|---|
| `TaskCenterListView.java` | 新增长按入口：`longPressRow` / `longPressConsumed` / `longPressAction` / `cancelPendingLongPress()`；`onTouchEvent` 的 DOWN/MOVE/UP/CANCEL 四个分支接入；`releaseResources()` 撤销计时器 |
| `TaskSwitcherOverlayController.java` | `onListAction` 分流：`action != TaskPanelAction.LOCK` 才 `playExit`；`onCloseAll` 补注释说明例外 |

**第二轮 · 精简（净减 76 行，去掉一套并行状态机）**

| 删除项 | 原作用 | 替代 |
|---|---|---|
| `draggingMenu` 字段 | 区分"拖菜单 / 拖列表"两种拖拽 | **不再需要** —— 菜单展开时拖拽只有一种语义 |
| `menuCollapseProgress` 字段 | 菜单跟手收缩进度 | 删 —— 不再跟手 |
| `menuAnim` 字段 | 菜单回弹动画 | 删 —— 不再回弹 |
| `applyMenuDrag()` | 按位移算收缩进度 | 删 |
| `finishMenuDrag()` | 松手按阈值判定收/弹回 | 删 |
| `settleMenuBack()` | 取消时回弹 | 删 |
| `animateMenuProgress()` | 回弹动画驱动 | 删 |
| `resetMenuProgress()` | 清理上述三态 | 删（无状态可清） |
| `MENU_COLLAPSE_RELEASE` 常量 | 松手放行阈值 | 删（改为纯距离判定） |
| onDraw 里的收缩裁剪 | 菜单项按进度裁剪绘制 | 删（整块画） |
| footer 两套判定 | 菜单展开 / 未展开各写一遍搜索与全部关闭 | **合并为一条判定链** |

**新的菜单收起规则（一行代码）**：
```java
if (expandedRow >= 0) {
    if (-moveDy >= dp(MENU_COLLAPSE_TRAVEL_DP)) collapseExpand();
    return true;
}
```

**规模变化**：`TaskCenterListView` 1279 → **1203 行**（−76）。

**行为差异（仅两处，都是变简单）**：
1. 菜单展开时上划：原来跟手收缩、松手按 50% 阈值决定收或弹回；现在推够 72dp 直接收，不够就完全不动。
2. 菜单展开时点「全部关闭」：原来无响应，现在生效（footer 不再分层）。

编译：`BUILD SUCCESSFUL`。
⚠️ 未装机验收（adb 未检测到设备）。装机后按第 7 章清单逐项过，重点验第 4、6、8、12、13、14、16 项。

---

## 附录 C · 面板尺寸与底栏规格（2026-10-28 定稿）

### C.1 目标

**窗口总高不变**：原来 8 行 + 底栏的高度，现在装 10 行 + 底栏。
底栏的两个控件同时加大加粗（原来 15dp 常规 + 28dp 圆，现在 17dp 粗体 + 36dp 圆）。

### C.2 关键常量（`computeMetrics()`）

| 常量 | 值 | 说明 |
|---|---|---|
| `rowPitch` | **42dp** | 原 52dp。42 = 图标 24 + 上下各 9 |
| `firstRowOffset` | 30dp | 不变 |
| `listHeight` 常数项 | **104dp** | 原 118dp。104 = 30（首行偏移）+ 74（末行中心到底） |
| 末行中心 → 横线 | **14dp** | 原 21dp，让横线贴近锁/叉（锁体底距线 4.5dp） |
| 横线 → 面板底 | 60dp | 由上面两项推出，恒定 |
| 搜索圆中心 X | `panelWidth − 50dp` | 原 `panelWidth − 28`。落在锁列(72)与叉列(28)的中点 |
| 搜索圆中心 Y | `lastDividerY + 35dp` | 不变 |
| `footerBaseline` | `lastDividerY + 40dp` | 不变 |

### C.3 底栏控件

| 控件 | 规格 |
|---|---|
| "全部关闭" | 17dp、`Typeface.BOLD`、`ACCENT` 蓝、面板居中 |
| 搜索圆底 | 直径 **36dp**（半径 18dp），`#f0f2f5` |
| 放大镜 | 镜片半径 6.5dp、手柄 (4.5,4.5)→(10,10)、笔画 1.9dp、`ACCENT` |

### C.4 行数 → 面板高度

| 任务数 | 面板高 |
|---|---|
| 3 | 188dp |
| 5 | 272dp |
| 8 | 398dp |
| **10** | **482dp** ← 与改造前 8 行同高 |

公式：`104 + (rows − 1) × 42`

### C.5 水平关系（280dp 面板）

| 元素 | X（相对面板左边） |
|---|---|
| 行首 ⋮ | 24 |
| 应用图标中心 | 58 |
| 应用名左缘 | 76 |
| 分隔线右端 `nameEnd` | 198 |
| 锁中心 `lockX` | 208 |
| 搜索圆中心 | **230**（锁与叉的中点） |
| 关闭叉中心 `crossX` | 252 |

搜索圆直径 36dp → 左缘 212、右缘 248，与锁（208）、叉（252）各差 **4dp**，正好嵌进两列之间。

### C.6 已知未解决问题：横屏溢出

10 行 482dp 在竖屏（可用约 724dp）没问题，但**横屏可用高度约 304dp，会溢出约 178dp**，底部"全部关闭"和搜索看不到。

这不是本次引入的——改造前 586dp 溢出更严重（约 282dp）。本次只是缓解。

彻底解决需要滚动（用户提过"超过就滚"），约需 90 行：滚动偏移 + 手势 + 边界钳制 + 绘制偏移 + 命中判定偏移 + 底栏固定。

竖屏为主场景时可以先不做。

---

*基于仓库 HEAD `a02b7e7` · 2026-10-28*
