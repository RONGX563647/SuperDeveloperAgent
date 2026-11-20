package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 任务接口的字段口径：版本号/标签必须读得出、只改一次的字段不丢其它字段。
 *
 * <p>这条契约就是「任务栏版本号太丑难用」的后端那一半：后端先把 version/labels
 * 稳定地写进去、读出来、给出候选，前端才有东西可展示。
 */
class GitIssueControllerFieldContractTest {

    @TempDir
    java.nio.file.Path workspace;

    @Test
    @DisplayName("PUT 局部更新：只改 version，标题与标签保持不变")
    void partialUpdateKeepsOtherFields() {
        FakeService service = new FakeService();
        service.current = sample("BUG-1", "0.1.2", "bug,待排查");
        GitIssueController controller = controller(service, new FakeLocator());

        controller.update("task-1", Map.of("version", "0.2.0"));

        assertEquals("0.2.0", service.saved.version(), "版本号必须写进去");
        assertEquals("登录失败", service.saved.title(), "未提交的标题不能被清空");
        assertEquals("bug,待排查", service.saved.labels(), "未提交的标签不能被清空");
        assertEquals("BUG-1", service.current.code(), "编码不随更新改变");
    }

    @Test
    @DisplayName("PUT 局部更新：只改 labels，版本号保持不变")
    void partialUpdateKeepsVersion() {
        FakeService service = new FakeService();
        service.current = sample("FEAT-2", "0.1.2", "feat");
        GitIssueController controller = controller(service, new FakeLocator());

        controller.update("task-1", Map.of("labels", "feat,高优"));

        assertEquals("feat,高优", service.saved.labels());
        assertEquals("0.1.2", service.saved.version(), "未提交的版本号不能被清空");
    }

    @Test
    @DisplayName("显式传空串表示有意清空，而不是当成未提交")
    void explicitEmptyClearsField() {
        FakeService service = new FakeService();
        service.current = sample("BUG-1", "0.1.2", "bug");
        GitIssueController controller = controller(service, new FakeLocator());

        Map<String, Object> body = new HashMap<>();
        body.put("version", "");
        controller.update("task-1", body);

        assertTrue(service.saved.version().isBlank(), "显式空串应当清空版本号");
    }

    @Test
    @DisplayName("创建时版本号与标签原样进入写入模型")
    void createCarriesVersionAndLabels() {
        FakeService service = new FakeService();
        service.current = sample("BUG-1", "", "");
        GitIssueController controller = controller(service, new FakeLocator());

        controller.create(Map.of("title", "新建任务", "version", "0.3.0", "labels", "bug,紧急"));

        assertEquals("0.3.0", service.saved.version());
        assertEquals("bug,紧急", service.saved.labels());
    }

    @Test
    @DisplayName("版本候选项：返回分支、建议值与标签列表")
    void versionsExposeSuggestedAndTags() {
        FakeService service = new FakeService();
        FakeLocator locator = new FakeLocator();
        locator.branch = "0.1.0-snapshot";
        locator.tags = List.of("0.1.2", "0.1.1");
        GitIssueController controller = controller(service, locator);

        Map<String, Object> versions = controller.versions("p1", null);

        assertEquals("0.1.0-snapshot", versions.get("branch"));
        assertEquals("0.1.3", versions.get("suggested"), "版本线分支取最大补丁 +1");
        assertEquals(List.of("0.1.2", "0.1.1"), versions.get("tags"));
    }

    @Test
    @DisplayName("kind=FEAT 时建议值是下一个次版本，与 Bug 的补丁口径分开")
    void versionsRespectKind() {
        FakeLocator locator = new FakeLocator();
        locator.branch = "0.1.0-snapshot";
        locator.tags = List.of("0.1.2", "0.1.1");
        GitIssueController controller = controller(new FakeService(), locator);

        assertEquals("0.2.0", controller.versions("p1", "FEAT").get("suggested"),
                "Feat 应给次版本，不能复用 Bug 的补丁建议值");
    }

    @Test
    @DisplayName("未给项目时版本候选项为空结构，不抛异常")
    void versionsWithoutProjectAreEmpty() {
        Map<String, Object> versions =
                controller(new FakeService(), new FakeLocator()).versions(null, null);

        assertEquals("", versions.get("branch"));
        assertEquals("", versions.get("suggested"));
        assertEquals(List.of(), versions.get("tags"));
    }

