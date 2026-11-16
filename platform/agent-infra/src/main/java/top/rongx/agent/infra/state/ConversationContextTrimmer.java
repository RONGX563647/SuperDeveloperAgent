package top.rongx.agent.infra.state;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文预算：落盘前裁掉最早的历史，保证下一轮请求的上下文不会无限膨胀。
 *
 * <p>约束（裁剪不能破坏 Agent 的可执行性）：
 * <ul>
 *   <li>开头的 SYSTEM 消息（系统提示）永远保留，且不占预算；</li>
 *   <li>裁剪窗口不能以 TOOL 消息开头——那会留下没有对应 tool_use 的工具结果；</li>
 *   <li>至少保留最近 {@code keepMessages} 条，除非单条就超过 {@code maxChars}；</li>
 *   <li>不修改传入对象（运行中的 Agent 仍在用同一实例），而是构造一个新的状态。</li>
 * </ul>
 *
 * <p>这是"上下文精益"的最后一道闸：工具输出还有更早的一层截断（{@code agent.tools.max-output-chars}），
 * 两层配合后，长会话的成本与延迟都是可控的。
 */
final class ConversationContextTrimmer {

    private ConversationContextTrimmer() {
    }

    // @handbook state-context-trimmer/trim | 上下文预算裁剪
    static AgentState trim(AgentState state, int maxChars, int keepMessages) {
        if (state == null || maxChars <= 0) {
            return state;
        }
        List<Msg> context = state.getContext();
        if (context == null || context.isEmpty()) {
            return state;
        }
        int budget = Math.max(1_000, maxChars);
        int keep = Math.max(1, keepMessages);
        if (totalChars(context) <= budget && context.size() <= keep) {
            return state;
        }

        int head = 0;
        while (head < context.size() && context.get(head).getRole() == MsgRole.SYSTEM) {
            head++;
        }
        List<Msg> system = context.subList(0, head);
        List<Msg> rest = context.subList(head, context.size());
        if (rest.isEmpty()) {
            return state;
        }
        if (totalChars(rest) <= budget && rest.size() <= keep) {
            return state;
        }

        int start = Math.max(0, rest.size() - keep);
        long chars = totalChars(rest.subList(start, rest.size()));
        // 既要把总量压到预算内，又不能让窗口以 TOOL 开头（必须是新的 USER 轮次或 ASSISTANT 回复）
        while (start < rest.size() - 1) {
            boolean badStart = rest.get(start).getRole() == MsgRole.TOOL;
            if (!badStart && chars <= budget) {
                break;
            }
            chars -= charsOf(rest.get(start));
            start++;
        }
        if (start <= 0) {
            return state;
        }

        List<Msg> kept = new ArrayList<>(system);
        kept.addAll(rest.subList(start, rest.size()));
        return AgentState.builder()
                .sessionId(state.getSessionId())
                .userId(state.getUserId())
                .summary(state.getSummary())
                .context(kept)
                .replyId(state.getReplyId())
                .curIter(state.getCurIter())
                .shutdownInterrupted(state.isShutdownInterrupted())
                .permissionContext(state.getPermissionContext())
                .toolContext(state.getToolContext())
                .tasksContext(state.getTasksContext())
                .planModeContext(state.getPlanModeContext())
                .build();
    }

    private static long totalChars(List<Msg> messages) {
        long total = 0;
        for (Msg message : messages) {
            total += charsOf(message);
        }
        return total;
    }

    private static long charsOf(Msg message) {
        if (message == null) {
            return 0;
        }
        String text = message.getTextContent();
        return text == null ? 0 : text.length();
    }
}
