package top.rongx.agent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.run.RunLedgerRepository;

/** 运行看门狗：超时运行收敛到 FAILED，未超时的不受影响。 */
class WorkspaceRunWatchdogTest {

    private static final long RUN_TIMEOUT = 10_000;
    private static final long QUEUE_TIMEOUT = 5_000;

    private final FakeStore store = new FakeStore();
    private final RecordingAborter aborter = new RecordingAborter();

    private WorkspaceRunWatchdog watchdog(boolean handleAlive) {
        aborter.result = handleAlive;
        return new WorkspaceRunWatchdog(store, store, aborter, RunLedgerRepository.noop(),
                RUN_TIMEOUT, QUEUE_TIMEOUT);
    }

    @Test
    @DisplayName("运行中超过上限：看门狗强制中止并记录原因")
    void abortsStuckRunningRun() {
        Conversation conversation = store.save(Conversation.normal("c-1", "RY", "alice", "t", "test", null));
        long now = 100_000;
        store.saveRun(run("run-1", conversation, RunStatus.RUNNING, now - 60_000, now - 50_000));

        assertEquals(1, watchdog(true).scanOnce(now));

        assertEquals(List.of("run-1"), aborter.failed);
        assertTrue(aborter.reason.contains("运行超时"), aborter.reason);
    }

    @Test
    @DisplayName("排队超过上限：同样中止，避免永久占用会话的派发闸门")
    void abortsStuckQueuedRun() {
        Conversation conversation = store.save(Conversation.normal("c-1", "RY", "alice", "t", "test", null));
        long now = 100_000;
        store.saveRun(run("run-1", conversation, RunStatus.QUEUED, now - 30_000, 0));

        assertEquals(1, watchdog(true).scanOnce(now));
        assertTrue(aborter.reason.contains("排队超时"), aborter.reason);
    }

    @Test
    @DisplayName("未超时的运行不会被误杀")
    void leavesFreshRunsAlone() {
        Conversation conversation = store.save(Conversation.normal("c-1", "RY", "alice", "t", "test", null));
        long now = 100_000;
        store.saveRun(run("run-running", conversation, RunStatus.RUNNING, now - 1_000, now - 1_000));
        store.saveRun(run("run-queued", conversation, RunStatus.QUEUED, now - 1_000, 0));

        assertEquals(0, watchdog(true).scanOnce(now));
        assertTrue(aborter.failed.isEmpty());
    }

    @Test
    @DisplayName("进程重启后句柄丢失：直接把库里的状态收敛为 FAILED")
    void convergesWhenHandleIsGone() {
        Conversation conversation = store.save(Conversation.normal("c-1", "RY", "alice", "t", "test", null));
        long now = 100_000;
        store.saveRun(run("run-1", conversation, RunStatus.RUNNING, now - 60_000, now - 50_000));

        assertEquals(1, watchdog(false).scanOnce(now));

        ConversationRun run = store.findRun("run-1").orElseThrow();
        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.error().contains("运行超时"), run.error());
        assertEquals(ConversationStatus.FAILED, store.find("c-1").orElseThrow().status());
    }

    private static ConversationRun run(String id, Conversation conversation, RunStatus status,
                                      long createdAt, long startedAt) {
        return new ConversationRun(id, conversation.projectId(), conversation.id(),
                conversation.ownerUserId(), "目标", "key-" + id, status, "", "",
                createdAt, startedAt, 0);
    }

    private static final class RecordingAborter implements RunAborter {
        private boolean result = true;
        private final List<String> failed = new ArrayList<>();
        private String reason = "";

        @Override
        public boolean fail(String runId, String reason) {
            this.reason = reason;
            if (result) {
                failed.add(runId);
            }
            return result;
        }
    }

    private static final class FakeStore implements ConversationRepository, ConversationRunRepository {
        private final Map<String, Conversation> conversations = new LinkedHashMap<>();
        private final Map<String, ConversationRun> runs = new LinkedHashMap<>();

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
                    .filter(c -> c.projectId().equals(projectId)
                            && c.ownerUserId().equals(ownerUserId))
                    .toList();
        }

        @Override
        public Optional<Conversation> findSupervisor(String projectId, String ownerUserId) {
            return Optional.empty();
        }

        @Override
        public Optional<Conversation> update(String id, String ownerUserId,
                                             ConversationPatch patch) {
            return findByOwner(id, ownerUserId).map(current -> {
                Conversation merged = new Conversation(current.id(), current.projectId(),
                        current.ownerUserId(), current.title(), current.kind(), current.role(),
                        current.env(), current.worktree(),
                        patch.status() == null ? current.status() : patch.status(),
                        current.summary(), current.pinned(), current.archived(),
                        current.parentConversationId(), System.currentTimeMillis(),
                        current.createdAt());
                conversations.put(id, merged);
                return merged;
            });
        }

        @Override
        public boolean delete(String id, String ownerUserId) {
            return conversations.remove(id) != null;
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
            return Optional.empty();
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
                    .limit(limit).toList();
        }

        @Override
        public List<ConversationRun> findActiveRuns(int limit) {
            return runs.values().stream()
                    .filter(r -> !r.status().terminal())
                    .sorted(Comparator.comparingLong(ConversationRun::createdAt))
                    .limit(limit).toList();
        }
    }
}
