package top.rongx.agent.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.executor.ProcessActionRunner;
import top.rongx.agent.infra.recipe.RecipeActionResolver;
import top.rongx.agent.tools.ActionCommandTools;
import top.rongx.agent.tools.ActionQueryTools;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D 的端到端演示：直接打印 agent 会看到的工具输出。
 *
 * <p>不经过模型（没有 API Key 也能跑），验证的是"工具面 + 配方 + 执行器"这条链路。
 * 用一个 Java 单文件程序真实产出 {@code target/app.jar}，因此产物与 sha256 都是真的。
 */
class ActionWorkflowDemoTest {

    /** 一个会写文件的 Java 单文件程序（Java 11+ 源码模式直接运行，无需编译）。 */
    private static final String MAKE_JAR = """
            import java.nio.file.*;
            public class MakeJar {
                public static void main(String[] a) throws Exception {
                    Files.createDirectories(Path.of("target"));
                    Files.writeString(Path.of("target/app.jar"), "demo-jar-content-" + System.currentTimeMillis());
                    System.out.println("built target/app.jar");
                }
            }
            """;

    private static final String ACTIONS_YML = """
            actions:
              backend_build:
                run: ["java", "MakeJar.java"]
                produce: ["target/*.jar"]
                timeout: 3m
              frontend_build:
                run: ["java", "-version"]
                timeout: 2m
            """;

    @Test
    @DisplayName("列动作 → 看计划 → 执行 → 产物校验（含 L3 与生产环境拦截）")
    void demo(@TempDir Path root) throws Exception {
        // 模拟项目工作区：<workspaceRoot>/<projectId>/ 里带 ci/actions.yml（人写的、review 过的）
        Path projectDir = root.resolve("RY");
        Files.createDirectories(projectDir.resolve("ci"));
        Files.writeString(projectDir.resolve("ci/actions.yml"), ACTIONS_YML);
        Files.writeString(projectDir.resolve("MakeJar.java"), MAKE_JAR);

        var resolver = new RecipeActionResolver();
        var runner = new ProcessActionRunner();
        var queryTools = new ActionQueryTools(resolver, root.toString());
        var commandTools = new ActionCommandTools(resolver, runner, root.toString());
        var testEnv = new ProjectContext("RY", "test", "dev-smoke", "https://gitee.com/x/y.git");
        var prodEnv = new ProjectContext("RY", "prod", "dev-smoke", "https://gitee.com/x/y.git");

        System.out.println("\n========== 1) list_actions ==========");
        String listed = queryTools.listActions(testEnv);
        System.out.println(listed);
        assertTrue(listed.contains("backend_build"));
        assertTrue(listed.contains("ci/actions.yml#backend_build"));

        System.out.println("\n========== 2) plan_action(backend_build) ==========");
        String plan = queryTools.planAction("backend_build", testEnv);
        System.out.println(plan);
        assertTrue(plan.contains("java MakeJar.java"), "计划里必须能看到命令原文");

        System.out.println("\n========== 3) plan_action(stack_deploy) ==========");
        System.out.println(queryTools.planAction("stack_deploy", testEnv));

        System.out.println("\n========== 4) run_action(backend_build) ==========");
        String ran = commandTools.runAction("backend_build", testEnv);
        System.out.println(ran);
        assertTrue(ran.contains("status: SUCCEEDED"), "构建应成功");
        assertTrue(ran.contains("target/app.jar"), "应识别到产物");
        assertTrue(ran.contains("sha256:"), "产物应有摘要");

        System.out.println("\n========== 5) run_action(backend_build) 第二次（产物变化 → 摘要变化）==========");
        System.out.println(commandTools.runAction("backend_build", testEnv));

        System.out.println("\n========== 6) run_action(stack_deploy) → L3 拦截 ==========");
        String l3 = commandTools.runAction("stack_deploy", testEnv);
        System.out.println(l3);
        assertTrue(l3.contains("L3"), "L3 动作必须被拒");

        System.out.println("\n========== 7) run_action(backend_build) 在 prod → 拦截 ==========");
        String prod = commandTools.runAction("backend_build", prodEnv);
        System.out.println(prod);
        assertTrue(prod.contains("生产环境"), "生产环境必须被拒");

        System.out.println("\n========== 8) 未知动作 ==========");
        System.out.println(commandTools.runAction("deploy_everything", testEnv));
    }
}
