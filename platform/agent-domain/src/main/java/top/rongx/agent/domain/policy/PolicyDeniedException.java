package top.rongx.agent.domain.policy;

/** 环境策略拒绝；HTTP 层映射 403，工具层返回 E-707。 */
public class PolicyDeniedException extends IllegalStateException {

    private final String code;

    public PolicyDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
