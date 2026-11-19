package top.rongx.agent.domain.conversation;

import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 工作区任务：由调度 Agent 或用户登记，用来表达"这个工作区还要做什么"。
 *
 * @param conversationId 关联会话（可空 = 尚未分配）
 * @param priority       1 最高
 * @param sourceRef      事件来源（如 Gitee 事件去重键；可空 = 人工登记）
 * @param triggerType    触发类型（如 issue_hook；可空 = 人工登记）
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
        long updatedAt,
        String sourceRef,
        String triggerType) {

    public WorkspaceTask {
        if (id == null || id.isBlank()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "task id 不能为空");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "projectId 不能为空");
        }
        if (goal == null || goal.isBlank()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "任务目标不能为空");
        }
        goal = goal.trim();
        expectedOutput = expectedOutput == null ? "" : expectedOutput.trim();
        status = status == null ? TaskStatus.TODO : status;
        priority = priority < 1 ? 3 : Math.min(priority, 5);
    }
}
