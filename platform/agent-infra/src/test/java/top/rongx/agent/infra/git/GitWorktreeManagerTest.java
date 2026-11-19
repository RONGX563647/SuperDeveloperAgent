package top.rongx.agent.infra.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PlatformException;
import org.junit.jupiter.api.Tag;

/**
 * GitWorktreeManager 真实集成测试：全部用本地目录 + 本地 bare 模拟（无网络、无真实远端）。
 */
// 慢测试：真实进程 / 真实 git / Spring 上下文 / BCrypt 迭代，提交时跳过（pre-commit 加 -DexcludedGroups=slow），推送与 CI 全跑。
@Tag("slow")
class GitWorktreeManagerTest {

    @TempDir
    Path ws;

    @TempDir
    Path userSpace;

    @Test
    @DisplayName("修复分支快进合并进主分支；主分支有新提交时拒绝自动合并")
    void mergeFastForwardIntoDefaultBranch() throws Exception {
        makeUserRepo("merge-demo");
        Path dir = userSpace.resolve("merge-demo");
        GitWorktreeManager m = manager();
        String base = m.currentBranch(dir);

        exec(dir, "git", "checkout", "-q", "-b", "agent/fix-1");
        Files.writeString(dir.resolve("Dockerfile"), "FROM eclipse-temurin:21\n");
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t",
                "commit", "-q", "-m", "add dockerfile");
        String fixSha = m.revParse(dir, "refs/heads/agent/fix-1");

        // 当前工作区就在主分支：走真实 merge --ff-only，文件同时落到工作区
        exec(dir, "git", "checkout", "-q", base);
        assertEquals(fixSha, m.mergeFastForward(dir, "agent/fix-1", base));
        assertTrue(Files.exists(dir.resolve("Dockerfile")));

