package top.rongx.agent.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.RecipeRenderer;
import top.rongx.agent.domain.deploy.EnvironmentPromotion;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「跑通 → 固化 → 重放 → 服务器前置」整条链路。
 *
 * <p>用户要求的规则：
 * <ul>
 *   <li>agent 先在环境里跑，跑通就固化成可复现脚本；</li>
 *   <li>下次直接重放（不再探索）；</li>
 *   <li>**上服务器前必须有测试机成功案例**（不是审批流，是证据校验）。</li>
 * </ul>
 */
class DeployRecipeFlowTest {

    /** 夹具只用 JDK 自带命令：容器构建镜像里没有 node，避免把环境缺失误报成流水线失败。 */
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
            .toString().replace('\\', '/');


    private static final String PROJECT = "FLOW";

    @Test
    @DisplayName("测试环境跑通后自动固化（含证据），并用脚本内容重放")
    void freezeAfterSuccessAndReplay(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        Path ws = temp.resolve("ws");
        MemoryRecipeStore recipes = new MemoryRecipeStore(ws);
        BuiltInPipelineEngine engine = newEngine(ws, repo, recipes, temp);
        try {
            String runId = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "test"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, runId), engine.logTail(runId));

            DeployRecipe frozen = recipes.current(PROJECT, "test").orElseThrow();
            assertEquals(runId, frozen.evidenceRunId(), "固化必须带证据运行");
            assertEquals(sha, frozen.gitSha());
            assertTrue(engine.logTail(runId).contains("[recipe]"));

