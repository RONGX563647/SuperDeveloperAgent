package top.rongx.agent.gateway.web;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一错误响应：含 traceId，不含堆栈与内部细节（规约 §3）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
