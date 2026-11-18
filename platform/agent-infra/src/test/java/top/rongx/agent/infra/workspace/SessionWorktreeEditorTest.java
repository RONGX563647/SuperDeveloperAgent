package top.rongx.agent.infra.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.SessionWorkspaceEditor;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 会话工作树编辑：真实 git 仓库 + DIRECT 布局（临时目录）。
 *
 * <p>覆盖 open 幂等、写→diff→提交闭环、路径逃逸与凭证文件拒绝。
 */
class SessionWorktreeEditorTest {

    @TempDir
    Path userDir;

    @TempDir
    Path platformRoot;

    private SessionWorktreeEditor editor;

    @BeforeEach
    void setUp() throws Exception {
        git(userDir, "init", "-q");
        Files.writeString(userDir.resolve("README.md"), "demo v1\n");
        git(userDir, "add", "-A");
        git(userDir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v1");

        GitWorktreeManager manager = new GitWorktreeManager(platformRoot);
        ProjectRepoLocator locator = new ProjectRepoLocator() {
            @Override
            public Optional<Path> repoDir(String projectId) {
                return Optional.of(userDir);
            }

            @Override
            public Optional<Path> worktreeRoot(String projectId) {
                return Optional.of(userDir.resolve(".agent").resolve("worktrees"));
            }

            @Override
            public void sync(String projectId) {
                // 测试不联网
            }

            @Override
            public StorageMode modeOf(String projectId) {
                return StorageMode.DIRECT;
            }
        };
        editor = new SessionWorktreeEditor(manager, locator);
    }

    @Test
    @DisplayName("open 建独立工作树与 agent/session-* 分支，重复调用复用")
    void opensAndReusesSessionWorktree() {
        SessionWorkspaceEditor.Session first = editor.open("RY", "wt-demo");

        assertEquals("agent/session-wt-demo", first.branch());
        assertTrue(first.created(), "首次应新建");
        assertTrue(Files.exists(Path.of(first.worktree()).resolve("README.md")));
        assertTrue(Path.of(first.worktree()).startsWith(userDir.resolve(".agent").resolve("worktrees")));

        SessionWorkspaceEditor.Session again = editor.open("RY", "wt-demo");
        assertFalse(again.created(), "重复 open 应复用");
        assertEquals(first.worktree(), again.worktree());
        assertEquals(first.branch(), again.branch());
    }

    @Test
    @DisplayName("写入 → diff 可见 → 提交后 diff 清空")
    void writeDiffCommitRoundTrip() {
        editor.open("RY", "wt-demo");

        editor.write("RY", "wt-demo", "src/app.txt", "hello\n");
        Path file = userDir.resolve(".agent").resolve("worktrees")
                .resolve("wt-session-wt-demo").resolve("src/app.txt");
        assertTrue(Files.exists(file));
        assertEquals("hello\n", editor.read("RY", "wt-demo", "src/app.txt"));
        assertTrue(editor.list("RY", "wt-demo", "src", 1).contains("src/app.txt"));

        SessionWorkspaceEditor.Diff diff = editor.diff("RY", "wt-demo");
        assertFalse(diff.changes().isEmpty(), "未提交改动应出现在 diff 中");

        SessionWorkspaceEditor.Commit commit = editor.commit("RY", "wt-demo", "agent: add app");
        assertTrue(commit.sha().matches("[0-9a-f]{40}"), commit.sha());
        assertEquals("agent/session-wt-demo", commit.branch());
        assertTrue(commit.files().stream().anyMatch(f -> f.startsWith("src")), commit.files().toString());
        assertTrue(editor.diff("RY", "wt-demo").changes().isEmpty(), "提交后应无未提交改动");
    }

    @Test
    @DisplayName("路径逃逸与凭证文件被拒绝")
    void rejectsEscapeAndCredentials() {
        editor.open("RY", "wt-demo");

        assertThrows(PlatformException.class,
                () -> editor.write("RY", "wt-demo", "../escape.txt", "x"));
        assertThrows(PlatformException.class,
                () -> editor.write("RY", "wt-demo", ".env.local", "SECRET=1"));
        assertThrows(PlatformException.class,
                () -> editor.write("RY", "wt-demo", "keys/id_rsa", "x"));
        assertFalse(Files.exists(userDir.resolve("escape.txt")));
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
