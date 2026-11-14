package top.rongx.agent.domain.conversation;

/**
 * 一次异步运行。
 *
 * <p>把"给某个会话发一条消息"变成可排队、可取消、可轮询的任务：
 * 工作区调度 Agent 不应该同步阻塞到子会话跑完。
 */
public record ConversationRun(
        String id,
        String projectId,
        String conversationId,
        String ownerUserId,
        String goal,
        String goalKey,
        RunStatus status,
        String result,
        String error,
        long createdAt,
        long startedAt,
        long endedAt) {

    public ConversationRun {
        if (id == null || id.isBlank() || conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("run id / conversationId 不能为空");
        }
        status = status == null ? RunStatus.QUEUED : status;
        goal = goal == null ? "" : goal;
        goalKey = goalKey == null ? "" : goalKey;
        result = result == null ? "" : result;
        error = error == null ? "" : error;
    }

    public ConversationRun withStatus(RunStatus next, String nextResult, String nextError,
                                      long started, long ended) {
        return new ConversationRun(id, projectId, conversationId, ownerUserId, goal, goalKey,
                next, nextResult, nextError, createdAt, started, ended);
    }
}
