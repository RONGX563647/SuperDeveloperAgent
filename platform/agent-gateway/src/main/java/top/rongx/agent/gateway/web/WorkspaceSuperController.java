package top.rongx.agent.gateway.web;

import io.agentscope.core.event.AgentEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final long TIMEOUT_MS = 180_000L;
    private static final Logger log = LoggerFactory.getLogger(WorkspaceSuperController.class);

    private final ChatService chatService;
    private final ProjectContextResolver contextResolver;
    private final AgentEventMapper eventMapper;
    private final CurrentUser currentUser;
    private final AgentStreamLimiter limiter;
    private final ConversationManager conversations;

    public WorkspaceSuperController(ChatService chatService,
                                    ProjectContextResolver contextResolver,
                                    AgentEventMapper eventMapper,
                                    CurrentUser currentUser,
                                    AgentStreamLimiter limiter,
                                    ConversationManager conversations) {
        this.chatService = chatService;
        this.contextResolver = contextResolver;
        this.eventMapper = eventMapper;
        this.currentUser = currentUser;
        this.limiter = limiter;
        this.conversations = conversations;
    }

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
        limiter.acquireOrThrow();

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicLong seq = new AtomicLong();
        Runnable cancel = () -> {
            if (closed.compareAndSet(false, true)) {
                Disposable current = subscription.get();
                if (current != null) {
                    current.dispose();
                }
                limiter.release();
            }
        };
        emitter.onTimeout(() -> {
            log.warn("调度对话超时 project={}", projectId);
            cancel.run();
            emitter.complete();
        });
        emitter.onCompletion(cancel);
        emitter.onError(error -> cancel.run());

        try {
            Disposable disposable = chatService
                    .streamSupervisor(ctx, supervisor.id(), request.message())
                    .subscribe(
                            event -> send(emitter, event, seq, cancel),
                            error -> {
                                log.error("调度对话异常 project={}", projectId, error);
                                sendError(emitter, error);
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
            sendError(emitter, error);
            cancel.run();
            emitter.complete();
        }
        return emitter;
    }

    private void send(SseEmitter emitter, AgentEvent event, AtomicLong seq, Runnable cancel) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(seq.incrementAndGet()))
                    .name("agent")
                    .data(eventMapper.toDto(event), MediaType.APPLICATION_JSON));
        } catch (IOException clientGone) {
            cancel.run();
            emitter.complete();
        } catch (RuntimeException error) {
            sendError(emitter, error);
            cancel.run();
            emitter.complete();
        }
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        String message = error.getMessage();
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", message == null || message.isBlank()
                            ? error.getClass().getSimpleName() : message),
                            MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开，无需再写
        }
    }

    public record SuperChatRequest(String sessionId, String message, String env) {
    }
}
