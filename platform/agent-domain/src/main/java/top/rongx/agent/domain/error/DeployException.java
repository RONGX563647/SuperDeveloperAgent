package top.rongx.agent.domain.error;

/** 部署失败（E-5xx）。 */
public final class DeployException extends PlatformException {

    public DeployException(ErrorCode errorCode, String message) {
        super(ErrorGroup.DEPLOY, errorCode, message, null);
    }

    public DeployException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.DEPLOY, errorCode, message, cause);
    }
}
