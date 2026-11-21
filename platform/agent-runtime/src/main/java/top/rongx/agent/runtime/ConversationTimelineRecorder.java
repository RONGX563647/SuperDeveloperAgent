package top.rongx.agent.runtime;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.process.LogTail;

/**
 * 服务端会话时间线记录器：把一轮对话真实发生的事件落盘。
 *
 * <p>为什么不信任前端上传的 transcript：那是渲染镜像，断线、换端、多端并发都会互相
 * 覆盖。这里只追加，记录「服务端确实发生过什么」，供重启回放与游标续读
 * （{@code GET /workspace/conversations/{id}/timeline}）。
 *
 * <p>写入失败只记日志：时间线是旁路能力，不能影响对话本身。
 */
@Service
public class ConversationTimelineRecorder {

    private static final Logger log = LoggerFactory.getLogger(ConversationTimelineRecorder.class);

    /** 单条正文上限：再长的回答也不该把存储撑爆。 */
    private static final int MAX_TEXT = 8_000;
    /** 工具结果上限：判断价值看头尾即可。 */
    private static final int MAX_TOOL_TEXT = 2_000;

    private final ConversationTimelineRepository repository;

    public ConversationTimelineRecorder(ConversationTimelineRepository repository) {
        this.repository = repository;
    }

    /** 为一轮对话开启记录器（每个请求一个）。 */
    public Recorder begin(String conversationId) {
        return new Recorder(conversationId);
    }

    /** 单轮对话的事件收集器；{@link #finish(String)} 幂等。 */
    public final class Recorder {

        private final String conversationId;
        private final StringBuilder reply = new StringBuilder();
        private final Map<String, String> toolNames = new HashMap<>();
        private final Map<String, StringBuilder> outputs = new HashMap<>();
        private final AtomicBoolean done = new AtomicBoolean();

        private Recorder(String conversationId) {
            this.conversationId = conversationId;
        }

        /** 用户输入（在模型调用前写入，保证崩溃时也留得下）。 */
        public void user(String text) {
            append("user", "user", text, null, null);
        }

        /** 流式事件：正文增量合并成一条，工具调用/结果各记一条。 */
        public synchronized void onEvent(AgentEvent event) {
            if (done.get() || conversationId == null || conversationId.isBlank()) {
                return;
            }
            if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                if (reply.length() < MAX_TEXT) {
                    reply.append(delta.getDelta());
                }
            } else if (event instanceof ToolCallStartEvent call && call.getToolCallId() != null) {
                toolNames.put(call.getToolCallId(), call.getToolCallName());
                append("tool_call", "assistant", null, call.getToolCallName(), call.getToolCallId());
            } else if (event instanceof ToolResultTextDeltaEvent delta
                    && delta.getToolCallId() != null && delta.getDelta() != null) {
                StringBuilder buffer = outputs.computeIfAbsent(
                        delta.getToolCallId(), key -> new StringBuilder());
                if (buffer.length() < MAX_TOOL_TEXT) {
                    buffer.append(delta.getDelta());
                }
            } else if (event instanceof ToolResultEndEvent end) {
                String name = end.getToolCallName() != null
                        ? end.getToolCallName() : toolNames.get(end.getToolCallId());
                StringBuilder buffer = outputs.remove(end.getToolCallId());
                append("tool_result", "tool",
                        truncate(buffer == null ? "" : buffer.toString(), MAX_TOOL_TEXT),
                        name, end.getToolCallId());
            }
        }

        /** 收尾：落回复正文；{@code error} 非空时额外记一条中断说明。 */
        public synchronized void finish(String error) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (error != null && !error.isBlank()) {
                append("assistant", "assistant", "运行中断：" + truncate(error, 500), null, null);
            }
            String text = reply.toString().trim();
            if (!text.isEmpty()) {
                append("assistant", "assistant", truncate(text, MAX_TEXT), null, null);
            }
            reply.setLength(0);
        }

        private void append(String kind, String role, String text, String tool, String toolCallId) {
            if (conversationId == null || conversationId.isBlank()) {
                return;
            }
            try {
                repository.append(conversationId, kind, role, text, tool, toolCallId);
            } catch (RuntimeException e) {
                log.warn("时间线写入失败 session={} kind={}：{}", conversationId, kind, e.getMessage());
            }
        }
    }

    private static String truncate(String text, int max) {
        return text == null ? null : LogTail.prefix(text, max, "…(截断)");
    }
}
