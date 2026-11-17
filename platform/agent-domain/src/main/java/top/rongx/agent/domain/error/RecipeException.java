package top.rongx.agent.domain.error;

/** 配方与参数非法（E-2xx）。 */
public final class RecipeException extends PlatformException {

    public RecipeException(ErrorCode errorCode, String message) {
        super(ErrorGroup.RECIPE, errorCode, message, null);
    }

    public RecipeException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.RECIPE, errorCode, message, cause);
    }
}
