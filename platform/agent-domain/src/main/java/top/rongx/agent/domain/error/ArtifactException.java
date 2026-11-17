package top.rongx.agent.domain.error;

/** 产物缺失或非法（E-4xx）。 */
public final class ArtifactException extends PlatformException {

    public ArtifactException(ErrorCode errorCode, String message) {
        super(ErrorGroup.ARTIFACT, errorCode, message, null);
    }

    public ArtifactException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.ARTIFACT, errorCode, message, cause);
    }
}
