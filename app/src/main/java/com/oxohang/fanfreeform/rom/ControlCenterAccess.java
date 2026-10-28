package com.oxohang.fanfreeform.rom;

/**
 * 控制中心能力的统一访问口。
 *
 * <p>OS1 与较新 ROM 的控制中心位于不同包名，业务通过本接口查询可用性，不感知底层类名
 * 差异。本阶段只解析并声明能力；展开/收起的观察挂接（用于暂停手势）属于手势阶段，在
 * 后续实现后由 {@link #supportsExpansionObserver()} 返回 true。
 */
public interface ControlCenterAccess {

    /** 当前 ROM 上是否存在可控的控制中心实现。 */
    boolean isAvailable();

    /** 解析到的真实类名。 */
    String resolvedClassName();

    /** 是否已支持展开/收起观察（本阶段恒 false，手势接入后返回 true）。 */
    boolean supportsExpansionObserver();
}
