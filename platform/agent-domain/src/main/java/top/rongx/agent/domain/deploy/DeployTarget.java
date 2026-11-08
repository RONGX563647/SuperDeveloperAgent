package top.rongx.agent.domain.deploy;

import java.util.Arrays;
import java.util.Optional;

/**
 * 部署目标（方案 C：发布编排 + 平台部署器）。
 *
 * <p>平台自己拥有部署语义，仓库只声明"部署到哪"，不再由仓库脚本决定：
 * <ul>
 *   <li>{@link #DOCKER}：本机 Docker 容器（替换式发布，保留上一版镜像供回滚）</li>
 *   <li>{@link #HOST}：本机直接进程（jar 包，pid 文件管理）</li>
 *   <li>{@link #SERVER}：远程服务器（后续引入服务器配置；当前 fail-closed）</li>
 * </ul>
 */
public enum DeployTarget {

    DOCKER("docker", "本机 Docker 容器"),
    HOST("host", "本机直接运行"),
    SERVER("server", "远程服务器（待接入配置）");

    private final String wireName;
    private final String label;

    DeployTarget(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    public static Optional<DeployTarget> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(t -> t.wireName.equals(normalized) || t.name().equalsIgnoreCase(normalized))
                .findFirst();
    }
}
