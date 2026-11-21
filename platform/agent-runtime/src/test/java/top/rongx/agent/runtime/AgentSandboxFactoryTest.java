package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.project.ProjectContext;

class AgentSandboxFactoryTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("工作树存在时装配 Harness Docker 沙箱并挂载 git 目录")
    void createsSandboxForWorktree() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path worktrees = Files.createDirectories(repo.resolve(".agent/worktrees"));
        Files.createDirectories(worktrees.resolve("wt-1"));

        AgentSandboxProperties props = new AgentSandboxProperties();
        props.setEnabled(true);
        AgentSandboxFactory factory = new AgentSandboxFactory(locator(repo, worktrees), props);

        AgentSandboxFactory.Sandbox sandbox = factory.create(ctx("wt-1")).orElseThrow();

        assertEquals(worktrees.resolve("wt-1"), sandbox.workspace());
        assertNotNull(sandbox.filesystem());
    }

    @Test
    @DisplayName("关闭开关或工作树不存在时保持原工具面")
    void skipsWhenDisabledOrMissing() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo2"));
        Path worktrees = Files.createDirectories(repo.resolve(".agent/worktrees"));

        AgentSandboxProperties props = new AgentSandboxProperties();
        AgentSandboxFactory disabled = new AgentSandboxFactory(locator(repo, worktrees), props);
        assertTrue(disabled.create(ctx("wt-1")).isEmpty());

        props.setEnabled(true);
        AgentSandboxFactory enabled = new AgentSandboxFactory(locator(repo, worktrees), props);
        assertTrue(enabled.create(ctx("wt-missing")).isEmpty());
    }

    private static ProjectContext ctx(String worktree) {
        return new ProjectContext("p1", "dev", "u1", null, worktree);
    }

    private static ProjectRepoLocator locator(Path repo, Path worktrees) {
        return new ProjectRepoLocator() {
            @Override
            public Optional<Path> repoDir(String projectId) {
                return Optional.of(repo);
            }

            @Override
            public Optional<Path> worktreeRoot(String projectId) {
                return Optional.of(worktrees);
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
}
