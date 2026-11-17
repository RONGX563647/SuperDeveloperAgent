package top.rongx.agent.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.settings.GitHostingAccount;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 标签创建/重推：DIRECT 仓库用本地 git 模拟，重点验证降级路径与幂等重推。
 */
class DefaultRepoLocatorTagTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("无 origin 时创建标签只落本地，重推给出明确原因；未知标签重推直接失败")
    void createAndPushWithoutRemote() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        exec(repo, "git", "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        exec(repo, "git", "add", "-A");
        exec(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t",
                "commit", "-q", "-m", "init");
        String sha = execOut(repo, "git", "rev-parse", "HEAD").trim();

        try (IntakeProjectStore store = new IntakeProjectStore(tmp.resolve("intake.db"))) {
            store.upsert(new IntakeProjectStore.IntakeProject("P1", "demo", null, "local",
                    "java", 18000, 18100, "LOCAL", repo.toString(), "DIRECT"), List.of());
            DefaultRepoLocator locator = new DefaultRepoLocator(store,
                    new GitWorktreeManager(tmp.resolve("ws")), new NoAccounts(),
                    tmp.resolve("ws").toString());

            ProjectRepoLocator.TagOutcome created =
                    locator.createTag("P1", "v1.0.0", sha, "alice");
            assertTrue(created.created(), created.message());
            assertFalse(created.pushed(), "没有 origin 不能算已推送");
            assertTrue(created.message().contains("没有 origin"), created.message());

            ProjectRepoLocator.TagOutcome repush = locator.pushTag("P1", "v1.0.0", "alice");
            assertTrue(repush.created());
            assertFalse(repush.pushed());

            ProjectRepoLocator.TagOutcome missing = locator.pushTag("P1", "v9.9.9", "alice");
            assertFalse(missing.created());
            assertFalse(missing.pushed());
            assertTrue(missing.message().contains("本地不存在标签"), missing.message());
        }
    }

    @Test
    @DisplayName("仓库不存在时不抛异常，返回 FAILED 结果")
    void missingRepoIsReported() {
        try (IntakeProjectStore store = new IntakeProjectStore(tmp.resolve("intake.db"))) {
            DefaultRepoLocator locator = new DefaultRepoLocator(store,
                    new GitWorktreeManager(tmp.resolve("ws")), new NoAccounts(),
                    tmp.resolve("ws").toString());

            ProjectRepoLocator.TagOutcome outcome = locator.pushTag("NOPE", "v1.0.0", "alice");
            assertFalse(outcome.created());
            assertFalse(outcome.pushed());
            assertTrue(outcome.message().contains("仓库不存在"));
        }
    }

    /** 不配置任何托管账号：推送必须退回本机凭据而不是报错。 */
    private static final class NoAccounts implements GitHostingAccountRepository {
        @Override
        public List<GitHostingAccount> findAll(String userId) {
            return List.of();
        }

        @Override
        public Optional<GitHostingAccount> find(String id, String userId) {
            return Optional.empty();
        }

        @Override
        public GitHostingAccount save(GitHostingAccountView view, String token, String userId) {
            throw new UnsupportedOperationException("test");
        }

        @Override
        public void delete(String id, String userId) {
            throw new UnsupportedOperationException("test");
        }

        @Override
        public Optional<String> tokenOf(String id, String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<Credential> credentialFor(String userId, String repoUrl) {
            return Optional.empty();
        }
    }

    private static void exec(Path dir, String... args) throws Exception {
        String out = execOut(dir, args);
        if (out == null) {
            throw new IllegalStateException("命令失败：" + String.join(" ", args));
        }
    }

    private static String execOut(Path dir, String... args) throws Exception {
        Process p = new ProcessBuilder(args).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git 失败：" + String.join(" ", args) + "\n" + out);
        }
        return out;
    }
}
