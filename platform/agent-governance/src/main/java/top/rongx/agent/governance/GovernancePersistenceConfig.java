package top.rongx.agent.governance;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** governance 持久化装配：复用容器的 EMF，与平台其余表共用同一个库。 */
@Configuration
public class GovernancePersistenceConfig {

    @Bean
    public GovernanceDb governanceDb(EntityManagerFactory entityManagerFactory) {
        return GovernanceDb.managed(entityManagerFactory);
    }
}
