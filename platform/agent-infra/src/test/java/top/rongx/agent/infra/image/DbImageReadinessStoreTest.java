package top.rongx.agent.infra.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.infra.persistence.PlatformDb;

/** V6：镜像就绪状态要能跨运行复用（READY 记住、FAILED 带退避），且按主机隔离。 */
class DbImageReadinessStoreTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    private DbImageReadinessStore store() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("readiness.db"));
        db.run(em -> PlatformDb.update(em, """
                CREATE TABLE IF NOT EXISTS image_readiness (
                    host text NOT NULL,
                    image text NOT NULL,
                    status text NOT NULL,
                    source text,
                    digest text,
                    attempts integer NOT NULL DEFAULT 0,
                    last_error text,
                    next_retry_at bigint NOT NULL DEFAULT 0,
                    updated_at bigint NOT NULL,
                    PRIMARY KEY (host, image)
                )
                """));
        return new DbImageReadinessStore(db);
    }

    @Test
    @DisplayName("READY 落库后可按 host+image 读回，digest 与来源都保留")
    void savesReadyAndReadsBack() {
        DbImageReadinessStore store = store();
        store.save(new ImageReadiness("host-a", "node:22-bookworm",
                ImageReadiness.Status.READY, "mirror:docker.m.daocloud.io",
                "sha256:abc", 0, null, 0L, 1_000L));

        ImageReadiness read = store.find("host-a", "node:22-bookworm").orElseThrow();
        assertEquals(ImageReadiness.Status.READY, read.status());
        assertEquals("mirror:docker.m.daocloud.io", read.source());
        assertEquals("sha256:abc", read.digest());
        assertEquals(0, read.attempts());
    }

    @Test
    @DisplayName("同键重复保存是覆盖而不是插入（attempts 累加、退避时间更新）")
    void saveIsUpsert() {
        DbImageReadinessStore store = store();
        store.save(new ImageReadiness("host-a", "maven:3.9", ImageReadiness.Status.FAILED,
                null, null, 1, "执行超时", 5_000L, 1_000L));
        store.save(new ImageReadiness("host-a", "maven:3.9", ImageReadiness.Status.FAILED,
                null, null, 2, "执行停滞", 9_000L, 2_000L));

        assertEquals(1, store.list("host-a").size(), "同 host+image 只能有一条记录");
        ImageReadiness read = store.find("host-a", "maven:3.9").orElseThrow();
        assertEquals(2, read.attempts());
        assertEquals(9_000L, read.nextRetryAt());
        assertTrue(read.inBackoff(8_000L), "退避窗口内应判定为不该再试");
        assertTrue(!read.inBackoff(9_000L), "到期后应允许重试");
    }

    @Test
    @DisplayName("按主机隔离：不同守护上的同名镜像状态互不影响")
    void stateIsPerHost() {
        DbImageReadinessStore store = store();
        store.save(new ImageReadiness("host-a", "node:22", ImageReadiness.Status.READY,
                "direct", "sha256:a", 0, null, 0L, 1_000L));
        store.save(new ImageReadiness("host-b", "node:22", ImageReadiness.Status.FAILED,
                null, null, 1, "无网络", 9_000L, 1_000L));

        assertEquals(ImageReadiness.Status.READY, store.find("host-a", "node:22").orElseThrow().status());
        assertEquals(ImageReadiness.Status.FAILED, store.find("host-b", "node:22").orElseThrow().status());
        assertEquals(List.of("node:22"), store.list("host-a").stream()
                .map(ImageReadiness::image).toList());
        assertEquals(1, store.list("host-b").size());
        // 运维视图按主机汇总，两台主机两条都要看得到
        assertEquals(List.of("host-a", "host-b"), store.listAll().stream()
                .map(ImageReadiness::host).toList());
    }
}
