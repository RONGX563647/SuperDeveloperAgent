package top.rongx.agent.domain.conversation;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓库。
 *
 * <p>所有查询都带 {@code ownerUserId}：不做项目级 RBAC（任何登录用户都能进入任一项目），
 * 但会话列表与内容只对创建者可见。
 */
public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> find(String id);

    Optional<Conversation> findByOwner(String id, String ownerUserId);

    List<Conversation> findByProject(String projectId, String ownerUserId, boolean includeArchived);

    Optional<Conversation> findSupervisor(String projectId, String ownerUserId);

    /** 局部更新；返回更新后的会话（不存在或不属于该用户时为空）。 */
    Optional<Conversation> update(String id, String ownerUserId, ConversationPatch patch);

    boolean delete(String id, String ownerUserId);
}
