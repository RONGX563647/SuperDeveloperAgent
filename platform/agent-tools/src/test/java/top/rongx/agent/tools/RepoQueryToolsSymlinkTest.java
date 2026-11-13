package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.RepoInspector;
import top.rongx.agent.domain.project.ProjectContext;

/** 仓库文件访问必须挡住符号链接逃逸（link -> /etc 之类）。 */
class RepoQueryToolsSymlinkTest {

    @TempDir
    Path temp;

    @Test
    void symlinkCannotEscapeRepository() throws IOException {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "TOP_SECRET");
        Files.writeString(repo.resolve("ok.txt"), "hello");
        try {
            Files.createSymbolicLink(repo.resolve("file-link"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(repo.resolve("dir-link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }

        RepoQueryTools tools = new RepoQueryTools(emptyInspector(), locator(repo),
                new SecretRedactor(null));
        ProjectContext ctx = new ProjectContext("P", "test", "u1", "", null);

        // 正常文件仍可读
        assertTrue(tools.readFile(ctx, "ok.txt").contains("hello"));
        // 文件链接 / 目录链接 / ../ / 绝对路径：全部拒绝
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(ctx, "file-link"));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(ctx, "dir-link/secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(ctx, "../outside/secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(ctx, "/etc/passwd"));
    }

    @Test
    void grepSkipsSymlinkedFilesAndDirectories() throws IOException {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "TOP_SECRET");
        try {
            Files.createSymbolicLink(repo.resolve("file-link"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(repo.resolve("dir-link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }

        RepoQueryTools tools = new RepoQueryTools(emptyInspector(), locator(repo),
                new SecretRedactor(null));
        ProjectContext ctx = new ProjectContext("P", "test", "u1", "", null);

        String result = tools.grep(ctx, "TOP_SECRET", null, 20, true);
        assertTrue(result.contains("matches: 0"), result);
    }

    private static ProjectRepoLocator locator(Path repo) {
        return new ProjectRepoLocator() {
            @Override
            public Optional<Path> repoDir(String projectId) {
                return Optional.of(repo);
            }

            @Override
            public Optional<Path> worktreeRoot(String projectId) {
                return Optional.empty();
            }

            @Override
            public void sync(String projectId) {
                // 只读工具不触发同步
            }

            @Override
            public StorageMode modeOf(String projectId) {
                return StorageMode.DIRECT;
            }
        };
    }

    private static RepoInspector emptyInspector() {
        return new RepoInspector() {
            @Override
            public RepoStatus status(String projectId) {
                return new RepoStatus("DIRECT", null, null, null, 0, 0,
                        List.of(), List.of(), List.of());
            }

            @Override
            public List<Commit> commits(String projectId, int limit, int skip) {
                return List.of();
            }

            @Override
            public List<String> logRange(String projectId, String from, String to) {
                return List.of();
            }

            @Override
            public List<String> diffStat(String projectId, String from, String to) {
                return List.of();
            }
        };
    }
}
