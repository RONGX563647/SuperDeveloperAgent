package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspaceSupervisorPort;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 工作区调度 Agent 的工具面。
 *
 * <p>只有"管理"能力：会话的增改查、异步派发、状态/等待/取消/汇总，以及任务板。
 * <b>没有任何写代码、写配置、执行命令或部署的工具</b>——需要动手时由调度 Agent
 * 新建/派发普通会话，子会话仍按环境策略与人工确认工作。
 *
 * <p>所有方法都用 {@link ProjectContext} 里的 projectId / userId，模型无法伪造归属；
 * 端口实现再按 owner 过滤一次，防止模型引用别人的 conversationId。
 */
@Component
public class WorkspaceSupervisorTools {

    private static final long MAX_WAIT_SECONDS = 60L;

    private final WorkspaceSupervisorPort supervisor;

    public WorkspaceSupervisorTools(WorkspaceSupervisorPort supervisor) {
        this.supervisor = supervisor;
    }

    @Tool(name = "workspace_snapshot",
            description = "工作区总览：会话数量、运行中/失败数、任务统计与活动运行。只读。先看这个再决定下一步。",
            readOnly = true, concurrencySafe = true)
    public String snapshot(ProjectContext ctx) {
        return guard(() -> supervisor.snapshot(ctx.projectId(), ctx.userId()));
    }

    @Tool(name = "conversation_list",
            description = "列出当前工作区的会话（默认不含归档）。只读。",
            readOnly = true, concurrencySafe = true)
    public String list(ProjectContext ctx,
                       @ToolParam(name = "include_archived", required = false,
                               description = "是否包含已归档会话，默认 false")
                       Boolean includeArchived) {
        return guard(() -> format(supervisor.listConversations(ctx.projectId(), ctx.userId(),
                Boolean.TRUE.equals(includeArchived))));
    }

    @Tool(name = "conversation_search",
            description = "按标题/摘要关键词搜索当前工作区的会话。只读。",
            readOnly = true, concurrencySafe = true)
    public String search(ProjectContext ctx,
                         @ToolParam(name = "keyword", description = "关键词")
                         String keyword) {
        return guard(() -> format(supervisor.searchConversations(ctx.projectId(), ctx.userId(),
                keyword)));
    }

    @Tool(name = "conversation_create",
            description = """
                    在当前工作区新建一个普通会话。只创建元数据，不会自动发送消息。
                    创建后用 conversation_send 派发目标。不能创建调度会话（调度深度固定为 1）。""",
            readOnly = false, concurrencySafe = false)
    public String create(ProjectContext ctx,
                         @ToolParam(name = "title", description = "会话标题，不超过 60 字")
                         String title,
                         @ToolParam(name = "env", required = false,
                                 description = "环境 dev/test/prod；默认当前调度会话环境")
                         String env,
                         @ToolParam(name = "worktree", required = false,
                                 description = "工作树名（可空 = 主仓库）")
                         String worktree) {
        return guard(() -> {
            Conversation conversation = supervisor.createConversation(ctx.projectId(),
                    ctx.userId(), title, env, worktree);
            return "ok: created conversation " + conversation.id()
                    + "  title=" + conversation.title()
                    + "  env=" + conversation.env();
        });
    }

    @Tool(name = "conversation_rename",
            description = "重命名会话。",
            readOnly = false, concurrencySafe = false)
    public String rename(ProjectContext ctx,
                         @ToolParam(name = "conversation_id", description = "会话 id")
                         String conversationId,
                         @ToolParam(name = "title", description = "新标题")
                         String title) {
        return guard(() -> supervisor.renameConversation(conversationId, ctx.userId(), title)
                ? "ok: renamed " + conversationId
                : "err: 会话不存在或无权访问：" + conversationId);
    }

    @Tool(name = "conversation_archive",
            description = "归档/恢复会话：归档只从默认列表隐藏，不影响会话数据。",
            readOnly = false, concurrencySafe = false)
    public String archive(ProjectContext ctx,
                          @ToolParam(name = "conversation_id", description = "会话 id")
                          String conversationId,
                          @ToolParam(name = "archived", description = "true=归档，false=恢复")
                          Boolean archived) {
        return guard(() -> supervisor.setArchived(conversationId, ctx.userId(),
                !Boolean.FALSE.equals(archived))
                ? "ok: archived=" + !Boolean.FALSE.equals(archived) + " " + conversationId
                : "err: 会话不存在或无权访问：" + conversationId);
    }

