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
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.PipelineEngine.LogSlice;
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
    /** 仓库定位器（v2 直接模式；可空 = 旧布局 workspace/<id>/git）。 */
    private final top.rongx.agent.domain.port.ProjectRepoLocator repoLocator;
    /** 平台部署器（方案 C：部署语义归平台，不再交给仓库脚本）。 */
    private final top.rongx.agent.domain.port.Deployer deployers;

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
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, null);
    }

    /** v2：带仓库定位器的构造（DIRECT 模式：仓库在用户目录）。 */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, Deployers.builtin());
    }

    /** 完整构造：额外注入平台部署器（DOCKER/HOST/SERVER）。 */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers) {
        this.recipes = recipes;
        this.sandboxed = sandboxed;
        this.host = host;
        this.workspaceRoot = workspaceRoot;
        this.history = history == null ? RunHistoryStore.disabled() : history;
        this.imagePrefix = imagePrefix == null || imagePrefix.isBlank() ? "ci" : imagePrefix;
        this.repoLocator = repoLocator;
        this.deployers = deployers == null ? Deployers.builtin() : deployers;
        restoreHistory();
    }

    // ---------------------------------------------------------------- PipelineEngine

    @Override
    public String id() {
        return "builtin-pipeline";
    }

    /** 流水线定义在仓库里的固定路径（versioned 数据；平台不猜构建命令）。 */
    private static final String PIPELINE_FILE = "ci/pipeline.yml";

    /** 旧布局（平台没定位到仓库目录）时的兜底名，仅用于展示；触发会由执行期给出可读错误。 */
    private static final List<String> BUILTIN_PIPELINES = List.of("release", "verify");

    /** 缺 ci/pipeline.yml（提交里没有）。 */
    private static final String E_NO_PIPELINE = "E-220";
    /** ci/pipeline.yml 存在但解析不出来。 */
    private static final String E_BAD_PIPELINE = "E-221";

    @Override
    public List<String> pipelines(String projectId) {
        // 白名单来自仓库 ci/pipeline.yml（数据、人写、versioned）
        Path repoDir = resolveRepoForRead(projectId);
        if (repoDir == null) {
            return BUILTIN_PIPELINES;
        }
        if (!hasPipelineDefinition(repoDir)) {
            // 不提供"看起来能跑"的假流水线：仓库没有定义时白名单为空，
            // 前端直接引导用户去生成并提交 ci/，而不是让用户点了再失败。
            return List.of();
        }
        Path pipelineFile = repoDir.resolve(PIPELINE_FILE);
        if (!Files.isRegularFile(pipelineFile)) {
            // MANAGED（bare 无工作区）：默认分支上有定义即可，读不到自定义名时用默认名
            return BUILTIN_PIPELINES;
        }
        try {
            PipelineDefinition def = PipelineDefinition.load(repoDir);
            if (def.name() != null && !def.name().isBlank()) {
                return List.of(def.name(), "verify");
            }
            return BUILTIN_PIPELINES;
        } catch (Exception e) {
            // 定义损坏时不阻塞白名单查询：仍列出默认名，触发期会给出可读的解析错误
            return BUILTIN_PIPELINES;
        }
    }

    /**
     * 仓库里是否已有流水线定义。
     *
     * <p>工作区（DIRECT = 用户目录）直接看文件；MANAGED 的仓库是 bare、没有工作区，
     * 只能查默认分支的 blob——否则托管项目会被误判成"没有流水线"。
     */
    private boolean hasPipelineDefinition(Path repoDir) {
        if (Files.isRegularFile(repoDir.resolve(PIPELINE_FILE))) {
            return true;
        }
        if (!Files.isDirectory(repoDir.resolve("objects"))) {
            return false;
        }
        for (String branch : defaultBranches(repoDir)) {
            ActionResult r = host.run(repoDir, spec("pipeline-check", List.of(
                    List.of("git", "cat-file", "-e", branch + ":" + PIPELINE_FILE))), () -> false);
            if (r.result().status() == StageStatus.SUCCEEDED) {
                return true;
            }
        }
        return false;
    }

    /** 默认分支候选：bare 的 HEAD 指向的分支，再退回 main / master。 */
    private List<String> defaultBranches(Path repo) {
        List<String> branches = new ArrayList<>();
        ActionResult head = host.run(repo, spec("pipeline-check", List.of(
                List.of("git", "symbolic-ref", "--short", "HEAD"))), () -> false);
        if (head.result().status() == StageStatus.SUCCEEDED) {
            String name = head.logTail().trim();
            if (!name.isEmpty()) {
                branches.add(name);
            }
        }
        for (String fallback : List.of("main", "master")) {
            if (!branches.contains(fallback)) {
                branches.add(fallback);
            }
        }
        return branches;
    }

    /** 读仓库（白名单/配方自检用）：locator 优先（DIRECT=用户目录），否则旧布局。 */
    private Path resolveRepoForRead(String projectId) {
        if (repoLocator != null) {
            return repoLocator.repoDir(projectId).orElse(null);
        }
        Path legacy = workspaceRoot.resolve(projectId).toAbsolutePath().normalize().resolve("repo");
        return Files.isDirectory(legacy) ? legacy : null;
    }

    /**
     * 读本次提交的流水线定义。
     *
     * <p>"提交里没有 ci/pipeline.yml" 和"YAML 解析不出来"都是<b>确定性失败</b>，不是引擎异常：
     * 必须给出用户能照着做的指引，否则界面上只会剩一句"无法读取某路径"。
     *
     * @return 定义；失败时返回 {@code null}（已登记对应的失败 stage）
     */
    private PipelineDefinition loadDefinition(Path runDir, RunState state) {
        if (!Files.isRegularFile(runDir.resolve(PIPELINE_FILE))) {
            String message = noPipelineMessage(state.projectId, state.gitSha);
            state.log.append('[').append(E_NO_PIPELINE).append("] ").append(message).append('\n');
            state.stages.add(StageResult.failed("pipeline-def", 1, E_NO_PIPELINE, message, 0));
            state.status = StageStatus.FAILED;
            return null;
        }
        try {
            return PipelineDefinition.load(runDir);
        } catch (IllegalArgumentException e) {
            String message = "ci/pipeline.yml 有语法/结构错误，无法执行：" + e.getMessage()
                    + "。修好并提交后再运行（stage 只能引用白名单动作，见《流水线执行器契约》）。";
            state.log.append('[').append(E_BAD_PIPELINE).append("] ").append(message).append('\n');
            state.stages.add(StageResult.failed("pipeline-def", 1, E_BAD_PIPELINE, message, 0));
            state.status = StageStatus.FAILED;
            return null;
        }
    }

    /** 该提交里是否有流水线定义（只看存在性，不解析）。 */
    private boolean hasPipelineAt(Path repo, String sha) {
        ActionResult r = host.run(repo, spec("pipeline-check", List.of(
                List.of("git", "cat-file", "-e", sha + ":" + PIPELINE_FILE))), () -> false);
        return r.result().status() == StageStatus.SUCCEEDED;
    }

    private String noPipelineMessage(String projectId) {
        return noPipelineMessage(projectId, null);
    }

    /**
     * 缺 ci/pipeline.yml 的可执行指引。
     *
     * <p>两种成因分开说：工作区已生成但没提交，和仓库压根没有 ci/——
     * 用户不用再猜自己踩的是哪一种。
     */
    private String noPipelineMessage(String projectId, String sha) {
        StringBuilder sb = new StringBuilder();
        if (sha != null && !sha.isBlank()) {
            sb.append("提交 ").append(shortSha(sha)).append(" 里没有 ").append(PIPELINE_FILE);
        } else {
            sb.append("仓库里没有 ").append(PIPELINE_FILE);
        }
        sb.append("。流水线定义随代码版本化（人写、可 review），平台不猜构建命令。");
        Path work = repoLocator == null ? null : repoLocator.repoDir(projectId).orElse(null);
        if (work != null && Files.isRegularFile(work.resolve(PIPELINE_FILE))) {
            sb.append("工作区已经有该文件但未提交——构建只读提交内容，请先提交")
                    .append("（git add ci && git commit），再重新运行。");
        } else {
            sb.append("请到「项目」页点「生成流水线脚本」，按仓库结构生成 ci/pipeline.yml 与 ")
                    .append("ci/actions.yml，提交后重新运行。");
        }
        return sb.toString();
    }

    private String shortSha(String sha) {
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    @Override
    public String trigger(String projectId, String pipelineId, Map<String, String> params) {
        List<String> available = pipelines(projectId);
        if (available.isEmpty()) {
            throw new IllegalArgumentException(noPipelineMessage(projectId));
        }
        if (!available.contains(pipelineId)) {
            throw new IllegalArgumentException("流水线不在白名单：" + pipelineId
                    + "（可用：" + String.join(", ", available) + "）");
        }
        String sha = params.get("gitSha");
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("gitSha 非法（需 7-40 位小写十六进制）");
        }
        // 触发前先确认该提交里确实有流水线定义：否则会先落一条必然失败的构建历史，
        // 用户看到的是"跑完才知道缺文件"。这里直接 400，把原因说清楚。
        Path repo = repoLocator == null ? null : repoLocator.repoDir(projectId).orElse(null);
        if (repo != null && Files.isDirectory(repo) && !hasPipelineAt(repo, sha)) {
            throw new IllegalArgumentException(noPipelineMessage(projectId, sha));
        }
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        RunState state = new RunState(runId, projectId, pipelineId, sha);
        String deployTarget = params.get("deployTarget");
        if (deployTarget != null && !deployTarget.isBlank()) {
            state.deployTargetOverride = deployTarget.trim().toLowerCase();
        }
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

    /**
     * 增量日志：在未脱敏缓冲上直接按 offset 切片，再对新增片段脱敏。
     *
     * <p>对单段做脱敏可保证 offset 语义稳定（每次全量脱敏后再切会因替换长度变化而错位）。
     */
    @Override
    public LogSlice logFrom(String runId, int offset) {
        RunState s = runs.get(runId);
        String raw = s != null
                ? s.log.toString()
                : history.list().stream()
                        .filter(h -> h.runId().equals(runId))
                        .findFirst().map(RunSnapshot::log).orElse("");
        int from = Math.min(Math.max(0, offset), raw.length());
        String added = ProcessExecutor.redact(raw.substring(from));
        // 片段脱敏后长度可能变化，end 用「原始已读位置」表达，前端只用 offset 续传
        return new LogSlice(from, raw.length(), added);
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
        // v2 直接模式：仓库位置与 worktree 父目录由 locator 决定
        // （DIRECT = 用户目录/.agent/worktrees；MANAGED/旧布局 = 平台 workspaces/<id>）
        Path bare;
        Path wtParent;
        if (repoLocator != null) {
            bare = repoLocator.repoDir(state.projectId).orElseThrow(
                    () -> new IllegalStateException("项目仓库不存在（projectId=" + state.projectId
                            + "），请先通过「接入项目」完成登记"));
            wtParent = repoLocator.worktreeRoot(state.projectId).orElseThrow();
            if (repoLocator.modeOf(state.projectId) == top.rongx.agent.domain.port.ProjectRepoLocator.StorageMode.DIRECT) {
                // DIRECT：构建前同步（REMOTE fetch 远端；LOCAL 无远端则内部 no-op）
                try {
                    repoLocator.sync(state.projectId);
                } catch (Exception e) {
                    state.log.append("[sync] 同步失败（继续用本地现状）：").append(e.getMessage()).append('\n');
                }
            }
        } else {
            Path projectDir = workspaceRoot.resolve(state.projectId).toAbsolutePath().normalize();
            bare = projectDir.resolve("git");
            wtParent = projectDir.resolve("worktrees");
        }
        Path runDir = wtParent.resolve("wt-build-" + state.runId);
        PipelineDefinition definition = null;
        boolean worktreeCreated = false;

        try {
            if (!Files.isDirectory(bare.resolve(".git"))
                    && !Files.isDirectory(bare.resolve("objects"))) {
                throw new IllegalStateException("项目仓库不存在：" + bare
                        + "（请先通过「接入项目」完成登记）");
            }
            // stage 1：从 bare 检出指定提交到独立 worktree
            checkout(bare, runDir, state);
            worktreeCreated = true;
            if (state.cancelled) {
                state.status = StageStatus.CANCELLED;
                return;
            }
            definition = loadDefinition(runDir, state);
            if (definition == null) {
                return; // 定义缺失/非法：已登记失败 stage（E-220 / E-221），无需再跑任何动作
            }
            if ("verify".equals(state.pipelineId)) {
                // verify = 构建与镜像，不做部署/健康检查（相当于 Jenkins 的 PR 检查）
                // 注意：没有 Dockerfile 时跳过 image stage —— 否则 verify 会因"没有镜像配方"而红，
                // 这属于仓库不具备镜像能力，不是代码问题。
                boolean hasDockerfile = Files.isRegularFile(runDir.resolve("Dockerfile"));
                definition = new PipelineDefinition(definition.name(),
                        definition.stages().stream()
                                .filter(s -> !"health".equals(s.type()))
                                .filter(s -> !"deploy".equals(s.type()))
                                .filter(s -> !"rollback".equals(s.type()))
                                .filter(s -> !"stack_deploy".equals(s.action()))
                                .filter(s -> hasDockerfile || !"image_package".equals(s.action()))
                                .toList(),
                        List.of(), null);
            }

            String tag = imagePrefix + "-" + state.gitSha.substring(0, 7);
            Map<String, String> vars = Map.of(
                    "gitSha", state.gitSha,
                    "gitSha7", state.gitSha.substring(0, 7),
                    "tag", tag);
            state.definition = definition;

            // E-508：部署配置必须与默认分支一致（防"改 YAML 即发布"绕过 review）
            if (!deployGuard(bare, runDir, definition, state)) {
                state.stages.add(StageResult.failed("deploy-guard", 1, "E-508",
                        "部署配置与默认分支不一致：请先把 ci/pipeline.yml 的 deploy 段合并到默认分支",
                        elapsed(0)));
                runOnFailure(state, definition, runDir, vars);
                state.status = StageStatus.FAILED;
                return;
            }

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
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            state.log.append("引擎异常: ").append(why).append('\n');
            state.status = StageStatus.FAILED;
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", why, 0));
            if (definition != null) {
                runOnFailure(state, definition, runDir, Map.of());
            }
        } finally {
            // 产物策略：成功的构建保留 worktree（产物文件可被下载/审计）；
            // 失败/取消的构建立即清理（无保留价值，磁盘优先）。
            // 泄漏兜底：GitWorktreeManager.prune + 用户可手动清理 wt-build-*。
            if (worktreeCreated) {
                history.save(state.snapshot());
                if (state.status != StageStatus.SUCCEEDED) {
                    cleanupWorktree(bare, runDir, state);
                }
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
            if ("deploy".equals(stage.type())) {
                return deploy(state, runDir, start, stage.id(), false);
            }
            if ("rollback".equals(stage.type())) {
                return deploy(state, runDir, start, stage.id(), true);
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

    /**
     * 部署配置受审校验（E-508）：本次运行的 {@code ci/pipeline.yml} 必须与默认分支一致。
     *
     * <p>为什么用 blob 哈希而不是解析 YAML 比对：日志管道会截断尾部（tail），
     * 解析可能因截断得到 null，从而"看起来一致"——那比不校验更危险。
     * 哈希对比是确定性的：任何未合并的流水线改动都会被拦住。
     *
     * <p>无法读取默认分支（无 main/master、文件缺失）时**放行并记录告警**：
     * 这是可用性取舍（新仓库可能还没有默认分支），日志留痕便于审计。
     */
    private boolean deployGuard(Path bare, Path runDir, PipelineDefinition definition, RunState state) {
        if (definition.deploy() == null) {
            return true; // 纯构建流水线不受约束
        }
        // 默认分支：先问 bare 的 HEAD 指向哪条分支，再退回 main/master
        List<String> branches = defaultBranches(bare);
        for (String branch : branches) {
            ActionResult reviewed = host.run(bare, spec("deploy-guard", List.of(
                    List.of("git", "rev-parse", "--verify", branch + ":ci/pipeline.yml"))), () -> false);
            if (reviewed.result().status() != StageStatus.SUCCEEDED) {
                continue;
            }
            String reviewedHash = reviewed.logTail().trim();
            ActionResult current = host.run(runDir, spec("deploy-guard", List.of(
                    List.of("git", "hash-object", "ci/pipeline.yml"))), () -> false);
            if (current.result().status() != StageStatus.SUCCEEDED) {
                state.log.append("[guard] 无法计算本次 pipeline.yml 哈希，跳过校验\n");
                return true;
            }
            String currentHash = current.logTail().trim();
            if (reviewedHash.equals(currentHash)) {
                state.log.append("[guard] deploy 配置与默认分支(").append(branch).append(")一致\n");
                return true;
            }
            state.log.append("[guard] 默认分支(").append(branch).append(") blob=")
                    .append(reviewedHash).append(" 本次 blob=").append(currentHash).append('\n');
            return false;
        }
        state.log.append("[guard] 无法读取默认分支的 ci/pipeline.yml（尝试：")
                .append(String.join(",", branches)).append("），跳过部署配置校验\n");
        return true;
    }

    /**
     * 平台部署（方案 C）：读 pipeline.yml 的 {@code deploy:} 段 + 本次运行的 target 覆盖，
     * 交给部署器执行；成功时登记 {@code deployment} 产物（endpoint 即证据）。
     *
     * @param rollback true = 回滚到上一版镜像（lastGood）
     */
    private boolean deploy(RunState state, Path runDir, long start, String stageId, boolean rollback) {
        PipelineDefinition.DeploySpec spec = state.definition == null ? null : state.definition.deploy();
        if (spec == null) {
            state.stages.add(StageResult.failed(stageId, 1, "E-506",
                    "pipeline.yml 缺少 deploy 段（target/port），无法判断部署目标", elapsed(start)));
            return false;
        }
        String desired = state.deployTargetOverride != null && !state.deployTargetOverride.isBlank()
                ? state.deployTargetOverride : spec.target();
        DeployTarget target = DeployTarget.fromWire(desired).orElse(null);
        if (target == null) {
            state.stages.add(StageResult.failed(stageId, 1, "E-506",
                    "未知部署目标：" + desired + "（可选 docker / host / server）", elapsed(start)));
            return false;
        }

        String currentImage = state.artifacts.stream()
                .filter(a -> "image".equals(a.kind()))
                .reduce((first, second) -> second)
                .map(ArtifactRef::ref)
                .orElse(null);
        String previous = lastGood.get(state.projectId);
        String image = rollback ? previous : currentImage;
        if (rollback && (image == null || image.isBlank())) {
            state.stages.add(StageResult.failed(stageId, 1, "E-507",
                    "没有可回滚的上一版（本项目尚无成功发布记录）", elapsed(start)));
            return false;
        }

        DeployRequest request = new DeployRequest(
                state.projectId,
                state.runId,
                state.gitSha,
                image,
                spec.artifact(),
                null,
                runDir.toAbsolutePath().toString(),
                target,
                spec.port(),
                spec.containerPort(),
                spec.healthPath(),
                previous,
                rollback);

        DeployResult result = deployers.deploy(request);
        String label = stageId;
        state.log.append('[').append(label).append("] ").append(target.wireName())
                .append(" → ").append(result.endpoint()).append('\n')
                .append(result.log()).append('\n');

        if (!result.success()) {
            state.stages.add(StageResult.failed(label, 1, result.errorCode(),
                    result.summary(), elapsed(start)));
            return false;
        }
        state.deployTargetUsed = target.wireName();
        List<ArtifactRef> artifacts = List.of(new ArtifactRef("deployment",
                result.endpoint(), null, 0,
                Map.of("target", target.wireName(),
                        "resource", result.resource() == null ? "" : result.resource())));
        state.stages.add(StageResult.success(label, 0, artifacts, elapsed(start)));
        state.artifacts.addAll(artifacts);
        return true;
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
            if ("rollback".equals(stage.type())) {
                // 平台回滚：用 lastGood 重新部署上一版，而不是执行仓库脚本
                deploy(state, runDir, System.nanoTime(), stage.id(), true);
                continue;
            }
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
        /** 本次运行的部署目标覆盖（触发参数）；null = 用 pipeline.yml 的 deploy.target。 */
        volatile String deployTargetOverride;
        /** 本次运行已生效的部署目标（回滚复用）。 */
        volatile String deployTargetUsed;
        /** 本次运行加载的流水线定义（deploy 段从这里读）。 */
        volatile PipelineDefinition definition;

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
