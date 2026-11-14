package top.rongx.agent.runtime.workspace;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 工作区事件总线（单实例内存实现）。
 *
 * <p>工作区首页通过 SSE 订阅"某个会话/运行"的状态变化；运行状态本身仍然以 SQLite 为准，
 * 这里只负责把实时变化推给已连接的浏览器。多实例部署时各实例只推送本实例产生的运行。
 */
@Component
public class WorkspaceEventBus {

    /**
     * 一条工作区事件。
     *
     * @param type text.delta / run.status / conversation.status
     */
    public record WorkspaceEvent(String projectId, String conversationId, String runId,
                                 String ownerUserId, String type, String status, String text,
                                 long at) {
    }

    private final Map<String, Sinks.Many<WorkspaceEvent>> sinks = new ConcurrentHashMap<>();

    /** 发布事件；没有订阅者时直接丢弃（状态仍可从 REST 查询）。 */
    public void publish(WorkspaceEvent event) {
        Sinks.Many<WorkspaceEvent> sink = sinks.get(event.projectId());
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    /** 订阅某个项目的全部事件；调用方按 conversationId / runId 自行过滤。 */
    public Flux<WorkspaceEvent> subscribe(String projectId) {
        return sinks.computeIfAbsent(projectId,
                ignored -> Sinks.many().multicast().onBackpressureBuffer()).asFlux();
    }
}
