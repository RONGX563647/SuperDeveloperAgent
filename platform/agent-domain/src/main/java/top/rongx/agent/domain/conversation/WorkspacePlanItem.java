package top.rongx.agent.domain.conversation;

/**
 * 调度 Agent 的一次计划项（Plan/Todo）。
 *
 * <p>计划直接落到工作区任务板：已经有 {@code taskId} 的计划项走更新，没有则新建。
 * 这样模型只需要一次工具调用就能把"打算做什么"变成用户可见、可跟踪的任务清单，
 * 不需要额外的计划表，也不会和任务板出现两套状态。
 *
 * @param taskId         已存在的任务 id；为空表示新建
 * @param goal           计划项目标
 * @param expectedOutput 期望产出（可空）
 * @param priority       优先级 1-5（可空，默认 3）
 * @param status         初始/更新状态（可空，默认 TODO）
 * @param conversationId 关联会话 id（可空）
 */
public record WorkspacePlanItem(
        String taskId,
        String goal,
        String expectedOutput,
        Integer priority,
        String status,
        String conversationId) {
}
