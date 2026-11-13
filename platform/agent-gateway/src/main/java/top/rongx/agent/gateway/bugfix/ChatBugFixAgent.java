package top.rongx.agent.gateway.bugfix;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.gateway.web.ProjectContextResolver;
import top.rongx.agent.runtime.ChatService;

/**
 * 自动修复的 Agent 适配器：把阶段提示词交给平台现有对话/部署 Agent，收集最终正文。
 *
 * <p>安全边界：
 * <ul>
 *   <li>项目上下文由服务端解析（用户必须有权访问该项目），模型无法伪造；</li>
 *   <li>每个阶段使用独立 session，避免上一次尝试的结论污染本次判断；</li>
 *   <li>固定超时，防止模型/工具卡住导致运行永远停在 RUNNING。</li>
 * </ul>
 */
@Component
public class ChatBugFixAgent implements BugFixAgentPort {

    /** 单阶段最长执行时间（部署阶段可能包含构建与健康检查，放宽到 15 分钟）。 */
    private static final int TIMEOUT_SECONDS = 900;

    private final ChatService chatService;
    private final ProjectContextResolver contexts;
    private final ProjectRepository projects;

    public ChatBugFixAgent(ChatService chatService, ProjectContextResolver contexts,
                           ProjectRepository projects) {
        this.chatService = chatService;
        this.contexts = contexts;
        this.projects = projects;
    }

    @Override
    public String run(BugFixRun run, Role role, String prompt) {
        ProjectContext ctx = context(run);
        String agentRole = role == Role.DEPLOY ? "deploy" : "chat";
        String sessionId = "bugfix-" + run.id() + "-" + role.name().toLowerCase(Locale.ROOT)
                + "-" + run.attempt();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> future = executor.submit(() -> collect(ctx, sessionId, prompt, agentRole));
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Agent 在 " + TIMEOUT_SECONDS + " 秒内未完成（"
                    + role.name() + "）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Agent 执行失败：" + safe(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent 执行被中断");
        } finally {
            // 不等待任务自然结束：超时后必须立即释放，避免卡死编排器
            executor.shutdownNow();
        }
    }

    /** 项目上下文：优先测试环境，其次第一个非生产环境（自动修复永不进入生产）。 */
    private ProjectContext context(BugFixRun run) {
        List<ProjectEnv> envs = projects.findEnvs(run.projectId());
        if (envs.isEmpty()) {
            throw new IllegalStateException("项目未配置任何环境：" + run.projectId());
        }
        String env = envs.stream().map(ProjectEnv::env)
                .filter(e -> "test".equalsIgnoreCase(e))
                .findFirst()
                .orElseGet(() -> envs.stream().map(ProjectEnv::env)
                        .filter(e -> !"prod".equalsIgnoreCase(e))
                        .findFirst()
                        .orElse(envs.get(0).env()));
        return contexts.resolve(run.projectId(), env, run.userId());
    }

    private String collect(ProjectContext ctx, String sessionId, String prompt, String role) {
        StringBuilder text = new StringBuilder();
        for (AgentEvent event : chatService.stream(ctx, sessionId, prompt, role).toIterable()) {
            if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                text.append(delta.getDelta());
            }
        }
        return text.toString().strip();
    }

    private static String safe(Throwable error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }
}
