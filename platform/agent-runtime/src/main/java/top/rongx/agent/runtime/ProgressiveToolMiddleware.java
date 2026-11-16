package top.rongx.agent.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.tool.ToolPluginIndex;

/**
 * 渐进式工具披露：每次模型调用前，只把「核心工具 + 已激活插件」的 schema 交给模型。
 *
 * <p>工具仍然全部注册在 Toolkit 里（调用链不受影响），这里只裁剪<b>发给模型的 schema</b>：
 * <ul>
 *   <li>核心工具与未归类工具（MCP / 技能工具）始终可见，避免能力无声消失；</li>
 *   <li>插件工具需要 {@code tool_load} 激活，激活标记写进会话状态（重启后仍然有效）；</li>
 *   <li>首轮按用户输入关键词自动预加载相关插件，减少"模型忘了加载"的失败；</li>
 *   <li>过滤保持 Toolkit 原有顺序，插件不变时工具列表完全一致，模型侧的 KV 前缀缓存才能命中。</li>
 * </ul>
 */
public class ProgressiveToolMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ProgressiveToolMiddleware.class);

    private final ToolPluginIndex index;
    private final boolean autoActivate;

    public ProgressiveToolMiddleware(ToolPluginIndex index, boolean autoActivate) {
        this.index = index;
        this.autoActivate = autoActivate;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<ToolSchema> tools = input.tools();
        if (index.plugins().isEmpty() || tools == null || tools.isEmpty()) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        Set<String> active = new LinkedHashSet<>();
        if (state != null) {
            active.addAll(state.getToolContext().getActivatedGroups());
        }
        if (autoActivate) {
            Set<String> routed = index.route(lastUserText(input.messages()));
            if (!routed.isEmpty() && active.addAll(routed) && state != null) {
                state.getToolContext().setActivatedGroups(new ArrayList<>(active));
                log.debug("按用户意图预加载工具插件：{}", routed);
            }
        }
        List<ToolSchema> visible = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            if (index.visible(tool.getName(), active)) {
                visible.add(tool);
            }
        }
        if (visible.size() == tools.size()) {
            return next.apply(input);
        }
        return next.apply(new ReasoningInput(input.messages(), visible, input.options()));
    }

    /** 取最近一条用户消息的正文（工具迭代期间最后一条通常是 TOOL/ASSISTANT）。 */
    private static String lastUserText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() == MsgRole.USER) {
                String text = message.getTextContent();
                return text == null ? "" : text;
            }
        }
        return "";
    }
}
