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

/**
 * 内置流水线引擎：把「检出 → 构建 → 测试 → 镜像 → 部署 → 健康检查」串成一次可追溯的运行。
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
 * <p>执行面是确定性代码，不调用任何模型（规约 A3）。
 */
public class BuiltInPipelineEngine implements PipelineEngine {

    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(5);

    private final ActionResolver recipes;
    private final ActionRunner sandboxed;
    private final ActionRunner host;
    private final Path workspaceRoot;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    /** 每个项目最近一次成功发布的镜像 tag，供回滚使用。 */
    private final Map<String, String> lastGood = new ConcurrentHashMap<>();

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot) {
        this.recipes = recipes;
        this.sandboxed = sandboxed;
        this.host = host;
        this.workspaceRoot = workspaceRoot;
    }

    // ---------------------------------------------------------------- PipelineEngine

    @Override
    public String id() {
        return "builtin-pipeline";
    }

    @Override
    public List<String> pipelines(String projectId) {
        return List.of("release", "verify");
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
        Thread.ofVirtual().start(() -> execute(state));
        return runId;
    }

    @Override
    public StageStatus status(String runId) {
        RunState s = runs.get(runId);
        return s == null ? StageStatus.PENDING : s.status;
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
        }
    }

    @Override
    public List<ArtifactRef> artifacts(String runId) {
        RunState s = runs.get(runId);
        return s == null ? List.of() : List.copyOf(s.artifacts);
    }

    /** 供验收/排障：某次运行的全部日志尾部。 */
    @Override
    public String logTail(String runId) {
        RunState s = runs.get(runId);
        return s == null ? "" : s.log.toString();
    }

    // ---------------------------------------------------------------- 执行

    private void execute(RunState state) {
        state.status = StageStatus.RUNNING;
        Path projectDir = workspaceRoot.resolve(state.projectId).toAbsolutePath().normalize();
        Path source = projectDir.resolve("repo");
        Path runDir = projectDir.resolve("runs").resolve(state.runId);
        PipelineDefinition definition = null;

        try {
            Files.createDirectories(runDir.getParent());
            // stage 1：从 SCM 检出指定提交（Jenkinsfile 也是从 SCM 读的）
            checkout(source, runDir, state);
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

            String tag = "ci-e2e:" + state.gitSha.substring(0, 7);
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
        }
    }

    /** 检出：git clone（本地仓库）+ checkout 指定 sha。 */
    private void checkout(Path source, Path runDir, RunState state) {
        long start = System.nanoTime();
        ActionResult clone = host.run(source, spec("checkout", List.of(
                List.of("git", "clone", "--quiet", "--no-checkout", posix(source), posix(runDir)))));
        if (clone.result().status() != StageStatus.SUCCEEDED) {
            throw new IllegalStateException("git clone 失败：" + clone.result().errorSummary());
        }
        ActionResult pinned = host.run(source, spec("checkout", List.of(
                List.of("git", "-C", posix(runDir), "checkout", "--detach", "--quiet", state.gitSha))));
        if (pinned.result().status() != StageStatus.SUCCEEDED) {
            throw new IllegalStateException("git checkout " + state.gitSha + " 失败：" + pinned.result().errorSummary());
        }
        state.log.append("[checkout] ").append(state.gitSha).append('\n');
        state.stages.add(StageResult.success("checkout", 0, List.of(),
                (System.nanoTime() - start) / 1_000_000L));
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
            ActionRunner runner = "host".equals(stage.runner()) ? host : sandboxed;
            ActionResult result = runner.run(runDir, substituted, () -> state.cancelled);

            state.log.append('[').append(stage.id()).append("] ")
                    .append(result.logTail()).append('\n');
            state.stages.add(rename(result.result(), stage.id()));
            state.artifacts.addAll(result.result().artifacts());

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
        final List<StageResult> stages = new ArrayList<>();
        final List<ArtifactRef> artifacts = new ArrayList<>();
        final StringBuilder log = new StringBuilder();
        volatile StageStatus status = StageStatus.PENDING;
        volatile boolean cancelled = false;

        RunState(String runId, String projectId, String pipelineId, String gitSha) {
            this.runId = runId;
            this.projectId = projectId;
            this.pipelineId = pipelineId;
            this.gitSha = gitSha;
        }
    }
}
