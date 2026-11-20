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
    /** 分项归因通道：本轮加载了哪些插件、各占多少 schema 字符。 */
    private static final Logger attributionLog = LoggerFactory.getLogger("token.attribution");

    private final TokenUsageRepository repository;

    public TokenUsageRecorder(TokenUsageRepository repository) {
        this.repository = repository;
    }

    /** 旧签名（无归因）：从 RuntimeContext 取归因后委托到完整版；无上下文时留空。 */
    public void record(String sessionId, ProjectContext ctx, ActiveModel model, ChatUsage usage,
                       io.agentscope.core.agent.RuntimeContext runtime) {
        ToolAttribution attribution = runtime == null ? null : runtime.get(ToolAttribution.class);
        record(sessionId, ctx, model, usage, attribution);
    }

    public void record(String sessionId, ProjectContext ctx, ActiveModel model, ChatUsage usage) {
        record(sessionId, ctx, model, usage, (ToolAttribution) null);
    }

    public void record(String sessionId, ProjectContext ctx, ActiveModel model, ChatUsage usage,
                       ToolAttribution attribution) {
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
        String activatedPlugins = attribution == null ? null : attribution.pluginsCsv();
        String pluginSchemaChars = attribution == null ? null : attribution.schemaCharsCsv();
        try {
            repository.record(new TokenUsageRepository.ModelUsage(sessionId, projectId, userId,
                    connectionId, modelName, input, cached, output, System.currentTimeMillis(),
                    activatedPlugins, pluginSchemaChars));
        } catch (RuntimeException e) {
            log.warn("用量落库失败 session={}：{}", sessionId, e.getMessage());
        }
        // 命中率与前端/接口同一口径：cached/input，input 为 0 时记 0，
        // 便于直接看 logs/token-usage.log 判断前缀缓存是否持续生效。
        int hitRate = input <= 0 ? 0 : (int) Math.round(cached * 100.0 / input);
        usageLog.info("session={} project={} user={} connection={} model={} in={} cached={} "
                        + "out={} total={} hit={}%",
                sessionId, projectId, userId, connectionId, modelName, input, cached, output,
                total, hitRate);
        // 分项归因独立成行，便于按 session 追踪"这一轮到底加载了哪些插件、各占多少 token 代理"。
        if (attribution != null && (!attribution.activatedPlugins().isEmpty()
                || !attribution.schemaChars().isEmpty())) {
            attributionLog.info("session={} model={} plugins={} schemaChars={}",
                    sessionId, modelName, activatedPlugins, pluginSchemaChars);
        }
    }
}
