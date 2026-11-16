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
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.workspace.ConversationManager;

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
                () -> new IllegalArgumentException("会话不存在或无权访问：" + sessionId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("totals", usage.sessionTotals(sessionId));
        out.put("calls", usage.findBySession(sessionId, 200));
        return out;
    }

    @GetMapping("/summary")
    public UsageSummary summary(@RequestParam(defaultValue = "30") int days) {
        currentUser.requireUserId();
        return usage.summary(days);
    }
}
