# 小米 10S（thyme）· HyperOS 1.0 适配记录

## 1. 目标机型实测环境

以下均来自真机 `adb` 采集（`adb devices -l` / `getprop` / `dumpsys display` / `dumpsys sensorservice`），非推测：

| 项目 | 实测值 |
| --- | --- |
| 设备/型号 | `thyme` / `M2102J2SC`（小米 10S，骁龙 870） |
| 系统 | HyperOS `OS1.0`，`V816.0.13.0.UMNCNXM`，MIUI `V816` |
| Android | 14（SDK 34） |
| 屏幕 | 1080×2340，density 440，默认模式 **90Hz**（另有 60Hz 模式） |
| 挖孔 | 左上角，`DisplayCutout{insets=Rect(0, 90 - 0, 0)}`，bounding 177×90px，圆角 radius 110 |
| 导航 | 手势导航（`navigation_mode=2`，`force_fsg_nav_bar=1`） |
| 气压计 | `bosch_bmp285`，**1Hz ~ 25Hz**，non-wakeup |
| LSPosed | `org.lsposed.manager` 已安装；`com.miui.home` 存在 |
| 自由窗口 | 独立包 `com.miui.freeform`（`MiuiFreeformService.apk`），运行时不常驻 |

## 2. 问题清单（含证据）

证据获取方式：从真机拉取 `SystemUI.apk` / `MiuiHome.apk`，解压 dex 后用字符串核验类名是否存在
（脚本见 `D:\AI\dump\check_classes.py`）。

### P1 · 自由窗口控制器类名不匹配 —— 导致小窗功能整体失效（严重）

- 位置：`FanFreeformHook.java` 原 `FREEFORM_CONTROLLER`
- 原值：`com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController`
- 真机核验：`multitasking` 前缀在 SystemUI dex 中 **0 命中**；真实类为
  `com.android.wm.shell.miuifreeform.MiuiFreeformModeController`（FOUND）
- 影响：小窗启动、任务跟踪、动画回调全部拿不到实例 → 小窗起不来
- 修复：改为候选数组 + 运行时刻探测（`firstExisting`），不按机型/版本分支

### P2 · PinHandler / Animation 同类问题（严重）

- 真机：`com.android.wm.shell.miuifreeform.MiuiFreeformModePinHandler`、
  `com.android.wm.shell.miuifreeform.MiuiFreeformModeAnimation` 均 FOUND
- 影响：边缘 pin 恢复、mini 窗口目标修正失效
- 修复：同 P1，候选探测

### P3 · 输入事件控制器在 HyperOS 1 上不存在（功能受限）

- `MulWinSwitchEventController` 的三个候选（含 `miuimultiwinswitch` 各种前缀）在真机 **全部 MISS**
- 说明：HyperOS 1 的 `com.android.wm.shell.miuimultiwinswitch.*` 包存在，但里面只有
  `MiuiMultiWinSwitchController` / `MiuiMultiwinTouchStatus` 等，没有该事件控制器
- 影响（**已改写**）：ROM 私有事件控制器确实不存在，但这**不等于**没有原生输入通道。
  真机已证实可走框架标准手势监听 `InputManager.monitorGestureInput(name, displayId)`，
  返回值自带 `InputMonitor`，可 `pilferPointers()` —— 与 OS3 事件控制器内部所用机制同源
- 修复（已完成）：`rom/InputSource` 扩展点已由占位实现换成
  `GestureMonitorInputSource`（`Kind.FRAMEWORK_GESTURE_MONITOR`），并已接线到业务层。
  真机 `native_input=ready` + 注入手势实测 `capture=pilfer`，与 OS3 行为一致，
  **已不需要回退到自建边缘捕获窗口**（自建窗口在原生通道就绪后自动停用）
- 证据与 A/B 对照见 `docs/os1-bridge-device-test.md` 第 7 节

### P4 · 系统边缘手势入口（已核验存在，交互实现留到后续阶段）

- 真机存在 `com.android.systemui.fsgesture.GestureStubView`，其
  `onTouchEvent(MotionEvent)` 是 MIUI/HyperOS 全面屏边缘手势的分发入口
  （由 `dexdump` 核验方法签名；实例字段只有 `GestureStubView$1`、`ContentResolver`、
  `Context`，**无 InputMonitor / InputChannel，因此拿不到 `pilferPointers()`**）
