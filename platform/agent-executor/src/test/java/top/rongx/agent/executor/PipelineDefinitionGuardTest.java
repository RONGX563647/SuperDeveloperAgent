package top.rongx.agent.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineDefAuthor;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 流水线定义的解析与来源（回归：仓库里没提交 ci/ 曾经直接报错）。
 *
 * <p>新契约：定义默认放<b>平台侧</b>，接入时按仓库结构自动生成，所以"仓库里没有 ci/pipeline.yml"
 * 不再是错误。优先级：仓库提交覆盖 → 平台定义 → 规则生成 → agent 兜底。
 */
class PipelineDefinitionGuardTest {

    /**
     * 夹具命令必须只依赖 JDK：容器构建（maven 镜像）里没有 node，
     * 用 node 会把"缺可执行文件"误报成流水线失败。
     */
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
            .toString().replace('\\', '/');

    private static final String PROJECT = "GUARD";

    @Test
    @DisplayName("仓库没有 ci/ → 平台自动生成定义并跑通（不再报缺文件）")
    void generatesPlatformDefinitionWhenRepoHasNone(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        PipelineDefStore store = new MemoryStore(temp.resolve("platform"));

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo), store, scaffolder(JAVA_PLAN), null);
        try {
            assertEquals(List.of("release", "verify"), engine.pipelines(PROJECT));
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, runId),
                    "构建应成功，日志：" + engine.logTail(runId));
            assertTrue(store.exists(PROJECT), "应把生成的平台定义落盘");
            assertTrue(engine.logTail(runId).contains("[definition]"), engine.logTail(runId));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("仓库提交里的 ci/pipeline.yml 优先于平台定义")
    void repoDefinitionWins(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), pipelineYaml("from-repo"), StandardCharsets.UTF_8);
        String sha = commit(repo, "ci");
        PipelineDefStore store = new MemoryStore(temp.resolve("platform"));
        store.write(PROJECT, pipelineYaml("from-platform"), actionsYaml());

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo), store, scaffolder(JAVA_PLAN), null);
        try {
            assertTrue(engine.pipelines(PROJECT).contains("from-repo"),
                    String.valueOf(engine.pipelines(PROJECT)));
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, runId));
            assertTrue(engine.logTail(runId).contains("REPO"), engine.logTail(runId));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("平台定义被写坏 → E-221（语法/结构错误）")
    void brokenPlatformDefinition(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        PipelineDefStore store = new MemoryStore(temp.resolve("platform"));
        store.write(PROJECT, "name: release\nstages: [", actionsYaml());

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo), store, scaffolder(JAVA_PLAN), null);
        try {
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            assertEquals(StageStatus.FAILED, awaitTerminal(engine, runId));
            assertTrue(engine.stages(runId).stream().anyMatch(s -> "E-221".equals(s.errorCode())),
                    String.valueOf(engine.stages(runId)));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("规则识别不出技术栈 → agent 兜底定义生效")
    void agentFallbackWhenStackUnknown(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        PipelineDefStore store = new MemoryStore(temp.resolve("platform"));
        PipelineDefAuthor author = (projectId, root, plan, reason) -> Optional.of(pipelineYaml("agent-made"));

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo), store, scaffolder(UNKNOWN_PLAN), author);
        try {
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            assertEquals(StageStatus.SUCCEEDED, awaitTerminal(engine, runId), engine.logTail(runId));
            assertTrue(store.read(PROJECT).orElse("").contains("agent-made"),
                    store.read(PROJECT).orElse(""));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("agent 输出引用了未知动作 → 丢弃，退回规则骨架")
    void invalidAgentDefinitionIsRejected(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        PipelineDefStore store = new MemoryStore(temp.resolve("platform"));
        String badYaml = """
                name: release
                stages:
                  - id: hack
                    type: action
                    action: rm -rf /
                """;
        PipelineDefAuthor author = (projectId, root, plan, reason) -> Optional.of(badYaml);

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo), store, scaffolder(UNKNOWN_PLAN), author);
        try {
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            awaitTerminal(engine, runId);
            assertTrue(store.read(PROJECT).orElse("").contains("backend_build"),
                    "应退回规则骨架：" + store.read(PROJECT).orElse(""));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("非法的仓库覆盖定义 → E-221，不进入执行")
    void brokenRepoDefinition(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), "name: release\nstages: []\n", StandardCharsets.UTF_8);
        String sha = commit(repo, "ci");
        PipelineDefStore store = new MemoryStore(temp.resolve("platform"));

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo), store, scaffolder(JAVA_PLAN), null);
        try {
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            assertEquals(StageStatus.FAILED, awaitTerminal(engine, runId));
            assertTrue(engine.stages(runId).stream().anyMatch(s -> "E-221".equals(s.errorCode())),
                    String.valueOf(engine.stages(runId)));
        } finally {
            engine.close();
        }
    }

    // ---------------------------------------------------------------- 夹具

    /** 规则产物：一个 host 上跑 node 的 build stage（不依赖宿主 JDK / Docker）。 */
    private static final ScaffoldPlan JAVA_PLAN = new ScaffoldPlan(PROJECT, "JAVA",
            List.of(new ScaffoldPlan.ScaffoldFile("ci/pipeline.yml", pipelineYaml("release")),
                    new ScaffoldPlan.ScaffoldFile("ci/actions.yml", actionsYaml())),
            List.of());

    private static final ScaffoldPlan UNKNOWN_PLAN = new ScaffoldPlan(PROJECT, "UNKNOWN",
            List.of(new ScaffoldPlan.ScaffoldFile("ci/pipeline.yml", "name: release\nstages:\n"
                            + "  - id: build\n    type: action\n    action: backend_build\n    runner: host\n"),
                    new ScaffoldPlan.ScaffoldFile("ci/actions.yml", actionsYaml())),
            List.of("未识别技术栈"));

    private static String pipelineYaml(String name) {
        return """
                name: %s
                stages:
                  - id: build
                    type: action
                    action: backend_build
                    runner: host
                """.formatted(name);
    }

    private static String actionsYaml() {
        return """
                actions:
                  backend_build:
                    run: [["%s", "-version"]]
                    timeout: 1m
                """.formatted(JAVA);
    }

    private static ProjectScaffolder scaffolder(ScaffoldPlan plan) {
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

    private static BuiltInPipelineEngine newEngine(Path workspace, ProjectRepoLocator locator,
                                                   PipelineDefStore store, ProjectScaffolder scaffolder,
                                                   PipelineDefAuthor author) {
        PipelineDefinitionResolver resolver = new PipelineDefinitionResolver(store, scaffolder, author);
        return new BuiltInPipelineEngine(PipelineEngineConfig
                .builder(RESOLVER, HOST, HOST, workspace)
                .repoLocator(locator)
                .deployers(Deployers.builtin())
                .resolver(resolver)
                .build());
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

    /** 内存版平台定义存储（同时真写文件，便于断言内容）。 */
    private static final class MemoryStore implements PipelineDefStore {
        private final Path root;
        private final Map<String, String> pipeline = new LinkedHashMap<>();
        private final Map<String, String> actions = new LinkedHashMap<>();

        MemoryStore(Path root) {
            this.root = root;
        }

        @Override
        public Path rootOf(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public boolean exists(String projectId) {
            return pipeline.containsKey(projectId);
        }

        @Override
        public Optional<String> read(String projectId) {
            return Optional.ofNullable(pipeline.get(projectId));
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
            pipeline.put(projectId, pipelineYaml);
            actions.put(projectId, actionsYaml);
            return true;
        }
    }

    /** DIRECT 定位器：仓库就在给定目录，worktree 建在 {@code <dir>/.agent/worktrees}。 */
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
