package top.rongx.agent.gateway.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.SshAccess;

/**
 * 中间件自动发现（只读）：本机 Docker / 远程 SSH。
 *
 * <p>只执行 {@code docker ps / docker inspect}，不启动、不停止任何容器；
 * 发现结果先作为候选返回，人工确认后才写入环境模板。
 */
@Component
public class EnvironmentDiscoveryService {

    private static final long SSH_TIMEOUT_MS = 15_000;

    private final DeployServerRepository servers;
    private final SshAccess ssh;

    public EnvironmentDiscoveryService(DeployServerRepository servers, SshAccess ssh) {
        this.servers = servers;
        this.ssh = ssh;
    }

    /** 本机 Docker 发现（按环境命名空间过滤；没有匹配时回退为全部，避免看不到旧容器）。 */
    public List<EnvResource> discoverLocal(String env, EnvTemplate template) {
        List<String> names = runLocal("docker", "ps", "-a", "--format", "{{.Names}}");
        List<EnvResource> all = new ArrayList<>();
        for (String name : names) {
            String n = name.trim();
            if (n.isEmpty()) {
                continue;
            }
            EnvResource r = inspectLocal(env, n, template);
            if (r != null) {
                all.add(r);
            }
        }
        return filterByNamespace(all, template);
    }

    /** 远程服务器发现（复用设置页的 SSH 配置）。 */
    public List<EnvResource> discoverRemote(String env, String serverId, EnvTemplate template) {
        DeployServer server = (serverId == null || serverId.isBlank()
                ? servers.findDefault()
                : servers.find(serverId)).orElseThrow(() ->
                new IllegalArgumentException("没有可用的 SSH 服务器：请先在「设置 → 服务器」添加"));
        String secret = servers.secretOf(server.id()).orElse(null);
        String list = sshRun(server, secret, "docker ps -a --format '{{.Names}}'");
        List<EnvResource> all = new ArrayList<>();
        for (String name : list.split("\\R")) {
            String n = name.trim();
            if (n.isEmpty()) {
                continue;
            }
            EnvResource r = inspectRemote(env, server, secret, n, template);
            if (r != null) {
                all.add(r);
            }
        }
        return filterByNamespace(all, template);
    }

    /** 只保留属于该环境命名空间的容器（按标签 / 名称 / 网络匹配）。 */
    private List<EnvResource> filterByNamespace(List<EnvResource> all, EnvTemplate template) {
        String ns = template == null || template.namespace() == null || template.namespace().isBlank()
                ? null : template.namespace().trim();
        if (ns == null) {
            return all;
        }
        List<EnvResource> matched = new ArrayList<>();
        for (EnvResource r : all) {
            if (belongsTo(r, ns)) {
                matched.add(r);
            }
        }
        if (matched.isEmpty()) {
            return all;
        }
        return matched;
    }

