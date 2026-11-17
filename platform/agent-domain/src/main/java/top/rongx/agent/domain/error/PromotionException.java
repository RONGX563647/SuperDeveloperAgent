package top.rongx.agent.domain.error;

/** 晋升与门禁未通过（E-6xx）。 */
public final class PromotionException extends PlatformException {

    public PromotionException(ErrorCode errorCode, String message) {
        super(ErrorGroup.PROMOTION, errorCode, message, null);
    }

    public PromotionException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.PROMOTION, errorCode, message, cause);
    }
}
