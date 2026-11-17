package top.rongx.agent.infra.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.infra.security.SecretBox;

class SqliteGitIssueStoreCodeTest {

    @TempDir
    java.nio.file.Path tmp;

    @Test
    @DisplayName("任务编码按项目+分类自增：FEAT-1 / BUG-1，创建后更新不变")
    void allocatesReadableCode() {
        SqliteGitIssueStore store = new SqliteGitIssueStore(tmp.toString(),
                new SecretBox(tmp.toString(), ""));
        long now = System.currentTimeMillis();

        GitIssue bug1 = store.save(issue("t1", "", "p1", "BUG", "登录失败", now));
        GitIssue bug2 = store.save(issue("t2", "", "p1", "BUG", "列表为空", now + 1));
        GitIssue feat = store.save(issue("t3", "", "p1", "FEAT", "支持导出", now + 2));
        GitIssue other = store.save(issue("t4", "", "p2", "BUG", "另一个项目", now + 3));

        assertEquals("BUG-1", bug1.code());
        assertEquals("BUG-2", bug2.code());
        assertEquals("FEAT-1", feat.code());
        assertEquals("BUG-1", other.code(), "编码按项目隔离");

        // 更新（例如 Git 同步）不能改变已分配的编码
        GitIssue updated = store.save(new GitIssue(bug1.id(), bug1.code(), bug1.userId(),
                bug1.projectId(), bug1.provider(), bug1.repo(), bug1.number(), "登录失败（已定位）",
                bug1.body(), bug1.state(), bug1.kind(), bug1.priority(), bug1.labels(),
                bug1.url(), "GIT", "SYNCED", "", bug1.assignee(), bug1.createdAt(), now + 10));
        assertEquals("BUG-1", updated.code());

        assertTrue(store.list("u1", null, null, "登录").stream()
                .anyMatch(issue -> "BUG-1".equals(issue.code())));
    }

    @Test
    @DisplayName("任务版本号：保存/更新往返，标签里的 semver 自动回填")
    void keepsVersion() {
        SqliteGitIssueStore store = new SqliteGitIssueStore(tmp.toString(),
                new SecretBox(tmp.toString(), ""));
        long now = System.currentTimeMillis();

        GitIssue issue = store.save(new GitIssue("t1", "", "u1", "p1", "GITHUB", "o/r", "",
                "关联版本", "", "OPEN", "FEAT", "P1", "feat", "", "PLATFORM", "LOCAL", "", "",
                now, now, "0.2.0"));
        assertEquals("0.2.0", issue.version());
        assertEquals("0.2.0", store.find("t1", "u1").orElseThrow().version(), "版本号需持久化");

        // Git 同步来的 Issue 没有独立字段：从 version: 标签回填版本
        GitIssue fromLabels = new GitIssue("t2", "", "u1", "p1", "GITHUB", "o/r", "", "远端任务",
                "", "OPEN", "BUG", "P2", "bug,version:0.3.1", "", "GIT", "SYNCED", "", "",
                now, now);
        assertEquals("0.3.1", fromLabels.version());
        assertEquals("0.3.1", store.save(fromLabels).version());
    }

    private static GitIssue issue(String id, String code, String projectId, String kind,
                                  String title, long at) {
        return new GitIssue(id, code, "u1", projectId, "GITHUB", "o/r", "", title, "", "OPEN",
                kind, "P2", "bug", "", "PLATFORM", "LOCAL", "", "", at, at);
    }
}
