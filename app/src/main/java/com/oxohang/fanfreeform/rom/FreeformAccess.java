package com.oxohang.fanfreeform.rom;

/**
 * 自由窗口（小窗）能力的统一访问口。
 *
 * <p>业务代码只依赖本接口，不接触类名、反射或 Xposed。由 {@link RomBridge} 提供当前 ROM
 * 的具体实现。
 */
public interface FreeformAccess {

    /** 当前 ROM 上是否存在自由窗口控制器。 */
    boolean isAvailable();

    /** 解析到的真实控制器类名（例如 OS1 是 com.android.wm.shell.miuifreeform.*）。 */
    String resolvedClassName();

    /** 宿主在 hook 到控制器实例后登记进来；实现仅持有引用，不接管生命周期。 */
    void registerControllerInstance(Object instance);

    /** 当前控制器实例。不可用→UNSUPPORTED；类存在但实例未创建→NOT_READY。 */
    BridgeResult<Object> controllerInstance();

    /** 是否支持 Pin 处理器的边缘停靠回调（MiuiFreeformModePinHandler）。 */
    boolean supportsPinHandler();

    /** 是否支持自由窗口动画目标回调（MiuiFreeformModeAnimation）。 */
    boolean supportsAnimation();
}
