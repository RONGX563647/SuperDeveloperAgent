package top.rongx.agent.domain.policy;

import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.ErrorGroup;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 破坏性 L2 动作需要人工确认：异常携带已登记的变更单号。
 *
 * <p>调用方（工具或 HTTP 层）把它转成可读提示，用户确认后由
 * {@code POST /api/v1/changes/{id}/apply} 带一次性令牌执行。
 */
public final class ConfirmationRequiredException extends PlatformException {

    private final String changeId;

    public ConfirmationRequiredException(String changeId, String message) {
        super(ErrorGroup.PROMOTION, ErrorCode.CONFIRM_REQUIRED, message, null);
        this.changeId = changeId;
    }

    public String changeId() {
        return changeId;
    }
}