    @Tool(name = "conversation_pin",
            description = "置顶/取消置顶会话。",
            readOnly = false, concurrencySafe = false)
    public String pin(ProjectContext ctx,
                      @ToolParam(name = "conversation_id", description = "会话 id")
                      String conversationId,
                      @ToolParam(name = "pinned", description = "true=置顶，false=取消")
                      Boolean pinned) {
        return guard(() -> supervisor.setPinned(conversationId, ctx.userId(),
                !Boolean.FALSE.equals(pinned))
                ? "ok: pinned=" + !Boolean.FALSE.equals(pinned) + " " + conversationId
                : "err: 会话不存在或无权访问：" + conversationId);
    }

    @Tool(name = "conversation_send",
            description = """
                    异步给某个普通会话发送一条消息，立即返回 runId（不会阻塞你）。
                    同一会话有运行未结束时会被拒绝；重复目标会直接返回已有 runId。
                    派发后用 conversation_wait 或 conversation_status 跟踪。""",
            readOnly = false, concurrencySafe = false)
    public String send(ProjectContext ctx,
                       @ToolParam(name = "conversation_id", description = "目标会话 id")
                       String conversationId,
                       @ToolParam(name = "message", description = "要执行的目标，尽量具体")
                       String message) {
        return guard(() -> {
            String runId = supervisor.send(conversationId, ctx.userId(), message);
            return runId.startsWith("err:") ? runId
                    : "ok: dispatched runId=" + runId + "  conversation=" + conversationId;
        });
    }

    @Tool(name = "conversation_status",
            description = "查询一次运行的状态与最近结果。只读。",
            readOnly = true, concurrencySafe = true)
    public String status(ProjectContext ctx,
                         @ToolParam(name = "run_id", description = "运行 id（conversation_send 返回）")
                         String runId) {
        return guard(() -> formatRun(supervisor.runStatus(runId, ctx.userId())));
    }

    @Tool(name = "conversation_wait",
            description = """
                    等待一次运行结束，最多 60 秒。未结束就返回 RUNNING，稍后再查；
                    不要连续高频调用。""",
            readOnly = true, concurrencySafe = true)
    public String waitRun(ProjectContext ctx,
                          @ToolParam(name = "run_id", description = "运行 id")
                          String runId,
                          @ToolParam(name = "timeout_seconds", required = false,
                                  description = "等待秒数，默认 30，最多 60")
                          Integer timeoutSeconds) {
        long seconds = timeoutSeconds == null ? 30 : timeoutSeconds;
        long timeout = Math.min(Math.max(1, seconds), MAX_WAIT_SECONDS) * 1000L;
        return guard(() -> formatRun(supervisor.waitForRun(runId, ctx.userId(), timeout)));
    }

    @Tool(name = "conversation_cancel",
            description = "取消一次排队中/运行中的任务。",
            readOnly = false, concurrencySafe = false)
    public String cancel(ProjectContext ctx,
                         @ToolParam(name = "run_id", description = "运行 id")
                         String runId) {
        return guard(() -> supervisor.cancelRun(runId, ctx.userId())
                ? "ok: cancelled " + runId
                : "err: 运行不存在、无权访问或已结束：" + runId);
    }

    @Tool(name = "conversation_summarize",
            description = "读取某个会话最近一次运行的结论（成功摘要或失败原因）。只读。",
            readOnly = true, concurrencySafe = true)
    public String summarize(ProjectContext ctx,
                            @ToolParam(name = "conversation_id", description = "会话 id")
                            String conversationId) {
        return guard(() -> supervisor.summarize(conversationId, ctx.userId()));
    }

    @Tool(name = "task_create",
            description = "在工作区任务板登记一个任务（不执行）。",
            readOnly = false, concurrencySafe = false)
    public String createTask(ProjectContext ctx,
                             @ToolParam(name = "goal", description = "任务目标")
                             String goal,
                             @ToolParam(name = "expected_output", required = false,
                                     description = "期望产出")
                             String expectedOutput,
                             @ToolParam(name = "priority", required = false,
                                     description = "优先级 1-5，1 最高，默认 3")
                             Integer priority,
                             @ToolParam(name = "conversation_id", required = false,
                                     description = "关联会话 id（可空）")
                             String conversationId) {
        return guard(() -> {
            WorkspaceTask task = supervisor.createTask(ctx.projectId(), ctx.userId(), goal,
                    expectedOutput, priority == null ? 3 : priority, conversationId);
            return "ok: task " + task.id() + "  priority=" + task.priority()
                    + "  status=" + task.status();
        });
    }

