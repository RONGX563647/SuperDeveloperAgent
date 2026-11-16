package top.rongx.agent.runtime.workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.DispatchNode;
import top.rongx.agent.domain.conversation.DispatchNodeResult;
import top.rongx.agent.domain.conversation.DispatchPlan;
import top.rongx.agent.domain.conversation.DispatchResult;

/**
 * DAG 并行调度器：把"串行 send + wait"换成"就绪即派发、并行等待、提前终止"。
 *
 * <p>关键点：
 * <ul>
 *   <li><b>并行</b>：每个节点的等待跑在自己的虚拟线程上，主循环只等"任意一个完成"，
 *       因此 N 个互不依赖的节点总耗时接近最慢的那个，而不是逐个 60 秒累加；</li>
 *   <li><b>依赖</b>：节点只在全部前置节点成功后派发；前置失败则整条链路 SKIPPED，
 *       不再占用工作区运行配额；</li>
 *   <li><b>提前终止</b>：必需节点失败（abortOnFailure）或必需节点已全部成功时，
 *       不再派发未开始的可选节点，并取消在跑的可选节点；</li>
 *   <li><b>有界</b>：单节点超时会 cancel 对应 run；同时在跑节点数受 maxParallel 限制；
 *       整张 DAG 还有总时长上限，超时取消在跑节点并如实返回部分结果。</li>
 *   <li><b>韧性</b>：节点执行失败（FAILED）可自动重试，复用同一会话重发同一条指令；
 *       连续失败达到阈值时熔断，不再继续派发剩余节点。</li>
 * </ul>
 *
 * <p>不依赖 Spring：通过 {@link Backend} 注入会话创建 / 派发 / 等待 / 取消，便于单测。
 */
