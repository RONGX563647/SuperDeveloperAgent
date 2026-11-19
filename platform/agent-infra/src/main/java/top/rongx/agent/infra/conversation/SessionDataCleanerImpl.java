package top.rongx.agent.infra.conversation;

import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.conversation.SessionDataCleaner;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.domain.settings.TokenUsageRepository;

/**
 * 会话删除后的磁盘/账本清理。
 *
 * <p>几处存储互相独立：任何一处失败只记日志，不能把「会话已从列表删掉」变成 500。
 */
@Component
public class SessionDataCleanerImpl implements SessionDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(SessionDataCleanerImpl.class);

    private final RunLedgerRepository ledger;
    private final AgentStateStore agentState;
    private final TokenUsageRepository tokenUsage;
    private final ConversationTimelineRepository timeline;

    public SessionDataCleanerImpl(RunLedgerRepository ledger, AgentStateStore agentState,
                                  TokenUsageRepository tokenUsage,
                                  ConversationTimelineRepository timeline) {
        this.ledger = ledger;
        this.agentState = agentState;
        this.tokenUsage = tokenUsage;
        this.timeline = timeline;
    }

    @Override
    public void purge(String ownerUserId, String conversationId) {
        try {
            ledger.deleteBySubject(conversationId);
        } catch (RuntimeException e) {
            log.warn("清理运行账本失败 session={}：{}", conversationId, e.getMessage());
        }
        try {
            agentState.delete(ownerUserId, conversationId);
        } catch (RuntimeException e) {
            log.warn("清理 Agent 记忆失败 session={}：{}", conversationId, e.getMessage());
        }
        try {
            tokenUsage.deleteBySession(conversationId);
        } catch (RuntimeException e) {
            log.warn("清理用量记录失败 session={}：{}", conversationId, e.getMessage());
        }
        try {
            timeline.deleteByConversation(conversationId);
        } catch (RuntimeException e) {
            log.warn("清理时间线失败 session={}：{}", conversationId, e.getMessage());
        }
    }
}
