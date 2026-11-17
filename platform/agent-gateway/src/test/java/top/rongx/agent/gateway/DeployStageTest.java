package top.rongx.agent.gateway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.process.BoundedCommand;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.executor.BuiltInPipelineEngine;
import top.rongx.agent.executor.ProcessActionRunner;
import top.rongx.agent.executor.RunHistoryStore;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.infra.recipe.RecipeActionResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 C 的端到端验证（不依赖真实容器发布）：deploy stage → 平台部署器 → 证据链。
 *
 * <p>用"记录型部署器"替身，验证引擎侧契约：目标解析、端口传递、覆盖优先级、
 * 缺 deploy 段的失败语义、rollback 的失败语义、受审校验。真实容器发布由
 * {@code PipelineAcceptanceTest} 覆盖（有 Docker 时）。
 *
 * <p>夹具必须产出 {@code image} 产物：引擎的「部署目标自愈」会在既无镜像又无可运行 jar
 * 时诚实失败（E-504），不允许假部署。这里用一个无副作用的 image_package 动作
 * （宿主执行器替身把它换成 {@code git status}）登记镜像产物，不真的构建镜像。
 */
class DeployStageTest {

    @TempDir
    static Path workspaces;

    private static String sha;
    private static RecordingDeployer deployer;
    private static EvidenceRecipeStore recipes;
    private static PipelineEngine engine;

    /** 记录调用参数并返回成功，用于断言引擎传了什么给部署器。 */
    private static final class RecordingDeployer implements Deployer {
        final List<DeployRequest> calls = new ArrayList<>();
        boolean failNext;

        @Override
        public boolean supports(DeployTarget target) {
            return true;
        }

        @Override
        public DeployResult deploy(DeployRequest request) {
            calls.add(request);
            if (failNext) {
                failNext = false;
                return DeployResult.failed(request.target(), "E-599", "模拟部署失败", "");
            }
            return DeployResult.ok(request.target(),
                    "http://127.0.0.1:" + request.hostPort(), "recorded", "");
        }
    }

    /**
     * 只提供「测试环境成功案例」的证据，满足 prod 触发的晋升前置。
     *
     * <p>本用例没有注入定义解析器，固化脚本不参与定义解析（来源仍是 REPO），
     * 因此这份替身不会把 E-508 的受审比对短路掉。gitSha 留空 = 不比对提交。
     */
    private static final class EvidenceRecipeStore implements RecipeStore {
        private volatile String evidenceRunId;

        @Override
        public DeployRecipe save(DeployRecipe recipe) {
            return recipe;
        }

        @Override
        public Optional<DeployRecipe> current(String projectId, String env) {
            if (!"test".equals(env) || evidenceRunId == null) {
                return Optional.empty();
            }
            return Optional.of(new DeployRecipe(projectId, "test", 1,
                    "name: evidence\nstages: []\n", null,
                    evidenceRunId, null, System.currentTimeMillis(), "test"));
        }

        @Override
        public Optional<DeployRecipe> version(String projectId, String env, int version) {
            return Optional.empty();
        }

        @Override
        public List<DeployRecipe> versions(String projectId, String env) {
            return List.of();
        }

        @Override
        public boolean activate(String projectId, String env, int version) {
            return false;
        }

        @Override
        public Path rootOf(String projectId, String env, int version) {
            return workspaces;
        }
    }

