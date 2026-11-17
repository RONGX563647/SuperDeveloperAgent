package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.PlatformException;
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

    public ProjectTools(ProjectRepository projects, EffectiveEndpointResolver endpoints) {
        this.projects = projects;
        this.endpoints = endpoints;
    }

    @Tool(name = "project_info", description = "Show current project information.",
            readOnly = true, concurrencySafe = true)
    public String projectInfo(ProjectContext ctx) {
        Project p = projects.findById(ctx.projectId())
                .orElseThrow(() -> new RunException(ErrorCode.RUN_INVALID_INPUT, "项目不存在: " + ctx.projectId()));
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

    @Tool(name = "list_envs", description = "List environments and endpoints.",
            readOnly = true, concurrencySafe = true)
    public String listEnvs(ProjectContext ctx) {
        List<ProjectEnv> envs = projects.findEnvs(ctx.projectId());
        if (envs.isEmpty()) {
            return "当前项目没有配置环境。evidence: project:" + ctx.projectId();
        }
        StringBuilder out = new StringBuilder("envs:\n");
        Map<String, List<String>> byDir = new LinkedHashMap<>();
        for (ProjectEnv e : envs) {
            EffectiveEndpointResolver.Endpoint endpoint = endpoints.resolve(ctx.projectId(), e.env());
            out.append("- ").append(e.env())
                    .append("  appUrl=").append(e.appUrl() == null || e.appUrl().isBlank()
                            ? "未登记" : e.appUrl())
                    .append("  effective=http://127.0.0.1:").append(endpoint.port())
                    .append(endpoint.healthPath())
                    .append("（来源：").append(endpoint.source()).append("）")
                    .append("  composeDir=").append(e.composeDir() == null ? "未登记" : e.composeDir())
                    .append('\n');
            int registeredPort = portOf(e.appUrl());
            if (registeredPort > 0 && registeredPort != endpoint.port()) {
                out.append("  warning: 登记地址端口 ").append(registeredPort)
                        .append(" 与生效端口 ").append(endpoint.port())
                        .append(" 不一致，以 effective 为准\n");
            }
            if (e.composeDir() != null && !e.composeDir().isBlank()) {
                byDir.computeIfAbsent(e.composeDir(), k -> new ArrayList<>()).add(e.env());
            }
        }
        byDir.forEach((dir, names) -> {
            if (names.size() > 1) {
                out.append("warning: 环境 ").append(String.join("/", names))
                        .append(" 共用 composeDir=").append(dir)
                        .append("；若在同一宿主机部署会互相覆盖\n");
            }
        });
        out.append("evidence: project:").append(ctx.projectId());
        return out.toString();
    }

    private static int portOf(String url) {
        if (url == null || url.isBlank()) {
            return 0;
        }
        try {
            return URI.create(url).getPort();
        } catch (PlatformException | IllegalArgumentException e) {
            return 0;
        }
    }
}
