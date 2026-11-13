package top.rongx.agent.governance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 确认令牌的反向测试（项目规约 §9.2 第 3–5 条）。
 *
 * <p>这些用例是"AI 不能自确认"的回归防线：一旦有人把校验改松，这里会红。
 */
class ConfirmTokenServiceTest {

    /** 可推进的时钟，用于测过期。 */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final ConfirmTokenService service = new ConfirmTokenService(clock);

    @Test
    @DisplayName("正常签发并消费")
    void issueThenConsume() {
        ConfirmToken token = service.issue("u1", "chg-1", "hash-a");
        ConfirmToken consumed = service.consume(token.token(), "chg-1", "hash-a");
        assertEquals("u1", consumed.userId());
        assertEquals("hash-a", consumed.planHash());
    }

    @Test
    @DisplayName("令牌一次性：重复消费被拒绝")
    void cannotReuse() {
        ConfirmToken token = service.issue("u1", "chg-1", "hash-a");
        service.consume(token.token(), "chg-1", "hash-a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.consume(token.token(), "chg-1", "hash-a"));
        assertEquals("确认令牌已被使用", e.getMessage());
    }

    @Test
    @DisplayName("过期后拒绝")
    void expiredRejected() {
        ConfirmToken token = service.issue("u1", "chg-1", "hash-a");
        clock.advance(Duration.ofMinutes(6));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.consume(token.token(), "chg-1", "hash-a"));
        assertEquals("确认令牌已过期", e.getMessage());
    }

    @Test
    @DisplayName("计划被改动后拒绝（确认 A、执行 B）")
    void planHashMismatchRejected() {
        ConfirmToken token = service.issue("u1", "chg-1", "hash-a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.consume(token.token(), "chg-1", "hash-b"));
        assertEquals("计划已变更，请重新确认", e.getMessage());
    }

    @Test
    @DisplayName("跨变更单复用被拒绝")
    void changeIdMismatchRejected() {
        ConfirmToken token = service.issue("u1", "chg-1", "hash-a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.consume(token.token(), "chg-2", "hash-a"));
        assertEquals("确认令牌与变更单不匹配", e.getMessage());
    }

    @Test
    @DisplayName("签发人不能为空（防止服务账号代签）")
    void blankUserRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.issue(" ", "chg-1", "hash-a"));
    }

    @Test
    @DisplayName("令牌落库：重启（新实例）后仍可消费，且仍是一次性")
    void tokenSurvivesRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path db = dir.resolve("governance.db");
        ConfirmTokenService before = new ConfirmTokenService(clock, db);
        ConfirmToken token = before.issue("u1", "chg-1", "hash-a");

        // 模拟进程重启：新实例读同一个数据文件
        ConfirmTokenService after = new ConfirmTokenService(clock, db);
        assertEquals("u1", after.consume(token.token(), "chg-1", "hash-a").userId());
        assertThrows(IllegalStateException.class,
                () -> after.consume(token.token(), "chg-1", "hash-a"));
    }

    @Test
    @DisplayName("过期令牌可被清理")
    void purgeExpired(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        ConfirmTokenService persisted = new ConfirmTokenService(clock, dir.resolve("governance.db"));
        persisted.issue("u1", "chg-1", "hash-a");
        clock.advance(Duration.ofMinutes(6));
        assertEquals(1, persisted.purgeExpired());
        assertEquals(0, persisted.purgeExpired());
    }
}
