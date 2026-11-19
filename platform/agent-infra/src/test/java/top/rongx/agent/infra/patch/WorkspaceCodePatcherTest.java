package top.rongx.agent.infra.patch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.infra.git.GitWorktreeManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PatchPathDeniedException;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 受限补丁器真实集成测试：本地 git 仓库，无网络。
 *
 * <p>验证四件事：业务源码可改、危险路径一律拒绝且返回结构化原因、diff 能生成、
 * 应用后落到独立分支且 worktree 被清理。
 */
class WorkspaceCodePatcherTest {

    @TempDir
    Path ws;

    @TempDir
    Path userSpace;

    @Test
    @DisplayName("业务源码 src/main 与测试代码 src/test 允许修改")
    void allowsBusinessAndTestSources() throws Exception {
        Path repo = makeRepo();
        String base = new GitWorktreeManager(ws).revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(new GitWorktreeManager(ws), locator(repo));

        CodePatcher.Staged staged = patcher.stage("P", base, List.of(
                new CodePatch.FileEdit("src/main/java/App.java", "class App { int v = 2; }\n"),
                new CodePatch.FileEdit("platform/demo/src/main/java/Other.java",
                        "package demo;\nclass Other {}\n"),
                new CodePatch.FileEdit("src/test/java/AppTest.java", "class AppTest {}\n")));

        assertEquals(List.of("src/main/java/App.java",
                "platform/demo/src/main/java/Other.java",
                "src/test/java/AppTest.java"), staged.files());
        assertTrue(staged.diff().contains("+++ b/src/main/java/App.java"), staged.diff());
    }

    @Test
    @DisplayName("编译产物、依赖目录与凭证文件被拒，且返回 PATH_DENIED 与允许清单")
    void rejectsArtifactsDependenciesAndSecrets() throws Exception {
        Path repo = makeRepo();
        String base = new GitWorktreeManager(ws).revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(new GitWorktreeManager(ws), locator(repo));

        for (String denied : List.of(
                "target/classes/App.class",
                "platform/demo/target/generated.txt",
                "node_modules/left-pad/index.js",
                ".git/config",
                ".env",
                "deploy/prod.pem",
                "id_rsa",
                "../evil.sh",
                "/etc/passwd")) {
            PatchPathDeniedException error = assertThrows(PatchPathDeniedException.class,
                    () -> patcher.stage("P", base,
                            List.of(new CodePatch.FileEdit(denied, "x\n"))), denied);
            assertEquals(PatchPathDeniedException.REASON_CODE, "PATH_DENIED");
            assertEquals(denied, error.path(), denied);
            assertFalse(error.allowedPatterns().isEmpty(), denied);
            assertTrue(error.structured().contains("allowed:"), denied);
        }
    }

    @Test
    @DisplayName("拒绝信息是结构化文本：含原因码、路径、允许与禁止清单")
    void denialIsStructured() throws Exception {
        Path repo = makeRepo();
        String base = new GitWorktreeManager(ws).revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(new GitWorktreeManager(ws), locator(repo));

        PatchPathDeniedException error = assertThrows(PatchPathDeniedException.class,
                () -> patcher.stage("P", base,
                        List.of(new CodePatch.FileEdit("docs/notes.md", "hi\n"))));
        String text = error.structured();
        assertTrue(text.startsWith(PatchPathDeniedException.REASON_CODE), text);
        assertTrue(text.contains("path: docs/notes.md"), text);
        assertTrue(text.contains("src/main/**"), text);
        assertTrue(text.contains("denied:"), text);
        assertEquals(PlatformException.class, error.getClass().getSuperclass().getSuperclass());
    }

    @Test
    @DisplayName("Dockerfile 与 .dockerignore 属于允许范围（修 Docker 部署链路）")
    void allowsDockerBuildFiles() throws Exception {
        Path repo = makeRepo();
        String base = new GitWorktreeManager(ws).revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(new GitWorktreeManager(ws), locator(repo));

        CodePatcher.Staged staged = patcher.stage("P", base, List.of(
                new CodePatch.FileEdit("Dockerfile", "FROM eclipse-temurin:21-jre\n"),
                new CodePatch.FileEdit(".dockerignore", "data\n.agent\n")));

        assertEquals(List.of("Dockerfile", ".dockerignore"), staged.files());
        assertTrue(staged.diff().contains("+++ b/Dockerfile"), staged.diff());
    }

    @Test
    @DisplayName("预演产出 diff，应用后提交到独立分支且 worktree 被清理")
    void stageThenApplyCommitsToBranch() throws Exception {
        Path repo = makeRepo();
        GitWorktreeManager git = new GitWorktreeManager(ws);
        String base = git.revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(git, locator(repo));

        CodePatcher.Staged staged = patcher.stage("P", base,
                List.of(new CodePatch.FileEdit("ci/pipeline.yml", "build:\n  command: mvn -B -DskipTests package\n")));
        assertTrue(staged.diff().contains("+++ b/ci/pipeline.yml"), staged.diff());
        assertTrue(staged.added() > 0);
        assertTrue(staged.removed() > 0);
        assertFalse(staged.diffHash().isBlank());

        CodePatch patch = new CodePatch("pt-test", "P", "dev", base, "agent/fix-pt-test",
                List.of(new CodePatch.FileEdit("ci/pipeline.yml", "build:\n  command: mvn -B -DskipTests package\n")),
                staged.diff(), staged.diffHash(), staged.added(), staged.removed(), 0L);
        String commit = patcher.apply(patch);

        assertNotEquals(base, commit);
        assertTrue(git.showFile(repo, commit, "ci/pipeline.yml").contains("mvn -B -DskipTests package"));
        // 分支保留（可直接用于重跑），worktree 不残留
        assertTrue(git.revParse(repo, "refs/heads/agent/fix-pt-test").startsWith(commit.substring(0, 8)));
        try (var walk = Files.walk(repo.resolve(".agent"))) {
            assertTrue(walk.noneMatch(p -> p.getFileName().toString().startsWith("wt-patch-")));
        }
    }

    @Test
    @DisplayName("应用阶段二次校验：绕过 stage 直接构造的补丁也写不进危险路径")
    void applyRechecksGuard() throws Exception {
        Path repo = makeRepo();
        GitWorktreeManager git = new GitWorktreeManager(ws);
        String base = git.revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(git, locator(repo));

        CodePatch evil = new CodePatch("pt-evil", "P", "dev", base, "agent/fix-pt-evil",
                List.of(new CodePatch.FileEdit(".git/hooks/pre-commit", "#!/bin/sh\n")),
                "", "", 1, 0, 0L);
        assertThrows(PatchPathDeniedException.class, () -> patcher.apply(evil));
        assertFalse(Files.exists(repo.resolve(".agent/worktrees/wt-patch-pt-evil")));
    }

    private Path makeRepo() throws Exception {
        Path dir = userSpace.resolve("demo");
        Files.createDirectories(dir.resolve("ci"));
        Files.createDirectories(dir.resolve("src/main/java"));
        exec(dir, "git", "init", "-q");
        Files.writeString(dir.resolve("ci/pipeline.yml"), "build:\n  command: mvn -B -q package\n");
        Files.writeString(dir.resolve("src/main/java/App.java"), "class App {}\n");
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v1");
        return dir;
    }

    private ProjectRepoLocator locator(Path repo) {
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
            }

            @Override
            public StorageMode modeOf(String projectId) {
                return StorageMode.DIRECT;
            }
        };
    }

    private void exec(Path dir, String... args) throws Exception {
        Process p = new ProcessBuilder(args).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", args) + " failed: " + out);
        }
    }
}
