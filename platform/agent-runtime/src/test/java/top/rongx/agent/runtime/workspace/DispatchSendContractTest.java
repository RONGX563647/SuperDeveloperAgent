package top.rongx.agent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.DispatchNode;
import top.rongx.agent.domain.conversation.DispatchNodeResult;
import top.rongx.agent.domain.conversation.DispatchPlan;
import top.rongx.agent.domain.conversation.DispatchResult;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 派发链路的返回值契约。
 *
 * <p>这里锁住一个真实缺陷：{@code send} 在「目标是调度会话」和「同目标重复派发」两条
 * 分支上返回过 {@code "err: ..."} 字符串，而调用方把它当 runId 继续用——
 * 于是子会话表现为「派发成功但永远不动」，父会话也看不到失败。
 *
 * <p>契约只有两条：
 * <ol>
 *   <li>{@code send} 要么返回合法 runId，要么抛异常，绝不返回错误文案；</li>
 *   <li>调度器只校验 runId 形状；形状不对就报「契约缺陷」而不是猜测错误内容。</li>
 * </ol>
 */
class DispatchSendContractTest {

    @Test
    @DisplayName("调度器拒绝非 runId 返回值，并明确指出是契约缺陷")
    void schedulerRejectsNonRunId() {
        FakeBackend backend = new FakeBackend("err: 该会话已有相同目标的运行");
        WorkspaceDispatchScheduler scheduler = new WorkspaceDispatchScheduler();

        DispatchResult result = scheduler.execute(plan("n1"), backend);

        DispatchNodeResult node = result.find("n1").orElseThrow();
        assertEquals("FAILED", node.status());
        assertTrue(node.error().contains("不是合法 runId"), node.error());
        assertTrue(node.error().contains("契约缺陷"), node.error());
    }

    @Test
    @DisplayName("空返回值同样判失败，且不会被当成 runId 去等待")
    void blankRunIdFails() {
        FakeBackend backend = new FakeBackend("  ");
        WorkspaceDispatchScheduler scheduler = new WorkspaceDispatchScheduler();

        DispatchNodeResult node = scheduler.execute(plan("n1"), backend)
                .find("n1").orElseThrow();

        assertEquals("FAILED", node.status());
        assertTrue(node.error().contains("没有返回 runId"), node.error());
        assertFalse(backend.waited, "形状不合法时不该进入等待");
    }

    @Test
    @DisplayName("合法 runId 正常进入等待并拿到结果")
    void validRunIdProceeds() {
        FakeBackend backend = new FakeBackend("run-abcd1234");
        WorkspaceDispatchScheduler scheduler = new WorkspaceDispatchScheduler();

        DispatchNodeResult node = scheduler.execute(plan("n1"), backend)
                .find("n1").orElseThrow();

        assertEquals("SUCCEEDED", node.status());
        assertTrue(backend.waited, "合法 runId 应进入等待");
    }

    @Test
    @DisplayName("send 抛异常时把业务原因透传成节点失败")
    void sendExceptionBecomesNodeFailure() {
        FakeBackend backend = new FakeBackend(new RunException(
                top.rongx.agent.domain.error.ErrorCode.RUN_INVALID_STATE,
                "会话正在运行，请先等待或取消：runId=run-old"));
        WorkspaceDispatchScheduler scheduler = new WorkspaceDispatchScheduler();

        DispatchNodeResult node = scheduler.execute(plan("n1"), backend)
                .find("n1").orElseThrow();

        assertEquals("FAILED", node.status());
        assertTrue(node.error().contains("会话正在运行"), node.error());
    }

    private static DispatchPlan plan(String nodeId) {
        return new DispatchPlan(List.of(new DispatchNode(nodeId, null, "节点 " + nodeId, null,
                null, "做一件事", List.of(), true, 5, 0)), true, 2, 30, 3);
    }

    /** 只实现调度器用到的三个动作；send 的返回值由用例指定。 */
    private static final class FakeBackend implements WorkspaceDispatchScheduler.Backend {
        private final String runId;
        private final RuntimeException failure;
        private boolean waited;

        private FakeBackend(String runId) {
            this.runId = runId;
            this.failure = null;
        }

        private FakeBackend(RuntimeException failure) {
            this.runId = null;
            this.failure = failure;
        }

        @Override
        public String createConversation(String title, String env, String worktree) {
            return "conv-1";
        }

        @Override
        public String send(String conversationId, String text) {
            if (failure != null) {
                throw failure;
            }
            return runId;
        }

        @Override
        public ConversationRun waitForRun(String runId, long timeoutMillis) {
            waited = true;
            long now = System.currentTimeMillis();
            return new ConversationRun(runId, "p1", "conv-1", "u1", "做一件事", "key",
                    RunStatus.SUCCEEDED, "已完成", "", now, now, now);
        }

        @Override
        public boolean cancel(String runId) {
            return true;
        }
    }
}
