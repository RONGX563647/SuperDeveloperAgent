package top.rongx.agent.governance;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.change.ChangePlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变更单持久化：重启后确认卡片仍能按 changeId 查到同一份计划。
 */
class ChangeRegistryTest {

    /**
     * 关闭按 JDBC URL 缓存的独立库：Windows 上 SQLite 文件还开着时，
     * JUnit 删除 @TempDir 会因"另一个程序正在使用"失败（@AfterEach 先于扩展清理执行）。
     */
    @AfterEach
    void releaseDb() {
        GovernanceDb.closeAll();
    }

    @Test
    @DisplayName("登记后的变更单在新实例中可查到，planHash 不变")
    void survivesRestart(@TempDir Path dir) {
        ChangeRegistry before = new ChangeRegistry(dir.toString());
        ChangePlan plan = before.request("RY", "test", "stack_deploy", "发布 test",
                "sha256:abc", null, false);

        ChangeRegistry after = new ChangeRegistry(dir.toString());
        ChangePlan loaded = after.find(plan.changeId()).orElseThrow();
        assertEquals(plan.planHash(), loaded.planHash());
        assertEquals("RY", loaded.projectId());
        assertEquals("test", loaded.env());
        assertEquals("stack_deploy", loaded.kind());
        assertEquals(plan.createdAt().toEpochMilli(), loaded.createdAt().toEpochMilli());
        assertTrue(after.find("chg-missing").isEmpty());
    }

    @Test
    @DisplayName("调用方给定 planHash 的登记路径同样落库")
    void registerWithHashPersisted(@TempDir Path dir) {
        ChangeRegistry registry = new ChangeRegistry(dir.toString());
        ChangePlan plan = registry.registerWithHash("RY", "prod", "stack_rollback", "回滚",
                "hash-from-recipe", true);
        ChangePlan loaded = new ChangeRegistry(dir.toString()).find(plan.changeId()).orElseThrow();
        assertEquals("hash-from-recipe", loaded.planHash());
        assertTrue(loaded.highRisk());
    }

    @Test
    @DisplayName("状态流转：PENDING → CONFIRMED → APPLIED/FAILED 持久化")
    void statusTransitionsPersist(@TempDir Path dir) {
        ChangeRegistry registry = new ChangeRegistry(dir.toString());
        ChangePlan plan = registry.request("RY", "test", "stack_deploy", "发布 test",
                null, null, false);
        assertEquals(ChangePlan.Status.PENDING, plan.status(), "登记后默认待确认");

        registry.updateStatus(plan.changeId(), ChangePlan.Status.CONFIRMED);
        // 新实例重新读库，状态必须仍在（Agent 后续轮次靠它判断）
        assertEquals(ChangePlan.Status.CONFIRMED,
                new ChangeRegistry(dir.toString()).find(plan.changeId()).orElseThrow().status());

        registry.updateStatus(plan.changeId(), ChangePlan.Status.FAILED);
        assertEquals(ChangePlan.Status.FAILED, registry.find(plan.changeId()).orElseThrow().status());
        // 未知单号不抛异常，保持幂等
        registry.updateStatus("chg-missing", ChangePlan.Status.APPLIED);
    }

    @Test
    @DisplayName("按时间清理：早于保留期的变更单被删除，近期保留")
    void purgeBeforeRemovesOnlyStale(@TempDir Path dir) {
        ChangeRegistry registry = new ChangeRegistry(dir.toString());
        ChangePlan plan = registry.request("RY", "test", "stack_deploy", "发布 test",
                null, null, false);

        assertEquals(0, registry.purgeBefore(java.time.Instant.now().minusSeconds(60)));
        assertTrue(registry.find(plan.changeId()).isPresent());

        assertEquals(1, registry.purgeBefore(java.time.Instant.now().plusSeconds(60)));
        assertTrue(registry.find(plan.changeId()).isEmpty());
    }
}
