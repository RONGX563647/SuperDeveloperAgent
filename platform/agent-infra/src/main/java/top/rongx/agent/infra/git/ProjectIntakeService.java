package top.rongx.agent.infra.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;
import top.rongx.agent.infra.persistence.IntakeProjectStore;
import top.rongx.agent.infra.scaffold.RecipeScaffolder;
import top.rongx.agent.domain.error.IntakeException;

/**
 * 项目接入服务（v2）：本地收编 / 远程 clone + 配方自检 + 状态机。
 *
 * <p>状态：VALIDATING → LINKING → RECIPE_CHECK → REGISTERED / INTAKE_FAILED。
 * 长任务异步执行（虚拟线程），HTTP 只提交任务与查询状态。
 * 并发防护：同一 projectId 同时只允许一个 intake（R4-b）。
 */
@Service
public class ProjectIntakeService {

    private static final Logger log = LoggerFactory.getLogger(ProjectIntakeService.class);
    /** projectId 白名单：大写字母开头，大写字母/数字/下划线，2-16 位。 */
    private static final String ID_PATTERN = "[A-Z][A-Z0-9_]{1,15}";

    /** 平台内置动作 id（报告按这个顺序展示，保持稳定）。 */
    private static final List<String> KNOWN_ACTIONS = List.of(
            "backend_build", "backend_test", "frontend_build", "frontend_test", "image_package");

    /** Dockerfile 探测顺序：与 RecipeScaffolder / ensure_dockerfile 一致，根目录优先。 */
    private static final List<String> DOCKERFILE_DIRS = List.of(
            "", "backend", "frontend", "deploy", "docker");

    /** 镜像阶段判定与执行面 {@code isImageStage} 对齐：动作 image_package 或阶段类型 image。 */
    private static final Pattern IMAGE_ACTION =
            Pattern.compile("(?m)\\baction\\s*:\\s*['\"]?image_package\\b");
    private static final Pattern IMAGE_TYPE =
            Pattern.compile("(?m)\\btype\\s*:\\s*['\"]?image\\b");
    /** 兼容 block style（{@code target: docker}）与 inline flow map（{@code deploy: {target: docker}}）。 */
    private static final Pattern TARGET_DOCKER =
            Pattern.compile("(?mi)\\btarget\\s*:\\s*['\"]?docker['\"]?(?=\\s|[,}]|$)");

    private final GitWorktreeManager git;
    private final Path workspaceRoot;
    private final IntakeProjectStore store;
    /** 流水线脚本生成器：接入时按仓库结构自动产出定义（存平台侧，不写用户仓库）。 */
    private final ProjectScaffolder scaffolder;
    /** 平台侧流水线定义存储。 */
    private final top.rongx.agent.domain.port.PipelineDefStore defStore;
    /** 接入状态表（内存态；REGISTERED 后落 SQLite）。 */
    private final Map<String, IntakeState> intakes = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectIntakeService(GitWorktreeManager git,
                                IntakeProjectStore store,
                                @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                                ProjectScaffolder scaffolder,
                                top.rongx.agent.domain.port.PipelineDefStore defStore) {
        this.git = git;
        this.store = store;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.scaffolder = scaffolder;
        this.defStore = defStore;
    }

    /** 兼容旧构造（测试/手工装配）：使用默认脚手架实现 + 按工作区根落盘。 */
    public ProjectIntakeService(GitWorktreeManager git,
                                IntakeProjectStore store,
                                String workspaceRoot) {
        this(git, store, workspaceRoot, new RecipeScaffolder(RecipeScaffolder.DEFAULT_NODE_IMAGE),
                new top.rongx.agent.infra.pipeline.FilePipelineDefStore(workspaceRoot));
    }

    /** 兼容旧构造：自定义脚手架，定义仍落工作区根。 */
    public ProjectIntakeService(GitWorktreeManager git,
                                IntakeProjectStore store,
                                String workspaceRoot,
                                ProjectScaffolder scaffolder) {
        this(git, store, workspaceRoot, scaffolder,
                new top.rongx.agent.infra.pipeline.FilePipelineDefStore(workspaceRoot));
    }

