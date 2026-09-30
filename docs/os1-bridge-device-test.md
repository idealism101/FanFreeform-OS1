# OS1 原生接口对接层 —— 真机测试报告

- 日期：2026-09-30
- 设备：Xiaomi Mi 10S（thyme / M2102J2SC），序列号 `4e056a62`
- 系统：HyperOS 1.0 `V816.0.13.0.UMNCNXM`，Android 14 / SDK 34
- 被测包：`com.oxohang.fanfreeform` 1.5-debug（versionCode 150）
- 被测 APK：`app/build/outputs/apk/debug/app-debug.apk`，构建于 2026-09-30 17:53（含对接层改动）
- 结论：**对接层在真机上加载并正常工作，SystemUI 宿主进程零报错**；发现 1 个能力探测缺口（见第 5 节）

---

## 1. 测试步骤（可复现）

```bash
export MSYS_NO_PATHCONV=1                      # Git Bash 下必须，否则 adb 参数被路径转换
ADB=/d/AI/sdk/platform-tools/adb.exe

$ADB install -r "D:\\0726\\FanFreeform-OS1\\app\\build\\outputs\\apk\\debug\\app-debug.apk"

# 宿主进程必须重启才会加载新代码
$ADB shell am force-stop com.miui.home         # MiuiHome 有效
$ADB shell am crash     com.android.systemui   # SystemUI 是 persistent，killall / force-stop 均无效
```

| 手段 | SystemUI | MiuiHome | 说明 |
|---|---|---|---|
| `killall <pkg>` | ✗ Operation not permitted | ✗ | adb shell 是 `uid=2000(shell)`，无权限 |
| `am restart <pkg>` | ✗ Exception | — | 该版本 `am restart` 执行异常 |
| `am force-stop <pkg>` | ✗ PID 不变 | ✓ PID 3449 → 11719 | SystemUI persistent，不被 force-stop |
| **`am crash <pkg>`** | **✓ PID 3960 → 9270** | — | 触发 `CrashedByAdbException`，进程自杀后自动重启 |

## 2. 关键坑：logcat 抓不到模块日志

LSPosed 会把 `XposedBridge.log` **重定向到自己的日志文件**，不写 logcat。
本模块 `BridgeLog` 同时写 `android.util.Log` 与 `XposedBridge.log`，但实测 logcat 中同样检索不到。

**必须读这个文件**（需要 root，本机 KernelSU 可用 `adb shell su -c`）：

```
/data/adb/lspd/log/verbose_<时间戳>.log
```

```bash
$ADB shell "su -c 'grep -n \"RomBridge\" /data/adb/lspd/log/verbose_*.log'"
```

> 附带观察：`logcat` 里有一条
> `E/zn-daemon64: unsupported api version 4 of module /data/adb/modules/zygisk_lsposed/zygisk/arm64-v8a.so, at most 3`
> —— 这是 Zygisk Next 1.4.3(817) 拒绝把 LSPosed v2.2.0(7854) 注入 **`artd`** 进程，
> **不影响 zygote / SystemUI 的注入**，本次测试中钩子全部正常加载，可忽略。

## 3. 对接层实测结果（全部通过）

### 3.1 能力快照（`RomBridge.attach`）

```
FanFreeformROM: RomBridge attached: Os1Capabilities{
  freeformController = com.android.wm.shell.miuifreeform.MiuiFreeformModeController
  freeformPinHandler = com.android.wm.shell.miuifreeform.MiuiFreeformModePinHandler
  freeformAnimation  = com.android.wm.shell.miuifreeform.MiuiFreeformModeAnimation
  controlCenter      = com.android.systemui.controlcenter.ControlCenter
  notificationShade  = com.android.systemui.shade.NotificationShadeWindowView
  gestureObserver    = com.miui.systemui.controller.GestureObserver
  recentsContainer   = -
  shellEventController = -
  systemGestureView  = com.android.systemui.fsgesture.GestureStubView
}
```

与真机 dex 核验预期**逐项一致**：

| 能力 | 预期 | 实测 | 判定 |
|---|---|---|---|
| freeformController | `miuifreeform.*`（无 `multitasking` 前缀） | 一致 | ✓ 修复生效 |
| freeformPinHandler | 同上 | 一致 | ✓ |
| freeformAnimation | 同上 | 一致 | ✓ |
| controlCenter | `com.android.systemui.controlcenter.*` | 一致 | ✓ OS1 包名正确 |
| notificationShade | `NotificationShadeWindowView` | 一致 | ✓ |
| gestureObserver | `com.miui.systemui.controller.GestureObserver` | 一致 | ✓ |
| shellEventController | 不存在 | `-` | ✓ 符合预期（OS1 无原生输入接管） |
| **systemGestureView** | `GestureStubView` | **命中** | ✓ **后续边缘手势的接入点已确认存在** |
| recentsContainer | 存在于 MiuiHome | `-` | ✗ 见第 5 节缺陷 |

