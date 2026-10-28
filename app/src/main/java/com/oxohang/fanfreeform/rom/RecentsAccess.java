package com.oxohang.fanfreeform.rom;

/**
 * 小米桌面（com.miui.home）最近任务能力的统一访问口。
 *
 * <p>该能力属于桌面进程，与 SystemUI 进程的能力分开解析；本阶段只暴露可用性与
 * 真实类名，具体的卡片观察/清理按钮挂接在后续手势/任务阶段处理。
 */
public interface RecentsAccess {

    /** 当前 ROM 上是否存在最近任务容器。 */
    boolean isAvailable();

    /** 解析到的真实类名。 */
    String resolvedClassName();
}
