package top.rongx.agent.infra.recipe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配方解析优先级与回退（方案 D 的核心行为）。 */
class RecipeActionResolverTest {

    private final RecipeActionResolver resolver = new RecipeActionResolver();

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("ci/actions.yml 优先于 Makefile")
    void ciActionsWinsOverMakefile(@TempDir Path root) throws Exception {
        write(root, "ci/actions.yml", """
                actions:
                  backend_build:
                    run: ["echo", "from-yml"]
                    produce: ["out/*.txt"]
                    timeout: 5m
                """);
        write(root, "Makefile", """
                backend-build:
                \techo from-make
                """);

        ActionSpec spec = resolver.resolve(root, ActionId.BACKEND_BUILD).orElseThrow();
        assertEquals(ActionSource.Kind.CI_ACTIONS, spec.source().kind());
        assertEquals(List.of("echo", "from-yml"), spec.steps().get(0));
        assertEquals(List.of("out/*.txt"), spec.produce());
        assertEquals(Duration.ofMinutes(5), spec.timeout());
    }

    @Test
    @DisplayName("无 ci/actions.yml 时回退到 Makefile，并校验 target 存在")
    void fallsBackToMakefile(@TempDir Path root) throws Exception {
        write(root, "Makefile", """
                backend-build:
                \techo hi

                other:
                \techo other
                """);

        ActionSpec spec = resolver.resolve(root, ActionId.BACKEND_BUILD).orElseThrow();
        assertEquals(ActionSource.Kind.MAKEFILE, spec.source().kind());
        assertEquals(List.of("make", "backend-build"), spec.steps().get(0));
    }

    @Test
    @DisplayName("Makefile 里没有对应 target 时回退到内置默认")
    void makefileWithoutTargetFallsBackToBuiltin(@TempDir Path root) throws Exception {
        write(root, "Makefile", """
                unrelated:
                \techo hi
                """);

        ActionSpec spec = resolver.resolve(root, ActionId.BACKEND_BUILD).orElseThrow();
        assertEquals(ActionSource.Kind.BUILTIN, spec.source().kind());
        // 内置默认：build 只打包（测试由 test 阶段/verify 负责），避免两遍编译
        assertEquals(List.of("mvn", "-B", "-q", "-DskipTests", "package"), spec.steps().get(0));
    }

    @Test
    @DisplayName("Taskfile 优先于 Makefile")
    void taskfileWinsOverMakefile(@TempDir Path root) throws Exception {
        write(root, "Taskfile.yml", """
                version: '3'
                tasks:
                  backend-build:
                    cmds:
                      - echo hi
                """);
        write(root, "Makefile", """
                backend-build:
                \techo hi
                """);

        ActionSpec spec = resolver.resolve(root, ActionId.BACKEND_BUILD).orElseThrow();
        assertEquals(ActionSource.Kind.TASKFILE, spec.source().kind());
        assertEquals(List.of("task", "backend-build"), spec.steps().get(0));
    }

    @Test
    @DisplayName("完全没有配方时用内置默认")
    void builtinWhenNoRecipe(@TempDir Path root) {
        ActionSpec spec = resolver.resolve(root, ActionId.BACKEND_BUILD).orElseThrow();
        assertEquals(ActionSource.Kind.BUILTIN, spec.source().kind());
        assertTrue(spec.source().ref().startsWith("builtin#"));

        // 没有内置默认的动作返回空
        assertTrue(resolver.resolve(root, ActionId.STACK_DEPLOY).isEmpty());
    }

    @Test
    @DisplayName("多命令：run 为二维数组")
    void multipleSteps(@TempDir Path root) throws Exception {
        write(root, "ci/actions.yml", """
                actions:
                  frontend_build:
                    run:
                      - ["pnpm", "install", "--frozen-lockfile"]
                      - ["pnpm", "build"]
                """);
        ActionSpec spec = resolver.resolve(root, ActionId.FRONTEND_BUILD).orElseThrow();
        assertEquals(2, spec.steps().size());
        assertEquals(List.of("pnpm", "build"), spec.steps().get(1));
    }

    @Test
    @DisplayName("YAML 损坏时降级而不是崩")
    void brokenYamlFallsThrough(@TempDir Path root) throws Exception {
        write(root, "ci/actions.yml", "actions: [ this is : not valid : yaml");
        Optional<ActionSpec> spec = resolver.resolve(root, ActionId.BACKEND_BUILD);
        assertTrue(spec.isPresent());
        assertEquals(ActionSource.Kind.BUILTIN, spec.get().source().kind());
    }

    @Test
    @DisplayName("hasRecipe 正确识别三种来源")
    void hasRecipe(@TempDir Path root) throws Exception {
        assertFalse(resolver.hasRecipe(root));
        write(root, "Makefile", "x:\n\techo\n");
        assertTrue(resolver.hasRecipe(root));
    }
}
