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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.executor.ProcessExecutor;

/**
 * 只读诊断工具：让 Agent 在不碰写操作的前提下"看到现场"。
 *
 * <p>全部命令都经 {@link ProcessExecutor}：不经 shell、有超时、输出脱敏、只回尾部。
 * 这些工具在 dev/test/prod 都常开（readOnly=true），并且不会改变任何运行态。
 *
 * <p>与 {@link RuntimeTools} 的分工：RuntimeTools 看"应用"，这里看"宿主/容器/端口/磁盘"。
 */
@Component
public class DiagTools {

    private static final Pattern CONTAINER = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");
    private static final int MAX_OUTPUT_CHARS = 4000;

    private final ProcessExecutor executor = new ProcessExecutor();
    private final Path workspace;
    private final ToolAuditRecorder audit;

    public DiagTools(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                     ToolAuditRecorder audit) {
        this.workspace = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.audit = audit;
    }

    @Tool(name = "docker_ps",
            description = "列出本机 Docker 容器（只读）：名称、状态、镜像、端口。排障先看这里。",
            readOnly = true, concurrencySafe = true)
    public String dockerPs(ProjectContext ctx) {
        return audit.call(ctx, "docker_ps", Map.of(),
                () -> run(List.of("docker", "ps", "-a",
                        "--format", "table {{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Ports}}")));
    }

    @Tool(name = "docker_logs",
            description = "读取某个容器的最近日志（只读，默认 100 行，最多 500 行）。",
            readOnly = true, concurrencySafe = true)
    public String dockerLogs(
            @ToolParam(name = "container", description = "容器名（只允许字母数字与 _.-，不接受 shell 片段）")
            String container,
            @ToolParam(name = "lines", description = "尾部行数，默认 100，最多 500", required = false)
            Integer lines,
            ProjectContext ctx) {
        return audit.call(ctx, "docker_logs",
                Map.of("container", container == null ? "" : container,
                        "lines", String.valueOf(lines)),
                () -> {
                    if (container == null || !CONTAINER.matcher(container).matches()) {
                        return "err: 非法容器名";
                    }
                    int tail = lines == null ? 100 : Math.max(1, Math.min(500, lines));
                    return run(List.of("docker", "logs", "--tail", String.valueOf(tail), container));
                });
    }