    /** 一次接入任务的状态。 */
    public record IntakeState(String projectId, String phase, String message,
                              Map<String, Object> report, long updatedAt) {
    }

    public record IntakeRequest(String projectId, String localPath, String repoUrl,
                                String type, boolean gitInitIfMissing,
                                /** DIRECT（默认，仓库留在用户目录）/ MANAGED（平台托管 bare）。 */
                                String storageMode,
                                /** DIRECT/REMOTE 时：clone 到用户选择的本地目录。 */
                                String cloneTargetDir,
                                /** HTTPS 凭据（由服务端按仓库 URL 匹配，前端不传）。 */
                                String gitUsername,
                                String gitToken) {
        /** 兼容旧构造（测试用）。 */
        public IntakeRequest(String projectId, String localPath, String repoUrl,
                             String type, boolean gitInitIfMissing) {
            this(projectId, localPath, repoUrl, type, gitInitIfMissing, "DIRECT", null, null, null);
        }

        public IntakeRequest(String projectId, String localPath, String repoUrl,
                             String type, boolean gitInitIfMissing,
                             String storageMode, String cloneTargetDir) {
            this(projectId, localPath, repoUrl, type, gitInitIfMissing,
                    storageMode, cloneTargetDir, null, null);
        }

        /** git 凭据（无 token 时为 null）。 */
        public GitWorktreeManager.GitAuth auth() {
            return gitToken == null || gitToken.isBlank()
                    ? null : new GitWorktreeManager.GitAuth(gitUsername, gitToken);
        }
    }

    // ---------------------------------------------------------------- 校验

    /** 表单级校验（同步，供前端失焦调用）。返回错误消息，null 为通过。 */
    public String validate(IntakeRequest req) {
        if (req.projectId() == null || !req.projectId().matches(ID_PATTERN)) {
            return "项目 ID 必须是大写字母开头、2-16 位的大写字母/数字/下划线";
        }
        if (req.localPath() != null && !req.localPath().isBlank()) {
            Path p = Path.of(req.localPath());
            if (!Files.isDirectory(p)) {
                return "目录不存在：" + req.localPath();
            }
            return null;
        }
        if (req.repoUrl() != null && !req.repoUrl().isBlank()) {
            if (!req.repoUrl().startsWith("https://") && !req.repoUrl().startsWith("http://")) {
                return "仓库 URL 仅支持 http(s)";
            }
            return null;
        }
        return "localPath 与 repoUrl 必须二选一";
    }

    // ---------------------------------------------------------------- 提交接入任务

