package top.rongx.agent.infra.persistence.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 实体与仓储扫描：infra 自己的持久化 + governance 的持久化。
 *
 * <p>网关的主包在 {@code top.rongx.agent.gateway}，Boot 的自动扫描看不到这两个包，
 * 因此在这里显式声明。领域层不出现任何 JPA 类型。
 */
@Configuration
@EntityScan(basePackages = {
        "top.rongx.agent.infra",
        "top.rongx.agent.governance",
        "top.rongx.agent.gateway"})
@EnableJpaRepositories(basePackages = {"top.rongx.agent.infra", "top.rongx.agent.governance"})
public class PersistenceConfig {
}