    @Tool(name = "task_update",
            description = "更新任务目标/期望产出/状态（TODO/RUNNING/BLOCKED/DONE/CANCELLED）。",
            readOnly = false, concurrencySafe = false)
    public String updateTask(ProjectContext ctx,
                             @ToolParam(name = "task_id", description = "任务 id")
                             String taskId,
                             @ToolParam(name = "goal", required = false, description = "新目标")
                             String goal,
                             @ToolParam(name = "expected_output", required = false,
                                     description = "新期望产出")
                             String expectedOutput,
                             @ToolParam(name = "status", required = false,
                                     description = "TODO/RUNNING/BLOCKED/DONE/CANCELLED")
                             String status) {
        TaskStatus parsed;
        try {
            parsed = status == null || status.isBlank()
                    ? null : TaskStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "err: 非法任务状态：" + status;
        }
        return guard(() -> {
            WorkspaceTask task = supervisor.updateTask(ctx.projectId(), ctx.userId(), taskId,
                    goal, expectedOutput, parsed);
            return "ok: task " + task.id() + "  status=" + task.status();
        });
    }

    @Tool(name = "task_complete",
            description = "把任务标记为 DONE。",
            readOnly = false, concurrencySafe = false)
    public String completeTask(ProjectContext ctx,
                               @ToolParam(name = "task_id", description = "任务 id")
                               String taskId) {
        return guard(() -> {
            WorkspaceTask task = supervisor.updateTask(ctx.projectId(), ctx.userId(), taskId,
                    null, null, TaskStatus.DONE);
            return "ok: task " + task.id() + "  status=" + task.status();
        });
    }

    @Tool(name = "task_list",
            description = "列出工作区任务板。只读。",
            readOnly = true, concurrencySafe = true)
    public String listTasks(ProjectContext ctx) {
        return guard(() -> {
            List<WorkspaceTask> tasks = supervisor.listTasks(ctx.projectId(), ctx.userId());
            if (tasks.isEmpty()) {
                return "tasks: （空）";
            }
            StringBuilder sb = new StringBuilder("tasks:\n");
            for (WorkspaceTask task : tasks) {
                sb.append("- ").append(task.id())
                        .append("  [").append(task.status()).append("]")
                        .append("  p").append(task.priority())
                        .append("  ").append(oneLine(task.goal(), 120));
                if (task.conversationId() != null) {
                    sb.append("  conversation=").append(task.conversationId());
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        });
    }

    // ------------------------------------------------------------------ 内部

    private static String format(List<Conversation> conversations) {
        if (conversations.isEmpty()) {
            return "conversations: （空）";
        }
        StringBuilder sb = new StringBuilder("conversations:\n");
        for (Conversation c : conversations) {
            sb.append("- ").append(c.id())
                    .append("  [").append(c.kind()).append('/').append(c.status()).append(']')
                    .append("  ").append(c.title())
                    .append("  env=").append(c.env());
            if (c.pinned()) {
                sb.append("  pinned");
            }
            if (c.archived()) {
                sb.append("  archived");
            }
            if (!c.summary().isBlank()) {
                sb.append("  summary=").append(oneLine(c.summary(), 80));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatRun(ConversationRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("run: ").append(run.id()).append('\n')
                .append("conversation: ").append(run.conversationId()).append('\n')
                .append("status: ").append(run.status()).append('\n');
        if (run.startedAt() > 0) {
            long end = run.endedAt() > 0 ? run.endedAt() : System.currentTimeMillis();
            sb.append("elapsed_ms: ").append(Math.max(0, end - run.startedAt())).append('\n');
        }
        if (!run.error().isBlank()) {
            sb.append("error: ").append(oneLine(run.error(), 400)).append('\n');
        }
        if (!run.result().isBlank()) {
            sb.append("result:\n---\n").append(oneLine(run.result(), 1_500)).append("\n---");
        }
        return sb.toString().trim();
    }

    private static String oneLine(String text, int max) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    /** 工具不应把异常直接抛给 Agent（会中断整轮），统一转成可读的 err 文本。 */
    private static String guard(ToolCall call) {
        try {
            return call.run();
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return "err: " + (message == null || message.isBlank()
                    ? e.getClass().getSimpleName() : message);
        }
    }

    @FunctionalInterface
    private interface ToolCall {
        String run();
    }
}
