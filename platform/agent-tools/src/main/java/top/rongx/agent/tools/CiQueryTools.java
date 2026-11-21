package top.rongx.agent.tools;

import top.rongx.agent.domain.hash.Hashes;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.port.RepoInspector;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.domain.runtime.RuntimeInspector;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.BuildException;
import top.rongx.agent.domain.error.PlatformException;

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
    private final SecretRedactor redactor;
    private final PipelineDefStore defStore;
    private final ProjectRepoLocator locator;

    public CiQueryTools(PipelineEngine engine, RepoInspector repo, RecipeStore recipes,
                        RuntimeInspector inspector, SecretRedactor redactor,
                        PipelineDefStore defStore, ProjectRepoLocator locator) {
        this.engine = engine;
        this.repo = repo;
        this.recipes = recipes;
        this.inspector = inspector;
        this.redactor = redactor;
        this.defStore = defStore;
        this.locator = locator;
    }

    @Tool(name = "ci_list_pipelines", description = "List pipelines of this project.",
            readOnly = true, concurrencySafe = true)
    public String listPipelines(ProjectContext ctx) {
        List<String> ids = engine.pipelines(ctx.projectId());
        return ids.stream()
                .collect(Collectors.joining("\n",
                        "pipelines(engine=" + engine.id() + "):\n",
                        "\nevidence: engine:" + engine.id()));
    }

    @Tool(name = "ci_definition", description = "Read the effective pipeline definition.",
            readOnly = true, concurrencySafe = true)
    public String definition(ProjectContext ctx) {
        Path repoRoot = locator.repoDir(ctx.projectId()).orElse(null);
        Path repoDef = repoRoot == null ? null : repoRoot.resolve("ci").resolve("pipeline.yml");
        String yaml;
        String source;
        String root;
        if (repoDef != null && Files.isRegularFile(repoDef)) {
            source = "repo（仓库 ci/pipeline.yml，优先于平台侧）";
            root = repoRoot.toString();
            try {
                yaml = Files.readString(repoDef);
            } catch (IOException e) {
                return "source: " + source + "\nroot: " + root
                        + "\nerror: 无法读取仓库流水线定义：" + e.getMessage()
                        + "\nevidence: project:" + ctx.projectId();
            }
        } else {
            source = "platform（仓库没有 ci/pipeline.yml，使用平台侧定义）";
            root = defStore.rootOf(ctx.projectId()).toString();
            yaml = defStore.read(ctx.projectId()).orElse("");
        }
        if (yaml.isBlank()) {
            return "source: " + source + "\nroot: " + root
                    + "\ndefinition: 无\n"
                    + "note: 仓库根 ci/pipeline.yml 若存在则优先生效；可用 propose_patch 新增\n"
                    + "evidence: project:" + ctx.projectId();
        }
        int max = 4000;
        String body = yaml.length() > max
                ? yaml.substring(0, max) + "\n…（已截断，完整内容见 " + root + "/ci/pipeline.yml）" : yaml;
        StringBuilder out = new StringBuilder();
        out.append("source: ").append(source).append('\n')
                .append("root: ").append(root).append('\n')
                .append("---\n").append(body).append("\n---\n");
        String hint = imageRepairHint(yaml, repoRoot);
        if (hint != null) {
            out.append(hint);
        }
        out.append("evidence: project:").append(ctx.projectId());
        return out.toString();
    }

    /**
     * docker 目标 + 缺 image stage 的修复配方：发布必然 E-501，必须让 Agent 知道改哪里。
     *
     * <p>注意改的是<b>仓库</b> ci/pipeline.yml（优先级更高），不是平台侧那份。
     */
    private static String imageRepairHint(String yaml, Path repoRoot) {
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> doc)) {
            return null;
        }
        Map<?, ?> deploy = doc.get("deploy") instanceof Map<?, ?> m ? m : Map.of();
        String target = deploy.get("target") == null ? "docker" : String.valueOf(deploy.get("target"));
        boolean docker = target.isBlank() || "docker".equalsIgnoreCase(target);
        boolean hasImageStage = false;
        if (doc.get("stages") instanceof List<?> stages) {
            for (Object item : stages) {
                if (!(item instanceof Map<?, ?> stage)) {
                    continue;
                }
                String type = stage.get("type") == null ? "" : String.valueOf(stage.get("type"));
                String action = stage.get("action") == null ? "" : String.valueOf(stage.get("action"));
                if ("image".equalsIgnoreCase(type) || "image".equalsIgnoreCase(action)
                        || "image_package".equals(action)) {
                    hasImageStage = true;
                    break;
                }
            }
        }
        if (!docker || hasImageStage) {
            return null;
        }
        boolean hasDockerfile = repoRoot != null
                && (Files.isRegularFile(repoRoot.resolve("Dockerfile"))
                || Files.isRegularFile(repoRoot.resolve("backend").resolve("Dockerfile"))
                || Files.isRegularFile(repoRoot.resolve("frontend").resolve("Dockerfile")));
        return "warning: deploy.target=docker 但没有 image_package stage，发布必然 E-501\n"
                + "repair: 用 propose_patch 在仓库根新增/修改 ci/pipeline.yml，加入 "
                + "`type: action` + `action: image_package` 的 stage"
                + (hasDockerfile ? "" : "，并补 Dockerfile")
                + "；仓库定义优先于平台侧，改完需人工确认\n";
    }

    @Tool(name = "ci_status", description = "Get one build status and stages.",
            readOnly = true, concurrencySafe = true)
    public String status(@ToolParam(name = "run_id", description = "ci_run 返回的 runId") String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "run_id 不能为空");
        }
        if (!engine.hasRun(runId)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "run 不存在：" + runId + "（请核对 ci_run 返回的 runId）");
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

    @Tool(name = "read_run_log", description = "Read a build log tail.",
            readOnly = true, concurrencySafe = true)
    public String readRunLog(ProjectContext ctx,
                             @ToolParam(name = "run_id", description = "ci_run / deploy_run 返回的 runId")
                             String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "run_id 不能为空");
        }
        if (!engine.hasRun(runId)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "run 不存在：" + runId + "（请核对 ci_run 返回的 runId）");
        }
        String log = engine.logTail(runId);
        if (log == null || log.isBlank()) {
            return "runId: " + runId + "\n(log 为空：运行尚未产出日志，稍后再试)\nevidence: run:" + runId;
        }
        // 构建日志可能包含 docker login / 环境变量打印：先做值级 + 形态级脱敏，再截断
        log = redactor.redact(log, ctx);
        String tail = headTail(log, 6000);
        return """
                runId: %s
                status: %s
                log:
                ---
                %s
                ---
                evidence: run:%s""".formatted(runId, engine.status(runId), tail, runId);
    }

    @Tool(name = "wait_run", description = "Wait for a build to finish.",
            readOnly = true, concurrencySafe = true)
    public String waitRun(@ToolParam(name = "run_id", description = "ci_run / deploy_run 返回的 runId")
                          String runId,
                          @ToolParam(name = "timeout_seconds",
                                  description = "最长等待秒数，默认 90，范围 5-180")
                          Integer timeoutSeconds) {
        if (runId == null || runId.isBlank()) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "run_id 不能为空");
        }
        if (!engine.hasRun(runId)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "run 不存在：" + runId + "（请核对 deploy_run 返回的 runId）");
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
        String tail = log == null ? "" : headTail(log, 2000);
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

    /**
     * 日志只留头 + 尾：只看尾部会丢掉阶段开始、命令与错误码，只看头部又会丢掉最终失败原因。
     *
     * <p>诊断"为什么失败"需要开头（哪个阶段、什么命令）和结尾（错误码、最后输出）同时在场。
     */
    static String headTail(String log, int max) {
        if (log == null || log.length() <= max) {
            return log;
        }
        int head = max / 4;
        int tail = max - head;
        int skipped = log.length() - head - tail;
        return log.substring(0, head)
                + "\n…（中间省略 " + skipped + " 字符；保留开头 " + head + " 与结尾 " + tail + "）…\n"
                + log.substring(log.length() - tail);
    }

    @Tool(name = "ci_history", description = "List recent builds of this project.",
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

    @Tool(name = "release_trace", description = "Trace commit to build to deploy chain.",
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
            } catch (PlatformException | IllegalStateException e) {
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
                .append("  sha=").append(Hashes.shortSha(h.gitSha(), 8))
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

    private static String blankDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
