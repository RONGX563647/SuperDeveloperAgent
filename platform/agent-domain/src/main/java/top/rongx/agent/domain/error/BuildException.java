package top.rongx.agent.domain.error;

/** 构建/执行失败（E-1xx）。 */
public final class BuildException extends PlatformException {

    public BuildException(ErrorCode errorCode, String message) {
        super(ErrorGroup.BUILD, errorCode, message, null);
    }

    public BuildException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.BUILD, errorCode, message, cause);
    }
}
