package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.PipelineEngine.ImageVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 镜像版本账本：按环境独立、最近部署优先、同一 tag 覆盖、删历史不影响账本。 */
class ImageVersionStoreTest {

    private static ImageVersion version(String tag, String sha, long deployedAt, String by) {
        return new ImageVersion(tag, "sha256:" + tag, 1024, "test-v1", sha, "run-1",
                "SUCCEEDED", 1000, deployedAt, by, 1);
    }

    @Test
    @DisplayName("同一 tag 重复登记只保留一条，并更新部署人与部署时间")
    void upsertReplacesSameTag() throws Exception {
        Path root = Files.createTempDirectory("img-ledger");
        ImageVersionStore store = ImageVersionStore.file(root);
        store.upsert("RY", "test", version("ci-test-aaa1111", "aaa1111", 100, "ui:admin"));
        store.upsert("RY", "test", version("ci-test-aaa1111", "aaa1111", 900, "agent"));

        List<ImageVersion> rows = store.list("RY", "test");
        assertEquals(1, rows.size());
        assertEquals(900, rows.get(0).deployedAt());
        assertEquals("agent", rows.get(0).deployedBy());
    }

    @Test
    @DisplayName("按环境独立记账：test 的版本不会出现在 prod")
    void isolatedPerEnv() throws Exception {
        Path root = Files.createTempDirectory("img-env");
        ImageVersionStore store = ImageVersionStore.file(root);
        store.upsert("RY", "test", version("ci-test-aaa1111", "aaa1111", 100, "ui:admin"));
        store.upsert("RY", "prod", version("ci-prod-bbb2222", "bbb2222", 200, "ui:admin"));

        assertEquals("ci-test-aaa1111", store.current("RY", "test").orElseThrow().tag());
        assertEquals("ci-prod-bbb2222", store.current("RY", "prod").orElseThrow().tag());
        assertTrue(store.find("RY", "test", "ci-prod-bbb2222").isEmpty());
    }

    @Test
    @DisplayName("current = 最近部署，previous = 第二近；移除后持久化不丢其它版本")
    void orderAndPersistence() throws Exception {
        Path root = Files.createTempDirectory("img-order");
        ImageVersionStore store = ImageVersionStore.file(root);
        store.upsert("RY", "test", version("ci-test-a", "aaa1111", 100, "ui:admin"));
        store.upsert("RY", "test", version("ci-test-b", "bbb2222", 300, "ui:admin"));
        store.upsert("RY", "test", version("ci-test-c", "ccc3333", 200, "ui:admin"));

        // 重新打开（模拟重启）后顺序仍然稳定
        ImageVersionStore reopened = ImageVersionStore.file(root);
        List<ImageVersion> rows = reopened.list("RY", "test");
        assertEquals(List.of("ci-test-b", "ci-test-c", "ci-test-a"),
                rows.stream().map(ImageVersion::tag).toList());
        assertEquals("ci-test-b", reopened.current("RY", "test").orElseThrow().tag());

        reopened.remove("RY", "test", "ci-test-b");
        assertEquals(2, reopened.list("RY", "test").size());
        assertEquals("ci-test-c", reopened.current("RY", "test").orElseThrow().tag());
        assertTrue(reopened.find("RY", "test", "ci-test-b").isEmpty());
        assertFalse(reopened.list("RY", "test").isEmpty());
    }
}
