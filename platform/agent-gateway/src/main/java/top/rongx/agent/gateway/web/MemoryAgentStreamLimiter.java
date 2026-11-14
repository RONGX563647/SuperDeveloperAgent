package top.rongx.agent.gateway.web;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 单实例并发闸门：进程内信号量。
 *
 * <p>默认实现。多实例部署时各实例独立计数，请在 {@code agent.chat.limiter=jdbc}
 * 或网关层限流之间二选一。
 */
public class MemoryAgentStreamLimiter implements AgentStreamLimiter {

    private final Semaphore permits;

    public MemoryAgentStreamLimiter(int max) {
        this.permits = new Semaphore(Math.max(1, max));
    }

    @Override
    public Lease acquireOrThrow() {
        if (!permits.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "当前并发会话过多，请稍后再试");
        }
        return new Lease(UUID.randomUUID().toString());
    }

    @Override
    public void release(Lease lease) {
        permits.release();
    }

    @Override
    public int available() {
        return permits.availablePermits();
    }
}
