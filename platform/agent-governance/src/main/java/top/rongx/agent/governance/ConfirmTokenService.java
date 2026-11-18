package top.rongx.agent.governance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.PromotionException;

/**
 * 确认令牌服务（PRD §4.10 的四道守卫中的第 2、3 道）。
 *
 * <ol>
 *   <li>令牌与人绑定：{@code userId} 来自用户会话，不是模型参数；</li>
 *   <li>令牌与计划绑定：{@code planHash} 在执行前必须比对。</li>
 * </ol>
 *
 * <p>令牌落统一库（{@code <workspace>/_platform/platform.db}）：重启后仍可消费，
 * 多实例共享同一数据文件时也保持一致。消费用 {@code UPDATE ... WHERE used=0} 原子完成，
 * 并发下只有一次能成功。
 */
@Service
public class ConfirmTokenService {

    /** 默认有效期 5 分钟。 */
    public static final Duration TTL = Duration.ofMinutes(5);

    private final GovernanceDb db;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    /** 生产装配：令牌落工作区统一库。 */
    @Autowired public ConfirmTokenService(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            GovernanceDb db) {
        this(Clock.systemUTC(), db);
    }

    /** 独立进程：按工作区根目录定位统一库文件。 */
    public ConfirmTokenService(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(Clock.systemUTC(), platformDbFile(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")));
    }

    /** 供测试注入固定时钟（{@code db == null} 时为纯内存，不触碰磁盘）。 */
    public ConfirmTokenService(Clock clock) {
        this(clock, (GovernanceDb) null);
    }

    ConfirmTokenService(Clock clock, Path dbFile) {
        this(clock, dbFile == null ? (GovernanceDb) null : GovernanceDb.sqlite(platformDbFile(dbFile)));
    }

    /** 独立运行时要保证库文件所在目录存在；schema 仍由 Hibernate 生成。 */
    private static Path platformDbFile(Path dbFile) {
        Path file = dbFile.toAbsolutePath().normalize();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌存储目录创建失败：" + e.getMessage(), e);
        }
        return file;
    }

    /** {@code db == null} 表示纯内存模式。 */
    ConfirmTokenService(Clock clock, GovernanceDb db) {
        this.clock = clock;
        this.db = db;
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
        // 1. 签发人必须来自已登录会话，不得为空（令牌与人绑定）
        if (userId == null || userId.isBlank()) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_INPUT, "签发人不能为空");
        }
        // 2. 生成 24 字节随机令牌值（不可预测）
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        // 3. 组装令牌：绑定 userId + changeId + planHash，TTL 内有效
        ConfirmToken token = new ConfirmToken(HexFormat.of().formatHex(buf), userId, changeId, planHash,
                clock.instant().plus(TTL), false);
        // 4. 入库待消费
        if (db == null) {
            memory.put(token.token(), token);
        } else {
            insert(token);
        }
        return token;
    }

    /**
     * 校验并消费令牌。
     *
     * @throws IllegalStateException 令牌不存在 / 已用 / 过期 / 与计划不匹配
     */
    public ConfirmToken consume(String tokenValue, String changeId, String planHash) {
        // 1. 令牌必须存在
        ConfirmToken token = db == null ? memory.get(tokenValue) : select(tokenValue);
        if (token == null) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌不存在");
        }
        // 2. 不可重复使用
        if (token.used()) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌已被使用");
        }
        // 3. 不可过期（过期即销毁，防重放）
        if (token.expired(clock.instant())) {
            delete(tokenValue);
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌已过期");
        }
        // 4. 绑定变更单必须一致
        if (!token.changeId().equals(changeId)) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌与变更单不匹配");
        }
        // 5. 关键守卫：确认的是当前计划哈希，而非"同单任意计划"（防配方篡改后复用令牌）
        if (!token.planHash().equals(planHash)) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "计划已变更，请重新确认");
        }
        // 6. 原子标记为已用：并发消费只有一次能成功（一次性）
        if (db == null) {
            memory.put(tokenValue, token.markUsed());
        } else if (!markUsed(tokenValue)) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌已被使用");
        }
        return token.markUsed();
    }

    /** 清理过期令牌（可由定时任务调用）。 */
    public int purgeExpired() {
        Instant now = clock.instant();
        if (db == null) {
            int before = memory.size();
            memory.entrySet().removeIf(e -> e.getValue().expired(now));
            return before - memory.size();
        }
        try {
            return db.inTx(em -> GovernanceDb.update(em,
                    "DELETE FROM confirm_tokens WHERE expires_at < ?", now.toEpochMilli()));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌清理失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 存储

    /** 测试用内存后备（仅当未配置数据库时使用）。 */
    private final java.util.Map<String, ConfirmToken> memory = new java.util.concurrent.ConcurrentHashMap<>();

    private void insert(ConfirmToken token) {
        String sql = """
                INSERT INTO confirm_tokens (token, user_id, change_id, plan_hash, expires_at, used)
                VALUES (?,?,?,?,?,?)
                """;
        try {
            db.run(em -> GovernanceDb.update(em, sql,
                    token.token(),
                    token.userId(),
                    token.changeId(),
                    token.planHash(),
                    token.expiresAt().toEpochMilli(),
                    token.used() ? 1 : 0));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌签发失败：" + e.getMessage(), e);
        }
    }

    private ConfirmToken select(String tokenValue) {
        String sql = """
                SELECT token, user_id, change_id, plan_hash, expires_at, used
                FROM confirm_tokens WHERE token = ?
                """;
        try {
            return db.read(em -> GovernanceDb.row(em, sql, tokenValue)
                    .map(row -> new ConfirmToken(
                            (String) row[0],
                            (String) row[1],
                            (String) row[2],
                            (String) row[3],
                            Instant.ofEpochMilli(((Number) row[4]).longValue()),
                            ((Number) row[5]).intValue() == 1))
                    .orElse(null));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌查询失败：" + e.getMessage(), e);
        }
    }

    /** 原子消费：只有 {@code used=0} 那一行会被更新。 */
    private boolean markUsed(String tokenValue) {
        try {
            return db.inTx(em -> GovernanceDb.update(em,
                    "UPDATE confirm_tokens SET used = 1 WHERE token = ? AND used = 0", tokenValue) == 1);
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌消费失败：" + e.getMessage(), e);
        }
    }

    private void delete(String tokenValue) {
        if (db == null) {
            memory.remove(tokenValue);
            return;
        }
        try {
            db.run(em -> GovernanceDb.update(em,
                    "DELETE FROM confirm_tokens WHERE token = ?", tokenValue));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "确认令牌清理失败：" + e.getMessage(), e);
        }
    }
}
