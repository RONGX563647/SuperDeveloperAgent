package top.rongx.agent.gateway.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Agent 轮次指标：耗时、工具调用次数、失败次数。
 *
 * <p>标签只放低基数维度（kind / outcome / tool），不要放 sessionId 或用户输入。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /** 一轮结束（正常/异常/客户端断开都调用一次）。 */
    public void stop(Timer.Sample sample, String kind, String outcome) {
        sample.stop(Timer.builder("agent.turn")
                .description("Agent 一轮执行的耗时")
                .tag("kind", kind)
                .tag("outcome", outcome)
                .register(registry));
    }

    /** 单次工具调用（按工具名聚合，便于看哪个工具最慢/最常失败）。 */
    public void toolCall(String tool, String outcome) {
        Counter.builder("agent.tool.calls")
                .description("Agent 工具调用次数")
                .tag("tool", tool == null ? "unknown" : tool)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
