package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.env.ServiceRuntime;
import top.rongx.agent.domain.net.PortProbe;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.runtime.RuntimeInspector;

/**
 * 应用运行时工具：只回答"应用有没有在跑、日志里报了什么"，不启停任何东西。
 *
 * <p>与 {@link EnvTools} 的分工：{@code env_summary} 说的是"部署时会注入什么 +
 * 中间件健康"；这里说的是"应用容器/进程本身的运行时事实"。两者不能互相替代——
 * 中间件全绿但应用容器已经退出，是部署后最常见的一种失败。
 *
 * <p>输出全部经过脱敏，且不返回环境变量的值。
 */
@Component
public class RuntimeTools {

    /** 单次日志返回上限：用户抱怨过 6000 字符里大半是构建产物。 */
    private static final int MAX_LOG_CHARS = 4000;
    private static final int MAX_LOG_LINES_OUT = 150;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final EnvironmentStore envStore;
    private final ServiceRuntime services;
    private final RuntimeInspector inspector;
    /** HOST 部署状态根：{@code <workspace>/_platform/deploy/<project>-<env>/app.log}。 */
    private final Path hostDeployRoot;

    public RuntimeTools(EnvironmentStore envStore, ServiceRuntime services,
                        RuntimeInspector inspector,
                        @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.envStore = envStore;
        this.services = services;
        this.inspector = inspector;
        this.hostDeployRoot = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("deploy");
    }

