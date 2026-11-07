package top.rongx.agent.gateway.web;

import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import org.springframework.stereotype.Component;

/**
 * 项目上下文解析器。
 *
 * <p>规约 §1.1 A2 的落地：{@code projectId} 由<b>服务端</b>解析并校验后才进入会话；
 * 模型无法提供或修改它。会话建立后若要换项目，必须新建会话。
 */
@Component
public class ProjectContextResolver {

    private final ProjectRepository projects;

    public ProjectContextResolver(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * 校验并构造上下文。
     *
     * @param projectId 客户端选择的项目
     * @param env       客户端选择的环境
     * @param userId    已认证用户
     * @throws IllegalArgumentException 项目不存在、环境不属于该项目
     */
    public ProjectContext resolve(String projectId, String env, String userId) {
        // 1. 项目必须存在（服务端校验，模型不可伪造）
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        // 2. 环境缺省为 test
        String targetEnv = (env == null || env.isBlank()) ? "test" : env;
        // 3. 环境必须属于该项目（防止越权访问他人环境）
        boolean known = projects.findEnvs(project.id()).stream()
                .map(ProjectEnv::env)
                .anyMatch(targetEnv::equals);
        if (!known) {
            throw new IllegalArgumentException("环境不属于该项目: " + targetEnv);
        }
        // 4. 构造不可变上下文
        return new ProjectContext(project.id(), targetEnv, userId, project.repoUrl());
    }
}