    @BeforeAll
    static void prepare() throws Exception {
        Assumptions.assumeTrue(TestEnv.dockerAvailable(),
                "Docker 不可用，跳过 deploy stage 验收（构建容器内没有 docker CLI）");
        Path repo = workspaces.resolve("RY-repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("app.txt"), "ok\n", StandardCharsets.UTF_8);
        writePipeline(repo, """
                name: release
                deploy:
                  target: docker
                  port: 18090
                  containerPort: 8080
                  healthPath: /healthz
                stages:
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        sha = sh(repo, "git", "rev-parse", "HEAD").trim();
        new GitWorktreeManager(workspaces).linkLocal("RY", repo, false);
        deployer = new RecordingDeployer();
        recipes = new EvidenceRecipeStore();
        engine = new BuiltInPipelineEngine(new RecipeActionResolver(),
                new ProcessActionRunner(), new FixtureHostRunner(),
                workspaces, RunHistoryStore.disabled(), "ci", null, deployer, null, recipes);
    }

    @Test
    @DisplayName("deploy stage：用 pipeline.yml 的 target/port 调用部署器，并登记 deployment 产物")
    void deployStageUsesPipelineDeclaration() throws Exception {
        deployer.failNext = false;
        deployer.calls.clear();
        String runId = engine.trigger("RY", "release", Map.of("gitSha", sha));
        awaitTerminal(runId, 30);

        assertEquals(StageStatus.SUCCEEDED, engine.status(runId), engine.logTail(runId));
        assertEquals(1, deployer.calls.size(), "应调用一次部署器");
        DeployRequest request = deployer.calls.get(0);
        assertEquals(DeployTarget.DOCKER, request.target());
        assertEquals(18090, request.hostPort());
        assertEquals(8080, request.containerPort());
        assertEquals("/healthz", request.healthPath());
        assertEquals(false, request.rollback());

        StageResult deployStage = stageOf(runId, "deploy");
        assertEquals(StageStatus.SUCCEEDED, deployStage.status());
        ArtifactRef deployment = deployStage.artifacts().stream()
                .filter(a -> "deployment".equals(a.kind())).findFirst().orElse(null);
        assertTrue(deployment != null, "应登记 deployment 产物：" + deployStage.artifacts());
        // 证据链：实际端口/容器端口/探活路径必须随产物落库，排障不靠日志反推
        assertEquals("18090", deployment.meta().get("port"), deployment.meta().toString());
        assertEquals("8080", deployment.meta().get("containerPort"), deployment.meta().toString());
        assertEquals("/healthz", deployment.meta().get("healthPath"), deployment.meta().toString());
        assertEquals("docker", deployment.meta().get("target"), deployment.meta().toString());
    }

    @Test
    @DisplayName("触发参数 deployTarget 覆盖 pipeline.yml 的 deploy.target")
    void triggerCanOverrideTarget() throws Exception {
        deployer.failNext = false;
        deployer.calls.clear();
        String runId = engine.trigger("RY", "release", Map.of("gitSha", sha, "deployTarget", "host"));
        awaitTerminal(runId, 30);

        assertEquals(StageStatus.SUCCEEDED, engine.status(runId), engine.logTail(runId));
        assertEquals(DeployTarget.HOST, deployer.calls.get(0).target());
    }

    @Test
    @DisplayName("缺少 deploy 段 → E-506，不静默跳过")
    void missingDeploySectionFailsClosed() throws Exception {
        Path repo = workspaces.resolve("RY2-repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("app.txt"), "ok\n", StandardCharsets.UTF_8);
        writePipeline(repo, """
                name: release
                stages:
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        String otherSha = sh(repo, "git", "rev-parse", "HEAD").trim();
        new GitWorktreeManager(workspaces).linkLocal("RY2", repo, false);

        deployer.failNext = false;
        String runId = engine.trigger("RY2", "release", Map.of("gitSha", otherSha));
        awaitTerminal(runId, 30);

        assertEquals(StageStatus.FAILED, engine.status(runId));
        assertEquals("E-506", stageOf(runId, "deploy").errorCode());
    }

    @Test
    @DisplayName("部署失败 → 进入 on-failure；无上一版时如实跳过回滚，不伪造第二个故障")
    void failureTriggersRollbackWithHonestError() throws Exception {
        Path repo = workspaces.resolve("RY3-repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("app.txt"), "ok\n", StandardCharsets.UTF_8);
        writePipeline(repo, """
                name: release
                deploy:
                  target: docker
                  port: 18091
                stages:
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                  - id: deploy
                    type: deploy
                on-failure:
                  - id: rollback
                    type: rollback
                """);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        String otherSha = sh(repo, "git", "rev-parse", "HEAD").trim();
        new GitWorktreeManager(workspaces).linkLocal("RY3", repo, false);

        deployer.failNext = true;
        deployer.calls.clear();
        String runId = engine.trigger("RY3", "release", Map.of("gitSha", otherSha));
        awaitTerminal(runId, 30);

        assertEquals(StageStatus.FAILED, engine.status(runId));
        assertEquals("E-599", stageOf(runId, "deploy").errorCode());
        StageResult rollback = stageOf(runId, "rollback");
        assertEquals(StageStatus.SUCCEEDED, rollback.status(),
                "首次发布失败没有上一版，回滚本身不该被判成第二个故障");
        assertTrue(engine.logTail(runId).contains("跳过回滚"),
                "必须如实记录跳过原因：" + engine.logTail(runId));
        assertFalse(deployer.calls.stream().anyMatch(DeployRequest::rollback),
                "无 lastGood 时不应调用部署器执行回滚");
    }

    @Test
    @DisplayName("E-508：prod 环境的未合并流水线改动，拒绝部署")
    void unmergedPipelineChangeIsRejected() throws Exception {
        Path repo = workspaces.resolve("RY4-repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("app.txt"), "ok\n", StandardCharsets.UTF_8);
        writePipeline(repo, """
                name: release
                deploy:
                  target: docker
                  port: 18092
                stages:
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "main");
        sh(repo, "git", "checkout", "-q", "-b", "feature");
        writePipeline(repo, """
                name: release
                deploy:
                  target: docker
                  port: 19999
                stages:
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "feature");
        String featureSha = sh(repo, "git", "rev-parse", "HEAD").trim();
        // 切回默认分支再接入：bare 的默认分支必须是"受审版本"
        sh(repo, "git", "checkout", "-q", "-");
        String mainSha = sh(repo, "git", "rev-parse", "HEAD").trim();
        new GitWorktreeManager(workspaces).linkLocal("RY4", repo, false);

        // 受审校验只在 prod 生效：先在 test 环境跑通一次，作为 prod 晋升的"成功案例"证据。
        deployer.failNext = false;
        deployer.calls.clear();
        String evidence = engine.trigger("RY4", "release", Map.of("gitSha", mainSha));
        awaitTerminal(evidence, 30);
        assertEquals(StageStatus.SUCCEEDED, engine.status(evidence), engine.logTail(evidence));
        recipes.evidenceRunId = evidence;

        // 把未合并的 feature 提交也推进 bare（模拟"有人 push 了未合并分支并要求构建它"）
        sh(repo, "git", "push", "--quiet", "agent", "feature:refs/heads/feature");

        deployer.calls.clear();
        String runId = engine.trigger("RY4", "release",
                Map.of("gitSha", featureSha, "env", "prod"));
        awaitTerminal(runId, 30);

        assertEquals(StageStatus.FAILED, engine.status(runId), engine.logTail(runId));
        assertEquals("E-508", stageOf(runId, "deploy-guard").errorCode());
        assertFalse(deployer.calls.stream().anyMatch(c -> !c.rollback()),
                "未合并的流水线不得调用部署器");
    }

    // ---------------------------------------------------------------- helpers

    private static StageResult stageOf(String runId, String stageId) {
        return engine.stages(runId).stream()
                .filter(s -> s.stageId().equals(stageId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 stage：" + stageId
                        + "，实际=" + engine.stages(runId).stream().map(StageResult::stageId).toList()));
    }

    private static void awaitTerminal(String runId, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (engine.status(runId).isTerminal()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("运行未在 " + seconds + "s 内结束：" + engine.logTail(runId));
    }

    private static void writePipeline(Path repo, String yaml) throws Exception {
        Files.writeString(repo.resolve("ci/pipeline.yml"), yaml, StandardCharsets.UTF_8);
    }

    /**
     * 宿主执行器替身：夹具里没有 Dockerfile，image_package 的内置配方（docker build）不能真跑，
     * 这里把它换成等价的只读命令 {@code git status --short}，其余动作照常走真实宿主执行。
     *
     * <p>配方必须用内置的：仓库自定义的 docker 配方已被引擎拒绝上宿主
     * （见 {@code BuiltInPipelineEngine#trustedForHost}），所以夹具不再写 ci/actions.yml。
     */
    private static final class FixtureHostRunner implements ActionRunner {
        private final ActionRunner delegate = new ProcessActionRunner();

        @Override
        public ActionResult run(Path workspace, ActionSpec spec) {
            return run(workspace, spec, () -> false, output -> { });
        }

        @Override
        public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled) {
            return run(workspace, spec, cancelled, output -> { });
        }

        @Override
        public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled,
                                Consumer<String> live) {
            boolean dockerBuild = spec.action() == ActionId.IMAGE_PACKAGE
                    && spec.steps().stream().flatMap(List::stream).anyMatch("build"::equals);
            ActionSpec effective = dockerBuild
                    ? new ActionSpec(ActionId.IMAGE_PACKAGE,
                            List.of(List.of("git", "status", "--short")),
                            spec.produce(), spec.timeout(), spec.source())
                    : spec;
            return delegate.run(workspace, effective, cancelled, live);
        }
    }

    /** 有界执行：并行排空输出 + 硬超时，避免 readAllBytes 让 waitFor 的超时形同虚设。 */
    private static String sh(Path dir, String... argv) {
        String label = String.join(" ", argv);
        BoundedCommand.Outcome outcome = BoundedCommand.run(List.of(argv), dir, 30_000L);
        assertFalse(outcome.timedOut(), "命令超时：" + label + "\n" + outcome.output());
        assertEquals(0, outcome.exitCode(),
                "命令失败：" + label + "\n" + outcome.output() + "\n" + outcome.detail());
        return outcome.output();
    }
}
