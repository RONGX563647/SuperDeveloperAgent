package top.rongx.agent.domain.conversation;

/**
 * 一个会话（工作区 = 项目，项目下可以有很多会话）。
 *
 * <p>会话元数据服务端持久化；消息正文仍由 AgentScope 的加密状态存储按
 * {@code (userId, sessionId)} 保存，这里只放"列表、状态、调度"需要的最小字段。
 */
public record Conversation(
        String id,
        String projectId,
        String ownerUserId,
        String title,
        ConversationKind kind,
        String role,
        String env,
        String worktree,
        ConversationStatus status,
        String summary,
        boolean pinned,
        boolean archived,
        String parentConversationId,
        long lastActivityAt,
        long createdAt,
        String modelConnectionId,
        String modelName) {

    /** 兼容旧调用（未选择模型 = 使用默认连接）。 */
    public Conversation(String id, String projectId, String ownerUserId, String title,
                        ConversationKind kind, String role, String env, String worktree,
                        ConversationStatus status, String summary, boolean pinned,
                        boolean archived, String parentConversationId, long lastActivityAt,
                        long createdAt) {
        this(id, projectId, ownerUserId, title, kind, role, env, worktree, status, summary,
                pinned, archived, parentConversationId, lastActivityAt, createdAt, null, null);
    }

    public Conversation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("conversation id 不能为空");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new IllegalArgumentException("ownerUserId 不能为空");
        }
        title = title == null || title.isBlank() ? "新会话" : title.trim();
        kind = kind == null ? ConversationKind.NORMAL : kind;
        role = role == null || role.isBlank() ? "chat" : role;
        env = env == null || env.isBlank() ? "test" : env;
        status = status == null ? ConversationStatus.IDLE : status;
        summary = summary == null ? "" : summary;
        modelConnectionId = modelConnectionId == null || modelConnectionId.isBlank()
                ? null : modelConnectionId.trim();
        modelName = modelName == null || modelName.isBlank() ? null : modelName.trim();
    }

    /** 新建一条普通会话。 */
    public static Conversation normal(String id, String projectId, String ownerUserId,
                                      String title, String env, String worktree) {
        return normal(id, projectId, ownerUserId, title, env, worktree, null, null);
    }

    /** 新建一条普通会话，并带上本会话选择的模型。 */
    public static Conversation normal(String id, String projectId, String ownerUserId,
                                      String title, String env, String worktree,
                                      String modelConnectionId, String modelName) {
        long now = System.currentTimeMillis();
        return new Conversation(id, projectId, ownerUserId, title, ConversationKind.NORMAL,
                "chat", env, worktree, ConversationStatus.IDLE, "", false, false, null, now, now,
                modelConnectionId, modelName);
    }
}
