package top.rongx.agent.gateway.web;

import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.gateway.auth.Role;
import top.rongx.agent.gateway.auth.UserStore;
import org.springframework.stereotype.Component;

/**
 * 项目上下文解析器。
 *
 * <p>规约 §1.1 A2 的落地：{@code projectId} 由<b>服务端</b>解析并校验后才进入会话；
 * 模型无法提供或修改它。会话建立后若要换项目，必须新建会话。
 *
 * <p>除「项目/环境存在」外，本类还校验<b>当前用户是否有权访问该项目</b>：
 * 只有 ADMIN（或显式授予 {@code *}）才能访问全部项目，其余用户按成员的 projectId 列表放行。
 * 只读角色（VIEWER）的上下文会带上 {@code readOnly=true}，由运行层据此装配只读工具面。
 */
@Component
public class ProjectContextResolver {

    /** 项目成员列表里的通配符：表示可访问全部项目。 */
    private static final String ALL_PROJECTS = "*";

    private final ProjectRepository projects;
    private final UserStore users;

    public ProjectContextResolver(ProjectRepository projects, UserStore users) {
        this.projects = projects;
        this.users = users;
    }

    /**
     * 校验并构造上下文。
     *
     * @param projectId 客户端选择的项目
     * @param env       客户端选择的环境
     * @param userId    已认证用户
     * @throws IllegalArgumentException 项目不存在、环境不属于该项目、用户无权访问该项目
     */
    public ProjectContext resolve(String projectId, String env, String userId) {
        return resolve(projectId, env, userId, null);
    }

    /**
     * 校验并构造上下文（带工作树）。
     *
     * @param projectId 客户端选择的项目
     * @param env       客户端选择的环境
     * @param userId    已认证用户
     * @param worktree  客户端当前选中的工作树（可空 = 主仓库）
     * @throws IllegalArgumentException 项目不存在、环境不属于该项目、用户无权访问该项目
     */
    public ProjectContext resolve(String projectId, String env, String userId, String worktree) {
        // 1. 身份必须是平台已知用户（角色决定可写/只读与项目范围）
        UserStore.User user = users.find(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在：" + userId);
        }
        // 2. 项目必须存在（服务端校验，模型不可伪造）
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        // 3. 项目级授权：非 ADMIN 必须是该项目的成员（防止越权读他人项目）
        if (!canAccess(user, project.id())) {
            throw new IllegalArgumentException("无权访问该项目：" + project.id());
        }
        // 4. 环境缺省为 test
        String targetEnv = (env == null || env.isBlank()) ? "test" : env;
        // 5. 环境必须属于该项目（防止越权访问他人环境）
        boolean known = projects.findEnvs(project.id()).stream()
                .map(ProjectEnv::env)
                .anyMatch(targetEnv::equals);
        if (!known) {
            throw new IllegalArgumentException("环境不属于该项目: " + targetEnv);
        }
        // 6. 构造不可变上下文（工作树名的合法性在 ProjectContext 构造器里统一校验）
        return new ProjectContext(project.id(), targetEnv, userId, project.repoUrl(), worktree,
                user.role() == Role.VIEWER);
    }

    /** 用户是否可访问该项目：ADMIN 全量，其余看成员列表（支持 {@code *} 通配）。 */
    private static boolean canAccess(UserStore.User user, String projectId) {
        if (user.role() == Role.ADMIN) {
            return true;
        }
        return user.projects().contains(ALL_PROJECTS) || user.projects().contains(projectId);
    }
}
