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
        // 默认值统一在 application.yml 的 ${ENV:默认} 里声明，Java 端缺失即快速失败，避免双源漂移
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("agent.model.base-url 未配置");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("agent.model.model 未配置");
        }
        maxIters = maxIters == null || maxIters < 1 ? 8 : Math.min(maxIters, 30);
        temperature = temperature == null ? 0.2d : temperature;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "agent.model.api-key 未配置：请通过环境变量 AGENT_MODEL_API_KEY 注入（规约 §8）");
        }
    }
}
