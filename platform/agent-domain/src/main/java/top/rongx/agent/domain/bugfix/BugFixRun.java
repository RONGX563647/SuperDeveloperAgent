package top.rongx.agent.domain.bugfix;

/**
 * 一次 Bug 自动修复运行（状态机实例）。
 *
 * <p>该记录是「身份 + 状态机」快照：任务信息、当前阶段/状态、尝试次数与时间戳；
 * 阶段产物（分析、提交、环境、PR、证据）集中在 {@link BugFixRunArtifacts}。
 * 这样阶段实现只关心自己产出的 artifacts，编排器也不必在复制快照时处理 20 多个字段。
 *
 * <p>每个阶段结束后整体覆盖保存，因此进程重启后仍能恢复阶段、证据与分支/PR 信息。
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
        BugFixRunArtifacts artifacts,
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
        artifacts = artifacts == null ? BugFixRunArtifacts.EMPTY : artifacts;
    }

    // ---- 产物访问器：阶段实现直接读，避免层层 artifacts() ----

    public String analysis() {
        return artifacts.analysis();
    }

    public String analysisJson() {
        return artifacts.analysisJson();
    }

    public String fixSummary() {
        return artifacts.fixSummary();
    }

    public String baseSha() {
        return artifacts.baseSha();
    }

    public String fixSha() {
        return artifacts.fixSha();
    }

    public String env() {
        return artifacts.env();
    }

    public String prUrl() {
        return artifacts.prUrl();
    }

    public String evidence() {
        return artifacts.evidence();
    }

    /** 阶段进度（0-100），等待合并/完成均视为 100。 */
    public int progress() {
        if (status == BugFixStatus.WAITING_MERGE || status == BugFixStatus.SUCCEEDED
                || stage == BugFixStage.DONE) {
            return 100;
        }
        return Math.round(stage.ordinal() * 100f / (BugFixStage.values().length - 1));
    }

    /** 是否是当前阶段的可重试阻塞。 */
    public boolean resumable() {
        return status == BugFixStatus.BLOCKED || status == BugFixStatus.FAILED;
    }

    /** PR 是否已创建并等待人工合并。 */
    public boolean waitingMerge() {
        return status == BugFixStatus.WAITING_MERGE;
    }

    /**
     * 只推进状态机字段；证据/产物保持不变。
     *
     * <p>{@code message} 传 null 时保留原值，便于只更新状态时不用重复拼文案。
     */
    public BugFixRun withState(BugFixStage stage, BugFixStatus status, String message, int attempt,
                               long updatedAt, long finishedAt) {
        return new BugFixRun(id, issueId, userId, projectId, provider, repo, issueNumber,
                issueTitle, issueBody, branch, stage, status,
                message == null ? this.message : message,
                artifacts, attempt, createdAt, updatedAt, finishedAt);
    }

    /** 合并阶段产物：只覆盖阶段明确返回的字段（null 保留原值）。 */
    public BugFixRun withResult(BugFixStageResult result) {
        BugFixRunArtifacts merged = artifacts.merge(result);
        if (merged == artifacts) {
            return this;
        }
        return new BugFixRun(id, issueId, userId, projectId, provider, repo, issueNumber,
                issueTitle, issueBody, branch, stage, status, message, merged, attempt,
                createdAt, updatedAt, finishedAt);
    }

    /** 完成/合并后的终态；保留 PR 与全部证据。 */
    public BugFixRun completed(long now) {
        return withState(BugFixStage.DONE, BugFixStatus.SUCCEEDED, "PR 已合并，自动修复完成",
                attempt, now, now);
    }
}
