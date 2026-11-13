package top.rongx.agent.gateway.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;
import top.rongx.agent.infra.persistence.IntakeProjectStore;

/**
 * 流水线脚手架接口：按仓库结构生成流水线定义。
 *
 * <p>默认落到<b>平台侧</b>（{@code <工作区>/<id>/ci/}）——生成即可运行，不需要往用户仓库里提交文件；
 * 想把定义随代码 review 的团队可以选 {@code mode=repo}，写进仓库 {@code ci/} 后自行提交。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectScaffoldController {

    private static final Logger log = LoggerFactory.getLogger(ProjectScaffoldController.class);

    private final ProjectScaffolder scaffolder;
    private final ProjectRepoLocator repoLocator;
    private final PipelineDefStore defStore;
    private final top.rongx.agent.domain.port.RecipeStore recipes;
    private final ProjectRepository projects;
    private final IntakeProjectStore intakeStore;

    public ProjectScaffoldController(ProjectScaffolder scaffolder, ProjectRepoLocator repoLocator,
                                     PipelineDefStore defStore,
                                     top.rongx.agent.domain.port.RecipeStore recipes,
                                     ProjectRepository projects,
                                     IntakeProjectStore intakeStore) {
        this.scaffolder = scaffolder;
        this.repoLocator = repoLocator;
        this.defStore = defStore;
        this.recipes = recipes;
        this.projects = projects;
        this.intakeStore = intakeStore;
    }

    /**
     * 一键准备：把「发布到测试」前能自动补齐的配置补齐（幂等，可重复调用）。
     *
     * <p>只做两件事，且都不覆盖用户已有配置：
     * <ol>
     *   <li>补登记目标环境（历史项目可能只登记了 dev/prod，导致选不到 test）；</li>
     *   <li>仓库与平台都没有流水线定义时，按仓库结构生成平台侧 {@code ci/}（生成即可运行）。</li>
     * </ol>
     * 返回 {@code prepared}（本次补了什么）与 {@code notes}（仍需人工处理的项）。
     * 是否真的可以发布仍以 {@code GET /{id}/readiness} 为准，前端只在 ready 时触发运行。
     */
    @PostMapping("/{id}/prepare")
    public Map<String, Object> prepare(@PathVariable String id,
                                       @RequestParam(defaultValue = "test") String env) {
        String target = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        List<Map<String, Object>> prepared = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Path repo = repoLocator.repoDir(id).filter(Files::isDirectory).orElse(null);
        if (repo == null) {
            notes.add("项目仓库尚未接入");
        }

        // 1) 环境登记
        boolean envRegistered = projects.findEnvs(id).stream()
                .anyMatch(e -> target.equals(e.env()));
        if (!envRegistered) {
            if (intakeStore.find(id).isPresent()) {
                if (intakeStore.ensureEnv(id, target)) {
                    prepared.add(preparedRow("env", "已登记 " + target + " 环境"));
                }
            } else {
                notes.add("预置项目请在项目配置里补充 " + target + " 环境");
            }
        }

        // 2) 流水线定义：仓库 ci/pipeline.yml 优先，平台侧已有则不动，都没有才生成
        boolean repoDefined = repo != null && Files.isRegularFile(repo.resolve("ci/pipeline.yml"));
        // bare 仓库没有工作树，扫描不出技术栈：交给用户先完成一次接入/检出
        boolean worktree = repo != null && Files.exists(repo.resolve(".git"));
        if (!repoDefined && !defStore.exists(id)) {
            if (repo == null) {
                notes.add("仓库不存在，无法生成流水线定义");
            } else if (!worktree) {
                notes.add("平台托管仓库还没有工作树，无法识别技术栈，请先生成流水线定义");
            } else {
                ScaffoldPlan plan = scaffolder.plan(id, repo, null);
                String pipelineYaml = fileContent(plan, "ci/pipeline.yml");
                String actionsYaml = fileContent(plan, "ci/actions.yml");
                if (defStore.write(id, pipelineYaml, actionsYaml)) {
                    prepared.add(preparedRow("pipeline",
                            "已生成平台侧流水线定义（" + plan.stack() + "）"));
                } else {
                    notes.add("流水线定义生成失败，请手动生成");
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", id);
        out.put("env", target);
        out.put("prepared", prepared);
        out.put("notes", notes);
        return out;
    }

    private static Map<String, Object> preparedRow(String key, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("detail", detail);
        return row;
    }

    /**
     * 预览：扫描仓库结构并返回将生成的文件（不写盘）。
     *
     * @param target 期望的部署目标（docker / host / server）；默认 auto = 按仓库能力自动选择
     */
    @GetMapping("/{id}/scaffold")
    public Map<String, Object> preview(@PathVariable String id,
                                       @RequestParam(defaultValue = "auto") String target) {
        Path root = readableRepo(id);
        DeployTarget deployTarget = parseTarget(target);
        ScaffoldPlan plan = scaffolder.plan(id, root, deployTarget);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", plan.projectId());
        out.put("stack", plan.stack());
        out.put("root", root.toString());
        out.put("target", plan.meta().getOrDefault("deployTarget",
                deployTarget == null ? "auto" : deployTarget.wireName()));
        out.put("meta", plan.meta());
        out.put("platformRoot", defStore.rootOf(id).toString());
        out.put("hasPlatformDefinition", defStore.exists(id));
        out.put("files", plan.files().stream()
                .map(f -> Map.of("path", f.path(), "content", f.content()))
                .toList());
        out.put("notes", plan.notes());
        return out;
    }

    /**
     * 保存：默认写平台侧（覆盖前需 overwrite=true），可选写入仓库工作树。
     *
     * @param mode      platform（默认，存平台）/ repo（写进仓库 ci/，需自行提交）
     * @param overwrite true 才覆盖已有文件（默认 false，防误吞人工配置）
     */
    @PostMapping("/{id}/scaffold")
    public Map<String, Object> apply(@PathVariable String id,
                                     @RequestParam(defaultValue = "auto") String target,
                                     @RequestParam(defaultValue = "platform") String mode,
                                     @RequestParam(defaultValue = "false") boolean overwrite) {
        Path root = readableRepo(id);
        DeployTarget deployTarget = parseTarget(target);
        ScaffoldPlan plan = scaffolder.plan(id, root, deployTarget);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean toRepo = "repo".equalsIgnoreCase(mode);

        if (toRepo) {
            List<String> written = scaffolder.apply(id, root, plan, overwrite);
            log.info("项目 {} 脚手架写入仓库：target={} 写入={}", id,
                    plan.meta().getOrDefault("deployTarget", "auto"), written);
            out.put("savedTo", "repo");
            out.put("written", written);
            out.put("skipped", plan.files().stream()
                    .map(ScaffoldPlan.ScaffoldFile::path)
                    .filter(p -> !written.contains(p))
                    .toList());
        } else {
            boolean exists = defStore.exists(id);
            if (exists && !overwrite) {
                out.put("savedTo", "platform");
                out.put("written", List.of());
                out.put("skipped", List.of("ci/pipeline.yml", "ci/actions.yml"));
                out.put("note", "平台侧已有定义，未覆盖（需要重生成请传 overwrite=true）");
            } else {
                String pipelineYaml = fileContent(plan, "ci/pipeline.yml");
                String actionsYaml = fileContent(plan, "ci/actions.yml");
                boolean saved = defStore.write(id, pipelineYaml, actionsYaml);
                if (saved && overwrite) {
                    // 旧的环境脚本是旧定义的固化产物：继续生效会让"重新生成"看起来没作用。
                    // 只失效 current 指针，历史版本仍可人工 activate 回来。
                    for (String env : List.of("dev", "test", "prod")) {
                        recipes.invalidate(id, env);
                    }
                }
                log.info("项目 {} 脚手架保存到平台：target={} saved={}", id,
                        plan.meta().getOrDefault("deployTarget", "auto"), saved);
                out.put("savedTo", "platform");
                out.put("resetRecipes", saved && overwrite);
                out.put("written", saved
                        ? List.of("platform:" + id + "/ci/pipeline.yml",
                        "platform:" + id + "/ci/actions.yml")
                        : List.of());
                out.put("skipped", List.of());
            }
        }
        out.put("stack", plan.stack());
        out.put("target", plan.meta().getOrDefault("deployTarget", "auto"));
        out.put("meta", plan.meta());
        out.put("notes", plan.notes());
        return out;
    }

    /** auto / 空 → null（按仓库能力自动选择）；未知值退回 docker，保持旧行为。 */
    private static DeployTarget parseTarget(String raw) {
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return DeployTarget.fromWire(raw).orElse(DeployTarget.DOCKER);
    }

    private static String fileContent(ScaffoldPlan plan, String path) {
        return plan.files().stream()
                .filter(f -> f.path().equals(path))
                .map(ScaffoldPlan.ScaffoldFile::content)
                .findFirst()
                .orElse("");
    }

    /** 可读的仓库根：DIRECT = 用户目录；MANAGED（bare）没有工作树，退回 verify worktree。 */
    private Path readableRepo(String projectId) {
        if (repoLocator.modeOf(projectId) == ProjectRepoLocator.StorageMode.MANAGED) {
            return repoLocator.repoDir(projectId)
                    .orElseThrow(() -> new IllegalStateException("项目仓库不存在：" + projectId));
        }
        return repoLocator.repoDir(projectId)
                .filter(Files::isDirectory)
                .orElseThrow(() -> new IllegalStateException(
                        "项目目录不存在，请先完成接入：" + projectId));
    }
}