        // 主分支再前进后，修复分支已不是快进关系
        Files.writeString(dir.resolve("README.md"), "v2\n");
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v2");
        assertThrows(PlatformException.class,
                () -> m.mergeFastForward(dir, "agent/fix-1", base));
    }

    @Test
    @DisplayName("平台自动提交绕过仓库本地 hook：归档门禁不拦截 Dockerfile 自愈")
    void commitAllBypassesRepoHooks() throws Exception {
        makeUserRepo("hook-demo");
        Path dir = userSpace.resolve("hook-demo");
        Path hooks = userSpace.resolve("blocking-hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        try {
            Files.setPosixFilePermissions(hook,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // Windows 没有 POSIX 权限位；hook 可能不执行，本用例在 Linux/CI 上才真正生效
        }
        exec(dir, "git", "config", "core.hooksPath", hooks.toAbsolutePath().toString());

        Files.writeString(dir.resolve("Dockerfile"), "FROM eclipse-temurin:21\n");
        GitWorktreeManager m = manager();
        String sha = m.commitAll(dir, "agent: ensure dockerfile");

        assertEquals(sha, m.revParse(dir, "HEAD"));
        assertTrue(Files.exists(dir.resolve("Dockerfile")));
    }

    @Test
    @DisplayName("commitOnly 用独立 index 提交未跟踪文件、绕过仓库 hook，提交后 index 干净")
    void commitOnlyCommitsUntrackedBypassingHooks() throws Exception {
        makeUserRepo("commit-only");
        Path dir = userSpace.resolve("commit-only");
        Path hooks = userSpace.resolve("blocking-hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        try {
            Files.setPosixFilePermissions(hook,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // Windows 没有 POSIX 权限位；hook 可能不执行，本用例在 Linux/CI 上才真正生效
        }
        exec(dir, "git", "config", "core.hooksPath", hooks.toAbsolutePath().toString());

        GitWorktreeManager m = manager();
        Files.writeString(dir.resolve("NewFile.txt"), "brand new\n");
        String sha = m.commitOnly(dir, "agent: add new file");

        assertEquals(sha, m.revParse(dir, "HEAD"));
        assertEquals("brand new\n", m.showFile(dir, "HEAD", "NewFile.txt"));
        // 提交后真实 index 已同步到新 HEAD：无未提交改动残留
        assertTrue(m.statusPorcelain(dir).isEmpty(), "提交后 index 应干净：" + m.statusPorcelain(dir));
    }

    /** 在用户空间造一个真实开发中的项目：git init + 一次提交。返回 HEAD。 */
    private String makeUserRepo(String name) throws Exception {
        Path dir = userSpace.resolve(name);
        Files.createDirectories(dir);
        exec(dir, "git", "init", "-q");
        Files.writeString(dir.resolve("README.md"), name + " v1\n");
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v1");
        return manager().revParse(dir, "HEAD");
    }

    private GitWorktreeManager manager() {
        return new GitWorktreeManager(ws);
    }

    /** 跑一条命令并等待完成，失败抛错。 */
    private void exec(Path dir, String... args) throws Exception {
        Process p = new ProcessBuilder(args).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }

    // ---------------------------------------------------------------- 收编

    @Test
    @DisplayName("bare 仓库上探测 status/upstream：返回空而不是抛错刷 WARN")
    void probeOnBareRepoIsQuiet() throws Exception {
        makeUserRepo("quiet-demo");
        GitWorktreeManager m = manager();
        Path bare = m.bareDir("QUIET");
        Files.createDirectories(bare);
        exec(bare, "git", "init", "--bare", "-q");

        // bare 没有工作区：git status 会 fatal，调用方应拿到空列表
        assertTrue(m.statusPorcelain(bare).isEmpty());
        // 分支没有 upstream：应返回 null（而不是抛异常）
        assertTrue(m.upstreamOf(bare, "master") == null || m.upstreamOf(bare, "master").isBlank());
    }

    @Test
    @DisplayName("L1 本地目录收编：bare 建成、agent remote 关联、HEAD 进 bare")
    void linkLocal() throws Exception {
        String head = makeUserRepo("shop-mall");
        GitWorktreeManager m = manager();
        Path userDir = userSpace.resolve("shop-mall");

        String linked = m.linkLocal("SHOP", userDir, false);

        assertEquals(head, linked);
        assertTrue(Files.isDirectory(m.bareDir("SHOP").resolve("objects")), "bare 已建");
        // 用户目录已关联 agent remote
        Process p = new ProcessBuilder("git", "remote").directory(userDir.toFile())
                .redirectErrorStream(true).start();
        String remotes = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(remotes.contains("agent"), "用户目录已有 agent remote: " + remotes);
        // HEAD 对象已进 bare（能被 rev-parse）
        assertEquals(head, m.revParse(m.bareDir("SHOP"), head));
    }

    @Test
    @DisplayName("worktree 目录还在但 .git 丢失：removeWorktreeAt 自动 prune 并清理，不抛错")
    void removesBrokenWorktreeWithoutThrowing() throws Exception {
        String head = makeUserRepo("broken-wt");
        Path repo = userSpace.resolve("broken-wt");
        GitWorktreeManager m = manager();
        Path wt = m.addWorktreeAt(repo, "wt-build-run-dead", head);
        assertTrue(Files.isRegularFile(wt.resolve(".git")), "worktree 已创建");

        // 模拟清理被中断：目录还在，但指向主仓库的 .git 文件被删
        Files.delete(wt.resolve(".git"));

        m.removeWorktreeAt(repo, "wt-build-run-dead");
        assertFalse(Files.exists(wt), "半残 worktree 目录应被删除");
        // 目录本来就不存在时也应幂等成功（清掉可能的登记残留）
        m.removeWorktreeAt(repo, "wt-build-run-gone");
    }

    @Test
    @DisplayName("L2 无 .git 目录且不允许 init → 明确报错")
    void linkLocalNoGitRefused() throws Exception {
        Path dir = userSpace.resolve("plain");
        Files.createDirectories(dir);
        GitWorktreeManager m = manager();
        GitWorktreeManager.GitIntakeException ex = assertThrows(
                GitWorktreeManager.GitIntakeException.class,
                () -> m.linkLocal("PLAIN", dir, false));
        assertTrue(ex.getMessage().contains("git init"), ex.getMessage());
    }

    @Test
    @DisplayName("L3 无 .git 目录 + 允许 init → 自动 init 并收编")
    void linkLocalAutoInit() throws Exception {
        Path dir = userSpace.resolve("plain2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "hello\n");
        GitWorktreeManager m = manager();

        String head = m.linkLocal("PLAIN2", dir, true);

        assertNotNull(head);
        assertTrue(Files.isDirectory(dir.resolve(".git")), "已 init");
        assertTrue(Files.isDirectory(m.bareDir("PLAIN2").resolve("objects")));
    }

    @Test
    @DisplayName("L4 用户目录新增提交后 fetchLocal 同步到 bare")
    void fetchLocalPicksUpNewCommits() throws Exception {
        String head1 = makeUserRepo("app");
        Path userDir = userSpace.resolve("app");
        GitWorktreeManager m = manager();
        m.linkLocal("APP", userDir, false);

        // 用户继续开发：第二次提交
        Files.writeString(userDir.resolve("new.txt"), "v2\n");
        exec(userDir, "git", "add", "-A");
        exec(userDir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v2");
        String head2 = m.revParse(userDir, "HEAD");
        assertFalse(head1.equals(head2));

        // 平台同步
        m.fetchLocal("APP", userDir);

        // bare 里能解析到新提交
        assertEquals(head2, m.revParse(m.bareDir("APP"), head2));
    }

    // ---------------------------------------------------------------- worktree

    @Test
    @DisplayName("W1 worktree 生命周期：add / list / remove")
    void worktreeLifecycle() throws Exception {
        String head = makeUserRepo("wl");
        Path userDir = userSpace.resolve("wl");
        GitWorktreeManager m = manager();
        m.linkLocal("WL", userDir, false);

        // 直接用收编返回的 HEAD（bare HEAD 可能指向 unborn 分支）
        Path wt = m.addWorktree("WL", "wt-build-run1", head);
        assertTrue(Files.isDirectory(wt), "worktree 已创建");
        assertTrue(Files.exists(wt.resolve("README.md")), "文件已检出");

        List<GitWorktreeManager.WorktreeInfo> wts = m.listWorktrees("WL");
        assertTrue(wts.stream().anyMatch(w -> w.name().equals("wt-build-run1") && w.role().equals("BUILD")));

        m.removeWorktree("WL", "wt-build-run1");
        assertFalse(Files.exists(wt), "已清理");
        assertTrue(m.listWorktrees("WL").stream().noneMatch(w -> w.name().equals("wt-build-run1")));
    }

    @Test
    @DisplayName("W2 cloneRemote（本地 bare 模拟远端）+ 从 clone 建 worktree")
    void cloneFromLocalBare() throws Exception {
        Path remoteBare = userSpace.resolve("hutool.git");
        Files.createDirectories(remoteBare);
        exec(remoteBare, "git", "init", "--bare", "-q");
        Path src = userSpace.resolve("hutool-src");
        Files.createDirectories(src);
        exec(src, "git", "init", "-q");
        Files.writeString(src.resolve("x.txt"), "1\n");
        exec(src, "git", "add", "-A");
        exec(src, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c1");
        exec(src, "git", "push", "-q", posix(remoteBare), "HEAD");

        GitWorktreeManager m = manager();
        m.cloneRemote("HUT", posix(remoteBare));

        assertTrue(Files.isDirectory(m.bareDir("HUT").resolve("objects")));
        String head = m.revParse(m.bareDir("HUT"), "HEAD");
        assertFalse(head.isBlank());

        Path wt = m.addWorktree("HUT", "wt-verify", head);
        assertTrue(Files.exists(wt.resolve("x.txt")));
    }

    @Test
    @DisplayName("W3 重复 clone 同一项目 → 拒绝")
    void cloneDuplicateRefused() throws Exception {
        Path remoteBare = userSpace.resolve("dup.git");
        Files.createDirectories(remoteBare);
        exec(remoteBare, "git", "init", "--bare", "-q");
        Path src = userSpace.resolve("dup-src");
        Files.createDirectories(src);
        exec(src, "git", "init", "-q");
        Files.writeString(src.resolve("a.txt"), "a\n");
        exec(src, "git", "add", "-A");
        exec(src, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c1");
        exec(src, "git", "push", "-q", posix(remoteBare), "HEAD");

        GitWorktreeManager m = manager();
        m.cloneRemote("DUP", posix(remoteBare));
        GitWorktreeManager.GitIntakeException ex = assertThrows(
                GitWorktreeManager.GitIntakeException.class,
                () -> m.cloneRemote("DUP", posix(remoteBare)));
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
    }

    @Test
    @DisplayName("createTag：本地创建、同提交幂等、版本号被占用时报错，并可推送到远端")
    void createAndPushTag() throws Exception {
        String head = makeUserRepo("tag-demo");
        Path dir = userSpace.resolve("tag-demo");
        GitWorktreeManager m = manager();

        m.createTag(dir, "v1.2.0", head);
        assertEquals(head, m.revParse(dir, "refs/tags/v1.2.0"));
        assertEquals(List.of("v1.2.0"), m.listTags(dir, 10), "版本号输入框的候选来源");

        // 同一提交重复发布：幂等成功
        m.createTag(dir, "v1.2.0", head);

        // 版本号已经指向别的提交：必须报错，不能悄悄改写
        Files.writeString(dir.resolve("README.md"), "v2\n");
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v2");
        String next = m.revParse(dir, "HEAD");
        GitWorktreeManager.GitIntakeException error = assertThrows(
                GitWorktreeManager.GitIntakeException.class, () -> m.createTag(dir, "v1.2.0", next));
        assertTrue(error.getMessage().contains("已存在"), error.getMessage());

        // 推送到本地 bare 模拟 origin
        Path remote = userSpace.resolve("tag-remote.git");
        Files.createDirectories(remote);
        exec(remote, "git", "init", "--bare", "-q");
        m.pushTagTo(dir, "v1.2.0", remote.toUri().toString(), null);
        assertEquals(head, m.revParse(remote, "refs/tags/v1.2.0"));
    }

    private String posix(Path p) {
        return p.toString().replace('\\', '/');
    }
}
