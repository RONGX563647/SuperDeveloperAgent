package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 进程内并发闸门：超过上限返回 429，释放后可以继续进入。 */
class AgentStreamLimiterTest {

    @Test
    void rejectsWhenAllPermitsAreTaken() {
        AgentStreamLimiter limiter = new AgentStreamLimiter(1);
        limiter.acquireOrThrow();

        assertThrows(ResponseStatusException.class, limiter::acquireOrThrow);

        limiter.release();
        assertDoesNotThrow(limiter::acquireOrThrow);
        assertEquals(0, limiter.available());
    }
}
