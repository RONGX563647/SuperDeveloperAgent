package top.rongx.agent.governance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 确认令牌服务（PRD §4.10 的四道守卫中的第 2、3 道）。
 *
 * <ol>
 *   <li>令牌与人绑定：{@code userId} 来自用户会话，不是模型参数；</li>
 *   <li>令牌与计划绑定：{@code planHash} 在执行前必须比对。</li>
 * </ol>
 *
 * <p>令牌落 SQLite（{@code <workspace>/_platform/governance.db}）：重启后仍可消费，
 * 多实例共享同一数据文件时也保持一致。消费用 {@code UPDATE ... WHERE used=0} 原子完成，
 * 并发下只有一次能成功。
 */
@Service
public class ConfirmTokenService {

    /** 默认有效期 5 分钟。 */
    public static final Duration TTL = Duration.ofMinutes(5);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS confirm_tokens (
                token      TEXT PRIMARY KEY,
                user_id    TEXT NOT NULL,
                change_id  TEXT NOT NULL,
                plan_hash  TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                used       INTEGER NOT NULL DEFAULT 0
            )
            """;

    private final Path dbFile;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    /** 生产装配：令牌落工作区 SQLite。 */
    @Autowired public ConfirmTokenService(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(Clock.systemUTC(), Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("governance.db"));
    }

    /** 供测试注入固定时钟（{@code dbFile == null} 时为纯内存，不触碰磁盘）。 */
    public ConfirmTokenService(Clock clock) {
        this(clock, null);
    }

    ConfirmTokenService(Clock clock, Path dbFile) {
        this.clock = clock;
        this.dbFile = dbFile;
        if (dbFile != null) {
            initSchema();
        }
    }

    private void initSchema() {
        try {
            Files.createDirectories(dbFile.getParent());
            try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(SCHEMA)) {
                st.executeUpdate();
            }
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("confirm_tokens 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return GovernanceDb.open(dbFile);
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
            throw new IllegalArgumentException("签发人不能为空");
        }
        // 2. 生成 24 字节随机令牌值（不可预测）
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        // 3. 组装令牌：绑定 userId + changeId + planHash，TTL 内有效
        ConfirmToken token = new ConfirmToken(HexFormat.of().formatHex(buf), userId, changeId, planHash,
                clock.instant().plus(TTL), false);
        // 4. 入库待消费
        if (dbFile == null) {
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
        ConfirmToken token = dbFile == null ? memory.get(tokenValue) : select(tokenValue);
        if (token == null) {
            throw new IllegalStateException("确认令牌不存在");
        }
        // 2. 不可重复使用
        if (token.used()) {
            throw new IllegalStateException("确认令牌已被使用");
        }
        // 3. 不可过期（过期即销毁，防重放）
        if (token.expired(clock.instant())) {
            delete(tokenValue);
            throw new IllegalStateException("确认令牌已过期");
        }
        // 4. 绑定变更单必须一致
        if (!token.changeId().equals(changeId)) {
            throw new IllegalStateException("确认令牌与变更单不匹配");
        }
        // 5. 关键守卫：确认的是当前计划哈希，而非"同单任意计划"（防配方篡改后复用令牌）
        if (!token.planHash().equals(planHash)) {
            throw new IllegalStateException("计划已变更，请重新确认");
        }
        // 6. 原子标记为已用：并发消费只有一次能成功（一次性）
        if (dbFile == null) {
            memory.put(tokenValue, token.markUsed());
        } else if (!markUsed(tokenValue)) {
            throw new IllegalStateException("确认令牌已被使用");
        }
        return token.markUsed();
    }

    /** 清理过期令牌（可由定时任务调用）。 */
    public int purgeExpired() {
        Instant now = clock.instant();
        if (dbFile == null) {
            int before = memory.size();
            memory.entrySet().removeIf(e -> e.getValue().expired(now));
            return before - memory.size();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM confirm_tokens WHERE expires_at < ?")) {
            st.setLong(1, now.toEpochMilli());
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("确认令牌清理失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 存储

    /** 测试用内存后备（仅当未配置数据库时使用）。 */
    private final java.util.Map<String, ConfirmToken> memory = new java.util.concurrent.ConcurrentHashMap<>();

    private void insert(ConfirmToken token) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO confirm_tokens (token, user_id, change_id, plan_hash, expires_at, used)
                     VALUES (?,?,?,?,?,?)
                     """)) {
            st.setString(1, token.token());
            st.setString(2, token.userId());
            st.setString(3, token.changeId());
            st.setString(4, token.planHash());
            st.setLong(5, token.expiresAt().toEpochMilli());
            st.setInt(6, token.used() ? 1 : 0);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("确认令牌签发失败：" + e.getMessage(), e);
        }
    }

    private ConfirmToken select(String tokenValue) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT token, user_id, change_id, plan_hash, expires_at, used
                     FROM confirm_tokens WHERE token = ?
                     """)) {
            st.setString(1, tokenValue);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ConfirmToken(rs.getString("token"), rs.getString("user_id"),
                        rs.getString("change_id"), rs.getString("plan_hash"),
                        Instant.ofEpochMilli(rs.getLong("expires_at")), rs.getInt("used") == 1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("确认令牌查询失败：" + e.getMessage(), e);
        }
    }

    /** 原子消费：只有 {@code used=0} 那一行会被更新。 */
    private boolean markUsed(String tokenValue) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "UPDATE confirm_tokens SET used = 1 WHERE token = ? AND used = 0")) {
            st.setString(1, tokenValue);
            return st.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("确认令牌消费失败：" + e.getMessage(), e);
        }
    }

    private void delete(String tokenValue) {
        if (dbFile == null) {
            memory.remove(tokenValue);
            return;
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM confirm_tokens WHERE token = ?")) {
            st.setString(1, tokenValue);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("确认令牌清理失败：" + e.getMessage(), e);
        }
    }
}
