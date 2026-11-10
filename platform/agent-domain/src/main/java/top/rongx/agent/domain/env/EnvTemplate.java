package top.rongx.agent.domain.env;

/**
 * 环境模板：dev / test / prod 三套全局配置。
 *
 * @param env           环境标识
 * @param network       部署时注入的应用网络（docker --network），可空
 * @param defaultTarget 默认部署目标（docker / host / server）
 * @param defaultPort   默认宿主端口（被 pipeline deploy.port 覆盖时以 pipeline 为准）
 * @param healthPath    默认健康检查路径
 * @param runtimeMode   运行形态（LOCAL_DEV / LOCAL_TEST / SERVER），空则按 env 推导
 * @param namespace     资源命名空间（agent-dev / agent-test / agent-prod），用于隔离与过滤
 * @param serverId      SERVER 形态绑定的服务器 id（空 = 默认服务器）
 * @param updatedAt     更新时间
 */
public record EnvTemplate(
        String env,
        String network,
        String defaultTarget,
        int defaultPort,
        String healthPath,
        String runtimeMode,
        String namespace,
        String serverId,
        long updatedAt) {

    public EnvTemplate {
        runtimeMode = RuntimeMode.of(runtimeMode, env).name();
        if (namespace == null || namespace.isBlank()) {
            namespace = defaultNamespace(env);
        }
        if (serverId != null && serverId.isBlank()) {
            serverId = null;
        }
    }

    /** 兼容旧调用：只声明基础字段，形态/命名空间按环境推导。 */
    public EnvTemplate(String env, String network, String defaultTarget, int defaultPort,
                       String healthPath, long updatedAt) {
        this(env, network, defaultTarget, defaultPort, healthPath, null, null, null, updatedAt);
    }

    public static String defaultNamespace(String env) {
        return "agent-" + (env == null || env.isBlank() ? "dev" : env);
    }

    public RuntimeMode mode() {
        return RuntimeMode.of(runtimeMode, env);
    }
}
