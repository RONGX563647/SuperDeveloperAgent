package top.rongx.agent.gateway.web.dto;

import java.util.List;

/**
 * 对话请求。
 *
 * @param projectId  项目（会话建立时确定，之后不可变更）
 * @param env        环境
 * @param sessionId  会话标识，可空（空则新建）
 * @param message    用户输入
 * @param worktree   当前工作树名（空 = 主仓库），仅作为会话上下文提示
 * @param skills     本会话启用的 Skill 目录名；null = 按设置页默认启用项，[] = 全部关闭
 * @param mcpServers 本会话启用的 MCP 服务名；null = 按设置页默认启用项，[] = 全部关闭
 * @param modelConnectionId 本会话选择的模型连接 id；null = 设置页默认连接
 * @param modelName          本会话选择的模型名；null = 该连接配置里的模型名
 */
public record ChatRequest(String projectId, String env, String sessionId, String message,
                          String worktree, List<String> skills, List<String> mcpServers,
                          String modelConnectionId, String modelName) {

    /** 兼容旧调用（未选择模型）。 */
    public ChatRequest(String projectId, String env, String sessionId, String message,
                       String worktree, List<String> skills, List<String> mcpServers) {
        this(projectId, env, sessionId, message, worktree, skills, mcpServers, null, null);
    }
}
