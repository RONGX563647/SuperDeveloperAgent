package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.workspace.WorkspaceEventBus;

/**
 * 工作区聚合事件流：把本实例内所有会话运行的状态/文本增量推给工作区首页。
 *
 * <p>只推当前用户自己的事件；状态真相仍在 SQLite，前端掉线后重新拉 REST 即可。
 */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceEventController {

    private static final long TIMEOUT_MS = 600_000L;

    private final WorkspaceEventBus bus;
    private final CurrentUser currentUser;

    public WorkspaceEventController(WorkspaceEventBus bus, CurrentUser currentUser) {
        this.bus = bus;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/workspaces/{projectId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String projectId,
                             @RequestParam(required = false) String conversationId,
                             @RequestParam(required = false) String runId) {
        String userId = currentUser.requireUserId();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        reactor.core.Disposable subscription = bus.subscribe(projectId)
                .filter(event -> event.ownerUserId().equals(userId))
                .filter(event -> conversationId == null || conversationId.isBlank()
                        || conversationId.equals(event.conversationId()))
                .filter(event -> runId == null || runId.isBlank() || runId.equals(event.runId()))
                .subscribe(event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("workspace")
                                .data(Map.of("type", event.type(),
                                        "conversationId", event.conversationId(),
                                        "runId", event.runId(),
                                        "status", event.status(),
                                        "text", event.text(),
                                        "at", event.at()),
                                        MediaType.APPLICATION_JSON));
                    } catch (IOException | IllegalStateException gone) {
                        emitter.complete();
                    }
                }, error -> {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", "事件流异常"),
                                        MediaType.APPLICATION_JSON));
                    } catch (IOException | IllegalStateException ignored) {
                        // 客户端已断开
                    }
                    emitter.complete();
                });
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                subscription.dispose();
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(error -> close.run());
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException gone) {
            close.run();
            emitter.complete();
        }
        return emitter;
    }
}
