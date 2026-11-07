package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.PipelineEngine;
import org.springframework.stereotype.Component;

/**
 * 流水线写工具（L2：仅测试环境）。
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
                    触发一次构建/测试（仅测试环境）。写操作。
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
