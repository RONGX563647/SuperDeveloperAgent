package top.rongx.agent.domain.runtime;

import java.util.List;

/**
 * 应用容器只读巡检端口。
 *
 * <p>Agent 工具层只能依赖领域层，不能直接依赖执行面；容器/进程的具体读取方式
 * （docker CLI、进程探测）由执行面的 {@code AppRuntimeInspector} 实现。
 */
public interface RuntimeInspector {

    /** 一个应用容器的运行时事实。 */
    record ContainerStatus(
            String name,
            String project,
            String env,
            String service,
            String status,
            boolean running,
            int restartCount,
            String startedAt,
            String image,
            String imageId,
            String health,
            int exitCode,
            String ports,
            String runId,
            String sha,
            String composeProject) {
    }

    /** 一个宿主端口的使用者（运行中的容器）。 */
    record PortOwner(String container, String project, String env, String service,
                     int hostPort) {
    }

    /**
     * 列出某项目某环境的应用容器。
     *
     * @throws IllegalStateException 运行时不可用（docker 未安装 / 守护进程未启动）
     */
    List<ContainerStatus> containers(String projectId, String env);

    /**
     * 列出映射了某宿主端口的运行中容器（只读）。
     *
     * @throws IllegalStateException 运行时不可用
     */
    List<PortOwner> portOwners(int hostPort);

    /** 读取容器日志时间窗（原始行，过滤由调用方统一做）。 */
    List<String> logs(String container, int sinceMinutes);

    /** 容器服务名：多服务发布有 service 标签；单容器发布一个容器承担全部服务。 */
    static String serviceName(ContainerStatus status) {
        return status != null && status.service() != null && !status.service().isBlank()
                ? status.service()
                : "app";
    }
}
