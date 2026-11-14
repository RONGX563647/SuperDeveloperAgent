package top.rongx.agent.domain.policy;

/**
 * 破坏性 L2 动作需要人工确认：异常携带已登记的变更单号。
 *
 * <p>调用方（工具或 HTTP 层）把它转成可读提示，用户确认后由
 * {@code POST /api/v1/changes/{id}/apply} 带一次性令牌执行。
 */
public class ConfirmationRequiredException extends RuntimeException {

    private final String changeId;

    public ConfirmationRequiredException(String changeId, String message) {
        super(message);
        this.changeId = changeId;
    }

    public String changeId() {
        return changeId;
    }
}
