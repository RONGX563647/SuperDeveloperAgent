package top.rongx.agent.domain.audit;

/**
 * 一次工具调用的审计记录。只存参数哈希，不存明文参数，避免密钥进审计表。
 *
 * @param id         记录 id
 * @param sessionId  会话 id（无会话时为空）
 * @param userId     操作人
 * @param projectId  项目
 * @param env        环境
 * @param tool       工具名
 * @param argsHash   参数规范化后的 SHA-256（不落明文）
 * @param resultCode OK / ERROR / EXCEPTION / DENIED
 * @param durationMs 耗时
 * @param createdAt  时间
 */
public record ToolAudit(
        String id,
        String sessionId,
        String userId,
        String projectId,
        String env,
        String tool,
        String argsHash,
        String resultCode,
        long durationMs,
        long createdAt) {
}
