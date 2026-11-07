package org.example.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.stream.Collectors;
import org.example.agent.domain.project.ProjectContext;
import org.example.agent.domain.port.PipelineEngine;
import org.springframework.stereotype.Component;

/**
 * 流水线只读工具（L0）。
 *
 * <p>与 {@link CiCommandTools} 分开的原因：AgentScope 的工具分组是**按对象**注册的
 * （{@code registration().tool(obj).group(name)}），读/写混在一个类里会导致
 * 只读方法被一起锁进未激活的写组。
 */
@Component
public class CiQueryTools {

    private final PipelineEngine engine;

    public CiQueryTools(PipelineEngine engine) {
        this.engine = engine;
    }

    @Tool(name = "ci_list_pipelines",
            description = "列出当前项目可用的流水线。只读。触发构建前必须先调用本工具确认 pipelineId。",
            readOnly = true, concurrencySafe = true)
    public String listPipelines(ProjectContext ctx) {
        List<String> ids = engine.pipelines(ctx.projectId());
        return ids.stream()
                .collect(Collectors.joining("\n",
                        "pipelines(engine=" + engine.id() + "):\n",
                        "\nevidence: engine:" + engine.id()));
    }

    @Tool(name = "ci_status",
            description = "查询一次构建的状态与阶段数量。只读。用于确认构建是否成功。",
            readOnly = true, concurrencySafe = true)
    public String status(@ToolParam(name = "run_id", description = "ci_run 返回的 runId") String runId) {
        if (runId == null || runId.isBlank()) {
            return "err: run_id 不能为空";
        }
        return """
                runId: %s
                status: %s
                stages: %d
                evidence: run:%s
                """.formatted(runId, engine.status(runId), engine.stages(runId).size(), runId);
    }
}
