package top.rongx.agent.runtime.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationKind;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspaceSupervisorPort;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.conversation.WorkspaceTaskRepository;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.port.UserRolePort;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;

/**
 * 工作区会话管理：会话元数据、任务、异步运行与调度 Agent 的工具能力。
 *
 * <p>工作区 = 项目；每个工作区有一个 {@code SUPERVISOR} 会话（按用户隔离）。
 * 调度 Agent 只能管理会话（创建 / 改名 / 归档 / 派发 / 跟踪 / 汇总），
 * 真正的写操作仍由子会话按环境策略执行，且生产写操作仍需人工确认。
 */
@Service
public class ConversationManager implements WorkspaceSupervisorPort {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private static final String DEFAULT_ENV = "test";
    private static final int MAX_TITLE = 60;
    private static final int MAX_TEXT = 4_000;
    private static final int MAX_TASK_GOAL = 1_000;
    private static final long MAX_WAIT_MILLIS = 60_000L;

    private final ConversationRepository conversations;
    private final ConversationRunRepository runs;
    private final WorkspaceTaskRepository tasks;
    private final ProjectRepository projects;
    private final WorkspaceRunExecutor executor;
    private final UserRolePort userRoles;

    public ConversationManager(ConversationRepository conversations,
                               ConversationRunRepository runs,
                               WorkspaceTaskRepository tasks,
                               ProjectRepository projects,
                               WorkspaceRunExecutor executor,
                               UserRolePort userRoles) {
        this.conversations = conversations;
        this.runs = runs;
        this.tasks = tasks;
        this.projects = projects;
        this.executor = executor;
        this.userRoles = userRoles;
    }

    /** 每个工作区（按用户）一个调度会话；不存在时创建。 */
    public Conversation ensureSupervisor(String projectId, String ownerUserId) {
        requireProject(projectId);
        requireUser(ownerUserId);
        return conversations.findSupervisor(projectId, ownerUserId)
                .orElseGet(() -> conversations.save(new Conversation(
                        "sup-" + shortId(), projectId, ownerUserId, "工作区调度",
                        ConversationKind.SUPERVISOR, "supervisor", resolveEnv(projectId, null),
                        null, ConversationStatus.IDLE,
                        "统一管理本工作区会话：创建、派发、跟踪与汇总。",
                        true, false, null,
                        System.currentTimeMillis(), System.currentTimeMillis())));
    }

    /**
     * 普通对话入口的会话登记：首条消息落标题，之后只刷新活跃时间与环境。
     *
     * <p>失败不阻断对话：会话元数据是辅助信息，不能因为 SQLite 抖动让聊天不可用。
     */
    public void ensureFromChat(ProjectContext ctx, String sessionId, String message, String worktree) {
        if (!validId(sessionId)) {
            return;
        }
        try {
            Optional<Conversation> existing = conversations.find(sessionId);
            if (existing.isEmpty()) {
                conversations.save(Conversation.normal(sessionId, ctx.projectId(), ctx.userId(),
                        titleOf(message), ctx.env(), worktree));
                return;
            }
            Conversation conversation = existing.get();
            if (!conversation.ownerUserId().equals(ctx.userId())
                    || !conversation.projectId().equals(ctx.projectId())) {
                return;
            }
            String title = conversation.title();
            if (("新会话".equals(title) || title.isBlank()) && message != null && !message.isBlank()) {
                title = titleOf(message);
            }
            conversations.update(sessionId, ctx.userId(),
                    new ConversationPatch(title, ctx.env(), worktree, null, null, null, null));
        } catch (RuntimeException e) {
            // 元数据写入失败不影响对话本身
            log.debug("会话元数据登记失败 session={}：{}", sessionId, e.getMessage());
        }
    }

    /** 导入前端 localStorage 里的历史会话（同 id 已存在则跳过）。 */
    public Conversation importSession(String projectId, String ownerUserId, String id,
                                      String title, Long at) {
        if (!validId(id)) {
            throw new IllegalArgumentException("会话 id 不合法：" + id);
        }
        return conversations.find(id)
                .filter(c -> c.ownerUserId().equals(ownerUserId)
                        && c.projectId().equals(projectId))
                .orElseGet(() -> {
                    long time = at == null || at <= 0 ? System.currentTimeMillis() : at;
                    return conversations.save(new Conversation(id, projectId, ownerUserId,
                            titleOf(title), ConversationKind.NORMAL, "chat",
                            resolveEnv(projectId, null), null, ConversationStatus.IDLE, "",
                            false, false, null, time, time));
                });
    }

