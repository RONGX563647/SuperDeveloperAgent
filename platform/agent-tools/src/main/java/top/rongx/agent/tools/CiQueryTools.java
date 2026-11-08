package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.stream.Collectors;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
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
            description = """
                    查询一次构建的状态与各阶段结果。只读。用于确认构建是否成功。
                    返回含 terminal 字段：true 表示已结束，无需再次轮询；
                    失败时会给出失败阶段的 errorCode 与摘要，直接据此排查。""",
            readOnly = true, concurrencySafe = true)
    public String status(@ToolParam(name = "run_id", description = "ci_run 返回的 runId") String runId) {
        if (runId == null || runId.isBlank()) {
            return "err: run_id 不能为空";
        }
        if (!engine.hasRun(runId)) {
            return "err: run 不存在：" + runId + "（请核对 ci_run 返回的 runId）";
        }
        List<StageResult> stageList = engine.stages(runId);
        StageStatus status = engine.status(runId);
        String failedPart = "";
        if (status == StageStatus.FAILED) {
            failedPart = stageList.stream()
                    .filter(s -> s.status() == StageStatus.FAILED)
                    .map(s -> "- %s: %s %s".formatted(s.stageId(),
                            s.errorCode() == null ? "" : s.errorCode(),
                            s.errorSummary() == null ? "" : s.errorSummary()))
                    .collect(Collectors.joining("\n", "failed:\n", ""));
        }
        return """
                runId: %s
                status: %s
                terminal: %s
                stages: %d
                %sevidence: run:%s""".formatted(runId, status, status.isTerminal(),
                stageList.size(), failedPart, runId);
    }

    @Tool(name = "ci_history",
            description = "查询当前项目最近的构建历史（最多 20 条）。只读。用于回答'上次构建是什么时候/成没成功'。",
            readOnly = true, concurrencySafe = true)
    public String history(ProjectContext ctx) {
        List<PipelineEngine.RunSummary> items = engine.history().stream()
                .filter(h -> h.projectId().equals(ctx.projectId()))
                .limit(20)
                .toList();
        if (items.isEmpty()) {
            return "当前项目暂无构建历史。evidence: engine:" + engine.id();
        }
        return items.stream()
                .map(h -> "- %s  %s  %s  sha=%s  stages=%d  %s".formatted(
                        h.runId(), h.pipelineId(), h.status(), h.gitSha(),
                        h.stageCount(), h.createdAt()))
                .collect(Collectors.joining("\n",
                        "history:\n", "\nevidence: engine:" + engine.id()));
    }
}
