package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.rongx.agent.domain.runtime.RuntimeInspector;

/**
 * 应用容器只读巡检：回答"应用起没起来、健康检查过没过、日志里报了什么"。
 *
 * <p>只执行 {@code docker ps / docker inspect / docker logs}，不启停、不修改任何容器；
 * 所有输出经 {@link ProcessExecutor#redact} 脱敏后才返回给 Agent。
 *
 * <p>定位方式：平台部署的容器都带 {@code agent.project} 标签（单容器发布与多服务发布都有），
 * 因此先按标签列出，再在 Java 侧按 {@code agent.env} / 容器名匹配环境——
 * 不依赖容器命名规则的历史版本（{@code agent-deploy-<project>-<env>} 与
 * {@code <namespace>-<project>-<service>} 并存）。
 */
public class AppRuntimeInspector implements RuntimeInspector {

    private static final Path CWD = Path.of(".");
    private static final long TIMEOUT_MS = 20_000;
    private static final int MAX_LOG_LINES = 2_000;

    private static final Pattern PORT_ENTRY = Pattern.compile("\"(\\d+/[a-z]+)\":\\[(.*?)]");
    private static final Pattern HOST_PORT =
            Pattern.compile("\"HostIp\":\"([^\"]*)\",\"HostPort\":\"([^\"]*)\"");

    /**
     * 列出某项目某环境的应用容器（不含中间件：中间件没有 {@code agent.project} 标签）。
     *
     * @throws IllegalStateException docker 不可用（未安装 / 守护进程未启动）
     */
    public List<ContainerStatus> containers(String projectId, String env) {
        Cmd list = docker(List.of("docker", "ps", "-a",
                "--filter", "label=agent.project", "--format", "{{.Names}}"));
        if (list.exitCode() != 0 && list.output().isBlank()) {
            throw new IllegalStateException("docker 不可用：" + firstLine(list.output()));
        }
        List<ContainerStatus> result = new ArrayList<>();
        for (String name : list.output().split("\\R")) {
            String container = name.trim();
            if (container.isEmpty()) {
                continue;
            }
            ContainerStatus status = inspect(container);
            if (status == null || !projectMatches(status, projectId)) {
                continue;
            }
            if (belongsToEnv(status, projectId, env)) {
                result.add(status);
            }
        }
        return result;
    }

    /**
     * 列出映射了某宿主端口的运行中容器（只读）。
     *
     * <p>只查 {@code docker ps}（运行中），不区分应用容器与中间件——端口排障需要的是"谁占着"，
     * 中间件容器没有 {@code agent.project} 标签，因此这里不做项目过滤。
     *
     * @throws IllegalStateException docker 不可用
     */
    public List<PortOwner> portOwners(int hostPort) {
        Cmd list = docker(List.of("docker", "ps", "--format", "{{.Names}}|{{.Ports}}"));
        if (list.exitCode() != 0 && list.output().isBlank()) {
            throw new IllegalStateException("docker 不可用：" + firstLine(list.output()));
        }
        String needle = ":" + hostPort + "->";
        List<PortOwner> owners = new ArrayList<>();
        for (String line : list.output().split("\\R")) {
            int bar = line.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            String name = line.substring(0, bar).trim();
            String ports = line.substring(bar + 1);
            if (name.isEmpty() || !ports.contains(needle)) {
                continue;
            }
            ContainerStatus status = inspect(name);
            // 中间件容器没有 agent.service 标签：不要回退成 "app"（会误导成应用容器）
            owners.add(new PortOwner(name,
                    status == null ? null : status.project(),
                    status == null ? null : status.env(),
                    status == null ? null : status.service(),
                    hostPort));
        }
        return owners;
    }

