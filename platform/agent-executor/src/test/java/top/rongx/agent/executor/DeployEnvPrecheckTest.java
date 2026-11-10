package top.rongx.agent.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.domain.settings.ProjectEnvVar;
import top.rongx.agent.domain.settings.ProjectEnvVarRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * E-509 部署前 env 预检：deploy.env-required 声明的必填变量缺失时，
 * **在启动容器之前**失败并给出可操作的指引（RUOYIWNZ 痛点的根治：
 * 容器起来又被应用启动脚本杀掉，错误还被停旧噪音掩盖）。
 */
class DeployEnvPrecheckTest {

    private static final String PROJECT = "ENVCHECK";

    private static final String PIPELINE_YAML = """
            name: release
            deploy:
              target: host
              port: 8687
              containerPort: 8687
              healthPath: /
              env-required:
                - DB_HOST
                - REDIS_HOST
                - TOKEN_SECRET
            stages:
              - id: deploy
                type: deploy
            """;

    @Test
    @DisplayName("必填 env 缺失：deploy 阶段 E-509 失败，部署器一次都没被调用（未启动容器）")
    void missingRequiredEnvFailsBeforeContainer(@TempDir Path temp) throws Exception {
        Path repo = newRepoWithPipeline(temp, PIPELINE_YAML);
        String sha = commit(repo, "init");
        Path ws = temp.resolve("ws");
        RecordingDeployer deployer = new RecordingDeployer();
        BuiltInPipelineEngine engine = engine(ws, repo, deployer, envVars(Map.of()));
        try {
            String runId = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "dev"));
            assertEquals(StageStatus.FAILED, awaitTerminal(engine, runId), engine.logTail(runId));

            String log = engine.logTail(runId);
            assertTrue(log.contains("E-509"), log);
            assertTrue(log.contains("DB_HOST") && log.contains("REDIS_HOST")
                    && log.contains("TOKEN_SECRET"), log);
            assertTrue(log.contains("设置 → 环境"), log);
            assertEquals(0, deployer.calls.size(), "预检失败绝不能走到 docker run");
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("必填 env 配齐：预检通过，部署器收到全部变量")
    void configuredEnvPassesPrecheck(@TempDir Path temp) throws Exception {
        Path repo = newRepoWithPipeline(temp, PIPELINE_YAML);
        String sha = commit(repo, "init");
        Path ws = temp.resolve("ws");
        RecordingDeployer deployer = new RecordingDeployer();
        BuiltInPipelineEngine engine = engine(ws, repo, deployer, envVars(Map.of(
                "DB_HOST", "10.0.0.1",
                "REDIS_HOST", "10.0.0.2",
                "TOKEN_SECRET", "s3cret")));
        try {
            String runId = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "dev"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, runId), engine.logTail(runId));
            assertTrue(engine.logTail(runId).contains("预检通过"), engine.logTail(runId));