    public List<Conversation> list(String projectId, String ownerUserId, boolean includeArchived) {
        requireProject(projectId);
        requireUser(ownerUserId);
        return conversations.findByProject(projectId, ownerUserId, includeArchived);
    }

    public Optional<Conversation> findOwned(String conversationId, String ownerUserId) {
        return conversations.findByOwner(conversationId, ownerUserId);
    }

    public Optional<Conversation> updateOwned(String conversationId, String ownerUserId,
                                              ConversationPatch patch) {
        Optional<Conversation> existing = conversations.findByOwner(conversationId, ownerUserId);
        if (existing.isEmpty() || patch == null) {
            return Optional.empty();
        }
        Conversation current = existing.get();
        if (patch.env() != null && !patch.env().isBlank()) {
            resolveEnv(current.projectId(), patch.env());
        }
        if (patch.worktree() != null) {
            new ProjectContext(current.projectId(), current.env(), ownerUserId, "",
                    patch.worktree());
        }
        return conversations.update(conversationId, ownerUserId, patch);
    }

    public boolean deleteOwned(String conversationId, String ownerUserId) {
        return conversations.delete(conversationId, ownerUserId);
    }

    public List<ConversationRun> runsOfProject(String projectId, int limit) {
        requireProject(projectId);
        return runs.findRunsByProject(projectId, limit);
    }

