package top.rongx.agent.domain.error;

/** 运行时失败（E-3xx）。 */
public class RunException extends PlatformException {

    public RunException(ErrorCode errorCode, String message) {
        super(ErrorGroup.RUN, errorCode, message, null);
    }

    public RunException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.RUN, errorCode, message, cause);
    }
}
