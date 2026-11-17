package top.rongx.agent.domain.policy;

import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.ErrorGroup;
import top.rongx.agent.domain.error.PlatformException;

/** 环境策略拒绝；HTTP 层按错误码映射（E-707 → 403，E-709 → 429）。 */
public final class PolicyDeniedException extends PlatformException {

    public PolicyDeniedException(ErrorCode errorCode, String message) {
        super(ErrorGroup.ENVIRONMENT, errorCode, message, null);
    }

    public PolicyDeniedException(ErrorCode errorCode, String message, Throwable cause) {
        super(ErrorGroup.ENVIRONMENT, errorCode, message, cause);
    }
}