    /**
     * 读取容器日志时间窗（原始行，grep 过滤由调用方统一做）。
     *
     * @param container    容器名
     * @param sinceMinutes 时间窗（分钟）
     */
    public List<String> logs(String container, int sinceMinutes) {
        Cmd cmd = docker(List.of("docker", "logs", "--since", sinceMinutes + "m",
                "--timestamps", "--tail", String.valueOf(MAX_LOG_LINES), container));
        List<String> lines = new ArrayList<>();
        for (String line : cmd.output().split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (cmd.exitCode() != 0 && lines.isEmpty()) {
            throw new IllegalStateException("docker logs 失败：" + firstLine(cmd.output()));
        }
        return lines;
    }

    // ---------------------------------------------------------------- 内部

    private ContainerStatus inspect(String container) {
        // 标签用反引号取键名：Windows 上 java 的 ProcessBuilder 会吃掉参数里的双引号，
        // 写成 "agent.env" 会让 docker 模板解析成函数调用（function "agent" not defined）。
        String template = "{{.Name}}|{{.State.Status}}|{{.State.Running}}|{{.RestartCount}}"
                + "|{{.State.StartedAt}}|{{.Config.Image}}|{{.Image}}"
                + "|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}"
                + "|{{.State.ExitCode}}|{{json .NetworkSettings.Ports}}"
                + "|{{index .Config.Labels `agent.env`}}"
                + "|{{index .Config.Labels `agent.service`}}"
                + "|{{index .Config.Labels `agent.run`}}"
                + "|{{index .Config.Labels `agent.sha`}}"
                + "|{{index .Config.Labels `com.docker.compose.project`}}"
                + "|{{index .Config.Labels `agent.project`}}";
        Cmd cmd = docker(List.of("docker", "inspect", "--format", template, container));
        if (cmd.exitCode() != 0 || cmd.output().isBlank()) {
            return null;
        }
        String[] parts = cmd.output().strip().split("\\|", -1);
        if (parts.length < 16) {
            return null;
        }
        String name = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        return new ContainerStatus(
                name,
                value(parts[15]),
                value(parts[10]),
                value(parts[11]),
                parts[1],
                Boolean.parseBoolean(parts[2]),
                parseInt(parts[3]),
                value(parts[4]),
                value(parts[5]),
                value(parts[6]),
                value(parts[7]),
                parseInt(parts[8]),
                ports(parts[9]),
                value(parts[12]),
                value(parts[13]),
                value(parts[14]));
    }

    private static boolean projectMatches(ContainerStatus status, String projectId) {
        if (status.project() != null && !status.project().isBlank()) {
            return status.project().equalsIgnoreCase(projectId);
        }
        // 旧容器可能没有 agent.project 标签：退回容器名匹配（部署器对项目名做同样的小写化）。
        String name = status.name().toLowerCase(Locale.ROOT);
        String slug = slug(projectId);
        return name.contains("-" + slug + "-") || name.equals("agent-deploy-" + slug);
    }

    private static boolean belongsToEnv(ContainerStatus status, String projectId, String env) {
        if (status.env() != null && !status.env().isBlank()) {
            return status.env().equalsIgnoreCase(env);
        }
        String name = status.name().toLowerCase(Locale.ROOT);
        String slug = slug(projectId);
        if (name.equals("agent-deploy-" + slug + "-" + env)) {
            return true;
        }
        if (name.equals("agent-deploy-" + slug)) {
            return "dev".equals(env);
        }
        // 多服务容器：<namespace>-<project>-<service>，namespace 默认 agent-<env>
        return name.startsWith("agent-" + env + "-" + slug + "-");
    }

    private static String ports(String portsJson) {
        if (portsJson == null || portsJson.isBlank() || "null".equals(portsJson)) {
            return "无端口映射";
        }
        StringBuilder sb = new StringBuilder();
        Matcher entry = PORT_ENTRY.matcher(portsJson);
        while (entry.find()) {
            String containerPort = entry.group(1);
            Matcher host = HOST_PORT.matcher(entry.group(2));
            if (host.find()) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(containerPort).append(" → ").append(host.group(1))
                        .append(':').append(host.group(2));
            }
        }
        return sb.isEmpty() ? portsJson : sb.toString();
    }

    /** 取值：docker 模板里缺失的标签渲染成 {@code <no value>}。 */
    private static String value(String raw) {
        return raw == null || raw.isBlank() || "<no value>".equals(raw) ? null : raw.trim();
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "无输出";
        }
        return text.split("\\R")[0].trim();
    }

    private static String slug(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        return value.isEmpty() ? "unknown" : value;
    }

    private Cmd docker(List<String> argv) {
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome outcome =
                new ProcessExecutor().exec(CWD, argv, TIMEOUT_MS, sink, ProcessExecutor.NEVER);
        return new Cmd(outcome.exitCode(), ProcessExecutor.redact(sink.toString()));
    }

    private record Cmd(int exitCode, String output) {
    }
}
