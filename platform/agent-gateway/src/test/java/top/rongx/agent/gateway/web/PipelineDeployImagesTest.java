package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.deploy.DeployGate;

/**
 * 生产的可部署选项必须来自「测试环境成功运行」的版本。
 *
 * <p>回归背景：选项原来取生产自己的版本账本，两个后果叠加成"生产点不动"：
 * <ol>
 *   <li>生产从未部署时账本为空 → 界面一个选项都没有；</li>
 *   <li>账本里的旧版本多半没有测试证据 → 点了被 DeployGate 判 E-601。</li>
 * </ol>
 */
class PipelineDeployImagesTest {

    private static final String PROJECT = "RY";

    private PipelineController controller(PipelineEngine engine) {
        return new PipelineController(
                engine, "",
                mock(top.rongx.agent.domain.settings.DeployServerRepository.class),
                mock(DeployGate.class),
                mock(top.rongx.agent.gateway.settings.EnvBindingService.class),
                mock(CurrentUser.class),
                mock(top.rongx.agent.domain.port.ProjectRepoLocator.class));
    }

    /** 版本账本里的一条：tag 形如 ci-ry-4ac8ba6，gitSha 用于反查提交。 */
    private static PipelineEngine.ImageVersion version(String tag, String sha) {
        return new PipelineEngine.ImageVersion(tag, "sha256:" + tag, 1024L, "0.1.0",
                sha, "run-" + tag, "PUSHED", 1000L, 1000L, "ui:alice", 1);
    }

    /** 取响应里某个 tag 的行，便于逐条断言 current / previous。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> out, String tag) {
        List<Map<String, Object>> images = (List<Map<String, Object>>) out.get("images");
        return images.stream()
                .filter(r -> tag.equals(r.get("tag")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("响应里没有 tag=" + tag + "：" + images));
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(Map<String, Object> out) {
        return ((List<Map<String, Object>>) out.get("images")).stream()
                .map(r -> String.valueOf(r.get("tag")))
                .toList();
    }

    @Test
    @DisplayName("生产：选项来自测试账本，sourceEnv 标注为 test")
    void prodOptionsComeFromTestLedger() {
        PipelineEngine engine = mock(PipelineEngine.class);
        when(engine.deployImages(eq(PROJECT), eq("test")))
                .thenReturn(List.of(version("ci-ry-2222222", "bbbbbbb")));
        when(engine.deployImages(eq(PROJECT), eq("prod"))).thenReturn(List.of());

        Map<String, Object> out = controller(engine).deployImages(PROJECT, "prod");

        assertEquals("test", out.get("sourceEnv"), "生产的选项来源必须标注为测试环境");
        assertEquals(List.of("ci-ry-2222222"), tags(out),
                "生产从未部署时也不能一个选项都没有，应列出测试跑通过的版本");
    }

    @Test
    @DisplayName("生产：当前/上一版按生产实际在跑的标注，不按测试列表顺序")
    void prodBadgesFollowProdLedger() {
        // 测试跑通过 A、B；生产当前跑的是 A（旧版），B 尚未发到生产
        PipelineEngine engine = mock(PipelineEngine.class);
        when(engine.deployImages(eq(PROJECT), eq("test"))).thenReturn(List.of(
                version("ci-ry-bbbbbb", "bbbbbbb"),
                version("ci-ry-aaaaaa", "aaaaaaa")));
        when(engine.deployImages(eq(PROJECT), eq("prod"))).thenReturn(List.of(
                version("ci-ry-aaaaaa", "aaaaaaa")));

        Map<String, Object> out = controller(engine).deployImages(PROJECT, "prod");

        assertEquals(List.of("ci-ry-bbbbbb", "ci-ry-aaaaaa"), tags(out),
                "选项按测试账本顺序，生产跑过的去重后仍在其中");
        assertFalse((Boolean) row(out, "ci-ry-bbbbbb").get("current"),
                "生产还没发过 B，不能标成当前");
        assertTrue((Boolean) row(out, "ci-ry-aaaaaa").get("current"),
                "生产实际在跑 A，应标当前");
        assertFalse((Boolean) row(out, "ci-ry-aaaaaa").get("previous"),
                "生产只有一版时不存在上一版");
    }

    @Test
    @DisplayName("生产：账本里有但测试没有的老版本仍列出，避免看不到当前在跑哪一版")
    void prodKeepsProdOnlyLegacyVersions() {
        PipelineEngine engine = mock(PipelineEngine.class);
        when(engine.deployImages(eq(PROJECT), eq("test")))
                .thenReturn(List.of(version("ci-ry-2222222", "bbbbbbb")));
        // 生产在"必须有测试证据"规则上线前发过一个版本，测试账本里没有
        when(engine.deployImages(eq(PROJECT), eq("prod")))
                .thenReturn(List.of(version("ci-ry-legacy", "ccccccc")));

        Map<String, Object> out = controller(engine).deployImages(PROJECT, "prod");

        assertEquals(List.of("ci-ry-2222222", "ci-ry-legacy"), tags(out),
                "老版本补在尾部，但不能挤掉测试通过的选项");
        assertTrue((Boolean) row(out, "ci-ry-legacy").get("current"),
                "老版本正是当前在跑的，必须标当前");
    }

    @Test
    @DisplayName("测试环境口径不变：仍取本环境账本，按列表顺序标当前/上一版")
    void testEnvKeepsOwnLedgerOrder() {
        PipelineEngine engine = mock(PipelineEngine.class);
        when(engine.deployImages(eq(PROJECT), eq("test"))).thenReturn(List.of(
                version("ci-ry-2222222", "bbbbbbb"),
                version("ci-ry-1111111", "aaaaaaa")));

        Map<String, Object> out = controller(engine).deployImages(PROJECT, "test");

        assertEquals("test", out.get("sourceEnv"));
        assertEquals(List.of("ci-ry-2222222", "ci-ry-1111111"), tags(out));
        assertTrue((Boolean) row(out, "ci-ry-2222222").get("current"));
        assertTrue((Boolean) row(out, "ci-ry-1111111").get("previous"));
    }

    @Test
    @DisplayName("dev 环境不受影响：sourceEnv 就是自身")
    void devEnvUnaffected() {
        PipelineEngine engine = mock(PipelineEngine.class);
        when(engine.deployImages(eq(PROJECT), eq("dev")))
                .thenReturn(List.of(version("ci-ry-3333333", "ddddddd")));

        Map<String, Object> out = controller(engine).deployImages(PROJECT, "dev");

        assertEquals("dev", out.get("sourceEnv"));
        assertEquals(List.of("ci-ry-3333333"), tags(out));
    }
}
