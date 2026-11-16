package top.rongx.agent.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import top.rongx.agent.domain.policy.ConfirmationRequiredException;

/** 统一错误响应：含 traceId，不含堆栈与内部细节（规约 §3）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 未登录 → 401（Sa-Token 会话缺失）。 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<?> notLogin(NotLoginException e, HttpServletRequest request) {
        return error(request, HttpStatus.UNAUTHORIZED, "未登录或会话已过期");
    }

    /** 无权限/角色 → 403（Sa-Token 权限校验）。 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class, DisableServiceException.class})
    public ResponseEntity<?> forbidden(RuntimeException e, HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "无权执行该操作");
    }

    /** 参数/业务校验失败 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e, HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 确认令牌相关失败 → 409（状态冲突，需要重新确认）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e, HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, e.getMessage());
    }

    /** 破坏性动作需要人工确认 → 409，message 里带 changeId。 */
    @ExceptionHandler(ConfirmationRequiredException.class)
    public ResponseEntity<?> confirmationRequired(ConfirmationRequiredException e,
            HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, e.getMessage());
    }

    /** Agent 执行失败 → 500（模型/工具依赖问题，不是客户端可以重试的 409 冲突）。 */
    @ExceptionHandler(AgentExecutionException.class)
    public ResponseEntity<?> agentFailed(AgentExecutionException e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.error("Agent 执行失败 traceId={} reason={}", traceId, e.getMessage(), e);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR,
                "Agent 执行失败，请稍后重试并提供 traceId", traceId);
    }

    /** 明确带 HTTP 状态的异常（如 webhook 401）→ 按原状态返回，不落到 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> responseStatus(ResponseStatusException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return error(request, status,
                e.getReason() == null ? status.getReasonPhrase() : e.getReason());
    }

    /** 兜底：记录堆栈，返回泛化文案。 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> internal(RuntimeException e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.error("未处理异常 traceId={}", traceId, e);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR,
                "服务内部错误，请联系管理员并提供 traceId", traceId);
    }

    private ResponseEntity<?> error(HttpServletRequest request, HttpStatus status, String message) {
        return error(request, status, message, UUID.randomUUID().toString());
    }

    /**
     * SSE 请求只声明接受 {@code text/event-stream}：若异常处理仍然写 JSON，
     * Spring 的内容协商会失败（HttpMediaTypeNotAcceptableException），
     * 于是 401/403 被容器改写成 500 并在日志里留下 ERROR 堆栈。
     *
     * <p>流式客户端只依赖状态码决定「跳登录 / 重连 / 终止」，
     * 因此这类请求直接返回无 body 的响应，既不会触发协商失败，也不会污染日志。
     */
    private ResponseEntity<?> error(HttpServletRequest request, HttpStatus status,
            String message, String traceId) {
        if (acceptsOnlyEventStream(request)) {
            return ResponseEntity.status(status).build();
        }
        return body(status, message, traceId);
    }

    private boolean acceptsOnlyEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return false;
        }
        String value = accept.toLowerCase(Locale.ROOT);
        if (!value.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return false;
        }
        // 浏览器 EventSource 有时附带 */*：这种请求 JSON 仍可写，保持原有错误体
        return !value.contains(MediaType.APPLICATION_JSON_VALUE) && !value.contains("*/*");
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
