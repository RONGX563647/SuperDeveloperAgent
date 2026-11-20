package top.rongx.agent.domain.port;

/**
 * 审计落库端口（BUG-37）。
 *
 * <p>append-only：实现只允许 INSERT，不暴露 UPDATE/DELETE。
 * 所有"注释里承诺写审计日志"的写入点（变更确认/应用、明文查看等）都应调用这里，
 * 而不是只打 slf4j 应用日志——日志会被轮转覆盖，审计必须能事后追溯"谁在什么时候确认/查看了什么"。
 */
public interface AuditTrail {

    /**
     * 记录一条审计事件。
     *
     * @param actor     操作人（userId；系统动作用 "system"）
     * @param role      操作人角色（admin/devops/viewer/...），可空
     * @param action    动作词汇：change:confirm / change:apply / settings:reveal / ...
     * @param targetType 目标类型：change_plan / env_var / effective_env / ...
     * @param targetId   目标 id（changeId、变量名等），可空
     * @param projectId  项目 id，可空
     * @param env        目标环境，可空
     * @param result     结果：OK / DENIED / FAILED，可空
     * @param detail     补充说明（不得包含明文密钥），可空
     */
    void record(String actor, String role, String action, String targetType,
                String targetId, String projectId, String env, String result, String detail);
}
