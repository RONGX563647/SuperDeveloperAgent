package top.rongx.agent.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.gateway.web.ActionController;
import top.rongx.agent.gateway.web.ChangeController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可用性测试：从 HTTP 控制器入口（真实 Spring 上下文）把「列动作 → 看计划 → 构建 → L3 审批闭环」走一遍。
 *
 * <p>用真实 Bean 而不是 mock：验证的是接线（依赖注入、配方解析、执行器、令牌服务），
 * 这部分是历史上最容易出错的地方。配方与"构建脚本"都放在临时工作区，不依赖 Docker、不依赖模型。
 */
@SpringBootTest
class ActionUsabilityTest {

    @TempDir
    static Path workspaceRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("agent.workspace.root", () -> workspaceRoot.toString());
        registry.add("agent.model.api-key", () -> "test-key-not-used");
    }

    @Autowired
    private ActionController actionController;

    @Autowired
    private ChangeController changeController;

    private static final String USER = "dev-usability";

    private static final String MAKE_JAR = """
            import java.nio.file.*;
            public class MakeJar {
                public static void main(String[] a) throws Exception {
                    Files.createDirectories(Path.of("target"));
                    Files.writeString(Path.of("target/app.jar"), "usability-jar");
                    System.out.println("built target/app.jar");
                }
            }
            """;

    private static final String FAIL = """
            public class Fail {
                public static void main(String[] a) {
                    System.err.println("ERROR: cannot find symbol: class Foo");
                    System.exit(1);
                }
            }
            """;

    private static final String DEPLOY = """
            import java.nio.file.*;
            public class Deploy {
                public static void main(String[] a) throws Exception {
                    Files.writeString(Path.of("deployed.txt"), "deployed at " + System.currentTimeMillis());
                    System.out.println("deploy step 1/2: stop container");
                    System.out.println("deploy step 2/2: start new container");
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
                run: ["java", "Fail.java"]
                timeout: 2m
              stack_deploy:
                run: ["java", "Deploy.java"]
                timeout: 3m
            """;

    @BeforeEach
    void setUp() throws Exception {
        writeRecipes(ACTIONS_YML);
    }

    private void writeRecipes(String yml) throws Exception {
        Path dir = workspaceRoot.resolve("RY");
        Files.createDirectories(dir.resolve("ci"));
        Files.writeString(dir.resolve("ci/actions.yml"), yml);
        Files.writeString(dir.resolve("MakeJar.java"), MAKE_JAR);
        Files.writeString(dir.resolve("Fail.java"), FAIL);
        Files.writeString(dir.resolve("Deploy.java"), DEPLOY);
    }

    // ---------------------------------------------------------------- U1–U5

    @Test
    @DisplayName("U1 列动作：来源指向仓库配方")
    void listActions() {
        var list = actionController.list("RY", "test", USER);
        assertEquals(3, list.size());
        var backend = list.stream().filter(a -> a.action().equals("backend_build")).findFirst().orElseThrow();
        assertEquals("ci/actions.yml#backend_build", backend.source());
        assertTrue(backend.humanAuthored(), "应识别为仓库配方");
        assertEquals("java MakeJar.java", backend.commands().get(0));
    }

    @Test
    @DisplayName("U2 看计划：命令原文可见、产物声明可见")
    void planAction() {
        var plan = actionController.plan("backend_build", "RY", "test", USER);
        assertEquals("java MakeJar.java", plan.commands().get(0));
        assertEquals("target/*.jar", plan.produce().get(0));
        assertEquals("L2_EXECUTE_ISOLATED", plan.level());
    }

    @Test
    @DisplayName("U3 构建成功：产物与 sha256")
    void runBuild() {
        var run = actionController.run("backend_build", "RY", "test", USER);
        assertEquals("SUCCEEDED", run.status());
        assertEquals(1, run.artifacts().size());
        assertEquals("target/app.jar", run.artifacts().get(0).ref());
        assertEquals(64, run.artifacts().get(0).digest().length());
        assertTrue(run.logTail().contains("built target/app.jar"));
    }

    @Test
    @DisplayName("U4 构建失败：E-101，日志尾部能定位错误")
    void runFailingBuild() {
        var run = actionController.run("frontend_build", "RY", "test", USER);
        assertEquals("FAILED", run.status());
        assertEquals("E-101", run.errorCode());
        assertTrue(run.logTail().contains("cannot find symbol"), "日志尾部应包含错误行：" + run.logTail());
    }

    @Test
    @DisplayName("U5 未知动作 / 未认证：可读错误")
    void badRequests() {
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> actionController.plan("deploy_everything", "RY", "test", USER));
        assertTrue(unknown.getMessage().contains("未知动作"));

        var noUser = assertThrows(IllegalArgumentException.class,
                () -> actionController.list("RY", "test", null));
        assertTrue(noUser.getMessage().contains("未认证"));
    }

    // ---------------------------------------------------------------- U6–U10 L3 治理

    @Test
    @DisplayName("U6 L3 动作不能直接执行")
    void l3CannotRunDirectly() {
        var ex = assertThrows(IllegalStateException.class,
                () -> actionController.run("stack_deploy", "RY", "test", USER));
        assertTrue(ex.getMessage().contains("L3"), ex.getMessage());
    }

    @Test
    @DisplayName("U7 生产环境不能直接执行构建")
    void prodBlocked() {
        var ex = assertThrows(IllegalStateException.class,
                () -> actionController.run("backend_build", "RY", "prod", USER));
        assertTrue(ex.getMessage().contains("生产环境"), ex.getMessage());
    }

    @Test
    @DisplayName("U8-L3 闭环：登记 → 确认 → 带令牌执行 → 令牌不可复用")
    void approveThenApply() {
        ChangePlan plan = requestDeploy();
        assertNotNull(plan.planHash());

        var confirmed = changeController.confirm(plan.changeId(), USER, new ChangeController.ConfirmRequest(null));
        assertEquals(plan.planHash(), confirmed.planHash(), "令牌应绑定计划哈希");

        var run = changeController.apply(plan.changeId(), USER, confirmed.confirmToken());
        assertEquals("SUCCEEDED", run.status());
        assertTrue(run.logTail().contains("start new container"));
        assertTrue(Files.exists(workspaceRoot.resolve("RY/deployed.txt")), "发布脚本应真的跑过");

        var reuse = assertThrows(IllegalStateException.class,
                () -> changeController.apply(plan.changeId(), USER, confirmed.confirmToken()));
        assertTrue(reuse.getMessage().contains("已被使用"), reuse.getMessage());
    }

    @Test
    @DisplayName("U9 确认后配方被改 → 执行被拒（planHash 不匹配）")
    void recipeChangedAfterConfirm() throws Exception {
        ChangePlan plan = requestDeploy();
        var confirmed = changeController.confirm(plan.changeId(), USER, new ChangeController.ConfirmRequest(null));

        writeRecipes(ACTIONS_YML.replace("[\"java\", \"Deploy.java\"]",
                "[\"java\", \"Deploy.java\", \"--v2\"]"));

        var ex = assertThrows(IllegalStateException.class,
                () -> changeController.apply(plan.changeId(), USER, confirmed.confirmToken()));
        assertTrue(ex.getMessage().contains("已被修改"), ex.getMessage());
    }

    @Test
    @DisplayName("U10 没有令牌不能执行 L3")
    void applyWithoutToken() {
        ChangePlan plan = requestDeploy();
        var ex = assertThrows(IllegalArgumentException.class,
                () -> changeController.apply(plan.changeId(), USER, null));
        assertTrue(ex.getMessage().contains("确认令牌"), ex.getMessage());
    }

    @Test
    @DisplayName("U11 令牌不能跨变更单使用")
    void tokenBoundToChange() {
        ChangePlan first = requestDeploy();
        ChangePlan second = requestDeploy();
        var token = changeController.confirm(first.changeId(), USER, new ChangeController.ConfirmRequest(null));

        var ex = assertThrows(IllegalStateException.class,
                () -> changeController.apply(second.changeId(), USER, token.confirmToken()));
        assertTrue(ex.getMessage().contains("不匹配"), ex.getMessage());
    }

    private ChangePlan requestDeploy() {
        var view = actionController.request("stack_deploy", "RY", "test", USER);
        return new ChangePlan(view.changeId(), view.projectId(), view.env(), view.kind(),
                view.planHash(), view.summary(), null, null, view.highRisk(), null);
    }
}
