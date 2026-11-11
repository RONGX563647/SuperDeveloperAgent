package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.port.RepoInspector;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.domain.runtime.RuntimeInspector;
import org.springframework.stereotype.Component;

/**
 * 流水线只读工具（L0）。
 *
 * <p>与 {@link CiCommandTools} 分开的原因：AgentScope 的工具分组是**按对象**注册的
 * （{@code registration().tool(obj).group(name)}），读/写混在一个类里会导致
 * 只读方法被一起锁进未激活的写组。
 */
@Component
public class CiQueryTools {

    private final PipelineEngine engine;
    private final RepoInspector repo;
    private final RecipeStore recipes;
    private final RuntimeInspector inspector;

    public CiQueryTools(PipelineEngine engine, RepoInspector repo, RecipeStore recipes,
                        RuntimeInspector inspector) {
        this.engine = engine;
        this.repo = repo;
        this.recipes = recipes;
        this.inspector = inspector;
    }

    @Tool(name = "ci_list_pipelines",
            description = "列出当前项目可用的流水线。只读。触发构建前必须先调用本工具确认 pipelineId。",
            readOnly = true, concurrencySafe = true)
    public String listPipelines(ProjectContext ctx) {
        List<String> ids = engine.pipelines(ctx.projectId());
        return ids.stream()
                .collect(Collectors.joining("\n",
                        "pipelines(engine=" + engine.id() + "):\n",
                        "\nevidence: engine:" + engine.id()));
    }

    @Tool(name = "ci_status",
            description = """
                    查询一次构建的状态与各阶段结果。只读。用于确认构建是否成功。
                    返回含 terminal 字段：true 表示已结束，无需再次轮询；
                    失败时会给出失败阶段的 errorCode 与摘要，直接据此排查。""",
            readOnly = true, concurrencySafe = true)
    public String status(@ToolParam(name = "run_id", description = "ci_run 返回的 runId") String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (!engine.hasRun(runId)) {
            throw new IllegalArgumentException("run 不存在：" + runId + "（请核对 ci_run 返回的 runId）");
        }
        List<StageResult> stageList = engine.stages(runId);
        StageStatus status = engine.status(runId);
        String failedPart = "";
        if (status == StageStatus.FAILED) {
            failedPart = stageList.stream()
                    .filter(s -> s.status() == StageStatus.FAILED)
                    .map(s -> "- %s: %s %s".formatted(s.stageId(),
                            s.errorCode() == null ? "" : s.errorCode(),
                            s.errorSummary() == null ? "" : s.errorSummary()))
                    .collect(Collectors.joining("\n", "failed:\n", ""));
        }
        return """
                runId: %s
                status: %s
                terminal: %s
                stages: %d
                %sevidence: run:%s""".formatted(runId, status, status.isTerminal(),
                stageList.size(), failedPart, runId);
    }

    @Tool(name = "read_run_log",
            description = """
                    读取一次构建的日志尾部（只读）。用于在 ci_status 显示 FAILED 后定位具体错误。
                    run_id 取 ci_run / deploy_run 的返回值；返回最后约 6000 字符，已脱敏。""",
            readOnly = true, concurrencySafe = true)
    public String readRunLog(@ToolParam(name = "run_id", description = "ci_run / deploy_run 返回的 runId")
                             String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (!engine.hasRun(runId)) {
            throw new IllegalArgumentException("run 不存在：" + runId + "（请核对 ci_run 返回的 runId）");
        }
        String log = engine.logTail(runId);
        if (log == null || log.isBlank()) {
            return "runId: " + runId + "\n(log 为空：运行尚未产出日志，稍后再试)\nevidence: run:" + runId;
        }
        int max = 6000;
        String tail = log.length() > max ? "…（已截断前 " + (log.length() - max) + " 字符）\n"
                + log.substring(log.length() - max) : log;
        return """
                runId: %s
                status: %s
                log:
                ---
                %s
                ---
                evidence: run:%s""".formatted(runId, engine.status(runId), tail, runId);
    }

