package top.rongx.agent.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 把 {@code ask_user} 工具调用翻译成 {@code ask.requested} 事件。
 *
 * <p>工具入参只有执行那一刻才知道，前端却需要在工具返回前就渲染选项卡片；
 * 因此在这里（onActing）先读入参并注入一条 {@link CustomEvent}，
 * 再由网关的 {@code AgentEventMapper} 换成稳定 wire type。工具本身仍会执行，
 * 它的返回值只是提示模型「结束本轮」，不参与渲染。
 */
public class AskUserMiddleware implements MiddlewareBase {

    /** 工具名（与 {@code AskUserTools} 的 @Tool 保持一致）。 */
    static final String TOOL_NAME = "ask_user";
    /** 自定义事件名；网关据此映射成 {@code ask.requested}。 */
    static final String EVENT_NAME = "ask_user";

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null) {
            return next.apply(input);
        }
        List<AgentEvent> asks = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (call == null || !TOOL_NAME.equals(call.getName())) {
                continue;
            }
            Map<String, Object> payload = askPayload(call.getInput());
            if (payload != null) {
                asks.add(new CustomEvent(EVENT_NAME, payload));
            }
        }
        Flux<AgentEvent> rest = next.apply(input);
        if (asks.isEmpty()) {
            return rest;
        }
        // 卡片先于工具执行事件到达，前端不必等工具返回才知道要问什么
        return Flux.concat(Flux.fromIterable(asks), rest);
    }

    /**
     * 归一化工具入参：question 必填，options 统一成 {@code [{label, detail}]}，
     * multiple / allowText 只在为真时出现（前端按缺省处理）。
     */
    static Map<String, Object> askPayload(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object rawQuestion = input.get("question");
        String question = rawQuestion == null ? "" : String.valueOf(rawQuestion).trim();
        if (question.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> options = normalizeOptions(input.get("options"));
        if (options.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("options", options);
        if (Boolean.TRUE.equals(input.get("multiple"))) {
            payload.put("multiple", true);
        }
        // 默认允许自由输入：选项覆盖不到的答案不应该逼用户二选一
        if (!Boolean.FALSE.equals(input.get("allow_text"))) {
            payload.put("allowText", true);
        }
        return payload;
    }

    private static List<Map<String, Object>> normalizeOptions(Object raw) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return options;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object label = map.get("label") == null ? map.get("text") : map.get("label");
                String text = label == null ? "" : String.valueOf(label).trim();
                if (text.isEmpty()) {
                    continue;
                }
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("label", text);
                Object detail = map.get("detail") == null ? map.get("description") : map.get("detail");
                if (detail != null && !String.valueOf(detail).isBlank()) {
                    option.put("detail", String.valueOf(detail).trim());
                }
                options.add(option);
                continue;
            }
            String text = item == null ? "" : String.valueOf(item).trim();
            if (!text.isEmpty()) {
                options.add(Map.of("label", text));
            }
        }
        return options;
    }
}
