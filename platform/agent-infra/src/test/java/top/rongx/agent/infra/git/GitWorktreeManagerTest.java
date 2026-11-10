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

/**
 * GitWorktreeManager 真实集成测试：全部用本地目录 + 本地 bare 模拟（无网络、无真实远端）。
 */
class GitWorktreeManagerTest {

    @TempDir
    Path ws;

    @TempDir
    Path userSpace;

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

    private String posix(Path p) {
        return p.toString().replace('\\', '/');
    }
}
