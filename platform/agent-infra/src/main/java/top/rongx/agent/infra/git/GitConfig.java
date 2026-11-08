package top.rongx.agent.infra.git;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** git 托管装配。 */
@Configuration
public class GitConfig {

    @Bean
    public GitWorktreeManager gitWorktreeManager(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        return new GitWorktreeManager(Path.of(workspaceRoot));
    }
}
