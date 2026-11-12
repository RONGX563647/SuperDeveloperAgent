package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.PatchStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 受限代码补丁工具：让 Agent 真正具备「改仓库」的能力，但只在受控范围内。
 *
 * <p>流程：{@code propose_patch} 产出 diff 并登记变更单 → 人在确认卡片上确认 →
 * {@code POST /api/v1/changes/{id}/apply} 提交到新分支。Agent 没有确认令牌，
 * 因此不可能自确认、自应用。
 *
 * <p>能改什么由 {@link CodePatcher#allowedPatterns()} 决定（ci/、构建配置、测试代码），
 * 业务代码与凭证不在范围内。
 */
@Component
public class PatchTools {

    /** 返回给模型的 diff 上限（完整 diff 供确认卡查看）。 */
    private static final int MAX_DIFF_CHARS = 6000;

    private final CodePatcher patcher;
    private final PatchStore patches;
    private final ChangeStore changes;

    public PatchTools(CodePatcher patcher, PatchStore patches, ChangeStore changes) {
        this.patcher = patcher;
        this.patches = patches;
        this.changes = changes;
    }

    @Tool(name = "propose_patch",
            description = """
                    提交一份受限代码补丁，用于修复流水线失败的根因（写操作，仅 dev/test）。
                    适用场景：构建/测试配置写错、e2e 用例不该在构建阶段执行、ci 流水线定义与产物不匹配。
                    允许修改的路径：ci/**、Jenkinsfile、**/pom.xml、**/build.gradle(.kts)、**/src/test/**、
                    Dockerfile、deploy/**、docker-compose*.yml、nginx*.conf、application*.yml、
                    package.json、.env.example；业务代码与凭证会被拒绝。
                    本工具只生成 diff 并登记变更单，不会直接改仓库——必须在平台的确认卡片上确认后才应用。
                    edits 格式（每个文件一段，内容写到下一个标记为止）：
                    === FILE: ci/pipeline.yml ===
                    <该文件的完整新内容>
                    === FILE: pom.xml ===
                    <该文件的完整新内容>
                    调用后必须把 changeId 告诉用户，并提示其到确认卡片确认；不要假设已确认。""",
            readOnly = false, concurrencySafe = false)
    public String propose(
            @ToolParam(name = "base_sha",
                    description = "补丁基于的提交 sha（失败运行的 gitSha，7-40 位十六进制）") String baseSha,
            @ToolParam(name = "edits",
                    description = "文件改动，格式见工具说明：=== FILE: 相对路径 === 后跟完整新内容") String edits,
            @ToolParam(name = "reason",
                    description = "一句话说明为什么要改（会写进变更单，便于人工复核）") String reason,
            ProjectContext ctx) {
        if (ctx.isProduction()) {
            return "err: 生产环境不允许提交代码补丁；请先在 dev/test 验证";
        }
        if (reason == null || reason.isBlank() || reason.length() > 120) {
            return "err: reason 不能为空且不超过 120 字";
        }
        List<CodePatch.FileEdit> files;
        try {
            files = parseEdits(edits);
        } catch (IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
        CodePatcher.Staged staged;
        try {
            staged = patcher.stage(ctx.projectId(), baseSha == null ? "" : baseSha.trim(), files);
        } catch (IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }

        String patchId = "pt-" + UUID.randomUUID().toString().substring(0, 8);
        String branch = "agent/fix-" + patchId;
        CodePatch patch = new CodePatch(patchId, ctx.projectId(), ctx.env(), baseSha.trim(),
                branch, files, staged.diff(), staged.diffHash(), staged.added(), staged.removed(), 0L);
        patches.save(patch);
        ChangePlan plan = changes.registerWithHash(ctx.projectId(), ctx.env(),
                "patch:" + patchId, reason.trim(), staged.diffHash(), false);

        String diff = staged.diff();
        boolean truncated = diff.length() > MAX_DIFF_CHARS;
        if (truncated) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n…（diff 已截断，完整内容见确认卡片）";
        }
        return """
                patchId: %s
                changeId: %s
                baseSha: %s
                branch: %s（应用时创建）
                files: %s
                changes: +%d -%d
                apply: 不支持由 Agent 直接应用（需要人工确认）
                next: 请把 changeId 告诉用户，让其在平台的确认卡片上确认并点「带令牌执行」；确认前不会修改仓库。
                ---
                %s
                evidence: patch:%s change:%s
                """.formatted(patchId, plan.changeId(), patch.baseSha(), branch,
                String.join(", ", staged.files()), staged.added(), staged.removed(),
                diff, patchId, plan.changeId());
    }

    /** 解析 {@code === FILE: path ===} 分段格式。 */
    static List<CodePatch.FileEdit> parseEdits(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("edits 不能为空");
        }
        List<CodePatch.FileEdit> result = new ArrayList<>();
        String marker = "=== FILE:";
        String currentPath = null;
        StringBuilder content = new StringBuilder();
        for (String line : raw.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith(marker) && trimmed.endsWith("===")) {
                if (currentPath != null) {
                    result.add(new CodePatch.FileEdit(currentPath, content.toString()));
                }
                currentPath = trimmed.substring(marker.length(), trimmed.length() - 3).trim();
                content.setLength(0);
            } else if (currentPath != null) {
                content.append(line).append('\n');
            }
        }
        if (currentPath != null) {
            result.add(new CodePatch.FileEdit(currentPath, content.toString()));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("没有解析到任何文件：请使用 \"=== FILE: 相对路径 ===\" 分段");
        }
        return result;
    }
}
