package top.rongx.agent.gateway.web;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.ChangeWire;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;
import top.rongx.agent.domain.settings.ModelCatalogRepository;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.gateway.web.dto.ChatRequest;
import top.rongx.agent.governance.ChangeRegistry;
import top.rongx.agent.runtime.ChatService;
import top.rongx.agent.runtime.workspace.ConversationManager;

/**
 * 对话入口（SSE）。
 *
 * <p>线程模型：事件流由 Reactor 驱动，SSE 发送在订阅回调里完成；工具上下文经
 * RuntimeContext 显式传递，不依赖 ThreadLocal。
 *
 * <p>连接生命周期：客户端断开 / 超时 / 服务端异常都会取消上游订阅，
 * 从而触发 ChatService 的 doFinally 关闭本会话的 MCP 客户端，避免进程泄漏。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    /** 变更相关工具：只有它们的返回会被翻译成确认卡事件（委派结果里也可能带 changeId）。 */
    private static final java.util.Set<String> CHANGE_TOOLS =
            java.util.Set.of("request_confirmation", "confirm", "propose_patch", "run_action",
                    "run_ops_agent");

    /** 生成环境绑定草案的工具：返回里带 bindingId，前端据此在对话中展开绑定确认卡。 */
    private static final java.util.Set<String> ENV_BINDING_TOOLS =
            java.util.Set.of("env_mapping_draft", "run_ops_agent");

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ProjectContextResolver contextResolver;
    private final AgentEventMapper eventMapper;
    private final top.rongx.agent.gateway.auth.CurrentUser currentUser;
    private final ChangeRegistry changes;
    private final top.rongx.agent.domain.env.EnvironmentStore envStore;
    private final AgentStreamLimiter limiter;
    private final AgentMetrics metrics;
    private final ConversationManager conversations;
    private final ModelRegistry models;
    private final ModelConnectionRepository connections;
    private final ModelCatalogRepository catalog;
    /** 单次会话的 SSE 生命周期上限（默认 3 分钟，可按部署调整）。 */
    private final long streamTimeoutMillis;
    private final long heartbeatMillis;

    public ChatController(ChatService chatService,
                          ProjectContextResolver contextResolver,
                          AgentEventMapper eventMapper,
                          top.rongx.agent.gateway.auth.CurrentUser currentUser,
                          ChangeRegistry changes,
                          top.rongx.agent.domain.env.EnvironmentStore envStore,
                          AgentStreamLimiter limiter,
                          AgentMetrics metrics,
                          ConversationManager conversations,
                          ModelRegistry models,
                          ModelConnectionRepository connections,
                          ModelCatalogRepository catalog,
                          @Value("${agent.chat.timeout-ms:180000}") long streamTimeoutMillis,
                          @Value("${agent.chat.heartbeat-seconds:15}") long heartbeatSeconds) {
        this.chatService = chatService;
        this.contextResolver = contextResolver;
        this.eventMapper = eventMapper;
        this.currentUser = currentUser;
        this.changes = changes;
        this.envStore = envStore;
        this.limiter = limiter;
        this.metrics = metrics;
        this.conversations = conversations;
        this.models = models;
        this.connections = connections;
        this.catalog = catalog;
        this.streamTimeoutMillis = Math.max(30_000L, streamTimeoutMillis);
        this.heartbeatMillis = Math.max(1, heartbeatSeconds) * 1000L;
    }

    /**
     * 发起一轮对话，以 SSE 返回事件流。
     *
     * @param request 请求体
     * @return SSE 发射器（身份来自 Sa-Token 会话）
     */
    @Operation(summary = "对话（SSE 事件流）",
            description = "返回 text/event-stream；事件名含 message/tool/usage/done/error，事件体见 docs/api/overview.md")
    @ApiResponse(responseCode = "200", description = "SSE 事件流",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        // 1. 认证：身份由 Sa-Token 会话保证（客户端不可伪造）
        String userId = currentUser.requireUserId();
        // 2. 服务端解析并校验项目上下文（模型不可篡改）
        ProjectContext ctx = contextResolver.resolve(
                request.projectId(), request.env(), userId, request.worktree());
        // 2.0 会话选择的模型必须真实可用：非法/停用的连接或目录外模型直接 400，不回退默认
        requireKnownModel(request.modelConnectionId(), request.modelName());
        ActiveModel active = models.resolve(request.modelConnectionId(), request.modelName())
                .orElseThrow(() -> new IllegalArgumentException("模型连接不可用或已停用："
                        + (request.modelConnectionId() == null ? "默认连接"
                        : request.modelConnectionId())));
        // 2.1 会话元数据服务端持久化：首条消息落标题，之后刷新活跃时间/环境/工作树/模型
        conversations.ensureFromChat(ctx, request.sessionId(), request.message(),
                request.worktree(), request.modelConnectionId(), request.modelName());
        // 3. 并发闸门：拿不到许可直接 429，不构建 Agent、不拉起 MCP
        AgentStreamLimiter.Lease lease = limiter.acquireOrThrow();

        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        Object sendLock = new Object();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicLong seq = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean();
        long started = System.nanoTime();
        io.micrometer.core.instrument.Timer.Sample sample = metrics.start();
        Runnable cancel = () -> {
            if (closed.compareAndSet(false, true)) {
                SseHeartbeat.stop(heartbeat.get());
                Disposable current = subscription.get();
                if (current != null) {
                    current.dispose();
                }
                limiter.release(lease);
                metrics.stop(sample, "chat", failed.get() ? "error" : "closed");
                log.info("对话流结束 project={} env={} session={} events={} cost={}ms",
                        ctx.projectId(), ctx.env(), request.sessionId(), seq.get(),
                        (System.nanoTime() - started) / 1_000_000);
            }
        };
        // 客户端断开 / 超时 / 派发错误都要取消上游，否则 Agent 与 MCP 会继续跑
        emitter.onTimeout(() -> {
            log.warn("对话流超时，已取消 project={} session={} timeout={}ms",
                    ctx.projectId(), request.sessionId(), streamTimeoutMillis);
            cancel.run();
            emitter.complete();
        });
        emitter.onCompletion(cancel);
        emitter.onError(error -> cancel.run());
        // 工具执行/模型思考期间没有事件时靠心跳保活，避免代理按空闲超时断流
        heartbeat.set(SseHeartbeat.start(emitter, sendLock, heartbeatMillis, cancel));

        try {
            Flux<AgentEvent> events = chatService.stream(ctx, request.sessionId(), request.message(),
                    "chat", request.skills(), request.mcpServers(),
                    request.modelConnectionId(), request.modelName());
            // 工具结果是流式增量：按 toolCallId 累积，结束事件时判断是否含变更单号
            Map<String, String> toolNames = new HashMap<>();
            Map<String, StringBuilder> toolResults = new HashMap<>();
            Disposable disposable = events.subscribe(
                    event -> onEvent(event, emitter, ctx, toolNames, toolResults, seq, cancel,
                            sendLock, active.modelName()),
                    error -> {
                        failed.set(true);
                        log.error("对话流异常 project={} env={}", ctx.projectId(), ctx.env(), error);
                        sendError(emitter, error, sendLock);
                        cancel.run();
                        emitter.complete();
                    },
                    () -> {
                        emitter.complete();
                        cancel.run();
                    });
            subscription.set(disposable);
            // 竞态兜底：订阅可能在 set 之前就已结束（空流/同步错误）
            if (closed.get()) {
                disposable.dispose();
            }
        } catch (RuntimeException error) {
            failed.set(true);
            log.error("对话启动失败 project={} env={}", ctx.projectId(), ctx.env(), error);
            sendError(emitter, error, sendLock);
            cancel.run();
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 校验「模型属于该网关」：连接配置里的模型名或目录（发现/手工）中的模型都算合法。
     *
     * <p>只给连接不给模型（或只给模型）时不在这里判断，交由 ModelRegistry 解析。
     */
    private void requireKnownModel(String connectionId, String modelName) {
        if (connectionId == null || connectionId.isBlank()
                || modelName == null || modelName.isBlank()) {
            return;
        }
        boolean configured = connections.find(connectionId)
                .map(c -> modelName.equals(c.modelName()))
                .orElse(false);
        if (!configured && catalog.find(connectionId, modelName).isEmpty()) {
            throw new IllegalArgumentException("模型不在该网关的模型目录中：" + modelName
                    + "（可在「设置 → 模型连接」同步网关模型）");
        }
    }

    private void onEvent(AgentEvent event, SseEmitter emitter, ProjectContext ctx,
                         Map<String, String> toolNames, Map<String, StringBuilder> toolResults,
                         AtomicLong seq, Runnable cancel, Object sendLock, String modelName) {
        try {
            if (event instanceof ToolCallStartEvent call && call.getToolCallId() != null) {
                toolNames.put(call.getToolCallId(), call.getToolCallName());
                metrics.toolCall(call.getToolCallName(), "invoked");
            } else if (event instanceof ToolResultTextDeltaEvent delta
                    && delta.getToolCallId() != null && delta.getDelta() != null) {
                StringBuilder buf = toolResults.computeIfAbsent(
                        delta.getToolCallId(), k -> new StringBuilder());
                // 单次工具结果不可能无限长；限制累积量，避免异常输出撑爆内存
                if (buf.length() < 64_000) {
                    buf.append(delta.getDelta());
                }
            }
            Map<String, Object> dto = eventMapper.toDto(event, modelName);
            synchronized (sendLock) {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(seq.incrementAndGet()))
                        .name("agent")
                        .data(dto, MediaType.APPLICATION_JSON));
            }
            // 变更单是"给人确认"的对象：在工具结果后补一条 change.created，
            // 前端据此展开确认卡片（原先只在正文里写单号，卡片永远不弹）
            if (event instanceof ToolResultEndEvent end) {
                String toolName = end.getToolCallName() != null
                        ? end.getToolCallName() : toolNames.get(end.getToolCallId());
                metrics.toolCall(toolName, "completed");
                StringBuilder result = toolResults.remove(end.getToolCallId());
                String text = result == null ? "" : result.toString();
                String changeId = toolName != null && CHANGE_TOOLS.contains(toolName)
                        ? changeIdOf(text, ctx) : null;
                if (changeId != null) {
                    synchronized (sendLock) {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(seq.incrementAndGet()))
                                .name("agent")
                                .data(Map.of("type", "change.created",
                                        "payload", Map.of("changeId", changeId)),
                                        MediaType.APPLICATION_JSON));
                    }
                }
                String bindingId = toolName != null && ENV_BINDING_TOOLS.contains(toolName)
                        ? bindingIdOf(text, ctx) : null;
                if (bindingId != null) {
                    synchronized (sendLock) {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(seq.incrementAndGet()))
                                .name("agent")
                                .data(Map.of("type", "env_binding.created",
                                        "payload", Map.of("bindingId", bindingId,
                                                "projectId", ctx.projectId(),
                                                "env", ctx.env())),
                                        MediaType.APPLICATION_JSON));
                    }
                }
            }
        } catch (IOException clientGone) {
            // 客户端主动断开：直接取消上游，不再 completeWithError
            cancel.run();
            emitter.complete();
        } catch (RuntimeException error) {
            log.error("对话事件推送失败 project={} env={}", ctx.projectId(), ctx.env(), error);
            sendError(emitter, error, sendLock);
            cancel.run();
            emitter.complete();
        }
    }

    private void sendError(SseEmitter emitter, Throwable error, Object sendLock) {
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", safeMessage(error)), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开，无需再写
        }
    }

    /**
     * 从工具返回文本里提取 bindingId，并核对它确实是当前项目/环境的 DRAFT。
     *
     * <p>只把服务端真实存在、且属于当前上下文的草案推给前端；模型复述或伪造的
     * bindingId 不会生成确认卡（否则用户可能确认到别的项目的绑定）。
     */
    private String bindingIdOf(String text, ProjectContext ctx) {
        String id = top.rongx.agent.domain.env.EnvBindingWire.extract(text);
        if (id == null) {
            return null;
        }
        return envStore.binding(id)
                .filter(binding -> binding.projectId().equals(ctx.projectId()))
                .filter(binding -> binding.env().equalsIgnoreCase(ctx.env()))
                .filter(binding -> "DRAFT".equals(binding.status()))
                .map(binding -> binding.id())
                .orElse(null);
    }

    /**
     * 从工具返回文本里提取变更单号，并用登记表核对。
     *
     * <p>格式由 {@link ChangeWire} 单点定义（生产者/消费者共用），不再各写一份正则；
     * 另外要求单号在当前项目的变更登记里真实存在，避免把模型复述的、别的项目的单号
     * 当成可确认的变更推给前端。
     */
    private String changeIdOf(String text, ProjectContext ctx) {
        String changeId = ChangeWire.extract(text);
        if (changeId == null) {
            return null;
        }
        ChangePlan plan = changes.find(changeId).orElse(null);
        if (plan == null || !plan.projectId().equals(ctx.projectId())) {
            log.warn("忽略无法核对的变更单：{}（project={}）", changeId, ctx.projectId());
            return null;
        }
        return changeId;
    }

    /** 对外只暴露简短、无内部细节的错误文案（规约 §3）。 */
    private String safeMessage(Throwable error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }
}
