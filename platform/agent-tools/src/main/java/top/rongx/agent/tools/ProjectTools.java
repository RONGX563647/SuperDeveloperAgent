package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 项目相关只读工具（L0）。
 *
 * <p>注意所有方法都<b>不接受</b> {@code projectId} 参数：项目来自 {@link ProjectContext}。
 */
@Component
public class ProjectTools {

    private final ProjectRepository projects;
    private final EffectiveEndpointResolver endpoints;
    private final ProjectRepoLocator locator;

    /** 测试/手工装配：不带仓库定位器，repo 为空时只报未登记。 */
    public ProjectTools(ProjectRepository projects, EffectiveEndpointResolver endpoints) {
        this(projects, endpoints, null);
    }

    @Autowired
    public ProjectTools(ProjectRepository projects, EffectiveEndpointResolver endpoints,
                        ProjectRepoLocator locator) {
        this.projects = projects;
        this.endpoints = endpoints;
        this.locator = locator;
    }

    @Tool(name = "project_info", description = "Show current project information.",
            readOnly = true, concurrencySafe = true)
    public String projectInfo(ProjectContext ctx) {
        Project p = projects.findById(ctx.projectId())
                .orElseThrow(() -> new RunException(ErrorCode.RUN_INVALID_INPUT, "项目不存在: " + ctx.projectId()));
        String repo = describeRepo(p);
        return """
                project: %s (%s)
                type: %s
                %s
                ports: %d-%d
                env: %s
                evidence: project:%s
                """.formatted(p.id(), p.name(), p.type(), repo,
                p.portRangeStart(), p.portRangeEnd(), ctx.env(), p.id());
    }

    /** 仓库地址：优先登记值；本地接入未登记 origin 时回退到本地路径；都没有明确标注，绝不输出裸 null。 */
    private String describeRepo(Project p) {
        if (p.repoUrl() != null && !p.repoUrl().isBlank()) {
            return "repo: " + p.repoUrl();
        }
        if (locator != null) {
            Optional<Path> dir = locator.repoDir(p.id());
            if (dir.isPresent()) {
                return "repo: " + dir.get() + "（本地仓库，origin 未登记）";
            }
        }
        return "repo: 未登记（本地仓库，未配置 origin）";
    }

    @Tool(name = "list_envs", description = "List environments and endpoints.",
            readOnly = true, concurrencySafe = true)
    public String listEnvs(ProjectContext ctx) {
        List<ProjectEnv> envs = projects.findEnvs(ctx.projectId());
        if (envs.isEmpty()) {
            return "当前项目没有配置环境。evidence: project:" + ctx.projectId();
        }
        StringBuilder out = new StringBuilder("envs:\n");
        for (ProjectEnv e : envs) {
            EffectiveEndpointResolver.Endpoint endpoint = endpoints.resolve(ctx.projectId(), e.env());
            out.append("- ").append(e.env())
                    .append("  effective=http://127.0.0.1:").append(endpoint.port())
                    .append(endpoint.healthPath())
                    .append("（来源：").append(endpoint.source()).append("）")
                    .append("  composeDir=").append(e.composeDir() == null ? "未登记" : e.composeDir())
                    .append("（仅登记，未参与部署：reserved）")
                    .append('\n');
        }
        out.append("note: 生效地址以端口租约/流水线为准（见上方「来源」），不再登记静态访问地址；"
                + "composeDir 只是登记信息，当前部署由流水线 deploy 段与容器命名决定，修改它不会改变部署落点\n");
        out.append("evidence: project:").append(ctx.projectId());
        return out.toString();
    }
}
