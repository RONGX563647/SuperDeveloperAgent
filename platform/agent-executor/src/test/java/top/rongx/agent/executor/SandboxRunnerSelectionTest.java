package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;

/**
 * 阶段执行器选择：哪些动作必须上宿主、哪些配方有资格上宿主。
 *
 * <p>背景：image_package 的配方是 {@code docker build …}，被包进沙箱容器后容器里没有
 * docker CLI，只会得到 exit 127。这里把判定抽成纯函数便于回归。
 */
class SandboxRunnerSelectionTest {

    private static ActionSpec spec(ActionSource source) {
        return new ActionSpec(ActionId.IMAGE_PACKAGE,
                List.of(List.of("docker", "build", "-f", "Dockerfile", "-t", "x", ".")),
                List.of(), Duration.ofMinutes(5), source);
    }

    @Test
    @DisplayName("只有 image_package 需要宿主 Docker")
    void onlyImagePackageNeedsHostDocker() {
        assertTrue(BuiltInPipelineEngine.needsHostDocker(ActionId.IMAGE_PACKAGE));
        assertFalse(BuiltInPipelineEngine.needsHostDocker(ActionId.BACKEND_BUILD));
    }

    @Test
    @DisplayName("内置配方与平台侧配方可以上宿主，仓库配方一律不行")
    void hostTrustFollowsDefinitionOrigin() {
        assertTrue(BuiltInPipelineEngine.trustedForHost(
                spec(ActionSource.builtin("image_package")), PipelineDefinitionResolver.Source.REPO),
                "内置配方与定义来源无关");

        ActionSource repoYml = ActionSource.of(ActionSource.Kind.CI_ACTIONS,
                "ci/actions.yml#image_package", "0123456789abcdef0123456789abcdef01234567");
        assertFalse(BuiltInPipelineEngine.trustedForHost(
                spec(repoYml), PipelineDefinitionResolver.Source.REPO),
                "仓库定义里的 docker 配方不能直接拿到宿主执行权");
        assertTrue(BuiltInPipelineEngine.trustedForHost(
                spec(repoYml), PipelineDefinitionResolver.Source.PLATFORM),
                "平台侧定义引用的配方由平台维护");

        assertFalse(BuiltInPipelineEngine.trustedForHost(null, PipelineDefinitionResolver.Source.PLATFORM));
        assertFalse(BuiltInPipelineEngine.trustedForHost(
                spec(repoYml), null), "来源不明的定义默认不可信");
    }
}
