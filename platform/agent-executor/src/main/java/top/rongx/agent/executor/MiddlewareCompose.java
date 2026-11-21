package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件 compose 文件生成器（纯函数，可单测）。
 *
 * <p>为什么改用 compose 而不是继续手拼 {@code docker run}：
 * <ul>
 *   <li><b>加固参数有地方放</b>：{@code cap_drop / security_opt / healthcheck} 在 compose 里是
 *       声明式字段，一眼可读；手拼 argv 时漏了就是漏了（中间件容器曾长期零安全参数，见 G5）；</li>
 *   <li><b>就绪判定交给 compose</b>：{@code up -d --wait} 会等到 healthcheck 通过，
 *       取代"起完再轮询 {@code docker inspect}} 的自造等待（原来 45s 轮询 + 750ms 间隔）；</li>
 *   <li><b>一次起多个</b>：一个环境需要的 mysql+redis+rabbitmq 从"三次 docker run + 三次轮询"
 *       变成一次 compose，失败时报出是哪个 service。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>接管的东西：端口分配、凭据决策、卷重建、健康记录仍由
 * {@link DockerEnvProvisioner} 负责——那些是业务语义，compose 不提供。
 */
final class MiddlewareCompose {

    /** 一个中间件服务（已由调用方算好端口/凭据/卷名）。 */
    record Service(String kind, String containerName, String image, int hostPort,
                   String volumeName, String volumePath, Map<String, String> env,
                   List<String> command) {

        Service {
            env = env == null ? Map.of() : Map.copyOf(env);
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    private MiddlewareCompose() {
    }

    /**
     * 生成 compose YAML。
     *
     * @param project     compose 项目名（= 环境命名空间，决定容器分组与卷前缀）
     * @param network     环境网络（{@code <namespace>-net}，external：由 provisioner 保证存在）
     * @param services    服务列表
     */
    static String render(String project, String network, List<Service> services) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# 由平台生成（DockerEnvProvisioner），每次准备环境时覆盖，请勿手工修改\n");
        yaml.append("name: ").append(project).append('\n');
        yaml.append("services:\n");
        for (Service service : services) {
            yaml.append("  ").append(escape(service.kind())).append(":\n");
            // container_name 必须与 platform 记录的容器名一致：
            // 环境页/日志/清理都按这个名字找容器，compose 默认命名会多一层前缀。
            yaml.append("    container_name: ").append(escape(service.containerName())).append('\n');
            yaml.append("    image: ").append(escape(service.image())).append('\n');
            yaml.append("    restart: unless-stopped\n");
            // 加固：中间件都不需要额外 capability，也不需要 setuid 提权。
            yaml.append("    security_opt:\n      - no-new-privileges:true\n");
            yaml.append("    cap_drop:\n      - ALL\n");
            // 只绑回环：应用容器走环境网络访问，中间件绝不对外暴露。
            yaml.append("    ports:\n      - \"127.0.0.1:")
                    .append(service.hostPort()).append(':').append(defaultInternalPort(service.kind()))
                    .append("\"\n");
            if (network != null && !network.isBlank()) {
                yaml.append("    networks:\n      - ").append(escape(network)).append('\n');
            }
            if (service.volumeName() != null && service.volumePath() != null) {
                yaml.append("    volumes:\n      - ").append(escape(service.volumeName()))
                        .append(':').append(escape(service.volumePath())).append('\n');
            }
            if (!service.env().isEmpty()) {
                yaml.append("    environment:\n");
                // 按 key 排序：同样输入产生同样文件，diff 时不会因顺序抖动
                new java.util.TreeMap<>(service.env()).forEach((key, value) ->
                        yaml.append("      ").append(escape(key)).append(": \"")
                                .append(escape(value == null ? "" : value)).append("\"\n"));
            }
            if (!service.command().isEmpty()) {
                yaml.append("    command: [");
                List<String> quoted = new ArrayList<>();
                for (String arg : service.command()) {
                    quoted.add("\"" + escape(arg) + "\"");
                }
                yaml.append(String.join(", ", quoted)).append("]\n");
            }
            yaml.append("    labels:\n");
            yaml.append("      com.docker.compose.service: ").append(escape(service.kind())).append('\n');
            yaml.append("      agent.env.middleware: ").append(escape(service.kind())).append('\n');
        }
        if (network != null && !network.isBlank()) {
            yaml.append("networks:\n");
            yaml.append("  ").append(escape(network)).append(":\n");
            // external：网络由 provisioner 的 ensureNetwork 保证存在。
            // 让 compose 自己创建会在"两个环境共用同一网络"时互相删除。
            yaml.append("    external: true\n");
        }
        return yaml.toString();
    }

    /** 服务名 → compose 里的 key 不能含 {@code .} 等字符。 */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    /** compose 里声明的容器内端口（与 {@code defaultPort} 同源，避免两处漂移）。 */
    static int defaultInternalPort(String kind) {
        return DockerEnvProvisioner.defaultPort(kind);
    }

    /**
     * compose 命令：{@code up -d --wait} 会等 healthcheck 通过。
     *
     * <p>{@code --wait-timeout} 是必须的：没有它，某个中间件一直 unhealthy 会让整条部署
     * 无限挂着（原来的自造轮询有 45s 上限）。
     */
    static List<String> upArgv(DockerCli docker, String file, String project, int timeoutSeconds) {
        return docker.cmd("compose", "-f", file, "-p", project,
                "up", "-d", "--wait", "--wait-timeout", String.valueOf(Math.max(1, timeoutSeconds)));
    }

    /** compose 可用性探测命令。 */
    static List<String> versionArgv(DockerCli docker) {
        return docker.cmd("compose", "version", "--short");
    }

    /** 便于日志：把服务列表压成一行。 */
    static String describe(List<Service> services) {
        Map<String, Integer> ports = new LinkedHashMap<>();
        for (Service service : services) {
            ports.put(service.containerName(), service.hostPort());
        }
        return ports.toString();
    }
}
