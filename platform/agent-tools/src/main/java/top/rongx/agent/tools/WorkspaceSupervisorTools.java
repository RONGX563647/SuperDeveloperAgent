package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.DispatchNode;
import top.rongx.agent.domain.conversation.DispatchNodeResult;
import top.rongx.agent.domain.conversation.DispatchPlan;
import top.rongx.agent.domain.conversation.DispatchResult;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspacePlanItem;
import top.rongx.agent.domain.conversation.WorkspaceSupervisorPort;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.tools.cache.ToolResultCache;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 工作区调度 Agent 的工具面。
 *
 * <p>只有"管理"能力：会话的增改查、异步派发、<b>DAG 并行派发</b>、状态/等待/取消/汇总，以及任务板。
 * <b>没有任何写代码、写配置、执行命令或部署的工具</b>——需要动手时由调度 Agent
 * 新建/派发普通会话，子会话仍按环境策略与人工确认工作。
 *
 * <p>多目标协作优先用 {@code workspace_dispatch}：一次调用完成"按依赖并行派发 + 并行等待 +
 * 失败熔断/提前终止 + 聚合汇报"，避免模型对每个子目标逐个 send→wait 串行等待。
 *
 * <p>所有方法都用 {@link ProjectContext} 里的 projectId / userId，模型无法伪造归属；
 * 端口实现再按 owner 过滤一次，防止模型引用别人的 conversationId。
 */
@Component
public class WorkspaceSupervisorTools {

    private static final long MAX_WAIT_SECONDS = 60L;
    private static final DateTimeFormatter RECALL_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final WorkspaceSupervisorPort supervisor;
    private final GitIssueService issues;
    private final ToolResultCache cache;

    public WorkspaceSupervisorTools(WorkspaceSupervisorPort supervisor, GitIssueService issues,
                                    ToolResultCache cache) {
        this.supervisor = supervisor;
        this.issues = issues;
        this.cache = cache;
    }

    @Tool(name = "workspace_snapshot", description = "Workspace overview: sessions and runs.",
            readOnly = true, concurrencySafe = true)
    public String snapshot(ProjectContext ctx) {
        return guard(() -> {
            List<GitIssue> board = projectIssues(ctx);
            long open = board.stream().filter(GitIssue::open).count();
            long p0 = board.stream().filter(i -> i.open() && "P0".equals(i.priority())).count();
            return supervisor.snapshot(ctx.projectId(), ctx.userId())
                    + "\ntasks: " + board.size() + "  open=" + open
                    + "  p0=" + p0 + "  closed=" + (board.size() - open)
                    + "\ntool_cache: " + cache.stats();
        });
    }

    @Tool(name = "conversation_list", description = "List workspace conversations.",
            readOnly = true, concurrencySafe = true)
    public String list(ProjectContext ctx,
                       @ToolParam(name = "include_archived", required = false,
                               description = "是否包含已归档会话，默认 false")
                       Boolean includeArchived) {
        return guard(() -> format(supervisor.listConversations(ctx.projectId(), ctx.userId(),
                Boolean.TRUE.equals(includeArchived))));
    }

    @Tool(name = "conversation_search", description = "Search conversations by keyword.",
            readOnly = true, concurrencySafe = true)
    public String search(ProjectContext ctx,
                         @ToolParam(name = "keyword", description = "关键词")
                         String keyword) {
        return guard(() -> format(supervisor.searchConversations(ctx.projectId(), ctx.userId(),
                keyword)));
    }

