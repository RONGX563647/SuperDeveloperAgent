package top.rongx.agent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import top.rongx.agent.domain.conversation.ConversationTranscriptRepository;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.conversation.DispatchPlan;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.TimelineEntry;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.conversation.WorkspaceTaskRepository;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.runtime.ChatService;
import top.rongx.agent.runtime.ChatSessionRegistry;
import top.rongx.agent.domain.error.PlatformException;

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
        return manager(store, chat, concurrency);
    }

    private ConversationManager manager(InMemoryStore store, ChatService chat, int concurrency) {
        WorkspaceRunExecutor executor = new WorkspaceRunExecutor(provider(() -> chat), store, store,
                store, new WorkspaceEventBus(),
                top.rongx.agent.domain.run.RunLedgerRepository.noop(), concurrency);
        return new ConversationManager(store, store, store, store, store, projects, executor, userId -> false,
                top.rongx.agent.domain.conversation.SessionDataCleaner.noop(),
                DispatchPlan.DEFAULT_MAX_PARALLEL, DispatchPlan.DEFAULT_MAX_DURATION_SECONDS);
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
    @DisplayName("异步运行结束后自动落会话时间线，子会话打开时能看到提问与产出")
    void runWritesTranscript() {
        ConversationManager manager = manager(new EchoChat(), 1);
        Conversation c = manager.createConversation(PROJECT, USER, "t", "test", null);
        String runId = manager.send(c.id(), USER, "检查构建");
        assertEquals(RunStatus.SUCCEEDED, manager.waitForRun(runId, USER, 3_000).status());

        String payload = manager.transcript(c.id(), USER).orElseThrow();
        assertTrue(payload.contains("检查构建"), "时间线应包含派发的提问：" + payload);
        assertTrue(payload.contains("已完成构建检查"), "时间线应包含运行产出：" + payload);
        assertTrue(payload.contains("\"tools\""), "时间线结构需与前端一致（messages + tools）");
    }

    @Test
    @DisplayName("服务端事件时间线按 seq 游标增量读取，且只有 owner 能读")
    void eventTimelineIsCursorReadable() {
        ConversationManager manager = manager(new SilentChat(), 1);
        Conversation c = manager.createConversation(PROJECT, USER, "t", "test", null);
        long first = store.append(c.id(), "user", "user", "修一下构建", null, null);
        long second = store.append(c.id(), "tool_call", "assistant", null, "read_file", "call-1");
        long third = store.append(c.id(), "tool_result", "tool", "file content", "read_file", "call-1");

        List<TimelineEntry> all = manager.timeline(c.id(), USER, 0, 100);
        assertEquals(List.of("user", "tool_call", "tool_result"),
                all.stream().map(TimelineEntry::kind).toList());
        // 断线续传：游标之后的记录
        assertEquals(List.of(second, third), manager.timeline(c.id(), USER, first, 100).stream()
                .map(TimelineEntry::seq).toList());
        assertEquals(List.of(third), manager.timeline(c.id(), USER, second, 100).stream()
                .map(TimelineEntry::seq).toList());
        // 别人的会话读不到
        assertThrows(PlatformException.class, () -> manager.timeline(c.id(), "bob", 0, 100));
    }

    @Test
    @DisplayName("同一会话多次运行按追加方式累积时间线，不覆盖历史")
    void runAppendsTranscript() {
        ConversationManager manager = manager(new EchoChat(), 1);
        Conversation c = manager.createConversation(PROJECT, USER, "t", "test", null);
        manager.waitForRun(manager.send(c.id(), USER, "第一轮"), USER, 3_000);
        manager.waitForRun(manager.send(c.id(), USER, "第二轮"), USER, 3_000);

        String payload = manager.transcript(c.id(), USER).orElseThrow();
        assertTrue(payload.contains("第一轮") && payload.contains("第二轮"),
                "第二轮运行时不能覆盖第一轮的时间线：" + payload);
    }

    @Test
    @DisplayName("时间线落库之前不对外暴露终态，避免下一轮读到旧时间线")
    void terminalStatusVisibleOnlyAfterTranscriptSaved() throws Exception {
        BlockingStore blocking = new BlockingStore();
        ConversationManager manager = manager(blocking, new EchoChat(), 1);
        Conversation c = manager.createConversation(PROJECT, USER, "t", "test", null);
        String runId = manager.send(c.id(), USER, "检查构建");

        assertTrue(blocking.entered.await(3, TimeUnit.SECONDS), "运行应进入时间线写入");
        assertEquals(ConversationStatus.RUNNING,
                manager.findOwned(c.id(), USER).orElseThrow().status(),
                "时间线未写完时不能先对外显示终态");

        blocking.release.countDown();
        assertEquals(RunStatus.SUCCEEDED, manager.waitForRun(runId, USER, 3_000).status());
        assertTrue(manager.transcript(c.id(), USER).orElseThrow().contains("检查构建"));
    }

    @Test
    @DisplayName("不同会话的相同目标各自派发；同会话重复目标明确拒绝；可取消")
    void duplicateGoalAndCancel() {
        ConversationManager manager = manager(new NeverChat(), 2);
        Conversation a = manager.createConversation(PROJECT, USER, "a", "test", null);
        Conversation b = manager.createConversation(PROJECT, USER, "b", "test", null);
        String first = manager.send(a.id(), USER, "  修复 构建  ");
        String second = manager.send(b.id(), USER, "修复   构建");
        assertFalse(first.equals(second), "不同会话的相同目标必须各自派发，不能复用别人的运行");
        assertFalse(second.startsWith("err:"));

        // 同一会话重复派发相同目标：幂等拒绝并给出明确原因，不能静默返回旧 runId
        String duplicate = manager.send(a.id(), USER, "修复 构建");
        assertTrue(duplicate.startsWith("err:"), "同会话重复目标应返回明确错误文案");
        // 同一会话不能再并发派发其他目标
        assertThrows(PlatformException.class,
                () -> manager.send(a.id(), USER, "另一个目标"));
        // 调度会话不能给自己派发（深度固定为 1）
        Conversation supervisor = manager.ensureSupervisor(PROJECT, USER);
        assertTrue(manager.send(supervisor.id(), USER, "x").startsWith("err:"));

        assertTrue(manager.cancelRun(first, USER));
        assertEquals(RunStatus.CANCELLED, manager.runStatus(first, USER).status());
        assertEquals(ConversationStatus.CANCELLED,
                manager.findOwned(a.id(), USER).orElseThrow().status());
        // 越权看不到运行
        assertThrows(PlatformException.class, () -> manager.runStatus(first, "bob"));
    }

    @Test
    @DisplayName("会话时间线按 owner 隔离存取，超限拒绝")
    void transcriptRoundTrip() {
        ConversationManager manager = manager(new SilentChat(), 1);
        Conversation c = manager.createConversation(PROJECT, USER, "t", "test", null);
        manager.saveTranscript(c.id(), USER, "{\"messages\":[]}");
        assertEquals("{\"messages\":[]}", manager.transcript(c.id(), USER).orElseThrow());
        // 越权读写都被拒绝
        assertThrows(PlatformException.class, () -> manager.transcript(c.id(), "bob"));
        assertThrows(PlatformException.class,
                () -> manager.saveTranscript(c.id(), "bob", "{}"));
        assertThrows(PlatformException.class,
                () -> manager.saveTranscript(c.id(), USER, "x".repeat(8_000_001)));
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
        assertThrows(PlatformException.class,
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
            super(null, new ChatSessionRegistry(), null, null);
        }

        @Override
        public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text,
                                       String role, List<String> skills, List<String> mcpServers,
                                       String modelConnectionId, String modelName) {
            return Flux.empty();
        }
    }

    /** 立即产出一段正文的对话流（用于验证时间线落库）。 */
    private static final class EchoChat extends ChatService {
        EchoChat() {
            super(null, new ChatSessionRegistry(), null, null);
        }

        @Override
        public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text,
                                       String role, List<String> skills, List<String> mcpServers,
                                       String modelConnectionId, String modelName) {
            return Flux.just(new TextBlockDeltaEvent("reply-1", "block-1", "已完成构建检查"));
        }
    }

    /** 永不结束的对话流（用于取消）。 */
    private static final class NeverChat extends ChatService {
        NeverChat() {
            super(null, new ChatSessionRegistry(), null, null);
        }

        @Override
        public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text,
                                       String role, List<String> skills, List<String> mcpServers,
                                       String modelConnectionId, String modelName) {
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

    /** 时间线写入可控阻塞，用于验证“先落时间线、再暴露终态”的顺序。 */
    private static final class BlockingStore extends InMemoryStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void saveTranscript(String conversationId, String payload) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.saveTranscript(conversationId, payload);
        }
    }

    /** 三份仓库接口的内存实现（测试专用）。 */
    private static class InMemoryStore implements ConversationRepository,
            ConversationRunRepository, WorkspaceTaskRepository, ConversationTranscriptRepository,
            ConversationTimelineRepository {
        private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<String, ConversationRun> runs = new ConcurrentHashMap<>();
        private final Map<String, WorkspaceTask> tasks = new ConcurrentHashMap<>();
        private final Map<String, String> transcripts = new ConcurrentHashMap<>();
        private final Map<String, List<TimelineEntry>> timeline = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        private final AtomicLong timelineSeq = new AtomicLong();

        @Override
        public long append(String conversationId, String kind, String role, String text,
                           String tool, String toolCallId) {
            long seq = timelineSeq.incrementAndGet();
            timeline.computeIfAbsent(conversationId, key -> new CopyOnWriteArrayList<>())
                    .add(new TimelineEntry(seq, conversationId, kind, role, text, tool,
                            toolCallId, System.currentTimeMillis()));
            return seq;
        }

        @Override
        public List<TimelineEntry> entries(String conversationId, long afterSeq, int limit) {
            return timeline.getOrDefault(conversationId, List.of()).stream()
                    .filter(e -> e.seq() > afterSeq)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void deleteByConversation(String conversationId) {
            timeline.remove(conversationId);
        }

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
            return findByOwner(id, ownerUserId).map(c -> {
                transcripts.remove(id);
                return conversations.remove(id) != null;
            }).orElse(false);
        }

        @Override
        public Optional<String> findTranscript(String conversationId) {
            return Optional.ofNullable(transcripts.get(conversationId));
        }

        @Override
        public void saveTranscript(String conversationId, String payload) {
            transcripts.put(conversationId, payload);
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
        public List<ConversationRun> findActiveRuns(int limit) {
            return runs.values().stream()
                    .filter(r -> !r.status().terminal())
                    .sorted(Comparator.comparingLong(ConversationRun::createdAt))
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
