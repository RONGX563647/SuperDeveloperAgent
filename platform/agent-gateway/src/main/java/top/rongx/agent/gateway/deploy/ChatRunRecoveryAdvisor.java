package top.rongx.agent.gateway.deploy;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.run.RunRecoveryAdvisor;
import top.rongx.agent.runtime.ChatService;

/**
 * 用部署 Agent 实现「运行失败自动诊断」。
 *
 * <p>执行器只负责在失败后调用本类，并把返回的每一行写进运行日志；因此流水线页面
 * 能看到 {@code [agent] 调用工具 env_ensure} 这类步骤，而不是"失败了、原因未知"。
 *
 * <p>约束：
 * <ul>
 *   <li>只处理 dev/test（生产必须人工确认）；</li>
 *   <li>只处理 UI 触发的运行（执行器已过滤 agent 自触发，避免递归）；</li>
 *   <li>最长等 3 分钟，超时后把已收集的步骤带回去，不阻塞流水线控制器。</li>
 * </ul>
 */
@Component
public class ChatRunRecoveryAdvisor implements RunRecoveryAdvisor {

    /** 一次自动诊断的等待上限（模型多轮工具调用 + 一次重试发布）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    /** 文本块累计上限（避免把整段报告塞进运行日志）。 */
    private static final int MAX_TEXT = 4000;

    /** 追加到运行日志的最大行数。 */
    private static final int MAX_LINES = 40;

    private final ChatService chatService;
    private final ProjectRepository projects;

    public ChatRunRecoveryAdvisor(ChatService chatService, ProjectRepository projects) {
        this.chatService = chatService;
        this.projects = projects;
    }

    @Override
    public List<String> advise(Context context) {
        if ("prod".equalsIgnoreCase(context.env())) {
            return List.of();
        }
        String userId = context.actor() != null && context.actor().startsWith("ui:")
                ? context.actor().substring(3) : "system";
        ProjectContext ctx;
        try {
            // 失败运行已经发生，环境和项目必然存在；这里只校验项目存在，不再要求
            // 环境出现在项目接入清单里（dev 常是首次运行才被平台真正使用）。
            Project project = projects.findById(context.projectId())
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + context.projectId()));
            ctx = new ProjectContext(project.id(), context.env(), userId, project.repoUrl(), null);
        } catch (RuntimeException e) {
            return List.of("无法解析项目上下文：" + safe(e.getMessage()));
        }

        List<String> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        try {
            chatService.stream(ctx, "recovery-" + context.runId(), task(context), "deploy")
                    .take(TIMEOUT)
                    .toIterable()
                    .forEach(event -> collect(event, lines, text));
        } catch (Exception e) {
            lines.add("诊断中断：" + safe(e.getMessage()));
        }
        flushText(text, lines);
        return limit(lines);
    }

    /** 把事件转成一行可读步骤：工具调用/完成直接记录，文本增量累计到结尾。 */
    private static void collect(AgentEvent event, List<String> lines, StringBuilder text) {
        if (event instanceof ToolCallStartEvent call) {
            lines.add("调用工具 " + nullToDash(call.getToolCallName()));
        } else if (event instanceof ToolResultStartEvent result) {
            lines.add("工具完成 " + nullToDash(result.getToolCallName()));
        } else if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() != null && text.length() < MAX_TEXT) {
                text.append(delta.getDelta());
            }
        }
    }

    /** 文本增量是流式的，最后统一按行拆开（过滤空行）。 */
    private static void flushText(StringBuilder text, List<String> lines) {
        if (text.isEmpty()) {
            return;
        }
        for (String raw : text.toString().split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                lines.add(line.length() > 400 ? line.substring(0, 400) + "…" : line);
            }
        }
    }

    private static List<String> limit(List<String> lines) {
        if (lines.size() <= MAX_LINES) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.subList(0, MAX_LINES));
        out.add("…（后续步骤已省略，共 " + lines.size() + " 条）");
        return out;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "未知原因";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    /** 交给部署 Agent 的任务：不追问、先定位、能修就修、最多重试一次。 */
    private static String task(Context context) {
        return """
                流水线运行失败，请自动诊断并尽可能修复，不要向用户追问。
                项目：%s
                环境：%s
                流水线：%s
                提交：%s
                运行 id：%s

                失败日志（尾部）：
                %s

                要求：
                1. 先用 read_run_log / env_summary 定位根因，不要凭空猜测。
                2. 中间件缺失或不可用 → 调 env_ensure；变量缺失/端点写错 → 说明原因并再次 env_ensure 修正。
                3. 若根因是流水线定义与产物不匹配（HOST 目标没有可运行 jar、前端静态产物选了 host），
                   明确说明并直接 deploy_run 重试一次（不要传 deployTarget，让平台按产物自动选择）。
                4. 最多重试一次发布；不允许操作生产环境；不要要求人工确认。
                5. 最后用中文输出结论：根因 / 已执行的修复 / 下一步建议（各一行，尽量短）。
                """.formatted(context.projectId(), context.env(), context.pipelineId(),
                context.gitSha(), context.runId(), context.log());
    }
}