    public List<ConversationRun> runsOfConversation(String conversationId, String ownerUserId,
                                                    int limit) {
        findOwned(conversationId, ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问"));
        return runs.findRunsByConversation(conversationId, limit);
    }

    // ------------------------------------------------------------------ 调度工具能力

    @Override
    public String snapshot(String projectId, String ownerUserId) {
        requireProject(projectId);
        requireUser(ownerUserId);
        Project project = projects.findById(projectId).orElse(null);
        List<Conversation> all = conversations.findByProject(projectId, ownerUserId, false);
        long running = all.stream().filter(c -> c.status() == ConversationStatus.RUNNING).count();
        long failed = all.stream().filter(c -> c.status() == ConversationStatus.FAILED).count();
        List<WorkspaceTask> allTasks = tasks.findTasksByProject(projectId);
        long todo = allTasks.stream().filter(t -> t.status() == TaskStatus.TODO).count();
        long doing = allTasks.stream().filter(t -> t.status() == TaskStatus.RUNNING).count();
        long done = allTasks.stream().filter(t -> t.status() == TaskStatus.DONE).count();
        List<ConversationRun> active = runs.findRunsByProject(projectId, 20).stream()
                .filter(r -> !r.status().terminal()).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("workspace: ").append(projectId)
                .append(project == null ? "" : " (" + project.name() + ")").append('\n')
                .append("conversations: ").append(all.size())
                .append("  running=").append(running)
                .append("  failed=").append(failed).append('\n')
                .append("tasks: ").append(allTasks.size())
                .append("  todo=").append(todo)
                .append("  running=").append(doing)
                .append("  done=").append(done).append('\n')
                .append("active_runs: ").append(active.size()).append('\n');
        if (!active.isEmpty()) {
            sb.append("runs:\n");
            for (ConversationRun run : active) {
                sb.append("  - ").append(run.id()).append("  [").append(run.status()).append("]  ")
                        .append(run.conversationId()).append("  ")
                        .append(oneLine(run.goal(), 80)).append('\n');
            }
        }
        return sb.toString().trim();
    }

    @Override
    public List<Conversation> listConversations(String projectId, String ownerUserId,
                                                boolean includeArchived) {
        return list(projectId, ownerUserId, includeArchived);
    }

    @Override
    public List<Conversation> searchConversations(String projectId, String ownerUserId,
                                                  String keyword) {
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return list(projectId, ownerUserId, false);
        }
        return conversations.findByProject(projectId, ownerUserId, true).stream()
                .filter(c -> c.title().toLowerCase(Locale.ROOT).contains(query)
                        || c.summary().toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    @Override
    public Conversation createConversation(String projectId, String ownerUserId, String title,
                                           String env, String worktree) {
        requireProject(projectId);
        requireUser(ownerUserId);
        String cleanWorktree = worktree == null || worktree.isBlank() ? null : worktree.trim();
        // 未指定环境时沿用调度会话当前环境（工作区首页切换环境后新建会话即跟随）
        String requestedEnv = env;
        if (requestedEnv == null || requestedEnv.isBlank()) {
            requestedEnv = conversations.findSupervisor(projectId, ownerUserId)
                    .map(Conversation::env).orElse(null);
        }
        String resolvedEnv = resolveEnv(projectId, requestedEnv);
        // 借用 ProjectContext 的校验：工作树名不允许包含分隔符或 ..
        new ProjectContext(projectId, resolvedEnv, ownerUserId, "", cleanWorktree);
        Conversation conversation = Conversation.normal("c-" + shortId(), projectId, ownerUserId,
                titleOf(title), resolvedEnv, cleanWorktree);
        return conversations.save(conversation);
    }

    @Override
    public boolean renameConversation(String conversationId, String ownerUserId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        return conversations.update(conversationId, ownerUserId,
                ConversationPatch.title(title)).isPresent();
    }

    @Override
    public boolean setPinned(String conversationId, String ownerUserId, boolean pinned) {
        return conversations.update(conversationId, ownerUserId,
                ConversationPatch.pin(pinned)).isPresent();
    }

    @Override
    public boolean setArchived(String conversationId, String ownerUserId, boolean archived) {
        return conversations.update(conversationId, ownerUserId,
                ConversationPatch.archive(archived)).isPresent();
    }

    @Override
    public String send(String conversationId, String ownerUserId, String text) {
        requireUser(ownerUserId);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("消息不能超过 " + MAX_TEXT + " 字");
        }
        Conversation conversation = conversations.findByOwner(conversationId, ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问：" + conversationId));
        if (conversation.kind() == ConversationKind.SUPERVISOR) {
            return "err: 不能给调度会话本身派发任务（调度深度固定为 1）";
        }
        Optional<ConversationRun> active = runs.findActiveByConversation(conversationId);
        if (active.isPresent()) {
            throw new IllegalArgumentException("会话正在运行，请先等待或取消：runId="
                    + active.get().id());
        }
        String goalKey = goalKey(text);
        Optional<ConversationRun> duplicate = runs.findActiveByGoal(conversation.projectId(), goalKey);
        if (duplicate.isPresent()) {
            return duplicate.get().id();
        }
        Project project = projects.findById(conversation.projectId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "项目不存在: " + conversation.projectId()));
        boolean readOnly = userRoles.isReadOnly(ownerUserId);
        ProjectContext ctx = new ProjectContext(project.id(),
                resolveEnv(project.id(), conversation.env()), ownerUserId, project.repoUrl(),
                conversation.worktree(), readOnly);
        long now = System.currentTimeMillis();
        ConversationRun run = new ConversationRun("run-" + shortId(), project.id(),
                conversation.id(), ownerUserId, text, goalKey, RunStatus.QUEUED,
                "", "", now, 0, 0);
        runs.saveRun(run);
        conversations.update(conversation.id(), ownerUserId,
                ConversationPatch.status(ConversationStatus.RUNNING));
        executor.submit(ctx, run, text);
        return run.id();
    }

    @Override
    public ConversationRun runStatus(String runId, String ownerUserId) {
        return runs.findRun(runId)
                .filter(r -> r.ownerUserId().equals(ownerUserId))
                .orElseThrow(() -> new IllegalArgumentException("运行不存在或无权访问：" + runId));
    }

    @Override
    public ConversationRun waitForRun(String runId, String ownerUserId, long timeoutMillis) {
        ConversationRun run = runStatus(runId, ownerUserId);
        if (!run.status().terminal()) {
            executor.await(runId, Math.min(Math.max(1, timeoutMillis), MAX_WAIT_MILLIS));
        }
        return runStatus(runId, ownerUserId);
    }

    @Override
    public boolean cancelRun(String runId, String ownerUserId) {
        ConversationRun run = runStatus(runId, ownerUserId);
        if (run.status().terminal()) {
            return run.status() == RunStatus.CANCELLED;
        }
        if (executor.cancel(runId)) {
            return true;
        }
        // 进程重启后内存句柄已丢失：至少把状态收敛，避免永远显示运行中
        runs.updateRun(run.withStatus(RunStatus.CANCELLED, run.result(), "已取消",
                run.startedAt(), System.currentTimeMillis()));
        conversations.update(run.conversationId(), ownerUserId,
                ConversationPatch.status(ConversationStatus.CANCELLED));
        return true;
    }

    @Override
    public String summarize(String conversationId, String ownerUserId) {
        Conversation conversation = conversations.findByOwner(conversationId, ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问：" + conversationId));
        List<ConversationRun> history = runs.findRunsByConversation(conversationId, 1);
        StringBuilder sb = new StringBuilder();
        sb.append("conversation: ").append(conversation.id()).append('\n')
                .append("title: ").append(conversation.title()).append('\n')
                .append("status: ").append(conversation.status()).append('\n');
        if (history.isEmpty()) {
            sb.append("summary: （还没有运行记录）");
            return sb.toString();
        }
        ConversationRun latest = history.get(0);
        sb.append("last_run: ").append(latest.id())
                .append("  ").append(latest.status()).append('\n');
        if (!latest.error().isBlank()) {
            sb.append("error: ").append(oneLine(latest.error(), 300)).append('\n');
        }
        sb.append("summary:\n---\n")
                .append(latest.result().isBlank() ? "（无文本输出）" : latest.result())
                .append("\n---");
        return sb.toString();
    }

    @Override
    public WorkspaceTask createTask(String projectId, String ownerUserId, String goal,
                                    String expectedOutput, int priority, String conversationId) {
        requireProject(projectId);
        requireUser(ownerUserId);
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("任务目标不能为空");
        }
        if (goal.length() > MAX_TASK_GOAL) {
            throw new IllegalArgumentException("任务目标不能超过 " + MAX_TASK_GOAL + " 字");
        }
        if (conversationId != null && !conversationId.isBlank()) {
            conversations.findByOwner(conversationId, ownerUserId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "关联会话不存在或无权访问：" + conversationId));
        }
        long now = System.currentTimeMillis();
        return tasks.saveTask(new WorkspaceTask("task-" + shortId(), projectId,
                conversationId == null || conversationId.isBlank() ? null : conversationId,
                goal, expectedOutput, TaskStatus.TODO, priority, now, now));
    }

