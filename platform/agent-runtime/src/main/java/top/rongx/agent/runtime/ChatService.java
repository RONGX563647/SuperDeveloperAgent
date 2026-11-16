package top.rongx.agent.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.UUID;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;
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
    private final ChatSessionRegistry sessions;
    private final DynamicModelProvider modelProvider;
    private final TokenUsageRecorder tokenUsage;

    public ChatService(AgentFactory agentFactory, ChatSessionRegistry sessions,
                       DynamicModelProvider modelProvider, TokenUsageRecorder tokenUsage) {
        this.agentFactory = agentFactory;
        this.sessions = sessions;
        this.modelProvider = modelProvider;
        this.tokenUsage = tokenUsage;
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
        return stream(ctx, sessionId, text, role, skills, mcpServers, null, null);
    }

    /**
     * 发起一轮对话，并使用会话显式选择的模型连接 / 模型名。
     *
     * @param modelConnectionId 模型连接 id；null = 设置页默认连接
     * @param modelName         模型名；null = 该连接配置里的模型名
     */
    public Flux<AgentEvent> stream(ProjectContext ctx, String sessionId, String text, String role,
                                   List<String> skills, List<String> mcpServers,
                                   String modelConnectionId, String modelName) {
        // 1. 空消息直接报错（不进入 Agent）
        if (text == null || text.isBlank()) {
            return Flux.error(new IllegalArgumentException("消息不能为空"));
        }
        // 2. 规范化会话 ID（缺失则生成），并校验它没有被绑定到别的项目/环境
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        sessions.bindOrVerify(sid, ctx);
        // 2.1 解析实际生效模型：非法选择在这里直接失败，不回退到默认模型
        ActiveModel active = modelProvider.activeConfig(modelConnectionId, modelName);
        // 3. 按项目上下文构造 Agent（角色决定工具面与系统提示；能力按本会话选择挂载）
        AgentFactory.AgentSession session = "deploy".equalsIgnoreCase(role)
                ? new AgentFactory.AgentSession(
                        agentFactory.createDeploy(ctx, modelConnectionId, modelName), List.of())
                : agentFactory.createChat(ctx, skills, mcpServers, modelConnectionId, modelName);
        // 4. 显式传递运行时上下文（含项目上下文，非 ThreadLocal）
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId(sid)
                .put(ProjectContext.class, ctx)
                .build();
        // 5. 返回事件流；每次模型调用结束都记录 token；结束后关闭本会话的 MCP 客户端
        return session.agent().streamEvents(text, runtime)
                .doOnNext(event -> recordUsage(event, sid, ctx, active))
                .doFinally(signal -> session.close());
    }

    /** 每次模型调用结束都记一条 token 用量（会话累计 + 独立日志）。 */
    private void recordUsage(AgentEvent event, String sessionId, ProjectContext ctx,
                             ActiveModel model) {
        if (event instanceof ModelCallEndEvent end) {
            tokenUsage.record(sessionId, ctx, model, end.getUsage());
        }
    }

    /**
     * 工作区调度会话：只装配管理工具，用于创建 / 派发 / 跟踪 / 汇总工作区内的会话。
     *
     * <p>与会话正文一样按 {@code sessionId} 保存上下文（调度会话也有连续对话需求）。
     */
    public Flux<AgentEvent> streamSupervisor(ProjectContext ctx, String sessionId, String text) {
        return streamSupervisor(ctx, sessionId, text, null, null);
    }

    /** 工作区调度会话：同样支持按会话选择的模型，并记录 token。 */
    public Flux<AgentEvent> streamSupervisor(ProjectContext ctx, String sessionId, String text,
                                             String modelConnectionId, String modelName) {
        if (text == null || text.isBlank()) {
            return Flux.error(new IllegalArgumentException("消息不能为空"));
        }
        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString() : sessionId;
        sessions.bindOrVerify(sid, ctx);
        ActiveModel active = modelProvider.activeConfig(modelConnectionId, modelName);
        ReActAgent agent = agentFactory.createSupervisor(ctx, modelConnectionId, modelName);
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId(sid)
                .put(ProjectContext.class, ctx)
                .build();
        return agent.streamEvents(text, runtime)
                .doOnNext(event -> recordUsage(event, sid, ctx, active));
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
        sessions.bindOrVerify(sid, ctx);
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
