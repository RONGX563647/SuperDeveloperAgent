package top.rongx.agent.infra.image;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.domain.image.ImageReadinessStore;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 镜像就绪状态落库（V6 迁移）。
 *
 * <p>用 upsert 而不是"先查再写"：贴合 {@code DbBugFixStore} 已有写法，SQLite 与
 * PostgreSQL 都支持 {@code ON CONFLICT}，并发下也不会写坏主键。
 */
@Repository
public class DbImageReadinessStore implements ImageReadinessStore {

    private final PlatformDb db;

    public DbImageReadinessStore(PlatformDb db) {
        this.db = db;
    }

    @Override
    public Optional<ImageReadiness> find(String host, String image) {
        return db.read(em -> PlatformDb.row(em, """
                SELECT status, source, digest, attempts, last_error, next_retry_at, updated_at
                FROM image_readiness WHERE host = ? AND image = ?
                """, host, image).map(row -> toReadiness(host, image, row)));
    }

    @Override
    public void save(ImageReadiness readiness) {
        db.run(em -> PlatformDb.update(em, """
                INSERT INTO image_readiness
                    (host, image, status, source, digest, attempts, last_error,
                     next_retry_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(host, image) DO UPDATE SET
                    status = excluded.status,
                    source = excluded.source,
                    digest = excluded.digest,
                    attempts = excluded.attempts,
                    last_error = excluded.last_error,
                    next_retry_at = excluded.next_retry_at,
                    updated_at = excluded.updated_at
                """,
                readiness.host(), readiness.image(), readiness.status().name(),
                readiness.source(), readiness.digest(), readiness.attempts(),
                readiness.lastError(), readiness.nextRetryAt(), readiness.updatedAt()));
    }

    @Override
    public List<ImageReadiness> list(String host) {
        return db.read(em -> PlatformDb.rows(em, """
                SELECT image, status, source, digest, attempts, last_error,
                       next_retry_at, updated_at
                FROM image_readiness WHERE host = ? ORDER BY image
                """, host).stream()
                .map(row -> toReadiness(host, str(row[0]),
                        new Object[] {row[1], row[2], row[3], row[4], row[5], row[6], row[7]}))
                .toList());
    }

    @Override
    public List<ImageReadiness> listAll() {
        return db.read(em -> PlatformDb.rows(em, """
                SELECT host, image, status, source, digest, attempts, last_error,
                       next_retry_at, updated_at
                FROM image_readiness ORDER BY host, image
                """).stream()
                .map(row -> toReadiness(str(row[0]), str(row[1]),
                        new Object[] {row[2], row[3], row[4], row[5], row[6], row[7], row[8]}))
                .toList());
    }

    private static ImageReadiness toReadiness(String host, String image, Object[] row) {
        return new ImageReadiness(host, image,
                "READY".equals(str(row[0])) ? ImageReadiness.Status.READY : ImageReadiness.Status.FAILED,
                str(row[1]), str(row[2]), intOf(row[3]), str(row[4]), num(row[5]), num(row[6]));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
