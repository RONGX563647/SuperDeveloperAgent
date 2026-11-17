package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.rongx.agent.domain.error.ErrorCode;

class ErrorStatusMapperTest {

    @Test
    void everyErrorCodeHasHttpStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertNotNull(ErrorStatusMapper.statusOf(code), "错误码缺少 HTTP 映射：" + code);
        }
    }

    @Test
    void keepsCompatibleStatusAndFixesPolicyCode() {
        assertEquals(HttpStatus.BAD_REQUEST, ErrorStatusMapper.statusOf(ErrorCode.ENV_BINDING_INVALID));
        assertEquals(HttpStatus.BAD_REQUEST, ErrorStatusMapper.statusOf(ErrorCode.DRY_RUN_FAILED));
        assertEquals(HttpStatus.CONFLICT, ErrorStatusMapper.statusOf(ErrorCode.CONFIRM_REQUIRED));
        assertEquals(HttpStatus.FORBIDDEN, ErrorStatusMapper.statusOf(ErrorCode.ENV_POLICY_DENIED));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ErrorStatusMapper.statusOf(ErrorCode.RATE_LIMITED));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorStatusMapper.statusOf(ErrorCode.AGENT_EXECUTION_FAILED));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorStatusMapper.statusOf(ErrorCode.INTAKE_FAILED));
    }
}
