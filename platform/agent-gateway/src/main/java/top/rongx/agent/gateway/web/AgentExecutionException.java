package top.rongx.agent.gateway.web;

/**
 * Agent 执行失败（模型超时、调用异常、未按 schema 返回）。
 *
 * <p>与 {@link IllegalStateException}（业务状态冲突，HTTP 409）区分开：这类错误是服务端
 * 依赖失败，统一映射为 500，避免客户端把"模型挂了"误判成"需要重新确认"。
 */
public class AgentExecutionException extends RuntimeException {

    public AgentExecutionException(String message) {
        super(message);
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
