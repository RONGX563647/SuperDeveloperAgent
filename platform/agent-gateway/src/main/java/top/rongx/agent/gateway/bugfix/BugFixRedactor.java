package top.rongx.agent.gateway.bugfix;

import top.rongx.agent.domain.process.LogRedactor;

/**
 * Agent 输出脱敏：自动修复会把模型摘要写进 Issue 评论与 PR 描述，
 * 模型可能复述日志里的 token / 密码 / 连接串，必须在离开平台前统一打码。
 *
 * <p><b>规则不在这里</b>：实现已收口到 {@link LogRedactor}（domain 层），
 * 本类只保留为入口，避免调用方全部改签名。收口前两份规则形状相近但阈值不同
 * （这里 6/16/20 字符起，执行内核 8/4 字符起，且各缺对方两条规则），
 * 结果是"同一段密钥写进运行日志脱了、写进 Issue 评论没脱"——比都没脱更难发现。
 */
public final class BugFixRedactor {

    private BugFixRedactor() {
    }

    public static String redact(String text) {
        return LogRedactor.redact(text);
    }
}
