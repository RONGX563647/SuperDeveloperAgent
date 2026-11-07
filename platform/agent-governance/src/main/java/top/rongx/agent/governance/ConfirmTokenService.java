package top.rongx.agent.governance;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 确认令牌服务（PRD §4.10 的四道守卫中的第 2、3 道）。
 *
 * <ol>
 *   <li>令牌与人绑定：{@code userId} 来自用户会话，不是模型参数；</li>
 *   <li>令牌与计划绑定：{@code planHash} 在执行前必须比对。</li>
 * </ol>
 *
 * <p>骨架实现放内存；生产改为 Redis（带 TTL），接口不变。
 */
@Service
public class ConfirmTokenService {

    /** 默认有效期 5 分钟。 */
    public static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, ConfirmToken> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ConfirmTokenService() {
        this(Clock.systemUTC());
    }

    /** 供测试注入固定时钟。 */
    public ConfirmTokenService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 签发令牌。只能由已登录用户的请求链路调用，<b>不得由工具调用触发</b>。
     *
     * @param userId   发起人
     * @param changeId 变更单
     * @param planHash 计划哈希
     * @return 令牌
     */
    public ConfirmToken issue(String userId, String changeId, String planHash) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("签发人不能为空");
        }
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        ConfirmToken token = new ConfirmToken(HexFormat.of().formatHex(buf), userId, changeId, planHash,
                clock.instant().plus(TTL), false);
        tokens.put(token.token(), token);
        return token;
    }

    /**
     * 校验并消费令牌。
     *
     * @throws IllegalStateException 令牌不存在 / 已用 / 过期 / 与计划不匹配
     */
    public ConfirmToken consume(String tokenValue, String changeId, String planHash) {
        ConfirmToken token = tokens.get(tokenValue);
        if (token == null) {
            throw new IllegalStateException("确认令牌不存在");
        }
        if (token.used()) {
            throw new IllegalStateException("确认令牌已被使用");
        }
        if (token.expired(clock.instant())) {
            tokens.remove(tokenValue);
            throw new IllegalStateException("确认令牌已过期");
        }
        if (!token.changeId().equals(changeId)) {
            throw new IllegalStateException("确认令牌与变更单不匹配");
        }
        // 关键守卫：确认的必须是这个计划，而不是"同一个变更单的任意计划"
        if (!token.planHash().equals(planHash)) {
            throw new IllegalStateException("计划已变更，请重新确认");
        }
        ConfirmToken used = token.markUsed();
        tokens.put(tokenValue, used);
        return used;
    }

    /** 清理过期令牌（可由定时任务调用）。 */
    public int purgeExpired() {
        Instant now = clock.instant();
        int before = tokens.size();
        tokens.entrySet().removeIf(e -> e.getValue().expired(now));
        return before - tokens.size();
    }
}
