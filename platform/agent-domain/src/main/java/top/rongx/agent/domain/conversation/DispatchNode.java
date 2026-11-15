package top.rongx.agent.domain.conversation;

import java.util.List;

/**
 * DAG 调度中的一个节点：一次子会话运行。
 *
 * <p>{@code dependsOn} 里的节点全部成功后才派发；任一依赖失败时本节点标记为 SKIPPED，
 * 不再消耗运行配额。{@code required=false} 的节点在"必需节点全部成功"后可以被提前终止。
 *
 * <p>{@code conversationId} 为空表示由调度器新建会话；非空则复用已有普通会话。
 * {@code timeoutSeconds} 是单节点等待上限（含多次 wait 续等），超时会取消该运行。
 */
public record DispatchNode(
        String id,
        String conversationId,
        String title,
        String env,
        String worktree,
        String message,
        List<String> dependsOn,
        boolean required,
        int timeoutSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int MAX_TIMEOUT_SECONDS = 300;

    public DispatchNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("节点 id 不能为空");
        }
        id = id.trim();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("节点 " + id + " 的 message 不能为空");
        }
        dependsOn = dependsOn == null ? List.of()
                : dependsOn.stream().filter(d -> d != null && !d.isBlank())
                .map(String::trim).distinct().toList();
        if (dependsOn.contains(id)) {
            throw new IllegalArgumentException("节点 " + id + " 不能依赖自己");
        }
        conversationId = blankToNull(conversationId);
        title = blankToNull(title);
        env = blankToNull(env);
        worktree = blankToNull(worktree);
        timeoutSeconds = timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
