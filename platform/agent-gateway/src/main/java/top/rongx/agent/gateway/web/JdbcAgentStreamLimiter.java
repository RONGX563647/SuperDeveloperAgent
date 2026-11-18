package top.rongx.agent.gateway.web;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 多实例共享的并发闸门：统一库里的租约表。
 *
 * <p>每个许可是一行 {@code stream_lease(token, expires_at)}：占用时在同一个写事务里
 * 清理过期行、统计、插入；释放时按 token 精确删除。TTL 兜底进程崩溃后残留的租约，
 * 因此不需要额外的后台清理任务。
 *
 * <p>数据库的写锁天然串行化多实例的"统计 + 插入"，不会出现同时超额通过。
 * 注意这是平台层的粗粒度闸门，网关层仍建议做一层用户级限流。
 */
public class JdbcAgentStreamLimiter implements AgentStreamLimiter {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentStreamLimiter.class);

    private final PlatformDb db;
    private final int max;
    private final long ttlMillis;

    public JdbcAgentStreamLimiter(PlatformDb db, int max, long ttlSeconds) {
        this.db = db;
        this.max = Math.max(1, max);
        this.ttlMillis = Math.max(30, ttlSeconds) * 1000L;
    }

    /** 测试/独立进程：按 SQLite 文件定位统一库。 */
    public JdbcAgentStreamLimiter(Path dbFile, int max, long ttlSeconds) {
        this(PlatformDb.sqlite(dbFile), max, ttlSeconds);
    }

    @Override
    public Lease acquireOrThrow() {
        long now = System.currentTimeMillis();
        String token = UUID.randomUUID().toString();
        try {
            return db.inTx(em -> {
                purgeExpired(em, now);
                int used = count(em);
                if (used >= max) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "当前并发会话过多（集群上限 " + max + "），请稍后再试");
                }
                PlatformDb.update(em,
                        "INSERT INTO stream_lease(token, expires_at) VALUES (?, ?)",
                        token, now + ttlMillis);
                return new Lease(token);
            });
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "并发许可占用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void release(Lease lease) {
        if (lease == null || lease.token() == null) {
            return;
        }
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM stream_lease WHERE token = ?", lease.token()));
        } catch (RuntimeException e) {
            // 释放失败由 TTL 兜底，只记录错误本身，不打印租约内容
            log.warn("并发许可释放失败：{}", e.getMessage());
        }
    }

    @Override
    public int available() {
        long now = System.currentTimeMillis();
        try {
            return db.inTx(em -> {
                purgeExpired(em, now);
                return Math.max(0, max - count(em));
            });
        } catch (RuntimeException e) {
            // 诊断接口不应因存储抖动失败
            return 0;
        }
    }

    private static void purgeExpired(EntityManager em, long now) {
        PlatformDb.update(em, "DELETE FROM stream_lease WHERE expires_at <= ?", now);
    }

    private static int count(EntityManager em) {
        return (int) PlatformDb.count(em, "SELECT COUNT(*) FROM stream_lease");
    }
}
