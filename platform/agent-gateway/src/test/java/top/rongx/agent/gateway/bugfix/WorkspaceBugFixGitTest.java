package top.rongx.agent.gateway.bugfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.bugfix.BugFixEdit;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 修复补丁落库测试：真实本地 git 仓库上验证「基线 worktree → 写文件 → 独立分支提交」，
 * 并确认路径逃逸与凭证文件仍被拒绝。
 */
class WorkspaceBugFixGitTest {

    @TempDir
    Path temp;

    @Test
    void commitsFixOnDedicatedBranchAndKeepsWorkingTreeClean() throws Exception {
        Path repo = initRepo();
        GitWorktreeManager manager = new GitWorktreeManager(temp.resolve("workspace"));
        WorkspaceBugFixGit git = new WorkspaceBugFixGit(manager, direct(repo), null);
        String base = manager.revParse(repo, "HEAD");

        BugFixRun run = run(base, "agent/bugfix-test001");
        String sha = git.commitFix(run,
                List.of(BugFixEdit.full("src/main/App.java",
                        "public class App { public static int value() { return 1; } }")),
                "fix(bug): 修复空值");

        assertNotEquals(base, sha, "应产生新的修复提交");
        assertEquals("public class App { public static int value() { return 1; } }",
                manager.showFile(repo, sha, "src/main/App.java"));
        assertEquals(sha, manager.revParse(repo, "refs/heads/agent/bugfix-test001"),
                "修复提交必须落在独立分支");
        assertEquals(base, manager.revParse(repo, "refs/heads/master"),
                "默认分支不允许被自动修复改动");
        assertTrue(Files.notExists(repo.resolve(".agent/worktrees/wt-bugfix-bf-test")),
                "临时 worktree 应被清理");
    }

    @Test
    void rejectsEscapingPathAndCredentialFiles() throws Exception {
        Path repo = initRepo();
        GitWorktreeManager manager = new GitWorktreeManager(temp.resolve("workspace"));
        WorkspaceBugFixGit git = new WorkspaceBugFixGit(manager, direct(repo), null);
        BugFixRun run = run(manager.revParse(repo, "HEAD"), "agent/bugfix-test002");

        assertThrows(IllegalArgumentException.class, () -> git.commitFix(run,
                List.of(BugFixEdit.full("../outside.txt", "x")), "bad"));
        assertThrows(IllegalArgumentException.class, () -> git.commitFix(run,
                List.of(BugFixEdit.full(".env", "TOKEN=x")), "bad"));
        assertThrows(IllegalArgumentException.class, () -> git.commitFix(run,
                List.of(BugFixEdit.full(".git/config", "x")), "bad"));
    }

    @Test
    void appliesUniqueSearchReplaceAndCreatesNewFiles() throws Exception {
        Path repo = initRepo();
        GitWorktreeManager manager = new GitWorktreeManager(temp.resolve("workspace"));
        WorkspaceBugFixGit git = new WorkspaceBugFixGit(manager, direct(repo), null);
        BugFixRun run = run(manager.revParse(repo, "HEAD"), "agent/bugfix-test003");

        String sha = git.commitFix(run, List.of(
                BugFixEdit.replace("src/main/App.java", "return 0;", "return 1;"),
                BugFixEdit.replace("src/main/New.java", "", "class New {}\n")), "fix: 片段替换");

        assertEquals("public class App { public static int value() { return 1; } }",
                manager.showFile(repo, sha, "src/main/App.java"));
        assertEquals("class New {}\n", manager.showFile(repo, sha, "src/main/New.java"));
    }

    @Test
    void rejectsMissingOrAmbiguousSnippet() throws Exception {
        Path repo = initRepo();
        GitWorktreeManager manager = new GitWorktreeManager(temp.resolve("workspace"));
        WorkspaceBugFixGit git = new WorkspaceBugFixGit(manager, direct(repo), null);
        BugFixRun run = run(manager.revParse(repo, "HEAD"), "agent/bugfix-test004");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> git.commitFix(run,
                        List.of(BugFixEdit.replace("src/main/App.java", "not-found", "x")), "bad"));
        assertTrue(missing.getMessage().contains("未在基线文件中出现"), missing.getMessage());

        Files.writeString(repo.resolve("src/main/App.java"),
                "class App { int a() { return 0; } int b() { return 0; } }",
                StandardCharsets.UTF_8);
        exec(repo, "git", "add", "-A");
        exec(repo, "git", "-c", "user.name=agent", "-c", "user.email=agent@local",
                "commit", "--quiet", "-m", "dup");
        String base = manager.revParse(repo, "HEAD");
        BugFixRun dupRun = run(base, "agent/bugfix-test005");

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class,
                () -> git.commitFix(dupRun,
                        List.of(BugFixEdit.replace("src/main/App.java", "return 0;", "return 1;")),
                        "bad"));
        assertTrue(ambiguous.getMessage().contains("出现多次"), ambiguous.getMessage());
    }

    @Test
    void rejectsPlainDirectoryNestedInAnotherRepo() throws Exception {
        Path repo = initRepo();
        Path plain = Files.createDirectories(repo.resolve("sub/plain"));
        GitWorktreeManager manager = new GitWorktreeManager(temp.resolve("workspace"));
        WorkspaceBugFixGit git = new WorkspaceBugFixGit(manager, direct(plain), null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> git.headSha("p1"));
        assertTrue(error.getMessage().contains("不是 git 仓库根目录"), error.getMessage());
    }

    private Path initRepo() throws IOException, InterruptedException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("src/main"));
        Files.writeString(repo.resolve("src/main/App.java"),
                "public class App { public static int value() { return 0; } }",
                StandardCharsets.UTF_8);
        exec(repo, "git", "init", "--quiet", "-b", "master");
        exec(repo, "git", "add", "-A");
        exec(repo, "git", "-c", "user.name=agent", "-c", "user.email=agent@local",
                "commit", "--quiet", "-m", "init");
        return repo;
    }

    private static void exec(Path cwd, String... argv) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(argv).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(String.join(" ", argv) + " 失败：" + output);
        }
    }

    private static ProjectRepoLocator direct(Path repo) {
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

    private static BugFixRun run(String baseSha, String branch) {
        long now = System.currentTimeMillis();
        return new BugFixRun("bf-test", "task-1", "u1", "p1", "GITHUB", "o/r", "1",
                "空值导致 500", "打开页面报错", branch,
                top.rongx.agent.domain.bugfix.BugFixStage.FIX,
                top.rongx.agent.domain.bugfix.BugFixStatus.RUNNING, "执行中", "", "", "",
                baseSha, "", "test", "", "", 0, now, now, 0);
    }
}
