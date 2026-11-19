package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 任务查询工具的输出字段契约。
 *
 * <p>这组用例的存在理由：{@code version} 曾在两处输出里静默丢失（
 * task-67bd7174cf）。字段丢没丢，模型和用户都看不出来——只会表现为
 * 「问它这条任务关联哪个版本，答不上来」。所以要用测试把字段钉在输出里。
 *
 * <p>同时锁住「空值不能变成空白」：缺版本要显示成 {@code -} 或明确写「未关联」，
 * 否则模型会把空串当成「有这个字段但值是空」，与「字段压根没输出」混淆。
 */
class GitIssueQueryToolsOutputTest {

    private static final ProjectContext CTX =
            new ProjectContext("P1", "dev", "u1", null, null);

    private static GitIssue issue(String code, String version, String labels) {
        long now = System.currentTimeMillis();
        return new GitIssue("task-1", code, "u1", "P1", "GITHUB", "o/r", "7", "登录失败", "正文",
                "OPEN", "BUG", "P0", labels, "", "PLATFORM", "LOCAL", "", "", now, now, version);
    }

    @Test
    @DisplayName("get_git_issue 输出必须带 code 与 version 两行")
    void getExposesCodeAndVersion() {
        GitIssueQueryTools tools = new GitIssueQueryTools(new FakeService(issue("BUG-9", "0.1.7", "bug")));

        String out = tools.getGitIssue("task-1", CTX);

        assertTrue(out.contains("code: BUG-9"), out);
        assertTrue(out.contains("version: 0.1.7"), out);
        assertTrue(out.contains("labels: bug"), out);
    }

    @Test
    @DisplayName("list_git_issues 每行都带 v版本 与 code，缺值用占位符而不是空白")
    void listExposesVersionAndCode() {
        GitIssue noVersion = issue("BUG-1", "", "");
        GitIssueQueryTools tools = new GitIssueQueryTools(new FakeService(noVersion));

        String out = tools.listGitIssues(null, null, null, CTX);

        // 空版本显示 v-：字段位置始终存在，模型不会误判成"没这个字段"
        assertTrue(out.contains("v-"), out);
        assertTrue(out.contains("code:BUG-1"), out);
        assertFalse(out.contains("v\n"), "空版本不能变成纯空白：" + out);
        assertTrue(out.contains("labels:-"), "空标签同样给占位：" + out);
    }

    @Test
    @DisplayName("多个任务时每行都完整，不会因为首行有值就漏掉后续行")
    void everyRowCarriesFields() {
        GitIssueQueryTools tools = new GitIssueQueryTools(new FakeService(
                issue("BUG-1", "0.1.1", "bug"),
                issue("FEAT-2", "0.2.0", "feat")));

        String out = tools.listGitIssues(null, null, null, CTX);
        String[] lines = out.lines().filter(line -> line.startsWith("- ")).toArray(String[]::new);

        assertTrue(lines.length >= 2, out);
        for (String line : lines) {
            assertTrue(line.contains("v0."), "每行都要有版本：" + line);
            assertTrue(line.contains("code:"), "每行都要有编码：" + line);
        }
    }

    /** 只实现查询路径用到的方法。 */
    private static final class FakeService implements GitIssueService {
        private final List<GitIssue> rows;

        private FakeService(GitIssue... rows) {
            this.rows = List.of(rows);
        }

        @Override
        public List<GitIssue> list(String userId, String provider, String state, String keyword) {
            return rows;
        }

        @Override
        public GitIssue get(String userId, String id) {
            return rows.get(0);
        }

        @Override
        public GitIssue create(String userId, GitIssue.Draft draft) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public GitIssue update(String userId, String id, GitIssue.Draft draft) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public void delete(String userId, String id) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public GitIssue setState(String userId, String id, String state) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public List<GitIssueComment> comments(String userId, String issueId) {
            return List.of();
        }

        @Override
        public GitIssueComment comment(String userId, String issueId, String body) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public void deleteComment(String userId, String issueId, String commentId) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public GitIssue sync(String userId, String id) {
            throw new UnsupportedOperationException("只读用例");
        }

        @Override
        public long openCount(String userId) {
            return rows.size();
        }
    }
}
