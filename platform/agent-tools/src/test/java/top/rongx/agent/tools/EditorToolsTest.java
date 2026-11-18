package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.port.SessionWorkspaceEditor;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 会话编辑工具：生产/无工作树/授权门槛/写入上限/路径逃逸。
 *
 * <p>用假 port + 内存变更单，不触碰真实文件系统与 git。
 */
class EditorToolsTest {

    private static ProjectContext ctx(String env, String worktree) {
        return new ProjectContext("RY", env, "tester", null, worktree);
    }

    @Test
    @DisplayName("生产环境直接拒绝，不进入编辑器")
    void productionRejected() {
        FakeEditor editor = new FakeEditor();
        EditorTools tools = new EditorTools(editor, new MemoryChangeStore());

        String out = tools.write("a.txt", "x", ctx("prod", "wt-demo"));

        assertTrue(out.startsWith("err:"), out);
        assertTrue(out.contains("生产环境"), out);
        assertTrue(editor.writes.isEmpty(), "生产环境不应触发任何写入");
    }

    @Test
    @DisplayName("未选择专属工作树直接拒绝")
    void missingWorktreeRejected() {
        FakeEditor editor = new FakeEditor();
        EditorTools tools = new EditorTools(editor, new MemoryChangeStore());

        String out = tools.write("a.txt", "x", ctx("test", null));

        assertTrue(out.startsWith("err:"), out);
        assertTrue(out.contains("工作树"), out);
        assertTrue(editor.writes.isEmpty());
    }

    @Test
    @DisplayName("首次写返回 NEEDS_CONFIRMATION，且确认前不写任何文件")
    void unauthorizedWriteAsksConfirmation() {
        FakeEditor editor = new FakeEditor();
        MemoryChangeStore store = new MemoryChangeStore();
        EditorTools tools = new EditorTools(editor, store);

        String out = tools.write("src/App.java", "class App {}", ctx("test", "wt-demo"));

        assertTrue(out.contains("NEEDS_CONFIRMATION"), out);
        assertTrue(out.contains("changeId: chg-"), out);
        assertTrue(out.contains("editor_branch:agent/session-wt-demo"), out);
        assertTrue(editor.writes.isEmpty(), "未授权不得写文件");
        assertEquals(1, store.plans.size());
        assertEquals(ChangePlan.Status.PENDING, store.plans.values().iterator().next().status());
    }

    @Test
    @DisplayName("同一分支重复未授权复用同一单号，不重复弹卡")
    void repeatedUnauthorizedReusesChangeId() {
        EditorTools tools = new EditorTools(new FakeEditor(), new MemoryChangeStore());

        String first = tools.write("a.txt", "1", ctx("test", "wt-demo"));
        String second = tools.write("b.txt", "2", ctx("test", "wt-demo"));

        assertEquals(changeId(first), changeId(second));
    }

    @Test
    @DisplayName("人工确认后可写；分支级授权覆盖提交")
    void confirmedGrantAllowsWrite() {
        FakeEditor editor = new FakeEditor();
        MemoryChangeStore store = new MemoryChangeStore();
        EditorTools tools = new EditorTools(editor, store);
        ProjectContext ctx = ctx("test", "wt-demo");

        String pending = tools.write("a.txt", "v1", ctx);
        store.updateStatus(changeId(pending), ChangePlan.Status.CONFIRMED);
        String written = tools.write("a.txt", "v2", ctx);
        assertTrue(written.startsWith("ok: wrote"), written);
        assertEquals("v2", editor.files.get("a.txt"));

        assertTrue(tools.commit("agent: update", ctx).startsWith("ok: committed"));
    }

    @Test
    @DisplayName("超限内容在进入编辑器前被拒")
    void oversizedContentRejected() {
        FakeEditor editor = new FakeEditor();
        EditorTools tools = new EditorTools(editor, new MemoryChangeStore());

        String out = tools.write("big.txt", "a".repeat(200_001), ctx("test", "wt-demo"));

        assertTrue(out.startsWith("err:"), out);
        assertTrue(editor.writes.isEmpty());
    }

