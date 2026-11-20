package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.domain.image.ImageReadinessStore;
import top.rongx.agent.gateway.auth.CurrentUser;

/** 镜像就绪运维视图：把"哪台机器上哪个镜像卡住了、还要等多久"直接摆出来。 */
class SandboxImageControllerTest {

    /** 只读视图不碰权限逻辑：用 mock 绕过 Sa-Token 会话（真权限校验由拦截器负责）。 */
    private static final CurrentUser ANY_USER = org.mockito.Mockito.mock(CurrentUser.class);

    @Test
    @DisplayName("退避中的失败镜像 → 状态、原因与剩余重试秒数")
    void failedImageShowsBackoff() {
        long now = System.currentTimeMillis();
        ImageReadiness failed = new ImageReadiness("host-a", "node:22-bookworm",
                ImageReadiness.Status.FAILED, "mirror:localhost:5000", null, 3,
                "E-302 执行停滞（60s 无输出）", now + 120_000, now);
        SandboxImageController controller = controller(new MemoryStore(List.of(failed)));

        Map<String, Object> row = controller.images().get(0);

        assertEquals("host-a", row.get("host"));
        assertEquals("node:22-bookworm", row.get("image"));
        assertEquals("FAILED", row.get("status"));
        assertEquals(3, row.get("attempts"));
        assertTrue(((Number) row.get("retryInSeconds")).longValue() > 60,
                "退避中要给出剩余秒数：" + row);
    }

    @Test
    @DisplayName("就绪镜像 → READY 且不需要重试")
    void readyImageHasNoRetry() {
        ImageReadiness ready = new ImageReadiness("host-a", "maven:3.9-eclipse-temurin-21",
                ImageReadiness.Status.READY, "local", "sha256:abc", 0, null, 0,
                System.currentTimeMillis());
        SandboxImageController controller = controller(new MemoryStore(List.of(ready)));

        Map<String, Object> row = controller.images().get(0);

        assertEquals("READY", row.get("status"));
        assertEquals(0L, row.get("retryInSeconds"));
        assertEquals("sha256:abc", row.get("digest"));
    }

    @Test
    @DisplayName("没有存储（进程模式 / 无库）→ 空列表而不是报错")
    void missingStoreReturnsEmpty() {
        SandboxImageController controller = new SandboxImageController(
                new DefaultListableBeanFactory().getBeanProvider(ImageReadinessStore.class), ANY_USER);

        assertTrue(controller.images().isEmpty());
    }

    private static SandboxImageController controller(ImageReadinessStore store) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("readiness", store);
        return new SandboxImageController(factory.getBeanProvider(ImageReadinessStore.class), ANY_USER);
    }

    private record MemoryStore(List<ImageReadiness> rows) implements ImageReadinessStore {

        @Override
        public Optional<ImageReadiness> find(String host, String image) {
            return rows.stream()
                    .filter(r -> r.host().equals(host) && r.image().equals(image))
                    .findFirst();
        }

        @Override
        public void save(ImageReadiness readiness) {
            throw new UnsupportedOperationException("只读视图测试不写入");
        }

        @Override
        public List<ImageReadiness> list(String host) {
            return rows.stream().filter(r -> r.host().equals(host)).toList();
        }

        @Override
        public List<ImageReadiness> listAll() {
            return rows;
        }
    }
}
