package top.rongx.agent.infra.state;

import io.agentscope.core.state.AgentStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 会话状态存储装配。
 *
 * <p>运行层（agent-runtime）只依赖 {@link AgentStateStore} 这个 AgentScope 接口，
 * 具体的"加密 + 文件落盘"实现与主密钥都在基础设施层，避免 runtime 反向依赖 infra。
 */
@Configuration
public class AgentStateConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentStateConfig.class);

    @Bean
    public AgentStateStore agentStateStore(
            SecretBox secretBox,
            @Value("${agent.state.root:}") String stateRoot,
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        return new EncryptedAgentStateStore(stateDirectory(stateRoot, workspaceRoot), secretBox);
    }

    /** 会话状态目录：默认挂在平台工作区下，创建后收紧为属主 700。 */
    private static Path stateDirectory(String stateRoot, String workspaceRoot) {
        Path dir = stateRoot == null || stateRoot.isBlank()
                ? Path.of(workspaceRoot).toAbsolutePath().normalize()
                        .resolve("_platform").resolve("agent-state")
                : Path.of(stateRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir,
                    PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            log.warn("无法收紧会话状态目录权限（{}）：{}", dir, e.getMessage());
        }
        return dir;
    }
}
