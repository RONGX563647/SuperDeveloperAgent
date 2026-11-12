package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;
import top.rongx.agent.domain.policy.PolicyDecision;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.tool.ToolLevel;
import top.rongx.agent.domain.policy.PolicyPort;
import org.springframework.stereotype.Component;

/**
 * 流水线写工具（编排层入口，L2：仅测试环境）。
 *
 * <p>层级契约：{@code ci_run} 是「编排层」入口——按仓库 {@code ci/pipeline.yml} 顺序跑整条
 * 流水线（检出→构建→测试→镜像→部署→健康检查）。其每个 stage 内部最终都分解为
 * {@code run_action} 所执行的单个动作（{@code ActionSpec} 是 {@code PipelineSpec} 的内部执行表示）。
 * 两者是<b>组合关系</b>而非并列替代：先 {@code ci_run} 跑整条线，需要单步调试某动作时才用
 * {@code run_action}。
 *
 * <p>参数只允许枚举/正则；生产环境直接拒绝，不进入执行器。
 */
@Component
public class CiCommandTools {

    private final PipelineEngine engine;
    private final PolicyPort policy;

    public CiCommandTools(PipelineEngine engine, PolicyPort policy) {
        this.engine = engine;
        this.policy = policy;
    }

    @Tool(name = "ci_run",
            description = """
                    触发一次完整流水线构建/测试（仅测试环境）。写操作，编排层入口。
                    与 run_action 的关系：ci_run 按 ci/pipeline.yml 跑整条流水线（含部署/健康检查）；
                    run_action 只执行单个动作，用于单步调试。优先用 ci_run，除非要单独验证某个动作。
                    pipeline_id 必须来自 ci_list_pipelines 的返回；git_sha 必须是 7-40 位小写十六进制。
                    生产环境调用会被拒绝，请先在测试环境验证。""",
            readOnly = false, concurrencySafe = false)
    public String run(
            @ToolParam(name = "pipeline_id", description = "流水线标识，必须来自 ci_list_pipelines") String pipelineId,
            @ToolParam(name = "git_sha", description = "要构建的提交短 sha（7-40 位十六进制）") String gitSha,
            ProjectContext ctx) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return "err: pipeline_id 不能为空";
        }
        java.util.List<String> available = engine.pipelines(ctx.projectId());
        if (available.isEmpty()) {
            return "err: 该项目还没有可用的流水线定义（平台侧定义 / ci/pipeline.yml 均可）。"
                    + "请在项目页点「生成流水线脚本」后重试";
        }
        if (!available.contains(pipelineId)) {
            return "err: pipeline 不在白名单：" + pipelineId + "（可用：" + String.join(", ", available) + "）";
        }
        if (gitSha == null || !gitSha.matches("[0-9a-f]{7,40}")) {
            return "err: git_sha 格式非法（需 7-40 位小写十六进制）";
        }
        if (policy.decide(ctx.env(), ToolLevel.L2_EXECUTE_ISOLATED) == PolicyDecision.DENY) {
            return "err: 生产环境 " + ctx.env() + " 的策略不允许直接触发构建（E-707），请先在测试环境验证";
        }
        String runId;
        try {
            PipelineEngine.DryRunReport dryRun = engine.dryRun(ctx.projectId(), pipelineId,
                    ctx.env(), gitSha);
            if (!dryRun.ok()) {
                return "err: E-708 dry-run 未通过：" + String.join("；", dryRun.blockers());
            }
            runId = engine.trigger(ctx.projectId(), pipelineId,
                    Map.of("gitSha", gitSha, "env", ctx.env(),
                            "dryRunHash", dryRun.reportHash()));
        } catch (IllegalArgumentException e) {
            // 前置校验（缺定义/参数非法）应作为可读错误回给模型，而不是抛异常打断对话
            return "err: " + e.getMessage();
        }
        return """
                runId: %s
                pipeline: %s
                status: %s
                evidence: run:%s
                """.formatted(runId, pipelineId, engine.status(runId), runId);
    }

    @Tool(name = "ci_dry_run",
            description = """
                    只读预检：解析流水线、渲染命令、检查环境变量/密钥/必需配置与 Docker 可用性。
                    不创建容器、不部署、不推送镜像，对真实系统零变更。
                    apply 之前必须先 dry-run；若 ok=false，把 blockers 原样告诉用户，不要重试到通过为止。
                    report_hash 可用于人工审批绑定；代码/配方/环境/密钥版本变化后旧报告自动失效。""",
            readOnly = true, concurrencySafe = true)
    public String dryRun(
            @ToolParam(name = "pipeline_id", description = "流水线标识，必须来自 ci_list_pipelines") String pipelineId,
            @ToolParam(name = "git_sha", description = "要预检的提交短 sha（7-40 位十六进制）") String gitSha,
            ProjectContext ctx) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return "err: pipeline_id 不能为空";
        }
        if (gitSha == null || !gitSha.matches("[0-9a-f]{7,40}")) {
            return "err: git_sha 格式非法（需 7-40 位小写十六进制）";
        }
        PipelineEngine.DryRunReport report;
        try {
            report = engine.dryRun(ctx.projectId(), pipelineId, ctx.env(), gitSha);
        } catch (IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ok: ").append(report.ok()).append('\n')
                .append("reportHash: ").append(report.reportHash()).append('\n')
                .append("fingerprint: ").append(report.fingerprint()).append('\n');
        if (!report.blockers().isEmpty()) {
            sb.append("blockers:\n");
            report.blockers().forEach(b -> sb.append("  - ").append(b).append('\n'));
        }
        if (!report.warnings().isEmpty()) {
            sb.append("warnings:\n");
            report.warnings().forEach(w -> sb.append("  - ").append(w).append('\n'));
        }
        sb.append("stages:\n");
        report.stages().forEach(s -> sb.append("  ").append(s.id())
                .append(" [").append(s.type()).append(s.action() == null ? "" : ":" + s.action())
                .append("] ").append(s.runnable() ? "RUN" : "SKIP")
                .append(s.reason() == null || s.reason().isBlank() ? "" : " — " + s.reason())
                .append('\n'));
        return sb.toString();
    }

    @Tool(name = "ci_cancel",
            description = """
                    取消一次尚未结束的构建。写操作。
                    仅对 RUNNING / PENDING 状态的运行有效；已结束的运行返回提示，不报错。""",
            readOnly = false, concurrencySafe = false)
    public String cancel(
            @ToolParam(name = "run_id", description = "ci_run 返回的 runId") String runId,
            ProjectContext ctx) {
        if (runId == null || runId.isBlank()) {
            return "err: run_id 不能为空";
        }
        if (!engine.hasRun(runId)) {
            return "err: run 不存在：" + runId;
        }
        if (engine.status(runId).isTerminal()) {
            return "run " + runId + " 已结束（" + engine.status(runId) + "），无需取消";
        }
        engine.cancel(runId);
        return """
                runId: %s
                status: %s
                evidence: run:%s""".formatted(runId, engine.status(runId), runId);
    }
}
