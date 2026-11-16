package top.rongx.agent.gateway.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.web.DeploymentControlService.ActionResult;
import top.rongx.agent.gateway.web.DeploymentControlService.Matrix;

/**
 * 工作区「环境」视图：版本 × 环境矩阵与一键启停。
 *
 * <p>部署产物来自流水线的 Docker 容器 / HOST release 目录，不新增数据表；
 * 启停只作用于项目应用本身，不触碰中间件资源。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{projectId}/deployments")
public class WorkspaceDeploymentController {

    private final DeploymentControlService deployments;
    private final CurrentUser currentUser;

    public WorkspaceDeploymentController(DeploymentControlService deployments,
                                         CurrentUser currentUser) {
        this.deployments = deployments;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Matrix matrix(@PathVariable String projectId) {
        currentUser.requireUserId();
        return deployments.matrix(projectId);
    }

    @PostMapping("/{env}/start")
    public ActionResult start(@PathVariable String projectId, @PathVariable String env,
                              @RequestBody(required = false) VersionRequest request) {
        currentUser.requireUserId();
        return deployments.start(projectId, env, request == null ? null : request.version());
    }

    @PostMapping("/{env}/stop")
    public ActionResult stop(@PathVariable String projectId, @PathVariable String env,
                             @RequestBody(required = false) VersionRequest request) {
        currentUser.requireUserId();
        return deployments.stop(projectId, env, request == null ? null : request.version());
    }

    public record VersionRequest(String version) {
    }
}
