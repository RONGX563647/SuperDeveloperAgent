package top.rongx.agent.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.UUID;
import top.rongx.agent.domain.project.ProjectContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
        return stream(ctx, sessionId, text, "chat");
    }

    /**
     * 发起一轮对话（可指定 Agent 角色）；能力按设置页默认启用项挂载。
     *
     * @param role {@code chat}=通用助手；{@code deploy}=部署 Agent（专管环境跑通与固化）
     */
    public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text, String role) {
        return stream(ctx, sessionId, text, role, null, null);
    }

    /**
     * 发起一轮对话，按本会话显式勾选的 Skill / MCP 挂载能力。
     *
     * @param skills     技能目录名；null 表示按设置页默认，空列表表示本会话全部关闭
     * @param mcpServers MCP 服务名；null 表示按设置页默认，空列表表示本会话全部关闭
     */
    public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text, String role,
                                   List<String> skills, List<String> mcpServers) {
        // 1. 空消息直接报错（不进入 Agent）
        if (text == null || text.isBlank()) {
            return Flux.error(new IllegalArgumentException("消息不能为空"));
        }
        // 2. 规范化会话 ID（缺失则生成）
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        // 3. 按项目上下文构造 Agent（角色决定工具面与系统提示；能力按本会话选择挂载）
        AgentFactory.AgentSession session = "deploy".equalsIgnoreCase(role)
                ? new AgentFactory.AgentSession(agentFactory.createDeploy(ctx), List.of())
                : agentFactory.createChat(ctx, skills, mcpServers);
        // 4. 显式传递运行时上下文（含项目上下文，非 ThreadLocal）
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId(sid)
                .put(ProjectContext.class, ctx)
                .build();
        // 5. 返回事件流；结束后关闭本会话创建的 MCP 客户端，避免进程/连接泄漏
        return session.agent().streamEvents(text, runtime)
                .doFinally(signal -> session.close());
    }

    /**
     * 带 JSON Schema 约束的一次调用：模型必须按 {@code structuredType} 的 schema 产出结果。
     *
     * <p>与提示词里写「请输出 JSON」不同，结构化输出由 AgentScope 生成 schema（并在模型支持时
     * 走原生 response_format / tool calling），解析失败由框架直接报错，而不是靠正则猜 JSON。
     * 返回的 {@link Msg} 里取 {@code getStructuredData(structuredType)} 即为强类型结果。
     *
     * @param ctx            服务端绑定的项目上下文
     * @param sessionId      会话标识；为空时生成
     * @param text           用户输入
     * @param structuredType 结构化结果类型（record/POJO 均可）
     */
    public <T> Mono<Msg> structured(ProjectContext ctx, String sessionId, String text,
                                    Class<T> structuredType) {
        if (text == null || text.isBlank()) {
            return Mono.error(new IllegalArgumentException("消息不能为空"));
        }
        if (structuredType == null) {
            return Mono.error(new IllegalArgumentException("结构化输出类型不能为空"));
        }
        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString() : sessionId;
        AgentFactory.AgentSession session = agentFactory.createChat(ctx, null, null);
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId(sid)
                .put(ProjectContext.class, ctx)
                .build();
        return session.agent().call(text, structuredType, runtime)
                .doFinally(signal -> session.close());
    }
}
