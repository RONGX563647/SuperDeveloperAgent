package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.workspace.ConversationManager;

/**
 * 工作区 REST：会话、任务与异步运行。
 *
 * <p>工作区 = 项目。不做项目级 RBAC（任何登录用户都能进入任一项目），
 * 但会话 / 运行只对创建者可见（owner 过滤在 Manager 与存储层各做一次）。
 */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

    private final ConversationManager conversations;
    private final CurrentUser currentUser;

    public WorkspaceController(ConversationManager conversations, CurrentUser currentUser) {
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ 会话

    @GetMapping("/workspaces/{projectId}/conversations")
    public List<ConversationView> list(@PathVariable String projectId,
                                       @RequestParam(defaultValue = "false") boolean includeArchived) {
        String userId = currentUser.requireUserId();
        return conversations.list(projectId, userId, includeArchived).stream()
                .map(ConversationView::from).toList();
    }

    @PostMapping("/workspaces/{projectId}/conversations")
    public ConversationView create(@PathVariable String projectId,
                                   @RequestBody CreateConversationRequest request) {
        String userId = currentUser.requireUserId();
        return ConversationView.from(conversations.createConversation(projectId, userId,
                request.title(), request.env(), request.worktree()));
    }

    /** 调度会话：每个工作区（按用户）一个，不存在时自动创建。 */
    @GetMapping("/workspaces/{projectId}/super")
    public ConversationView supervisor(@PathVariable String projectId) {
        String userId = currentUser.requireUserId();
        return ConversationView.from(conversations.ensureSupervisor(projectId, userId));
    }

    /** 把前端 localStorage 里的历史会话导入服务端（同 id 已存在则跳过）。 */
    @PostMapping("/workspaces/{projectId}/conversations/sync")
    public Map<String, Object> sync(@PathVariable String projectId,
                                    @RequestBody SyncRequest request) {
        String userId = currentUser.requireUserId();
        List<SyncSession> sessions = request.sessions() == null
                ? List.of() : request.sessions().stream().limit(200).toList();
        int imported = 0;
        int skipped = 0;
        List<String> rejected = new ArrayList<>();
        for (SyncSession session : sessions) {
            try {
                boolean exists = session.id() != null
                        && conversations.findOwned(session.id(), userId).isPresent();
                if (exists) {
                    skipped++;
                    continue;
                }
                conversations.importSession(projectId, userId, session.id(), session.title(),
                        session.at());
                imported++;
            } catch (IllegalArgumentException e) {
                rejected.add(String.valueOf(session.id()));
            }
        }
        return Map.of("imported", imported, "skipped", skipped, "rejected", rejected);
    }

    @PatchMapping("/conversations/{conversationId}")
    public ConversationView update(@PathVariable String conversationId,
                                   @RequestBody UpdateConversationRequest request) {
        String userId = currentUser.requireUserId();
        ConversationPatch patch = new ConversationPatch(request.title(), request.env(),
                request.worktree(), null, null, request.pinned(), request.archived());
        return conversations.updateOwned(conversationId, userId, patch)
                .map(ConversationView::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "会话不存在或无权访问：" + conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> delete(@PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        if (!conversations.deleteOwned(conversationId, userId)) {
            throw new IllegalArgumentException("会话不存在或无权访问：" + conversationId);
        }
        return Map.of("deleted", true);
    }

    @PostMapping("/conversations/{conversationId}/send")
    public Map<String, Object> send(@PathVariable String conversationId,
                                    @RequestBody SendRequest request) {
        String userId = currentUser.requireUserId();
        return Map.of("runId", conversations.send(conversationId, userId, request.message()));
    }

    // ------------------------------------------------------------------ 运行

    @GetMapping("/workspaces/{projectId}/runs")
    public List<RunView> runs(@PathVariable String projectId,
                              @RequestParam(defaultValue = "50") int limit) {
        String userId = currentUser.requireUserId();
        return conversations.runsOfProject(projectId, clamp(limit)).stream()
                .filter(run -> run.ownerUserId().equals(userId))
                .map(RunView::from).toList();
    }

    @GetMapping("/conversations/{conversationId}/runs")
    public List<RunView> conversationRuns(@PathVariable String conversationId,
                                          @RequestParam(defaultValue = "20") int limit) {
        String userId = currentUser.requireUserId();
        return conversations.runsOfConversation(conversationId, userId, clamp(limit)).stream()
                .map(RunView::from).toList();
    }

    @GetMapping("/runs/{runId}")
    public RunView run(@PathVariable String runId) {
        String userId = currentUser.requireUserId();
        return RunView.from(conversations.runStatus(runId, userId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        String userId = currentUser.requireUserId();
        return Map.of("cancelled", conversations.cancelRun(runId, userId));
    }

    // ------------------------------------------------------------------ 任务

    @GetMapping("/workspaces/{projectId}/tasks")
    public List<TaskView> tasks(@PathVariable String projectId) {
        String userId = currentUser.requireUserId();
        return conversations.listTasks(projectId, userId).stream().map(TaskView::from).toList();
    }

    @PostMapping("/workspaces/{projectId}/tasks")
    public TaskView createTask(@PathVariable String projectId,
                               @RequestBody CreateTaskRequest request) {
        String userId = currentUser.requireUserId();
        return TaskView.from(conversations.createTask(projectId, userId, request.goal(),
                request.expectedOutput(), request.priority() == null ? 3 : request.priority(),
                request.conversationId()));
    }

    @PatchMapping("/workspaces/{projectId}/tasks/{taskId}")
    public TaskView updateTask(@PathVariable String projectId, @PathVariable String taskId,
                               @RequestBody UpdateTaskRequest request) {
        String userId = currentUser.requireUserId();
        TaskStatus status = parseStatus(request.status());
        return TaskView.from(conversations.updateTask(projectId, userId, taskId,
                request.goal(), request.expectedOutput(), status));
    }

    private static TaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法任务状态：" + value);
        }
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    // ------------------------------------------------------------------ DTO

    public record ConversationView(String id, String projectId, String title, String kind,
                                   String role, String env, String worktree, String status,
                                   String summary, boolean pinned, boolean archived,
                                   long lastActivityAt, long createdAt) {
        static ConversationView from(Conversation c) {
            return new ConversationView(c.id(), c.projectId(), c.title(), c.kind().name(),
                    c.role(), c.env(), c.worktree(), c.status().name(), c.summary(),
                    c.pinned(), c.archived(), c.lastActivityAt(), c.createdAt());
        }
    }

    public record RunView(String id, String projectId, String conversationId, String goal,
                          String status, String result, String error, long createdAt,
                          long startedAt, long endedAt) {
        static RunView from(ConversationRun run) {
            return new RunView(run.id(), run.projectId(), run.conversationId(), run.goal(),
                    run.status().name(), run.result(), run.error(), run.createdAt(),
                    run.startedAt(), run.endedAt());
        }
    }

    public record TaskView(String id, String projectId, String conversationId, String goal,
                           String expectedOutput, String status, int priority,
                           long createdAt, long updatedAt) {
        static TaskView from(WorkspaceTask task) {
            return new TaskView(task.id(), task.projectId(), task.conversationId(), task.goal(),
                    task.expectedOutput(), task.status().name(), task.priority(),
                    task.createdAt(), task.updatedAt());
        }
    }

    public record CreateConversationRequest(String title, String env, String worktree) {
    }

    public record UpdateConversationRequest(String title, String env, String worktree,
                                            Boolean pinned, Boolean archived) {
    }

    public record SendRequest(String message) {
    }

    public record SyncRequest(List<SyncSession> sessions) {
    }

    public record SyncSession(String id, String title, Long at) {
    }

    public record CreateTaskRequest(String goal, String expectedOutput, Integer priority,
                                    String conversationId) {
    }

    public record UpdateTaskRequest(String goal, String expectedOutput, String status) {
    }
}
