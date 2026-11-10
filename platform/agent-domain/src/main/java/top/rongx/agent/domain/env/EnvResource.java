package top.rongx.agent.domain.env;

import java.util.ArrayList;
import java.util.List;

/**
 * 环境里的中间件资源（登记连接信息与运行状态；docker 来源的资源可由平台启停）。
 *
 * <p>{@code password} 是明文，只允许仓储/部署器内部使用；对外接口必须用
 * {@link #passwordMasked()}，且 {@link #toString()} 不会输出明文。
 *
 * @param id           资源 id
 * @param env          所属环境
 * @param kind         mysql / mariadb / redis / rabbitmq / minio / postgres / mongo
 * @param name         展示名
 * @param host         应用连接用的主机（按运行形态解析后的值；原始登记值见 {@link EnvAddressing}）
 * @param port         应用连接用的端口（按运行形态解析后的值）
 * @param containerPort 中间件容器内端口（如 mysql 3306；0 = 未知）
 * @param internalHost 容器内 / 共享网络内的地址（容器名或服务名；空 = 用 name）
 * @param databaseName 库名（可空）
 * @param username     用户名（可空）
 * @param password     明文密码（可空，仅内部使用）
 * @param network      应用侧 docker network（如 test_default）
 * @param serverId     远程来源的服务器 id（本机为 null）
 * @param source       LOCAL_DOCKER / REMOTE_DOCKER / MANUAL / COMPOSE
 * @param running      最近一次健康检查是否运行
 * @param lastCheckAt  最近检查时间（0 = 未检查）
 * @param lastError    最近错误
 */
public record EnvResource(
        String id,
        String env,
        String kind,
        String name,
        String host,
        int port,
        int containerPort,
        String internalHost,
        String databaseName,
        String username,
        String password,
        String network,
        String serverId,
        String source,
        boolean running,
        long lastCheckAt,
        String lastError) {

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    /** 接口只回掩码。 */
    public String passwordMasked() {
        return hasPassword() ? "****" : null;
    }

    /**
     * 连接信息缺失项（空 = 信息齐全）。
     *
     * <p>只有"缺用户名/密码/主机"才算异常；redis 允许无密码，
     * 手工登记的其它类型也只要求主机与端口。docker 容器是否启动不影响这里。
     */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (host == null || host.isBlank()) {
            missing.add("host");
        }
        if (port <= 0) {
            missing.add("port");
        }
        if (needsCredentials()) {
            if (username == null || username.isBlank()) {
                missing.add("username");
            }
            if (!hasPassword()) {
                missing.add("password");
            }
        }
        return List.copyOf(missing);
    }

    /** 会话类中间件（redis 等）不强制用户名/密码。 */
    private boolean needsCredentials() {
        String k = kind == null ? "" : kind;
        return switch (k) {
            case "mysql", "mariadb", "postgres", "mongo", "rabbitmq", "minio" -> true;
            default -> false;
        };
    }

    /**
     * 页面状态：MISSING（缺连接信息）/ RUNNING / STOPPED / UNKNOWN（未检查过）。
     *
     * <p>STOPPED 不是异常：部署前平台会自动启动被项目引用的中间件。
     */
    public String status() {
        if (!missingFields().isEmpty()) {
            return "MISSING";
        }
        if (running) {
            return "RUNNING";
        }
        return lastCheckAt > 0 ? "STOPPED" : "UNKNOWN";
    }

    @Override
    public String toString() {
        return "EnvResource[id=" + id + ", env=" + env + ", kind=" + kind + ", name=" + name
                + ", host=" + host + ", port=" + port + ", password="
                + (hasPassword() ? "****" : "null") + "]";
    }
}