### 3.2 类名解析（对接层统一出口）

```
[FanFreeform] Resolved ROM class names
  freeformController=com.android.wm.shell.miuifreeform.MiuiFreeformModeController
  pinHandler=com.android.wm.shell.miuifreeform.MiuiFreeformModePinHandler
  animation=com.android.wm.shell.miuifreeform.MiuiFreeformModeAnimation
  eventController=com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController
```

前三项命中真实类；`eventController` 全部候选都不存在时按设计回退到首选名（不抛异常）。

### 3.3 钩子安装（12 条，全部成功）

```
SystemUI application attach diagnostic hook installed
Freeform controller hooks installed controller=com.android.wm.shell.miuifreeform.MiuiFreeformModeController
Freeform pin handler hook installed
Freeform mini animation target hook installed
Freeform edge-pin restore target hooks installed
Shortcut enter animation alignment hooks installed
Configured landscape freeform shape hooks installed orientation=1 requested=1
status bar touch-entry hook installed
notification shade window touch-entry hook installed
Notification shade expansion hook installed
Control center expansion hook installed class=com.android.systemui.controlcenter.ControlCenter
Control center expansion hook installed class=com.android.systemui.controlcenter.policy.ControlCenterControllerImpl
Authoritative system panel state hooks installed
```

### 3.4 运行时状态

```
Input capability runtime=ready freeform=waiting native_input=waiting corner_fallback=waiting
SystemUI probe inputController missing=...MulWinSwitchEventController   （4 个候选全部 missing）
SystemUI probe inputHandler     missing=...MulWinSwitchEventController$EventHandler
SystemUI probe freeformController present=...MiuiFreeformModeController
SystemUI probe freeformPinHandler present=...MiuiFreeformModePinHandler
SystemUI probe freeformAnimation  present=...MiuiFreeformModeAnimation
SystemUI ROM input candidates=[]                    ← 本阶段无原生输入源，符合设计
MiuiFreeformModeController connected
Input capability freeform=ready native_input=waiting
Authoritative system panel state notification=true control=false
```

**关键**：`MiuiFreeformModeController connected` —— 改对接层之前，模块硬编码 `multitasking` 前缀，
在 OS1 上根本拿不到这个实例；现在经 `RomTargets` 候选探测后**成功拿到并登记到 `RomBridge.freeform()`**。

### 3.5 稳定性

- 新 SystemUI 进程（PID 9270）中 `Exception / Error / failed / NoSuchFieldError` 检索结果：**0 条**
- 无 `FATAL EXCEPTION`、无 tombstone（logcat 中唯一一条 `FATAL EXCEPTION` 是 `CrashedByAdbException`，
  由本次 `am crash` 自己触发，属正常）
- 进程持续存活，未发生反复重启

> 对比：改动前的旧进程（PID 3960）反复出现
> `Pending task scan failed: java.lang.NoSuchFieldError:`（16:50 / 17:00 / 17:01 / 17:07 / 17:21），
> 新进程已不再出现。

---

## 4. MiuiHome 侧

```
(com.miui.home) HyperOS recents clear-button hook installed
(com.miui.home) HyperOS recents clear button hidden=false
(com.miui.home) Shortcut host active in MiuiHome
(com.miui.home) MiuiHome catalog refresh completed in 3330ms / 1856ms
```

MiuiHome 侧钩子正常，无异常。

## 5. 发现的缺陷（1 项，低风险）

**现象**：`RomBridge attached` 只在 SystemUI 进程出现，MiuiHome 进程没有。

**原因**：`FanFreeformHook.handleLoadPackage` 中，MiuiHome 分支在做完 `hookShortcutHost` /
`hookRecentsClearButton` 后直接 `return`，没有调用 `RomBridge.get().attach(...)`：

```java
if (MIUI_HOME.equals(loadPackageParam.packageName)) {
    hookShortcutHost(loadPackageParam.classLoader);
    hookRecentsClearButton(loadPackageParam.classLoader);
    return;                       // ← 缺少 RomBridge.get().attach(classLoader)
}
if (!SYSTEM_UI.equals(loadPackageParam.packageName)) return;
RomBridge.get().attach(loadPackageParam.classLoader);
```

**影响**：`RECENTS_CONTAINER`（`com.miui.home.recents.views.RecentsContainer`）只存在于 MiuiHome 的
ClassLoader 里，而快照只在 SystemUI 构建 → `recentsContainer` 恒为 `-`，
`RomBridge.recents().isAvailable()` 永远返回 false。目前尚无业务代码依赖它，所以不影响功能，
但接口给出的是**假阴性**。

**修法（约 2 行）**：在 MIUI_HOME 分支也调用 `attach`。两个进程各自持有独立的 `RomBridge`
单例（模块在每个宿主进程各加载一份），不会互相覆盖；语义应为「本进程可用的能力快照」。

## 6. 复测结果（18:13，缺陷已修复并复验）

