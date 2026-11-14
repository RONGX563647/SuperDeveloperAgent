package top.rongx.agent.domain.conversation;

import java.util.List;
import java.util.Optional;

/** 工作区任务仓库。 */
public interface WorkspaceTaskRepository {

    WorkspaceTask saveTask(WorkspaceTask task);

    WorkspaceTask updateTask(WorkspaceTask task);

    Optional<WorkspaceTask> findTask(String id);

    List<WorkspaceTask> findTasksByProject(String projectId);

    List<WorkspaceTask> findTasksByConversation(String conversationId);
}
