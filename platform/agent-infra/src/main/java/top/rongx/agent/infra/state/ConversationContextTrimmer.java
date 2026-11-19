package top.rongx.agent.infra.state;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>被裁掉的旧历史不会静默消失：折叠成<b>一条</b> synthetic 摘要消息，保留角色、
 * 工具名和 runId/changeId/sha 等关键标识。这样长会话追问"刚才那个 run 为什么失败"时，
 * 模型仍能定位到证据，不必重新取证（重新取证才是真正的 token 大头）。
 *
 * <p>这是"上下文精益"的最后一道闸：工具输出还有更早的一层截断（{@code agent.tools.max-output-chars}），
 * 两层配合后，长会话的成本与延迟都是可控的。
 */
final class ConversationContextTrimmer {

    /** synthetic 消息的种类标记：与 {@link Msg#METADATA_REMINDER_KIND} 配套使用。 */
    static final String COMPACTED_HISTORY = "compacted_history";

    /** 摘要默认预算：与 agent.context.summary-chars 的默认值保持一致。 */
    private static final int DEFAULT_SUMMARY_CHARS = 4_000;

    /** 单行正文最多保留的字符数：够看清意图，又不至于把摘要撑爆。 */
    private static final int LINE_CHARS = 180;

    /** 工具结果里值得跨轮保留的标识：runId/changeId/sha/traceId 等。 */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)(runId|run_id|changeId|change_id|sha|traceId)\\s*[=:]\\s*([A-Za-z0-9_-]{4,})");

    /** 摘要头部单独汇总的关键标识数量上限。 */
    private static final int MAX_KEY_IDS = 12;

    private ConversationContextTrimmer() {
    }

    static AgentState trim(AgentState state, int maxChars, int keepMessages) {
        return trim(state, maxChars, keepMessages, DEFAULT_SUMMARY_CHARS);
    }

    // @handbook state-context-trimmer/trim | 上下文预算裁剪
    static AgentState trim(AgentState state, int maxChars, int keepMessages, int summaryChars) {
        if (state == null || maxChars <= 0) {
            return state;
        }
        List<Msg> context = state.getContext();
        if (context == null || context.isEmpty()) {
            return state;
        }
        int budget = Math.max(1_000, maxChars);
        // KV cache 友好：留 25% 余量才触发，一次裁到 60%，避免每条消息都改写历史前缀。
        // 每裁一次模型侧的前缀缓存都会失效；高频小裁比"少裁但裁得深"贵得多。
        int trigger = budget + budget / 4;
        int target = Math.max(1_000, budget * 3 / 5);
        int keep = Math.max(1, keepMessages);
        if (totalChars(context) <= trigger && context.size() <= keep) {
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
        if (totalChars(rest) <= trigger && rest.size() <= keep) {
            return state;
        }

        int start = Math.max(0, rest.size() - keep);
        long chars = totalChars(rest.subList(start, rest.size()));
        // 既要把总量压到预算内，又不能让窗口以 TOOL 开头（必须是新的 USER 轮次或 ASSISTANT 回复）
        while (start < rest.size() - 1) {
            boolean badStart = rest.get(start).getRole() == MsgRole.TOOL;
            if (!badStart && chars <= target) {
                break;
            }
            chars -= charsOf(rest.get(start));
            start++;
        }
        if (start <= 0) {
            return state;
        }

        List<Msg> kept = new ArrayList<>(system);
        if (summaryChars > 0) {
            String summary = compactSummary(rest.subList(0, start), summaryChars);
            if (!summary.isBlank()) {
                kept.add(compactedMessage(summary));
            }
        }
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

    /** 把被裁掉的旧消息折叠成确定性摘要：不调模型，只做提取与截断。 */
    private static String compactSummary(List<Msg> dropped, int summaryChars) {
        // 上一次裁剪留下的摘要行必须排在最前，保证"更早的事实在前、最近的事实在后"的顺序。
        Deque<String> earlier = new ArrayDeque<>();
        List<String> lines = new ArrayList<>();
        // 关键标识单独汇总：它们最值钱，不能因为摘要预算不足被丢掉。
        Deque<String> keyIds = new ArrayDeque<>();
        for (Msg message : dropped) {
            if (message == null) {
                continue;
            }
            String text = textOf(message);
            collectIdentifiers(text, keyIds);
            if (isCompacted(message)) {
                for (String line : message.getTextContent().split("\n")) {
                    if (line.startsWith("- ")) {
                        earlier.addLast(line);
                    }
                }
                continue;
            }
            String line = lineOf(message);
            if (line != null) {
                lines.add(line);
            }
        }
        List<String> all = new ArrayList<>();
        all.addAll(earlier);
        all.addAll(lines);
        if (all.isEmpty() && keyIds.isEmpty()) {
            return "";
        }
        String header = "<compacted-history>\n"
                + "Earlier turns were compacted. Keep these facts; re-query only if details "
                + "are needed.\n";
        String idsLine = keyIds.isEmpty() ? "" : "Key IDs: " + String.join(", ", keyIds) + "\n";
        String footer = "</compacted-history>";
        int budget = summaryChars - header.length() - idsLine.length() - footer.length();
        if (budget <= 0) {
            // 预算小到连关键标识都放不下：退回直接丢弃，避免"摘要比原文还长"。
            return "";
        }
        // 超预算时丢最早的行，保留最近的事实。
        Deque<String> kept = new ArrayDeque<>();
        int used = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            String line = all.get(i);
            if (used + line.length() + 1 > budget) {
                break;
            }
            kept.addFirst(line);
            used += line.length() + 1;
        }
        if (kept.isEmpty() && idsLine.isEmpty()) {
            return "";
        }
        return header + idsLine
                + (kept.isEmpty() ? "" : String.join("\n", kept) + "\n")
                + footer;
    }

    /** 关键标识最多保留 12 个，且只留最近的（先到先出）。 */
    private static void collectIdentifiers(String text, Deque<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String id = "{" + matcher.group(1) + "=" + matcher.group(2) + "}";
            if (out.contains(id)) {
                continue;
            }
            if (out.size() >= MAX_KEY_IDS) {
                out.removeFirst();
            }
            out.addLast(id);
        }
    }

    private static String lineOf(Msg message) {
        String text = oneLine(textOf(message));
        if (message.getRole() == MsgRole.TOOL) {
            String name = toolName(message);
            String body = clip(text, LINE_CHARS)
                    + (text.length() > LINE_CHARS ? " …(" + (text.length() - LINE_CHARS)
                    + " chars omitted)" : "");
            return "- [tool" + (name == null || name.isBlank() ? "" : ":" + name) + "] "
                    + body;
        }
        if (text.isBlank()) {
            return null;
        }
        return "- [" + message.getRole().name().toLowerCase() + "] "
                + clip(text, LINE_CHARS);
    }

    private static boolean isCompacted(Msg message) {
        Object kind = message.getMetadata() == null
                ? null : message.getMetadata().get(Msg.METADATA_REMINDER_KIND);
        return COMPACTED_HISTORY.equals(kind);
    }

    private static Msg compactedMessage(String summary) {
        return Msg.builderForRole(MsgRole.USER)
                .name("compaction")
                .textContent(summary)
                .metadata(Map.of(
                        Msg.METADATA_SYNTHETIC, true,
                        Msg.METADATA_REMINDER_KIND, COMPACTED_HISTORY))
                .build();
    }

    private static String toolName(Msg message) {
        ToolResultBlock block = message.getFirstContentBlock(ToolResultBlock.class);
        return block == null ? null : block.getName();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static long totalChars(List<Msg> messages) {
        long total = 0;
        for (Msg message : messages) {
            total += charsOf(message);
        }
        return total;
    }

    private static long charsOf(Msg message) {
        return textOf(message).length();
    }

    /**
     * 消息的可见文本：TOOL 消息的正文在 {@link ToolResultBlock#getOutput()} 里，
     * {@link Msg#getTextContent()} 取不到。不递归统计会让工具输出完全不计入上下文预算，
     * 而这恰恰是长会话 token 的大头。
     */
    private static String textOf(Msg message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String text = message.getTextContent();
        if (text != null && !text.isEmpty()) {
            sb.append(text);
        }
        for (ToolResultBlock block : message.getContentBlocks(ToolResultBlock.class)) {
            for (ContentBlock part : block.getOutput()) {
                if (part instanceof TextBlock t && t.getText() != null) {
                    sb.append(t.getText());
                }
            }
        }
        return sb.toString();
    }
}
