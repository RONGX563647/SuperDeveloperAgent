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
 * GitWorktreeManager 鐪熷疄闆嗘垚娴嬭瘯锛氬叏閮ㄧ敤鏈湴鐩綍 + 鏈湴 bare 妯℃嫙锛堟棤缃戠粶銆佹棤鐪熷疄杩滅锛夈€? */
class GitWorktreeManagerTest {

    @TempDir
    Path ws;

    @TempDir
    Path userSpace;

    private GitWorktreeManager newManager() {
        return new GitWorktreeManager(ws);
    }

    /** 鍦ㄧ敤鎴风┖闂撮€犱竴涓?鐪熷疄寮€鍙戜腑鐨勯」鐩?锛歡it init + 涓€娆℃彁浜ゃ€傝繑鍥?HEAD銆?*/
    private String makeUserRepo(String name) throws Exception {
        Path dir = userSpace.resolve(name);
        Files.createDirectories(dir);
        exec(dir, "git", "init", "-q");
        Files.writeString(dir.resolve("README.md"), name + " v1\n");
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v1");
        return manager().revParse(dir, "HEAD");
    }

    /** 璺戜竴鏉″懡浠ゅ苟绛夊緟瀹屾垚锛屽け璐ユ姏閿欍€?*/
    private void exec(Path dir, String... args) throws Exception {
        Process p = new ProcessBuilder(args).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 澶辫触锛? + out);
        }
    }

    // ---------------------------------------------------------------- 鏀剁紪

    @Test
    @DisplayName("L1 鏈湴鐩綍鏀剁紪锛歜are 寤恒€佺敤鎴风洰褰曟垚涓?worktree銆丠EAD 涓€鑷?)
    void linkLocal() throws Exception {
        String head = makeUserRepo("shop-mall");
        GitWorktreeManager m = manager();
        Path userDir = userSpace.resolve("shop-mall");

        String linked = m.linkLocal("SHOP", userDir, false);

        assertEquals(head, linked);
        assertTrue(Files.isDirectory(m.bareDir("SHOP").resolve("objects")), "bare 宸插缓");
        List<GitWorktreeManager.WorktreeInfo> wts = m.listWorktrees("SHOP");
        assertTrue(wts.stream().anyMatch(w -> w.role().equals("USER")), "鐢ㄦ埛鐩綍鐧昏涓?USER worktree锛? + wts);
    }

    @Test
    @DisplayName("L2 鏃?.git 鐩綍 + gitInitIfMissing=false 鈫?鏄庣‘鎶ラ敊")
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
    @DisplayName("L3 鏃?.git 鐩綍 + gitInitIfMissing=true 鈫?鑷姩 init 骞舵敹缂?)
    void linkLocalAutoInit() throws Exception {
        Path dir = userSpace.resolve("plain2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "hello\n");
        GitWorktreeManager m = manager();

        String head = m.linkLocal("PLAIN2", dir, true);

        assertNotNull(head);
        assertTrue(Files.isDirectory(dir.resolve(".git")), "宸?init");
        assertTrue(Files.isDirectory(m.bareDir("PLAIN2").resolve("objects")));
    }

    @Test
    @DisplayName("L4 鐢ㄦ埛鐩綍鏂板鎻愪氦鍚?fetchLocal 鑳藉悓姝ュ埌 bare")
    void fetchLocalPicksUpNewCommits() throws Exception {
        String head1 = makeUserRepo("app");
        Path userDir = userSpace.resolve("app");
        GitWorktreeManager m = manager();
        m.linkLocal("APP", userDir, false);

        // 鐢ㄦ埛缁х画寮€鍙戯細绗簩娆℃彁浜?        Files.writeString(userDir.resolve("new.txt"), "v2\n");
        exec(userDir, "git", "add", "-A");
        exec(userDir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v2");
        String head2 = m.revParse(userDir, "HEAD");
        assertFalse(head1.equals(head2));

        // 骞冲彴鍚屾
        m.fetchLocal("APP", userDir);

        // bare 閲岃兘 rev-parse 鍒版柊鎻愪氦
        assertEquals(head2, m.revParse(m.bareDir("APP"), head2));
    }

    // ---------------------------------------------------------------- worktree

    @Test
    @DisplayName("W1 add/remove worktree锛氭瀯寤?worktree 鐢熷懡鍛ㄦ湡")
    void worktreeLifecycle() throws Exception {
        makeUserRepo("wl");
        Path userDir = userSpace.resolve("wl");
        GitWorktreeManager m = manager();
        m.linkLocal("WL", userDir, false);

        Path wt = m.addWorktree("WL", "wt-build-run1", m.revParse(m.bareDir("WL"), "HEAD"));
        assertTrue(Files.isDirectory(wt), "worktree 宸插垱寤?);
        assertTrue(Files.exists(wt.resolve("README.md")), "鏂囦欢宸叉鍑?);

        List<GitWorktreeManager.WorktreeInfo> wts = m.listWorktrees("WL");
        assertTrue(wts.stream().anyMatch(w -> w.name().equals("wt-build-run1") && w.role().equals("BUILD")));

        m.removeWorktree("WL", "wt-build-run1");
        assertFalse(Files.exists(wt), "宸叉竻鐞?);
        assertTrue(m.listWorktrees("WL").stream().noneMatch(w -> w.name().equals("wt-build-run1")));
    }

    @Test
    @DisplayName("W2 cloneRemote 鍒版湰鍦?bare 浠撳簱锛堟ā鎷熻繙绔級")
    void cloneFromLocalBare() throws Exception {
        // 鍏堥€犱竴涓?杩滅"bare
        Path remoteBare = userSpace.resolve("hutool.git");
        Files.createDirectories(remoteBare);
        exec(remoteBare, "git", "init", "--bare", "-q");
        // 閫犱竴涓簮浠撳簱骞舵帹鍒?杩滅"
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

        // 浠?clone 寤?worktree
        Path wt = m.addWorktree("HUT", "wt-verify", head);
        assertTrue(Files.exists(wt.resolve("x.txt")));
    }

    @Test
    @DisplayName("W3 閲嶅 clone 鍚屼竴椤圭洰 鈫?鎷掔粷")
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
        assertTrue(ex.getMessage().contains("宸插瓨鍦?), ex.getMessage());
    }

    private String posix(Path p) {
        return p.toString().replace('\\', '/');
    }
}
