package top.rongx.agent.runtime.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.ConversationTranscriptRepository;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.run.RunKind;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.domain.run.RunRecord;
import top.rongx.agent.domain.run.RunState;
import top.rongx.agent.runtime.ChatService;

/**
 * 工作区异步运行执行器。
 *
 * <p>每个工作区一个固定大小的线程池（默认 3）：调度 Agent 发起的会话运行排队执行，
 * 不会把浏览器连接或调度 Agent 自己卡住。运行状态、结果、错误全部落盘；
 * 取消时同时移除排队任务并释放上游事件流（触发 MCP 客户端关闭）。
 */
@Component
public class WorkspaceRunExecutor implements RunAborter {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRunExecutor.class);

    private static final int MAX_QUEUE = 100;
    private static final int MAX_RESULT_CHARS = 20_000;
    /** 与前端保持一致：单个会话时间线最多保留 200 条消息。 */
    private static final int MAX_TRANSCRIPT_MESSAGES = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Results(String result, String error) {
    }

    private static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final CountDownLatch streamDone = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Future<?> future;
    }

    private final ObjectProvider<ChatService> chatService;
    private final ConversationRunRepository runs;
    private final ConversationRepository conversations;
    private final ConversationTranscriptRepository transcripts;
    private final WorkspaceEventBus events;
    private final RunLedgerRepository ledger;
    private final int maxConcurrent;

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    public WorkspaceRunExecutor(ObjectProvider<ChatService> chatService,
                                ConversationRunRepository runs,
                                ConversationRepository conversations,
                                ConversationTranscriptRepository transcripts,
                                WorkspaceEventBus events,
                                RunLedgerRepository ledger,
                                @Value("${agent.workspace.supervisor.max-concurrent:3}") int maxConcurrent) {
        this.chatService = chatService;
        this.runs = runs;
        this.conversations = conversations;
        this.transcripts = transcripts;
        this.events = events;
        this.ledger = ledger;
        this.maxConcurrent = Math.max(1, Math.min(maxConcurrent, 10));
    }

    /** 提交一次运行；队列满时直接把运行标记为失败，不抛给调用方。 */
    public void submit(ProjectContext ctx, ConversationRun run, String text) {
        Handle handle = new Handle();
        handles.put(run.id(), handle);
        track(run, RunState.QUEUED, "已入队", "");
        try {
            handle.future = pool(run.projectId()).submit(
                    () -> execute(ctx, run, text, handle));
        } catch (RejectedExecutionException e) {
            finish(run, RunStatus.FAILED, "", "工作区运行队列已满，请稍后再试", handle);
        }
    }

    /** 取消运行；返回是否真的取消（已结束返回 false）。 */
    public boolean cancel(String runId) {
        Handle handle = handles.get(runId);
        if (handle == null) {
            return false;
        }
        synchronized (handle) {
            if (handle.finished.get()) {
                return false;
            }
            handle.cancelled.set(true);
            handle.streamDone.countDown();
            Disposable subscription = handle.subscription.get();
            if (subscription != null) {
                subscription.dispose();
            }
            Future<?> future = handle.future;
            ConversationRun current = runs.findRun(runId).orElse(null);
            if (future != null) {
                // 快速路径：排队中的任务立刻从队列移除，空出的并发位给后续运行，
                // 而不是等线程池出队时才看到 cancelled 标记。
                if (future instanceof Runnable runnable && current != null) {
                    pool(current.projectId()).remove(runnable);
                }
                future.cancel(false);
            }
            if (current == null) {
                return false;
            }
            finish(current, RunStatus.CANCELLED, "", "已取消", handle);
            return true;
        }
    }

    @Override
    public boolean fail(String runId, String reason) {
        Handle handle = handles.get(runId);
        if (handle == null) {
            return false;
        }
        synchronized (handle) {
            if (handle.finished.get()) {
                return false;
            }
            handle.cancelled.set(true);
            handle.streamDone.countDown();
            Disposable subscription = handle.subscription.get();
            if (subscription != null) {
                subscription.dispose();
            }
            Future<?> future = handle.future;
            ConversationRun current = runs.findRun(runId).orElse(null);
            if (future != null) {
                if (future instanceof Runnable runnable && current != null) {
                    pool(current.projectId()).remove(runnable);
                }
                future.cancel(false);
            }
            if (current == null) {
                return false;
            }
            finish(current, RunStatus.FAILED, "", reason, handle);
            return true;
        }
    }

    /** 等待运行结束（调用方负责把超时限制在 60 秒内）。 */
    public void await(String runId, long timeoutMillis) {
        Handle handle = handles.get(runId);
        if (handle == null) {
            return;
        }
        try {
            handle.done.await(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void execute(ProjectContext ctx, ConversationRun run, String text, Handle handle) {
        synchronized (handle) {
            if (handle.finished.get() || handle.cancelled.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            runs.updateRun(run.withStatus(RunStatus.RUNNING, "", "", now, 0));
            conversations.update(run.conversationId(), run.ownerUserId(),
                    ConversationPatch.status(ConversationStatus.RUNNING));
            events.publish(new WorkspaceEventBus.WorkspaceEvent(run.projectId(),
                    run.conversationId(), run.id(), run.ownerUserId(),
                    "run.status", "RUNNING", "", now));
            track(run, RunState.RUNNING, "正在执行", "");
        }

        Results results = stream(ctx, run, text, handle);
        ConversationRun current = runs.findRun(run.id()).orElse(run);
        if (handle.cancelled.get() || current.status() == RunStatus.CANCELLED) {
            finish(run, RunStatus.CANCELLED, results.result(), "已取消", handle);
        } else if (!results.error().isBlank()) {
            finish(run, RunStatus.FAILED, results.result(), results.error(), handle);
        } else {
            finish(run, RunStatus.SUCCEEDED, results.result(), "", handle);
        }
    }

    private Results stream(ProjectContext ctx, ConversationRun run, String text, Handle handle) {
        StringBuilder output = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Flux<AgentEvent> flux;
        try {
            // 异步运行沿用会话保存的模型选择（与用户在对话栏看到的一致）
            top.rongx.agent.domain.conversation.Conversation conversation =
                    conversations.find(run.conversationId()).orElse(null);
            flux = chatService.getObject().stream(ctx, run.conversationId(), text,
                    "chat", null, null,
                    conversation == null ? null : conversation.modelConnectionId(),
                    conversation == null ? null : conversation.modelName());
        } catch (RuntimeException e) {
            return new Results("", safeMessage(e));
        }
        Disposable subscription = flux.subscribe(
                event -> onEvent(event, run, output, handle),
                error -> {
                    failure.set(error);
                    handle.streamDone.countDown();
                },
                handle.streamDone::countDown);
        handle.subscription.set(subscription);
        if (handle.cancelled.get()) {
            subscription.dispose();
            handle.streamDone.countDown();
        }
        try {
            handle.streamDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subscription.dispose();
        Throwable error = failure.get();
        return new Results(trim(output.toString()), error == null ? "" : safeMessage(error));
    }

    private void onEvent(AgentEvent event, ConversationRun run, StringBuilder output, Handle handle) {
        if (handle.cancelled.get() || !(event instanceof TextBlockDeltaEvent delta)) {
            return;
        }
        String text = delta.getDelta();
        if (text == null || text.isEmpty() || output.length() >= MAX_RESULT_CHARS) {
            return;
        }
        output.append(text);
        events.publish(new WorkspaceEventBus.WorkspaceEvent(run.projectId(),
                run.conversationId(), run.id(), run.ownerUserId(), "text.delta", "RUNNING",
                text, System.currentTimeMillis()));
    }

    private void finish(ConversationRun base, RunStatus status, String result, String error,
                        Handle handle) {
        synchronized (handle) {
            if (!handle.finished.compareAndSet(false, true)) {
                return;
            }
            ConversationRun current = runs.findRun(base.id()).orElse(base);
            long now = System.currentTimeMillis();
            long started = current.startedAt() == 0 ? now : current.startedAt();
            ConversationRun updated = current.withStatus(
                    status, trim(result), trim(error), started, now);
            // 先补时间线再落终态：等待方看到 terminal 时，本轮记录必须已经可读，
            // 否则同一会话紧接着的下一轮会读到旧时间线并把它覆盖掉。
            saveTranscript(updated);
            runs.updateRun(updated);
            conversations.update(updated.conversationId(), updated.ownerUserId(),
                    new ConversationPatch(null, null, null, summaryOf(updated),
                            conversationStatus(status), null, null));
            events.publish(new WorkspaceEventBus.WorkspaceEvent(updated.projectId(),
                    updated.conversationId(), updated.id(), updated.ownerUserId(),
                    "run.status", status.name(), updated.result(), now));
            track(updated, ledgerState(status), summaryOf(updated), updated.error());
            handles.remove(updated.id());
            handle.streamDone.countDown();
            handle.done.countDown();
            if (status == RunStatus.FAILED) {
                log.warn("工作区运行失败 run={} conversation={}：{}",
                        updated.id(), updated.conversationId(), updated.error());
            }
        }
    }

    /**
     * 运行结束后把这一轮的提问与产出追加进会话时间线。
     *
     * <p>子会话由调度 Agent 异步派发，前端没有打开对应页面，时间线不会由浏览器写入；
     * 不在这里补一次，用户点开会话只能看到空白记录。写入失败只记日志，不影响运行终态。
     */
    private void saveTranscript(ConversationRun run) {
        try {
            ObjectNode root = existingTranscript(run.conversationId());
            ArrayNode messages = root.withArray("messages");
            messages.add(messageNode(run.id() + "-user", "user", run.goal(), run.createdAt()));
            String reply = run.result();
            if (!run.error().isBlank()) {
                reply = reply.isBlank() ? "运行失败：" + run.error()
                        : reply + "\n\n运行失败：" + run.error();
            }
            if (!reply.isBlank()) {
                messages.add(messageNode(run.id() + "-assistant", "assistant", reply,
                        System.currentTimeMillis()));
            }
            while (messages.size() > MAX_TRANSCRIPT_MESSAGES) {
                messages.remove(0);
            }
            transcripts.saveTranscript(run.conversationId(), JSON.writeValueAsString(root));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("会话时间线写入失败 conversation={} run={}：{}",
                    run.conversationId(), run.id(), e.getMessage());
        }
    }

    /** 读取已保存的时间线；为空或损坏时按空模板重建，避免整轮记录丢失。 */
    private ObjectNode existingTranscript(String conversationId) {
        Optional<String> stored = transcripts.findTranscript(conversationId);
        if (stored.isPresent()) {
            try {
                JsonNode node = JSON.readTree(stored.get());
                if (node instanceof ObjectNode object
                        && object.get("messages") instanceof ArrayNode) {
                    if (!object.has("tools") || !object.get("tools").isObject()) {
                        object.putObject("tools");
                    }
                    return object;
                }
            } catch (JsonProcessingException ignored) {
                // 旧数据损坏：按空时间线重建
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.putArray("messages");
        root.putObject("tools");
        return root;
    }

    private static ObjectNode messageNode(String id, String role, String text, long at) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id);
        node.put("role", role);
        node.put("text", text == null ? "" : text);
        node.put("at", at);
        return node;
    }

    private ThreadPoolExecutor pool(String projectId) {
        return pools.computeIfAbsent(projectId, id -> {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    maxConcurrent, maxConcurrent, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(MAX_QUEUE),
                    Thread.ofVirtual().name("workspace-run-" + id + "-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
            return pool;
        });
    }

    private static ConversationStatus conversationStatus(RunStatus status) {
        return switch (status) {
            case QUEUED, RUNNING -> ConversationStatus.RUNNING;
            case SUCCEEDED -> ConversationStatus.SUCCEEDED;
            case FAILED -> ConversationStatus.FAILED;
            case CANCELLED -> ConversationStatus.CANCELLED;
        };
    }

    private static RunState ledgerState(RunStatus status) {
        return switch (status) {
            case QUEUED -> RunState.QUEUED;
            case RUNNING -> RunState.RUNNING;
            case SUCCEEDED -> RunState.SUCCEEDED;
            case FAILED -> RunState.FAILED;
            case CANCELLED -> RunState.CANCELLED;
        };
    }

    /** 统一运行账本双写：失败只记日志，绝不影响会话运行本身。 */
    private void track(ConversationRun run, RunState state, String summary, String error) {
        try {
            long now = System.currentTimeMillis();
            ledger.save(new RunRecord(run.id(), RunKind.WORKSPACE, run.projectId(),
                    run.conversationId(), run.ownerUserId(), state, summary, error,
                    run.createdAt(), now));
        } catch (RuntimeException e) {
            log.warn("运行账本写入失败 run={}：{}", run.id(), e.getMessage());
        }
    }

    private static String summaryOf(ConversationRun run) {
        if (!run.error().isBlank()) {
            return "失败：" + run.error();
        }
        String result = run.result().isBlank() ? "已完成（无文本输出）" : run.result();
        return result.length() > 600 ? result.substring(0, 600) + "…" : result;
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_RESULT_CHARS ? value.substring(0, MAX_RESULT_CHARS) : value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @PreDestroy
    void shutdown() {
        pools.values().forEach(ThreadPoolExecutor::shutdownNow);
    }
}
