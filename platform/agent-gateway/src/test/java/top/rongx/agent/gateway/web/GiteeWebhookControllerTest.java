package top.rongx.agent.gateway.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gitee webhook 的鉴权、去重与触发（不依赖 HTTP 容器）。 */
class GiteeWebhookControllerTest {

    private static final String BODY = """
            {"after":"abcdef1234567890abcdef1234567890abcdef12",
             "project":{"path_with_namespace":"tester/RY"}}
            """;

    private static final String BODY_UNKNOWN_REPO = """
            {"after":"abcdef1234567890abcdef1234567890abcdef12",
             "project":{"path_with_namespace":"tester/UNKNOWN"}}
            """;

    private static final String ISSUE_OPEN = """
            {"hook_name":"issue_hook","action":"open","number":"I1","title":"登录失败",
             "repository":{"path_with_namespace":"tester/RY"}}
            """;

    private static final String ISSUE_CLOSED = """
            {"hook_name":"issue_hook","action":"close","number":"I1","title":"登录失败",
             "repository":{"path_with_namespace":"tester/RY"}}
            """;

    private static final Project RY =
            new Project("RY", "RuoYi", "https://gitee.com/x/RuoYi-Vue-Plus.git", "gitee", "ruoyi-plus", 8080, 8099);

    /** 只注册 RY 一个项目的仓储。 */
    private static ProjectRepository repo() {
        return new ProjectRepository() {
            @Override
            public Optional<Project> findById(String projectId) {
                return RY.id().equals(projectId) ? Optional.of(RY) : Optional.empty();
            }

            @Override
            public List<Project> findAll() {
                return List.of(RY);
            }

            @Override
            public List<ProjectEnv> findEnvs(String projectId) {
                return List.of();
            }
        };
    }

    private static class StubEngine implements PipelineEngine {
        int calls = 0;
        String projectId;
        String pipelineId;
        String sha;

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public List<String> pipelines(String projectId) {
            return List.of("verify");
        }

        @Override
        public String trigger(String projectId, String pipelineId, Map<String, String> params) {
            calls++;
            this.projectId = projectId;
            this.pipelineId = pipelineId;
            this.sha = params.get("gitSha");
            return "run-1";
        }

        @Override
        public top.rongx.agent.domain.run.StageStatus status(String runId) {
            return top.rongx.agent.domain.run.StageStatus.RUNNING;
        }

        @Override
        public List<top.rongx.agent.domain.run.StageResult> stages(String runId) {
            return List.of();
        }

        @Override
        public void cancel(String runId) {
        }

        @Override
        public List<top.rongx.agent.domain.artifact.ArtifactRef> artifacts(String runId) {
            return List.of();
        }
    }

    /** 记录事件的仓储：验证 Fast Path 建任务时不依赖数据库。 */
    private static class StubTasks implements top.rongx.agent.domain.conversation.WorkspaceTaskRepository {
        final List<top.rongx.agent.domain.conversation.WorkspaceTask> saved =
                new java.util.ArrayList<>();

        @Override
        public top.rongx.agent.domain.conversation.WorkspaceTask saveTask(
                top.rongx.agent.domain.conversation.WorkspaceTask task) {
            saved.add(task);
            return task;
        }

        @Override
        public top.rongx.agent.domain.conversation.WorkspaceTask updateTask(
                top.rongx.agent.domain.conversation.WorkspaceTask task) {
            return task;
        }

        @Override
        public Optional<top.rongx.agent.domain.conversation.WorkspaceTask> findTask(String id) {
            return saved.stream().filter(t -> t.id().equals(id)).findFirst();
        }

        @Override
        public List<top.rongx.agent.domain.conversation.WorkspaceTask> findTasksByProject(
                String projectId) {
            return saved.stream().filter(t -> t.projectId().equals(projectId)).toList();
        }

        @Override
        public List<top.rongx.agent.domain.conversation.WorkspaceTask> findTasksByConversation(
                String conversationId) {
            return List.of();
        }
    }

    @Test
    @DisplayName("issue open 事件 → 建任务并记录来源，不触发构建")
    void issueOpenCreatesTask() {
        StubEngine engine = new StubEngine();
        StubTasks tasks = new StubTasks();
        GiteeWebhookController controller =
                new GiteeWebhookController(engine, repo(), tasks, "s3cr3t");
        Map<String, Object> result = controller.event("s3cr3t", null, null, ISSUE_OPEN);
        assertEquals("issue_hook", result.get("triggerType"));
        assertEquals(1, tasks.saved.size());
        assertEquals("RY:issue_hook:I1:open", tasks.saved.get(0).sourceRef());
        assertEquals("issue_hook", tasks.saved.get(0).triggerType());
        assertEquals("RY", tasks.saved.get(0).projectId());
        assertEquals(0, engine.calls);
    }