- 处理（本阶段）：只把它登记进能力快照（`Os1Capabilities.systemGestureView`，
  真机实测命中），**不做 hook、不实现侧滑/边缘手势交互**，等后续阶段沿
  `rom/InputSource` 扩展点接入
- **实测补充（重要）**：探针曾 hook 其 `onTouchEvent`（`hooks=1`，挂上了），但真机上
  **一次都没触发**（两组 A/B 均 0 条）。即它在 HyperOS 1 上根本不收触摸，是 MIUI 12 遗留
  路径。因此它连"只读旁路"都算不上（读了也是空的），正式实现不得依赖它

### P5 · 控制中心类名不匹配（界面冲突风险）

- 原值：`com.miui.systemui.controlcenter.ControlCenterImpl` —— 真机 MISS
- 真机：`com.android.systemui.controlcenter.ControlCenter`、
  `com.android.systemui.controlcenter.policy.ControlCenterControllerImpl`（FOUND）
- 影响：控制中心展开时无法暂停手势 → 下拉控制中心可能同时呼出手势浮层
- 修复：候选数组，对所有存在者安装展开/收起回调

### P6 · 高刷新率处理用了已弃用 API 且无条件锁帧（功耗/卡顿）

- 位置：`HoneycombOverlayController.java` 原 `windowManager.getDefaultDisplay().getRefreshRate()`
- 问题：`getDefaultDisplay()` 已弃用；且无论屏幕是否为高刷都写 `preferredRefreshRate`，
  会把动态刷新率钉死
- 修复：改用 `Context.getDisplay().getMode()`，仅当 > 60Hz 才锁定

### P7 · 气压计能力与采样（体验/耗电）

- 真机 BMP285：1–25Hz、non-wakeup；`getMaxDelay()` 即最快间隔（约 40ms）
- 修复：采样周期取传感器声明值，对返回 0（能力未知）的 ROM 做回退并用最慢间隔兜底

### 已核验为「无需改动」的项

| 检查项 | 结论 |
| --- | --- |
| ABI / 原生库 | 项目为纯 Java，无 `.so`、无 `abiFilters`，不涉及 arm64 兼容问题 |
| 通知权限 / 通知渠道 | 项目不使用通知，无需 `POST_NOTIFICATIONS` |
| 前台服务 / FGS 类型 | 无 Service 声明，不涉及 |
| 后台启动 Activity | 在 SystemUI 进程以 `FLAG_ACTIVITY_NEW_TASK` 启动，不受后台启动限制 |
| 动态注册广播 flag | 三处 `registerReceiver` 均已带 `RECEIVER_EXPORTED/NOT_EXPORTED`，符合 Android 14 |
| 挖孔屏 / 手势安全区 | 已用 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 与 `systemGestures()` insets，官方 API |
| 桌面侧 hook | `com.miui.home.recents.views.RecentsContainer` 在 MiuiHome 中存在（FOUND） |

## 3. 修改的文件与位置

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `xposed/FanFreeformHook.java` | 新增 `SHELL_MULTITASKING/SHELL_FLAT` 与 4 组候选数组；新增 `firstExisting()` 与 `resolveSystemUiClassNames()` | 把硬编码类名改为运行时刻探测，一处集中封装 |
| 同上 | `handleLoadPackage` 中最先调用 `resolveSystemUiClassNames()` | 保证后续所有 hook 复用解析结果 |
| 同上 | 控制中心改为遍历 `RomTargets.CONTROL_CENTER` | 兼容两套包名（P5） |
| 同上 | 自由窗口控制器构造完成后调用 `RomBridge.get().freeform().registerControllerInstance()` | 实例统一登记进对接层，业务不再自己持有裸对象 |
| 同上 | MiuiHome 分支也调用 `RomBridge.get().attach()` | `RecentsContainer` 只在桌面 ClassLoader 里，不 attach 就是假阴性（真机实测发现） |
| `xposed/FanRuntime.java` | 新增 `usesNativeInput()`、`isCapturingByOverlay()` | 供 hook 层去重，避免双通道重复投递 |
| 同上 | 气压采样周期取值加固 + 实测注释 | 适配 25Hz 上限气压计（P7） |
| `xposed/HoneycombOverlayController.java` | 改用 `Context.getDisplay().getMode()`，仅 >60Hz 锁帧 | 去掉弃用 API、避免无条件锁帧（P6） |

