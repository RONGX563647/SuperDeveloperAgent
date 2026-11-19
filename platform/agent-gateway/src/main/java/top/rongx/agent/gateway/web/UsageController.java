package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.settings.TokenUsageRepository;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageSummary;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageTotals;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.workspace.ConversationManager;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * token 用量查询：会话累计 + 全局聚合（设置页「用量统计」）。
 *
 * <p>会话查询要校验归属，避免用别人的 sessionId 读到用量；
 * 汇总只暴露聚合数字，不含任何提示词或回复内容。
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final TokenUsageRepository usage;
    private final ConversationManager conversations;
    private final CurrentUser currentUser;

    public UsageController(TokenUsageRepository usage, ConversationManager conversations,
                           CurrentUser currentUser) {
        this.usage = usage;
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId) {
        String userId = currentUser.requireUserId();
        conversations.findOwned(sessionId, userId).orElseThrow(
                () -> new RunException(ErrorCode.RUN_INVALID_INPUT, "会话不存在或无权访问：" + sessionId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        UsageTotals totals = usage.sessionTotals(sessionId);
        out.put("totals", totals);
        out.put("cacheHitRate", totals.cacheHitRate());
        out.put("calls", usage.findBySession(sessionId, 200));
        return out;
    }

    /**
     * 全局用量汇总。
     *
     * <p>除原始 token 数外，额外给每个维度算好缓存命中率：前端各自用
     * {@code cachedTokens / inputTokens} 手算容易出现口径不一致（除零、四舍五入、
     * 有的算百分比有的算小数），而命中率恰恰是判断「压缩有没有省到钱」的关键指标
     * （FEAT-5 第 3 项）。统一在服务端算，口径只有一份。
     */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        currentUser.requireUserId();
        UsageSummary raw = usage.summary(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", raw.days());
        out.put("totals", raw.totals());
        // 总体命中率：设置页顶部那个数字
        out.put("cacheHitRate", raw.totals().cacheHitRate());
        out.put("daily", raw.daily().stream().map(row -> withHitRate(
                Map.of("day", row.day()), row)).toList());
        out.put("byModel", raw.byModel().stream().map(row -> withHitRate(
                Map.of("modelName", row.modelName()), row)).toList());
        out.put("byProject", raw.byProject().stream().map(row -> withHitRate(
                Map.of("projectId", row.projectId()), row)).toList());
        return out;
    }

    /** 一行用量 + 该行的缓存命中率（0–100 整数；无输入时为 0）。 */
    private static Map<String, Object> withHitRate(Map<String, Object> label, Object row) {
        long calls;
        long input;
        long cached;
        long output;
        long total;
        if (row instanceof TokenUsageRepository.DailyUsage d) {
            calls = d.calls();
            input = d.inputTokens();
            cached = d.cachedTokens();
            output = d.outputTokens();
            total = d.totalTokens();
        } else if (row instanceof TokenUsageRepository.ModelUsageSummary m) {
            calls = m.calls();
            input = m.inputTokens();
            cached = m.cachedTokens();
            output = m.outputTokens();
            total = m.totalTokens();
        } else if (row instanceof TokenUsageRepository.ProjectUsageSummary p) {
            calls = p.calls();
            input = p.inputTokens();
            cached = p.cachedTokens();
            output = p.outputTokens();
            total = p.totalTokens();
        } else {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "未知的用量行类型：" + row);
        }
        Map<String, Object> out = new LinkedHashMap<>(label);
        out.put("calls", calls);
        out.put("inputTokens", input);
        out.put("cachedTokens", cached);
        out.put("outputTokens", output);
        out.put("totalTokens", total);
        out.put("cacheHitRate", input <= 0 ? 0 : (int) Math.round(cached * 100.0 / input));
        return out;
    }
}
