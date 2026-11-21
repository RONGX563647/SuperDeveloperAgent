package top.rongx.agent.domain.workspace;

import java.nio.file.Path;

/**
 * 工作区平台数据目录的唯一拼装入点。
 *
 * <p>此前 {@code _platform} 与它下面的 db/key/skills/deploy/local/... 字面量散落在
 * 7 个模块共 35 处，同一个库文件名被复制了 11 次。任一处手误就会让某个 Store
 * 悄悄读写另一个库。这里不改变目录结构，只把拼装收敛到一处。
 */
public final class WorkspacePaths {

    /** 平台数据目录名（位于用户工作区根目录下）。 */
    public static final String PLATFORM_DIR = "_platform";

    private WorkspacePaths() {
    }

    public static Path platform(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize().resolve(PLATFORM_DIR);
    }

    public static Path db(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("platform.db");
    }

    public static Path key(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("platform.key");
    }

    public static Path skills(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("skills");
    }

    public static Path deploy(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("deploy");
    }

    public static Path local(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("local");
    }

    public static Path issueImages(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("issues").resolve("images");
    }

    public static Path agentState(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("agent-state");
    }

    public static Path knownHosts(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("known_hosts");
    }

    public static Path seedPasswords(Path workspaceRoot) {
        return platform(workspaceRoot).resolve("seed-passwords.txt");
    }
}
