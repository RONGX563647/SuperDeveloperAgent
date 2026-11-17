package top.rongx.agent.gateway.web;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.ChatService;
import top.rongx.agent.runtime.workspace.ConversationManager;

/**
 * 工作区调度 Agent 的对话入口（SSE）。
 *
 * <p>与普通对话不同，这里只装配管理工具：它可以创建/派发/跟踪会话，但没有任何
 * 写代码、执行命令或部署的工具。调度会话 id 由服务端固定，客户端不能借这个入口
 * 往普通会话里写消息。
 */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceSuperController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSuperController.class);

    private final ChatService chatService;
    private final ProjectContextResolver contextResolver;
    private final AgentEventMapper eventMapper;
    private final CurrentUser currentUser;
    private final AgentStreamLimiter limiter;
    private final ConversationManager conversations;
    /** 调度会话可能要等整张 DAG 跑完，默认 10 分钟（可按部署调整）。 */
    private final long streamTimeoutMillis;
    private final long heartbeatMillis;

    public WorkspaceSuperController(ChatService chatService,
                                    ProjectContextResolver contextResolver,
                                    AgentEventMapper eventMapper,
                                    CurrentUser currentUser,
                                    AgentStreamLimiter limiter,
                                    ConversationManager conversations,
                                    @Value("${agent.chat.super-timeout-ms:600000}")
                                    long streamTimeoutMillis,
                                    @Value("${agent.chat.heartbeat-seconds:15}")
                                    long heartbeatSeconds) {
        this.chatService = chatService;
        this.contextResolver = contextResolver;
        this.eventMapper = eventMapper;
        this.currentUser = currentUser;
        this.limiter = limiter;
        this.conversations = conversations;
        this.streamTimeoutMillis = Math.max(30_000L, streamTimeoutMillis);
        this.heartbeatMillis = Math.max(1, heartbeatSeconds) * 1000L;
    }

    @Operation(summary = "工作区调度 Agent（SSE 事件流）",
            description = "返回 text/event-stream；调度 Agent 只用只读工具答题，或编排子会话")
    @ApiResponse(responseCode = "200", description = "SSE 事件流",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @PostMapping(value = "/workspaces/{projectId}/super/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String projectId,
                             @RequestBody SuperChatRequest request) {
        String userId = currentUser.requireUserId();
        ProjectContext ctx = contextResolver.resolve(projectId, request.env(), userId, null);
        Conversation supervisor = conversations.ensureSupervisor(projectId, userId);
        if (request.sessionId() != null && !request.sessionId().isBlank()
                && !request.sessionId().equals(supervisor.id())) {
            throw new IllegalArgumentException("调度会话 id 不匹配");
        }
        // 打开工作区时把调度会话的环境同步为当前选择：新建会话默认沿用该环境
        if (!ctx.env().equals(supervisor.env())) {
            conversations.updateOwned(supervisor.id(), userId,
                    new ConversationPatch(null, ctx.env(), null, null, null, null, null));
        }
        AgentStreamLimiter.Lease lease = limiter.acquireOrThrow();

        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        Object sendLock = new Object();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicLong seq = new AtomicLong();
        Runnable cancel = () -> {
            if (closed.compareAndSet(false, true)) {
                SseHeartbeat.stop(heartbeat.get());
                Disposable current = subscription.get();
                if (current != null) {
                    current.dispose();
                }
                limiter.release(lease);
            }
        };
        emitter.onTimeout(() -> {
            log.warn("调度对话超时 project={} timeout={}ms", projectId, streamTimeoutMillis);
            cancel.run();
            emitter.complete();
        });
        emitter.onCompletion(cancel);
        emitter.onError(error -> cancel.run());
        // 长任务期间没有业务事件时靠心跳保活；发送失败会走 cancel 释放配额
        heartbeat.set(SseHeartbeat.start(emitter, sendLock, heartbeatMillis, cancel));

        try {
            Disposable disposable = chatService
                    .streamSupervisor(ctx, supervisor.id(), request.message(),
                            supervisor.modelConnectionId(), supervisor.modelName())
                    .subscribe(
                            event -> send(emitter, event, supervisor.modelName(), seq, cancel,
                                    sendLock),
                            error -> {
                                log.error("调度对话异常 project={}", projectId, error);
                                sendError(emitter, error, sendLock);
                                cancel.run();
                                emitter.complete();
                            },
                            () -> {
                                emitter.complete();
                                cancel.run();
                            });
            subscription.set(disposable);
            if (closed.get()) {
                disposable.dispose();
            }
        } catch (RuntimeException error) {
            log.error("调度对话启动失败 project={}", projectId, error);
            sendError(emitter, error, sendLock);
            cancel.run();
            emitter.complete();
        }
        return emitter;
    }

    private void send(SseEmitter emitter, AgentEvent event, String modelName, AtomicLong seq,
                      Runnable cancel, Object sendLock) {
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(seq.incrementAndGet()))
                        .name("agent")
                        .data(eventMapper.toDto(event, modelName), MediaType.APPLICATION_JSON));
            }
            // AGENT_END 是运行终态：立刻关流并释放配额。
            // 不能只依赖上游 Flux 的 onComplete —— 调度 Agent 的工具循环可能长时间不结束，
            // 心跳会让 SSE 连接一直挂着，前端只能手动点停止才能发下一条。
            if (event.getType() == AgentEventType.AGENT_END) {
                emitter.complete();
                cancel.run();
            }
        } catch (IOException clientGone) {
            cancel.run();
            emitter.complete();
        } catch (RuntimeException error) {
            sendError(emitter, error, sendLock);
            cancel.run();
            emitter.complete();
        }
    }

    private void sendError(SseEmitter emitter, Throwable error, Object sendLock) {
        String message = error.getMessage();
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", message == null || message.isBlank()
                                ? error.getClass().getSimpleName() : message),
                                MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开，无需再写
        }
    }

    public record SuperChatRequest(String sessionId, String message, String env) {
    }
}
