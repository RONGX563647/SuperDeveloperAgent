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
        AgentStreamLimiter limiter = new MemoryAgentStreamLimiter(1);
        AgentStreamLimiter.Lease lease = limiter.acquireOrThrow();

        assertThrows(ResponseStatusException.class, limiter::acquireOrThrow);

        limiter.release(lease);
        assertDoesNotThrow(limiter::acquireOrThrow);
        assertEquals(0, limiter.available());
    }

    @Test
    void jdbcLimiterSharesQuotaAcrossInstances() {
        java.nio.file.Path db = java.nio.file.Path.of("target",
                "rate-limit-test-" + java.util.UUID.randomUUID() + ".db");
        JdbcAgentStreamLimiter first = new JdbcAgentStreamLimiter(db, 1, 600);
        JdbcAgentStreamLimiter second = new JdbcAgentStreamLimiter(db, 1, 600);

        AgentStreamLimiter.Lease lease = first.acquireOrThrow();
        assertThrows(ResponseStatusException.class, second::acquireOrThrow);

        first.release(lease);
        AgentStreamLimiter.Lease next = second.acquireOrThrow();
        second.release(next);
    }
}
