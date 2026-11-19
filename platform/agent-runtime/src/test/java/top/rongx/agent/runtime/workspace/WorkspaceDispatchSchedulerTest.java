package top.rongx.agent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.DispatchNode;
import top.rongx.agent.domain.conversation.DispatchNodeResult;
import top.rongx.agent.domain.conversation.DispatchPlan;
import top.rongx.agent.domain.conversation.DispatchResult;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.error.PlatformException;

/** DAG 调度：并行等待、依赖顺序、失败熔断与提前终止。 */
class WorkspaceDispatchSchedulerTest {

    private final WorkspaceDispatchScheduler scheduler = new WorkspaceDispatchScheduler();

    @Test
    @DisplayName("无依赖节点并行执行：总耗时接近最慢节点，而不是累加")
    void independentNodesRunInParallel() {
        FakeBackend backend = new FakeBackend();
        backend.delayMillis = 250;
        DispatchPlan plan = new DispatchPlan(List.of(
                node("a", 60, true),
                node("b", 60, true),
                node("c", 60, true)), true, 4);

        long start = System.currentTimeMillis();
        DispatchResult result = scheduler.execute(plan, backend);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, result.succeeded());
        // 串行至少 750ms；并行应明显更低（留足 CI 抖动余量）
        assertTrue(elapsed < 650, "并行调度耗时过高：" + elapsed + "ms");
        assertEquals(3, backend.sent.size());
    }

    @Test
    @DisplayName("依赖节点在前置完成前不会派发")
    void dependentNodeWaitsForDependency() {
        FakeBackend backend = new FakeBackend();
        backend.delayMillis = 80;
        DispatchPlan plan = new DispatchPlan(List.of(
                node("build", 60, true),
                new DispatchNode("test", null, null, null, null, "目标-test",
                        List.of("build"), true, 60)), true, 4);

        DispatchResult result = scheduler.execute(plan, backend);

        assertEquals(2, result.succeeded());
        assertEquals(List.of("build", "test"), backend.sentOrder);
    }

    @Test
    @DisplayName("必需节点失败 → 依赖链 SKIPPED，并提前终止")
    void failureSkipsDependents() {
        FakeBackend backend = new FakeBackend();
        backend.failNodes.add("build");
        DispatchPlan plan = new DispatchPlan(List.of(
                node("build", 60, true),
                new DispatchNode("test", null, null, null, null, "目标-test",
                        List.of("build"), true, 60),
                node("docs", 60, false)), true, 1);

        DispatchResult result = scheduler.execute(plan, backend);

        assertTrue(result.earlyStopped());
        assertEquals("FAILED", result.find("build").orElseThrow().status());
        assertTrue(result.find("test").orElseThrow().skipped());
        assertTrue(result.find("docs").orElseThrow().skipped());
        assertEquals(0, result.succeeded());
    }

    @Test
    @DisplayName("必需节点全部成功后，提前终止在跑的可选节点")
    void earlyStopsOptionalNodesAfterRequiredDone() {
        FakeBackend backend = new FakeBackend();
        backend.delayMillis = 5_000;
        backend.fastNodes.add("required");
        DispatchPlan plan = new DispatchPlan(List.of(
                node("required", 60, true),
                node("optional", 60, false)), true, 4);

        long start = System.currentTimeMillis();
        DispatchResult result = scheduler.execute(plan, backend);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.earlyStopped());
        assertTrue(result.find("required").orElseThrow().succeeded());
        assertTrue(result.find("optional").orElseThrow().skipped());
        assertTrue(elapsed < 1_500, "可选节点没有被提前终止：" + elapsed + "ms");
        assertTrue(backend.cancelled.containsValue(true), "在跑的可选节点应被取消");
    }

    @Test
    @DisplayName("全是可选节点时不会误触发早停，所有节点都会执行")
    void optionalOnlyPlanRunsAll() {
        FakeBackend backend = new FakeBackend();
        DispatchPlan plan = new DispatchPlan(List.of(
                node("a", 60, false),
                node("b", 60, false)), true, 4);

        DispatchResult result = scheduler.execute(plan, backend);

        assertEquals(2, result.succeeded());
        assertEquals(2, backend.sent.size());
    }

    @Test
    @DisplayName("循环依赖在派发前直接拒绝")
    void rejectsCycles() {
        DispatchPlan plan = new DispatchPlan(List.of(
                new DispatchNode("a", null, null, null, null, "a", List.of("b"), true, 60),
                new DispatchNode("b", null, null, null, null, "b", List.of("a"), true, 60)),
                true, 2);

        PlatformException error = assertThrows(PlatformException.class,
                () -> scheduler.execute(plan, new FakeBackend()));
        assertTrue(error.getMessage().contains("循环依赖"));
    }

    @Test
    @DisplayName("派发即登记票据，终态回写结果：任务板能看到规格、会话与结论")
    void ticketsOpenOnDispatchAndCloseWithResult() {
        FakeBackend backend = new FakeBackend();
        DispatchPlan plan = new DispatchPlan(List.of(
                node("build", 60, true),
                node("docs", 60, false)), true, 2);

        DispatchResult result = scheduler.execute(plan, backend);

        assertEquals(2, backend.openedTickets.size(), "每个成功派发的节点都应开票据");
        // 票据必须绑定到真正执行它的会话，而不是随便哪个会话
        assertEquals(result.find("build").orElseThrow().conversationId(),
                backend.ticketConversations.get(0));
        assertEquals(result.find("docs").orElseThrow().conversationId(),
                backend.ticketConversations.get(1));
        assertTrue(backend.closedTickets.containsKey("ticket-1"), "终态必须回写票据");
        assertEquals("SUCCEEDED", backend.closedTickets.get("ticket-1").status());
        assertEquals("ticket-1", result.find("build").orElseThrow().ticketId(),
                "结果要带回票据 id，便于从任务板追溯到运行");
    }

    @Test
    @DisplayName("派发失败（err 文本）不会拖垮其它节点")
    void dispatchErrorIsIsolated() {
        FakeBackend backend = new FakeBackend();
        backend.rejectMessage = "err: 会话正在运行";
        DispatchPlan plan = new DispatchPlan(List.of(
                node("bad", 60, true),
                node("good", 60, false)), true, 1);

        DispatchResult result = scheduler.execute(plan, backend);

        DispatchNodeResult bad = result.find("bad").orElseThrow();
        assertEquals("FAILED", bad.status());
        assertTrue(bad.error().contains("会话正在运行"));
        // 必需节点失败 → 可选节点未开始即跳过
        assertTrue(result.find("good").orElseThrow().skipped());
    }

    @Test
    @DisplayName("DAG 总时长超限：取消在跑节点并返回部分结果")
    void globalTimeoutCancelsInFlightNodes() {
        FakeBackend backend = new FakeBackend();
        backend.delayMillis = 10_000;
        DispatchPlan plan = new DispatchPlan(List.of(
                node("a", 60, true),
                node("b", 60, true)), true, 4, 1);

        long start = System.currentTimeMillis();
        DispatchResult result = scheduler.execute(plan, backend);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.earlyStopped());
        assertTrue(result.reason().contains("总时长"), "原因应说明全局超时：" + result.reason());
        assertEquals(2, result.nodes().stream()
                .filter(node -> "TIMEOUT".equals(node.status())).count());
        assertTrue(elapsed < 5_000, "全局超时未生效：" + elapsed + "ms");
        assertEquals(2, backend.cancelled.size(), "在跑节点都应被取消");
    }

    @Test
    @DisplayName("节点失败可按 retries 自动重试，复用同一会话重发同一指令")
    void failedNodeRetries() {
        FakeBackend backend = new FakeBackend();
        backend.transientFailures.put("flaky", new AtomicInteger(1));
        DispatchPlan plan = new DispatchPlan(List.of(
                new DispatchNode("flaky", null, null, null, null, "目标-flaky", List.of(),
                        true, 60, 1)), true, 1);

        DispatchResult result = scheduler.execute(plan, backend);

        assertTrue(result.find("flaky").orElseThrow().succeeded());
        assertEquals(List.of("flaky", "flaky"), backend.sentOrder);
        // 重试复用同一会话，不会重复新建
        assertEquals(1, backend.conversationsCreated.get());
    }

    @Test
    @DisplayName("连续失败达到阈值触发熔断，剩余节点不再派发")
    void consecutiveFailuresTripBreaker() {
        FakeBackend backend = new FakeBackend();
        backend.failNodes.addAll(Set.of("a", "b"));
        DispatchPlan plan = new DispatchPlan(List.of(
                node("a", 60, false),
                node("b", 60, false),
                node("c", 60, false),
                node("d", 60, false)), false, 1, 600, 2);

        DispatchResult result = scheduler.execute(plan, backend);

        assertTrue(result.earlyStopped());
        assertTrue(result.reason().contains("熔断"), "原因应说明熔断：" + result.reason());
        assertEquals(2, backend.sentOrder.size(), "熔断后不应继续派发");
        assertTrue(result.find("c").orElseThrow().skipped());
        assertTrue(result.find("d").orElseThrow().skipped());
    }

    @Test
    @DisplayName("计划参数越界时收敛到安全默认值")
    void planValuesAreClamped() {
        DispatchPlan plan = new DispatchPlan(List.of(node("a", 60, true)), true, 0, 99_999);

        assertEquals(DispatchPlan.DEFAULT_MAX_PARALLEL, plan.maxParallel());
        assertEquals(DispatchPlan.MAX_MAX_DURATION_SECONDS, plan.maxDurationSeconds());
        assertEquals(DispatchPlan.DEFAULT_MAX_DURATION_SECONDS,
                new DispatchPlan(List.of(node("a", 60, true)), true, 4).maxDurationSeconds());
        assertEquals(0, new DispatchPlan(List.of(node("a", 60, true)), true, 4, 600, 0)
                .maxConsecutiveFailures(), "0 表示关闭熔断");
        assertEquals(DispatchPlan.MAX_MAX_CONSECUTIVE_FAILURES,
                new DispatchPlan(List.of(node("a", 60, true)), true, 4, 600, 99)
                        .maxConsecutiveFailures());
    }

    private static DispatchNode node(String id, int timeoutSeconds, boolean required) {
        return new DispatchNode(id, null, null, null, null, "目标-" + id, List.of(), required,
                timeoutSeconds);
    }

    /** 内存后端：按节点延迟模拟运行，记录派发顺序与取消。 */
    private static final class FakeBackend implements WorkspaceDispatchScheduler.Backend {
        final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final List<String> sentOrder = Collections.synchronizedList(new ArrayList<>());
        final Set<String> failNodes = ConcurrentHashMap.newKeySet();
        final Set<String> fastNodes = ConcurrentHashMap.newKeySet();
        /** 前 N 次运行失败的节点（用于验证自动重试）。 */
        final Map<String, AtomicInteger> transientFailures = new ConcurrentHashMap<>();
        final AtomicInteger sequence = new AtomicInteger();
        final AtomicInteger conversationsCreated = new AtomicInteger();
        final List<String> openedTickets = Collections.synchronizedList(new ArrayList<>());
        final List<String> ticketConversations = Collections.synchronizedList(new ArrayList<>());
        final Map<String, DispatchNodeResult> closedTickets = new ConcurrentHashMap<>();
        volatile long delayMillis = 20;
        volatile String rejectMessage;

        @Override
        public String openTicket(DispatchNode node, String conversationId) {
            String ticket = "ticket-" + (openedTickets.size() + 1);
            openedTickets.add(ticket);
            ticketConversations.add(conversationId);
            return ticket;
        }

        @Override
        public void closeTicket(String ticketId, DispatchNodeResult result) {
            if (ticketId != null) {
                closedTickets.put(ticketId, result);
            }
        }

        @Override
        public String createConversation(String title, String env, String worktree) {
            conversationsCreated.incrementAndGet();
            return "c-" + sequence.incrementAndGet();
        }

        @Override
        public String send(String conversationId, String text) {
            if (rejectMessage != null) {
                return rejectMessage;
            }
            String nodeId = text.startsWith("目标-") ? text.substring(3) : text;
            String runId = "run-" + nodeId + "-" + sequence.incrementAndGet();
            sent.add(runId);
            sentOrder.add(nodeId);
            return runId;
        }

        @Override
        public ConversationRun waitForRun(String runId, long timeoutMillis) {
            String nodeId = nodeOf(runId);
            long begin = System.currentTimeMillis();
            long waitFor = fastNodes.contains(nodeId) ? 30 : delayMillis;
            long sliceDeadline = begin + Math.min(waitFor, timeoutMillis);
            while (System.currentTimeMillis() < sliceDeadline
                    && !cancelled.containsKey(runId)) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            RunStatus status;
            if (cancelled.containsKey(runId)) {
                status = RunStatus.CANCELLED;
            } else if (failNodes.contains(nodeId)) {
                status = RunStatus.FAILED;
            } else if (transientFailures.containsKey(nodeId)
                    && transientFailures.get(nodeId).getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
                status = RunStatus.FAILED;
            } else if (System.currentTimeMillis() - begin >= waitFor) {
                status = RunStatus.SUCCEEDED;
            } else {
                status = RunStatus.RUNNING;
            }
            return new ConversationRun(runId, "p1", "c1", "u1", "goal", "key", status,
                    status == RunStatus.SUCCEEDED ? "done " + nodeId : "",
                    status == RunStatus.FAILED ? "boom" : "", begin, begin,
                    status.terminal() ? System.currentTimeMillis() : 0);
        }

        @Override
        public boolean cancel(String runId) {
            cancelled.put(runId, true);
            return true;
        }

        private static String nodeOf(String runId) {
            // run-<node>-<seq>
            int lastDash = runId.lastIndexOf('-');
            return runId.substring("run-".length(), lastDash);
        }
    }
}