    @Tool(name = "env_runtime_status",
            description = """
                    查看某环境「应用」（不是中间件）的运行时事实（只读）：容器/进程是否在跑、
                    健康检查结果、重启次数、镜像与 digest、启动时间、端口映射。
                    dev 返回平台托管的宿主机进程状态并做 HTTP 探活；test/prod 返回应用容器状态。
                    用于回答"应用启动了吗 / 为什么起不来"，不要凭印象猜。""",
            readOnly = true, concurrencySafe = true)
    public String runtimeStatus(@ToolParam(name = "env", description = "环境：dev / test / prod")
                                String env, ProjectContext ctx) {
        String target = normalize(env);
        EffectiveEnvironment effective;
        try {
            effective = envStore.effective(ctx.projectId(), target);
        } catch (RuntimeException e) {
            return "err: 读取项目环境失败：" + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(ctx.projectId()).append('\n')
                .append("env: ").append(target).append('\n');

        boolean any = false;
        if ("dev".equals(target)) {
            any = appendHostProcesses(sb, ctx, target, effective);
        }
        String dockerError = null;
        List<RuntimeInspector.ContainerStatus> containers = List.of();
        try {
            containers = inspector.containers(ctx.projectId(), target);
        } catch (IllegalStateException e) {
            dockerError = e.getMessage();
        }
        if (dockerError != null) {
            sb.append("docker: 不可用（").append(dockerError).append("）\n");
        } else if (!containers.isEmpty()) {
            any = true;
            long running = containers.stream().filter(RuntimeInspector.ContainerStatus::running).count();
            sb.append("runtime: docker\n")
                    .append("containers: ").append(running).append('/').append(containers.size())
                    .append(" running\n");
            for (RuntimeInspector.ContainerStatus c : containers) {
                sb.append("container: ").append(c.name()).append('\n')
                        .append("  service: ").append(RuntimeInspector.serviceName(c)).append('\n')
                        .append("  status: ").append(c.status())
                        .append(c.running() ? "" : "（exit " + c.exitCode() + "）").append('\n')
                        .append("  health: ").append(c.health() == null ? "none" : c.health()).append('\n')
                        .append("  restarts: ").append(c.restartCount()).append('\n')
                        .append("  startedAt: ").append(c.startedAt() == null ? "未知" : c.startedAt())
                        .append('\n')
                        .append("  image: ").append(c.image() == null ? "未知" : c.image()).append('\n')
                        .append("  imageId: ").append(c.imageId() == null ? "未知" : c.imageId()).append('\n')
                        .append("  ports: ").append(c.ports()).append('\n');
                if (c.runId() != null) {
                    sb.append("  run: ").append(c.runId()).append('\n');
                }
                if (c.sha() != null) {
                    sb.append("  sha: ").append(c.sha()).append('\n');
                }
            }
        }

        if (!any) {
            sb.append("result: 未找到该环境运行中的应用")
                    .append("dev".equals(target) ? "（容器与平台托管进程都没有）" : "（可能未部署，或部署在远程服务器）")
                    .append('\n');
        }
        sb.append("evidence: project:").append(ctx.projectId()).append("/env:").append(target);
        if (!containers.isEmpty()) {
            sb.append(", docker:").append(containers.get(0).name());
        }
        return sb.toString();
    }

    @Tool(name = "app_logs",
            description = """
                    读取某环境应用日志（只读，已脱敏）。test/prod 优先读 docker logs 按 since_minutes
                    时间窗过滤；HOST 目标（本机 jar）读平台部署状态目录里持久化的 app.log；
                    dev 读取平台托管的进程日志尾部（无逐行时间戳时按行过滤）。
                    grep 为大小写不敏感的子串过滤。部署后"应用起没起来、报什么错"先用它，不要猜。""",
            readOnly = true, concurrencySafe = true)
    public String appLogs(@ToolParam(name = "env", description = "环境：dev / test / prod") String env,
                          @ToolParam(name = "service",
                                  description = "服务：backend / frontend / app；空或 all = 该环境全部应用日志")
                          String service,
                          @ToolParam(name = "since_minutes",
                                  description = "时间窗（分钟），默认 30，范围 1-1440")
                          Integer sinceMinutes,
                          @ToolParam(name = "grep", description = "可选：日志行子串过滤（大小写不敏感）")
                          String grep,
                          ProjectContext ctx) {
        String target = normalize(env);
        int since = sinceMinutes == null ? 30 : Math.max(1, Math.min(1440, sinceMinutes));
        String needle = grep == null ? "" : grep.trim();
        String wanted = service == null || service.isBlank() ? "all" : service.trim();
        String window = OffsetDateTime.now(ZoneId.systemDefault())
                .minusMinutes(since).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(ctx.projectId()).append('\n')
                .append("env: ").append(target).append('\n')
                .append("service: ").append(wanted).append('\n')
                .append("window: since ").append(since).append("m（").append(window).append(" → now）\n");
        if (!needle.isEmpty()) {
            sb.append("grep: ").append(needle).append('\n');
        }

        List<String> names = List.of(ServiceLease.BACKEND, ServiceLease.FRONTEND);
        boolean wantedHost = "all".equalsIgnoreCase(wanted)
                || names.contains(wanted.toLowerCase(Locale.ROOT));
        boolean wrote = false;

        // dev：平台托管的宿主机进程日志（没有逐行时间戳，只能按行过滤）
        if ("dev".equals(target)) {
            List<String> targets = "all".equalsIgnoreCase(wanted)
                    ? names : wantedHost ? List.of(wanted.toLowerCase(Locale.ROOT)) : List.of();
            for (String s : targets) {
                List<String> lines = services.logTail(ctx.projectId(), s, 400);
                wrote |= appendLogSection(sb, "host-process", null, s, lines,
                        "dev 进程日志无逐行时间戳，按最后 400 行过滤", needle);
            }
        }

        // docker：test/prod（dev 也可能有遗留容器，一并查；没有就跳过）
        String dockerError = null;
        List<RuntimeInspector.ContainerStatus> containers = List.of();
        try {
            containers = inspector.containers(ctx.projectId(), target);
        } catch (IllegalStateException e) {
            dockerError = e.getMessage();
        }
        if (dockerError != null && !wrote) {
            sb.append("docker: 不可用（").append(dockerError).append("）\n");
        }
        for (RuntimeInspector.ContainerStatus c : containers) {
            String serviceName = RuntimeInspector.serviceName(c);
            // 单容器发布一个容器承担全部服务（service=app）：按 backend/frontend 查也应命中。
            if (!"all".equalsIgnoreCase(wanted)
                    && !serviceName.equalsIgnoreCase(wanted)
                    && !c.name().equalsIgnoreCase(wanted)
                    && !"app".equalsIgnoreCase(serviceName)) {
                continue;
            }
            List<String> lines;
            try {
                lines = inspector.logs(c.name(), since);
            } catch (IllegalStateException e) {
                sb.append("container: ").append(c.name()).append('\n')
                        .append("  err: ").append(e.getMessage()).append('\n');
                wrote = true;
                continue;
            }
            wrote |= appendLogSection(sb, "docker", c.name(), serviceName, lines,
                    "时间窗 + 逐行时间戳（读取 " + lines.size() + " 行）", needle);
        }

        // HOST 部署（本机 jar）：日志写在平台数据目录，跨运行保留；
        // 与 docker 日志并列输出，避免"部署完日志没了"。
        boolean wantsBackend = "all".equalsIgnoreCase(wanted)
                || "backend".equalsIgnoreCase(wanted) || "app".equalsIgnoreCase(wanted);
        if (!"dev".equals(target) && wantsBackend) {
            Path appLog = hostDeployRoot.resolve(ctx.projectId() + "-" + target).resolve("app.log");
            if (Files.isRegularFile(appLog)) {
                List<String> lines = tail(appLog, 400);
                wrote |= appendLogSection(sb, "host-deploy", null, ServiceLease.BACKEND, lines,
                        "HOST 部署进程日志 " + appLog + "（最近 " + lines.size()
                                + " 行，跨多次部署追加）", needle);
            }
        }

        if (!wrote) {
            sb.append("result: 没有日志可读（该环境没有匹配的应用")
                    .append("all".equalsIgnoreCase(wanted) ? "" : "服务 " + wanted)
                    .append("，或平台尚未启动它）\n");
        }
        sb.append("evidence: project:").append(ctx.projectId()).append("/env:").append(target);
        return sb.toString();
    }

    @Tool(name = "port_check",
            description = """
                    检查某 host:port 是否可连接，并给出占用者（只读）：运行中的 docker 容器、
                    本环境登记的中间件、平台 dev 服务租约。用于回答"端口被谁占了/应用该连的端口通不通"。
                    只有本机地址（localhost / 127.0.0.1 / 0.0.0.0）才做归属判定；远程主机只做 TCP 连通性。""",
            readOnly = true, concurrencySafe = true)
    public String portCheck(@ToolParam(name = "host", description = "主机，默认 127.0.0.1")
                            String host,
                            @ToolParam(name = "port", description = "端口（1-65535）")
                            Integer port,
                            @ToolParam(name = "env", required = false,
                                    description = "要对照的环境：dev / test / prod；默认会话环境")
                            String env,
                            ProjectContext ctx) {
        String target = normalize(env == null || env.isBlank() ? ctx.env() : env);
        String hostValue = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        if (port == null || port <= 0 || port > 65535) {
            return "err: port 必须是 1-65535";
        }
        boolean loopback = isLoopback(hostValue);
        boolean open = PortProbe.open(hostValue, port, 1500);
        StringBuilder sb = new StringBuilder();
        sb.append("host: ").append(hostValue).append('\n')
                .append("port: ").append(port).append('\n')
                .append("tcp: ").append(open ? "open（可连接）" : "closed（连接被拒绝/超时）")
                .append('\n');

        if (!loopback) {
            sb.append("attribution: 跳过（远程主机不做本机 docker/租约归属判定）\n")
                    .append("evidence: host:").append(hostValue).append(':').append(port);
            return sb.toString();
        }

        String dockerError = null;
        List<RuntimeInspector.PortOwner> owners = List.of();
        try {
            owners = inspector.portOwners(port);
        } catch (IllegalStateException e) {
            dockerError = e.getMessage();
        }
        if (dockerError != null) {
            sb.append("docker: 不可用（").append(dockerError).append("）\n");
        } else if (owners.isEmpty()) {
            sb.append("docker: 没有运行中的容器映射该端口\n");
        } else {
            for (RuntimeInspector.PortOwner owner : owners) {
                sb.append("docker: ").append(owner.container());
                if (owner.project() != null) {
                    sb.append("  project=").append(owner.project());
                }
                if (owner.env() != null) {
                    sb.append("  env=").append(owner.env());
                }
                if (owner.service() != null) {
                    sb.append("  service=").append(owner.service());
                }
                sb.append('\n');
            }
        }

        List<EnvResource> middleware = envStore.resources(target).stream()
                .filter(r -> r.port() == port && sameHost(r.host(), hostValue))
                .toList();
        if (!middleware.isEmpty()) {
            middleware.forEach(r -> sb.append("middleware: ").append(r.name()).append("  ")
                    .append(r.kind()).append("  ").append(r.status()).append('\n'));
        }

        ServiceLease lease = envStore.allServiceLeases(target).stream()
                .filter(l -> l.hostPort() == port)
                .findFirst().orElse(null);
        if (lease != null) {
            sb.append("lease: ").append(lease.service())
                    .append("  project=").append(lease.projectId())
                    .append("  env=").append(lease.env())
                    .append("  status=").append(lease.status());
            if (lease.pid() > 0) {
                sb.append("  pid=").append(lease.pid());
            }
            if (!lease.projectId().equalsIgnoreCase(ctx.projectId())) {
                sb.append("  （被其它项目的本地服务占用）");
            }
            sb.append('\n');
        }
        sb.append("evidence: project:").append(ctx.projectId()).append("/env:").append(target);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 内部

    private static boolean isLoopback(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value) || "127.0.0.1".equals(value)
                || "0.0.0.0".equals(value) || "::1".equals(value) || "[::1]".equals(value);
    }

    private static boolean sameHost(String registered, String host) {
        if (registered == null || host == null) {
            return false;
        }
        String a = registered.trim().toLowerCase(Locale.ROOT);
        String b = host.trim().toLowerCase(Locale.ROOT);
        return a.equals(b)
                || ("localhost".equals(a) && "127.0.0.1".equals(b))
                || ("127.0.0.1".equals(a) && "localhost".equals(b));
    }

    private boolean appendHostProcesses(StringBuilder sb, ProjectContext ctx, String env,
                                        EffectiveEnvironment effective) {
        List<ServiceLease> leases = envStore.serviceLeases(ctx.projectId(), env);
        if (leases.isEmpty()) {
            return false;
        }
        sb.append("runtime: host-process\n");
        for (ServiceLease lease : leases) {
            String live = services.status(ctx.projectId(), env, lease.service());
            sb.append("service: ").append(lease.service()).append('\n')
                    .append("  status: ").append(live).append('\n')
                    .append("  port: ").append(lease.hostPort()).append('\n')
                    .append("  pid: ").append(lease.pid() > 0 ? lease.pid() : "未知").append('\n');
            if (ServiceLease.BACKEND.equals(lease.service())) {
                sb.append("  health: ").append(probe(lease.hostPort(), effective.healthPath()))
                        .append('\n');
            }
        }
        return true;
    }

    /** 读取文件末尾若干行（文件不存在或不可读时返回空）。 */
    private static List<String> tail(Path file, int maxLines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return all.size() <= maxLines ? all : all.subList(all.size() - maxLines, all.size());
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private boolean appendLogSection(StringBuilder sb, String sourceKind, String container,
                                     String service, List<String> allLines, String note,
                                     String needle) {
        sb.append("source: ").append(sourceKind).append('\n');
        if (container != null) {
            sb.append("container: ").append(container).append('\n');
        }
        sb.append("service: ").append(service).append('\n')
                .append("window: ").append(note).append('\n');
        if (allLines.isEmpty()) {
            sb.append("log: （时间窗内没有日志行；可调大 since_minutes 再试）\n");
            return true;
        }
        List<String> matched = new ArrayList<>();
        for (String line : allLines) {
            if (needle.isEmpty() || line.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                matched.add(line);
            }
        }
        if (matched.isEmpty()) {
            sb.append("log: （读取 ").append(allLines.size()).append(" 行，没有匹配 ").append(needle)
                    .append(" 的行）\n");
            return true;
        }
        List<String> out = matched.size() > MAX_LOG_LINES_OUT
                ? matched.subList(matched.size() - MAX_LOG_LINES_OUT, matched.size())
                : matched;
        String text = String.join("\n", out);
        if (text.length() > MAX_LOG_CHARS) {
            text = "…（已截断更早的 " + (text.length() - MAX_LOG_CHARS)
                    + " 字符，仅保留最近 " + MAX_LOG_CHARS + " 字符）\n"
                    + text.substring(text.length() - MAX_LOG_CHARS);
        }
        sb.append("log:\n---\n")
                .append(indent(text))
                .append("\n---\n");
        return true;
    }

    /** dev 后端 HTTP 探活：只回状态码/失败原因，不读正文。 */
    private static String probe(int port, String healthPath) {
        if (port <= 0) {
            return "端口未知，未探活";
        }
        String path = normalizePath(healthPath);
        int code = httpStatus(port, path);
        // 环境模板默认 /healthz 在 Spring Boot 里不存在：回退 actuator，避免把活着的应用报成未就绪
        if (code != 200 && !"/actuator/health/liveness".equals(path)) {
            int fallback = httpStatus(port, "/actuator/health/liveness");
            if (fallback > 0) {
                return "GET http://127.0.0.1:" + port + "/actuator/health/liveness → HTTP " + fallback
                        + "（模板路径 " + path + " → " + (code > 0 ? code : "连接失败") + "）";
            }
        }
        return "GET http://127.0.0.1:" + port + path + " → "
                + (code > 0 ? "HTTP " + code : "连接失败");
    }

    private static int httpStatus(int port, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String normalizePath(String healthPath) {
        String path = healthPath == null || healthPath.isBlank() ? "/" : healthPath;
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String indent(String text) {
        return "  " + text.replace("\n", "\n  ");
    }

    private static String normalize(String env) {
        String value = env == null || env.isBlank() ? "test" : env.trim().toLowerCase(Locale.ROOT);
        return List.of("dev", "test", "prod").contains(value) ? value : "test";
    }
}
