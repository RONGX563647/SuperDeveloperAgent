package top.rongx.agent.gateway.bugfix;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 运行取消登记表：编排器标记取消，Agent 适配器在每个事件回调处协作检查。
 *
 * <p>取消不能只依赖「阶段结束再丢弃结果」：一次 Agent 调用可能长达 15 分钟，
 * 用户取消后必须尽快停止流式输出与后续工具调用。
 */
@Component
public class BugFixCancellationRegistry {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public void markCancelled(String runId) {
        if (runId != null && !runId.isBlank()) {
            cancelled.add(runId);
        }
    }

    public void clear(String runId) {
        if (runId != null) {
            cancelled.remove(runId);
        }
    }

    public boolean cancelled(String runId) {
        return runId != null && cancelled.contains(runId);
    }
}
