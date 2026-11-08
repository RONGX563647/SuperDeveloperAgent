package top.rongx.agent.executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.executor.RunHistoryStore.RunSnapshot;

/**
 * 内置流水线引擎：把「检出 → 构建 → 测试 → 镜像 → 部署 → 健康检查」串成一次可追溯的运行。
 *
 * <p><b>编排职责边界（P3 澄清）</b>：平台<b>不</b>拥有多 stage 编排逻辑本身——
 * 编排由仓库 {@code ci/pipeline.yml}（数据，人写、versioned）声明，本引擎只是其确定性执行器。
 * 这与"模型写命令"严格隔离：模型只能 {@code ci_run} 触发一条已定义流水线，不能拼 stage。
 *
 * <p>与 Jenkins 的对应关系：
 * <ul>
 *   <li>{@code ci/pipeline.yml} ↔ Jenkinsfile（但只是数据，不能写任意代码）；</li>
 *   <li>{@code trigger(gitSha)} ↔ 指定提交构建；</li>
 *   <li>{@code RunState} ↔ 构建历史（stage 结果、耗时、产物）；</li>
 *   <li>失败即停 + on-failure ↔ {@code post { failure { ... } } }；</li>
 *   <li>{@code health} stage ↔ 发布门禁。</li>
 * </ul>
 *
 * <p>执行面是确定性代码，不调用任何模型（规约 A3）。每个 stage 底层都分解为
 * {@code ActionSpec}（动作层），与 {@code run_action} 共用同一套 ActionRunner 与门禁。
 */
public class BuiltInPipelineEngine implements PipelineEngine, AutoCloseable {

    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(5);

    private final ActionResolver recipes;
    private final ActionRunner sandboxed;
    private final ActionRunner host;
    private final Path workspaceRoot;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    /** 每个项目最近一次成功发布的镜像 tag，供回滚使用。 */
    private final Map<String, String> lastGood = new ConcurrentHashMap<>();
    /** 有界队列：全局并发上限 4；同一项目串行由 TriggerScheduler 保证。 */
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
    private final TriggerScheduler scheduler = new TriggerScheduler();
    /** 构建历史持久化（重启可恢复）。 */
    private final RunHistoryStore history;
    /** 发布镜像 tag 前缀（可配置；避免测试字样进入正式发布链路）。 */
    private final String imagePrefix;

