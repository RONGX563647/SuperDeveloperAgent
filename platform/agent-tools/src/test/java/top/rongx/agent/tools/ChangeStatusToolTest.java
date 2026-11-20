package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * change_status：只读查询变更单真实状态。
 *
 * <p>为什么必须与 confirm 分开：confirm 会推送确认卡片。模型只想查状态时
 * 只能调 confirm，就会出现「人已确认、卡片又被推一遍」——反复推卡的成因之一。
 * 这组用例锁住三件事：状态如实透传、绝不产生副作用、越权读不到。
 */
class ChangeStatusToolTest {

    private static ProjectContext ctx(String projectId, String env) {
        return new ProjectContext(projectId, env, "u1", null, null);
    }

    private static ChangePlan plan(String id, String projectId, String env,
                                   ChangePlan.Status status) {
        return new ChangePlan(id, projectId, env, "stack_deploy", "hash-" + id, "摘要",
                null, null, false, Instant.now(), status);
    }

    @Test
    @DisplayName("四种状态各自给出对应 next，模型不必自己推断")
    void mapsEveryStatusToNext() {
        FakeStore store = new FakeStore();
        ChangeStatusTool tools = new ChangeStatusTool(store);

        store.put(plan("chg-p", "P1", "dev", ChangePlan.Status.PENDING));
        store.put(plan("chg-c", "P1", "dev", ChangePlan.Status.CONFIRMED));
        store.put(plan("chg-a", "P1", "dev", ChangePlan.Status.APPLIED));
        store.put(plan("chg-f", "P1", "dev", ChangePlan.Status.FAILED));

        String pending = tools.changeStatus("chg-p", ctx("P1", "dev"));
        assertTrue(pending.contains("status: PENDING"), pending);
        assertTrue(pending.contains("尚未确认"), pending);

        String confirmed = tools.changeStatus("chg-c", ctx("P1", "dev"));
        assertTrue(confirmed.contains("status: CONFIRMED"), confirmed);
        assertTrue(confirmed.contains("不要再推卡"), confirmed);

        String applied = tools.changeStatus("chg-a", ctx("P1", "dev"));
        assertTrue(applied.contains("status: APPLIED"), applied);
        assertTrue(applied.contains("不要再次推卡"), applied);

        String failed = tools.changeStatus("chg-f", ctx("P1", "dev"));
        assertTrue(failed.contains("status: FAILED"), failed);
        assertTrue(failed.contains("失败"), failed);
    }

    @Test
    @DisplayName("查询是只读的：不写状态、不推卡（store 无任何写调用）")
    void queryHasNoSideEffects() {
        FakeStore store = new FakeStore();
        ChangeStatusTool tools = new ChangeStatusTool(store);
        store.put(plan("chg-1", "P1", "dev", ChangePlan.Status.PENDING));

        tools.changeStatus("chg-1", ctx("P1", "dev"));

        assertEquals(0, store.writes.get(), "只读工具不允许调用任何写方法");
        assertEquals(ChangePlan.Status.PENDING,
                store.find("chg-1").orElseThrow().status(), "状态不能被查询改掉");
    }

    @Test
    @DisplayName("越权与不存在都拒绝，且不泄露别的项目内容")
    void rejectsForeignAndMissing() {
        FakeStore store = new FakeStore();
        ChangeStatusTool tools = new ChangeStatusTool(store);
        store.put(plan("chg-other", "P2", "dev", ChangePlan.Status.CONFIRMED));
        store.put(plan("chg-env", "P1", "test", ChangePlan.Status.CONFIRMED));

        String foreign = tools.changeStatus("chg-other", ctx("P1", "dev"));
        assertTrue(foreign.startsWith("err:"), foreign);
        assertTrue(foreign.contains("不属于当前项目"), foreign);
        assertFalse(foreign.contains("CONFIRMED"), "拒绝时不得回显状态：" + foreign);

        String envMismatch = tools.changeStatus("chg-env", ctx("P1", "dev"));
        assertTrue(envMismatch.startsWith("err:"), envMismatch);
        assertTrue(envMismatch.contains("不一致"), envMismatch);

        String missing = tools.changeStatus("chg-none", ctx("P1", "dev"));
        assertTrue(missing.startsWith("err:"), missing);
        assertTrue(missing.contains("不存在"), missing);

        String blank = tools.changeStatus("  ", ctx("P1", "dev"));
        assertTrue(blank.startsWith("err:"), blank);
    }

