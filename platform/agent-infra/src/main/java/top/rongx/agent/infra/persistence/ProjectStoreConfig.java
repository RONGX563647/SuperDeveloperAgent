package top.rongx.agent.infra.persistence;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 项目仓储装配：预置（yml）∪ 接入（SQLite）→ Composite 对外。
 * SQLite 文件落在工作区根下（data/ 已 gitignore，不入库）。
 */
@Configuration
@EnableConfigurationProperties(ProjectsProperties.class)
public class ProjectStoreConfig {

    /** 预置项目源（只读，来自 application.yml）。 */
    @Bean
    public InMemoryProjectRepository presetProjectRepository(ProjectsProperties props) {
        return new InMemoryProjectRepository(props);
    }

    /** 接入项目存储（SQLite）。应用关闭时释放连接（Windows 下未关连接会锁住 db 文件）。 */
    @Bean(destroyMethod = "close")
    public IntakeProjectStore intakeProjectStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        return new IntakeProjectStore(Path.of(workspaceRoot).resolve("_platform").resolve("projects.db"));
    }

    /** 对外唯一视图：预置 ∪ 接入。 */
    @Bean
    @Primary
    public CompositeProjectRepository projectRepository(InMemoryProjectRepository preset,
                                                        IntakeProjectStore intake) {
        return new CompositeProjectRepository(preset, intake);
    }
}
