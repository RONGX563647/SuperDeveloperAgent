package top.rongx.agent.domain.conversation;

import java.util.List;
import java.util.Optional;

/** 异步运行仓库：状态必须落盘，进程重启后仍能查询历史运行。 */
public interface ConversationRunRepository {

    ConversationRun saveRun(ConversationRun run);

    ConversationRun updateRun(ConversationRun run);

    Optional<ConversationRun> findRun(String id);

    Optional<ConversationRun> findActiveByConversation(String conversationId);

    /** 同一项目下仍在排队/运行、且目标相同的运行（用于防重复提交）。 */
    Optional<ConversationRun> findActiveByGoal(String projectId, String goalKey);

    List<ConversationRun> findRunsByProject(String projectId, int limit);

    List<ConversationRun> findRunsByConversation(String conversationId, int limit);
}
