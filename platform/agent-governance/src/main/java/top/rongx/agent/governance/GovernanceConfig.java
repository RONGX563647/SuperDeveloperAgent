package top.rongx.agent.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.rongx.agent.domain.policy.DangerousActionPolicy;

/** 治理组件的装配：策略本身在 domain（tools 可依赖），阈值由配置注入。 */
@Configuration
public class GovernanceConfig {

    @Bean
    public DangerousActionPolicy dangerousActionPolicy(
            @Value("${agent.security.dangerous-max-per-window:3}") int maxPerWindow,
            @Value("${agent.security.dangerous-window-seconds:600}") long windowSeconds) {
        return new DangerousActionPolicy(maxPerWindow, windowSeconds);
    }
}
