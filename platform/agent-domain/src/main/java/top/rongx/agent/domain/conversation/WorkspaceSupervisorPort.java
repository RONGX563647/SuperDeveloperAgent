package top.rongx.agent.domain.conversation;

import java.util.List;

/**
 * 工作区调度 Agent 的能力端口（只做"管理"，不含任何写代码/写环境的工具）。
 *
 * <p>实现方负责 owner 校验：工具参数里的 conversationId 来自模型，模型可能编造或引用
 * 别的用户的会话，因此每次都必须用 {@code ownerUserId} 过滤。
 */
public interface WorkspaceSupervisorPort {

    /** 工作区快照：会话数、运行中数量、任务统计。 */
    String snapshot(String projectId, String ownerUserId);

    List<Conversation> listConversations(String projectId, String ownerUserId, boolean includeArchived);

    List<Conversation> searchConversations(String projectId, String ownerUserId, String keyword);

    /** 新建普通会话（禁止创建 Supervisor，调度深度固定为 1）。 */
    Conversation createConversation(String projectId, String ownerUserId, String title,
                                    String env, String worktree);

    boolean renameConversation(String conversationId, String ownerUserId, String title);

    boolean setPinned(String conversationId, String ownerUserId, boolean pinned);

    boolean setArchived(String conversationId, String ownerUserId, boolean archived);

    /** 异步发送一条消息；返回 runId。目标重复且仍在运行时直接返回已有 runId。 */
    String send(String conversationId, String ownerUserId, String text);

    ConversationRun runStatus(String runId, String ownerUserId);

    /** 最多等待 60 秒。 */
    ConversationRun waitForRun(String runId, String ownerUserId, long timeoutMillis);

    boolean cancelRun(String runId, String ownerUserId);

    /** 汇总某个会话的最近运行结果（供调度 Agent 汇报）。 */
    String summarize(String conversationId, String ownerUserId);

    WorkspaceTask createTask(String projectId, String ownerUserId, String goal,
                             String expectedOutput, int priority, String conversationId);

    WorkspaceTask updateTask(String projectId, String ownerUserId, String taskId, String goal,
                             String expectedOutput, TaskStatus status);

    List<WorkspaceTask> listTasks(String projectId, String ownerUserId);
}
