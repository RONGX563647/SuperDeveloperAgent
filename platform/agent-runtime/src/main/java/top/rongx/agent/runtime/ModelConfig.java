package top.rongx.agent.runtime;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配。
 *
 * <p>把 {@link Model} 做成单例 Bean：它内部持有 HTTP 客户端与连接池，
 * 每次对话都 new 一遍会浪费连接、增加延迟。配置不变时模型实例应当复用。
 */
@Configuration
public class ModelConfig {

    /** 与具体的 provider 无关——OpenAI 兼容端点可接通义 / DeepSeek / 自建 vLLM。 */
    @Bean
    public Model agentModel(AgentProperties props) {
        return OpenAIChatModel.builder()
                .apiKey(props.apiKey())
                .baseUrl(props.baseUrl())
                .modelName(props.model())
                .stream(true)
                // temperature 只能通过 generateOptions 生效（Builder 没有 temperature 方法）
                .generateOptions(GenerateOptions.builder().temperature(props.temperature()).build())
                .build();
    }
}
