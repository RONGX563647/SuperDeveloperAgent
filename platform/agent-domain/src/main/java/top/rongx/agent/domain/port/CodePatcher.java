package top.rongx.agent.domain.port;

import java.util.List;
import top.rongx.agent.domain.patch.CodePatch;

/**
 * 受限代码补丁端口（工具层只能用这个接口，不能在 tools 层起进程或直接写盘）。
 *
 * <p>两段式：
 * <ol>
 *   <li>{@link #stage} 预演改动并产出 diff，<b>不写仓库</b>；</li>
 *   <li>{@link #apply} 在人工确认后把补丁提交到新分支，返回新提交 sha。</li>
 * </ol>
 *
 * <p>实现负责路径白名单与路径逃逸校验；调用方无法绕过。
 */
public interface CodePatcher {

    /** 预演结果：diff、哈希与增删行数。 */
    record Staged(String diff, String diffHash, int added, int removed, List<String> files) {
    }

    /**
     * 在 {@code baseSha} 之上预演改动。
     *
     * @throws top.rongx.agent.domain.error.PatchPathDeniedException 路径命中拒绝规则或不在允许清单
     * @throws IllegalArgumentException 基于的提交不存在或内容为空
     */
    Staged stage(String projectId, String baseSha, List<CodePatch.FileEdit> edits);

    /**
     * 应用补丁：从 {@code baseSha} 建 worktree → 写入 → 提交到 {@code patch.branch()} → 清理 worktree。
     *
     * @return 新提交 sha（可直接用于流水线 deploy_run）
     */
    String apply(CodePatch patch);

    /** 允许改动的路径规则（给模型与用户看的说明）。 */
    List<String> allowedPatterns();

    /**
     * 明确拒绝的路径规则（优先级高于允许清单）。
     *
     * <p>即使某条允许规则命中了，只要同时命中这里也必须拒绝：编译产物、依赖目录、
     * git 元数据与密钥文件永远不该被 Agent 写入。
     */
    List<String> deniedPatterns();
}
