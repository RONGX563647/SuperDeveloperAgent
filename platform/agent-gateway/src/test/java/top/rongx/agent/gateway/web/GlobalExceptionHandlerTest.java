package top.rongx.agent.gateway.web;

import java.util.Map;
import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常响应与 SSE 内容协商：流式请求只接受 text/event-stream，
 * 错误响应若仍写 JSON，Spring 会因内容协商失败把 401 退化成 500 并打印 ERROR 堆栈。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("SSE 请求未登录只返回 401 状态码，不写 JSON")
    void notLoginKeepsStatusOnlyForEventStream() {
        ResponseEntity<?> response = handler.notLogin(notLogin(), request("text/event-stream"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    @DisplayName("普通请求未登录仍返回带 message 的 JSON 错误体")
    void notLoginKeepsJsonBodyForJsonRequest() {
        ResponseEntity<?> response = handler.notLogin(notLogin(), request("application/json"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("未登录或会话已过期", body.get("message"));
    }

    @Test
    @DisplayName("SSE 请求附带 */* 时保持 JSON 错误体")
    void notLoginKeepsJsonWhenWildcardIsAccepted() {
        ResponseEntity<?> response = handler.notLogin(notLogin(), request("text/event-stream, */*"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertInstanceOf(Map.class, response.getBody());
    }

    @Test
    @DisplayName("方法不支持：405 并在报文里指名方法与路径")
    void methodNotSupportedExplainsWhatFailed() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/git-issues/export");

        ResponseEntity<?> response = handler.methodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("POST"), request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        String message = String.valueOf(body.get("message"));
        // 默认报文是空的，排查全靠状态码；这里必须说清哪个方法打到了哪个路径
        assertTrue(message.contains("POST"), message);
        assertTrue(message.contains("/api/v1/git-issues/export"), message);
        assertEquals(405, body.get("code"));
    }

    private MockHttpServletRequest request(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/workspaces/A/dev/events");
        request.addHeader(HttpHeaders.ACCEPT, accept);
        return request;
    }

    private NotLoginException notLogin() {
        return new NotLoginException("token 无效", "login", "stale-token");
    }
}
