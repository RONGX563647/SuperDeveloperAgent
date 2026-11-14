package top.rongx.agent.runtime.workspace;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import jakarta.annotation.PreDestroy;
import java.util.Map;
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
public class WorkspaceRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRunExecutor.class);

    private static final int MAX_QUEUE = 100;
    private static final int MAX_RESULT_CHARS = 20_000;

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
    private final WorkspaceEventBus events;
    private final RunLedgerRepository ledger;
    private final int maxConcurrent;

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    public WorkspaceRunExecutor(ObjectProvider<ChatService> chatService,
                                ConversationRunRepository runs,
                                ConversationRepository conversations,
                                WorkspaceEventBus events,
                                RunLedgerRepository ledger,
                                @Value("${agent.workspace.supervisor.max-concurrent:3}") int maxConcurrent) {
        this.chatService = chatService;
        this.runs = runs;
        this.conversations = conversations;
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
            if (future != null) {
                future.cancel(false);
            }
            ConversationRun current = runs.findRun(runId).orElse(null);
            if (current == null) {
                return false;
            }
            finish(current, RunStatus.CANCELLED, "", "已取消", handle);
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
            flux = chatService.getObject().stream(ctx, run.conversationId(), text,
                    "chat", null, null);
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
            ConversationRun updated = runs.updateRun(current.withStatus(
                    status, trim(result), trim(error), started, now));
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
