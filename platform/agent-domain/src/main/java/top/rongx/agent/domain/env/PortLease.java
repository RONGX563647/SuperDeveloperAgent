package top.rongx.agent.domain.env;

/**
 * 端口租约：保证同一 (项目, 环境, 容器端口) 重复发布时端点稳定。
 *
 * @param projectId     项目
 * @param env           环境
 * @param containerPort 容器内端口
 * @param hostPort      实际分配的宿主端口
 * @param runId         最近一次使用它的运行
 * @param updatedAt     更新时间
 */
public record PortLease(
        String projectId,
        String env,
        int containerPort,
        int hostPort,
        String runId,
        long updatedAt) {
}