### 兼容性说明（不退化）

- 类名解析按「谁存在用谁」，HyperOS 3 上仍解析到 `multitasking.*`，行为不变
- 系统边缘手势通道是**次选**：已接上 wm.shell 原生通道，或自建捕获窗口正在处理时，
  直接跳过，不会重复投递，也不会改写事件结果（系统返回手势保留）

## 4. OS1 原生接口对接层（新增）

目标：把「ROM 私有类名 / 反射 / Xposed 细节」全部收进一层，业务代码只面对统一接口；
本阶段**只做对接，不做交互**（侧滑返回、边缘手势留到后续）。

### 4.1 文件清单

| 文件 | 职责 |
| --- | --- |
| `rom/RomTargets.java` | **唯一真相源**：类名候选数组（自由窗口控制器 / PinHandler / Animation / shell 事件控制器 / 控制中心 / 通知栏 / 手势观察器 / 最近任务 / 系统手势 View）+ `firstExisting()` 探测 |
| `rom/RomBridge.java` | 门面单例：`attach(ClassLoader)` 探测并锁定能力快照；`freeform()` / `controlCenter()` / `recents()` / `input()` |
| `rom/Os1Capabilities.java` | 不可变能力快照 + `hasXxx()` 谓词 + `describe()`（缺失项打印 `-`） |
| `rom/BridgeResult.java` | 统一返回结构：`Code{OK, UNSUPPORTED, CLASS_MISSING, METHOD_MISSING, INVOCATION_FAILED, NOT_READY}` + `value()` / `detail()` / `cause()` / `orElse()` |
| `rom/BridgeLog.java` | `Sink` 可替换日志出口，默认 `android.util.Log` + `XposedBridge.log`，全 try/catch 不影响宿主 |
| `rom/FreeformAccess.java` | 自由窗口：可用性、真实类名、登记控制器实例、声明 Pin/动画支持 |
| `rom/ControlCenterAccess.java` | 控制中心：只声明可用性与真实类名（OS1 的实例是 Dagger 注入、取不到，故不假装提供 `expand/collapse`） |
| `rom/RecentsAccess.java` | 最近任务：可用性与真实类名 |
| `rom/InputSource.java` | **扩展点**：`Kind{NONE, SYSTEM_GESTURE_VIEW, SHELL_EVENT_CONTROLLER, FRAMEWORK_GESTURE_MONITOR}` + `MotionListener` + `start/stop` + `monitorHandle()`（能否 `pilferPointers()` 的唯一凭据） |
| `rom/GestureMonitorInputSource.java` | **原生输入源实现**：`InputManager.monitorGestureInput()` → `InputMonitor.getInputChannel()` → `InputChannelCompat$InputEventReceiver` → 透传 `MotionEvent`。与 OS3 事件控制器内部机制同源，不依赖任何 ROM 私有类 |
| `rom/RomBridgeTest.java`（test） | 10 个纯 JVM 单测：结果结构、候选探测顺序与空值、OS1 候选命中、快照与 `describe()` |

### 4.2 关键实现点

- **调用方式统一**：所有能力经 `RomBridge.get().xxx()` 取用，返回一律 `BridgeResult`，
  失败不抛异常、不外泄 ROM 细节
- **与业务解耦**：业务（`FanFreeformHook` / `FanRuntime`）只依赖 `rom` 包的接口，
  不再散落 `XposedHelpers.findClass` 与裸类名字符串
- **快照按进程**：模块在每个宿主进程各加载一份，单例互不干扰；
  SystemUI 与桌面各自 `attach` 一次，缺失项记 `null`，由 `isAvailable()` 如实反映
