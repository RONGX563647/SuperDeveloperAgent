package top.rongx.agent.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.OpsAgentPort;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 多 Agent 协作：把一次运维任务委派给「发布工程师 Agent」并压缩结果。
 *
 * <p>实现要点：
 * <ul>
 *   <li>子 Agent 使用 {@link AgentFactory#createDeploy} 的工具面（部署/取证/受限补丁），
 *       不包含本委派工具本身，天然防递归；</li>
 *   <li>独立 sessionId：子 Agent 的上下文不会污染主对话，也不会复用上次委派的结论；</li>
 *   <li>固定超时（{@value #TIMEOUT_SECONDS} 秒）：工具调用必须同步返回，超时后取消并给结论；</li>
 *   <li>生产环境直接拒绝：生产的发布只走"人工确认的部署面板"，不开放模型自主探索。</li>
 * </ul>
 */
@Component
public class OpsAgentRunner implements OpsAgentPort {

    /** 子 Agent 单次委派的最长执行时间（小于对话 SSE 的 180 秒，避免前端先断流）。 */
    private static final int TIMEOUT_SECONDS = 150;
    /** 返回给主 Agent 的正文上限：太长会挤占主对话上下文。 */
    private static final int MAX_SUMMARY_CHARS = 4000;
    private static final int MAX_ERRORS = 5;

    private final AgentFactory agentFactory;

    /**
     * 构造依赖故意用 {@link Lazy}：AgentFactory 的构造依赖 agentToolkit，而 agentToolkit 又注册了
     * 委派工具 → 本类。若立即注入会形成构造环；这里延迟到真正委派时再取（此时容器已就绪）。
     */
    public OpsAgentRunner(@Lazy AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Override
    public Report run(ProjectContext ctx, String task) {
        if (ctx.isProduction()) {
            return new Report("REJECTED",
                    "生产环境不启用子 Agent：生产发布必须在部署面板由人工确认。",
                    List.of(), List.of());
        }
        ReActAgent agent = agentFactory.createDeploy(ctx);
        String sessionId = "ops-" + ctx.projectId() + "-" + ctx.env() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId(sessionId)
                .put(ProjectContext.class, ctx)
                .build();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Report> future = executor.submit(() -> collect(agent, task, runtime));
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return new Report("TIMEOUT",
                    "子 Agent 在 " + TIMEOUT_SECONDS + " 秒内未完成；请缩小任务范围（例如只诊断不修复），"
                            + "或让用户直接使用发布工作台。",
                    List.of(), List.of("委派超时"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Report("FAILED", "子 Agent 执行异常：" + safe(cause), List.of(),
                    List.of(safe(cause)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Report("FAILED", "委派被中断", List.of(), List.of("interrupted"));
        }
    }

    /** 消费子 Agent 事件流：保留正文结论、工具轨迹与失败原因。 */
    private static Report collect(ReActAgent agent, String task, RuntimeContext runtime) {
        StringBuilder text = new StringBuilder();
        Set<String> tools = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (AgentEvent event : agent.streamEvents(task, runtime).toIterable()) {
            if (event instanceof TextBlockDeltaEvent delta) {
                if (delta.getDelta() != null && text.length() < MAX_SUMMARY_CHARS) {
                    text.append(delta.getDelta());
                }
            } else if (event instanceof ToolCallStartEvent call) {
                if (call.getToolCallName() != null) {
                    tools.add(call.getToolCallName());
                }
            } else if (event instanceof ToolResultEndEvent end && isFailure(end.getState())) {
                if (errors.size() < MAX_ERRORS) {
                    errors.add(end.getToolCallName() + "：" + stateName(end.getState()));
                }
            }
        }
        String summary = text.toString().strip();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS) + "…";
        }
        if (summary.isEmpty()) {
            summary = "子 Agent 未返回正文结论（可查看其工具轨迹或改用工作台查看运行日志）。";
        }
        return new Report(errors.isEmpty() ? "SUCCEEDED" : "FAILED", summary,
                List.copyOf(tools), List.copyOf(errors));
    }

    private static boolean isFailure(Enum<?> state) {
        return state != null && !"SUCCESS".equalsIgnoreCase(state.name())
                && !"COMPLETED".equalsIgnoreCase(state.name());
    }

    private static String stateName(Enum<?> state) {
        return state == null ? "UNKNOWN" : state.name();
    }

    private static String safe(Throwable error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }
}
