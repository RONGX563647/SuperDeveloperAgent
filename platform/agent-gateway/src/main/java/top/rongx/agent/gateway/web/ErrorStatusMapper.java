package top.rongx.agent.gateway.web;

import org.springframework.http.HttpStatus;
import top.rongx.agent.domain.error.ErrorCode;

/**
 * 业务错误码 → HTTP 状态。
 *
 * <p>兼容期沿用既有语义：构建/配方/环境校验类返回 400，门禁冲突返回 409；
 * 仅 {@link ErrorCode#ENV_POLICY_DENIED}（403）与 {@link ErrorCode#RATE_LIMITED}（429）
 * 按契约修正。新增码段必须先在这里给出映射。
 */
final class ErrorStatusMapper {

    private ErrorStatusMapper() {
    }

    static HttpStatus statusOf(ErrorCode code) {
        return switch (code.group()) {
            case BUILD, RECIPE -> HttpStatus.BAD_REQUEST;
            case PROMOTION -> HttpStatus.CONFLICT;
            case ENVIRONMENT -> environmentStatus(code);
            case RUN, ARTIFACT, DEPLOY, INTAKE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static HttpStatus environmentStatus(ErrorCode code) {
        return switch (code) {
            case ENV_POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
