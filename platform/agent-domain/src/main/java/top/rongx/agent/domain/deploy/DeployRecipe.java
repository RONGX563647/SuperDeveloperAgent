package top.rongx.agent.domain.deploy;

import java.util.List;

/**
 * 一次"在某个环境上跑通"之后固化下来的可复现脚本。
 *
 * <p>为什么要有它：agent 探索出来的成功路径如果只留在日志里，下次还得重新探索（贵、还可能不一样）。
 * 固化成 recipe 之后，同一环境的重放是<b>确定性</b>的：不调用模型，只按 stage 序列执行。
 *
 * @param projectId    项目
 * @param env          环境（dev / test / prod）
 * @param version      版本号（从 1 递增；保留历史便于回滚）
 * @param pipelineYaml 流水线定义原文
 * @param actionsYaml  配套配方原文（可为空，空则用仓库/内置配方）
 * @param evidenceRunId 证明它跑通的那次运行（服务端可回查状态，是"测试机成功案例"的证据）
 * @param gitSha       在哪个提交上跑通的（部署到服务器时必须与发布提交一致）
 * @param createdAt    固化时间
 * @param createdBy    固化人（人类账号，或 {@code agent} 表示探索产出）
 */
public record DeployRecipe(String projectId, String env, int version,
                           String pipelineYaml, String actionsYaml,
                           String evidenceRunId, String gitSha,
                           long createdAt, String createdBy) {

    /** 定义 + 配方的内容指纹（判断"这次用的脚本是不是当前固化版本"）。 */
    public String fingerprint() {
        return Integer.toHexString((pipelineYaml + "\n---\n"
                + (actionsYaml == null ? "" : actionsYaml)).hashCode());
    }

    /** 证据是否齐备（能拿去做晋升判断）。 */
    public boolean hasEvidence() {
        return evidenceRunId != null && !evidenceRunId.isBlank();
    }

    public DeployRecipe withEvidence(String runId, String sha) {
        return new DeployRecipe(projectId, env, version, pipelineYaml, actionsYaml,
                runId == null || runId.isBlank() ? evidenceRunId : runId,
                sha == null || sha.isBlank() ? gitSha : sha, createdAt, createdBy);
    }

    /** 环境顺序：dev < test < prod（用于"只能向前晋升"的展示）。 */
    public static List<String> order() {
        return List.of("dev", "test", "prod");
    }
}
