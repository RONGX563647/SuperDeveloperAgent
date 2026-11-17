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
 *   <li>核心工具始终可见；插件工具（含未归类的 other / MCP）需要 {@code tool_load} 激活；</li>
 *   <li>激活标记写进会话状态（重启后仍然有效）；</li>
 *   <li>首轮按用户输入关键词自动预加载，<b>只在会话首轮做一次</b>且最多 2 个插件——
 *       工具集一旦定型就保持不变，模型侧 KV 前缀缓存才能持续命中；</li>
 *   <li>过滤保持 Toolkit 原有顺序，插件不变时工具列表完全一致。</li>
 * </ul>
 *
 * <p>禁止「每轮按需增删」：那会让 tools 段在 prompt 最前面抖动，不只丢掉 tools 的缓存，
 * 连后续 messages 的前缀缓存一起失效。
 */
public class ProgressiveToolMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ProgressiveToolMiddleware.class);

    /** 首轮路由只命中一次的信号；写进会话状态，避免每轮重新判定意图。 */
    static final String ROUTED_MARKER = "router:done";

    /** 首轮自动加载的插件上限：超过之后交给 tool_load，保持工具面稳定。 */
    private static final int MAX_AUTO_PLUGINS = 2;

    private final ToolPluginIndex index;
    private final boolean autoActivate;

    /**
     * @param autoActivate 自动预加载开关：{@code session}（默认，首轮一次）/ {@code off}
     */
    public ProgressiveToolMiddleware(ToolPluginIndex index, String autoActivate) {
        this.index = index;
        this.autoActivate = autoActivate == null
                || !("off".equalsIgnoreCase(autoActivate.trim())
                || "false".equalsIgnoreCase(autoActivate.trim()));
    }

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
        if (autoActivate && !active.contains(ROUTED_MARKER)) {
            Set<String> routed = limit(index.route(lastUserText(input.messages())));
            active.addAll(routed);
            active.add(ROUTED_MARKER);
            if (state != null) {
                state.getToolContext().setActivatedGroups(new ArrayList<>(active));
                if (!routed.isEmpty()) {
                    log.debug("首轮按用户意图预加载工具插件：{}", routed);
                }
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

    /** 按插件目录顺序保留命中的前 {@link #MAX_AUTO_PLUGINS} 个，保证结果可预测。 */
    private Set<String> limit(Set<String> routed) {
        Set<String> kept = new LinkedHashSet<>();
        if (routed.isEmpty()) {
            return kept;
        }
        for (ToolPluginIndex.Plugin plugin : index.plugins()) {
            if (kept.size() >= MAX_AUTO_PLUGINS) {
                break;
            }
            if (routed.contains(plugin.name())) {
                kept.add(plugin.name());
            }
        }
        return kept;
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
