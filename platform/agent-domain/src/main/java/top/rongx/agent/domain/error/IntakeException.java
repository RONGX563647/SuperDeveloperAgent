package top.rongx.agent.domain.error;

/** 项目接入失败（E-8xx）。 */
public class IntakeException extends PlatformException {

    public IntakeException(ErrorCode errorCode, String message) {
        super(ErrorGroup.INTAKE, errorCode, message, null);
    }

    public IntakeException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.INTAKE, errorCode, message, cause);
    }
}