    @Test
    @DisplayName("留痕单标 RECORDED：明确告知无需确认，避免模型空等")
    void manualKindIsRecorded() {
        FakeStore store = new FakeStore();
        ChangeStatusTool tools = new ChangeStatusTool(store);
        store.put(new ChangePlan("chg-m", "P1", "dev", "env_ensure", "h", "留痕",
                null, null, false, Instant.now(), ChangePlan.Status.PENDING));

        String out = tools.changeStatus("chg-m", ctx("P1", "dev"));

        assertTrue(out.contains("status: RECORDED"), out);
        assertTrue(out.contains("无需确认"), out);
        assertTrue(out.contains("executable: false"), out);
    }

    @Test
    @DisplayName("跨环境必须拒绝且不回显 status（BUG-28 唯一实现的验收口径）")
    void crossEnvIsRejectedWithoutLeakingStatus() {
        FakeStore store = new FakeStore();
        ChangeStatusTool tools = new ChangeStatusTool(store);
        // 单子登记在 test 环境，模型试图从 dev 会话里读它
        store.put(plan("chg-cross", "P1", "test", ChangePlan.Status.CONFIRMED));

        String out = tools.changeStatus("chg-cross", ctx("P1", "dev"));

        assertTrue(out.startsWith("err:"), out);
        assertTrue(out.contains("不一致"), out);
        assertFalse(out.contains("status: "), "拒绝时不得回显任何状态行：" + out);
        assertFalse(out.contains("CONFIRMED"), "拒绝时不得回显状态值：" + out);
        // 唯一实现：全平台只有一处 @Tool(name = "change_status") 声明
        assertEquals(1, declaredChangeStatusToolCount(),
                "change_status 的 @Tool 声明必须全局唯一（BUG-28）");
    }

    /** 统计三个候选源文件里 change_status 的 @Tool 声明数量（必须恰好 1）。 */
    private static int declaredChangeStatusToolCount() {
        String[] sources = {
                "src/main/java/top/rongx/agent/tools/ChangeTools.java",
                "src/main/java/top/rongx/agent/tools/ChangeQueryTools.java",
                "src/main/java/top/rongx/agent/tools/ChangeStatusTool.java",
        };
        int count = 0;
        for (String path : sources) {
            try {
                String source = java.nio.file.Files.readString(java.nio.file.Path.of(path));
                if (source.contains("@Tool(name = \"change_status\"")) {
                    count++;
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取源文件失败：" + path, e);
            }
        }
        return count;
    }
    /** 只实现查询所需的读方法；写方法计数以证明没被调用。 */
    private static final class FakeStore implements ChangeStore {
        private final Map<String, ChangePlan> rows = new HashMap<>();
        private final AtomicInteger writes = new AtomicInteger();

        private void put(ChangePlan plan) {
            rows.put(plan.changeId(), plan);
        }

        @Override
        public ChangePlan request(String projectId, String env, String kind, String summary,
                                  String imageDigest, String frontendDistHash, boolean highRisk) {
            writes.incrementAndGet();
            throw new UnsupportedOperationException("只读用例不应写入");
        }

        @Override
        public ChangePlan registerWithHash(String projectId, String env, String kind, String summary,
                                           String planHash, boolean highRisk) {
            writes.incrementAndGet();
            throw new UnsupportedOperationException("只读用例不应写入");
        }

        @Override
        public Optional<ChangePlan> find(String changeId) {
            return Optional.ofNullable(rows.get(changeId));
        }

        @Override
        public Optional<ChangePlan> findLatest(String projectId, String kindPrefix) {
            return rows.values().stream()
                    .filter(p -> p.projectId().equals(projectId))
                    .filter(p -> p.kind().startsWith(kindPrefix))
                    .reduce((a, b) -> b);
        }

        @Override
        public void updateStatus(String changeId, ChangePlan.Status status) {
            writes.incrementAndGet();
            throw new UnsupportedOperationException("只读用例不应写入");
        }
    }
}
