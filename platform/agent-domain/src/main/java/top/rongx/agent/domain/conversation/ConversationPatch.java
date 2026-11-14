package top.rongx.agent.domain.conversation;

/**
 * 会话的局部更新（null = 不改）。
 *
 * @param title    新标题
 * @param env      新环境
 * @param worktree 新工作树（空字符串表示主仓库）
 * @param summary  最近一次运行的结论摘要
 * @param status   新状态
 * @param pinned   是否置顶
 * @param archived 是否归档
 */
public record ConversationPatch(String title, String env, String worktree, String summary,
                                ConversationStatus status, Boolean pinned, Boolean archived) {

    public static ConversationPatch status(ConversationStatus status) {
        return new ConversationPatch(null, null, null, null, status, null, null);
    }

    public static ConversationPatch title(String title) {
        return new ConversationPatch(title, null, null, null, null, null, null);
    }

    public static ConversationPatch pin(boolean pinned) {
        return new ConversationPatch(null, null, null, null, null, pinned, null);
    }

    public static ConversationPatch archive(boolean archived) {
        return new ConversationPatch(null, null, null, null, null, null, archived);
    }
}
