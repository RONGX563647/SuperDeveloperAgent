package org.example.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.stream.Collectors;
import org.example.agent.domain.port.ProjectRepository;
import org.example.agent.domain.project.Project;
import org.example.agent.domain.project.ProjectContext;
import org.example.agent.domain.project.ProjectEnv;
import org.springframework.stereotype.Component;

/**
 * 项目相关只读工具（L0）。
 *
 * <p>注意所有方法都<b>不接受</b> {@code projectId} 参数：项目来自 {@link ProjectContext}。
 */
@Component
public class ProjectTools {

    private final ProjectRepository projects;

    public ProjectTools(ProjectRepository projects) {
        this.projects = projects;
    }

    @Tool(name = "project_info",
            description = "查询当前项目的基本信息与端口段。只读。用于确认自己在哪个项目、哪个环境。",
            readOnly = true, concurrencySafe = true)
    public String projectInfo(ProjectContext ctx) {
        Project p = projects.findById(ctx.projectId())
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + ctx.projectId()));
        return """
                project: %s (%s)
                type: %s
                repo: %s
                ports: %d-%d
                env: %s
                evidence: project:%s
                """.formatted(p.id(), p.name(), p.type(), p.repoUrl(),
                p.portRangeStart(), p.portRangeEnd(), ctx.env(), p.id());
    }

    @Tool(name = "list_envs",
            description = "列出当前项目的所有环境及其访问地址。只读。发布前用来确认目标环境。",
            readOnly = true, concurrencySafe = true)
    public String listEnvs(ProjectContext ctx) {
        List<ProjectEnv> envs = projects.findEnvs(ctx.projectId());
        if (envs.isEmpty()) {
            return "当前项目没有配置环境。evidence: project:" + ctx.projectId();
        }
        return envs.stream()
                .map(e -> "- %s  %s  composeDir=%s".formatted(e.env(), e.appUrl(), e.composeDir()))
                .collect(Collectors.joining("\n", "envs:\n", "\nevidence: project:" + ctx.projectId()));
    }
}
