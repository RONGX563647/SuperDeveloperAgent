package top.rongx.agent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.conversation.TimelineEntry;
import top.rongx.agent.domain.error.PlatformException;

/** 时间线订阅：游标续传、终态自动结束、owner 隔离。 */
class ConversationTimelineStreamTest {

    private final FakeStore store = new FakeStore();
    private final ConversationTimelineStream stream = new ConversationTimelineStream(store, store, 100);

    @Test
    @DisplayName("从 0 开始补齐全部记录，seq 单调递增")
    void replaysFromBeginning() {
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        store.append("s-1", "user", "user", "帮我修构建", null, null);
        store.append("s-1", "tool_call", "assistant", null, "read_file", "call-1");
        store.append("s-1", "assistant", "assistant", "构建已修复", null, null);

        List<TimelineEntry> entries = stream.stream("s-1", "alice", 0)
                .take(3).collectList().block(Duration.ofSeconds(5));

        assertEquals(3, entries.size());
        assertTrue(entries.get(0).seq() < entries.get(1).seq()
                && entries.get(1).seq() < entries.get(2).seq());
        assertEquals("assistant", entries.get(2).kind());
    }

    @Test
    @DisplayName("断线续传：只推游标之后的记录")
    void resumesAfterCursor() {
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        long first = store.append("s-1", "user", "user", "第一轮", null, null);
        long second = store.append("s-1", "assistant", "assistant", "结论", null, null);

        List<TimelineEntry> entries = stream.stream("s-1", "alice", first)
                .take(1).collectList().block(Duration.ofSeconds(5));

        assertEquals(1, entries.size());
        assertEquals(second, entries.get(0).seq());
        assertEquals("结论", entries.get(0).text());
    }

    @Test
    @DisplayName("会话进入终态且没有新记录时主动结束订阅")
    void completesWhenConversationTerminal() {
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        store.update("s-1", "alice", ConversationPatch.status(ConversationStatus.SUCCEEDED));
        store.append("s-1", "user", "user", "跑完了", null, null);
        store.append("s-1", "assistant", "assistant", "完成", null, null);

        List<TimelineEntry> entries = stream.stream("s-1", "alice", 0)
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(2, entries.size(), "终态会话补完最后一轮记录后必须结束流");
    }

    @Test
    @DisplayName("别人的会话不能订阅")
    void rejectsForeignConversation() {
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        assertThrows(PlatformException.class, () -> stream.stream("s-1", "bob", 0));
    }

    private static final class FakeStore implements ConversationRepository,
            ConversationTimelineRepository {

        private final Map<String, Conversation> conversations = new LinkedHashMap<>();
        private final List<TimelineEntry> timeline = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

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
        public long append(String conversationId, String kind, String role, String text,
                           String tool, String toolCallId) {
            long next = seq.incrementAndGet();
            timeline.add(new TimelineEntry(next, conversationId, kind, role, text, tool,
                    toolCallId, System.currentTimeMillis()));
            return next;
        }

        @Override
        public List<TimelineEntry> entries(String conversationId, long afterSeq, int limit) {
            return timeline.stream()
                    .filter(e -> e.conversationId().equals(conversationId))
                    .filter(e -> e.seq() > afterSeq)
                    .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void deleteByConversation(String conversationId) {
            timeline.removeIf(e -> e.conversationId().equals(conversationId));
        }
    }
}
