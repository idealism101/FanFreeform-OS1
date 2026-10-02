package com.oxohang.fanfreeform.rom;

/**
 * 对接层统一返回结构。
 *
 * <p>所有 {@link RomBridge} 暴露的能力都通过它返回：调用方不需要处理异常，只需要判断
 * {@link #isOk()}，失败时从 {@link #code()} 拿到可控的错误分类、从 {@link #detail()}
 * 拿到可写进兼容性报告的描述。
 */
public final class BridgeResult<T> {

    /** 失败分类。业务代码应针对分类做降级，而不是针对具体 ROM 分支。 */
    public enum Code {
        /** 调用成功。 */
        OK,
        /** 当前 ROM/系统版本上根本没有这套原生能力。 */
        UNSUPPORTED,
        /** 能力所需的目标类不存在。 */
        CLASS_MISSING,
        /** 类存在，但方法/字段签名与预期不匹配。 */
        METHOD_MISSING,
        /** 找到了目标，但反射调用过程抛异常。 */
        INVOCATION_FAILED,
        /** 目标存在但尚未就绪（例如宿主进程还没有 attach、实例还没有创建）。 */
        NOT_READY
    }

    private final Code code;
    private final T value;
    private final String detail;
    private final Throwable cause;

    private BridgeResult(Code code, T value, String detail, Throwable cause) {
        this.code = code;
        this.value = value;
        this.detail = detail;
        this.cause = cause;
    }

    public static <T> BridgeResult<T> ok(T value) {
        return new BridgeResult<>(Code.OK, value, null, null);
    }

    public static <T> BridgeResult<T> fail(Code code, String detail) {
        return new BridgeResult<>(code, null, detail, null);
    }

    public static <T> BridgeResult<T> fail(Code code, String detail, Throwable cause) {
        return new BridgeResult<>(code, null, detail, cause);
    }

    public boolean isOk() {
        return code == Code.OK;
    }

    public Code code() {
        return code;
    }

    public T value() {
        return value;
    }

    public String detail() {
        return detail;
    }

    public Throwable cause() {
        return cause;
    }

    @Override public String toString() {
        return "BridgeResult{" + code + (detail == null ? "" : " detail=" + detail) + "}";
    }
}
