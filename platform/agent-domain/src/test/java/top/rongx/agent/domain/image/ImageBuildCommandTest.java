package top.rongx.agent.domain.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RecipeException;

/**
 * 镜像构建命令的唯一来源。
 *
 * <p>守住的回归：三处字面量曾经漂移成 {@code docker build} 与 {@code docker buildx build --load}
 * 并存——BuildKit 不带 {@code --load} 时镜像只留在构建缓存，{@code docker run} 报 No such image。
 */
class ImageBuildCommandTest {

    @Test
    @DisplayName("标准构建必须带 --load（否则镜像不进 docker 镜像库）")
    void standardAlwaysLoads() {
        List<String> argv = ImageBuildCommand.standard("ci-abc1234");

        assertEquals("docker", argv.get(0));
        assertEquals("buildx", argv.get(1));
        assertTrue(argv.contains("--load"), "缺 --load 会让 docker run 找不到镜像：" + argv);
        assertTrue(argv.contains("--load"), argv.toString());
        assertEquals("ci-abc1234", argv.get(argv.indexOf("-t") + 1));
        assertEquals(".", argv.get(argv.size() - 1), "构建上下文必须是最后一个位置参数：" + argv);
        assertEquals(List.of("docker", "buildx", "build", "--load", "-q", "-t", "ci-abc1234", "."),
                argv);
    }

    @Test
    @DisplayName("内置配方的 ${tag} 占位符必须被接受（真值由配方引擎注入）")
    void placeholderTagAccepted() {
        List<String> argv = ImageBuildCommand.standard("${tag}");
        assertEquals("${tag}", argv.get(argv.indexOf("-t") + 1));
    }

    @Test
    @DisplayName("非法 tag（空、含空白、以 - 开头、占位符内容异常）一律拒绝")
    void invalidTagRejected() {
        // 一律抛带错误码的平台异常（E-201）：裸 IllegalArgumentException 无法被页面/自愈分类
        for (String bad : List.of("", "a b", "-x", "${ta g}", "${}")) {
            RecipeException e = assertThrows(RecipeException.class,
                    () -> ImageBuildCommand.standard(bad), bad);
            assertEquals(ErrorCode.RECIPE_INVALID_INPUT, e.errorCode(), bad);
        }
    }

    @Test
    @DisplayName("子目录 Dockerfile：-f 在 -t 之前，上下文紧跟其后")
    void withDockerfileOrdering() {
        List<String> argv = ImageBuildCommand.withDockerfile("backend/Dockerfile", "${tag}", "backend");

        assertEquals("backend/Dockerfile", argv.get(argv.indexOf("-f") + 1));
        assertEquals("${tag}", argv.get(argv.indexOf("-t") + 1));
        assertEquals("backend", argv.get(argv.size() - 1));
        assertTrue(argv.indexOf("-f") < argv.indexOf("-t"), "-f 要在 -t 之前：" + argv);
        // 空上下文 → "."
        assertEquals(".", ImageBuildCommand.withDockerfile("Dockerfile", "t1", null)
                .get(ImageBuildCommand.withDockerfile("Dockerfile", "t1", null).size() - 1));
    }

    @Test
    @DisplayName("路径不允许以 - 开头（会被 docker 当选项）")
    void dockerfileCannotLookLikeOption() {
        assertThrows(RecipeException.class,
                () -> ImageBuildCommand.withDockerfile("--privileged", "t", "."));
        assertThrows(RecipeException.class,
                () -> ImageBuildCommand.withDockerfile("Dockerfile", "t", "-v /:/host"));
    }

    @Test
    @DisplayName("render 产出可写进 ci/actions.yml 的单行命令")
    void renderSingleLine() {
        assertEquals("docker buildx build --load -q -t ${tag} .",
                ImageBuildCommand.render(ImageBuildCommand.standard("${tag}")));
    }
}
