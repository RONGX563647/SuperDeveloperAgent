package top.rongx.agent.domain.port;

import java.util.List;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 运维/发布 Agent 的委派端口（多 Agent 协作）。
 *
 * <p>为什么是端口：工具层（agent-tools）不能依赖运行时（agent-runtime），否则形成反向依赖。
 * 工具只声明"我要委派一次运维任务"，由运行时提供实现（构造发布 Agent、消费事件流、压缩报告）。
 *
 * <p>安全约束（实现方必须遵守）：
 * <ul>
 *   <li>只在非生产环境执行；生产一律拒绝（发布 Agent 的生产入口是人工确认的部署面板）；</li>
 *   <li>项目/环境/操作人只能来自 {@link ProjectContext}，不能由被委派的任务文本改写；</li>
 *   <li>子 Agent 的工具面不含本端口对应工具（防递归委派）；</li>
 *   <li>子 Agent 仍受"写操作必须人工确认"的约束，不能借委派绕过确认卡。</li>
 * </ul>
 */
public interface OpsAgentPort {

    /**
     * 委派一次运维任务。
     *
     * @param ctx  当前会话上下文（项目/环境/操作人，服务端绑定）
     * @param task 要完成的目标，例如"把 test 环境跑通并固化脚本"
     */
    Report run(ProjectContext ctx, String task);

    /**
     * 委派结果（压缩后的报告，供对话 Agent 转述）。
     *
     * @param status  SUCCEEDED / FAILED / TIMEOUT / REJECTED
     * @param summary 关键结论（不含密钥；可能包含 changeId / runId / 证据行）
     * @param tools   子 Agent 实际调用过的工具名（去重）
     * @param errors  失败原因摘要（最多保留少量）
     */
    record Report(String status, String summary, List<String> tools, List<String> errors) {

        public Report {
            tools = tools == null ? List.of() : List.copyOf(tools);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean ok() {
            return "SUCCEEDED".equals(status);
        }
    }
}