    @Tool(name = "port_check",
            description = "检查宿主端口是否被占用，并尽量给出占用进程（只读）。部署报 E-503 时先用它。",
            readOnly = true, concurrencySafe = true)
    public String portCheck(
            @ToolParam(name = "port", description = "宿主端口，1-65535") Integer port,
            ProjectContext ctx) {
        return audit.call(ctx, "port_check", Map.of("port", String.valueOf(port)), () -> {
            if (port == null || !PORT.matcher(String.valueOf(port)).matches()
                    || port < 1 || port > 65535) {
                return "err: 非法端口";
            }
            String ss = run(List.of("ss", "-ltnp"));
            if (!ss.startsWith("err:") && ss.contains(":" + port)) {
                return "占用: 是\n" + keepLines(ss, ":" + port, 10);
            }
            String lsof = run(List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN"));
            if (!lsof.startsWith("err:") && !lsof.isBlank()) {
                return "占用: 是\n" + lsof;
            }
            return "占用: 否（ss/lsof 未发现监听）\n--- ss ---\n" + ss;
        });
    }

    @Tool(name = "http_probe",
            description = "对 URL 发起一次 GET 探活（只读）：返回状态码、耗时与响应体前 500 字。",
            readOnly = true, concurrencySafe = true)
    public String httpProbe(
            @ToolParam(name = "url", description = "http/https URL，例如 http://127.0.0.1:8080/healthz")
            String url,
            ProjectContext ctx) {
        return audit.call(ctx, "http_probe", Map.of("url", url == null ? "" : url), () -> {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                return "err: 只支持 http/https URL";
            }
            long start = System.nanoTime();
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long ms = (System.nanoTime() - start) / 1_000_000;
                String body = response.body() == null ? "" : response.body();
                if (body.length() > 500) {
                    body = body.substring(0, 500) + "…";
                }
                return "status: " + response.statusCode() + "\nurl: " + url
                        + "\ndurationMs: " + ms + "\nbody:\n" + ProcessExecutor.redact(body);
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return "err: 探活失败 " + url + "：" + e.getMessage();
            }
        });
    }

    @Tool(name = "proc_list",
            description = "列出宿主进程（只读），可按关键字过滤，例如 java / node / nginx。",
            readOnly = true, concurrencySafe = true)
    public String procList(
            @ToolParam(name = "filter", description = "可选关键字（只做字符串包含匹配）", required = false)
            String filter,
            ProjectContext ctx) {
        return audit.call(ctx, "proc_list", Map.of("filter", filter == null ? "" : filter), () -> {
            String out = run(List.of("ps", "-eo", "pid,comm,%cpu,%mem,args"));
            if (filter == null || filter.isBlank()) {
                return out;
            }
            String wanted = filter.trim().toLowerCase(Locale.ROOT);
            List<String> hits = new ArrayList<>();
            for (String line : out.split("\\R")) {
                if (line.toLowerCase(Locale.ROOT).contains(wanted)) {
                    hits.add(line);
                }
            }
            return hits.isEmpty() ? "没有匹配进程：" + filter
                    : String.join("\n", hits.subList(0, Math.min(50, hits.size())));
        });
    }

    @Tool(name = "log_tail",
            description = "读取工作区内某个日志文件的尾部（只读，默认 200 行）；越界路径会被拒绝。",
            readOnly = true, concurrencySafe = true)
    public String logTail(
            @ToolParam(name = "path", description = "相对工作区根目录的路径，例如 _platform/deploy/p-e/app.log")
            String path,
            @ToolParam(name = "lines", description = "尾部行数，默认 200，最多 1000", required = false)
            Integer lines,
            ProjectContext ctx) {
        return audit.call(ctx, "log_tail",
                Map.of("path", path == null ? "" : path, "lines", String.valueOf(lines)), () -> {
                    if (path == null || path.isBlank() || path.contains("..")) {
                        return "err: 非法路径";
                    }
                    Path file = workspace.resolve(path).normalize();
                    if (!file.startsWith(workspace)) {
                        return "err: 路径越界";
                    }
                    if (!Files.isRegularFile(file)) {
                        return "err: 文件不存在：" + path;
                    }
                    try {
                        List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                        int n = lines == null ? 200 : Math.max(1, Math.min(1000, lines));
                        int from = Math.max(0, all.size() - n);
                        String text = String.join("\n", all.subList(from, all.size()));
                        if (text.length() > MAX_OUTPUT_CHARS) {
                            text = "…" + text.substring(text.length() - MAX_OUTPUT_CHARS);
                        }
                        return ProcessExecutor.redact(text);
                    } catch (IOException e) {
                        return "err: 读取失败 " + e.getMessage();
                    }
                });
    }

    @Tool(name = "disk_usage",
            description = "查看宿主磁盘使用（只读），用于判断构建/镜像拉取是否因空间不足失败。",
            readOnly = true, concurrencySafe = true)
    public String diskUsage(ProjectContext ctx) {
        return audit.call(ctx, "disk_usage", Map.of(), () -> run(List.of("df", "-h")));
    }

    private String run(List<String> argv) {
        StringBuilder sink = new StringBuilder();
        BooleanSupplier never = () -> false;
        ProcessExecutor.StepOutcome outcome =
                executor.exec(workspace, argv, 10_000, sink, never);
        String out = ProcessExecutor.tail(sink);
        if (outcome.exitCode() == 0) {
            return out.isBlank() ? "（无输出）" : out;
        }
        return "err: " + (outcome.summary() == null ? "exit " + outcome.exitCode() : outcome.summary())
                + (out.isBlank() ? "" : "\n---\n" + out);
    }

    private static String keepLines(String text, String needle, int limit) {
        List<String> hits = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.contains(needle)) {
                hits.add(line);
                if (hits.size() >= limit) {
                    break;
                }
            }
        }
        return String.join("\n", hits);
    }
}
