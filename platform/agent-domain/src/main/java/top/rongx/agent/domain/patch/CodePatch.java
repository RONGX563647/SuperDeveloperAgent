package top.rongx.agent.domain.patch;

import java.util.List;

/**
 * 一次受限代码补丁：Agent 唯一能改动仓库内容的载体。
 *
 * <p>为什么需要它：运行失败时根因常常是「构建/测试配置写错」或「e2e 用例不该在构建阶段跑」，
 * 只读工具 + 环境变量修复解决不了。补丁被限制在受控路径内（{@code ci/}、测试代码、构建配置），
 * 并且必须经人工确认后才会落到新分支，避免模型直接改业务代码。
 *
 * @param patchId   补丁标识
 * @param projectId 所属项目（服务端绑定）
 * @param env       提出补丁时所在环境
 * @param baseSha   补丁基于的提交
 * @param branch    应用后创建的分支名
 * @param edits     文件改动（完整新内容）
 * @param diff      统一 diff（供人工复核）
 * @param diffHash  diff 的规范化哈希，作为变更单的 planHash
 * @param added     新增行数
 * @param removed   删除行数
 * @param createdAt 创建时间
 */
public record CodePatch(
        String patchId,
        String projectId,
        String env,
        String baseSha,
        String branch,
        List<FileEdit> edits,
        String diff,
        String diffHash,
        int added,
        int removed,
        long createdAt) {

    public CodePatch {
        edits = edits == null ? List.of() : List.copyOf(edits);
        if (createdAt == 0L) {
            createdAt = System.currentTimeMillis();
        }
    }

    /** 一个文件的完整新内容（不做局部替换，避免模型猜上下文）。 */
    public record FileEdit(String path, String content) {
        public FileEdit {
            content = content == null ? "" : content;
        }
    }
}
