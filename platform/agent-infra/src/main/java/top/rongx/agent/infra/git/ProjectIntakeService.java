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
    /** 接入状态表（内存态；REGISTERED 后由项目存储接管）。 */
    private final Map<String, IntakeState> intakes = new ConcurrentHashMap<>();

    public ProjectIntakeService(GitWorktreeManager git,
                                @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.git = git;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    /** 一次接入任务的状态。 */
    public record IntakeState(String projectId, String phase, String message,
                              Map<String, Object> report, long updatedAt) {
    }

    public record IntakeRequest(String projectId, String localPath, String repoUrl,
                                String type, boolean gitInitIfMissing) {
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
        if (existing != null && !"REGISTERED".equals(existing.phase()) && !"INTAKE_FAILED".equals(existing.phase())) {
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

    private void update(String projectId, String phase, String message, Map<String, Object> report) {
        intakes.put(projectId, new IntakeState(projectId, phase, message,
                report == null ? Map.of() : report, System.currentTimeMillis()));
    }

    private void runIntake(IntakeRequest req) {
        String id = req.projectId();
        try {
            // 1. LINKING：收编 / clone
            update(id, "LINKING", req.localPath() != null ? "正在收编本地目录…" : "正在 clone 远程仓库…", null);
            String head;
            if (req.localPath() != null && !req.localPath().isBlank()) {
                head = git.linkLocal(id, Path.of(req.localPath()), req.gitInitIfMissing());
            } else {
                git.cloneRemote(id, req.repoUrl());
                head = git.revParse(git.bareDir(id), "HEAD");
            }

            // 2. 建 wt-verify（自检 + 后续代码检索用）
            git.addWorktree(id, "wt-verify", head);

            // 3. RECIPE_CHECK：在 wt-verify 上做配方自检
            update(id, "RECIPE_CHECK", "正在检查动作配方与流水线定义…", null);
            Map<String, Object> report = recipeCheck(git.verifyWorktree(id));
            boolean ready = Boolean.TRUE.equals(report.get("ready"));

            // 4. REGISTERED
            update(id, "REGISTERED", ready ? "接入完成" : "接入完成（自检有警告，见报告）", report);
            log.info("项目 {} 接入完成 head={} ready={}", id, head, ready);
        } catch (Exception e) {
            log.warn("项目 {} 接入失败：{}", id, e.getMessage());
            update(id, "INTAKE_FAILED", String.valueOf(e.getMessage()), Map.of());
        }
    }

    // ---------------------------------------------------------------- 配方自检

    /**
     * 配方自检（在 worktree/仓库根上跑）：
     * 动作配方覆盖、pipeline.yml、类型必备项。产出结构化报告。
     */
    public Map<String, Object> recipeCheck(Path repoRoot) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();

        // 1. 动作配方覆盖（6 个 wireName）；仅 verify 必需项影响 ready
        Map<String, String> actions = new LinkedHashMap<>();
        boolean verifyReady = true;
        Path actionsYml = repoRoot.resolve("ci/actions.yml");
        for (String wire : List.of("backend_build", "backend_test", "frontend_build",
                "image_package", "stack_deploy", "stack_rollback")) {
            if (Files.isRegularFile(actionsYml) && yamlHasAction(actionsYml, wire)) {
                actions.put(wire, "ci/actions.yml#" + wire);
            } else if (makefileHasTarget(repoRoot, wire)) {
                actions.put(wire, "Makefile#" + makeTargetFor(wire));
            } else if ("backend_build".equals(wire) || "frontend_build".equals(wire)) {
                actions.put(wire, "内置默认（mvn / pnpm）");
            } else {
                actions.put(wire, null);
                if ("backend_build".equals(wire) || "backend_test".equals(wire)) {
                    verifyReady = false;
                    warnings.add("动作 " + wire + " 无配方：verify 流水线无法执行，请在 ci/actions.yml 声明");
                } else {
                    warnings.add("动作 " + wire + " 无配方：发布（release）流水线会跳过该步，"
                            + "需要发布时先在 ci/actions.yml 声明");
                }
            }
        }
        report.put("actions", actions);

        // 2. pipeline.yml
        boolean hasPipeline = Files.isRegularFile(repoRoot.resolve("ci/pipeline.yml"));
        report.put("pipeline", hasPipeline ? "ci/pipeline.yml" : null);
        if (!hasPipeline) {
            warnings.add("缺 ci/pipeline.yml：请在仓库按模板创建（模板见报告 pipelineTemplate 字段）");
            report.put("pipelineTemplate", """
                    name: release
                    stages:
                      - id: build
                        type: action
                        action: backend_build
                        runner: container
                      - id: test
                        type: action
                        action: backend_test
                        runner: container
                      - id: image
                        type: action
                        action: image_package
                        runner: host
                      - id: deploy
                        type: action
                        action: stack_deploy
                        runner: host
                      - id: health
                        type: health
                        url: http://127.0.0.1:PORT/healthz
                        expected: 200
                    on-failure:
                      - id: rollback
                        type: action
                        action: stack_rollback
                        runner: host
                    """);
        }

        // 3. Dockerfile（image_package 需要）
        boolean hasDockerfile = Files.isRegularFile(repoRoot.resolve("Dockerfile"));
        report.put("dockerfile", hasDockerfile);
        if (!hasDockerfile) {
            verifyReady = false;
            warnings.add("缺 Dockerfile：image_package（镜像打包）将无法执行");
        }

        boolean ready = verifyReady;
        report.put("ready", ready);
        report.put("warnings", warnings);
        return report;
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
