package top.rongx.agent.gateway.web;

import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * Agent 执行失败（模型超时、调用异常、未按 schema 返回）。
 *
 * <p>属于运行时（E-3xx）业务异常，统一映射为 500，避免客户端把"模型挂了"
 * 误判成"需要重新确认"。
 */
public class AgentExecutionException extends RunException {

    public AgentExecutionException(String message) {
        super(ErrorCode.AGENT_EXECUTION_FAILED, message);
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(ErrorCode.AGENT_EXECUTION_FAILED, message, cause);
    }
}
