package top.rongx.agent.domain.deploy;

/**
 * 健康检查路径规范化：空值回退 {@code /}，缺失前导斜杠自动补齐。
 *
 * <p>此前 Host / Server / Docker 三个部署器各写了一份完全相同的实现。
 */
public final class HealthPath {

    private HealthPath() {
    }

    public static String normalize(String healthPath) {
        if (healthPath == null || healthPath.isBlank()) {
            return "/";
        }
        return healthPath.startsWith("/") ? healthPath : "/" + healthPath;
    }
}
