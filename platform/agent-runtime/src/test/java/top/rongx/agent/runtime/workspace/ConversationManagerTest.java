package top.rongx.agent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationKind;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.conversation.WorkspaceTaskRepository;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.runtime.ChatService;
import top.rongx.agent.runtime.ChatSessionRegistry;

/**
 * 工作区管理：调度会话、异步运行、防重复、取消与任务板。
 *
 * <p>不启动 Spring：用内存仓库 + 可控的 ChatService 替身，验证排队/状态落库/owner 隔离。
 */
class ConversationManagerTest {

    private static final String PROJECT = "RY";
    private static final String USER = "alice";

    private final InMemoryStore store = new InMemoryStore();
    private final ProjectRepository projects = new FakeProjects();

    private ConversationManager manager(ChatService chat, int concurrency) {
        WorkspaceRunExecutor executor = new WorkspaceRunExecutor(provider(() -> chat), store, store,
                new WorkspaceEventBus(), top.rongx.agent.domain.run.RunLedgerRepository.noop(),
                concurrency);
        return new ConversationManager(store, store, store, projects, executor, userId -> false);
    }

    private static <T> ObjectProvider<T> provider(Supplier<T> supplier) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return supplier.get();
            }
        };
    }

    @Test
    @DisplayName("调度会话按用户唯一；新建会话默认沿用调度环境")
    void supervisorAndCreate() {
        ConversationManager manager = manager(new SilentChat(), 2);
        Conversation first = manager.ensureSupervisor(PROJECT, USER);
        Conversation again = manager.ensureSupervisor(PROJECT, USER);
        assertEquals(first.id(), again.id());
        assertEquals(ConversationKind.SUPERVISOR, first.kind());
        assertFalse(manager.ensureSupervisor(PROJECT, "bob").id().equals(first.id()));

        Conversation created = manager.createConversation(PROJECT, USER, "修复构建", null, "wt-a");
        assertEquals("test", created.env());
        assertEquals("wt-a", created.worktree());
        assertEquals(1, manager.list(PROJECT, USER, false).stream()
                .filter(c -> c.kind() == ConversationKind.NORMAL).count());
        assertTrue(manager.list(PROJECT, "bob", false).stream()
                .noneMatch(c -> c.kind() == ConversationKind.NORMAL));
    }

    @Test
    @DisplayName("发送消息异步执行并落状态；完成后会话变 SUCCEEDED")
    void sendCompletesAndPersists() {
        ConversationManager manager = manager(new SilentChat(), 2);
        Conversation created = manager.createConversation(PROJECT, USER, "t", "test", null);
        String runId = manager.send(created.id(), USER, "检查构建");
        assertTrue(runId.startsWith("run-"));
        ConversationRun done = manager.waitForRun(runId, USER, 3_000);
        assertEquals(RunStatus.SUCCEEDED, done.status());
        assertEquals(ConversationStatus.SUCCEEDED,
                manager.findOwned(created.id(), USER).orElseThrow().status());
        assertFalse(store.runs.isEmpty());
    }

    @Test
    @DisplayName("同一目标在运行中不重复派发；不同会话共用去重；可取消")
    void duplicateGoalAndCancel() {
        ConversationManager manager = manager(new NeverChat(), 2);
        Conversation a = manager.createConversation(PROJECT, USER, "a", "test", null);
        Conversation b = manager.createConversation(PROJECT, USER, "b", "test", null);
        String first = manager.send(a.id(), USER, "  修复 构建  ");
        String second = manager.send(b.id(), USER, "修复   构建");
        assertEquals(first, second, "规范化后同一目标应复用运行");

        // 同一会话不能再并发派发
        assertThrows(IllegalArgumentException.class,
                () -> manager.send(a.id(), USER, "另一个目标"));
        // 调度会话不能给自己派发（深度固定为 1）
        Conversation supervisor = manager.ensureSupervisor(PROJECT, USER);
        assertTrue(manager.send(supervisor.id(), USER, "x").startsWith("err:"));

        assertTrue(manager.cancelRun(first, USER));
        assertEquals(RunStatus.CANCELLED, manager.runStatus(first, USER).status());
        assertEquals(ConversationStatus.CANCELLED,
                manager.findOwned(a.id(), USER).orElseThrow().status());
        // 越权看不到运行
        assertThrows(IllegalArgumentException.class, () -> manager.runStatus(first, "bob"));
    }

    @Test
    @DisplayName("任务板：创建、更新、完成；跨项目不可更新")
    void tasks() {
        ConversationManager manager = manager(new SilentChat(), 1);
        WorkspaceTask task = manager.createTask(PROJECT, USER, "补单测", "覆盖率 80%", 1, null);
        assertEquals(TaskStatus.TODO, task.status());
        assertEquals(1, manager.listTasks(PROJECT, USER).size());

        WorkspaceTask done = manager.updateTask(PROJECT, USER, task.id(), null, null,
                TaskStatus.DONE);
        assertEquals(TaskStatus.DONE, done.status());
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateTask("OTHER", USER, task.id(), null, null, TaskStatus.TODO));
    }

    @Test
    @DisplayName("搜索命中标题/摘要，归档默认隐藏")
    void searchAndArchive() {
        ConversationManager manager = manager(new SilentChat(), 1);
        Conversation c = manager.createConversation(PROJECT, USER, "登录页报错", "test", null);
        manager.updateOwned(c.id(), USER, new ConversationPatch(null, null, null,
                "验证码不显示", null, null, null));
        assertEquals(1, manager.searchConversations(PROJECT, USER, "登录").size());
        assertEquals(1, manager.searchConversations(PROJECT, USER, "验证码").size());

        manager.setArchived(c.id(), USER, true);
        assertTrue(manager.list(PROJECT, USER, false).isEmpty());
        assertEquals(1, manager.list(PROJECT, USER, true).size());
    }

    /** 立即结束的对话流。 */
    private static final class SilentChat extends ChatService {
        SilentChat() {
            super(null, new ChatSessionRegistry());
        }

        @Override
        public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text,
                                       String role, List<String> skills, List<String> mcpServers) {
            return Flux.empty();
        }
    }

    /** 永不结束的对话流（用于取消）。 */
    private static final class NeverChat extends ChatService {
        NeverChat() {
            super(null, new ChatSessionRegistry());
        }

        @Override
        public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text,
                                       String role, List<String> skills, List<String> mcpServers) {
            return Flux.never();
        }
    }

    private static final class FakeProjects implements ProjectRepository {
        private final Project project = new Project(PROJECT, "RuoYi", "https://gitee.com/x/RY.git",
                "gitee", "ruoyi-plus", 18000, 18100);

        @Override
        public Optional<Project> findById(String projectId) {
            return PROJECT.equals(projectId) ? Optional.of(project) : Optional.empty();
        }

        @Override
        public List<Project> findAll() {
            return List.of(project);
        }

        @Override
        public List<ProjectEnv> findEnvs(String projectId) {
            return PROJECT.equals(projectId)
                    ? List.of(new ProjectEnv(PROJECT, "dev", "", ""),
                    new ProjectEnv(PROJECT, "test", "", ""),
                    new ProjectEnv(PROJECT, "prod", "", ""))
                    : List.of();
        }
    }

    /** 三份仓库接口的内存实现（测试专用）。 */
    private static final class InMemoryStore implements ConversationRepository,
            ConversationRunRepository, WorkspaceTaskRepository {
        private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<String, ConversationRun> runs = new ConcurrentHashMap<>();
        private final Map<String, WorkspaceTask> tasks = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Conversation save(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
            return conversation;
        }

        @Override
        public Optional<Conversation> find(String id) {
            return Optional.ofNullable(conversations.get(id));
        }

        @Override
        public Optional<Conversation> findByOwner(String id, String ownerUserId) {
            return find(id).filter(c -> c.ownerUserId().equals(ownerUserId));
        }

        @Override
        public List<Conversation> findByProject(String projectId, String ownerUserId,
                                                boolean includeArchived) {
            return conversations.values().stream()
                    .filter(c -> c.projectId().equals(projectId))
                    .filter(c -> c.ownerUserId().equals(ownerUserId))
                    .filter(c -> includeArchived || !c.archived())
                    .sorted(Comparator.comparingLong(Conversation::lastActivityAt).reversed())
                    .toList();
        }

        @Override
        public Optional<Conversation> findSupervisor(String projectId, String ownerUserId) {
            return conversations.values().stream()
                    .filter(c -> c.kind() == ConversationKind.SUPERVISOR)
                    .filter(c -> c.projectId().equals(projectId))
                    .filter(c -> c.ownerUserId().equals(ownerUserId))
                    .findFirst();
        }

        @Override
        public Optional<Conversation> update(String id, String ownerUserId,
                                             ConversationPatch patch) {
            return findByOwner(id, ownerUserId).map(current -> {
                Conversation merged = new Conversation(id, current.projectId(), ownerUserId,
                        patch.title() == null ? current.title() : patch.title(),
                        current.kind(), current.role(),
                        patch.env() == null ? current.env() : patch.env(),
                        patch.worktree() == null ? current.worktree()
                                : (patch.worktree().isBlank() ? null : patch.worktree()),
                        patch.status() == null ? current.status() : patch.status(),
                        patch.summary() == null ? current.summary() : patch.summary(),
                        patch.pinned() == null ? current.pinned() : patch.pinned(),
                        patch.archived() == null ? current.archived() : patch.archived(),
                        current.parentConversationId(), System.currentTimeMillis(),
                        current.createdAt());
                conversations.put(id, merged);
                return merged;
            });
        }

        @Override
        public boolean delete(String id, String ownerUserId) {
            return findByOwner(id, ownerUserId).map(c -> conversations.remove(id) != null)
                    .orElse(false);
        }

        @Override
        public ConversationRun saveRun(ConversationRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public ConversationRun updateRun(ConversationRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<ConversationRun> findRun(String id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public Optional<ConversationRun> findActiveByConversation(String conversationId) {
            return runs.values().stream()
                    .filter(r -> r.conversationId().equals(conversationId))
                    .filter(r -> !r.status().terminal())
                    .findFirst();
        }

        @Override
        public Optional<ConversationRun> findActiveByGoal(String projectId, String goalKey) {
            return runs.values().stream()
                    .filter(r -> r.projectId().equals(projectId))
                    .filter(r -> r.goalKey().equals(goalKey))
                    .filter(r -> !r.status().terminal())
                    .findFirst();
        }

        @Override
        public List<ConversationRun> findRunsByProject(String projectId, int limit) {
            return runs.values().stream()
                    .filter(r -> r.projectId().equals(projectId))
                    .sorted(Comparator.comparingLong(ConversationRun::createdAt).reversed())
                    .limit(limit).toList();
        }

        @Override
        public List<ConversationRun> findRunsByConversation(String conversationId, int limit) {
            return runs.values().stream()
                    .filter(r -> r.conversationId().equals(conversationId))
                    .sorted(Comparator.comparingLong(ConversationRun::createdAt).reversed())
                    .limit(limit).toList();
        }

        @Override
        public WorkspaceTask saveTask(WorkspaceTask task) {
            tasks.put(task.id(), task);
            return task;
        }

        @Override
        public WorkspaceTask updateTask(WorkspaceTask task) {
            tasks.put(task.id(), task);
            return task;
        }

        @Override
        public Optional<WorkspaceTask> findTask(String id) {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public List<WorkspaceTask> findTasksByProject(String projectId) {
            List<WorkspaceTask> out = new ArrayList<>(tasks.values().stream()
                    .filter(t -> t.projectId().equals(projectId)).toList());
            out.sort(Comparator.comparingInt(WorkspaceTask::priority));
            return out;
        }

        @Override
        public List<WorkspaceTask> findTasksByConversation(String conversationId) {
            return tasks.values().stream()
                    .filter(t -> conversationId.equals(t.conversationId())).toList();
        }
    }
}