    @Override
    public WorkspaceTask updateTask(String projectId, String ownerUserId, String taskId,
                                    String goal, String expectedOutput, TaskStatus status) {
        requireProject(projectId);
        requireUser(ownerUserId);
        WorkspaceTask task = tasks.findTask(taskId)
                .filter(t -> t.projectId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        String nextGoal = goal == null || goal.isBlank() ? task.goal() : goal.trim();
        if (nextGoal.length() > MAX_TASK_GOAL) {
            throw new IllegalArgumentException("任务目标不能超过 " + MAX_TASK_GOAL + " 字");
        }
        WorkspaceTask updated = new WorkspaceTask(task.id(), task.projectId(),
                task.conversationId(), nextGoal,
                expectedOutput == null ? task.expectedOutput() : expectedOutput.trim(),
                status == null ? task.status() : status, task.priority(),
                task.createdAt(), System.currentTimeMillis());
        return tasks.updateTask(updated);
    }

    @Override
    public List<WorkspaceTask> listTasks(String projectId, String ownerUserId) {
        requireProject(projectId);
        requireUser(ownerUserId);
        return tasks.findTasksByProject(projectId);
    }

    // ------------------------------------------------------------------ 内部

    private String resolveEnv(String projectId, String requested) {
        String env = requested == null || requested.isBlank()
                ? DEFAULT_ENV : requested.trim().toLowerCase(Locale.ROOT);
        List<ProjectEnv> envs = projects.findEnvs(projectId);
        if (envs.isEmpty()) {
            if (!List.of("dev", "test", "prod").contains(env)) {
                throw new IllegalArgumentException("环境不合法：" + env);
            }
            return env;
        }
        boolean known = envs.stream().map(ProjectEnv::env).anyMatch(env::equals);
        if (!known) {
            throw new IllegalArgumentException("环境不属于该项目：" + env);
        }
        return env;
    }

    private static String titleOf(String text) {
        if (text == null || text.isBlank()) {
            return "新会话";
        }
        String oneLine = text.trim().replaceAll("\\s+", " ");
        return oneLine.length() > MAX_TITLE ? oneLine.substring(0, MAX_TITLE) : oneLine;
    }

    private static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.trim().replaceAll("\\s+", " ");
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    private static String goalKey(String text) {
        String normalized = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static void requireProject(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("非法项目 ID：" + projectId);
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户不能为空");
        }
    }
}
