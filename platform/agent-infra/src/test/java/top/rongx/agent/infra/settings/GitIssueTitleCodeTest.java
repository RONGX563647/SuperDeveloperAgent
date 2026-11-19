package top.rongx.agent.infra.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 标题带任务编码（FEAT-4 第 1 项）。
 *
 * <p>需求原文：「新建任务标题需要带任务编码，git 提交和存档使用此编码作为标题」。
 * 编码是创建时按分类自增分配的，调用方提交草稿时还不知道自己会拿到哪个编号，
 * 因此只能由存储层统一加前缀——否则三个入口（任务页、Agent 工具、Git webhook）
 * 会各自实现一套，早晚不一致。
 */
class GitIssueTitleCodeTest {

    @TempDir
    Path tmp;

    private SqliteGitIssueStore store() {
        return new SqliteGitIssueStore(tmp.toString(), new SecretBox(tmp.toString(), ""));
    }

    private static GitIssue draft(String id, String kind, String title) {
        long now = System.currentTimeMillis();
        return new GitIssue(id, "", "u1", "p1", "GITHUB", "o/r", "", title, "", "OPEN",
                kind, "P2", "", "", "PLATFORM", "LOCAL", "", "", now, now);
    }

    @Test
    @DisplayName("新建任务时标题自动带编码，且与 code 字段一致")
    void titleCarriesAllocatedCode() {
        SqliteGitIssueStore store = store();

        GitIssue bug = store.save(draft("t1", "BUG", "登录失败"));
        GitIssue feat = store.save(draft("t2", "FEAT", "支持导出"));

        assertEquals("BUG-1", bug.code());
        assertEquals("BUG-1 登录失败", bug.title(), "标题应带编码前缀");
        assertEquals("FEAT-1", feat.code());
        assertEquals("FEAT-1 支持导出", feat.title());

        // 读回来也要带（不能只在返回值里加）
        assertEquals("BUG-1 登录失败", store.find("t1", "u1").orElseThrow().title());
    }

    @Test
    @DisplayName("标题已自己写了编码就不重复加（幂等）")
    void doesNotDoublePrefix() {
        assertEquals("BUG-7 登录失败",
                SqliteGitIssueStore.withCodePrefix("BUG-7 登录失败", "BUG-7"));
        // 冒号形式也算已带编码：保留原文写法，不改写用户输入
        assertEquals("BUG-7: 登录失败",
                SqliteGitIssueStore.withCodePrefix("BUG-7: 登录失败", "BUG-7"));
        assertEquals("BUG-7", SqliteGitIssueStore.withCodePrefix("BUG-7", "BUG-7"));
        // 大小写不同也算已带
        assertEquals("bug-7 登录失败",
                SqliteGitIssueStore.withCodePrefix("bug-7 登录失败", "BUG-7"));
    }

    @Test
    @DisplayName("编码只是标题前缀的一部分时不算已带，仍要加")
    void prefixMustBeStandalone() {
        // "BUG-7x" 不是编码 BUG-7，必须补上真正的编码
        assertEquals("BUG-7 BUG-7xx 说明",
                SqliteGitIssueStore.withCodePrefix("BUG-7xx 说明", "BUG-7"));
    }

    @Test
    @DisplayName("更新标题不会被二次加前缀")
    void updateKeepsTitleAsIs() {
        SqliteGitIssueStore store = store();
        GitIssue created = store.save(draft("t1", "BUG", "登录失败"));

        long now = System.currentTimeMillis();
        GitIssue updated = store.save(new GitIssue(created.id(), created.code(), "u1", "p1",
                "GITHUB", "o/r", "", "登录失败（已定位）", "", "OPEN", "BUG", "P2", "", "",
                "PLATFORM", "LOCAL", "", "", created.createdAt(), now));

        assertEquals("登录失败（已定位）", updated.title(),
                "更新路径不应再动标题，否则用户改一次就多一层前缀");
        assertEquals("BUG-1", updated.code(), "更新不改变已分配编码");
    }

    @Test
    @DisplayName("空标题与空编码安全")
    void blankIsSafe() {
        assertEquals("", SqliteGitIssueStore.withCodePrefix("", "BUG-1"));
        assertEquals("登录失败", SqliteGitIssueStore.withCodePrefix("登录失败", ""));
        assertEquals("", SqliteGitIssueStore.withCodePrefix(null, null));
        assertTrue(SqliteGitIssueStore.withCodePrefix("  登录失败  ", "BUG-1")
                .startsWith("BUG-1 登录失败"));
    }
}
