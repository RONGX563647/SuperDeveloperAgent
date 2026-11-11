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

/**
 * 受限补丁器真实集成测试：本地 git 仓库，无网络。
 *
 * <p>验证四件事：白名单外的文件改不了、diff 能生成、应用后落到独立分支、worktree 被清理。
 */
class WorkspaceCodePatcherTest {

    @TempDir
    Path ws;

    @TempDir
    Path userSpace;

    @Test
    @DisplayName("只允许改 ci/ 与测试相关文件，越界路径被拒")
    void rejectsPathsOutsideWhitelist() throws Exception {
        Path repo = makeRepo();
        String base = new GitWorktreeManager(ws).revParse(repo, "HEAD");
        WorkspaceCodePatcher patcher = new WorkspaceCodePatcher(new GitWorktreeManager(ws), locator(repo));

        assertThrows(IllegalArgumentException.class, () -> patcher.stage("P", base,
                List.of(new CodePatch.FileEdit("src/main/java/App.java", "hacked\n"))));
        assertThrows(IllegalArgumentException.class, () -> patcher.stage("P", base,
                List.of(new CodePatch.FileEdit("../evil.sh", "rm -rf /\n"))));
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
