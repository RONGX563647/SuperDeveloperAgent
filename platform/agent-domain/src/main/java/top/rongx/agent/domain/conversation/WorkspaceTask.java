package top.rongx.agent.domain.conversation;

/**
 * 工作区任务：由调度 Agent 或用户登记，用来表达"这个工作区还要做什么"。
 *
 * @param conversationId 关联会话（可空 = 尚未分配）
 * @param priority       1 最高
 */
public record WorkspaceTask(
        String id,
        String projectId,
        String conversationId,
        String goal,
        String expectedOutput,
        TaskStatus status,
        int priority,
        long createdAt,
        long updatedAt) {

    public WorkspaceTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("task id 不能为空");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("任务目标不能为空");
        }
        goal = goal.trim();
        expectedOutput = expectedOutput == null ? "" : expectedOutput.trim();
        status = status == null ? TaskStatus.TODO : status;
        priority = priority < 1 ? 3 : Math.min(priority, 5);
    }
}
