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
 * 镜像构建命令的来源可信判定：仓库侧残留的旧配方不允许拿到宿主执行权。
 *
 * <p>回归背景（D12）：`image_package` 的标准实现是 `docker buildx build --load`，
 * 但某个已接入项目的工作区里仍留着历史的 `docker build`（`data/workspaces/.../ci/actions.yml`）。
 * 那份文件不在版本库里（`data/` 被 gitignore），属于运行期数据，平台不修改它；
 * 真正要守住的是：<b>即使它还在，也不能被当成宿主命令执行</b>。
 *
 * <p>注意这里判定的不是"命令对不对"，而是"谁能上宿主"：
 * 仓库内容（`ci/actions.yml` / `Taskfile.yml` / `Makefile`）一律不可信；
 * 平台内置配方与平台侧定义才可信。三份实现曾经漂移成 `build` 与 `buildx` 并存，
 * 统一到 {@link top.rongx.agent.domain.image.ImageBuildCommand} 之后，
 * 可信的那个分支永远只有一种命令形态。
 */
class ImagePackageSourceTrustTest {

    private static ActionSpec spec(ActionSource source, List<String> argv) {
        return new ActionSpec(ActionId.IMAGE_PACKAGE, List.of(argv), List.of(),
                Duration.ofMinutes(20), source);
    }

    @Test
    @DisplayName("内置配方（buildx --load）可上宿主执行")
    void builtinRecipeIsTrusted() {
        ActionSpec builtin = spec(ActionSource.builtin("builtin"),
                top.rongx.agent.domain.image.ImageBuildCommand.standard("${tag}"));

        assertTrue(BuiltInPipelineEngine.trustedForHost(builtin,
                        PipelineDefinitionResolver.Source.REPO),
                "内置配方与仓库定义无关，始终可信");
    }

    @Test
    @DisplayName("仓库里残留的 docker build 配方不给宿主执行权（沙箱边界）")
    void repoRecipeIsNotTrusted() {
        // 就是 data/workspaces 里那份历史残留的形态
        ActionSpec stale = spec(ActionSource.of(ActionSource.Kind.CI_ACTIONS, "ci/actions.yml", "abc1234"),
                List.of("docker", "build", "-f", "Dockerfile", "-t", "${tag}", "."));

        assertFalse(BuiltInPipelineEngine.trustedForHost(stale,
                        PipelineDefinitionResolver.Source.REPO),
                "仓库配方给宿主执行权 = 沙箱被绕过，必须拒绝");
    }

    @Test
    @DisplayName("平台侧定义（非仓库来源）里的同名配方仍可信")
    void platformDefinitionIsTrusted() {
        ActionSpec platform = spec(ActionSource.of(ActionSource.Kind.CI_ACTIONS, "ci/actions.yml", "abc1234"),
                List.of("docker", "build", "-f", "Dockerfile", "-t", "${tag}", "."));

        assertTrue(BuiltInPipelineEngine.trustedForHost(platform,
                        PipelineDefinitionResolver.Source.PLATFORM),
                "平台维护的定义走的是另一条信任链");
        assertFalse(BuiltInPipelineEngine.trustedForHost(platform, null),
                "定义来源未知时按不可信处理");
    }

    @Test
    @DisplayName("标准构建命令天然带 --load：即便是内置配方也不会产出只在缓存里的镜像")
    void builtinRecipeAlwaysLoads() {
        List<String> argv = top.rongx.agent.domain.image.ImageBuildCommand.standard("${tag}");

        assertTrue(argv.contains("--load"), argv.toString());
        // "不是裸 docker build" 要按子命令序列判断：`buildx build` 里本来就含 "build" 这个词
        assertTrue(argv.contains("buildx"), "必须是 buildx build：" + argv);
        assertTrue(argv.indexOf("buildx") < argv.indexOf("build"),
                "buildx 必须是子命令前缀：" + argv);
    }
}
