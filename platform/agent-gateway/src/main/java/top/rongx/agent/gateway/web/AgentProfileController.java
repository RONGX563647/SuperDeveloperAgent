package top.rongx.agent.gateway.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.AgentFactory;

/**
 * Agent 运行画像（只读）。
 *
 * <p>轨迹页需要回答"这个 Agent 现在被怎么约束"：系统提示词、工具面按环境裁剪的规则都在
 * 服务端，前端不能自行拼。这里只返回提示词与作用域，<b>不返回</b>模型密钥或凭证。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentProfileController {

    private final AgentFactory agentFactory;
    private final ProjectContextResolver contextResolver;
    private final CurrentUser currentUser;

    public AgentProfileController(AgentFactory agentFactory,
                                  ProjectContextResolver contextResolver,
                                  CurrentUser currentUser) {
        this.agentFactory = agentFactory;
        this.contextResolver = contextResolver;
        this.currentUser = currentUser;
    }

    /**
     * 当前项目/环境下的通用对话 Agent 画像。
     *
     * @param projectId 项目 id
     * @param env       环境（dev / test / prod）
     */
    @GetMapping("/profile")
    public AgentProfileView profile(@RequestParam String projectId, @RequestParam String env) {
        String userId = currentUser.requireUserId();
        ProjectContext ctx = contextResolver.resolve(projectId, env, userId);
        return new AgentProfileView("devops-agent", agentFactory.systemPrompt(ctx),
                projectId, env, userId);
    }

    /** 画像视图：提示词只描述角色与流程，安全边界在工具与确认层。 */
    public record AgentProfileView(String name, String prompt, String projectId,
                                   String env, String userId) {
    }
}
