package top.rongx.agent.gateway.web;

import java.nio.file.Files;
import java.nio.file.Path;
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
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

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

    public ProjectScaffoldController(ProjectScaffolder scaffolder, ProjectRepoLocator repoLocator,
                                     PipelineDefStore defStore) {
        this.scaffolder = scaffolder;
        this.repoLocator = repoLocator;
        this.defStore = defStore;
    }

    /**
     * 预览：扫描仓库结构并返回将生成的文件（不写盘）。
     *
     * @param target 期望的部署目标（docker / host / server），默认 docker
     */
    @GetMapping("/{id}/scaffold")
    public Map<String, Object> preview(@PathVariable String id,
                                       @RequestParam(defaultValue = "docker") String target) {
        Path root = readableRepo(id);
        DeployTarget deployTarget = DeployTarget.fromWire(target).orElse(DeployTarget.DOCKER);
        ScaffoldPlan plan = scaffolder.plan(id, root, deployTarget);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", plan.projectId());
        out.put("stack", plan.stack());
        out.put("root", root.toString());
        out.put("target", deployTarget.wireName());
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
                                     @RequestParam(defaultValue = "docker") String target,
                                     @RequestParam(defaultValue = "platform") String mode,
                                     @RequestParam(defaultValue = "false") boolean overwrite) {
        Path root = readableRepo(id);
        DeployTarget deployTarget = DeployTarget.fromWire(target).orElse(DeployTarget.DOCKER);
        ScaffoldPlan plan = scaffolder.plan(id, root, deployTarget);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean toRepo = "repo".equalsIgnoreCase(mode);

        if (toRepo) {
            List<String> written = scaffolder.apply(id, root, plan, overwrite);
            log.info("项目 {} 脚手架写入仓库：target={} 写入={}", id, deployTarget.wireName(), written);
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
                log.info("项目 {} 脚手架保存到平台：target={} saved={}", id, deployTarget.wireName(), saved);
                out.put("savedTo", "platform");
                out.put("written", saved
                        ? List.of("platform:" + defStore.rootOf(id).resolve("ci/pipeline.yml"),
                        "platform:" + defStore.rootOf(id).resolve("ci/actions.yml"))
                        : List.of());
                out.put("skipped", List.of());
            }
        }
        out.put("stack", plan.stack());
        out.put("notes", plan.notes());
        return out;
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