            assertEquals(1, deployer.calls.size());
            Map<String, String> injected = deployer.calls.get(0).env();
            assertEquals("10.0.0.1", injected.get("DB_HOST"));
            assertEquals("s3cret", injected.get("TOKEN_SECRET"));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("值为空串视同缺失；未声明 env-required 的老定义不受预检影响")
    void blankValueCountsAsMissingAndLegacyUnaffected(@TempDir Path temp) throws Exception {
        Path repo = newRepoWithPipeline(temp, PIPELINE_YAML);
        String sha = commit(repo, "init");
        Path ws = temp.resolve("ws");
        RecordingDeployer deployer = new RecordingDeployer();
        // REDIS_HOST 配了空串 → 仍算缺失
        BuiltInPipelineEngine engine = engine(ws, repo, deployer, envVars(Map.of(
                "DB_HOST", "10.0.0.1", "REDIS_HOST", " ", "TOKEN_SECRET", "x")));
        try {
            String runId = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "dev"));
            assertEquals(StageStatus.FAILED, awaitTerminal(engine, runId), engine.logTail(runId));
            assertTrue(engine.logTail(runId).contains("REDIS_HOST"), engine.logTail(runId));
            assertEquals(0, deployer.calls.size());
        } finally {
            engine.close();
        }

        // 无 env-required 的定义：没有预检，照常部署（向后兼容）
        Path legacy = newRepoWithPipeline(temp.resolve("legacy"), """
                name: release
                deploy:
                  target: host
                  port: 8687
                stages:
                  - id: deploy
                    type: deploy
                """);
        String legacySha = commit(legacy, "init");
        RecordingDeployer legacyDeployer = new RecordingDeployer();
        BuiltInPipelineEngine legacyEngine = engine(temp.resolve("ws2"), legacy,
                legacyDeployer, envVars(Map.of()));
        try {
            String runId = legacyEngine.trigger("LEGACY", "release",
                    Map.of("gitSha", legacySha, "env", "dev"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(legacyEngine, runId),
                    legacyEngine.logTail(runId));
            assertEquals(1, legacyDeployer.calls.size());
        } finally {
            legacyEngine.close();
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static BuiltInPipelineEngine engine(Path workspace, Path repo, Deployer deployer,
                                                ProjectEnvVarRepository envVars) {
        return new BuiltInPipelineEngine(NO_ACTION, HOST, HOST, workspace,
                RunHistoryStore.disabled(), "ci", locator(repo), deployer,
                null, null, envVars);
    }

    private static Path newRepoWithPipeline(Path base, String pipelineYaml) throws Exception {
        Path repo = base.resolve("repo");
        Files.createDirectories(repo.resolve("ci"));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "ci@example.com");
        git(repo, "config", "user.name", "CI");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("ci/pipeline.yml"), pipelineYaml, StandardCharsets.UTF_8);
        // 部署目标自愈（reconcileTarget）需要本次真有可运行产物：host 目标认 target/*.jar
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target/app.jar"), "jar", StandardCharsets.UTF_8);
        return repo;
    }

    /** 只记账不真部署：验证"预检失败时部署器根本没被调用"。 */
    private static final class RecordingDeployer implements Deployer {
        final List<DeployRequest> calls = new ArrayList<>();

        @Override
        public boolean supports(DeployTarget target) {
            return true;
        }

        @Override
        public DeployResult deploy(DeployRequest request) {
            calls.add(request);
            return DeployResult.ok(request.target(),
                    "http://127.0.0.1:" + request.hostPort(), "stub", "ok");
        }
    }

    private static ProjectEnvVarRepository envVars(Map<String, String> values) {
        return new ProjectEnvVarRepository() {
            @Override
            public List<ProjectEnvVar> findAll(String projectId, String env) {
                return List.of();
            }

            @Override
            public Map<String, String> resolve(String projectId, String env) {
                return values;
            }

            @Override
            public ProjectEnvVar save(String projectId, String env, String key, String value,
                                      boolean secret, String updatedBy) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String projectId, String env, String key) {
                // 测试只读
            }
        };
    }

    private static final ActionRunner HOST = new ProcessActionRunner();

    private static final ActionResolver NO_ACTION = new ActionResolver() {
        @Override
        public Optional<ActionSpec> resolve(Path repoRoot, ActionId action) {
            return Optional.empty();
        }

        @Override
        public List<ActionSpec> resolveAll(Path repoRoot) {
            return List.of();
        }

        @Override
        public boolean hasRecipe(Path repoRoot) {
            return true;
        }
    };

    private static ProjectRepoLocator locator(Path repo) {
        return new ProjectRepoLocator() {
            @Override
            public Optional<Path> repoDir(String projectId) {
                return Optional.of(repo);
            }

            @Override
            public Optional<Path> worktreeRoot(String projectId) {
                return Optional.of(repo.resolve(".agent/worktrees"));
            }

            @Override
            public void sync(String projectId) {
                // 本地目录：无远端
            }

            @Override
            public StorageMode modeOf(String projectId) {
                return StorageMode.DIRECT;
            }
        };
    }

    private static StageStatus awaitTerminal(BuiltInPipelineEngine engine, String runId)
            throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            StageStatus status = engine.status(runId);
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(100);
        }
        fail("运行未在 30s 内结束：" + runId);
        return null;
    }

    private static String commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", message);
        return git(repo, "rev-parse", "HEAD").trim();
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败：" + out);
        }
        return out;
    }
}
