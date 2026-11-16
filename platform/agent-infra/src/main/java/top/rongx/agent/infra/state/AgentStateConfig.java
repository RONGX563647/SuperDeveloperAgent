package top.rongx.agent.infra.state;

import io.agentscope.core.state.AgentStateStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
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
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            @Value("${agent.context.max-chars:60000}") int contextMaxChars,
            @Value("${agent.context.keep-messages:24}") int contextKeepMessages) {
        return new EncryptedAgentStateStore(stateDirectory(stateRoot, workspaceRoot), secretBox,
                contextMaxChars, contextKeepMessages);
    }

    /**
     * 会话状态目录：默认挂在平台工作区下，创建后收紧为「仅属主可读写」。
     *
     * <p>POSIX 平台用 700；Windows 没有 POSIX 权限视图，改用 ACL 实现等价效果：
     * 清掉继承来的 Users/Everyone 等条目，只保留属主完全控制。
     */
    static Path stateDirectory(String stateRoot, String workspaceRoot) {
        Path dir = stateRoot == null || stateRoot.isBlank()
                ? Path.of(workspaceRoot).toAbsolutePath().normalize()
                        .resolve("_platform").resolve("agent-state")
                : Path.of(stateRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("无法创建会话状态目录（{}）：{}", dir, e.toString());
            return dir;
        }
        tightenPermissions(dir);
        return dir;
    }

    private static void tightenPermissions(Path dir) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException | IOException e) {
                log.warn("无法收紧会话状态目录权限（{}）：{}", dir, e.toString());
            }
            return;
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("acl")) {
            tightenWindowsAcl(dir);
            return;
        }
        log.debug("文件系统既无 POSIX 也无 ACL 权限视图，跳过状态目录收紧：{}", dir);
    }

    /** Windows：DACL 只保留属主完全控制，并同步收紧已存在的子项（旧文件可能仍带宽松 ACL）。 */
    private static void tightenWindowsAcl(Path dir) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
            if (view == null) {
                log.debug("无法获取状态目录 ACL 视图，跳过权限收紧：{}", dir);
                return;
            }
            view.setAcl(List.of(ownerEntry(view.getOwner(), true)));
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(path -> !path.equals(dir)).forEach(AgentStateConfig::tightenFileAcl);
            }
            log.debug("已收紧会话状态目录 ACL（仅属主）：{}", dir);
        } catch (IOException | SecurityException | UncheckedIOException e) {
            log.warn("无法收紧会话状态目录 ACL（{}）：{}", dir, e.toString());
        }
    }

    private static void tightenFileAcl(Path file) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(
                    file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                view.setAcl(List.of(ownerEntry(view.getOwner(), false)));
            }
        } catch (IOException | SecurityException e) {
            // 单个旧文件被占用/无权限不影响启动，下一次启动会再尝试
            log.debug("收紧状态文件 ACL 失败（{}）：{}", file, e.toString());
        }
    }

    private static AclEntry ownerEntry(UserPrincipal owner, boolean inheritToChildren) {
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (inheritToChildren) {
            builder.setFlags(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT));
        }
        return builder.build();
    }
}
