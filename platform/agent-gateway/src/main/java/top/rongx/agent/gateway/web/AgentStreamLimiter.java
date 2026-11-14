package top.rongx.agent.gateway.web;

/**
 * Agent 轮次并发闸门。
 *
 * <p>每条对话/部署都会构建 Agent 并可能拉起 MCP 子进程，必须有上限；
 * 实现分两种：单实例内存信号量（默认）与 SQLite 租约（多实例共享配额）。
 * 多实例部署仍建议在网关/负载均衡层再做一次总限流。
 */
public interface AgentStreamLimiter {

    /**
     * 占用一个许可；超限直接抛 429，不进入 Agent。
     *
     * @return 释放凭据，必须原样传给 {@link #release(Lease)}
     */
    Lease acquireOrThrow();

    /** 释放许可；重复释放无副作用。 */
    void release(Lease lease);

    /** 当前可用许可数（诊断用）。 */
    int available();

    /** 许可凭据（内存实现不关心 token，租约实现用它精确删除自己的行）。 */
    record Lease(String token) {
    }
}
