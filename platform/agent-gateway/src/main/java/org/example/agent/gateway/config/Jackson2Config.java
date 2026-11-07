package org.example.agent.gateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 的 ObjectMapper。
 *
 * <p><b>为什么需要它</b>：Spring Boot 4 默认使用 Jackson 3（{@code tools.jackson.*}），
 * 容器里没有 {@code com.fasterxml.jackson.databind.ObjectMapper} 这个 Bean；
 * 而 AgentScope 的事件模型是按 Jackson 2 注解的，序列化必须用 Jackson 2。
 *
 * <p>两者包名不同，可以共存：Jackson 3 仍负责 Spring MVC 的 HTTP 消息转换，
 * 本 Bean 只用于序列化 Agent 事件。
 */
@Configuration
public class Jackson2Config {

    /** 供 Agent 事件序列化使用（不参与 HTTP 消息转换）。 */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