    @Tool(name = "wait_run",
            description = """
                    等待一次构建到达终态（只读，会阻塞最多 timeout_seconds 秒）。
                    触发后优先用它等待，不要高频轮询 ci_status；超时仍 RUNNING 时可再次调用续等。
                    返回最终状态、阶段数、失败阶段摘要与日志尾部。""",
            readOnly = true, concurrencySafe = true)
    public String waitRun(@ToolParam(name = "run_id", description = "ci_run / deploy_run 返回的 runId")
                          String runId,
                          @ToolParam(name = "timeout_seconds",
                                  description = "最长等待秒数，默认 90，范围 5-180")
                          Integer timeoutSeconds) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        if (!engine.hasRun(runId)) {
            throw new IllegalArgumentException("run 不存在：" + runId + "（请核对 deploy_run 返回的 runId）");
        }
        int timeout = timeoutSeconds == null ? 90 : Math.max(5, Math.min(180, timeoutSeconds));
        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        StageStatus status = engine.status(runId);
        while (!status.isTerminal() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            status = engine.status(runId);
        }
        List<StageResult> stageList = engine.stages(runId);
        String failedPart = "";
        if (status == StageStatus.FAILED) {
            failedPart = stageList.stream()
                    .filter(s -> s.status() == StageStatus.FAILED)
                    .map(s -> "- %s: %s %s".formatted(s.stageId(),
                            s.errorCode() == null ? "" : s.errorCode(),
                            s.errorSummary() == null ? "" : s.errorSummary()))
                    .collect(Collectors.joining("\n", "failed:\n", ""));
        }
        String log = engine.logTail(runId);
        String tail = log == null ? "" : log;
        int max = 2000;
        if (tail.length() > max) {
            tail = "…（已截断前 " + (tail.length() - max) + " 字符）\n" + tail.substring(tail.length() - max);
        }
        return """
                runId: %s
                status: %s
                terminal: %s
                waitedSeconds: %d
                stages: %d
                %slog:
                ---
                %s
                ---
                evidence: run:%s""".formatted(runId, status, status.isTerminal(), timeout,
                stageList.size(), failedPart, tail.isBlank() ? "(暂无日志)" : tail, runId);
    }

    @Tool(name = "ci_history",
            description = """
                    查询当前项目最近的构建历史（最多 20 条）。只读。
                    每条含 env、可读时间（ISO）、耗时、提交与流水线定义来源，
                    用于回答"上次构建是什么时候/成没成功/跑的是哪份定义"。""",
            readOnly = true, concurrencySafe = true)
    public String history(ProjectContext ctx) {
        List<PipelineEngine.RunSummary> items = engine.history().stream()
                .filter(h -> h.projectId().equals(ctx.projectId()))
                .limit(20)
                .toList();
        if (items.isEmpty()) {
            return "当前项目暂无构建历史。evidence: engine:" + engine.id();
        }
        return items.stream()
                .map(this::historyLine)
                .collect(Collectors.joining("\n",
                        "history:\n", "\nevidence: engine:" + engine.id()));
    }

    @Tool(name = "release_trace",
            description = """
                    按提交 sha 串起一条发布链路（只读）：提交 → 构建 → 配方 → 容器/健康检查。
                    缺失的环节会显式列在 missing 里，用于回答"这个提交上线了吗/上线到哪个环境/
                    用的哪个镜像"，不要凭印象猜。sha 可传完整值或至少 4 位前缀。""",
            readOnly = true, concurrencySafe = true)
    public String releaseTrace(ProjectContext ctx,
                               @ToolParam(name = "sha", description = "提交 sha（完整或至少 4 位前缀）")
                               String sha) {
        if (sha == null || sha.isBlank() || sha.trim().length() < 4) {
            return "err: sha 至少 4 位（完整 sha 或前缀）";
        }
        String needle = sha.trim().toLowerCase();
        String projectId = ctx.projectId();
        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(projectId).append('\n')
                .append("sha: ").append(needle).append('\n');

        // 1) 提交
        RepoInspector.Commit commit = null;
        for (RepoInspector.Commit c : repo.commits(projectId, 100, 0)) {
            if (c.sha() != null && c.sha().toLowerCase().startsWith(needle)) {
                commit = c;
                break;
            }
        }
        sb.append("commit: ");
        if (commit == null) {
            sb.append("未在最近 100 条提交里找到（可能是更早的提交，或不在当前分支）\n");
        } else {
            sb.append(commit.sha()).append("  ").append(commit.relativeTime())
                    .append("  ").append(commit.author()).append("  ").append(commit.subject())
                    .append('\n');
        }

        // 2) 构建
        List<PipelineEngine.RunSummary> allBuilds = engine.history().stream()
                .filter(h -> h.projectId().equalsIgnoreCase(projectId))
                .filter(h -> h.gitSha() != null && h.gitSha().toLowerCase().startsWith(needle))
                .sorted(Comparator.comparingLong(PipelineEngine.RunSummary::createdAt))
                .toList();
        int limit = 10;
        List<PipelineEngine.RunSummary> builds = allBuilds.size() > limit
                ? allBuilds.subList(allBuilds.size() - limit, allBuilds.size()) : allBuilds;
        sb.append("builds: ").append(allBuilds.size());
        if (allBuilds.size() > builds.size()) {
            sb.append("（显示最近 ").append(builds.size()).append(" 条）");
        }
        sb.append('\n');
        builds.forEach(h -> sb.append("  ").append(historyLine(h)).append('\n'));

        // 3) 配方
        StringBuilder recipeOut = new StringBuilder();
        int recipeCount = 0;
        for (String env : List.of("dev", "test", "prod")) {
            for (DeployRecipe r : recipes.versions(projectId, env)) {
                if (r.gitSha() == null || !r.gitSha().toLowerCase().startsWith(needle)) {
                    continue;
                }
                recipeCount++;
                recipeOut.append("  - env=").append(env)
                        .append("  v").append(r.version())
                        .append("  evidence=").append(blankDash(r.evidenceRunId()))
                        .append("  at=").append(iso(r.createdAt()))
                        .append("  by=").append(blankDash(r.createdBy()))
                        .append('\n');
            }
        }
        sb.append("recipes: ").append(recipeCount).append('\n').append(recipeOut);

        // 4) 部署（容器带 agent.sha 标签）
        StringBuilder deployOut = new StringBuilder();
        int deployCount = 0;
        boolean dockerDown = false;
        for (String env : List.of("dev", "test", "prod")) {
            List<RuntimeInspector.ContainerStatus> containers;
            try {
                containers = inspector.containers(projectId, env);
            } catch (IllegalStateException e) {
                dockerDown = true;
                continue;
            }
            for (RuntimeInspector.ContainerStatus c : containers) {
                if (c.sha() == null || !c.sha().toLowerCase().startsWith(needle)) {
                    continue;
                }
                deployCount++;
                deployOut.append("  - env=").append(env)
                        .append("  container=").append(c.name())
                        .append("  status=").append(c.status())
                        .append("  health=").append(c.health() == null ? "none" : c.health())
                        .append("  image=").append(blankDash(c.image()))
                        .append("  startedAt=").append(blankDash(c.startedAt()))
                        .append('\n');
            }
        }
        sb.append("deployments: ").append(deployCount).append('\n').append(deployOut);

        List<String> missing = new ArrayList<>();
        if (commit == null) {
            missing.add("提交快照（可能已不在最近 100 条里）");
        }
        if (allBuilds.isEmpty()) {
            missing.add("构建（该 sha 没有构建记录）");
        }
        if (recipeCount == 0) {
            missing.add("配方（跑通后未固化，无法确定性重放）");
        }
        if (deployCount == 0) {
            missing.add(dockerDown ? "部署（docker 不可用，无法读取容器）" : "部署（没有带该 sha 标签的容器）");
        }
        if (!missing.isEmpty()) {
            sb.append("missing: ").append(String.join("；", missing)).append('\n');
        }
        sb.append("evidence: project:").append(projectId).append("/sha:").append(needle);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 内部

    private String historyLine(PipelineEngine.RunSummary h) {
        StringBuilder line = new StringBuilder("- ").append(h.runId())
                .append("  env=").append(blankDash(h.env()))
                .append("  ").append(h.pipelineId())
                .append("  ").append(h.status())
                .append("  sha=").append(shortSha(h.gitSha()))
                .append("  stages=").append(h.stageCount())
                .append("  duration=").append(duration(h.durationMs()))
                .append("  at=").append(iso(h.createdAt()));
        PipelineEngine.RunMeta meta = engine.runMeta(h.runId());
        if (meta != null && (meta.definitionSource() != null || meta.definitionNote() != null)) {
            line.append("  def=").append(blankDash(meta.definitionSource()));
            if (meta.definitionNote() != null && !meta.definitionNote().isBlank()) {
                line.append("(").append(meta.definitionNote()).append(")");
            }
        }
        return line.toString();
    }

    private static String iso(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String duration(long millis) {
        if (millis <= 0) {
            return "-";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            long rest = seconds % 60;
            return minutes + "m" + (rest == 0 ? "" : rest + "s");
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours + "h" + (rest == 0 ? "" : rest + "m");
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "-";
        }
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    private static String blankDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