    /** 提交接入（异步）。返回当前状态；重复接入同一项目抛错。 */
    // @handbook infra-project-intake/submit | 项目接入异步入口与状态机
    public IntakeState submit(IntakeRequest req) {
        String err = validate(req);
        if (err != null) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, err);
        }
        String id = req.projectId();
        IntakeState existing = intakes.get(id);
        // 只有"正在跑"的接入才拦截；REGISTERED / INTAKE_FAILED 都允许重提交
        // （FAILED=重试；REGISTERED=重新收编，覆盖语义）
        if (existing != null && isRunning(existing.phase())) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "项目 " + id + " 正在接入中（" + existing.phase() + "）");
        }
        IntakeState start = new IntakeState(id, "VALIDATING", "校验中", Map.of(), System.currentTimeMillis());
        intakes.put(id, start);
        Thread.ofVirtual().start(() -> runIntake(req));
        return start;
    }

    /** 状态查询。 */
    public IntakeState status(String projectId) {
        IntakeState s = intakes.get(projectId);
        if (s == null) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "没有 " + projectId + " 的接入记录");
        }
        return s;
    }

    /** 是否属于"任务还在跑"的 phase（拦截重复提交）。 */
    private boolean isRunning(String phase) {
        return "VALIDATING".equals(phase) || "LINKING".equals(phase) || "RECIPE_CHECK".equals(phase);
    }

    /** 登记到 SQLite（重启恢复）；DIRECT 模式仓库留在用户目录。 */
    private void persistProject(IntakeRequest req, String head, String storageMode) {
        String id = req.projectId();
        boolean local = req.localPath() != null && !req.localPath().isBlank();
        // DIRECT/REMOTE 的 localPath = clone 目标目录
        String localPath = local ? req.localPath()
                : ("DIRECT".equals(storageMode) ? req.cloneTargetDir() : null);
        // 本地目录接入：从仓库自身的 origin 回填 repoUrl，避免 project_info 工具读到
        // null 而误报「未绑定仓库」。UI 之所以能显示，是因为它走 git remote 实时探测；
        // 这里把同一份事实落到登记字段，消除两个数据源的分裂。纯本地仓库无 origin 时
        // 退回本地路径（标注在 project_info 的展示层），不写死 null。
        String repoUrl = local ? localOriginUrl(localPath) : req.repoUrl();
        IntakeProjectStore.IntakeProject p = new IntakeProjectStore.IntakeProject(
                id,
                id,
                repoUrl,
                local ? "local" : "gitee",
                req.type() == null || req.type().isBlank() ? "generic-java" : req.type(),
                8100, 8199,
                local ? "LOCAL" : "REMOTE",
                localPath,
                storageMode);
        // 三个环境都登记：dev 走本地服务（端口由 LocalServiceManager 按项目 hash 动态分配）。
        // 不再登记「应用访问地址」：生效端口一律由 EffectiveEndpointResolver 从租约/流水线解析，
        // 静态地址既对不齐又无人消费（见 V7__drop_project_env_app_url）。只登记 composeDir 备注。
        store.upsert(p, List.of(
                new top.rongx.agent.domain.project.ProjectEnv(id, "dev", ""),
                new top.rongx.agent.domain.project.ProjectEnv(id, "test", "/opt/apps/" + id.toLowerCase() + "/test"),
                new top.rongx.agent.domain.project.ProjectEnv(id, "prod", "/opt/apps/" + id.toLowerCase() + "/prod")));
    }

    /** 读取本地仓库的 origin URL；无 origin（纯本地仓库）时返回 null。 */
    private String localOriginUrl(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return null;
        }
        try {
            return git.remotes(java.nio.file.Path.of(localPath)).stream()
                    .filter(r -> "origin".equalsIgnoreCase(r.name()))
                    .map(GitWorktreeManager.RemoteInfo::url)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.debug("读取本地仓库 {} 的 origin 失败，repoUrl 留空：{}", localPath, e.getMessage());
            return null;
        }
    }

    private void update(String projectId, String phase, String message, Map<String, Object> report) {
        intakes.put(projectId, new IntakeState(projectId, phase, message,
                report == null ? Map.of() : report, System.currentTimeMillis()));
    }

    private void runIntake(IntakeRequest req) {
        String id = req.projectId();
        try {
            boolean direct = req.storageMode() == null || req.storageMode().isBlank()
                    || "DIRECT".equalsIgnoreCase(req.storageMode());
            // 1. LINKING：直接登记 / clone 到指定目录（DIRECT）或平台托管（MANAGED）
            String head;
            if (req.localPath() != null && !req.localPath().isBlank()) {
                if (direct) {
                    // v2 直接模式：仓库留在用户目录，不收编；wt-verify 也建在用户目录下
                    update(id, "LINKING", "正在登记本地目录（就地模式）…", null);
                    Path local = Path.of(req.localPath()).toAbsolutePath().normalize();
                    if (!Files.isDirectory(local.resolve(".git"))) {
                        if (!req.gitInitIfMissing()) {
                            throw new GitWorktreeManager.GitIntakeException(
                                    "目录不是 git 仓库（无 .git）：" + local + "；如需自动 git init 请勾选确认");
                        }
                        git.initBareless(local);
                    }
                    head = git.revParse(local, "HEAD");
                    // wt-verify 用 worktree 挂在用户目录旁（.agent/worktrees/wt-verify）
                    git.addWorktreeAt(local, "wt-verify", head);
                } else {
                    update(id, "LINKING", "正在收编本地目录（托管模式）…", null);
                    head = git.linkLocal(id, Path.of(req.localPath()), req.gitInitIfMissing());
                    git.addWorktree(id, "wt-verify", head);
                }
            } else {
                if (direct) {
                    // DIRECT/REMOTE：clone 到用户选择的目录
                    update(id, "LINKING", "正在 clone 到 " + req.cloneTargetDir() + "…", null);
                    Path target = Path.of(req.cloneTargetDir());
                    git.cloneTo(req.repoUrl(), target, req.auth());
                    head = git.revParse(target, "HEAD");
                    git.addWorktreeAt(target, "wt-verify", head);
                } else {
                    update(id, "LINKING", "正在 clone 远程仓库（托管模式）…", null);
                    git.cloneRemote(id, req.repoUrl(), req.auth());
                    head = git.revParse(git.bareDir(id), "HEAD");
                    git.addWorktree(id, "wt-verify", head);
                }
            }

            // 2. RECIPE_CHECK：在 wt-verify（或 DIRECT 的用户目录本身）上做配方自检
            update(id, "RECIPE_CHECK", "正在检查动作配方与流水线定义…", null);
            Path checkRoot = direct
                    ? Path.of(req.localPath() != null ? req.localPath() : req.cloneTargetDir())
                    : git.verifyWorktree(id);
            Map<String, Object> report = recipeCheck(checkRoot);

            // 2.5 自动生成流水线定义：**存平台侧**（<工作区>/<id>/ci/），不写用户仓库。
            //     仓库里已有 ci/pipeline.yml 时以仓库为准（随代码 review），不再生成平台副本，
            //     避免"报告说仓库覆盖、实际却在用平台定义"的错位。
            boolean repoHasPipeline = Files.isRegularFile(checkRoot.resolve("ci/pipeline.yml"));
            // 平台侧已有定义：自动生成的（带脚手架头）允许随重新接入刷新；手工写的保留，避免覆盖人的改动
            boolean autoGeneratedDef = defStore.read(id)
                    .map(text -> text.startsWith("# 由平台脚手架生成"))
                    .orElse(false);
            String generatedNote = "";
            if (repoHasPipeline) {
                generatedNote = "（使用仓库 ci/pipeline.yml，随代码 review）";
            } else if (!defStore.exists(id) || autoGeneratedDef) {
                try {
                    // preferredTarget=null：由脚手架按仓库能力选目标（无 Dockerfile 的纯 Java 自动降级 HOST）
                    ScaffoldPlan plan = scaffolder.plan(id, checkRoot, null);
                    String pipelineYaml = plan.files().stream()
                            .filter(f -> f.path().equals("ci/pipeline.yml"))
                            .map(ScaffoldPlan.ScaffoldFile::content)
                            .findFirst()
                            .orElseThrow(() -> new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "脚手架没有生成 ci/pipeline.yml"));
                    String actionsYaml = plan.files().stream()
                            .filter(f -> f.path().equals("ci/actions.yml"))
                            .map(ScaffoldPlan.ScaffoldFile::content)
                            .findFirst()
                            .orElse(null);
                    boolean saved = defStore.write(id, pipelineYaml, actionsYaml);
                    report.put("scaffoldWritten", saved
                            ? List.of("platform:" + id + "/ci/pipeline.yml")
                            : List.of());
                    report.put("stack", plan.stack());
                    generatedNote = saved
                            ? "（已按仓库结构生成平台侧流水线定义，stack=" + plan.stack() + "）"
                            : "（平台侧定义保存失败，可在项目页重试）";
                    log.info("项目 {} 生成平台侧流水线定义：stack={} saved={}", id, plan.stack(), saved);
                } catch (Exception e) {
                    log.warn("项目 {} 生成流水线定义失败：{}", id, e.getMessage());
                    // 生成失败本身是阻塞项：没有定义就跑不了流水线
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> blockers = (List<Map<String, Object>>) report.get("blockers");
                    blockers.add(diag("blocker", "SCAFFOLD_FAILED",
                            "生成流水线定义失败：" + e.getMessage(),
                            "在项目页「生成流水线脚本」手动重试，或提交一份 ci/pipeline.yml"));
                    report.put("warnings", flattenWarnings(report));
                    report.put("deployable", false);
                    report.put("ready", false);
                }
            } else {
                // 平台侧是人工维护的定义：保留不覆盖，报告里说明来源
                generatedNote = "（沿用平台侧已有定义，未自动覆盖）";
            }

            // 3. 落库（SQLite：重启不丢）+ REGISTERED
            persistProject(req, head, direct ? "DIRECT" : "MANAGED");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blockers = (List<Map<String, Object>>) report.getOrDefault("blockers", List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> suggestions =
                    (List<Map<String, Object>>) report.getOrDefault("suggestions", List.of());
            boolean ready = Boolean.TRUE.equals(report.get("ready"));
            String baseMessage = !blockers.isEmpty() ? "接入完成（有阻塞项，见报告）"
                    : !suggestions.isEmpty() ? "接入完成（有建议，见报告）" : "接入完成";
            update(id, "REGISTERED", baseMessage + generatedNote, report);
            log.info("项目 {} 接入完成 head={} mode={} ready={} blockers={}", id, head,
                    direct ? "DIRECT" : "MANAGED", ready, blockers.size());
        } catch (Exception e) {
            log.warn("项目 {} 接入失败：{}", id, e.getMessage());
            update(id, "INTAKE_FAILED", String.valueOf(e.getMessage()), Map.of());
        }
    }

    // ---------------------------------------------------------------- 配方自检

    /**
     * 配方自检（在 worktree/仓库根上跑）：
     * <ul>
     *   <li>流水线：仓库 {@code ci/pipeline.yml} 优先；缺失时用 {@link ProjectScaffolder} 按仓库结构生成；</li>
     *   <li>动作配方：只检查<b>最终定义真正引用</b>的动作，不再列无关动作；</li>
     *   <li>诊断分级：blocker（跑不通）/ suggestion（建议改）/ info（说明），同一根因只出一条；</li>
     *   <li>{@code ready} 只有在没有阻塞项时才为 true，避免"报告说 Docker 不可用、结果仍显示就绪"。</li>
     * </ul>
     */
    public Map<String, Object> recipeCheck(Path repoRoot) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> diags = new ArrayList<>();

        boolean hasPipeline = Files.isRegularFile(repoRoot.resolve("ci/pipeline.yml"));
        String pipelineYaml = null;
        ScaffoldPlan plan = null;
        if (hasPipeline) {
            pipelineYaml = readText(repoRoot.resolve("ci/pipeline.yml"));
            report.put("pipeline", "ci/pipeline.yml（仓库，随代码提交）");
            diags.add(diag("info", "REPO_PIPELINE",
                    "使用仓库 ci/pipeline.yml（优先级高于平台侧自动生成的定义）", null));
        } else {
            report.put("pipeline", null);
            try {
                plan = scaffolder.plan(projectIdOf(repoRoot), repoRoot, null);
                pipelineYaml = fileContent(plan, "ci/pipeline.yml");
                report.put("stack", plan.stack());
                report.put("summary", plan.meta());
                report.put("scaffoldFiles", plan.files().stream()
                        .map(f -> Map.of("path", f.path(), "content", f.content()))
                        .toList());
                report.put("pipelineTemplate", pipelineYaml);
                Object rawDiags = plan.meta().get("diagnostics");
                if (rawDiags instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            diags.add(copyDiag(map));
                        }
                    }
                }
                diags.add(diag("info", "PIPELINE_AUTOGEN",
                        "仓库里没有 ci/pipeline.yml：使用平台按仓库结构自动生成的定义",
                        "想把定义随代码 review：在项目页选「写入仓库」后提交 ci/"));
            } catch (Exception e) {
                diags.add(diag("blocker", "SCAFFOLD_FAILED",
                        "自动生成流水线定义失败：" + e.getMessage(),
                        "在项目页「生成流水线脚本」重试，或提交一份 ci/pipeline.yml"));
            }
        }

        // 与流水线预检（ci_dry_run）同一口径：只要有镜像阶段（image_package / type: image），
        // 就要求仓库里存在 Dockerfile；不能只看 deploy.target——target=host 的定义也可能
        // 残留 image 阶段，那种定义照样会在预检被 E-501 挡住。
        String dockerfilePath = findDockerfile(repoRoot);
        boolean hasDockerfile = dockerfilePath != null;
        boolean imageStage = hasImageStage(pipelineYaml);
        boolean dockerTarget = targetIsDocker(pipelineYaml);
        report.put("dockerfile", hasDockerfile);
        report.put("dockerfilePath", dockerfilePath);
        if (!hasDockerfile && (imageStage || dockerTarget)) {
            diags.add(diag("blocker", "DOCKERFILE_MISSING",
                    (imageStage
                            ? "流水线含镜像阶段（image_package），但仓库没有 Dockerfile：镜像无法构建（"
                            : "流水线目标是 docker，但仓库没有 Dockerfile：镜像无法构建（")
                            + ErrorCode.DEPLOY_PREREQUISITE_MISSING.code() + "）",
                    "让 Agent 调用 ensure_dockerfile（base_sha=当前提交、container_port=容器端口）"
                            + "自动生成 Dockerfile 并登记待确认补丁；确认后提交新版本再发布"));
        } else if (hasDockerfile && imageStage && !dockerTarget) {
            // 定义自相矛盾：镜像阶段在 host 目标下不会产出任何被部署的东西，但预检仍会要求
            // Dockerfile。保留为建议而不是阻塞项：补齐 Dockerfile 后发布仍可跑通。
            diags.add(diag("suggestion", "IMAGE_STAGE_UNUSED",
                    "定义含 image_package 阶段，但 deploy.target 不是 docker：镜像产物不会被部署使用",
                    "确认是否需要镜像发布；不需要就删掉 image 阶段及 rollback 对镜像的依赖"));
        }

        // 动作配方：只看最终定义真正引用的动作（+ Makefile 里显式提供的配方）
        Map<String, String> actions = new LinkedHashMap<>();
        Path actionsYml = repoRoot.resolve("ci/actions.yml");
        boolean hasActionsFile = Files.isRegularFile(actionsYml);
        Set<String> referenced = usedActions(pipelineYaml);
        Set<String> candidates = new java.util.LinkedHashSet<>();
        for (String wire : KNOWN_ACTIONS) {
            if (referenced.contains(wire) || makefileHasTarget(repoRoot, wire)) {
                candidates.add(wire);
            }
        }
        for (String wire : referenced) {
            candidates.add(wire);
        }
        for (String wire : candidates) {
            boolean makeTarget = makefileHasTarget(repoRoot, wire);
            if (hasActionsFile && yamlHasAction(actionsYml, wire)) {
                actions.put(wire, "ci/actions.yml#" + wire);
            } else if (makeTarget) {
                actions.put(wire, "Makefile#" + makeTargetFor(wire));
            } else if (plan != null && planProvidesAction(plan, wire)) {
                actions.put(wire, "平台生成（接入时写入）");
            } else {
                actions.put(wire, null);
                diags.add(diag("blocker", "ACTION_MISSING",
                        "流水线引用了动作 " + wire + "，但仓库没有配方",
                        "在 ci/actions.yml 或 Makefile 里补 " + makeTargetFor(wire) + "，或删掉这个 stage"));
            }
        }
        report.put("actions", actions);

        List<Map<String, Object>> unique = dedupeByCode(diags);
        List<Map<String, Object>> blockers = filterLevel(unique, "blocker");
        List<Map<String, Object>> suggestions = filterLevel(unique, "suggestion");
        List<Map<String, Object>> info = filterLevel(unique, "info");
        boolean deployable = blockers.isEmpty();

        report.put("blockers", blockers);
        report.put("suggestions", suggestions);
        report.put("info", info);
        report.put("warnings", flattenWarnings(report));
        report.put("deployable", deployable);
        report.put("ready", deployable && pipelineYaml != null);
        return report;
    }

    /** 报告里的项目标识（目录名即可，仅用于展示）。 */
    private String projectIdOf(Path repoRoot) {
        Path name = repoRoot.getFileName();
        return name == null ? "PROJECT" : name.toString();
    }

    /** 一条诊断（与脚手架同一套字段，前端统一渲染）。 */
    private Map<String, Object> diag(String level, String code, String message, String fix) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("code", code);
        out.put("message", message);
        if (fix != null && !fix.isBlank()) {
            out.put("fix", fix);
        }
        return out;
    }

    private Map<String, Object> copyDiag(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private List<Map<String, Object>> filterLevel(List<Map<String, Object>> diags, String level) {
        return diags.stream()
                .filter(d -> level.equals(String.valueOf(d.get("level"))))
                .toList();
    }

    /** 同一 code 只保留第一条：脚手架与接入检查可能同时报同一根因（如 DOCKERFILE_MISSING）。 */
    private List<Map<String, Object>> dedupeByCode(List<Map<String, Object>> diags) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> diag : diags) {
            String code = String.valueOf(diag.getOrDefault("code", ""));
            if (!code.isBlank() && !seen.add(code)) {
                continue;
            }
            out.add(diag);
        }
        return out;
    }

    /** 兼容旧前端/旧脚本：warnings 是三个分组的扁平文本。 */
    private List<String> flattenWarnings(Map<String, Object> report) {
        List<String> out = new java.util.ArrayList<>();
        for (String key : List.of("blockers", "suggestions", "info")) {
            Object raw = report.get(key);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Object message = map.get("message");
                        if (message != null && !out.contains(String.valueOf(message))) {
                            out.add(String.valueOf(message));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 定义里引用到的 action（{@code action: backend_build} 行），保持出现顺序。 */
    private java.util.Set<String> usedActions(String pipelineYaml) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (pipelineYaml == null) {
            return out;
        }
        for (String line : pipelineYaml.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("action:")) {
                String wire = trimmed.substring("action:".length()).trim();
                if (wire.matches("[A-Za-z_]+")) {
                    out.add(wire);
                }
            }
        }
        return out;
    }

    /** 已存在的 Dockerfile（仓库相对路径）；没有返回 null。探测范围与脚手架一致。 */
    private String findDockerfile(Path repoRoot) {
        for (String dir : DOCKERFILE_DIRS) {
            Path base = dir.isEmpty() ? repoRoot : repoRoot.resolve(dir);
            if (Files.isRegularFile(base.resolve("Dockerfile"))) {
                return dir.isEmpty() ? "Dockerfile" : dir + "/Dockerfile";
            }
        }
        return null;
    }

    /** 定义里是否有镜像阶段（与执行面 isImageStage 同口径：image_package 动作或 image 类型）。 */
    private boolean hasImageStage(String pipelineYaml) {
        if (pipelineYaml == null) {
            return false;
        }
        return IMAGE_ACTION.matcher(pipelineYaml).find() || IMAGE_TYPE.matcher(pipelineYaml).find();
    }

    private boolean targetIsDocker(String pipelineYaml) {
        return pipelineYaml != null && TARGET_DOCKER.matcher(pipelineYaml).find();
    }

    private boolean planProvidesAction(ScaffoldPlan plan, String wire) {
        String actionsYaml = fileContent(plan, "ci/actions.yml");
        return actionsYaml.contains("\n  " + wire + ":") || actionsYaml.startsWith("  " + wire + ":");
    }

    private String fileContent(ScaffoldPlan plan, String path) {
        return plan.files().stream()
                .filter(f -> f.path().equals(path))
                .map(ScaffoldPlan.ScaffoldFile::content)
                .findFirst()
                .orElse("");
    }

    private String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return "";
        }
    }

    /** 轻量 YAML 检查：actions: 下是否有该 key（不引库，缩进两层判断）。 */
    private boolean yamlHasAction(Path yml, String wire) {
        try {
            boolean inActions = false;
            for (String line : Files.readAllLines(yml)) {
                if (line.startsWith("actions:")) {
                    inActions = true;
                    continue;
                }
                if (inActions) {
                    if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                        inActions = false; // 离开 actions 块
                    } else if (line.strip().startsWith(wire + ":")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean makefileHasTarget(Path repoRoot, String wire) {
        Path makefile = repoRoot.resolve("Makefile");
        if (!Files.isRegularFile(makefile)) {
            return false;
        }
        String target = makeTargetFor(wire);
        try {
            return Files.readAllLines(makefile).stream()
                    .anyMatch(l -> !l.isBlank() && !l.startsWith("\t") && !l.startsWith("#")
                            && l.strip().startsWith(target + ":"));
        } catch (Exception e) {
            return false;
        }
    }

    private String makeTargetFor(String wire) {
        return wire.replace('_', '-');
    }
}