    @Test
    @DisplayName("infra 抛出的路径逃逸异常转成 err 文本")
    void pathEscapeSurfacesAsError() {
        FakeEditor editor = new FakeEditor();
        editor.rejectPath = true;
        MemoryChangeStore store = new MemoryChangeStore();
        EditorTools tools = new EditorTools(editor, store);
        ProjectContext ctx = ctx("test", "wt-demo");

        // 先授权，确保进入写入分支
        String pending = tools.write("../escape.txt", "x", ctx);
        store.updateStatus(changeId(pending), ChangePlan.Status.CONFIRMED);
        String out = tools.write("../escape.txt", "x", ctx);

        assertTrue(out.startsWith("err:"), out);
        assertTrue(out.contains("路径越界"), out);
        assertTrue(editor.writes.isEmpty());
    }

    private static String changeId(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("changeId:\\s*(chg-[A-Za-z0-9-]+)").matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group(1);
    }

    /** 假编辑器：记录写入，可模拟路径逃逸。 */
    private static class FakeEditor implements SessionWorkspaceEditor {

        final Map<String, String> files = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean rejectPath;

        @Override
        public Session open(String projectId, String sessionKey) {
            return new Session("/tmp/wt-session-" + sessionKey,
                    "agent/session-" + sessionKey, "head-sha", false);
        }

        @Override
        public List<String> list(String projectId, String sessionKey, String dir, int depth) {
            return List.copyOf(files.keySet());
        }

        @Override
        public String read(String projectId, String sessionKey, String path) {
            return files.getOrDefault(path, "");
        }

        @Override
        public void write(String projectId, String sessionKey, String path, String content) {
            if (rejectPath) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "路径越界：" + path);
            }
            writes.add(path);
            files.put(path, content);
        }

        @Override
        public boolean delete(String projectId, String sessionKey, String path) {
            return files.remove(path) != null;
        }

        @Override
        public Diff diff(String projectId, String sessionKey) {
            return new Diff(List.of(), "");
        }

        @Override
        public Commit commit(String projectId, String sessionKey, String message) {
            return new Commit("sha1", "agent/session-" + sessionKey, List.copyOf(files.keySet()));
        }
    }

    /** 内存变更单：findLatest 按创建时间倒序取第一条前缀匹配。 */
    private static class MemoryChangeStore implements ChangeStore {

        final Map<String, ChangePlan> plans = new LinkedHashMap<>();

        @Override
        public ChangePlan request(String projectId, String env, String kind, String summary,
                                  String imageDigest, String frontendDistHash, boolean highRisk) {
            return registerWithHash(projectId, env, kind, summary, "hash", highRisk);
        }

        @Override
        public ChangePlan registerWithHash(String projectId, String env, String kind, String summary,
                                           String planHash, boolean highRisk) {
            ChangePlan plan = new ChangePlan("chg-" + UUID.randomUUID(), projectId, env, kind,
                    planHash, summary, null, null, highRisk, Instant.now(), ChangePlan.Status.PENDING);
            plans.put(plan.changeId(), plan);
            return plan;
        }

        @Override
        public Optional<ChangePlan> find(String changeId) {
            return Optional.ofNullable(plans.get(changeId));
        }

        @Override
        public Optional<ChangePlan> findLatest(String projectId, String kindPrefix) {
            return plans.values().stream()
                    .filter(p -> p.projectId().equals(projectId))
                    .filter(p -> p.kind().startsWith(kindPrefix))
                    .max(Comparator.comparing(ChangePlan::createdAt));
        }

        @Override
        public void updateStatus(String changeId, ChangePlan.Status status) {
            ChangePlan old = plans.get(changeId);
            if (old == null) {
                return;
            }
            plans.put(changeId, new ChangePlan(old.changeId(), old.projectId(), old.env(),
                    old.kind(), old.planHash(), old.summary(), old.imageDigest(),
                    old.frontendDistHash(), old.highRisk(), old.createdAt(), status));
        }
    }
}
