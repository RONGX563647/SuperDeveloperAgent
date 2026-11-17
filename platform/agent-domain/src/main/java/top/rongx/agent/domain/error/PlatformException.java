package top.rongx.agent.domain.error;

/**
 * 平台业务异常基类：错误码与业务分组强绑定。
 *
 * <p>业务可预期失败必须抛本类的子类，禁止用 {@code IllegalArgumentException} /
 * {@code IllegalStateException} 携带错误码。子类只能位于本包或
 * {@code top.rongx.agent.domain.policy}（策略与确认异常）。
 *
 * <p>构造时校验错误码所属分组，跨组使用立即失败：例如 {@code DeployException}
 * 只能接收 5xx 错误码。
 */
public abstract class PlatformException extends RuntimeException {

    private final ErrorCode errorCode;

    protected PlatformException(ErrorGroup group, ErrorCode errorCode, String message,
            Throwable cause) {
        super(message, cause);
        if (errorCode.group() != group) {
            // spec-ignore: R15 契约守卫，跨组错误码必须当场拒绝
            throw new IllegalArgumentException("错误码 " + errorCode.code() + " 属于 "
                    + errorCode.group() + "，不能用于 " + group + " 异常");
        }
        this.errorCode = errorCode;
    }

    /** 业务错误码枚举，供日志与测试使用。 */
    public final ErrorCode errorCode() {
        return errorCode;
    }

    /** 对外错误码字符串，例如 {@code E-707}。 */
    public final String code() {
        return errorCode.code();
    }
}
