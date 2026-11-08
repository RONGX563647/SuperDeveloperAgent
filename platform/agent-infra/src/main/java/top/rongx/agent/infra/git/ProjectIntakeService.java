package top.rongx.agent.infra.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;
import top.rongx.agent.infra.persistence.IntakeProjectStore;
import top.rongx.agent.infra.scaffold.RecipeScaffolder;

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
        this(git, store, workspaceRoot, new RecipeScaffolder(),
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
                                String cloneTargetDir) {
        /** 兼容旧构造（测试用）。 */
        public IntakeRequest(String projectId, String localPath, String repoUrl,
                             String type, boolean gitInitIfMissing) {
            this(projectId, localPath, repoUrl, type, gitInitIfMissing, "DIRECT", null);
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
    public IntakeState submit(IntakeRequest req) {
        String err = validate(req);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        String id = req.projectId();
        IntakeState existing = intakes.get(id);
        // 只有"正在跑"的接入才拦截；REGISTERED / INTAKE_FAILED 都允许重提交
        // （FAILED=重试；REGISTERED=重新收编，覆盖语义）
        if (existing != null && isRunning(existing.phase())) {
            throw new IllegalStateException("项目 " + id + " 正在接入中（" + existing.phase() + "）");
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
            throw new IllegalArgumentException("没有 " + projectId + " 的接入记录");
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
        IntakeProjectStore.IntakeProject p = new IntakeProjectStore.IntakeProject(
                id,
                id,
                local ? null : req.repoUrl(),
                local ? "local" : "gitee",
                req.type() == null || req.type().isBlank() ? "generic-java" : req.type(),
                8100, 8199,
                local ? "LOCAL" : "REMOTE",
                localPath,
                storageMode);
        store.upsert(p, List.of(
                new top.rongx.agent.domain.project.ProjectEnv(id, "test", "http://127.0.0.1:8180", "/opt/apps/" + id.toLowerCase()),
                new top.rongx.agent.domain.project.ProjectEnv(id, "prod", "http://127.0.0.1:8181", "/opt/apps/" + id.toLowerCase())));
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
                    git.cloneTo(req.repoUrl(), target);
                    head = git.revParse(target, "HEAD");
                    git.addWorktreeAt(target, "wt-verify", head);
                } else {
                    update(id, "LINKING", "正在 clone 远程仓库（托管模式）…", null);
                    git.cloneRemote(id, req.repoUrl());
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
            boolean ready = Boolean.TRUE.equals(report.get("ready"));

            // 2.5 自动生成流水线定义：**存平台侧**（<工作区>/<id>/ci/），不写用户仓库。
            //     仓库里的 ci/pipeline.yml 仍可作为可选的覆盖，随代码 review。
            String generatedNote = "";
            if (!defStore.exists(id)) {
                try {
                    ScaffoldPlan plan = scaffolder.plan(id, checkRoot, DeployTarget.DOCKER);
                    String pipelineYaml = plan.files().stream()
                            .filter(f -> f.path().equals("ci/pipeline.yml"))
                            .map(ScaffoldPlan.ScaffoldFile::content)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("脚手架没有生成 ci/pipeline.yml"));
                    String actionsYaml = plan.files().stream()
                            .filter(f -> f.path().equals("ci/actions.yml"))
                            .map(ScaffoldPlan.ScaffoldFile::content)
                            .findFirst()
                            .orElse(null);
                    boolean saved = defStore.write(id, pipelineYaml, actionsYaml);
                    report.put("scaffoldWritten", saved
                            ? List.of("platform:" + defStore.rootOf(id).resolve("ci/pipeline.yml"))
                            : List.of());
                    report.put("stack", plan.stack());
                    generatedNote = saved
                            ? "（已按仓库结构生成平台侧流水线定义，stack=" + plan.stack() + "，可直接运行）"
                            : "（平台侧定义保存失败，可在项目页重试）";
                    log.info("项目 {} 生成平台侧流水线定义：stack={} saved={}", id, plan.stack(), saved);
                } catch (Exception e) {
                    log.warn("项目 {} 生成流水线定义失败：{}", id, e.getMessage());
                    @SuppressWarnings("unchecked")
                    List<String> warnings = (List<String>) report.get("warnings");
                    warnings.add("生成流水线定义失败：" + e.getMessage()
                            + "；可在项目页「生成流水线脚本」手动重试");
                }
            }

            // 3. 落库（SQLite：重启不丢）+ REGISTERED
            persistProject(req, head, direct ? "DIRECT" : "MANAGED");
            String baseMessage = ready ? "接入完成" : "接入完成（自检有警告，见报告）";
            update(id, "REGISTERED", baseMessage + generatedNote, report);
            log.info("项目 {} 接入完成 head={} mode={} ready={}", id, head,
                    direct ? "DIRECT" : "MANAGED", ready);
        } catch (Exception e) {
            log.warn("项目 {} 接入失败：{}", id, e.getMessage());
            update(id, "INTAKE_FAILED", String.valueOf(e.getMessage()), Map.of());
        }
    }

    // ---------------------------------------------------------------- 配方自检

    /**
     * 配方自检（在 worktree/仓库根上跑）：
     * <ul>
     *   <li>动作配方：只检查流水线会用到的构建/打包动作——deploy/rollback 已由<b>平台部署器</b>承担，
     *       不再要求仓库提供 stack_deploy 配方；</li>
     *   <li>流水线：缺失时用 {@link ProjectScaffolder} 按仓库结构生成（真实内容，不再是 PORT 占位模板）；</li>
     *   <li>Dockerfile：只影响镜像 stage，不阻塞接入。</li>
     * </ul>
     */
    public Map<String, Object> recipeCheck(Path repoRoot) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();

        // 1. 动作配方（只关心构建类；部署阶段的配方不再需要）
        Map<String, String> actions = new LinkedHashMap<>();
        Path actionsYml = repoRoot.resolve("ci/actions.yml");
        for (String wire : List.of("backend_build", "backend_test", "frontend_build", "image_package")) {
            if (Files.isRegularFile(actionsYml) && yamlHasAction(actionsYml, wire)) {
                actions.put(wire, "ci/actions.yml#" + wire);
            } else if (makefileHasTarget(repoRoot, wire)) {
                actions.put(wire, "Makefile#" + makeTargetFor(wire));
            } else if ("backend_build".equals(wire) || "frontend_build".equals(wire)) {
                actions.put(wire, "内置默认（mvn / pnpm）");
            } else {
                actions.put(wire, null);
                warnings.add("动作 " + wire + " 无配方：对应 stage 会被跳过（不阻塞接入）");
            }
        }
        report.put("actions", actions);

        // 2. 流水线：仓库里没有也没关系——平台会按仓库结构生成（存平台侧，不写仓库）
        boolean hasPipeline = Files.isRegularFile(repoRoot.resolve("ci/pipeline.yml"));
        report.put("pipeline", hasPipeline ? "ci/pipeline.yml（仓库覆盖）" : null);
        if (!hasPipeline) {
            try {
                ScaffoldPlan plan = scaffolder.plan(projectIdOf(repoRoot), repoRoot, DeployTarget.DOCKER);
                report.put("stack", plan.stack());
                report.put("scaffoldFiles", plan.files().stream()
                        .map(f -> Map.of("path", f.path(), "content", f.content()))
                        .toList());
                plan.files().stream()
                        .filter(f -> f.path().equals("ci/pipeline.yml"))
                        .findFirst()
                        .ifPresent(f -> report.put("pipelineTemplate", f.content()));
                warnings.add("仓库里没有 ci/pipeline.yml：将使用平台按仓库结构自动生成的定义"
                        + "（无需提交到仓库，也可在仓库里放一份覆盖它）");
                warnings.addAll(plan.notes());
            } catch (Exception e) {
                warnings.add("自动生成流水线定义失败：" + e.getMessage()
                        + "；可在项目页「生成流水线脚本」重试");
            }
        }

        // 3. Dockerfile（只影响镜像 stage）
        boolean hasDockerfile = Files.isRegularFile(repoRoot.resolve("Dockerfile"));
        report.put("dockerfile", hasDockerfile);
        if (!hasDockerfile) {
            warnings.add("缺 Dockerfile：DOCKER 目标无法打镜像；Java 项目可改用 HOST 目标，"
                    + "或补 Dockerfile 后重新生成脚本");
        }

        // 4. 可接入：已有流水线，或已能自动生成
        boolean ready = hasPipeline || report.containsKey("pipelineTemplate");
        report.put("ready", ready);
        report.put("warnings", warnings);
        return report;
    }

    /** 自检报告里的项目标识（目录名即可，仅用于展示）。 */
    private String projectIdOf(Path repoRoot) {
        Path name = repoRoot.getFileName();
        return name == null ? "PROJECT" : name.toString();
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
