package top.rongx.agent.infra.pipeline;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.deploy.DeployRecipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境脚本存储：版本递增、current 指针、历史回滚。
 */
class FileRecipeStoreTest {

    private static final String P = "DEMO";

    @Test
    @DisplayName("保存两个版本后 current 指向最新，历史可回滚")
    void versionsAndActivate(@TempDir Path workspace) {
        FileRecipeStore store = new FileRecipeStore(workspace.toString());

        DeployRecipe v1 = store.save(recipe("name: v1\n", "run-1", "aaaaaaa"));
        assertEquals(1, v1.version());
        assertEquals(1, store.current(P, "test").orElseThrow().version());

        DeployRecipe v2 = store.save(recipe("name: v2\n", "run-2", "bbbbbbb"));
        assertEquals(2, v2.version());
        assertEquals(2, store.current(P, "test").orElseThrow().version());
        assertEquals(2, store.versions(P, "test").size());

        assertTrue(store.activate(P, "test", 1));
        DeployRecipe current = store.current(P, "test").orElseThrow();
        assertEquals(1, current.version());
        assertTrue(current.pipelineYaml().contains("v1"));
        assertEquals("run-1", current.evidenceRunId());

        // 定义根目录形如 <ws>/<project>/recipes/<env>/vN，其下有 ci/pipeline.yml
        Path root = store.rootOf(P, "test", 1);
        assertTrue(java.nio.file.Files.isRegularFile(root.resolve("ci/pipeline.yml")));
    }

    @Test
    @DisplayName("环境之间互不干扰；不存在的版本返回空")
    void isolatedByEnv(@TempDir Path workspace) {
        FileRecipeStore store = new FileRecipeStore(workspace.toString());
        store.save(recipe("name: t\n", "run-t", "aaaaaaa"));
        assertTrue(store.current(P, "prod").isEmpty());
        assertTrue(store.current(P, "test").isPresent());
        assertFalse(store.activate(P, "test", 99));
        assertTrue(store.version(P, "test", 99).isEmpty());
    }

    @Test
    @DisplayName("非法 projectId / env 被拒绝（防路径穿越）")
    void rejectsUnsafeIds(@TempDir Path workspace) {
        FileRecipeStore store = new FileRecipeStore(workspace.toString());
        try {
            store.current("../etc", "test");
            throw new AssertionError("应拒绝非法 projectId");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static DeployRecipe recipe(String yaml, String evidence, String sha) {
        return new DeployRecipe(P, "test", 0, yaml, null, evidence, sha, 0L, "agent");
    }
}
