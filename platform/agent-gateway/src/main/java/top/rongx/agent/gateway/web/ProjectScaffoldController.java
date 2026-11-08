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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

/**
 * 流水线脚手架接口：导入项目后一键生成平台专用脚本。
 *
 * <p>流程：前端先 GET 预览（不写盘）→ 人确认 → POST 落盘（默认不覆盖已有文件）。
 * 生成的脚本写入仓库工作树，随代码 review；平台不自动提交。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectScaffoldController {

    private static final Logger log = LoggerFactory.getLogger(ProjectScaffoldController.class);

    private final ProjectScaffolder scaffolder;
    private final ProjectRepoLocator repoLocator;

    public ProjectScaffoldController(ProjectScaffolder scaffolder, ProjectRepoLocator repoLocator) {
        this.scaffolder = scaffolder;
        this.repoLocator = repoLocator;
    }

    /**
     * 预览：扫描仓库结构并返回将生成的文件（不写盘）。
     *
     * @param target 期望的部署目标（docker / host / server），默认 docker
     */
    @GetMapping("/{id}/scaffold")
    public Map<String, Object> preview(@PathVariable String id,
                                       @RequestParam(defaultValue = "docker") String target) {
        Path root = requireRepo(id);
        DeployTarget deployTarget = DeployTarget.fromWire(target).orElse(DeployTarget.DOCKER);
        ScaffoldPlan plan = scaffolder.plan(id, root, deployTarget);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", plan.projectId());
        out.put("stack", plan.stack());
        out.put("root", root.toString());
        out.put("target", deployTarget.wireName());
        out.put("files", plan.files().stream()
                .map(f -> Map.of("path", f.path(), "content", f.content()))
                .toList());
        out.put("notes", plan.notes());
        return out;
    }

    /**
     * 落盘：把预览结果写入仓库 {@code ci/}。
     *
     * @param overwrite true 才覆盖已存在文件（默认 false，防误吞人工配置）
     */
    @PostMapping("/{id}/scaffold")
    public Map<String, Object> apply(@PathVariable String id,
                                     @RequestParam(defaultValue = "docker") String target,
                                     @RequestParam(defaultValue = "false") boolean overwrite) {
        Path root = requireRepo(id);
        DeployTarget deployTarget = DeployTarget.fromWire(target).orElse(DeployTarget.DOCKER);
        ScaffoldPlan plan = scaffolder.plan(id, root, deployTarget);
        List<String> written = scaffolder.apply(id, root, plan, overwrite);
        log.info("项目 {} 脚手架落盘：target={} 写入={}", id, deployTarget.wireName(), written);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("written", written);
        out.put("skipped", plan.files().stream()
                .map(ScaffoldPlan.ScaffoldFile::path)
                .filter(p -> !written.contains(p))
                .toList());
        out.put("notes", plan.notes());
        return out;
    }

    /** 解析可写的工作树根：DIRECT = 用户目录；MANAGED（bare）没有工作树，明确拒绝。 */
    private Path requireRepo(String projectId) {
        if (repoLocator.modeOf(projectId) == ProjectRepoLocator.StorageMode.MANAGED) {
            throw new IllegalStateException(
                    "该项目为平台托管（bare 仓库），没有可写工作树；"
                            + "请改用「本地目录接入」，或在项目详情里检出工作树后再生成");
        }
        return repoLocator.repoDir(projectId)
                .filter(Files::isDirectory)
                .orElseThrow(() -> new IllegalStateException(
                        "项目目录不存在，请先完成接入：" + projectId));
    }
}