    @Test
    @DisplayName("白名单外的事件 → 忽略且不建任务")
    void ignoresNonAllowlistedEvent() {
        StubTasks tasks = new StubTasks();
        GiteeWebhookController controller =
                new GiteeWebhookController(new StubEngine(), repo(), tasks, "s3cr3t");
        Map<String, Object> result = controller.event("s3cr3t", null, null, ISSUE_CLOSED);
        assertEquals(true, result.get("ignored"));
        assertTrue(tasks.saved.isEmpty());
    }

    @Test
    @DisplayName("同一 issue 同一动作重复投递（含重启后新实例）→ 幂等，只建一个任务")
    void deduplicatesEvents() {
        StubTasks tasks = new StubTasks();
        new GiteeWebhookController(new StubEngine(), repo(), tasks, "s3cr3t")
                .event("s3cr3t", null, null, ISSUE_OPEN);
        // 新实例模拟重启：内存去重集合清空，必须靠任务表的 source_ref 兜底
        Map<String, Object> second = new GiteeWebhookController(new StubEngine(), repo(), tasks,
                "s3cr3t").event("s3cr3t", null, null, ISSUE_OPEN);
        assertEquals(true, second.get("duplicate"));
        assertEquals(1, tasks.saved.size());
    }

    @Test
    @DisplayName("未加引号的 issue number 也能作为去重键")
    void readsUnquotedNumber() {
        StubTasks tasks = new StubTasks();
        GiteeWebhookController controller =
                new GiteeWebhookController(new StubEngine(), repo(), tasks, "s3cr3t");
        Map<String, Object> result = controller.event("s3cr3t", null, null, """
                {"hook_name":"issue_hook","action":"open","number":42,"title":"登录失败",
                 "repository":{"path_with_namespace":"tester/RY"}}
                """);
        assertEquals("RY:issue_hook:42:open", result.get("sourceRef"));
        assertEquals(1, tasks.saved.size());
    }

    @Test
    @DisplayName("未配置密钥时拒绝，不静默放行")
    void rejectsWhenNoSecret() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller =
                new GiteeWebhookController(engine, repo(), new StubTasks(), "");
        assertThrows(ResponseStatusException.class,
                () -> controller.push("anything", null, BODY));
        assertEquals(0, engine.calls);
    }

    @Test
    @DisplayName("Token 正确 → 触发 verify，取仓库名作为 projectId")
    void triggersWithToken() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller =
                new GiteeWebhookController(engine, repo(), new StubTasks(), "s3cr3t");
        Map<String, Object> result = controller.push("s3cr3t", null, BODY);
        assertEquals("run-1", result.get("runId"));
        assertEquals("RY", engine.projectId);
        assertEquals("verify", engine.pipelineId);
        assertEquals("abcdef1234567890abcdef1234567890abcdef12", engine.sha);
        assertEquals(1, engine.calls);
    }

    @Test
    @DisplayName("同一 commit 重复推送 → 幂等，不重复触发")
    void deduplicates() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller =
                new GiteeWebhookController(engine, repo(), new StubTasks(), "s3cr3t");
        controller.push("s3cr3t", null, BODY);
        Map<String, Object> second = controller.push("s3cr3t", null, BODY);
        assertEquals(true, second.get("duplicate"));
        assertEquals(1, engine.calls);
    }

    @Test
    @DisplayName("Token 错误 → 401")
    void rejectsWrongToken() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller =
                new GiteeWebhookController(engine, repo(), new StubTasks(), "s3cr3t");
        assertThrows(ResponseStatusException.class,
                () -> controller.push("wrong", null, BODY));
        assertEquals(0, engine.calls);
    }

    @Test
    @DisplayName("未托管仓库的 push → 400 拒绝，不触发构建")
    void rejectsUnknownRepo() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller =
                new GiteeWebhookController(engine, repo(), new StubTasks(), "s3cr3t");
        assertThrows(ResponseStatusException.class,
                () -> controller.push("s3cr3t", null, BODY_UNKNOWN_REPO));
        assertEquals(0, engine.calls);
    }
}
