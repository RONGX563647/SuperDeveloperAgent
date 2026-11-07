package top.rongx.agent.gateway.web;

import io.agentscope.core.event.AgentEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import top.rongx.agent.domain.project.ProjectContext;
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

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ProjectContextResolver contextResolver;
    private final AgentEventMapper eventMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService chatService,
                          ProjectContextResolver contextResolver,
                          AgentEventMapper eventMapper) {
        this.chatService = chatService;
        this.contextResolver = contextResolver;
        this.eventMapper = eventMapper;
    }

    /**
     * 发起一轮对话，以 SSE 返回事件流。
     *
     * @param request  请求体
     * @param userId   当前用户（骨架用请求头；接账号体系后改由会话解析）
     * @return SSE 发射器
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request,
                           @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // 1. 认证：缺少用户头直接拒绝
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("未认证：缺少 X-User-Id");
        }
        // 2. 服务端解析并校验项目上下文（模型不可篡改）
        ProjectContext ctx = contextResolver.resolve(request.projectId(), request.env(), userId);
        // 3. 建立 SSE 发射器，向业务层要事件流
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Flux<AgentEvent> events = chatService.stream(ctx, request.sessionId(), request.message());
        // 4. 虚拟线程异步推送：逐事件映射为 DTO 并发送
        executor.submit(() -> {
            try {
                long seq = 0;
                for (AgentEvent event : events.toIterable()) {
                    Map<String, Object> dto = eventMapper.toDto(event);
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(++seq))
                            .name("agent")
                            .data(dto, MediaType.APPLICATION_JSON));
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

    /** 对外只暴露简短、无内部细节的错误文案（规约 §3）。 */
    private String safeMessage(RuntimeException error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }
}
