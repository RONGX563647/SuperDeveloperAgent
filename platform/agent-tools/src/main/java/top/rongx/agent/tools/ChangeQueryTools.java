package top.rongx.agent.tools;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 变更单只读查询工具。
 *
 * <p><b>历史遗留说明（BUG-28）</b>：本类不再声明 {@code @Tool}——
 * {@code change_status} 的唯一实现在 {@link ChangeStatusTool}（两面复用同一个 bean，
 * 保留跨项目 + 跨环境越权校验）。这里保留查询入口做转发，方便仍持有本类引用的
 * 调用方（如 ToolChainTest）不改代码。
 *
 * <p>与 {@link ChangeTools} 拆开的原因：写工具（登记/推卡）不能出现在生产只读面，
 * 但"我的单子确认了没"必须能查——确认卡是异步广播，模型无法等待，只能事后查。
 */
@Component
public class ChangeQueryTools {

    private final ChangeStatusTool statusTool;

    public ChangeQueryTools(ChangeStatusTool statusTool) {
        this.statusTool = statusTool;
    }

    /**
     * 查询变更单状态（只读）。委托给唯一的 {@link ChangeStatusTool} 实现。
     */
    public String status(String changeId, ProjectContext ctx) {
        return statusTool.changeStatus(changeId, ctx);
    }
}
