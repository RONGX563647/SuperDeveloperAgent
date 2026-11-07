package top.rongx.agent.domain.action;

import top.rongx.agent.domain.run.StageResult;

/**
 * 动作执行结果 = 结构化结果 + 日志尾部。
 *
 * <p>日志尾部单独返回，是因为工具需要把"失败在哪一行"交给模型；
 * {@link StageResult} 保持精简（它是执行器契约的一部分，不塞日志）。
 *
 * @param result  结构化结果
 * @param logTail 输出尾部（已截断，已是脱敏后的文本）
 */
public record ActionResult(StageResult result, String logTail) {
}