    /** 应用关闭时停掉执行线程：不再接新任务，正在跑的构建被中断（避免悬挂进程）。 */
    @Override
    public void close() {
        workers.shutdownNow();
    }

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot) {
        this(recipes, sandboxed, host, workspaceRoot, RunHistoryStore.disabled(), "ci");
    }

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history) {
        this(recipes, sandboxed, host, workspaceRoot, history, "ci");
    }

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix) {
        this.recipes = recipes;
        this.sandboxed = sandboxed;
        this.host = host;
        this.workspaceRoot = workspaceRoot;
        this.history = history == null ? RunHistoryStore.disabled() : history;
        this.imagePrefix = imagePrefix == null || imagePrefix.isBlank() ? "ci" : imagePrefix;
        restoreHistory();
    }

    // ---------------------------------------------------------------- PipelineEngine

    @Override
    public String id() {
        return "builtin-pipeline";
    }

    /** 内置兜底流水线（仓库无 ci/pipeline.yml 时使用）。 */
    private static final List<String> BUILTIN_PIPELINES = List.of("release", "verify");

    @Override
    public List<String> pipelines(String projectId) {
        // 白名单来自仓库 ci/pipeline.yml（数据、人写、versioned）；读不到再退回内置
        Path repoDir = workspaceRoot.resolve(projectId).toAbsolutePath().normalize().resolve("repo");
        Path pipelineFile = repoDir.resolve("ci/pipeline.yml");
        if (Files.isRegularFile(pipelineFile)) {
            try {
                PipelineDefinition def = PipelineDefinition.load(repoDir);
                if (def.name() != null && !def.name().isBlank()) {
                    return List.of(def.name(), "verify");
                }
            } catch (Exception e) {
                // 仓库的 pipeline.yml 损坏时不阻塞白名单查询：回退内置并保留触发期报错
            }
        }
        return BUILTIN_PIPELINES;
    }

    @Override
    public String trigger(String projectId, String pipelineId, Map<String, String> params) {
        if (!pipelines(projectId).contains(pipelineId)) {
            throw new IllegalArgumentException("流水线不在白名单：" + pipelineId);
        }
        String sha = params.get("gitSha");
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("gitSha 非法（需 7-40 位小写十六进制）");
        }
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        RunState state = new RunState(runId, projectId, pipelineId, sha);
        runs.put(runId, state);
        history.save(state.snapshot());
        scheduler.submit(projectId, () -> workers.submit(() -> execute(state)));
        return runId;
    }

    @Override
    public StageStatus status(String runId) {
        RunState s = runs.get(runId);
        return s == null ? StageStatus.PENDING : s.status;
    }

    /** 该 runId 是否真实存在（用于把"不存在"与"排队中"区分开）。 */
    public boolean hasRun(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    @Override
    public List<StageResult> stages(String runId) {
        RunState s = runs.get(runId);
        return s == null ? List.of() : List.copyOf(s.stages);
    }

    @Override
    public void cancel(String runId) {
        RunState s = runs.get(runId);
        if (s != null && !s.status.isTerminal()) {
            s.cancelled = true;
            history.save(s.snapshot());
        }
    }

    @Override
    public List<ArtifactRef> artifacts(String runId) {
        RunState s = runs.get(runId);
        return s == null ? List.of() : List.copyOf(s.artifacts);
    }

    /** 供验收/排障：某次运行的全部日志尾部（经脱敏，避免凭证入链）。 */
    @Override
    public String logTail(String runId) {
        RunState s = runs.get(runId);
        if (s != null) {
            return ProcessExecutor.redact(s.log.toString());
        }
        return history.list().stream()
                .filter(h -> h.runId().equals(runId))
                .findFirst().map(h -> ProcessExecutor.redact(h.log())).orElse("");
    }

    /** 构建历史列表（含重启前落盘的运行），转为 domain 视图。 */
    @Override
    public List<RunSummary> history() {
        return history.list().stream()
                .map(h -> new RunSummary(h.runId(), h.projectId(), h.pipelineId(), h.gitSha(),
                        h.status(), h.createdAt(), h.stages().size()))
                .toList();
    }

    /** 排队数量（可观测性）。 */
    public int pendingCount() {
        return scheduler.pendingCount();
    }

    private void restoreHistory() {
        for (RunHistoryStore.RunSnapshot snap : history.list()) {
            if (runs.containsKey(snap.runId())) {
                continue;
            }
            RunState restored = new RunState(snap.runId(), snap.projectId(), snap.pipelineId(), snap.gitSha());
            restored.status = snap.status();
            restored.stages.addAll(snap.stages());
            restored.artifacts.addAll(snap.artifacts());
            restored.log.append(snap.log());
            runs.put(snap.runId(), restored);
            if (snap.status() == StageStatus.SUCCEEDED && "release".equals(snap.pipelineId())) {
                // 回滚锚点：取该次运行登记的镜像 tag
                snap.artifacts().stream()
                        .filter(a -> a.kind().equals("image"))
                        .findFirst()
                        .ifPresent(a -> lastGood.put(snap.projectId(), a.ref()));
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    private void execute(RunState state) {
        try {
            state.status = StageStatus.RUNNING;
            doExecute(state);
        } finally {
            // 每次运行结束（含异常）都落盘，保证重启后可查询历史
            history.save(state.snapshot());
            scheduler.complete(state.projectId);
        }
    }

    private void doExecute(RunState state) {
        Path projectDir = workspaceRoot.resolve(state.projectId).toAbsolutePath().normalize();
        // v2：bare 仓库是唯一对象库；构建在独立 worktree 里进行（终态清理）
        Path bare = projectDir.resolve("git");
        Path runDir = projectDir.resolve("worktrees").resolve("wt-build-" + state.runId);
        PipelineDefinition definition = null;
        boolean worktreeCreated = false;

        try {
            if (!Files.isDirectory(bare.resolve("objects"))) {
                throw new IllegalStateException("项目仓库不存在：" + bare
                        + "（请先通过「接入项目」完成收编或 clone）");
            }
            // stage 1：从 bare 检出指定提交到独立 worktree
            checkout(bare, runDir, state);
            worktreeCreated = true;
            if (state.cancelled) {
                state.status = StageStatus.CANCELLED;
                return;
            }
            definition = PipelineDefinition.load(runDir);
            if ("verify".equals(state.pipelineId)) {
                // verify = 构建与镜像，不做部署/健康检查（相当于 Jenkins 的 PR 检查）
                definition = new PipelineDefinition(definition.name(),
                        definition.stages().stream()
                                .filter(s -> !"health".equals(s.type()))
                                .filter(s -> !"stack_deploy".equals(s.action()))
                                .toList(),
                        List.of());
            }

            String tag = imagePrefix + "-" + state.gitSha.substring(0, 7);
            Map<String, String> vars = Map.of(
                    "gitSha", state.gitSha,
                    "gitSha7", state.gitSha.substring(0, 7),
                    "tag", tag);

            for (PipelineDefinition.StageDef stage : definition.stages()) {
                if (state.cancelled) {
                    state.status = StageStatus.CANCELLED;
                    return;
                }
                boolean ok = runStage(state, stage, runDir, vars);
                if (!ok) {
                    // 先跑完 on-failure，再置终态：否则查询方会在补救动作结束前看到"已完成"
                    runOnFailure(state, definition, runDir, vars);
                    state.status = StageStatus.FAILED;
                    return;
                }
            }

            lastGood.put(state.projectId, tag);
            state.status = StageStatus.SUCCEEDED;
        } catch (Exception e) {
            state.log.append("引擎异常: ").append(e.getMessage()).append('\n');
            state.status = StageStatus.FAILED;
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", e.getMessage(), 0));
            if (definition != null) {
                runOnFailure(state, definition, runDir, Map.of());
            }
        } finally {
            // 无论成败，构建 worktree 用完即清（worktree 里可能有产物被记账，先落盘历史再删）
            if (worktreeCreated) {
                history.save(state.snapshot());
                cleanupWorktree(bare, runDir, state);
            }
        }
    }

    /**
     * 检出（v2）：不再 clone，用 git worktree add——毫秒级、零重复存储。
     * source 现在是 bare 仓库（{@code <workspace>/<id>/git}）；runDir 即本次构建的 worktree
     * （{@code worktrees/wt-build-<runId>}），构建终态后由 cleanupWorktree 移除。
     */
    private void checkout(Path bare, Path runDir, RunState state) {
        long start = System.nanoTime();
        ActionResult add = host.run(bare, spec("checkout", List.of(
                List.of("git", "worktree", "add", "--quiet", "--detach",
                        posix(runDir), state.gitSha))));
        if (add.result().status() != StageStatus.SUCCEEDED) {
            // 新推送的提交可能还没进 bare：提示前置同步
            throw new IllegalStateException("git worktree add " + state.gitSha
                    + " 失败：" + add.result().errorSummary()
                    + "（若为最新提交，请先在项目详情里执行「同步到平台」）");
        }
        state.log.append("[checkout] worktree ").append(state.gitSha).append('\n');
        state.stages.add(StageResult.success("checkout", 0, List.of(),
                (System.nanoTime() - start) / 1_000_000L));
    }

    /** 构建终态后清理本次 worktree（失败不影响主流程，prune 兜底）。 */
    private void cleanupWorktree(Path bare, Path runDir, RunState state) {
        ActionResult rm = host.run(bare, spec("checkout", List.of(
                List.of("git", "worktree", "remove", "--force", posix(runDir)))));
        if (rm.result().status() == StageStatus.SUCCEEDED) {
            state.log.append("[cleanup] worktree removed\n");
        } else {
            state.log.append("[cleanup] worktree 清理失败（prune 兜底）：")
                    .append(rm.result().errorSummary()).append('\n');
        }
    }

    private boolean runStage(RunState state, PipelineDefinition.StageDef stage,
                             Path runDir, Map<String, String> vars) {
        long start = System.nanoTime();
        try {
            if ("git-checkout".equals(stage.type())) {
                return true; // 已在 execute 里完成
            }
            if ("health".equals(stage.type())) {
                return health(state, stage, start);
            }

            ActionId action = ActionId.fromWire(stage.action()).orElseThrow(
                    () -> new IllegalArgumentException("未知动作：" + stage.action()));
            Optional<ActionSpec> resolved = recipes.resolve(runDir, action);
            if (resolved.isEmpty()) {
                state.stages.add(StageResult.failed(stage.id(), 1, "E-201",
                        "动作 " + action.wireName() + " 没有配方", elapsed(start)));
                return false;
            }
            ActionSpec original = resolved.get();
            ActionSpec substituted = substitute(original, vars);
            ActionRunner runner = selectRunner(stage);
            // monorepo：stage 可在子目录执行（如 backend / frontend），
            // 但产物与镜像仍按仓库根记账，避免各模块配方各写一套路径。
            Path stageDir = (stage.workdir() == null || stage.workdir().isBlank())
                    ? runDir : runDir.resolve(stage.workdir()).normalize();
            ActionResult result = runner.run(stageDir, substituted, () -> state.cancelled);

            state.log.append('[').append(stage.id()).append("] ")
                    .append(result.logTail()).append('\n');
            state.stages.add(rename(result.result(), stage.id()));
            state.artifacts.addAll(prefixArtifacts(result.result().artifacts(), stage.workdir()));

            if (result.result().status() == StageStatus.SUCCEEDED
                    && "image_package".equals(stage.action())) {
                String tag = vars.get("tag");
                state.artifacts.add(new ArtifactRef("image", tag,
                        imageDigest(runDir, tag), 0, Map.of("runId", state.runId)));
            }
            return result.result().status() == StageStatus.SUCCEEDED;
        } catch (Exception e) {
            state.stages.add(StageResult.failed(stage.id(), 1, "E-2xx", e.getMessage(), elapsed(start)));
            return false;
        }
    }

    /** 把子目录 stage 的相对产物路径补成仓库根相对路径（便于统一记账/下载）。 */
    private List<ArtifactRef> prefixArtifacts(List<ArtifactRef> artifacts, String workdir) {
        if (workdir == null || workdir.isBlank()) {
            return artifacts;
        }
        String prefix = workdir.replace('\\', '/').replaceAll("^/+|/+$", "") + "/";
        return artifacts.stream()
                .map(a -> new ArtifactRef(a.kind(), prefix + a.ref(), a.digest(),
                        a.sizeBytes(), a.meta()))
                .toList();
    }

    /**
     * 选择执行器：host 走宿主；container 且声明了 {@code image} 时，
     * 用该 stage 专属工具链镜像新建一个沙箱（同一套隔离参数，只换镜像）。
     */
    private ActionRunner selectRunner(PipelineDefinition.StageDef stage) {
        if ("host".equals(stage.runner())) {
            return host;
        }
        if (stage.image() == null || stage.image().isBlank()) {
            return sandboxed;
        }
        if (sandboxed instanceof DockerActionRunner dockerRunner) {
            DockerSandbox base = dockerRunner.sandbox();
            return new DockerActionRunner(base.withImage(stage.image()));
        }
        // 宿主模式下忽略 image（工具链由宿主机提供）
        return host;
    }

    private boolean health(RunState state, PipelineDefinition.StageDef stage, long start) {
        String url = stage.url();
        if (url == null || url.isBlank()) {
            state.stages.add(StageResult.failed(stage.id(), 1, "E-201", "health 缺少 url", elapsed(start)));
            return false;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String body = "";
        for (int i = 0; i < 20; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                body = response.body();
                if (response.statusCode() == stage.expected()) {
                    state.log.append("[health] ").append(response.statusCode()).append(' ').append(body).append('\n');
                    state.stages.add(StageResult.success(stage.id(), 0, List.of(), elapsed(start)));
                    return true;
                }
            } catch (Exception ignored) {
                // 服务尚未起来，继续重试
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        state.stages.add(StageResult.failed(stage.id(), 1, "E-401",
                "健康检查未通过：" + url + " body=" + body, elapsed(start)));
        return false;
    }

    private void runOnFailure(RunState state, PipelineDefinition definition,
                              Path runDir, Map<String, String> vars) {
        for (PipelineDefinition.StageDef stage : definition.onFailure()) {
            ActionId action = ActionId.fromWire(stage.action()).orElse(null);
            if (action == null) {
                continue;
            }
            Map<String, String> merged = new java.util.HashMap<>(vars);
            String good = lastGood.get(state.projectId);
            if (good != null) {
                merged.put("tag", good);
            }
            Optional<ActionSpec> spec = recipes.resolve(runDir, action);
            if (spec.isEmpty()) {
                continue;
            }
            ActionResult result = host.run(runDir, substitute(spec.get(), Map.copyOf(merged)),
                    () -> false);
            state.log.append("[on-failure:").append(stage.id()).append("] ")
                    .append(result.logTail()).append('\n');
            state.stages.add(rename(result.result(), "on-failure:" + stage.id()));
        }
    }

    private String imageDigest(Path runDir, String tag) {
        ActionResult r = host.run(runDir, spec("image-digest", List.of(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", tag))), () -> false);
        if (r.result().status() != StageStatus.SUCCEEDED) {
            return null;
        }
        String line = r.logTail().trim();
        return line.contains(":") ? line.substring(line.lastIndexOf(':') + 1) : line;
    }

    private ActionSpec substitute(ActionSpec spec, Map<String, String> vars) {
        List<List<String>> steps = spec.steps().stream()
                .map(argv -> argv.stream()
                        .map(token -> {
                            String out = token;
                            for (Map.Entry<String, String> e : vars.entrySet()) {
                                out = out.replace("${" + e.getKey() + "}", e.getValue());
                            }
                            return out;
                        }).toList())
                .toList();
        return new ActionSpec(spec.action(), steps, spec.produce(), spec.timeout(), spec.source());
    }

    private ActionSpec spec(String id, List<List<String>> steps) {
        return new ActionSpec(ActionId.BACKEND_BUILD, steps, List.of(), STAGE_TIMEOUT,
                ActionSource.builtin("engine#" + id));
    }

    private StageResult rename(StageResult result, String stageId) {
        return new StageResult(stageId, result.status(), result.exitCode(), result.errorCode(),
                result.errorSummary(), result.artifacts(), result.durationMs(), result.retryCount());
    }

    /** 路径给 git / docker 用时统一成 POSIX 风格（Windows 下 E:\x → E:/x）。 */
    private String posix(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 一次运行的状态（构建历史）。 */
    private static final class RunState {
        final String runId;
        final String projectId;
        final String pipelineId;
        final String gitSha;
        /** 触发时间（epoch millis），构建历史按它倒序展示。 */
        final long createdAt = System.currentTimeMillis();
        final List<StageResult> stages = new ArrayList<>();
        final List<ArtifactRef> artifacts = new ArrayList<>();
        final StringBuilder log = new StringBuilder();
        volatile StageStatus status = StageStatus.PENDING;
        volatile boolean cancelled = false;

        RunSnapshot snapshot() {
            return new RunHistoryStore.RunSnapshot(runId, projectId, pipelineId, gitSha,
                    status, createdAt, List.copyOf(stages), List.copyOf(artifacts),
                    ProcessExecutor.redact(log.toString()));
        }

        RunState(String runId, String projectId, String pipelineId, String gitSha) {
            this.runId = runId;
            this.projectId = projectId;
            this.pipelineId = pipelineId;
            this.gitSha = gitSha;
        }
    }
}