- **不做机型/版本分支**：全部靠「谁存在就用谁」的运行时探测
- **原生输入接线**（`FanRuntime.attachSystemGestureMonitor(Context)`）：
  1. 已被 OS3 事件控制器接管则直接返回，避免同一手势被两条通道重复投递
  2. `beginNativeInputRegistration()` → 向框架注册手势监听 → 成功
     `completeNativeInputRegistration()`（状态机切到 `NATIVE_GLOBAL`，自建兜底窗口停用），
     失败 `failNativeInputRegistration()` 并**如实记录原因**，不做静默降级
  3. 事件直接 `onMotion(motion, monitorHandle())` 交给业务层，业务层沿用既有的
     8 处 `pilfer()` 调用点 —— 与 OS3 完全一致

### 4.3 真机实测能力快照（thyme / HyperOS 1.0）

SystemUI 进程：

```
Os1Capabilities{freeformController=com.android.wm.shell.miuifreeform.MiuiFreeformModeController
  freeformPinHandler=com.android.wm.shell.miuifreeform.MiuiFreeformModePinHandler
  freeformAnimation=com.android.wm.shell.miuifreeform.MiuiFreeformModeAnimation
  controlCenter=com.android.systemui.controlcenter.ControlCenter
  notificationShade=com.android.systemui.shade.NotificationShadeWindowView
  gestureObserver=com.miui.systemui.controller.GestureObserver
  recentsContainer=- shellEventController=-
  systemGestureView=com.android.systemui.fsgesture.GestureStubView}
```

桌面进程：

```
Os1Capabilities{freeformController=- ... recentsContainer=com.miui.home.recents.views.RecentsContainer ...}
```

完整测试报告见 `docs/os1-bridge-device-test.md`。

## 5. 验证方法

### 5.1 本地（已完成）

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

结果：`BUILD SUCCESSFUL`；单测 `RomBridgeTest` 10/10 通过（另有 config 相关单测全绿），
产物 `app/build/outputs/apk/debug/app-debug.apk`。

### 5.2 真机（已完成，2026-09-30）

1. 安装：`adb install -r app-debug.apk`
2. LSPosed 中启用模块，作用域勾选 `com.android.systemui` 与 `com.miui.home`
3. 重启宿主进程加载新代码：

```bash
export MSYS_NO_PATHCONV=1                    # Git Bash 下必须
adb shell am force-stop com.miui.home        # 桌面：有效
adb shell am crash     com.android.systemui  # SystemUI：persistent，killall/force-stop 均无效
```

4. **看日志必须读 LSPosed 自己的文件，logcat 里没有**：

```bash
adb shell "su -c 'grep -n \"RomBridge attached\" /data/adb/lspd/log/verbose_*.log'"
```

预期看到（已实测命中）：

- `RomBridge attached: Os1Capabilities{freeformController=com.android.wm.shell.miuifreeform.MiuiFreeformModeController ...}`（SystemUI 与桌面各一条）
- `Resolved ROM class names freeformController=com.android.wm.shell.miuifreeform.MiuiFreeformModeController ...`
- `Freeform controller hooks installed controller=com.android.wm.shell.miuifreeform.MiuiFreeformModeController`
- `MiuiFreeformModeController connected`
- 桌面的快照里 `recentsContainer=com.miui.home.recents.views.RecentsContainer`

5. 稳定性核对：新进程内检索 `Exception / failed / Error` 应为 0 条（已实测为 0）
6. 功能自测（交互部分属后续阶段）：底角斜滑呼出扇形 → 松手小窗启动；侧滑；蜂窝总览；
   控制中心展开时不应同时呼出浮层
7. 若异常：模块内「一键导出兼容性报告」导出后附到 issue

详见 `docs/os1-bridge-device-test.md`。

### 5.3 已确认的已知限制

- HyperOS 1 上没有 wm.shell 事件控制器，原生输入接管不可用；`pilfer` 需要系统
  InputMonitor，而 `GestureStubView` 拿不到，故本阶段 `InputSource` 为占位实现
- 侧滑返回、边缘手势交互**本阶段未实现**，入口已核验存在（`GestureStubView`），
  沿 `rom/InputSource` 扩展点后续接入
- 气压计 25Hz 上限，按压手势灵敏度受硬件采样率限制
