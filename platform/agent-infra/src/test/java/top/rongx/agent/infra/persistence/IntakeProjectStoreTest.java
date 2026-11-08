package top.rongx.agent.infra.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接入项目 SQLite 存储：落库、重启恢复、两源合并、下线。 */
class IntakeProjectStoreTest {

    @TempDir
    Path ws;

    private IntakeProjectStore newStore() {
        return new IntakeProjectStore(ws.resolve("_platform/projects.db"));
    }

    private IntakeProjectStore.IntakeProject shop() {
        return new IntakeProjectStore.IntakeProject(
                "SHOP", "商城", "https://gitee.com/x/shop.git", "gitee",
                "ruoyi-plus", 8100, 8199, "REMOTE", null, "MANAGED");
    }

    private IntakeProjectStore.IntakeProject agt() {
        return new IntakeProjectStore.IntakeProject(
                "AGT", "AGT", null, "local", "generic-java",
                8200, 8299, "LOCAL", "E:\\CODE\\agentscope-ruoyi", "DIRECT");
    }

    @Test
    @DisplayName("S1 落库 + 读回：项目与环境完整")
    void upsertAndRead() {
        try (IntakeProjectStore s = newStore()) {
            s.upsert(shop(), List.of(
                    new ProjectEnv("SHOP", "test", "http://127.0.0.1:8180", "/opt/apps/shop")));

            Optional<IntakeProjectStore.IntakeProject> back = s.find("SHOP");
            assertTrue(back.isPresent());
            assertEquals("REMOTE", back.get().source());
            assertEquals("https://gitee.com/x/shop.git", back.get().repoUrl());
            assertEquals(1, s.findEnvs("SHOP").size());
            assertEquals("test", s.findEnvs("SHOP").get(0).env());
        }
    }

    @Test
    @DisplayName("S2 重启恢复：新 store 实例（模拟进程重启）读到同一数据")
    void survivesRestart() {
        IntakeProjectStore first = newStore();
        first.upsert(agt(), List.of());
        first.close();

        IntakeProjectStore second = newStore(); // 同一 db 文件，新连接 = 重启
        assertTrue(second.find("AGT").isPresent(), "重启后应能读到接入项目");
        assertEquals("E:\\CODE\\agentscope-ruoyi", second.find("AGT").get().localPath());
        second.close();
    }

    @Test
    @DisplayName("S3 LOCAL 语义：localPath 保留、isLocal 判断")
    void localSemantics() {
        try (IntakeProjectStore s = newStore()) {
            s.upsert(agt(), List.of());
            var p = s.find("AGT").orElseThrow();
            assertTrue(p.isLocal());
            assertEquals("E:\\CODE\\agentscope-ruoyi", p.localPath());
            assertTrue(s.find("AGT").orElseThrow().repoUrl() == null);
        }
    }

    @Test
    @DisplayName("S4 两源合并：预置优先、接入补齐、isIntake 判断")
    void compositeMerge() {
        // 预置源：只有 RY
        ProjectsProperties props = new ProjectsProperties(List.of(
                new ProjectsProperties.ProjectDef("RY", "RuoYi", "https://gitee.com/x/ry.git",
                        "gitee", "ruoyi-plus", 8080, 8099, List.of(), null, null)));
        InMemoryProjectRepository preset = new InMemoryProjectRepository(props);

        try (IntakeProjectStore s = newStore()) {
            s.upsert(shop(), List.of());

            CompositeProjectRepository composite = new CompositeProjectRepository(preset, s);

            // 列表 = RY + SHOP
            assertEquals(2, composite.findAll().size());
            // 预置优先
            assertTrue(composite.findById("RY").isPresent());
            assertTrue(composite.findById("SHOP").isPresent());
            assertTrue(composite.isIntake("SHOP"));
            assertFalse(composite.isIntake("RY"));
            // LOCAL localPath 视图
            assertTrue(composite.localPathOf("SHOP").isEmpty());
        }
    }

    @Test
    @DisplayName("S5 下线：delete 后消失，环境一并清理")
    void delete() {
        try (IntakeProjectStore s = newStore()) {
            s.upsert(shop(), List.of());
            assertTrue(s.delete("SHOP"));
            assertTrue(s.find("SHOP").isEmpty());
            assertTrue(s.findEnvs("SHOP").isEmpty());
            assertFalse(s.delete("SHOP"), "重复删除返回 false");
        }
    }
}
