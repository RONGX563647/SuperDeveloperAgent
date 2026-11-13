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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
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
 *   <li>环境与策略/部署阶段保持一致（{@code policy.testEnv}），并且<b>永远拒绝生产环境</b>；</li>
 *   <li>分析/复现/修复/验证一律使用<b>只读工具面</b>，只有部署阶段才挂载运维写工具——</li>
 *   <li>因此即使 Bug 描述里藏了 prompt injection，模型也没有写文件/执行命令的工具；</li>
 *   <li>每个阶段使用独立 session，避免上一次尝试的结论污染本次判断；固定超时防止卡死。</li>
 * </ul>
 */
@Component
public class ChatBugFixAgent implements BugFixAgentPort {

    private static final Logger log = LoggerFactory.getLogger(ChatBugFixAgent.class);

    /** 单阶段最长执行时间（部署阶段可能包含构建与健康检查，放宽到 15 分钟）。 */
    private static final int TIMEOUT_SECONDS = 900;

    private final ChatService chatService;
    private final ProjectContextResolver contexts;
    private final ProjectRepository projects;
    private final BugFixPolicyStore policies;

    public ChatBugFixAgent(ChatService chatService, ProjectContextResolver contexts,
                           ProjectRepository projects, BugFixPolicyStore policies) {
        this.chatService = chatService;
        this.contexts = contexts;
        this.projects = projects;
        this.policies = policies;
    }

    @Override
    public String run(BugFixRun run, Role role, String prompt) {
        ProjectContext ctx = context(run, role);
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

    /**
     * 项目上下文：使用策略配置的测试环境（与部署阶段一致），缺失时回退 test / 首个非生产环境。
     *
     * <p>非部署角色强制 {@code readOnly=true}，从工具装配层面阻断写操作。
     */
    private ProjectContext context(BugFixRun run, Role role) {
        List<ProjectEnv> envs = projects.findEnvs(run.projectId());
        if (envs.isEmpty()) {
            throw new IllegalStateException("项目未配置任何环境：" + run.projectId());
        }
        List<String> names = envs.stream().map(ProjectEnv::env).toList();
        String preferred = policies.load().testEnv();
        String env = names.stream()
                .filter(name -> name.equalsIgnoreCase(preferred))
                .findFirst()
                .orElseGet(() -> names.stream()
                        .filter(name -> "test".equalsIgnoreCase(name))
                        .findFirst()
                        .orElseGet(() -> names.stream()
                                .filter(name -> !productionLike(name))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "项目只有生产环境，自动修复已拒绝执行：" + run.projectId()))));
        if (preferred != null && !preferred.isBlank() && !env.equalsIgnoreCase(preferred)) {
            log.warn("策略环境 {} 不在项目 {} 的环境列表中，已回退到 {}",
                    preferred, run.projectId(), env);
        }
        if (productionLike(env)) {
            throw new IllegalStateException("自动修复禁止使用生产环境：" + env);
        }
        ProjectContext base = contexts.resolve(run.projectId(), env, run.userId());
        boolean readOnly = role != Role.DEPLOY;
        if (base.readOnly() == readOnly) {
            return base;
        }
        return new ProjectContext(base.projectId(), base.env(), base.userId(), base.repoUrl(),
                base.worktree(), readOnly);
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

    /** 生产/预生产一律拒绝（prod / production / preprod）。 */
    private static boolean productionLike(String env) {
        String value = env == null ? "" : env.trim().toLowerCase(Locale.ROOT);
        return value.contains("prod");
    }

    private static String safe(Throwable error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }
}