            // 重放：第二次运行应直接命中固化脚本（不再生成/改定义）
            String replayId = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "test"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, replayId), engine.logTail(replayId));
            assertTrue(engine.logTail(replayId).contains("RECIPE"), engine.logTail(replayId));
            assertEquals(1, recipes.versions(PROJECT, "test").size(), "脚本没变就不该堆版本");
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("没有测试成功案例时，禁止在服务器（prod）上操作")
    void blocksProdWithoutTestEvidence(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        Path ws = temp.resolve("ws");
        BuiltInPipelineEngine engine = newEngine(ws, repo, new MemoryRecipeStore(ws), temp);
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "prod")));
            assertTrue(e.getMessage().contains("E-601"), e.getMessage());
            assertTrue(e.getMessage().contains("测试环境"), e.getMessage());
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("测试证据成功后 prod 放行；但换了未验证的提交又会被拒")
    void promotesAfterTestEvidence(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        Path ws = temp.resolve("ws");
        RecipeStore recipes = new MemoryRecipeStore(ws);
        BuiltInPipelineEngine engine = newEngine(ws, repo, recipes, temp);
        try {
            String testRun = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "test"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, testRun));

            String prodRun = engine.trigger(PROJECT, "release", Map.of("gitSha", sha, "env", "prod"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, prodRun), engine.logTail(prodRun));

            // 新提交未在测试环境验证过 → 拒绝
            Files.writeString(repo.resolve("second.txt"), "x", StandardCharsets.UTF_8);
            String otherSha = commit(repo, "second");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> engine.trigger(PROJECT, "release",
                            Map.of("gitSha", otherSha, "env", "prod")));
            assertTrue(e.getMessage().contains("测试环境验证的是"), e.getMessage());
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("脚本内容不变的新提交：不堆版本，但证据要刷新到新提交，prod 才能继续晋升")
    void refreshesEvidenceWhenContentUnchanged(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha1 = commit(repo, "init");
        Path ws = temp.resolve("ws");
        RecipeStore recipes = new MemoryRecipeStore(ws);
        BuiltInPipelineEngine engine = newEngine(ws, repo, recipes, temp);
        try {
            String run1 = engine.trigger(PROJECT, "release", Map.of("gitSha", sha1, "env", "test"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, run1), engine.logTail(run1));

            // 新提交只改了业务文件，ci/pipeline.yml 内容不变
            Files.writeString(repo.resolve("second.txt"), "x", StandardCharsets.UTF_8);
            String sha2 = commit(repo, "second");
            String run2 = engine.trigger(PROJECT, "release", Map.of("gitSha", sha2, "env", "test"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, run2), engine.logTail(run2));

            DeployRecipe cur = recipes.current(PROJECT, "test").orElseThrow();
            assertEquals(sha2, cur.gitSha(), "同内容新提交也必须刷新证据 commit");
            assertEquals(run2, cur.evidenceRunId(), "证据运行要指向最新一次成功运行");
            assertEquals(1, recipes.versions(PROJECT, "test").size(), "内容没变不该堆版本");

            // 证据已刷新 → 同一提交上生产不再被误挡
            String prodRun = engine.trigger(PROJECT, "release", Map.of("gitSha", sha2, "env", "prod"));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, prodRun), engine.logTail(prodRun));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("晋升规则矩阵：无脚本 / 无证据 / 证据失败 / sha 不一致")
    void promotionMatrix(@TempDir Path temp) {
        RecipeStore recipes = new MemoryRecipeStore(temp.resolve("ws"));
        // 无脚本
        assertTrue(EnvironmentPromotion.problem(recipes, r -> StageStatus.SUCCEEDED, PROJECT, "prod", "aaaaaaa")
                .contains("还没有成功案例"));
        // test 环境不拦
        assertEquals(null, EnvironmentPromotion.problem(recipes, null, PROJECT, "test", "aaaaaaa"));
        // 有脚本但证据失败
        recipes.save(new DeployRecipe(PROJECT, "test", 0, "name: x\nstages: []\n", null,
                "run-x", "aaaaaaa", 0L, "agent"));
        assertTrue(EnvironmentPromotion.problem(recipes, r -> StageStatus.FAILED, PROJECT, "prod", "aaaaaaa")
                .contains("需要成功"));
        // 证据成功但提交不一致
        assertTrue(EnvironmentPromotion.problem(recipes, r -> StageStatus.SUCCEEDED, PROJECT, "prod", "bbbbbbb")
                .contains("测试环境验证的是"));
        // 全满足
        assertEquals(null, EnvironmentPromotion.problem(recipes, r -> StageStatus.SUCCEEDED,
                PROJECT, "prod", "aaaaaaa"));
    }

    @Test
    @DisplayName("渲染器：动作序列 → 合法 YAML（含 deploy/health/rollback），不含命令")
    void rendererProducesValidYaml() {
        String yaml = RecipeRenderer.render("release",
                RecipeRenderer.standard(true, false, true), RecipeRenderer.RenderTarget.docker(9090));

        assertTrue(yaml.contains("name: release"), yaml);
        assertTrue(yaml.contains("action: backend_build"), yaml);
        assertTrue(yaml.contains("action: image_package"), yaml);
        assertTrue(yaml.contains("type: deploy"), yaml);
        assertTrue(yaml.contains("type: health"), yaml);
        assertTrue(yaml.contains("type: rollback"), yaml);
        assertTrue(yaml.contains("port: 9090"), yaml);
        // 渲染出来的是"编排"，绝不含 shell 命令
        assertFalse(yaml.contains("mvn "), yaml);
        assertFalse(yaml.contains("docker build"), yaml);
        assertTrue(PipelineDefinitionResolver.isValidDefinition(yaml), yaml);
    }

    // ---------------------------------------------------------------- 夹具

    private static BuiltInPipelineEngine newEngine(Path workspace, Path repo, RecipeStore recipes,
                                                   Path temp) {
        PipelineDefStore store = new MemoryDefStore(workspace.resolve("defs"));
        PipelineDefinitionResolver resolver = new PipelineDefinitionResolver(
                store, scaffolder(), null, recipes);
        return new BuiltInPipelineEngine(RESOLVER, HOST, HOST, workspace,
                RunHistoryStore.disabled(), "ci", locator(repo), alwaysOkDeployer(), resolver, recipes);
    }

    /** 只验证"跑通后固化"的编排语义：部署本身用桩，避免依赖 Docker。 */
    private static Deployer alwaysOkDeployer() {
        return new Deployer() {
            @Override
            public boolean supports(DeployTarget target) {
                return true;
            }

            @Override
            public DeployResult deploy(DeployRequest request) {
                return DeployResult.ok(request.target(),
                        "http://127.0.0.1:" + request.hostPort(), "stub", "ok");
            }
        };
    }

    /** 内存版 recipe 存储：同时把 ci/pipeline.yml 真的写到磁盘（解析器要读）。 */
    private static final class MemoryRecipeStore implements RecipeStore {
        private final Path root;
        private final Map<String, DeployRecipe> current = new java.util.HashMap<>();
        private final Map<String, java.util.TreeMap<Integer, DeployRecipe>> all = new java.util.HashMap<>();

        MemoryRecipeStore(Path root) {
            this.root = root;
        }

        @Override
        public DeployRecipe save(DeployRecipe recipe) {
            var versions = all.computeIfAbsent(key(recipe.projectId(), recipe.env()),
                    k -> new java.util.TreeMap<>());
            int version = versions.isEmpty() ? 1 : versions.lastKey() + 1;
            DeployRecipe saved = new DeployRecipe(recipe.projectId(), recipe.env(), version,
                    recipe.pipelineYaml(), recipe.actionsYaml(), recipe.evidenceRunId(),
                    recipe.gitSha(), System.currentTimeMillis(), recipe.createdBy());
            versions.put(version, saved);
            current.put(key(recipe.projectId(), recipe.env()), saved);
            write(saved);
            return saved;
        }

        @Override
        public Optional<DeployRecipe> updateEvidence(String projectId, String env,
                                                     String runId, String gitSha) {
            return current(projectId, env).map(cur -> {
                DeployRecipe updated = cur.withEvidence(runId, gitSha);
                var versions = all.get(key(projectId, env));
                if (versions != null) {
                    versions.put(cur.version(), updated);
                }
                current.put(key(projectId, env), updated);
                write(updated);
                return updated;
            });
        }

        @Override
        public Optional<DeployRecipe> current(String projectId, String env) {
            return Optional.ofNullable(current.get(key(projectId, env)));
        }

        @Override
        public Optional<DeployRecipe> version(String projectId, String env, int version) {
            var versions = all.get(key(projectId, env));
            return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
        }

        @Override
        public List<DeployRecipe> versions(String projectId, String env) {
            var versions = all.get(key(projectId, env));
            return versions == null ? List.of() : List.copyOf(versions.descendingMap().values());
        }

        @Override
        public boolean activate(String projectId, String env, int version) {
            return version(projectId, env, version).map(r -> {
                current.put(key(projectId, env), r);
                return true;
            }).orElse(false);
        }

        @Override
        public Path rootOf(String projectId, String env, int version) {
            return root.resolve(projectId).resolve(env).resolve("v" + version);
        }

        private void write(DeployRecipe recipe) {
            try {
                Path dir = rootOf(recipe.projectId(), recipe.env(), recipe.version()).resolve("ci");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("pipeline.yml"), recipe.pipelineYaml(),
                        StandardCharsets.UTF_8);
                if (recipe.actionsYaml() != null && !recipe.actionsYaml().isBlank()) {
                    Files.writeString(dir.resolve("actions.yml"), recipe.actionsYaml(),
                            StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String key(String projectId, String env) {
            return projectId + "|" + env;
        }
    }

    /** 平台定义存储（真写文件：固化时要读回 pipeline.yml）。 */
    private static final class MemoryDefStore implements PipelineDefStore {
        private final Path root;
        private final Map<String, String> saved = new java.util.HashMap<>();

        MemoryDefStore(Path root) {
            this.root = root;
        }

        @Override
        public Path rootOf(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public boolean exists(String projectId) {
            return saved.containsKey(projectId);
        }

        @Override
        public Optional<String> read(String projectId) {
            return Optional.ofNullable(saved.get(projectId));
        }

        @Override
        public boolean write(String projectId, String pipelineYaml, String actionsYaml) {
            try {
                Path dir = rootOf(projectId).resolve("ci");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("pipeline.yml"), pipelineYaml, StandardCharsets.UTF_8);
                if (actionsYaml != null && !actionsYaml.isBlank()) {
                    Files.writeString(dir.resolve("actions.yml"), actionsYaml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                return false;
            }
            saved.put(projectId, pipelineYaml);
            return true;
        }
    }

    private static ProjectScaffolder scaffolder() {
        ScaffoldPlan plan = new ScaffoldPlan(PROJECT, "JAVA",
                List.of(new ScaffoldPlan.ScaffoldFile("ci/pipeline.yml", """
                        name: release
                        deploy:
                          target: host
                          port: 8080
                          containerPort: 8080
                          healthPath: /healthz
                        stages:
                          - id: build
                            type: action
                            action: backend_build
                            runner: host
                          - id: deploy
                            type: deploy
                        """), new ScaffoldPlan.ScaffoldFile("ci/actions.yml", "actions: {}\n")),
                List.of());
        return new ProjectScaffolder() {
            @Override
            public ScaffoldPlan plan(String projectId, Path repoRoot, DeployTarget preferredTarget) {
                return plan;
            }

            @Override
            public List<String> apply(String projectId, Path repoRoot, ScaffoldPlan p, boolean overwrite) {
                return List.of();
            }
        };
    }

    private static final ActionRunner HOST = new ProcessActionRunner();

    private static final ActionResolver RESOLVER = new ActionResolver() {
        @Override
        public Optional<ActionSpec> resolve(Path repoRoot, ActionId action) {
            if (action == ActionId.BACKEND_BUILD || action == ActionId.BACKEND_TEST) {
                return Optional.of(new ActionSpec(action,
                        List.of(List.of(JAVA, "-version")),
                        List.of(), Duration.ofMinutes(1), ActionSource.builtin("test")));
            }
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

    private static Path newRepo(Path temp) throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "ci@example.com");
        git(repo, "config", "user.name", "CI");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        // 部署目标自愈（reconcileTarget）需要本次真有可运行产物：host 目标认 target/*.jar
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target/app.jar"), "jar", StandardCharsets.UTF_8);
        return repo;
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
