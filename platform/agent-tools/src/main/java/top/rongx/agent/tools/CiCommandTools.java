package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.PipelineEngine;
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

    public CiCommandTools(PipelineEngine engine) {
        this.engine = engine;
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
        if (!engine.pipelines(ctx.projectId()).contains(pipelineId)) {
            return "err: pipeline 不在白名单：" + pipelineId;
        }
        if (gitSha == null || !gitSha.matches("[0-9a-f]{7,40}")) {
            return "err: git_sha 格式非法（需 7-40 位小写十六进制）";
        }
        if (ctx.isProduction()) {
            return "err: 生产环境不允许直接触发构建，请先在测试环境验证";
        }
        String runId = engine.trigger(ctx.projectId(), pipelineId, Map.of("gitSha", gitSha));
        return """
                runId: %s
                pipeline: %s
                status: %s
                evidence: run:%s
                """.formatted(runId, pipelineId, engine.status(runId), runId);
    }
}
