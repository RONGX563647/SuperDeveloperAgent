package top.rongx.agent.gateway.web;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 进程内 Agent 轮次并发闸门。
 *
 * <p>每条对话/部署都会构建 Agent 并可能拉起 MCP 子进程，必须有上限；否则单个用户
 * 并发请求就能把线程、内存和外部进程打满。多实例部署时每个实例各自计数。
 */
@Component
public class AgentStreamLimiter {

    private final Semaphore permits;

    public AgentStreamLimiter(@Value("${agent.chat.max-concurrent:8}") int max) {
        this.permits = new Semaphore(Math.max(1, max));
    }

    /** 拿不到许可直接返回 429，不进入 Agent。 */
    public void acquireOrThrow() {
        if (!permits.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "当前并发会话过多，请稍后再试");
        }
    }

    /** 与 {@link #acquireOrThrow()} 成对调用，重复释放由调用方用原子标志保护。 */
    public void release() {
        permits.release();
    }

    /** 当前可用许可数（诊断用）。 */
    public int available() {
        return permits.availablePermits();
    }
}