    @Test
    @DisplayName("更新时标题被显式清空要拒绝（任务必须有标题）")
    void blankTitleRejected() {
        FakeService service = new FakeService();
        service.current = sample("BUG-1", "0.1.2", "bug");
        GitIssueController controller = controller(service, new FakeLocator());

        assertThrows(ResponseStatusException.class,
                () -> controller.update("task-1", Map.of("title", "   ")));
    }

    @Test
    @DisplayName("列表接口回显 version/labels/code，供任务栏渲染")
    void listReturnsFields() {
        FakeService service = new FakeService();
        service.rows = List.of(sample("BUG-7", "0.1.9", "bug,P1"));
        List<GitIssue> rows = controller(service, new FakeLocator()).list(null, null, null);

        assertEquals(1, rows.size());
        assertEquals("BUG-7", rows.get(0).code());
        assertEquals("0.1.9", rows.get(0).version());
        assertEquals("bug,P1", rows.get(0).labels());
    }

    private GitIssueController controller(GitIssueService issues, ProjectRepoLocator locator) {
        CurrentUser user = mock(CurrentUser.class);
        when(user.requireUserId()).thenReturn("u1");
        return new GitIssueController(issues, user, locator, workspace.toString());
    }

    private static GitIssue sample(String code, String version, String labels) {
        long now = System.currentTimeMillis();
        return new GitIssue("task-1", code, "u1", "p1", "GITHUB", "o/r", "", "登录失败", "", "OPEN",
                "BUG", "P0", labels, "", "PLATFORM", "LOCAL", "", "", now, now, version);
    }

    private static final class FakeLocator implements ProjectRepoLocator {
        private String branch = "";
        private List<String> tags = List.of();

        @Override
        public Optional<java.nio.file.Path> repoDir(String projectId) {
            return Optional.empty();
        }

        @Override
        public Optional<java.nio.file.Path> worktreeRoot(String projectId) {
            return Optional.empty();
        }

        @Override
        public void sync(String projectId) {
        }

        @Override
        public StorageMode modeOf(String projectId) {
            return StorageMode.DIRECT;
        }

        @Override
        public List<String> listTags(String projectId, int limit) {
            return tags;
        }

        @Override
        public String currentBranch(String projectId) {
            return branch;
        }
    }

    /** 只记录最后一次写入模型，其余按接口最小实现。 */
    private static final class FakeService implements GitIssueService {
        private GitIssue current;
        private GitIssue saved;
        private List<GitIssue> rows = List.of();

        @Override
        public List<GitIssue> list(String userId, String provider, String state, String keyword) {
            return rows;
        }

        @Override
        public GitIssue get(String userId, String id) {
            return current;
        }

        @Override
        public GitIssue create(String userId, GitIssue.Draft draft) {
            return toIssue(draft);
        }

        @Override
        public GitIssue update(String userId, String id, GitIssue.Draft draft) {
            return toIssue(draft);
        }

        @Override
        public void delete(String userId, String id) {
        }

        @Override
        public GitIssue setState(String userId, String id, String state) {
            return current;
        }

        @Override
        public List<GitIssueComment> comments(String userId, String issueId) {
            return List.of();
        }

        @Override
        public GitIssueComment comment(String userId, String issueId, String body) {
            return null;
        }

        @Override
        public void deleteComment(String userId, String issueId, String commentId) {
        }

        @Override
        public GitIssue sync(String userId, String id) {
            return current;
        }

        @Override
        public long openCount(String userId) {
            return 0;
        }

        private GitIssue toIssue(GitIssue.Draft draft) {
            long now = System.currentTimeMillis();
            String code = current == null ? "BUG-1" : current.code();
            saved = new GitIssue("task-1", code, "u1",
                    draft.projectId(), draft.provider(), draft.repo(), "", draft.title(),
                    draft.body(), draft.state(), draft.kind(), draft.priority(), draft.labels(),
                    "", "PLATFORM", "LOCAL", "", draft.assignee(), now, now, draft.version());
            return saved;
        }
    }
}
