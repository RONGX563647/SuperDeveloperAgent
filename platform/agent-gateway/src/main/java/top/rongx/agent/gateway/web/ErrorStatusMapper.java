package top.rongx.agent.gateway.web;

import org.springframework.http.HttpStatus;
import top.rongx.agent.domain.error.ErrorCode;

/**
 * 业务错误码 → HTTP 状态。
 *
 * <p>按错误码语义类型映射，保持既有行为：输入类 400、状态冲突 409、内部失败 500；
 * {@link ErrorCode#ENV_POLICY_DENIED}（403）与 {@link ErrorCode#RATE_LIMITED}（429）
 * 按契约单独修正。新增错误码只需声明 {@code ErrorKind}。
 */
final class ErrorStatusMapper {

    private ErrorStatusMapper() {
    }

    static HttpStatus statusOf(ErrorCode code) {
        if (code == ErrorCode.ENV_POLICY_DENIED) {
            return HttpStatus.FORBIDDEN;
        }
        if (code == ErrorCode.RATE_LIMITED) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        return switch (code.kind()) {
            case INPUT -> HttpStatus.BAD_REQUEST;
            case STATE -> HttpStatus.CONFLICT;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
