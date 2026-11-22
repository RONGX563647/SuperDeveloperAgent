package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.PipelineEngine.ImageVersion;

/**
 * 按版本部署的账本环境口径。
 *
 * <p>prod 的版本选项来自 test 账本（{@code EnvironmentPromotion.requiresTestEvidence}），
 * 触发预检与 sha 反查都走 {@code ledgerEnv(env)}。过去只有「复用镜像元信息」这一处
 * 直接按 {@code state.env} 查 prod 账本，导致 digest 写空串、version 退化成 tag——
 * 界面上就表现为「生产环境部署镜像明明在却说不在」。
 */
class DeployImageLedgerEnvTest {

    private static final String PROJECT = "SUPERDEVELOPERAG";
    private static final String TAG = "ci-test-220a8d2";

    private static ImageVersion version(String tag, String digest, long size, String version,
                                        String sha, String by) {
        return new ImageVersion(tag, digest, size, version, sha, "run-7962b8b5",
                "SUCCEEDED", 1000, 2000, by, 53);
    }

    @Test
    @DisplayName("prod 按版本部署从 test 账本取元数据：digest / size / version 不退化")
    void prodReuseReadsTestLedger() throws Exception {
        Path root = Files.createTempDirectory("prod-reuse-ledger");
        ImageVersionStore store = ImageVersionStore.file(root);
        store.upsert(PROJECT, "test", version(TAG, "sha256:29fcb2e2", 667_455_692L,
                "test-v53", "220a8d24", "ui:admin"));

        ImageVersion known = BuiltInPipelineEngine.reusedImageVersion(store, PROJECT, "prod", TAG);

        assertNotNull(known, "prod 版本来自 test 账本，必须能查到");
        assertEquals("sha256:29fcb2e2", known.digest());
        assertEquals(667_455_692L, known.sizeBytes());
        assertEquals("test-v53", known.version());
        assertEquals("220a8d24", known.gitSha());
    }

    @Test
    @DisplayName("prod 账本里同 tag 的空壳记录不能遮蔽 test 账本的真实版本")
    void prodLedgerDoesNotShadowTest() throws Exception {
        Path root = Files.createTempDirectory("prod-shadow-ledger");
        ImageVersionStore store = ImageVersionStore.file(root);
        store.upsert(PROJECT, "test", version(TAG, "sha256:real", 4096, "test-v53",
                "220a8d24", "ui:admin"));
        // 模拟历史遗留：prod 账本里有一条同 tag、元信息为空的记录
        store.upsert(PROJECT, "prod", version(TAG, "", 0, TAG, "220a8d24", "agent"));

        ImageVersion known = BuiltInPipelineEngine.reusedImageVersion(store, PROJECT, "prod", TAG);

        assertNotNull(known);
        assertEquals("sha256:real", known.digest(), "不能读 prod 账本的空壳");
        assertEquals("test-v53", known.version(), "不能把 tag 当版本号");
    }

    @Test
    @DisplayName("非晋升环境仍按自身账本取；查不到返回 null（不跨环境借用）")
    void testEnvReadsOwnLedger() throws Exception {
        Path root = Files.createTempDirectory("test-own-ledger");
        ImageVersionStore store = ImageVersionStore.file(root);
        store.upsert(PROJECT, "test", version(TAG, "sha256:test", 1024, "test-v1",
                "aaa1111", "ui:admin"));
        store.upsert(PROJECT, "prod", version("ci-prod-other", "sha256:prod", 2048, "prod-v1",
                "bbb2222", "ui:admin"));

        assertEquals("sha256:test", BuiltInPipelineEngine
                .reusedImageVersion(store, PROJECT, "test", TAG).digest());
        assertNull(BuiltInPipelineEngine.reusedImageVersion(store, PROJECT, "test",
                "ci-prod-other"));
        assertNull(BuiltInPipelineEngine.reusedImageVersion(store, PROJECT, "prod", "missing"));
        assertNull(BuiltInPipelineEngine.reusedImageVersion(store, PROJECT, "prod", " "));
    }
}
