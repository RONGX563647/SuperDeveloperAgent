package top.rongx.agent.domain.bugfix;

/**
 * 一次运行产出的证据与制品：分析、修复摘要、提交、环境、PR 与最近阶段证据。
 *
 * <p>从 {@link BugFixRun} 中拆出来后，运行快照只保留「身份 + 状态机」字段，阶段产物集中在这里。
 * 所有字段非 null（缺省为空串），合并阶段结果时 null 表示「保留原值」。
 */
public record BugFixRunArtifacts(
        String analysis,
        String analysisJson,
        String fixSummary,
        String baseSha,
        String fixSha,
        String env,
        String prUrl,
        String evidence) {

    public static final BugFixRunArtifacts EMPTY =
            new BugFixRunArtifacts("", "", "", "", "", "", "", "");

    public BugFixRunArtifacts {
        analysis = analysis == null ? "" : analysis;
        analysisJson = analysisJson == null ? "" : analysisJson;
        fixSummary = fixSummary == null ? "" : fixSummary;
        baseSha = baseSha == null ? "" : baseSha;
        fixSha = fixSha == null ? "" : fixSha;
        env = env == null ? "" : env;
        prUrl = prUrl == null ? "" : prUrl;
        evidence = evidence == null ? "" : evidence;
    }

    /** 合并阶段结果：null 字段保留旧值。 */
    public BugFixRunArtifacts merge(BugFixStageResult result) {
        if (result == null) {
            return this;
        }
        return new BugFixRunArtifacts(
                result.analysis() == null ? analysis : result.analysis(),
                result.analysisJson() == null ? analysisJson : result.analysisJson(),
                result.fixSummary() == null ? fixSummary : result.fixSummary(),
                result.baseSha() == null ? baseSha : result.baseSha(),
                result.fixSha() == null ? fixSha : result.fixSha(),
                result.env() == null ? env : result.env(),
                result.prUrl() == null ? prUrl : result.prUrl(),
                result.evidence() == null ? evidence : result.evidence());
    }
}
