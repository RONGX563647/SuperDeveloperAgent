package top.rongx.agent.domain.policy;

import top.rongx.agent.domain.error.ErrorCode;

/** 环境策略拒绝；HTTP 层映射 403，工具层返回 {@link ErrorCode#ENV_POLICY_DENIED}。 */
public class PolicyDeniedException extends IllegalStateException {

    private final String code;

    public PolicyDeniedException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    public String code() {
        return code;
    }
}
