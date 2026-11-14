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
import top.rongx.agent.domain.conversation.WorkspaceTask;

/** 会话元数据持久化：owner 隔离、状态更新、运行与任务，重启后仍可读。 */
class SqliteConversationStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("会话 CRUD 与 owner 隔离")
    void conversationCrud() {
        SqliteConversationStore store = new SqliteConversationStore(root.toString());
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
        SqliteConversationStore store = new SqliteConversationStore(root.toString());
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
        SqliteConversationStore store = new SqliteConversationStore(root.toString());
        store.save(Conversation.normal("s-1", "RY", "alice", "t", "test", null));
        long now = System.currentTimeMillis();
        store.saveRun(new ConversationRun("run-1", "RY", "s-1", "alice", "修复构建", "key",
                RunStatus.QUEUED, "", "", now, 0, 0));
        assertEquals("run-1", store.findActiveByConversation("s-1").orElseThrow().id());
        assertEquals("run-1", store.findActiveByGoal("RY", "key").orElseThrow().id());

        store.updateRun(store.findRun("run-1").orElseThrow()
                .withStatus(RunStatus.SUCCEEDED, "完成", "", now, now + 1));
        assertTrue(store.findActiveByConversation("s-1").isEmpty());

        store.saveTask(new WorkspaceTask("task-1", "RY", "s-1", "补测试", "单测通过",
                TaskStatus.TODO, 2, now, now));

        // 模拟进程重启：同一个文件，新实例
        SqliteConversationStore reopened = new SqliteConversationStore(root.toString());
        assertEquals(RunStatus.SUCCEEDED, reopened.findRun("run-1").orElseThrow().status());
        assertEquals("补测试", reopened.findTasksByProject("RY").get(0).goal());
        assertEquals(1, reopened.findRunsByProject("RY", 10).size());
        assertEquals(1, reopened.findRunsByConversation("s-1", 10).size());
    }
}
