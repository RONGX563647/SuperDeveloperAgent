package top.rongx.agent.domain.conversation;

/**
 * 会话删除后的跨存储清理。
 *
 * <p>会话、历史运行、任务在同一个 SQLite 事务里删除；这里承接另外两处残留：
 * {@code run_ledger.db} 中挂在会话上的运行记录，以及 Agent 记忆目录
 * {@code agent-state/<user>/<session>}。实现必须尽力清理，单个存储失败不应阻断删除。
 */
@FunctionalInterface
public interface SessionDataCleaner {

    void purge(String ownerUserId, String conversationId);

    /** 测试/降级用：没有外部存储时不做清理。 */
    static SessionDataCleaner noop() {
        return (ownerUserId, conversationId) -> {
            // 无外部存储，无需清理
        };
    }
}
