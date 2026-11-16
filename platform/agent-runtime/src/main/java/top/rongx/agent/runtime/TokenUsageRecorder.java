package top.rongx.agent.runtime;

import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.settings.TokenUsageRepository;

/**
 * 模型 token 用量记录：每次 {@code MODEL_CALL_END} 落一条明细，
 * 同时写独立日志 {@code token.usage}（logs/token-usage.log）。
 *
 * <p>统计失败不能影响对话：仓储异常只记 warn，事件流继续。
 */
@Component
public class TokenUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecorder.class);
    /** 独立日志通道：logback-spring.xml 单独滚动到 logs/token-usage.log。 */
    private static final Logger usageLog = LoggerFactory.getLogger("token.usage");

    private final TokenUsageRepository repository;

    public TokenUsageRecorder(TokenUsageRepository repository) {
        this.repository = repository;
    }

    public void record(String sessionId, ProjectContext ctx, ActiveModel model, ChatUsage usage) {
        if (usage == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        int input = Math.max(0, usage.getInputTokens());
        int cached = Math.min(Math.max(0, usage.getCachedTokens()), input);
        int output = Math.max(0, usage.getOutputTokens());
        long total = (long) input + output;
        String modelName = model == null ? null : model.modelName();
        String connectionId = model == null ? null : model.id();
        String projectId = ctx == null ? null : ctx.projectId();
        String userId = ctx == null ? null : ctx.userId();
        try {
            repository.record(new TokenUsageRepository.ModelUsage(sessionId, projectId, userId,
                    connectionId, modelName, input, cached, output, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("用量落库失败 session={}：{}", sessionId, e.getMessage());
        }
        usageLog.info("session={} project={} user={} connection={} model={} in={} cached={} out={} total={}",
                sessionId, projectId, userId, connectionId, modelName, input, cached, output, total);
    }
}
