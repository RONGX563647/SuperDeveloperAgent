package top.rongx.agent.domain.settings;

/**
 * 模型连接（设置页管理；替代 application.yml 里的 agent.model.*）。
 *
 * @param id            主键
 * @param name          展示名
 * @param provider      厂商编码：dashscope | deepseek | openai | ollama | custom
 * @param baseUrl       OpenAI 兼容端点（如 https://dashscope.aliyuncs.com/compatible-mode/v1）
 * @param modelName     模型名（如 qwen-plus）
 * @param temperature   采样温度
 * @param maxIters      单轮工具调用最大迭代
 * @param enabled       是否启用
 * @param isDefault     是否默认（运行时使用）
 * @param hasApiKey     是否已保存密钥（回显用）
 * @param apiKeyMasked  密钥掩码（仅列表展示）
 * @param lastTestAt    最近测试时间
 * @param lastTestOk    最近测试是否成功
 * @param lastTestMsg   最近测试说明
 * @param lastTestLatencyMs 最近测试延迟
 * @param updatedBy     最后修改人
 * @param updatedAt     最后修改时间
 */
public record ModelConnection(
        String id,
        String name,
        String provider,
        String baseUrl,
        String modelName,
        double temperature,
        int maxIters,
        boolean enabled,
        boolean isDefault,
        boolean hasApiKey,
        String apiKeyMasked,
        Long lastTestAt,
        Boolean lastTestOk,
        String lastTestMsg,
        Long lastTestLatencyMs,
        String updatedBy,
        long updatedAt) {
}
