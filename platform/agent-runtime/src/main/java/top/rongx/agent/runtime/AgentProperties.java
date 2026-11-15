package top.rongx.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型与运行时配置。
 *
 * <p>默认值取"安全侧"：迭代次数有上限、超时短。<b>API Key 不阻塞启动</b>——
 * 它既可以在设置页「模型连接」里配置（加密落库、热切换），也可以由环境变量
 * {@code AGENT_MODEL_API_KEY} 兜底注入（规约 §8：明文凭证不入配置库）。
 * 两处都没有时服务照常启动，只在真正调用模型时给出明确指引；否则会出现
 * "没配密钥 → 启不来 → 进不了设置页配密钥"的死循环。
 *
 * @param baseUrl OpenAI 兼容端点
 * @param apiKey  API Key（可选；空串表示尚未配置）
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
        // 默认值统一在 application.yml 的 ${ENV:默认} 里声明，Java 端只做归一与边界收敛
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("agent.model.base-url 未配置");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("agent.model.model 未配置");
        }
        maxIters = maxIters == null || maxIters < 1 ? 8 : Math.min(maxIters, 30);
        temperature = temperature == null ? 0.2d : temperature;
        // 密钥允许缺失：null / 全空白统一归一为空串，下游用 hasApiKey() 判断。
        // 不做启动期 fail-fast，配置入口（设置页）必须先可用。
        apiKey = apiKey == null || apiKey.isBlank() ? "" : apiKey;
    }

    /** 是否已配置 API Key（设置页模型连接或环境变量任一来源）。 */
    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }
}