public final class WorkspaceDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDispatchScheduler.class);

    /** 单次 wait 的切片：避免一次阻塞太久，也让取消/超时更及时。 */
    private static final long WAIT_SLICE_MILLIS = 15_000L;
    private static final int MAX_SUMMARY_CHARS = 800;
    private static final int MAX_ERROR_CHARS = 400;

    /** 调度器需要的最小会话能力（由 ConversationManager 绑定 owner 后传入）。 */
    public interface Backend {
        String createConversation(String title, String env, String worktree);

        String send(String conversationId, String text);

        ConversationRun waitForRun(String runId, long timeoutMillis);

        boolean cancel(String runId);
    }

    /** 在跑节点：runId 可变，重试时切换到新的运行，让取消操作始终命中当前运行。 */
    private static final class Active {
        private final String conversationId;
        private final CompletableFuture<DispatchNodeResult> future;
        private volatile String runId;

        Active(String conversationId, String runId,
               CompletableFuture<DispatchNodeResult> future) {
            this.conversationId = conversationId;
            this.runId = runId == null ? "" : runId;
            this.future = future;
        }

        String conversationId() {
            return conversationId;
        }

        String runId() {
            return runId;
        }

        void runId(String value) {
            this.runId = value == null ? "" : value;
        }

        CompletableFuture<DispatchNodeResult> future() {
            return future;
        }
    }

    public DispatchResult execute(DispatchPlan plan, Backend backend) {
        long startedAt = System.currentTimeMillis();
        long globalDeadline = startedAt + plan.maxDurationSeconds() * 1000L;
        Map<String, DispatchNode> nodes = new LinkedHashMap<>();
        plan.nodes().forEach(node -> nodes.put(node.id(), node));
        detectCycles(nodes);

        Map<String, DispatchNodeResult> results = new LinkedHashMap<>();
        Map<String, Active> active = new LinkedHashMap<>();
        boolean earlyStopped = false;
        String reason = "";
        int consecutiveFailures = 0;

        ExecutorService waiters = Executors.newVirtualThreadPerTaskExecutor();
        try {
            while (true) {
                // 0. 全局时长上限：到点后取消在跑节点，避免整张 DAG 无限拖长
                if (!earlyStopped && System.currentTimeMillis() >= globalDeadline
                        && results.size() < nodes.size()) {
                    earlyStopped = true;
                    reason = "DAG 总时长超过 " + plan.maxDurationSeconds() + "s，已取消在跑节点";
                    abortAll(active, backend, results, reason, startedAt);
                }

                // 1. 就绪即派发（受并发上限约束）
                if (!earlyStopped) {
                    for (DispatchNode node : nodes.values()) {
                        if (active.size() >= plan.maxParallel()) {
                            break;
                        }
                        if (results.containsKey(node.id()) || active.containsKey(node.id())) {
                            continue;
                        }
                        Optional<DispatchNodeResult> blocked = blockingDependency(node, results);
                        if (blocked.isPresent()) {
                            results.put(node.id(), blocked.get());
                            continue;
                        }
                        if (!dependenciesDone(node, results)) {
                            continue;
                        }
                        active.put(node.id(), dispatch(node, backend, waiters));
                    }
                }

                if (active.isEmpty()) {
                    break;
                }

                // 2. 只等"任意一个"完成：互不依赖的节点是并行等待，而不是逐个串行
                // 切片同时受全局 deadline 约束，超时后回到循环顶部统一收尾
                waitAny(active, globalDeadline);

                // 3. 收割已完成节点
                for (Iterator<Map.Entry<String, Active>> it = active.entrySet().iterator();
                     it.hasNext(); ) {
                    Map.Entry<String, Active> entry = it.next();
                    if (entry.getValue().future().isDone()) {
                        DispatchNodeResult result = join(entry.getValue().future());
                        results.put(entry.getKey(), result);
                        if (result.succeeded()) {
                            consecutiveFailures = 0;
                        } else if (!result.skipped()) {
                            consecutiveFailures++;
                        }
                        it.remove();
                    }
                }

                // 4. 提前终止：必需节点失败熔断 / 连续失败熔断 / 必需节点全部成功后放弃可选节点
                if (!earlyStopped) {
                    Optional<DispatchNodeResult> failedRequired = results.values().stream()
                            .filter(r -> !r.succeeded() && !r.skipped())
                            .filter(r -> nodes.get(r.nodeId()).required())
                            .findFirst();
                    if (plan.abortOnFailure() && failedRequired.isPresent()) {
                        earlyStopped = true;
                        reason = "必需节点 " + failedRequired.get().nodeId() + " "
                                + failedRequired.get().status() + "，提前终止剩余节点";
                    } else if (plan.maxConsecutiveFailures() > 0
                            && consecutiveFailures >= plan.maxConsecutiveFailures()) {
                        earlyStopped = true;
                        reason = "连续 " + consecutiveFailures + " 个节点失败，触发熔断，提前终止剩余节点";
                    } else if (requiredAllSucceeded(nodes, results)
                            && nodes.values().stream().anyMatch(n -> !n.required()
                            && !results.containsKey(n.id()))) {
                        earlyStopped = true;
                        reason = "必需节点已全部成功，提前终止可选节点";
                    }
                    if (earlyStopped) {
                        cancelOptional(nodes, active, backend, results, reason);
                    }
                }
            }
        } finally {
            // 提前终止时可能有被放弃的等待线程，直接中断，避免拖到节点超时才返回
            waiters.shutdownNow();
        }

        // 5. 未开始的节点如实标记，便于调度 Agent 汇报"为什么没跑"
        for (DispatchNode node : nodes.values()) {
            if (!results.containsKey(node.id())) {
                results.put(node.id(), DispatchNodeResult.skipped(node.id(),
                        node.conversationId() == null ? "" : node.conversationId(),
                        earlyStopped ? reason : "未满足调度条件"));
            }
        }

        List<DispatchNodeResult> ordered = new ArrayList<>();
        nodes.keySet().forEach(id -> ordered.add(results.get(id)));
        long elapsed = System.currentTimeMillis() - startedAt;
        if (earlyStopped) {
            log.info("DAG 调度提前终止：{}（节点 {} 个，耗时 {} ms）", reason, ordered.size(), elapsed);
        }
        return new DispatchResult(earlyStopped, reason, elapsed, ordered);
    }

    /** 已完成的依赖里存在非成功节点时，本节点直接跳过（不派发）。 */
    private static Optional<DispatchNodeResult> blockingDependency(DispatchNode node,
                                                                   Map<String, DispatchNodeResult> results) {
        for (String dep : node.dependsOn()) {
            DispatchNodeResult depResult = results.get(dep);
            if (depResult != null && !depResult.succeeded()) {
                return Optional.of(DispatchNodeResult.skipped(node.id(), "",
                        "依赖未成功：" + dep + "（" + depResult.status() + "）"));
            }
        }
        return Optional.empty();
    }

    private static boolean dependenciesDone(DispatchNode node,
                                            Map<String, DispatchNodeResult> results) {
        return node.dependsOn().stream().allMatch(results::containsKey);
    }

    private static boolean requiredAllSucceeded(Map<String, DispatchNode> nodes,
                                                Map<String, DispatchNodeResult> results) {
        List<DispatchNode> required = nodes.values().stream()
                .filter(DispatchNode::required).toList();
        // 全是可选节点时不存在"必需节点已完成"的早停语义，否则会一个都不跑
        return !required.isEmpty() && required.stream().allMatch(n -> {
            DispatchNodeResult r = results.get(n.id());
            return r != null && r.succeeded();
        });
    }

    private Active dispatch(DispatchNode node, Backend backend, ExecutorService waiters) {
        long start = System.currentTimeMillis();
        String conversationId;
        try {
            conversationId = node.conversationId() != null ? node.conversationId()
                    : backend.createConversation(titleOf(node), node.env(), node.worktree());
        } catch (RuntimeException e) {
            return completedFailure(node, "", "", safeMessage(e), start);
        }
        String runId;
        try {
            runId = backend.send(conversationId, node.message());
        } catch (RuntimeException e) {
            return completedFailure(node, conversationId, "", safeMessage(e), start);
        }
        if (runId == null || runId.isBlank() || runId.startsWith("err:")) {
            String message = runId == null || runId.isBlank() ? "派发失败：没有返回 runId" : runId;
            return completedFailure(node, conversationId, "", message, start);
        }
        Active active = new Active(conversationId, runId, new CompletableFuture<>());
        waiters.submit(() -> {
            try {
                active.future().complete(await(node, active, backend, start));
            } catch (RuntimeException e) {
                active.future().complete(new DispatchNodeResult(node.id(), conversationId,
                        active.runId(), "FAILED", false, "", truncate(safeMessage(e), MAX_ERROR_CHARS),
                        System.currentTimeMillis() - start));
            }
        });
        return active;
    }

    /**
     * 等待节点终态；FAILED 且还有重试次数时，复用同一会话重发同一条指令。
     *
     * <p>重试不重置 start，便于汇报总耗时；每次尝试都有独立的节点超时预算。
     * TIMEOUT / CANCELLED 不重试：前者往往意味着环境或模型卡死，重试只会拖长整张 DAG。
     */
    private DispatchNodeResult await(DispatchNode node, Active active, Backend backend,
                                     long start) {
        int attempt = 0;
        while (true) {
            long deadline = System.currentTimeMillis() + node.timeoutSeconds() * 1000L;
            DispatchNodeResult result = awaitOnce(node, active, deadline, backend, start);
            if (result.succeeded() || attempt >= node.retries()
                    || !"FAILED".equals(result.status())) {
                return result;
            }
            attempt++;
            log.info("节点 {} 第 {} 次失败，自动重试 {}/{}：{}",
                    node.id(), attempt, attempt, node.retries(),
                    truncate(result.error(), 120));
            try {
                String nextRunId = backend.send(active.conversationId(), node.message());
                if (nextRunId == null || nextRunId.isBlank() || nextRunId.startsWith("err:")) {
                    String message = nextRunId == null || nextRunId.isBlank()
                            ? "重试派发失败：没有返回 runId" : nextRunId;
                    return new DispatchNodeResult(node.id(), active.conversationId(),
                            active.runId(), "FAILED", false, "",
                            truncate("已重试 " + attempt + " 次；" + message, MAX_ERROR_CHARS),
                            System.currentTimeMillis() - start);
                }
                active.runId(nextRunId);
            } catch (RuntimeException e) {
                return new DispatchNodeResult(node.id(), active.conversationId(),
                        active.runId(), "FAILED", false, "",
                        truncate("已重试 " + attempt + " 次；" + safeMessage(e), MAX_ERROR_CHARS),
                        System.currentTimeMillis() - start);
            }
        }
    }

    private DispatchNodeResult awaitOnce(DispatchNode node, Active active, long deadline,
                                         Backend backend, long start) {
        while (true) {
            String runId = active.runId();
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                cancelQuietly(backend, runId);
                return new DispatchNodeResult(node.id(), active.conversationId(), runId, "TIMEOUT",
                        false, "", "等待超时（" + node.timeoutSeconds() + "s）",
                        System.currentTimeMillis() - start);
            }
            ConversationRun run;
            try {
                run = backend.waitForRun(runId, Math.min(remaining, WAIT_SLICE_MILLIS));
            } catch (RuntimeException e) {
                return new DispatchNodeResult(node.id(), active.conversationId(), runId, "FAILED",
                        false, "", safeMessage(e), System.currentTimeMillis() - start);
            }
            if (run != null && run.status().terminal()) {
                return toResult(node, active.conversationId(), run, start);
            }
        }
    }

    private static DispatchNodeResult toResult(DispatchNode node, String conversationId,
                                               ConversationRun run, long start) {
        return new DispatchNodeResult(node.id(), conversationId, run.id(),
                run.status().name(), false, truncate(run.result(), MAX_SUMMARY_CHARS),
                truncate(run.error(), MAX_ERROR_CHARS),
                System.currentTimeMillis() - start);
    }

    /** 提前终止时只取消"可选 + 在跑"的节点；必需节点允许跑完，结果仍然有价值。 */
    private static void cancelOptional(Map<String, DispatchNode> nodes, Map<String, Active> active,
                                       Backend backend, Map<String, DispatchNodeResult> results,
                                       String reason) {
        for (Iterator<Map.Entry<String, Active>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Active> entry = it.next();
            DispatchNode node = nodes.get(entry.getKey());
            if (node.required()) {
                continue;
            }
            Active running = entry.getValue();
            cancelQuietly(backend, running.runId());
            results.put(node.id(), new DispatchNodeResult(node.id(), running.conversationId(),
                    running.runId(), "CANCELLED", true, "", reason, 0));
            it.remove();
        }
    }

    private static void waitAny(Map<String, Active> active, long globalDeadline) {
        CompletableFuture<?>[] futures = active.values().stream()
                .map(Active::future).toArray(CompletableFuture[]::new);
        long remaining = globalDeadline - System.currentTimeMillis();
        long slice = Math.max(1L, Math.min(WAIT_SLICE_MILLIS, remaining));
        try {
            CompletableFuture.anyOf(futures).get(slice, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 切片到期：回到主循环检查全局 deadline / 收割已完成节点
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // 单个 future 内部已兜底成结果；这里不应中断主循环
            log.debug("等待 DAG 节点时出现异常：{}", e.getMessage());
        }
    }

    /** 全局超时：在跑节点（含必需节点）一并取消，结果如实标记为 TIMEOUT。 */
    private static void abortAll(Map<String, Active> active, Backend backend,
                                 Map<String, DispatchNodeResult> results, String reason,
                                 long startedAt) {
        long elapsed = System.currentTimeMillis() - startedAt;
        for (Iterator<Map.Entry<String, Active>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Active> entry = it.next();
            Active running = entry.getValue();
            cancelQuietly(backend, running.runId());
            results.put(entry.getKey(), new DispatchNodeResult(entry.getKey(),
                    running.conversationId(), running.runId(), "TIMEOUT", true, "", reason, elapsed));
            it.remove();
        }
    }

    private static DispatchNodeResult join(CompletableFuture<DispatchNodeResult> future) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            return new DispatchNodeResult("", "", "", "FAILED", false, "", safeMessage(e), 0);
        }
    }

    private Active completedFailure(DispatchNode node, String conversationId, String runId,
                                    String error, long start) {
        return new Active(conversationId,
                runId == null ? "" : runId,
                CompletableFuture.completedFuture(new DispatchNodeResult(node.id(), conversationId,
                        runId, "FAILED", false, "", truncate(error, MAX_ERROR_CHARS),
                        System.currentTimeMillis() - start)));
    }

    private static void cancelQuietly(Backend backend, String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            backend.cancel(runId);
        } catch (RuntimeException e) {
            log.debug("取消运行失败 run={}：{}", runId, e.getMessage());
        }
    }

    /** Kahn 拓扑排序检测环：环会让节点永远等不到依赖，必须在派发前拒绝。 */
    private static void detectCycles(Map<String, DispatchNode> nodes) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        nodes.keySet().forEach(id -> {
            indegree.put(id, 0);
            children.put(id, new ArrayList<>());
        });
        for (DispatchNode node : nodes.values()) {
            for (String dep : node.dependsOn()) {
                indegree.merge(node.id(), 1, Integer::sum);
                children.get(dep).add(node.id());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            visited++;
            for (String child : children.get(id)) {
                if (indegree.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (visited != nodes.size()) {
            List<String> cycle = nodes.keySet().stream()
                    .filter(id -> indegree.get(id) > 0).toList();
            throw new IllegalArgumentException("调度计划存在循环依赖：" + cycle);
        }
    }

    private static String titleOf(DispatchNode node) {
        if (node.title() != null) {
            return node.title();
        }
        String oneLine = node.message().trim().replaceAll("\\s+", " ");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) : oneLine;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
