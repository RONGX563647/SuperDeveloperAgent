package top.rongx.agent.infra.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationKind;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.TimelineEntry;
import top.rongx.agent.domain.conversation.WorkspaceTask;

/** 会话元数据持久化：owner 隔离、状态更新、运行与任务，重启后仍可读。 */
class DbConversationStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("会话 CRUD 与 owner 隔离")
    void conversationCrud() {
        DbConversationStore store = new DbConversationStore(root.toString());
        Conversation saved = store.save(Conversation.normal("s-1", "RY", "alice",
                "首条消息标题", "test", null));

        assertEquals("首条消息标题", store.find("s-1").orElseThrow().title());
        assertTrue(store.findByOwner("s-1", "alice").isPresent());
        assertFalse(store.findByOwner("s-1", "bob").isPresent());
        // 别人的更新不能生效
        assertTrue(store.update("s-1", "bob", ConversationPatch.title("hacked")).isEmpty());
        assertEquals("首条消息标题", saved.title());

        Conversation updated = store.update("s-1", "alice",
                new ConversationPatch("新标题", "prod", "wt-a", "摘要",
                        ConversationStatus.SUCCEEDED, true, false)).orElseThrow();
        assertEquals("新标题", updated.title());
        assertEquals("prod", updated.env());
        assertEquals("wt-a", updated.worktree());
        assertEquals(ConversationStatus.SUCCEEDED, updated.status());
        assertTrue(updated.pinned());
        assertEquals(List.of("s-1"), store.findByProject("RY", "alice", false).stream()
                .map(Conversation::id).toList());
        assertTrue(store.findByProject("RY", "bob", false).isEmpty());
    }

    @Test
    @DisplayName("每个工作区（按用户）只有一个调度会话")
    void supervisorIsUnique() {
        DbConversationStore store = new DbConversationStore(root.toString());
        Conversation supervisor = new Conversation("sup-1", "RY", "alice", "工作区调度",
                ConversationKind.SUPERVISOR, "supervisor", "test", null,
                ConversationStatus.IDLE, "", true, false, null, 1, 1);
        store.save(supervisor);
        assertEquals("sup-1", store.findSupervisor("RY", "alice").orElseThrow().id());
        assertTrue(store.findSupervisor("RY", "bob").isEmpty());
    }

    @Test
    @DisplayName("运行与任务落盘，重启（新实例）后仍可读")
    void runsAndTasksSurviveRestart() {
        DbConversationStore store = new DbConversationStore(root.toString());
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        long now = System.currentTimeMillis();
        store.saveRun(new ConversationRun("run-1", "RY", "s-1", "alice", "修复构建", "key",
                RunStatus.QUEUED, "", "", now, 0, 0));
        assertEquals("run-1", store.findActiveByConversation("s-1").orElseThrow().id());
        assertEquals("run-1", store.findActiveByGoal("RY", "key").orElseThrow().id());
        assertEquals(1, store.findActiveRuns(10).size(), "未终态的运行应能被看门狗扫到");

        store.updateRun(store.findRun("run-1").orElseThrow()
                .withStatus(RunStatus.SUCCEEDED, "完成", "", now, now + 1));
        assertTrue(store.findActiveByConversation("s-1").isEmpty());
        assertTrue(store.findActiveRuns(10).isEmpty(), "终态运行不再出现在看门狗扫描里");

        store.saveTask(new WorkspaceTask("task-1", "RY", "s-1", "补测试", "单测通过",
                TaskStatus.TODO, 2, now, now));

        // 模拟进程重启：同一个文件，新实例
        DbConversationStore reopened = new DbConversationStore(root.toString());
        assertEquals(RunStatus.SUCCEEDED, reopened.findRun("run-1").orElseThrow().status());
        assertEquals("补测试", reopened.findTasksByProject("RY").get(0).goal());
        assertEquals(1, reopened.findRunsByProject("RY", 10).size());
        assertEquals(1, reopened.findRunsByConversation("s-1", 10).size());
    }

    @Test
    @DisplayName("会话时间线落盘，重启（新实例）后仍可读")
    void transcriptSurvivesRestart() {
        DbConversationStore store = new DbConversationStore(root.toString());
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        String payload = "{\"messages\":[{\"id\":\"m-1\",\"role\":\"user\",\"text\":\"你好\"}]}";
        store.saveTranscript("s-1", payload);
        assertEquals(payload, store.findTranscript("s-1").orElseThrow());

        // 覆盖写入而不是追加新行
        store.saveTranscript("s-1", "{\"messages\":[]}");
        DbConversationStore reopened = new DbConversationStore(root.toString());
        assertEquals("{\"messages\":[]}", reopened.findTranscript("s-1").orElseThrow());
        assertTrue(reopened.findTranscript("missing").isEmpty());
    }

    @Test
    @DisplayName("删除会话时历史运行与任务一并物理删除，不留孤儿行")
    void deleteCascadesRunsAndTasks() {
        DbConversationStore store = new DbConversationStore(root.toString());
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        long now = System.currentTimeMillis();
        store.saveRun(new ConversationRun("run-1", "RY", "s-1", "alice", "跑一下", "key",
                RunStatus.SUCCEEDED, "ok", "", now, now, now));
        store.saveTask(new WorkspaceTask("task-1", "RY", "s-1", "补测试", "单测通过",
                TaskStatus.DONE, 2, now, now));
        store.saveTranscript("s-1", "{\"messages\":[]}");

        assertTrue(store.delete("s-1", "alice"));

        assertTrue(store.find("s-1").isEmpty());
        assertTrue(store.findRun("run-1").isEmpty());
        assertTrue(store.findRunsByConversation("s-1", 10).isEmpty());
        assertTrue(store.findTasksByConversation("s-1").isEmpty());
        assertTrue(store.findTranscript("s-1").isEmpty());
        // 别人的会话删不掉，也不该误删数据
        store.save(Conversation.normal("s-2", "RY", "alice", "t2", "test", null));
        assertFalse(store.delete("s-2", "bob"));
        assertTrue(store.find("s-2").isPresent());
    }

    @Test
    @DisplayName("服务端时间线只追加、按 seq 游标续读，重启后仍在")
    void timelineAppendAndCursor() {
        DbConversationStore store = new DbConversationStore(root.toString());
        long first = store.append("s-1", "user", "user", "帮我修构建", null, null);
        long second = store.append("s-1", "tool_call", "assistant", null, "read_file", "call-1");
        long third = store.append("s-1", "tool_result", "tool", "file content", "read_file", "call-1");
        store.append("s-2", "user", "user", "另一个会话", null, null);

        assertTrue(first < second && second < third, "seq 必须单调递增");
        List<TimelineEntry> all = store.entries("s-1", 0, 100);
        assertEquals(List.of("user", "tool_call", "tool_result"),
                all.stream().map(TimelineEntry::kind).toList());
        assertEquals("read_file", all.get(2).tool());
        assertEquals("call-1", all.get(2).toolCallId());

        // 断线续传：只返回游标之后的记录
        assertEquals(List.of(third), store.entries("s-1", second, 100).stream()
                .map(TimelineEntry::seq).toList());

        // 新实例模拟重启：历史仍可读，且会话之间互不串
        DbConversationStore reopened = new DbConversationStore(root.toString());
        assertEquals(3, reopened.entries("s-1", 0, 100).size());
        assertEquals(1, reopened.entries("s-2", 0, 100).size());

        reopened.deleteByConversation("s-1");
        assertTrue(reopened.entries("s-1", 0, 100).isEmpty(), "删除会话应清空时间线");
        assertEquals(1, reopened.entries("s-2", 0, 100).size(), "不能误删其他会话");
    }
}
