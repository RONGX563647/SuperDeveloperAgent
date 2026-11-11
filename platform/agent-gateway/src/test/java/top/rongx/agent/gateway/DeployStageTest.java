package top.rongx.agent.gateway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.port.PipelineEngine;
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
 * 方案 C 的端到端验证（不依赖真实 Docker）：deploy stage → 平台部署器 → 证据链。
 *
 * <p>用"记录型部署器"替身，验证引擎侧契约：目标解析、端口传递、覆盖优先级、
 * 缺 deploy 段的失败语义、rollback 的失败语义。真实容器发布由
 * {@code PipelineAcceptanceTest} 覆盖（有 Docker 时）。
 */
class DeployStageTest {

    @TempDir
    static Path workspaces;

    private static String sha;
    private static RecordingDeployer deployer;
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
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        sha = sh(repo, "git", "rev-parse", "HEAD").trim();
        new GitWorktreeManager(workspaces).linkLocal("RY", repo, false);
        deployer = new RecordingDeployer();
        engine = new BuiltInPipelineEngine(new RecipeActionResolver(),
                new ProcessActionRunner(), new ProcessActionRunner(),
                workspaces, RunHistoryStore.disabled(), "ci", null, deployer);
    }

    @Test
    @DisplayName("deploy stage：用 pipeline.yml 的 target/port 调用部署器，并登记 deployment 产物")
    void deployStageUsesPipelineDeclaration() throws Exception {
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
        assertTrue(deployStage.artifacts().stream().anyMatch(a -> "deployment".equals(a.kind())),
                "应登记 deployment 产物：" + deployStage.artifacts());
    }

    @Test
    @DisplayName("触发参数 deployTarget 覆盖 pipeline.yml 的 deploy.target")
    void triggerCanOverrideTarget() throws Exception {
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
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        String otherSha = sh(repo, "git", "rev-parse", "HEAD").trim();
        new GitWorktreeManager(workspaces).linkLocal("RY2", repo, false);

        String runId = engine.trigger("RY2", "release", Map.of("gitSha", otherSha));
        awaitTerminal(runId, 30);

        assertEquals(StageStatus.FAILED, engine.status(runId));
        assertEquals("E-506", stageOf(runId, "deploy").errorCode());
    }

    @Test
    @DisplayName("部署失败 → 进入 on-failure；无上一版时 rollback 报 E-507 而不是假成功")
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
        assertEquals("E-507", rollback.errorCode(), "没有上一版时必须诚实失败");
        // rollback 会再调用一次部署器（rollback=true），由引擎先行拒绝（E-507 在引擎侧）
        assertFalse(deployer.calls.stream().anyMatch(DeployRequest::rollback),
                "无 lastGood 时不应调用部署器执行回滚");
    }

    @Test
    @DisplayName("E-508：流水线改动未合并到默认分支时，拒绝部署")
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
                  - id: deploy
                    type: deploy
                """);
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "feature");
        String featureSha = sh(repo, "git", "rev-parse", "HEAD").trim();
        // 切回默认分支再接入：bare 的默认分支必须是"受审版本"
        sh(repo, "git", "checkout", "-q", "-");
        new GitWorktreeManager(workspaces).linkLocal("RY4", repo, false);
        // 把未合并的 feature 提交也推进 bare（模拟"有人 push 了未合并分支并要求构建它"）
        sh(repo, "git", "push", "--quiet", "agent", "feature:refs/heads/feature");

        deployer.calls.clear();
        String runId = engine.trigger("RY4", "release", Map.of("gitSha", featureSha));
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

    private static String sh(Path dir, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "命令超时：" + String.join(" ", argv));
        assertEquals(0, p.exitValue(), "命令失败：" + String.join(" ", argv) + "\n" + out);
        return out;
    }
}
