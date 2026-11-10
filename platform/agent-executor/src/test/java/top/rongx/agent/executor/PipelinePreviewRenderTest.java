package top.rongx.agent.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.PipelineEngine.StagePreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 命令级预览：按 shell 转义、密钥掩码、脚本头与内置阶段。 */
class PipelinePreviewRenderTest {

    /** 含空格、反斜杠、单引号、占位符、密钥与内嵌凭据的参数。 */
    private static final List<List<String>> STEPS = List.of(List.of(
            "tool", "a b", "it's", "E:\\x y", "${tag}",
            "DB_PASSWORD=s3cret", "--token=abc", "https://u:p@host/api"));

    private static ActionResolver resolver() {
        return new ActionResolver() {
            @Override
            public Optional<ActionSpec> resolve(Path repoRoot, ActionId action) {
                return Optional.of(new ActionSpec(action, STEPS, List.of("target/*.jar"),
                        Duration.ofMinutes(15), ActionSource.builtin("test")));
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
    }

    private static StagePreview previewOf(Path workspace, String stageId) {
        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(
                resolver(), new ProcessActionRunner(), new ProcessActionRunner(), workspace);
        return engine.preview("P", "release", "test").stream()
                .filter(s -> s.id().equals(stageId))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("脚本渲染：单引号转义、密钥掩码、头部写清变量/超时/产物")
    void rendersScript(@TempDir Path workspace) throws Exception {
        writePipeline(workspace);
        StagePreview build = previewOf(workspace, "build");

        String shell = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "pwsh" : "sh";
        assertEquals(shell, build.shell());
        assertFalse(build.builtin());
        assertEquals(12, build.scriptSha().length());

        String script = build.script();
        assertTrue(script.contains("# stage: build · backend_build"), script);
        assertTrue(script.contains("# 变量: tag"), script);
        assertTrue(script.contains("# 超时: 15m"), script);
        assertTrue(script.contains("target/*.jar"), script);

        // 含空格/反斜杠的参数：单引号包裹（sh 内单引号 → '\''，pwsh → ''）
        assertTrue(script.contains("'a b'"), script);
        assertTrue(script.contains("'E:\\x y'"), script);
        String singleQuote = "pwsh".equals(shell) ? "'it''s'" : "'it'\\''s'";
        assertTrue(script.contains(singleQuote), script);

        // 占位符不加引号：运行时由平台替换
        assertTrue(script.contains("${tag}"), script);

        // 密钥掩码：KEY=VALUE / --flag=value / URL 内嵌凭据
        assertTrue(script.contains("DB_PASSWORD=******"), script);
        assertTrue(script.contains("--token=******"), script);
        assertTrue(script.contains("https://u:******@host/api"), script);
        assertFalse(script.contains("s3cret"), script);
        assertFalse(script.contains("abc"), script);
    }

    @Test
    @DisplayName("内置阶段没有脚本，只给可读说明")
    void builtinStage(@TempDir Path workspace) throws Exception {
        writePipeline(workspace);
        StagePreview deploy = previewOf(workspace, "deploy");

        assertTrue(deploy.builtin());
        assertTrue(deploy.script().isEmpty());
        assertTrue(deploy.command().contains("平台内置"), deploy.command());
    }

    @Test
    @DisplayName("指定提交预览：按该提交的 ci/pipeline.yml 解析，而不是工作区")
    void previewByCommit(@TempDir Path workspace) throws Exception {
        Assumptions.assumeTrue(hasGit(), "本机没有 git，跳过按提交预览");

        Path repo = workspace.resolve("P").resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "test");
        git(repo, "config", "commit.gpgsign", "false");

        Path ci = repo.resolve("ci");
        Files.createDirectories(ci);
        Files.writeString(ci.resolve("actions.yml"), "actions: {}\n");
        Files.writeString(ci.resolve("pipeline.yml"), pipelineYaml("backend_build"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "first");
        String first = git(repo, "rev-parse", "HEAD").trim();

        // 工作区改成另一个动作：预览必须跟随"所选提交"，而不是工作区
        Files.writeString(ci.resolve("pipeline.yml"), pipelineYaml("backend_test"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "second");

        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(
                resolver(), new ProcessActionRunner(), new ProcessActionRunner(), workspace);

        // 所选提交是第一次提交：即使工作区已改成 backend_test，预览仍应是 backend_build
        assertEquals("backend_build", buildStage(engine.preview("P", "release", "test", first)).action());
        assertEquals("backend_test", buildStage(engine.preview("P", "release", "test", null)).action());
    }

    private static StagePreview buildStage(List<StagePreview> stages) {
        return stages.stream().filter(s -> s.id().equals("build")).findFirst().orElseThrow();
    }

    private static boolean hasGit() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + " 失败：" + out);
        return out;
    }

    private static String pipelineYaml(String action) {
        return """
                name: release
                stages:
                  - id: build
                    type: action
                    action: %s
                    runner: host
                """.formatted(action);
    }

    /** 最小仓库布局：workspace/<projectId>/repo/ci/{pipeline,actions}.yml */
    private static void writePipeline(Path workspace) throws Exception {
        Path ci = workspace.resolve("P").resolve("repo").resolve("ci");
        Files.createDirectories(ci);
        Files.writeString(ci.resolve("pipeline.yml"), """
                name: release
                stages:
                  - id: build
                    type: action
                    action: backend_build
                    runner: host
                  - id: deploy
                    type: deploy
                """);
        Files.writeString(ci.resolve("actions.yml"), "actions: {}\n");
    }
}