    private static boolean belongsTo(EnvResource r, String ns) {
        String name = r.name() == null ? "" : r.name();
        if (name.equals(ns) || name.startsWith(ns + "-")) {
            return true;
        }
        String network = r.network() == null ? "" : r.network();
        for (String n : network.split("\\s+")) {
            if (n.equals(ns) || n.startsWith(ns)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 本机

    private EnvResource inspectLocal(String env, String name, EnvTemplate template) {
        List<String> meta = runLocal("docker", "inspect", "--format",
                "{{.Name}}|{{.Config.Image}}|{{.State.Running}}|"
                        + "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}|"
                        + "{{range $p,$conf := .NetworkSettings.Ports}}{{$p}}={{range $conf}}{{.HostPort}}{{end}};{{end}}",
                name);
        if (meta.isEmpty()) {
            return null;
        }
        EnvResource base = parseMeta(env, meta.get(0), null, template);
        if (base == null) {
            return null;
        }
        List<String> envLines = runLocal("docker", "inspect", "--format",
                "{{range .Config.Env}}{{println .}}{{end}}", name);
        return withCredentials(base, envLines);
    }

    // ---------------------------------------------------------------- 远程

    private EnvResource inspectRemote(String env, DeployServer server, String secret, String name,
                                      EnvTemplate template) {
        String meta = sshRun(server, secret, "docker inspect --format "
                + "'{{.Name}}|{{.Config.Image}}|{{.State.Running}}|"
                + "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}|"
                + "{{range $p,$conf := .NetworkSettings.Ports}}{{$p}}={{range $conf}}{{.HostPort}}{{end}};{{end}}' "
                + name);
        EnvResource base = parseMeta(env, meta.trim(), server, template);
        if (base == null) {
            return null;
        }
        String envText = sshRun(server, secret,
                "docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' " + name);
        return withCredentials(base, List.of(envText.split("\\R")));
    }

    // ---------------------------------------------------------------- 解析

    private EnvResource parseMeta(String env, String line, DeployServer server, EnvTemplate template) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.trim().split("\\|", -1);
        if (parts.length < 3) {
            return null;
        }
        String container = parts[0].replaceFirst("^/", "");
        String image = parts[1].toLowerCase(Locale.ROOT);
        boolean running = Boolean.parseBoolean(parts[2]);
        String network = parts.length > 3 ? parts[3].trim() : "";
        String kind = kindOf(image);
        if (kind == null) {
            return null;
        }
        int containerPort = defaultPort(kind);
        Integer mapped = parts.length > 4 ? mappedPort(parts[4], containerPort) : null;
        // 登记值：host=对外主机（本机 127.0.0.1 / 服务器地址），port=发布端口，containerPort=容器内端口。
        String host = server == null ? "127.0.0.1" : server.host();
        EnvResource raw = new EnvResource(null, env, kind, container, host,
                mapped == null ? 0 : mapped, containerPort, container,
                null, null, null, network.isBlank() ? null : network.split("\\s+")[0],
                server == null ? null : server.id(),
                server == null ? "LOCAL_DOCKER" : "REMOTE_DOCKER",
                running, 0, null);
        // 这里保留"登记值"（发布端口 / 容器内端口 / 容器名），
        // 按运行形态寻址在展示与注入时统一做（EnvAddressing），切环境不用重新发现。
        return raw;
    }

    /** 解析 docker inspect 的端口映射字段：{@code 3306/tcp=13306;}。 */
    private static Integer mappedPort(String raw, int containerPort) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String entry : raw.split(";")) {
            String[] kv = entry.trim().split("=", -1);
            if (kv.length != 2 || !kv[0].startsWith(containerPort + "/")) {
                continue;
            }
            try {
                return Integer.parseInt(kv[1].trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private EnvResource withCredentials(EnvResource base, List<String> envLines) {
        Map<String, String> env = new HashMap<>();
        for (String line : envLines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        String database = null;
        String username = null;
        String password = null;
        switch (base.kind()) {
            case "mysql", "mariadb" -> {
                database = env.get("MYSQL_DATABASE");
                username = env.getOrDefault("MYSQL_USER", "root");
                password = env.getOrDefault("MYSQL_ROOT_PASSWORD", env.get("MYSQL_PASSWORD"));
            }
            case "redis" -> password = env.get("REDIS_PASSWORD");
            case "rabbitmq" -> {
                username = env.getOrDefault("RABBITMQ_DEFAULT_USER", "guest");
                password = env.getOrDefault("RABBITMQ_DEFAULT_PASS", "guest");
                database = env.getOrDefault("RABBITMQ_DEFAULT_VHOST", "/");
            }
            case "minio" -> {
                username = env.getOrDefault("MINIO_ROOT_USER", env.get("MINIO_ACCESS_KEY"));
                password = env.getOrDefault("MINIO_ROOT_PASSWORD", env.get("MINIO_SECRET_KEY"));
            }
            case "postgres" -> {
                database = env.getOrDefault("POSTGRES_DB", "postgres");
                username = env.getOrDefault("POSTGRES_USER", "postgres");
                password = env.get("POSTGRES_PASSWORD");
            }
            case "mongo" -> {
                database = env.get("MONGO_INITDB_DATABASE");
                username = env.getOrDefault("MONGO_INITDB_ROOT_USERNAME", "root");
                password = env.get("MONGO_INITDB_ROOT_PASSWORD");
            }
            default -> {
                // 未知类型保留原样
            }
        }
        return new EnvResource(base.id(), base.env(), base.kind(), base.name(), base.host(),
                base.port(), base.containerPort(), base.internalHost(), database, username,
                password, base.network(), base.serverId(),
                base.source(), base.running(), base.lastCheckAt(), base.lastError());
    }

    private static String kindOf(String image) {
        if (image.contains("mysql")) return "mysql";
        if (image.contains("mariadb")) return "mariadb";
        if (image.contains("redis")) return "redis";
        if (image.contains("rabbitmq")) return "rabbitmq";
        if (image.contains("minio")) return "minio";
        if (image.contains("postgres")) return "postgres";
        if (image.contains("mongo")) return "mongo";
        return null;
    }

    private static int defaultPort(String kind) {
        return switch (kind) {
            case "mysql", "mariadb" -> 3306;
            case "redis" -> 6379;
            case "rabbitmq" -> 5672;
            case "minio" -> 9000;
            case "postgres" -> 5432;
            case "mongo" -> 27017;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- 命令

    private List<String> runLocal(String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalArgumentException("命令超时：" + String.join(" ", argv));
            }
            if (p.exitValue() != 0) {
                throw new IllegalArgumentException("Docker 不可用或命令失败：" + out.trim());
            }
            return List.of(out.split("\\R"));
        } catch (IOException e) {
            throw new IllegalArgumentException("无法执行 docker：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发现被中断");
        }
    }

    private String sshRun(DeployServer server, String secret, String command) {
        SshAccess.Result result = ssh.run(server, secret, command, SSH_TIMEOUT_MS);
        if (!result.ok()) {
            throw new IllegalArgumentException("远程发现失败：" + result.error());
        }
        return result.output();
    }
}
