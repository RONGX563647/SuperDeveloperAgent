package top.rongx.agent.governance;

import java.nio.file.Path;
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
