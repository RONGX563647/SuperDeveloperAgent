package top.rongx.agent.infra.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 环境绑定历史：全量可查、解绑只标记不删除、当前可用绑定的判定口径。
 *
 * <p>「历史环境卡片看不到」的根因是只有「当前绑定」这一条投影；
 * 这里把「历史必须留痕」固化成存储层契约。
 */
class EnvBindingHistoryTest {

    @TempDir
    Path workspace;

    private DbEnvironmentStore store() {
        return new DbEnvironmentStore(workspace.toString(),
                new SecretBox(workspace.toString(), ""));
    }

    private static EnvBinding draft(String projectId, String env, String hash) {
        return new EnvBinding(null, projectId, env, "{\"keys\":[]}", hash, "agent",
                null, 0, 0, "DRAFT");
    }

    @Test
    @DisplayName("绑定历史包含草稿/已确认/已解绑，按创建时间倒序")
    void historyKeepsEveryState() {
        DbEnvironmentStore store = store();
        EnvBinding first = store.saveBinding(draft("p1", "test", "h1"));
        store.confirmBinding(first.id(), "u1", System.currentTimeMillis() + 3_600_000L);
        EnvBinding second = store.saveBinding(draft("p1", "test", "h2"));
        EnvBinding third = store.saveBinding(draft("p1", "test", "h3"));

        List<EnvBinding> rows = store.bindings("p1", "test", 20);

        assertEquals(3, rows.size(), "三条记录都应保留：" + rows);
        assertEquals(third.id(), rows.get(0).id(), "最新的在最前");
        assertTrue(rows.stream().anyMatch(b -> b.id().equals(first.id()) && b.confirmed()));
        assertTrue(rows.stream().anyMatch(b -> b.id().equals(second.id()) && "DRAFT".equals(b.status())));
    }

    @Test
    @DisplayName("不同环境/项目的绑定互不串场")
    void historyIsScopedByProjectAndEnv() {
        DbEnvironmentStore store = store();
        store.saveBinding(draft("p1", "test", "h1"));
        store.saveBinding(draft("p1", "dev", "h2"));
        store.saveBinding(draft("p2", "test", "h3"));

        assertEquals(1, store.bindings("p1", "test", 20).size());
        assertEquals(1, store.bindings("p1", "dev", 20).size());
        assertEquals(1, store.bindings("p2", "test", 20).size());
    }

    @Test
    @DisplayName("解绑只标记 REVOKED：记录仍在历史里，且不再算可用")
    void revokeKeepsRecord() {
        DbEnvironmentStore store = store();
        EnvBinding binding = store.saveBinding(draft("p1", "test", "h1"));
        store.confirmBinding(binding.id(), "u1", System.currentTimeMillis() + 3_600_000L);
        assertTrue(store.binding(binding.id()).orElseThrow().usable(), "解绑前应可用");

        EnvBinding revoked = store.revokeBinding(binding.id(), "u2").orElseThrow();

        assertEquals(EnvBinding.STATUS_REVOKED, revoked.status());
        assertTrue(revoked.revoked());
        assertFalse(revoked.usable(), "解绑后不能再用于部署");
        // 关键：记录没被删除，历史仍查得到
        assertTrue(store.binding(binding.id()).isPresent(), "解绑不能删记录");
        assertEquals(1, store.bindings("p1", "test", 20).size());
        assertTrue(store.latestConfirmedBinding("p1", "test").isEmpty(),
                "已解绑的绑定不能作为最新可用绑定");
    }

    @Test
    @DisplayName("解绑不存在的绑定返回空，不抛异常")
    void revokeMissingIsEmpty() {
        assertTrue(store().revokeBinding("bind-missing", "u1").isEmpty());
        assertTrue(store().revokeBinding("", "u1").isEmpty());
    }

    @Test
    @DisplayName("limit 生效且被上限截断，避免长历史拖慢首屏")
    void limitIsBounded() {
        DbEnvironmentStore store = store();
        for (int i = 0; i < 5; i++) {
            store.saveBinding(draft("p1", "test", "h" + i));
        }

        assertEquals(2, store.bindings("p1", "test", 2).size());
        assertEquals(5, store.bindings("p1", "test", 0).size(), "0 = 默认条数");
        assertEquals(5, store.bindings("p1", "test", 10_000).size(), "超上限按上限截断，不报错");
    }
}
