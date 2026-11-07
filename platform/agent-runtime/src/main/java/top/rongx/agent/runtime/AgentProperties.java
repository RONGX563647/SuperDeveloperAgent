package top.rongx.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型与运行时配置。
 *
 * <p>默认值取"安全侧"：迭代次数有上限、超时短、密钥无默认值（缺失即启动失败）。
 *
 * @param baseUrl OpenAI 兼容端点
 * @param apiKey  API Key（必须由环境变量注入）
 * @param model   模型名
 * @param maxIters 单轮工具调用最大迭代
 * @param temperature 采样温度
 */
@ConfigurationProperties(prefix = "agent.model")
public record AgentProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer maxIters,
        Double temperature) {

    public AgentProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl;
        model = model == null || model.isBlank() ? "qwen-plus" : model;
        maxIters = maxIters == null || maxIters < 1 ? 8 : Math.min(maxIters, 30);
        temperature = temperature == null ? 0.2d : temperature;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "agent.model.api-key 未配置：请通过环境变量 AGENT_MODEL_API_KEY 注入（规约 §8）");
        }
    }
}