    @Tool(name = "conversation_create", description = "Create a plain conversation.",
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

    @Tool(name = "conversation_rename", description = "Rename a conversation.",
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

    @Tool(name = "conversation_archive", description = "Archive or restore a conversation.",
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

    @Tool(name = "conversation_pin", description = "Pin or unpin a conversation.",
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

    @Tool(name = "conversation_send", description = "Send a message to a conversation.",
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

    @Tool(name = "conversation_status", description = "Get a run status and latest result.",
            readOnly = true, concurrencySafe = true)
    public String status(ProjectContext ctx,
                         @ToolParam(name = "run_id", description = "运行 id（conversation_send 返回）")
                         String runId) {
        return guard(() -> formatRun(supervisor.runStatus(runId, ctx.userId())));
    }

    @Tool(name = "conversation_wait", description = "Wait up to 60s for a run to finish.",
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

    @Tool(name = "conversation_cancel", description = "Cancel a queued or running run.",
            readOnly = false, concurrencySafe = false)
    public String cancel(ProjectContext ctx,
                         @ToolParam(name = "run_id", description = "运行 id")
                         String runId) {
        return guard(() -> supervisor.cancelRun(runId, ctx.userId())
                ? "ok: cancelled " + runId
                : "err: 运行不存在、无权访问或已结束：" + runId);
    }

    @Tool(name = "conversation_summarize", description = "Read a conversation last conclusion.",
            readOnly = true, concurrencySafe = true)
    public String summarize(ProjectContext ctx,
                            @ToolParam(name = "conversation_id", description = "会话 id")
                            String conversationId) {
        return guard(() -> supervisor.summarize(conversationId, ctx.userId()));
    }

    @Tool(name = "workspace_dispatch", description = "Dispatch sub-goals as a parallel DAG.",
            readOnly = false, concurrencySafe = false)
    public String dispatch(ProjectContext ctx,
                           @ToolParam(name = "nodes",
                                   description = "节点数组；每项必填 id、message，可选 conversation_id、"
                                           + "title、env、worktree、depends_on、required、"
                                           + "timeout_seconds、retries")
                           List<DispatchNode> nodes,
                           @ToolParam(name = "abort_on_failure", required = false,
                                   description = "必需节点失败后是否停止剩余节点，默认 true")
                           Boolean abortOnFailure,
                           @ToolParam(name = "max_parallel", required = false,
                                   description = "并行上限 1-8，默认 4")
                           Integer maxParallel,
                           @ToolParam(name = "max_duration_seconds", required = false,
                                   description = "整张 DAG 总时长上限 1-1800 秒，默认 600")
                           Integer maxDurationSeconds,
                           @ToolParam(name = "max_consecutive_failures", required = false,
                                   description = "连续失败熔断阈值 1-10，默认 3；0=关闭")
                           Integer maxConsecutiveFailures) {
        return guard(() -> formatDispatch(supervisor.dispatch(ctx.projectId(), ctx.userId(),
                new DispatchPlan(nodes, !Boolean.FALSE.equals(abortOnFailure),
                        maxParallel == null ? DispatchPlan.DEFAULT_MAX_PARALLEL : maxParallel,
                        maxDurationSeconds == null
                                ? DispatchPlan.DEFAULT_MAX_DURATION_SECONDS : maxDurationSeconds,
                        maxConsecutiveFailures == null
                                ? DispatchPlan.DEFAULT_MAX_CONSECUTIVE_FAILURES
                                : maxConsecutiveFailures))));
    }

    @Tool(name = "workspace_plan", description = "Register a plan on the task board.",
            readOnly = false, concurrencySafe = false)
    public String plan(ProjectContext ctx,
                       @ToolParam(name = "items",
                               description = "计划项数组；每项必填 goal，可选 task_id、expected_output、"
                                       + "priority(P0/P1/P2)、status(TODO/RUNNING/BLOCKED/DONE/CANCELLED)")
                       List<WorkspacePlanItem> items) {
        return guard(() -> {
            if (items == null || items.isEmpty()) {
                return "err: items 不能为空";
            }
            StringBuilder sb = new StringBuilder("plan:\n");
            for (WorkspacePlanItem item : items) {
                if (item.goal() == null || item.goal().isBlank()) {
                    return "err: 每个计划项都必须有 goal";
                }
                TaskStatus status = parseStatus(item.status());
                String state = status == null ? null : issueState(status);
                GitIssue issue;
                if (item.taskId() != null && !item.taskId().isBlank()) {
                    GitIssue current = issues.get(ctx.userId(), item.taskId().trim());
                    issue = issues.update(ctx.userId(), current.id(), new GitIssue.Draft(
                            current.projectId(), current.provider(), current.repo(),
                            item.goal(),
                            blank(item.expectedOutput()) ? current.body() : item.expectedOutput(),
                            state == null ? current.state() : state,
                            current.kind(),
                            item.priority() == null ? "" : item.priority(),
                            current.labels(), current.assignee()));
                } else {
                    // 计划项按 FEAT（需求）登记，不触发 Bug 自动修复
                    issue = issues.create(ctx.userId(), new GitIssue.Draft(ctx.projectId(), "", "",
                            item.goal(), item.expectedOutput(), state == null ? "OPEN" : state,
                            "FEAT", item.priority(), "", ""));
                }
                sb.append("- ").append(issue.id())
                        .append("  [").append(issue.state()).append(']')
                        .append("  ").append(issue.priority())
                        .append("  ").append(oneLine(issue.title(), 120))
                        .append('\n');
            }
            return sb.toString().trim();
        });
    }

    @Tool(name = "workspace_recall", description = "Search past runs across sessions.",
            readOnly = true, concurrencySafe = true)
    public String recall(ProjectContext ctx,
                         @ToolParam(name = "keyword", required = false,
                                 description = "关键词，可空")
                         String keyword,
                         @ToolParam(name = "limit", required = false,
                                 description = "返回条数 1-20，默认 5")
                         Integer limit) {
        return guard(() -> {
            List<ConversationRun> runs = supervisor.recallRuns(ctx.projectId(), ctx.userId(),
                    keyword, limit == null ? 5 : limit);
            if (runs.isEmpty()) {
                return "recall: （没有匹配的历史运行）";
            }
            StringBuilder sb = new StringBuilder("recall:\n");
            for (ConversationRun run : runs) {
                sb.append("- ").append(RECALL_TIME.format(Instant.ofEpochMilli(run.createdAt())))
                        .append("  [").append(run.status()).append(']')
                        .append("  conversation=").append(run.conversationId())
                        .append("  goal=").append(oneLine(run.goal(), 100)).append('\n');
                if (!run.result().isBlank()) {
                    sb.append("  result: ").append(oneLine(run.result(), 280)).append('\n');
                }
                if (!run.error().isBlank()) {
                    sb.append("  error: ").append(oneLine(run.error(), 200)).append('\n');
                }
            }
            return sb.toString().trim();
        });
    }

    @Tool(name = "dispatch_issue", description = "Dispatch an issue to a new session.",
            readOnly = false, concurrencySafe = false)
    public String dispatchIssue(ProjectContext ctx,
                                @ToolParam(name = "issue_id", description = "任务/工单 id")
                                String issueId,
                                @ToolParam(name = "env", required = false,
                                        description = "环境 dev/test/prod；默认当前调度会话环境")
                                String env,
                                @ToolParam(name = "worktree", required = false,
                                        description = "工作树名（可空 = 主仓库）")
                                String worktree) {
        return guard(() -> {
            GitIssue issue = issues.get(ctx.userId(), issueId);
            if (issue == null) {
                return "err: 工单不存在或无权访问：" + issueId;
            }
            if (!issue.projectId().isBlank() && !issue.projectId().equals(ctx.projectId())) {
                return "err: 工单属于项目 " + issue.projectId() + "，不属于当前工作区";
            }
            if (!issue.open()) {
                return "err: 工单已关闭，不派发：" + issue.id();
            }
            Conversation conversation = supervisor.createConversation(ctx.projectId(), ctx.userId(),
                    issueTitle(issue), env, worktree);
            String message = issueMessage(issue);
            String runId = supervisor.send(conversation.id(), ctx.userId(), message);
            if (runId.startsWith("err:")) {
                return runId;
            }
            return "ok: issue=" + issue.id()
                    + "  conversation=" + conversation.id()
                    + "  run=" + runId
                    + "\n工单已派发，子会话完成后会回写评论；用 conversation_status 跟踪结果。";
        });
    }

    private static String issueTitle(GitIssue issue) {
        String title = issue.title().isBlank() ? "工单 " + issue.id() : issue.title();
        return oneLine("[" + (issue.number().isBlank() ? issue.id() : "#" + issue.number()) + "] "
                + title, 60);
    }

    /** 子会话目标：把工单事实与回写要求写清楚，避免它再去猜工单内容。 */
    private static String issueMessage(GitIssue issue) {
        String body = issue.body().isBlank() ? "（无描述）" : issue.body();
        return """
                处理任务页工单 %s（%s，%s）。
                标题：%s
                描述：
                %s

                要求：
                1. 先复述问题与验收标准，再动手；修改范围尽量小。
                2. 完成后运行相关构建/测试，把关键输出作为证据。
                3. 无论成功还是无法完成，最后都要调用 comment_git_issue 把结论回写到该工单。
                """.formatted(issue.id(), issue.kind(), issue.state(),
                issue.title().isBlank() ? "（无标题）" : issue.title(),
                body.length() > 3_000 ? body.substring(0, 3_000) + "\n…（已截断）" : body);
    }

    private static TaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (PlatformException | IllegalArgumentException e) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "非法任务状态：" + status);
        }
    }

    /** Git 工单只有 OPEN / CLOSED：完成与取消都折叠为关闭。 */
    private static String issueState(TaskStatus status) {
        return status == TaskStatus.DONE || status == TaskStatus.CANCELLED ? "CLOSED" : "OPEN";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 任务页数据按用户查询，再收敛到当前项目（未归属项目的任务也展示）。 */
    private List<GitIssue> projectIssues(ProjectContext ctx) {
        return issues.list(ctx.userId(), null, null, null).stream()
                .filter(i -> i.projectId().isBlank() || i.projectId().equals(ctx.projectId()))
                .toList();
    }

    @Tool(name = "task_create", description = "Create a task on the task board.",
            readOnly = false, concurrencySafe = false)
    public String createTask(ProjectContext ctx,
                             @ToolParam(name = "goal", description = "任务目标（标题）")
                             String goal,
                             @ToolParam(name = "expected_output", required = false,
                                     description = "期望产出（描述）")
                             String expectedOutput,
                             @ToolParam(name = "priority", required = false,
                                     description = "优先级 P0/P1/P2，默认 P2")
                             String priority,
                             @ToolParam(name = "kind", required = false,
                                     description = "类型 BUG / FEAT，默认 FEAT")
                             String kind) {
        return guard(() -> {
            GitIssue issue = issues.create(ctx.userId(), new GitIssue.Draft(ctx.projectId(),
                    "", "", goal, expectedOutput, "OPEN", kind, priority, "", ""));
            return "ok: task " + issue.id() + "  priority=" + issue.priority()
                    + "  state=" + issue.state();
        });
    }

    @Tool(name = "task_update", description = "Update task goal, output or status.",
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
                             String status,
                             @ToolParam(name = "priority", required = false,
                                     description = "P0/P1/P2，可空表示不变")
                             String priority) {
        TaskStatus parsed;
        try {
            parsed = status == null || status.isBlank()
                    ? null : TaskStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: 非法任务状态：" + status;
        }
        return guard(() -> {
            GitIssue current = issues.get(ctx.userId(), taskId);
            GitIssue updated = issues.update(ctx.userId(), taskId, new GitIssue.Draft(
                    current.projectId(), current.provider(), current.repo(),
                    blank(goal) ? current.title() : goal,
                    blank(expectedOutput) ? current.body() : expectedOutput,
                    parsed == null ? current.state() : issueState(parsed),
                    current.kind(), priority, current.labels(), current.assignee()));
            return "ok: task " + updated.id() + "  state=" + updated.state()
                    + "  priority=" + updated.priority();
        });
    }

    @Tool(name = "task_complete", description = "Mark a task DONE.",
            readOnly = false, concurrencySafe = false)
    public String completeTask(ProjectContext ctx,
                               @ToolParam(name = "task_id", description = "任务 id")
                               String taskId) {
        return guard(() -> {
            GitIssue issue = issues.setState(ctx.userId(), taskId, "CLOSED");
            return "ok: task " + issue.id() + "  state=" + issue.state();
        });
    }

    @Tool(name = "task_list", description = "List the workspace task board.",
            readOnly = true, concurrencySafe = true)
    public String listTasks(ProjectContext ctx) {
        return guard(() -> {
            List<GitIssue> tasks = projectIssues(ctx);
            if (tasks.isEmpty()) {
                return "tasks: （空）";
            }
            StringBuilder sb = new StringBuilder("tasks:\n");
            for (GitIssue task : tasks) {
                sb.append("- ").append(task.id())
                        .append("  [").append(task.state()).append("]")
                        .append("  ").append(task.priority())
                        .append("  ").append(task.kind())
                        .append("  ").append(oneLine(task.title(), 120));
                if (task.number().isBlank()) {
                    sb.append("  local");
                } else {
                    sb.append("  ").append(task.provider()).append('/').append(task.repo())
                            .append('#').append(task.number());
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

    private static String formatDispatch(DispatchResult result) {
        StringBuilder sb = new StringBuilder("dispatch: nodes=").append(result.nodes().size())
                .append("  succeeded=").append(result.succeeded())
                .append("  failed=").append(result.failed())
                .append("  skipped=").append(result.skipped())
                .append("  elapsed_ms=").append(result.elapsedMillis()).append('\n');
        if (result.earlyStopped()) {
            sb.append("early_stopped: ").append(result.reason()).append('\n');
        }
        for (DispatchNodeResult node : result.nodes()) {
            sb.append("- ").append(node.nodeId()).append(" [").append(node.status()).append(']');
            if (!node.conversationId().isBlank()) {
                sb.append("  conversation=").append(node.conversationId());
            }
            if (!node.runId().isBlank()) {
                sb.append("  run=").append(node.runId());
            }
            sb.append('\n');
            if (!node.error().isBlank()) {
                sb.append("  error: ").append(oneLine(node.error(), 300)).append('\n');
            }
            if (!node.summary().isBlank()) {
                sb.append("  summary: ").append(oneLine(node.summary(), 600)).append('\n');
            }
        }
        String text = sb.toString().trim();
        // 上下文精益：聚合结果本身也要有上限，细节留给 conversation_summarize
        return text.length() > 6_000
                ? text.substring(0, 6_000) + "\n…（已截断，单节点细节用 conversation_summarize 查询）"
                : text;
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
