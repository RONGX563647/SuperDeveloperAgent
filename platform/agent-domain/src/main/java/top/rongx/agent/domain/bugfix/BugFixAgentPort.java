package top.rongx.agent.domain.bugfix;

import top.rongx.agent.domain.bugfix.BugFixAgentPort.Role;

/**
 * 编排器与 Agent 之间的端口。实现负责构造项目上下文、挂载工具面并汇总最终正文，
 * 让编排逻辑可被单测（替换成脚本化假 Agent，不依赖模型网络）。
 */
public interface BugFixAgentPort {

    /** 运行一次非交互 Agent，返回最终正文（含结构化 JSON 时由阶段自行解析）。 */
    String run(BugFixRun run, Role role, String prompt);

    /** 阶段使用的 Agent 角色。 */
    enum Role {
        /** 分析 Bug：只读工具，输出定位与复现建议。 */
        ANALYZE,
        /** 测试环境复现判定。 */
        REPRODUCE,
        /** 修复代码：输出文件级补丁。 */
        FIX,
        /** 修复验证判定。 */
        VERIFY,
        /** 部署测试环境（运维 Agent）。 */
        DEPLOY
    }
}
