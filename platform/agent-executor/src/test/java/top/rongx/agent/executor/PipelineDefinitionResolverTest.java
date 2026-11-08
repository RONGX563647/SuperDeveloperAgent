package top.rongx.agent.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.PipelineDefAuthor;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定义解析优先级：仓库提交覆盖 → 平台定义 → 规则生成 → agent 兜底。
 *
 * <p>这组用例直接测解析器（不起引擎），因为"用哪份定义、以及为什么"是这里的核心，
 * 引擎只负责把它执行出来。
 */
class PipelineDefinitionResolverTest {

    private static final String PROJECT = "RES";

    @Test
    @DisplayName("仓库里有 ci/pipeline.yml → 用它（source=REPO，配方根=仓库）")
    void repoWins(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), yaml("from-repo"), StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore(temp.resolve("platform"));

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("JAVA", "from-rule"), null).resolveForRead(PROJECT, repo);

        assertEquals(PipelineDefinitionResolver.Source.REPO, r.source());
        assertEquals("from-repo", r.definition().name());
        assertEquals(repo, r.recipeRoot());
        assertTrue(r.note().contains("ci/pipeline.yml"));
    }

    @Test
    @DisplayName("仓库没有定义但有平台定义 → 用平台（source=PLATFORM）")
    void platformWinsOverGeneration(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));
        store.write(PROJECT, yaml("from-platform"), null);

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("JAVA", "from-rule"), null).resolveForRead(PROJECT, repo);

        assertEquals(PipelineDefinitionResolver.Source.PLATFORM, r.source());
        assertEquals("from-platform", r.definition().name());
        assertTrue(r.note().contains("平台"));
    }

    @Test
    @DisplayName("两边都没有 → 按规则生成并落盘（source=GENERATED）")
    void generatesWhenNothingExists(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("JAVA", "from-rule"), null).resolveForRead(PROJECT, repo);

        assertEquals(PipelineDefinitionResolver.Source.GENERATED, r.source());
        assertEquals("from-rule", r.definition().name());
        assertTrue(store.exists(PROJECT), "生成的定义要存下来，下次直接用");
        assertTrue(r.note().contains("JAVA"), r.note());
    }

    @Test
    @DisplayName("规则认不出技术栈 + agent 给出合法定义 → 用 agent 的，并标注需人工确认")
    void agentFallback(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));
        PipelineDefAuthor author = (id, root, p, reason) -> Optional.of(yaml("agent-made"));

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("UNKNOWN", "skeleton"), author).resolveForRead(PROJECT, repo);

        assertEquals("agent-made", r.definition().name());
        assertTrue(r.note().contains("agent"), r.note());
        assertTrue(r.note().contains("人工确认"), r.note());
        assertTrue(store.read(PROJECT).orElse("").contains("agent-made"));
    }

    @Test
    @DisplayName("agent 输出引用了未知动作 → 丢弃，退回规则骨架")
    void invalidAgentOutputRejected(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));
        PipelineDefAuthor author = (id, root, p, reason) -> Optional.of("""
                name: release
                stages:
                  - id: hack
                    type: action
                    action: drop table users
                """);

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("UNKNOWN", "skeleton"), author).resolveForRead(PROJECT, repo);

        assertEquals("skeleton", r.definition().name());
        assertTrue(r.note().contains("不合契约"), r.note());
        assertTrue(store.read(PROJECT).orElse("").contains("skeleton"));
    }

    @Test
    @DisplayName("agent 不可用（empty）→ 退回规则骨架并提示")
    void agentUnavailableFallsBack(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("UNKNOWN", "skeleton"), (id, root, p, reason) -> Optional.empty())
                        .resolveForRead(PROJECT, repo);

        assertEquals("skeleton", r.definition().name());
        assertTrue(r.note().contains("agent 兜底不可用"), r.note());
    }

    @Test
    @DisplayName("工作区有未提交的 ci/pipeline.yml → 收进平台（不按规则覆盖用户内容）")
    void adoptsWorktreeDefinition(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), yaml("user-edited"), StandardCharsets.UTF_8);
        // 检出目录（= 被构建的提交）里没有 ci/：走"平台侧生成"的分支
        Path worktree = dir(temp, "worktree");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));

        PipelineDefinitionResolver.Resolved r =
                resolver(store, plan("JAVA", "from-rule"), null)
                        .resolveForRun(PROJECT, worktree, repo);

        assertEquals("user-edited", r.definition().name(), "应沿用用户已写好的那份");
        assertTrue(store.read(PROJECT).orElse("").contains("user-edited"));
        assertTrue(r.note().contains("收进平台"), r.note());
    }

    @Test
    @DisplayName("该环境已有固化脚本时，脚本优先于仓库里提交的定义")
    void recipeWinsOverRepo(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), yaml("from-repo"), StandardCharsets.UTF_8);
        MemoryStore defs = new MemoryStore(temp.resolve("platform"));
        MemoryRecipeStore recipes = new MemoryRecipeStore(temp.resolve("recipes"));
        recipes.save(new DeployRecipe(PROJECT, "test", 0, yaml("from-recipe"), null,
                "run-1", "aaaaaaa", 0L, "agent"));

        PipelineDefinitionResolver.Resolved r = new PipelineDefinitionResolver(
                defs, scaffolder(plan("JAVA", "from-rule")), null, recipes)
                .resolveForRun(PROJECT, repo, repo, "test");

        assertEquals(PipelineDefinitionResolver.Source.RECIPE, r.source());
        assertEquals("from-recipe", r.definition().name());
    }

    @Test
    @DisplayName("生产环境还没跑过 → 晋升为测试环境的固化脚本")
    void prodFallsBackToTestRecipe(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore defs = new MemoryStore(temp.resolve("platform"));
        MemoryRecipeStore recipes = new MemoryRecipeStore(temp.resolve("recipes"));
        recipes.save(new DeployRecipe(PROJECT, "test", 0, yaml("test-script"), null,
                "run-9", "aaaaaaa", 0L, "agent"));

        PipelineDefinitionResolver.Resolved r = new PipelineDefinitionResolver(
                defs, scaffolder(plan("JAVA", "from-rule")), null, recipes)
                .resolveForRun(PROJECT, repo, repo, "prod");

        assertEquals(PipelineDefinitionResolver.Source.RECIPE, r.source());
        assertEquals("test-script", r.definition().name());
        assertTrue(r.note().contains("晋升"), r.note());
    }

    @Test
    @DisplayName("平台定义被写坏 → 抛 IllegalArgumentException（引擎映射成 E-221）")
    void brokenPlatformDefinitionThrows(@TempDir Path temp) throws Exception {
        Path repo = dir(temp, "repo");
        MemoryStore store = new MemoryStore(temp.resolve("platform"));
        store.write(PROJECT, "name: release\nstages: [", null);

        assertThrows(IllegalArgumentException.class,
                () -> resolver(store, plan("JAVA", "x"), null).resolveForRead(PROJECT, repo));
    }

    @Test
    @DisplayName("校验器：只认白名单动作与合法 stage 类型")
    void validation() {
        assertTrue(PipelineDefinitionResolver.isValidDefinition("""
                name: release
                stages:
                  - id: build
                    type: action
                    action: backend_build
                  - id: deploy
                    type: deploy
                """));
        assertTrue(!PipelineDefinitionResolver.isValidDefinition("""
                name: x
                stages:
                  - id: build
                    type: action
                    action: run_shell
                """));
        assertTrue(!PipelineDefinitionResolver.isValidDefinition("""
                name: x
                stages:
                  - id: build
                    type: mystery
                """));
        assertTrue(!PipelineDefinitionResolver.isValidDefinition("name: x\n"));
    }

    // ---------------------------------------------------------------- 夹具

    private static PipelineDefinitionResolver resolver(PipelineDefStore store, ScaffoldPlan plan,
                                                       PipelineDefAuthor author) {
        return new PipelineDefinitionResolver(store, scaffolder(plan), author);
    }

    private static ScaffoldPlan plan(String stack, String pipelineName) {
        return new ScaffoldPlan(PROJECT, stack,
                List.of(new ScaffoldPlan.ScaffoldFile("ci/pipeline.yml", yaml(pipelineName)),
                        new ScaffoldPlan.ScaffoldFile("ci/actions.yml", "actions: {}\n")),
                List.of("note-" + stack));
    }

    private static String yaml(String name) {
        return "name: " + name + "\nstages:\n"
                + "  - id: build\n    type: action\n    action: backend_build\n    runner: host\n";
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

    private static Path dir(Path parent, String name) throws Exception {
        Path p = parent.resolve(name);
        Files.createDirectories(p);
        return p;
    }

    private static final class MemoryStore implements PipelineDefStore {
        private final Path root;
        private final Map<String, String> pipeline = new LinkedHashMap<>();

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
            } catch (Exception e) {
                return false;
            }
            pipeline.put(projectId, pipelineYaml);
            return true;
        }
    }

    /** 内存版 recipe 存储：save 时真写文件，解析器要按 rootOf 读回来。 */
    private static final class MemoryRecipeStore implements top.rongx.agent.domain.port.RecipeStore {
        private final Path root;
        private final Map<String, DeployRecipe> current = new LinkedHashMap<>();

        MemoryRecipeStore(Path root) {
            this.root = root;
        }

        @Override
        public DeployRecipe save(DeployRecipe recipe) {
            DeployRecipe saved = new DeployRecipe(recipe.projectId(), recipe.env(), 1,
                    recipe.pipelineYaml(), recipe.actionsYaml(), recipe.evidenceRunId(),
                    recipe.gitSha(), System.currentTimeMillis(), recipe.createdBy());
            current.put(recipe.projectId() + "|" + recipe.env(), saved);
            try {
                Path dir = rootOf(recipe.projectId(), recipe.env(), 1).resolve("ci");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("pipeline.yml"), recipe.pipelineYaml(),
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return saved;
        }

        @Override
        public Optional<DeployRecipe> current(String projectId, String env) {
            return Optional.ofNullable(current.get(projectId + "|" + env));
        }

        @Override
        public Optional<DeployRecipe> version(String projectId, String env, int version) {
            return current(projectId, env);
        }

        @Override
        public List<DeployRecipe> versions(String projectId, String env) {
            return current(projectId, env).map(List::of).orElse(List.of());
        }

        @Override
        public boolean activate(String projectId, String env, int version) {
            return current.containsKey(projectId + "|" + env);
        }

        @Override
        public Path rootOf(String projectId, String env, int version) {
            return root.resolve(projectId).resolve(env).resolve("v" + version);
        }
    }
}
