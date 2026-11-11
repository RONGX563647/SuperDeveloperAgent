package top.rongx.agent.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
        // 本测试固定宿主进程模式：验证治理链路，不依赖 Docker。
        registry.add("agent.sandbox.mode", () -> "process");
        // 宿主模式下 L3 发布被 fail-closed 拦截是**正确行为**；本用例只验证令牌链路，
        // 用一个非 L3 的自定义动作来走完"登记→确认→带令牌执行"闭环。
        registry.add("agent.sandbox.allow-test-l3", () -> "true");
        // 预置项目清单已从 application.yml 移除（项目统一走「项目接入」）；
        // 本用例需要固定项目 RY 来验证接线，因此在测试内声明，不依赖本地 data/workspaces。
        addTestProject(registry);
    }

    /** 测试用项目 RY：dev/test/prod 三个环境，满足控制器对项目与环境的校验。 */
    static void addTestProject(DynamicPropertyRegistry registry) {
        registry.add("agent.projects.items[0].id", () -> "RY");
        registry.add("agent.projects.items[0].name", () -> "RuoYi-Vue-Plus");
        registry.add("agent.projects.items[0].repo-url", () -> "https://gitee.com/dromara/RuoYi-Vue-Plus.git");
        registry.add("agent.projects.items[0].repo-type", () -> "gitee");
        registry.add("agent.projects.items[0].type", () -> "ruoyi-plus");
        registry.add("agent.projects.items[0].port-range-start", () -> "8100");
        registry.add("agent.projects.items[0].port-range-end", () -> "8199");
        registry.add("agent.projects.items[0].envs[0].env", () -> "dev");
        registry.add("agent.projects.items[0].envs[0].app-url", () -> "http://127.0.0.1:8180");
        registry.add("agent.projects.items[0].envs[0].compose-dir", () -> "opt/apps/ry");
        registry.add("agent.projects.items[0].envs[1].env", () -> "test");
        registry.add("agent.projects.items[0].envs[1].app-url", () -> "http://127.0.0.1:8180");
        registry.add("agent.projects.items[0].envs[1].compose-dir", () -> "opt/apps/ry");
        registry.add("agent.projects.items[0].envs[2].env", () -> "prod");
        registry.add("agent.projects.items[0].envs[2].app-url", () -> "http://127.0.0.1:8181");
        registry.add("agent.projects.items[0].envs[2].compose-dir", () -> "opt/apps/ry");
    }

    @Autowired
    private ActionController actionController;

    @Autowired
    private ChangeController changeController;


    // 产物生产脚本改用 node：宿主 PATH 上的 java 版本不受本测试控制
    // （jenv 可能切到 JDK8，而 java 单文件模式需要 11+）；node 语义等价且稳定。
    private static final String MAKE_JAR = """
            const fs = require('fs');
            fs.mkdirSync('target', { recursive: true });
            fs.writeFileSync('target/app.jar', 'usability-jar');
            console.log('built target/app.jar');
            """;

    private static final String FAIL = """
            console.error('ERROR: cannot find symbol: class Foo');
            process.exit(1);
            """;

    private static final String DEPLOY = """
            const fs = require('fs');
            fs.writeFileSync('deployed.txt', 'deployed at ' + Date.now());
            console.log('deploy step 1/2: stop container');
            console.log('deploy step 2/2: start new container');
            """;

    private static final String ACTIONS_YML = """
            actions:
              backend_build:
                run: ["node", "make-jar.js"]
                produce: ["target/*.jar"]
                timeout: 3m
              frontend_build:
                run: ["node", "fail.js"]
                timeout: 2m
              stack_deploy:
                run: ["node", "deploy.js"]
                timeout: 3m
            """;

    @BeforeEach
    void setUp() throws Exception {
        writeRecipes(ACTIONS_YML);
        // 以 devops 身份建立会话（L2/L3 均有权）；用例内如需其它角色再单独 login。
        // 纯 JUnit 无 Web 上下文：用 Sa-Token 自带的 mock 上下文包住整个测试线程。
        cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
        cn.dev33.satoken.stp.StpUtil.login("devops");
    }

    @AfterEach
    void tearDown() {
        cn.dev33.satoken.stp.StpUtil.logout();
        cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
    }

    private void writeRecipes(String yml) throws Exception {
        Path dir = workspaceRoot.resolve("RY");
        Files.createDirectories(dir.resolve("ci"));
        Files.writeString(dir.resolve("ci/actions.yml"), yml);
        Files.writeString(dir.resolve("make-jar.js"), MAKE_JAR);
        Files.writeString(dir.resolve("fail.js"), FAIL);
        Files.writeString(dir.resolve("deploy.js"), DEPLOY);
    }

    // ---------------------------------------------------------------- U1–U5

    @Test
    @DisplayName("U1 列动作：来源指向仓库配方")
    void listActions() {
        var list = actionController.list("RY", "test");
        // 平台内置动作也会出现在列表里；这里只断言"仓库配方被识别"的部分
        long fromRepo = list.stream()
                .filter(a -> a.source() != null && a.source().startsWith("ci/actions.yml#"))
                .count();
        assertEquals(3, fromRepo, String.valueOf(list));
        var backend = list.stream().filter(a -> a.action().equals("backend_build")).findFirst().orElseThrow();
        assertEquals("ci/actions.yml#backend_build", backend.source());
        assertTrue(backend.humanAuthored(), "应识别为仓库配方");
        assertEquals("node make-jar.js", backend.commands().get(0));
    }

    @Test
    @DisplayName("U2 看计划：命令原文可见、产物声明可见")
    void planAction() {
        var plan = actionController.plan("backend_build", "RY", "test");
        assertEquals("node make-jar.js", plan.commands().get(0));
        assertEquals("target/*.jar", plan.produce().get(0));
        assertEquals("L2_EXECUTE_ISOLATED", plan.level());
    }

    @Test
    @DisplayName("U3 构建成功：产物与 sha256")
    void runBuild() {
        Assumptions.assumeTrue(TestEnv.nodeAvailable(), "缺少 node，跳过真实动作执行");
        var run = actionController.run("backend_build", "RY", "test");
        assertEquals("SUCCEEDED", run.status(), "应构建成功，日志：" + run.logTail());
        assertEquals(1, run.artifacts().size(), "应收集到产物，日志：" + run.logTail());
        assertEquals("target/app.jar", run.artifacts().get(0).ref());
        assertEquals(64, run.artifacts().get(0).digest().length());
        assertTrue(run.logTail().contains("built target/app.jar"));
    }

    @Test
    @DisplayName("U4 构建失败：E-101，日志尾部能定位错误")
    void runFailingBuild() {
        Assumptions.assumeTrue(TestEnv.nodeAvailable(), "缺少 node，跳过真实动作执行");
        var run = actionController.run("frontend_build", "RY", "test");
        assertEquals("FAILED", run.status());
        assertEquals("E-101", run.errorCode());
        assertTrue(run.logTail().contains("cannot find symbol"), "日志尾部应包含错误行：" + run.logTail());
    }

    @Test
    @DisplayName("U5 未知动作 / 未认证：可读错误")
    void badRequests() {
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> actionController.plan("deploy_everything", "RY", "test"));
        assertTrue(unknown.getMessage().contains("未知动作"));

        // 切到 viewer：没有 action:run 权限，应被拒（用例内显式换角色）
        cn.dev33.satoken.stp.StpUtil.logout();
        cn.dev33.satoken.stp.StpUtil.login("viewer");
        var noPerm = assertThrows(Exception.class, () -> actionController.run("backend_build", "RY", "test"));
        assertTrue(noPerm.getMessage().contains("权限") || noPerm.getMessage().contains("Permission"),
                "viewer 应被拒：" + noPerm.getMessage());
        // 恢复 devops（后续用例依赖该角色；@AfterEach 统一登出）
        cn.dev33.satoken.stp.StpUtil.logout();
        cn.dev33.satoken.stp.StpUtil.login("devops");
    }

    // ---------------------------------------------------------------- U6–U10 L3 治理

    @Test
    @DisplayName("U6 L3 动作不能直接执行")
    void l3CannotRunDirectly() {
        var ex = assertThrows(IllegalStateException.class,
                () -> actionController.run("stack_deploy", "RY", "test"));
        assertTrue(ex.getMessage().contains("L3"), ex.getMessage());
    }

    @Test
    @DisplayName("U7 生产环境不能直接执行构建")
    void prodBlocked() {
        var ex = assertThrows(IllegalStateException.class,
                () -> actionController.run("backend_build", "RY", "prod"));
        assertTrue(ex.getMessage().contains("生产环境"), ex.getMessage());
    }

    @Test
    @DisplayName("U8-L3 闭环：登记 → 确认 → 带令牌执行 → 令牌不可复用")
    void approveThenApply() {
        Assumptions.assumeTrue(TestEnv.nodeAvailable(), "缺少 node，跳过真实动作执行");
        ChangePlan plan = requestDeploy();
        assertNotNull(plan.planHash());

        var confirmed = changeController.confirm(plan.changeId(), new ChangeController.ConfirmRequest(null));
        assertEquals(plan.planHash(), confirmed.planHash(), "令牌应绑定计划哈希");

        var run = changeController.apply(plan.changeId(), confirmed.confirmToken());
        assertEquals("SUCCEEDED", run.status());
        assertTrue(run.logTail().contains("start new container"));
        assertTrue(Files.exists(workspaceRoot.resolve("RY/deployed.txt")), "发布脚本应真的跑过");

        var reuse = assertThrows(IllegalStateException.class,
                () -> changeController.apply(plan.changeId(), confirmed.confirmToken()));
        assertTrue(reuse.getMessage().contains("已被使用"), reuse.getMessage());
    }

    @Test
    @DisplayName("U9 确认后配方被改 → 执行被拒（planHash 不匹配）")
    void recipeChangedAfterConfirm() throws Exception {
        ChangePlan plan = requestDeploy();
        var confirmed = changeController.confirm(plan.changeId(), new ChangeController.ConfirmRequest(null));

        writeRecipes(ACTIONS_YML.replace("[\"node\", \"deploy.js\"]",
                "[\"node\", \"deploy.js\", \"--v2\"]"));

        var ex = assertThrows(IllegalStateException.class,
                () -> changeController.apply(plan.changeId(), confirmed.confirmToken()));
        assertTrue(ex.getMessage().contains("已被修改"), ex.getMessage());
    }

    @Test
    @DisplayName("U10 没有令牌不能执行 L3")
    void applyWithoutToken() {
        ChangePlan plan = requestDeploy();
        var ex = assertThrows(IllegalArgumentException.class,
                () -> changeController.apply(plan.changeId(), null));
        assertTrue(ex.getMessage().contains("确认令牌"), ex.getMessage());
    }

    @Test
    @DisplayName("U11 令牌不能跨变更单使用")
    void tokenBoundToChange() {
        ChangePlan first = requestDeploy();
        ChangePlan second = requestDeploy();
        var token = changeController.confirm(first.changeId(), new ChangeController.ConfirmRequest(null));

        var ex = assertThrows(IllegalStateException.class,
                () -> changeController.apply(second.changeId(), token.confirmToken()));
        assertTrue(ex.getMessage().contains("不匹配"), ex.getMessage());
    }

    private ChangePlan requestDeploy() {
        var view = actionController.request("stack_deploy", "RY", "test");
        return new ChangePlan(view.changeId(), view.projectId(), view.env(), view.kind(),
                view.planHash(), view.summary(), null, null, view.highRisk(), null);
    }
}
