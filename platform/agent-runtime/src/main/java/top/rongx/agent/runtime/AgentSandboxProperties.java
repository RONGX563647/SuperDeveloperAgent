package top.rongx.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 会话沙箱配置（{@code agent.agent-sandbox.*}）。
 *
 * <p>只负责“Agent 在容器里执行命令”这一件事，与 {@code agent.sandbox.*}（CI 流水线沙箱）
 * 分开：流水线沙箱有镜像预热、缓存卷、构建日志等执行器语义，Agent 沙箱只需要
 * Harness 的 DockerFilesystemSpec 生命周期。
 *
 * <p>默认关闭；只有显式开启且会话不是只读、不是生产、已绑定工作树时才装配。
 */
@ConfigurationProperties(prefix = "agent.agent-sandbox")
public class AgentSandboxProperties {

    private boolean enabled;
    private String image = "maven:3.9-eclipse-temurin-21";
    /** none 最安全；需要装依赖时显式改成 bridge。 */
    private String network = "none";
    private String memory = "2g";
    private String cpus = "2";
    private int pidsLimit = 512;
    /** 空 = 使用镜像默认用户；宿主目录权限不一致时可在配置里指定 uid:gid。 */
    private String user = "";
    /** 挂载时使用的容器内工作区根；工具路径会按它归一化。 */
    private String workspaceRoot = "/workspace";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getCpus() {
        return cpus;
    }

    public void setCpus(String cpus) {
        this.cpus = cpus;
    }

    public int getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(int pidsLimit) {
        this.pidsLimit = pidsLimit;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }
}
