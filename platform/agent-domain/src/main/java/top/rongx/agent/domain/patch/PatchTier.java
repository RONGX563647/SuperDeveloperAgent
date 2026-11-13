package top.rongx.agent.domain.patch;

import java.util.List;
import top.rongx.agent.domain.policy.AutonomyLevel;

/**
 * 补丁范围分档：不再"一刀切允许 ci/ 与构建配置"。
 *
 * <ul>
 *   <li>{@link #CI_BUILD}：流水线定义、构建配置、测试代码、Dockerfile——修复 CI 失败的主力；</li>
 *   <li>{@link #CONFIG_DEP}：部署/运行配置（deploy/**、compose、nginx、application*.yml、package.json）；</li>
 *   <li>{@link #SOURCE}：业务源码，默认不允许 Agent 修改。</li>
 * </ul>
 */
public enum PatchTier {

    CI_BUILD,
    CONFIG_DEP,
    SOURCE,
    NONE;

    /** 按路径分档（不在白名单内返回 NONE）。 */
    public static PatchTier classify(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
            return NONE;
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("ci/") || p.equals("Jenkinsfile")
                || p.endsWith("pom.xml") || p.endsWith("build.gradle") || p.endsWith("build.gradle.kts")
                || p.startsWith("src/test/") || p.contains("/src/test/")
                || p.equals("Dockerfile") || p.endsWith("/Dockerfile") || p.endsWith(".Dockerfile")) {
            return CI_BUILD;
        }
        if (p.startsWith("deploy/") || p.contains("/deploy/")
                || p.endsWith("docker-compose.yml") || p.endsWith("docker-compose.yaml")
                || (p.contains("nginx") && p.endsWith(".conf")) || p.contains("/nginx/")
                || isApplicationConfig(p)
                || p.equals("package.json") || p.endsWith("/package.json")
                || p.endsWith(".env.example") || p.endsWith(".env.sample") || p.endsWith(".env.template")) {
            return CONFIG_DEP;
        }
        return NONE;
    }

    /** 该自治级别是否允许此档补丁。 */
    public static boolean allowedFor(AutonomyLevel autonomy, List<PatchTier> tiers) {
        if (autonomy == null || autonomy == AutonomyLevel.PROD) {
            return false;
        }
        for (PatchTier tier : tiers) {
            if (tier == NONE) {
                return false;
            }
            if (tier == SOURCE && autonomy != AutonomyLevel.AUTO) {
                return false;
            }
            // AUTO 全档；REVIEW 只允许 CI_BUILD / CONFIG_DEP
        }
        return true;
    }

    private static boolean isApplicationConfig(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.startsWith("application")
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }
}
