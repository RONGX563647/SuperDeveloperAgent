package top.rongx.agent.tools;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.deploy.DeployDiagnosis;
import top.rongx.agent.domain.deploy.DeployRun;
import top.rongx.agent.domain.deploy.DeployRunStatus;
import top.rongx.agent.domain.deploy.DeployRunStore;
import top.rongx.agent.domain.deploy.ReasonCode;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.policy.AutonomyLevel;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.governance.CapabilityService;

/**
 * 部署闸门：互斥 + 每日预算 + 连续失败熔断 + 终态回写。
 *
 * <p>Agent 与部署面板共用同一条触发路径，区别只在 {@code humanConfirmed}：
 * 人工确认过的重放不受预算/熔断限制（人可以为了一次修复明确放行），
 * Agent 自主触发则受全部限制，被拒绝时返回可读错误码，让 Agent 转人工。
 */
@Component
public class DeployGuard {

    private static final Logger log = LoggerFactory.getLogger(DeployGuard.class);

    /** 面向工具/前端的稳定错误码。 */
    public static final String BUSY = "E-607";
    public static final String DAILY_LIMIT = "E-608";
    public static final String CIRCUIT_OPEN = "E-609";
    public static final String PROD_NOT_ALLOWED = "E-610";

    private final DeployRunStore runs;
    private final PipelineEngine engine;
    private final CapabilityService capability;
    private final int dailyLimit;
    private final int failureThreshold;
    private final long watchTimeoutMs;

    public DeployGuard(DeployRunStore runs,
                       PipelineEngine engine,
                       CapabilityService capability,
                       @Value("${agent.deploy.daily-limit:20}") int dailyLimit,
                       @Value("${agent.deploy.failure-threshold:3}") int failureThreshold,
                       @Value("${agent.deploy.watch-timeout-minutes:30}") long watchTimeoutMinutes) {
        this.runs = runs;
        this.engine = engine;
        this.capability = capability;
        this.dailyLimit = Math.max(1, dailyLimit);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.watchTimeoutMs = Math.max(1, watchTimeoutMinutes) * 60_000L;
    }

    /**
     * 抢互斥位并触发流水线。
     *
     * @param humanConfirmed 人已明确放行（部署面板重放）；true 时跳过预算与熔断
     * @return 流水线 runId
     * @throws IllegalStateException 带 E-607/E-608/E-609/E-610 前缀的可读错误
     */
    public String trigger(String projectId, String env, String sha, String pipelineId,
                          Map<String, String> params, boolean humanConfirmed) {
        if (!humanConfirmed) {
            if (capability.autonomy(env) == AutonomyLevel.PROD) {
                throw new IllegalStateException(PROD_NOT_ALLOWED
                        + " 生产环境不允许 Agent 自主触发部署：请先在部署面板确认发布");
            }
            int failures = runs.consecutiveFailures(projectId, env);
            if (failures >= failureThreshold) {
                throw new IllegalStateException(CIRCUIT_OPEN + " 该环境已连续失败 " + failures
                        + " 次，已自动降级为人工：请先排障，再到部署面板确认后重放");
            }
            long dayStart = LocalDate.now(ZoneId.systemDefault())
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            int today = runs.countSince(projectId, env, dayStart);
            if (today >= dailyLimit) {
                throw new IllegalStateException(DAILY_LIMIT + " 该环境今日部署次数已达上限（"
                        + dailyLimit + "）：请人工确认后重放，或明天再试");
            }
        }

        Optional<DeployRun> started = runs.tryStart(projectId, env, sha, pipelineId, 0);
        if (started.isEmpty()) {
            DeployRun active = runs.active(projectId, env).orElse(null);
            String detail = active == null ? "" : "（runId=" + active.runId()
                    + "，开始于 " + active.startedAt() + "）";
            throw new IllegalStateException(BUSY + " 该环境已有部署在进行中" + detail
                    + "：同一环境同时只允许一个部署，请等待其结束");
        }
        DeployRun run = started.get();
        try {
            String runId = engine.trigger(projectId, pipelineId, params);
            runs.bindRunId(run.id(), runId);
            watch(projectId, env, run.id(), runId);
            return runId;
        } catch (RuntimeException e) {
            runs.finish(run.id(), DeployRunStatus.FAILED, ReasonCode.UNKNOWN.wireName(),
                    e.getMessage());
            throw e;
        }
    }

    public Optional<DeployRun> active(String projectId, String env) {
        return runs.active(projectId, env);
    }

    /** 最近一次部署记录（含终态）。 */
    public Optional<DeployRun> latest(String projectId, String env) {
        return runs.recent(projectId, env, 1).stream().findFirst();
    }

    public int consecutiveFailures(String projectId, String env) {
        return runs.consecutiveFailures(projectId, env);
    }

    /** 异步守护：流水线终态后回写部署记录，并用日志尾部做原因分类。 */
    private void watch(String projectId, String env, String deployRunId, String pipelineRunId) {
        Thread.ofVirtual().name("deploy-watch-" + deployRunId).start(() -> {
            long deadline = System.currentTimeMillis() + watchTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    StageStatus status = engine.status(pipelineRunId);
                    if (status == StageStatus.SUCCEEDED) {
                        runs.finish(deployRunId, DeployRunStatus.SUCCEEDED,
                                ReasonCode.OK.wireName(), "部署成功");
                        return;
                    }
                    if (status.isTerminal()) {
                        String tail = engine.logTail(pipelineRunId);
                        boolean rolledBack = tail != null
                                && (tail.contains("ROLLED_BACK") || tail.contains("已自动回滚"));
                        ReasonCode code = rolledBack ? ReasonCode.ROLLED_BACK
                                : DeployDiagnosis.classify(false, "", "", tail);
                        runs.finish(deployRunId,
                                rolledBack ? DeployRunStatus.ROLLED_BACK : DeployRunStatus.FAILED,
                                code.wireName(), lastLine(tail));
                        return;
                    }
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warn("部署守护异常：{}", e.getMessage());
                    return;
                }
            }
            runs.finish(deployRunId, DeployRunStatus.FAILED,
                    ReasonCode.UNKNOWN.wireName(), "部署超时未结束（守护线程退出）");
        });
    }

    private static String lastLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].length() > 300 ? lines[i].substring(0, 300) : lines[i];
            }
        }
        return "";
    }
}
