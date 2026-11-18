package top.rongx.agent.gateway.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 并发闸门装配：{@code agent.chat.limiter=memory|jdbc}。
 *
 * <p>单实例默认 memory；多实例部署切到 jdbc，由 SQLite 租约表共享配额。
 */
@Configuration
public class StreamLimiterConfig {

    @Bean
    public AgentStreamLimiter agentStreamLimiter(
            @Value("${agent.chat.limiter:memory}") String mode,
            @Value("${agent.chat.max-concurrent:8}") int max,
            @Value("${agent.chat.lease-ttl-seconds:600}") long ttlSeconds,
            PlatformDb platformDb) {
        if ("jdbc".equalsIgnoreCase(mode)) {
            return new JdbcAgentStreamLimiter(platformDb, max, ttlSeconds);
        }
        return new MemoryAgentStreamLimiter(max);
    }
}
