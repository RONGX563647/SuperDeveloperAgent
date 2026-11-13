package top.rongx.agent.gateway.web;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.ChangeWire;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.governance.ChangeRegistry;
import top.rongx.agent.runtime.ChatService;
import top.rongx.agent.gateway.web.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 对话入口（SSE）。
 *
 * <p>线程模型：用虚拟线程承载一次会话；工具上下文经 RuntimeContext 显式传递，
 * 不依赖 ThreadLocal，因此可以安全地跨线程执行。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    /** 单次会话最长 3 分钟。 */
    private static final long TIMEOUT_MS = 180_000L;

    /** 变更相关工具：只有它们的返回会被翻译成确认卡事件（委派结果里也可能带 changeId）。 */
    private static final java.util.Set<String> CHANGE_TOOLS =
            java.util.Set.of("request_confirmation", "confirm", "run_ops_agent");

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ProjectContextResolver contextResolver;
    private final AgentEventMapper eventMapper;
    private final top.rongx.agent.gateway.auth.CurrentUser currentUser;
    private final ChangeRegistry changes;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService chatService,
                          ProjectContextResolver contextResolver,
                          AgentEventMapper eventMapper,
                          top.rongx.agent.gateway.auth.CurrentUser currentUser,
                          ChangeRegistry changes) {
        this.chatService = chatService;
        this.contextResolver = contextResolver;
        this.eventMapper = eventMapper;
        this.currentUser = currentUser;
        this.changes = changes;
    }

    /**
     * 发起一轮对话，以 SSE 返回事件流。
     *
     * @param request 请求体
     * @return SSE 发射器（身份来自 Sa-Token 会话）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        // 1. 认证：身份由 Sa-Token 会话保证（客户端不可伪造）
        String userId = currentUser.requireUserId();
        // 2. 服务端解析并校验项目上下文（模型不可篡改）
        ProjectContext ctx = contextResolver.resolve(
                request.projectId(), request.env(), userId, request.worktree());
        // 3. 建立 SSE 发射器，向业务层要事件流
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Flux<AgentEvent> events = chatService.stream(ctx, request.sessionId(), request.message(),
                "chat", request.skills(), request.mcpServers());
        // 4. 虚拟线程异步推送：逐事件映射为 DTO 并发送
        executor.submit(() -> {
            try {
                long seq = 0;
                // 工具结果是流式增量：按 toolCallId 累积，结束事件时判断是否含变更单号
                Map<String, String> toolNames = new HashMap<>();
                Map<String, StringBuilder> toolResults = new HashMap<>();
                for (AgentEvent event : events.toIterable()) {
                    if (event instanceof ToolCallStartEvent call && call.getToolCallId() != null) {
                        toolNames.put(call.getToolCallId(), call.getToolCallName());
                    } else if (event instanceof ToolResultTextDeltaEvent delta
                            && delta.getToolCallId() != null && delta.getDelta() != null) {
                        StringBuilder buf = toolResults.computeIfAbsent(
                                delta.getToolCallId(), k -> new StringBuilder());
                        // 单次工具结果不可能无限长；限制累积量，避免异常输出撑爆内存
                        if (buf.length() < 64_000) {
                            buf.append(delta.getDelta());
                        }
                    }
                    Map<String, Object> dto = eventMapper.toDto(event);
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(++seq))
                            .name("agent")
                            .data(dto, MediaType.APPLICATION_JSON));
                    // 变更单是"给人确认"的对象：在工具结果后补一条 change.created，
                    // 前端据此展开确认卡片（原先只在正文里写单号，卡片永远不弹）
                    if (event instanceof ToolResultEndEvent end) {
                        String toolName = end.getToolCallName() != null
                                ? end.getToolCallName() : toolNames.get(end.getToolCallId());
                        StringBuilder result = toolResults.remove(end.getToolCallId());
                        String changeId = toolName != null && CHANGE_TOOLS.contains(toolName)
                                && result != null ? changeIdOf(result.toString(), ctx) : null;
                        if (changeId != null) {
                            emitter.send(SseEmitter.event()
                                    .id(String.valueOf(++seq))
                                    .name("agent")
                                    .data(Map.of("type", "change.created",
                                            "payload", Map.of("changeId", changeId)),
                                            MediaType.APPLICATION_JSON));
                        }
                    }
                }
                emitter.complete();
            } catch (IOException clientGone) {
                // 客户端主动断开：不能再用 completeWithError（会触发错误页派发并失败）
                emitter.complete();
            } catch (RuntimeException error) {
                // 已经建立流之后再抛 500 是无效的：改为发一条 error 事件，然后正常结束
                log.error("对话流异常 project={} env={}", ctx.projectId(), ctx.env(), error);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", safeMessage(error)), MediaType.APPLICATION_JSON));
                } catch (IOException ignored) {
                    // 客户端已断开，无需再写
                }
                emitter.complete();
            }
        });
        return emitter;
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
    private String safeMessage(RuntimeException error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }
}
