package top.rongx.agent.domain.error;

/** 环境与策略失败（E-7xx）。 */
public final class EnvironmentException extends PlatformException {

    public EnvironmentException(ErrorCode errorCode, String message) {
        super(ErrorGroup.ENVIRONMENT, errorCode, message, null);
    }

    public EnvironmentException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.ENVIRONMENT, errorCode, message, cause);
    }
}
