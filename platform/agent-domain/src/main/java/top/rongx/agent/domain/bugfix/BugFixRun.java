package top.rongx.agent.domain.bugfix;

/**
 * 一次 Bug 自动修复运行（状态机实例）。
 *
 * <p>该记录同时是编排器的输入与落库快照：每个阶段结束后整体覆盖保存，因此进程重启后
 * 仍能恢复阶段、证据与已产出的分支/PR 信息。{@code evidence} 保存最近一个阶段的执行证据
 * （部署日志摘要、复现说明等），供前端展开查看；逐阶段证据与耗时见
 * {@link BugFixStageRun}。
 *
 * <p>{@code analysisJson} 保存分析阶段的结构化结论（影响版本、提交范围、根因、复现步骤、
 * 目标文件、置信度），修复阶段据此校验改动范围；{@code fixSummary} 保存修复说明，
 * 验证阶段只读它而不是读部署日志，避免证据互相覆盖。
 */
public record BugFixRun(
        String id,
        String issueId,
        String userId,
        String projectId,
        String provider,
        String repo,
        String issueNumber,
        String issueTitle,
        String issueBody,
        String branch,
        BugFixStage stage,
        BugFixStatus status,
        String message,
        String analysis,
        String analysisJson,
        String fixSummary,
        String baseSha,
        String fixSha,
        String env,
        String prUrl,
        String evidence,
        int attempt,
        long createdAt,
        long updatedAt,
        long finishedAt) {

    public BugFixRun {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("run id 不能为空");
        }
        if (issueId == null || issueId.isBlank()) {
            throw new IllegalArgumentException("issueId 不能为空");
        }
    }

    /** 阶段进度（0-100），按阶段序号计算，终态直接 100。 */
    public int progress() {
        if (status == BugFixStatus.SUCCEEDED || stage == BugFixStage.DONE) {
            return 100;
        }
        return Math.round(stage.ordinal() * 100f / (BugFixStage.values().length - 1));
    }

    /** 是否是当前阶段的可重试阻塞。 */
    public boolean resumable() {
        return status == BugFixStatus.BLOCKED || status == BugFixStatus.FAILED;
    }

    /** 只更新证据（null 保留原值）。 */
    public BugFixRun withEvidence(String value) {
        if (value == null) {
            return this;
        }
        return new BugFixRun(id, issueId, userId, projectId, provider, repo, issueNumber,
                issueTitle, issueBody, branch, stage, status, message, analysis, analysisJson,
                fixSummary, baseSha, fixSha, env, prUrl, value, attempt, createdAt, updatedAt,
                finishedAt);
    }

    /** 只更新 PR 链接（null 保留原值）。 */
    public BugFixRun withPrUrl(String value) {
        if (value == null) {
            return this;
        }
        return new BugFixRun(id, issueId, userId, projectId, provider, repo, issueNumber,
                issueTitle, issueBody, branch, stage, status, message, analysis, analysisJson,
                fixSummary, baseSha, fixSha, env, value, evidence, attempt, createdAt, updatedAt,
                finishedAt);
    }
}
