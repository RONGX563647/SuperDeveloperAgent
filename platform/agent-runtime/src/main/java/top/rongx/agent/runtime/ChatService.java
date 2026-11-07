package top.rongx.agent.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import java.util.UUID;
import top.rongx.agent.domain.project.ProjectContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对话服务：把一次用户输入变成事件流。
 *
 * <p><b>线程说明</b>：本设计不依赖任何 ThreadLocal —— 工具所需的项目上下文经
 * {@link RuntimeContext} 显式传递（规约 §1.1 A2），因此事件流可以安全地在工作线程消费。
 */
@Service
public class ChatService {

    private final AgentFactory agentFactory;

    public ChatService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * 发起一轮对话。
     *
     * @param ctx       服务端绑定的项目上下文（不来自模型）
     * @param sessionId 会话标识
     * @param text      用户输入
     * @return 事件流
     */
    public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text) {
        // 1. 空消息直接报错（不进入 Agent）
        if (text == null || text.isBlank()) {
            return Flux.error(new IllegalArgumentException("消息不能为空"));
        }
        // 2. 规范化会话 ID（缺失则生成）
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        // 3. 按项目上下文构造无状态 Agent
        ReActAgent agent = agentFactory.create(ctx);
        // 4. 显式传递运行时上下文（含项目上下文，非 ThreadLocal）
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId(sid)
                .put(ProjectContext.class, ctx)
                .build();
        // 5. 返回事件流
        return agent.streamEvents(text, runtime);
    }
}