修复：在 MIUI_HOME 分支补 `RomBridge.get().attach(loadPackageParam.classLoader)`。

重新构建（`BUILD SUCCESSFUL`，`RomBridgeTest` 10/10）+ 安装 + 重启两进程后：

```
(com.miui.home) RomBridge attached: Os1Capabilities{
  freeformController=- freeformPinHandler=- freeformAnimation=- controlCenter=-
  notificationShade=- gestureObserver=-
  recentsContainer=com.miui.home.recents.views.RecentsContainer   ← 已解析到
  shellEventController=- systemGestureView=- }
```

```
(com.android.systemui) RomBridge attached: Os1Capabilities{
  freeformController=com.android.wm.shell.miuifreeform.MiuiFreeformModeController
  freeformPinHandler=com.android.wm.shell.miuifreeform.MiuiFreeformModePinHandler
  freeformAnimation=com.android.wm.shell.miuifreeform.MiuiFreeformModeAnimation
  controlCenter=com.android.systemui.controlcenter.ControlCenter
  notificationShade=com.android.systemui.shade.NotificationShadeWindowView
  gestureObserver=com.miui.systemui.controller.GestureObserver
  recentsContainer=- shellEventController=-
  systemGestureView=com.android.systemui.fsgesture.GestureStubView }
```

- 两进程各自 attach，缺失项按「本进程不可用」如实为 `-`，无跨进程误报
- 新 SystemUI（PID 13102）与 MiuiHome（PID 12927）检索 `Exception / failed / Error`：**0 条**
- MiuiHome 后续钩子正常：recents clear-button、Shortcut host active、catalog refresh 完成

---

## 7. 原生输入通道：可行性探针 + 严格 A/B（19:13，已闭环）

### 7.1 结论

OS1 上**存在**与 OS3 同源的原生输入通道：框架标准手势监听
`InputManager.monitorGestureInput(name, displayId)`，返回值自带 `InputMonitor`，
可 `pilferPointers()`。已真机证实有效，已正式接线，未使用任何降级/替代方案。

### 7.2 探针先行的原因

`pilferPointers()` 调用不抛异常 ≠ 真的抢到了手势。为排除"其实没生效"的误判，
先装了一版**只带探针**的包（只观察 + 一次可开关的抢占），用 `setprop` 做严格 A/B，
确认后才写正式实现；探针代码随后整段删除。

### 7.3 A/B 严格对照（同一次开机、同一进程 PID 24601）

观测目标：Settings 的 `WifiSettingsActivity`，边缘滑动若生效会退回 Launcher。

| 组 | `persist.fanprobe.pilfer` | 日志 | 滑动前 | 滑动后 |
|---|---|---|---|---|
| A（对照） | `0` | `doPilfer=false ok=false monitor=true` | `WifiSettingsActivity` | `com.miui.home/.launcher.Launcher` |
| B（抢占） | `1` | `doPilfer=true ok=true monitor=true` | `WifiSettingsActivity` | `WifiSettingsActivity`（**未返回**） |
| B 复测 | `1` | 同上 | `WifiSettingsActivity` | `WifiSettingsActivity`（**未返回**） |

注入命令（事件能进手势监听，无需人工滑动）：

```bash
adb shell am start -a android.settings.WIFI_SETTINGS
adb shell input swipe 3 1300 520 1300 350
```

结论：**调用 `pilferPointers()` 后系统返回手势被真实掐断**，不是"它本来就不触发"。

### 7.4 探针顺带排除的错误结论

探针同时挂了 `com.android.systemui.fsgesture.GestureStubView#onTouchEvent`
（hook 成功，`hooks=1`），但**两组都是 0 条事件**——它在 HyperOS 1 上就是不收触摸
（MIUI 12 的旧路径）。因此"stub 静默"**不能**用来证明 pilfer 生效；这也是
"读 GestureStubView 旁路"之所以是降级方案（拿不到 InputMonitor，无法抢占）的实测依据。

### 7.5 正式接线后的端到端验证（PID 31813）

```
Input capability native_input=switching corner_fallback=inactive
Input capability native_input=ready     corner_fallback=inactive
System gesture monitor attached kind=FRAMEWORK_GESTURE_MONITOR monitor=true
Input capability freeform=ready native_input=ready
```

- 自建兜底窗口已按状态机自动停用（`corner_fallback=inactive`），不再与原生通道并存
- 左下角注入滑动 → 业务层完整识别：
  ```
  Fan hot zone armed on down corner=LEFT
  Fan input claimed after inward-upward direction distance=28 capture=pilfer   ← 抢占生效
  Fan released without icon hit; launch cancelled
  ```
- 负向：屏幕中央滑动 → 业务层**零输出**，全局手势流未造成误触发
- 稳定性：本轮 SystemUI 检索 `E/LSPosed` / `Exception`：**0 条**
- 事件坐标为屏幕坐标（`x == rawX`），与业务层既有坐标系一致，无需换算
