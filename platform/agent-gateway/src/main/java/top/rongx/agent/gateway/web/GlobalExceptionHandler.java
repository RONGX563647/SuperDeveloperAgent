package top.rongx.agent.gateway.web;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import top.rongx.agent.domain.error.DomainException;

/** 统一错误响应：含 traceId，不含堆栈与内部细节（规约 §3）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 未登录 → 401（Sa-Token 会话缺失）。 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, Object>> notLogin(NotLoginException e) {
        return body(HttpStatus.UNAUTHORIZED, "未登录或会话已过期");
    }

    /** 无权限/角色 → 403（Sa-Token 权限校验）。 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class, DisableServiceException.class})
    public ResponseEntity<Map<String, Object>> forbidden(RuntimeException e) {
        return body(HttpStatus.FORBIDDEN, "无权执行该操作");
    }

    /** 领域异常：按 Kind 映射 404 / 409 / 503，不再一律落到 500。 */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> domain(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return body(status, e.getMessage());
    }

    /** 参数/业务校验失败 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 确认令牌相关失败 → 409（状态冲突，需要重新确认）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 明确带 HTTP 状态的异常（如 webhook 401）→ 按原状态返回，不落到 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return body(status, e.getReason() == null ? status.getReasonPhrase() : e.getReason());
    }

    /** 兜底：记录堆栈，返回泛化文案。 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> internal(RuntimeException e) {
        String traceId = UUID.randomUUID().toString();
        log.error("未处理异常 traceId={}", traceId, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请联系管理员并提供 traceId", traceId);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return body(status, message, UUID.randomUUID().toString());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, String traceId) {
        return ResponseEntity.status(status).body(Map.of(
                "code", status.value(),
                "message", message == null ? "未知错误" : message,
                "traceId", traceId));
    }
}
