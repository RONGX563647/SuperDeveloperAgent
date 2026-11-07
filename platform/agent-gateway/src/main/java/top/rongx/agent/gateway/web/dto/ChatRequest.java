package top.rongx.agent.gateway.web.dto;

/**
 * 对话请求。
 *
 * @param projectId 项目（会话建立时确定，之后不可变更）
 * @param env       环境
 * @param sessionId 会话标识，可空（空则新建）
 * @param message   用户输入
 */
public record ChatRequest(String projectId, String env, String sessionId, String message) {
}
