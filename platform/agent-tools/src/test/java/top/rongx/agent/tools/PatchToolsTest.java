package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.error.PatchPathDeniedException;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.PatchStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * propose_patch 的路径守卫契约：被拒时返回结构化 PATH_DENIED，带允许清单。
 *
 * <p>这条契约是「Agent 能不能自己改代码」的总开关：笼统失败会让模型反复重试，
 * 结构化拒绝才能让它换路径。用假补丁器，不触碰 git。
 */
class PatchToolsTest {

    private static final String EDITS = "=== FILE: src/main/java/App.java ===\nclass App {}\n";

    @Test
    @DisplayName("路径被拒时返回 PATH_DENIED、被拒路径与允许清单")
    void deniedPathReturnedAsStructured() {
        PatchTools tools = new PatchTools(new DenyingPatcher(), new MemoryPatches(),
                new MemoryChanges());

        String out = tools.propose("0123456", EDITS, "修一个空指针", ctx("dev"));

        assertTrue(out.startsWith("err: PATH_DENIED"), out);
        assertTrue(out.contains("path: src/main/java/App.java"), out);
        assertTrue(out.contains("allowed: src/main/**"), out);
        assertTrue(out.contains("reason: 命中凭证/密钥文件规则"), out);
        assertTrue(out.contains("next:"), out);
    }

    @Test
    @DisplayName("允许路径正常产出 patchId / changeId / diff")
    void allowedPathProducesPatch() {
        PatchTools tools = new PatchTools(new AllowingPatcher(), new MemoryPatches(),
                new MemoryChanges());

        String out = tools.propose("0123456", EDITS, "补一个字段", ctx("dev"));

        assertTrue(out.contains("patchId: pt-"), out);
        assertTrue(out.contains("changeId: ch-"), out);
        assertTrue(out.contains("+++ b/src/main/java/App.java"), out);
    }

    @Test
    @DisplayName("生产环境与空 reason 在进入补丁器之前就被拒")
    void preconditionsRejectedBeforePatcher() {
        AllowingPatcher patcher = new AllowingPatcher();
        PatchTools tools = new PatchTools(patcher, new MemoryPatches(), new MemoryChanges());

        assertTrue(tools.propose("0123456", EDITS, "x", ctx("prod")).contains("生产环境"));
        assertTrue(tools.propose("0123456", EDITS, "  ", ctx("dev")).contains("reason"));
        assertEquals(0, patcher.calls, "前置校验失败时不应调用补丁器");
    }

    @Test
    @DisplayName("edits 分段格式非法时给出可读错误，不抛异常")
    void malformedEditsRejected() {
        AllowingPatcher patcher = new AllowingPatcher();
        PatchTools tools = new PatchTools(patcher, new MemoryPatches(), new MemoryChanges());

        String out = tools.propose("0123456", "no marker here", "reason", ctx("dev"));

        assertTrue(out.startsWith("err:"), out);
        assertTrue(out.contains("=== FILE:"), out);
    }

    @Test
    @DisplayName("结构化拒绝文本自带原因码、允许/禁止清单与 next 指令")
    void denialCarriesEverythingTheModelNeeds() {
        PatchPathDeniedException error = new PatchPathDeniedException(".env", "命中凭证/密钥文件规则",
                List.of("src/main/**"), List.of("**/.env"));
        String text = error.structured();
        assertEquals("PATH_DENIED", text.lines().findFirst().orElse(""));
        assertTrue(text.contains("path: .env"), text);
        assertTrue(text.contains("denied: **/.env"), text);
        assertTrue(text.contains("next:"), text);
        // 结构化原因码不污染错误码枚举：对外仍是运行时输入错误
        assertEquals("E-390", error.code());
    }

    private static ProjectContext ctx(String env) {
        return new ProjectContext("RY", env, "tester", null, null);
    }

    /** 永远拒绝（模拟 .env 之类的危险路径）。 */
    private static final class DenyingPatcher implements CodePatcher {
        @Override
        public Staged stage(String projectId, String baseSha, List<CodePatch.FileEdit> edits) {
            throw new PatchPathDeniedException("src/main/java/App.java", "命中凭证/密钥文件规则",
                    List.of("src/main/**", "src/test/**"), List.of("**/.env", "**/target/**"));
        }

        @Override
        public String apply(CodePatch patch) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<String> allowedPatterns() {
            return List.of("src/main/**", "src/test/**");
        }

        @Override
        public List<String> deniedPatterns() {
            return List.of("**/.env", "**/target/**");
        }
    }

    /** 永远允许，diff 里带上文件名。 */
    private static final class AllowingPatcher implements CodePatcher {
        private int calls;

        @Override
        public Staged stage(String projectId, String baseSha, List<CodePatch.FileEdit> edits) {
            calls++;
            String diff = "--- a/" + edits.get(0).path() + "\n+++ b/" + edits.get(0).path() + "\n";
            return new Staged(diff, "hash", 1, 0, edits.stream().map(CodePatch.FileEdit::path).toList());
        }

        @Override
        public String apply(CodePatch patch) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<String> allowedPatterns() {
            return List.of("src/main/**");
        }

        @Override
        public List<String> deniedPatterns() {
            return List.of();
        }
    }

    private static final class MemoryPatches implements PatchStore {
        private final Map<String, CodePatch> rows = new HashMap<>();

        @Override
        public void save(CodePatch patch) {
            rows.put(patch.patchId(), patch);
        }

        @Override
        public Optional<CodePatch> find(String patchId) {
            return Optional.ofNullable(rows.get(patchId));
        }

        @Override
        public void remove(String patchId) {
            rows.remove(patchId);
        }
    }

    private static final class MemoryChanges implements ChangeStore {
        private final Map<String, ChangePlan> rows = new HashMap<>();
        private int seq;

        @Override
        public ChangePlan request(String projectId, String env, String kind, String summary,
                                  String imageDigest, String frontendDistHash, boolean highRisk) {
            return registerWithHash(projectId, env, kind, summary, imageDigest, highRisk);
        }

        @Override
        public ChangePlan registerWithHash(String projectId, String env, String kind, String summary,
                                           String planHash, boolean highRisk) {
            seq++;
            ChangePlan plan = new ChangePlan("ch-" + seq, projectId, env, kind, planHash, summary,
                    null, null, highRisk, Instant.now(), ChangePlan.Status.PENDING);
            rows.put(plan.changeId(), plan);
            return plan;
        }

        @Override
        public Optional<ChangePlan> find(String changeId) {
            return Optional.ofNullable(rows.get(changeId));
        }

        @Override
        public Optional<ChangePlan> findLatest(String projectId, String kindPrefix) {
            return rows.values().stream()
                    .filter(p -> p.kind().startsWith(kindPrefix))
                    .reduce((a, b) -> b);
        }

        @Override
        public void updateStatus(String changeId, ChangePlan.Status status) {
        }
    }
}
