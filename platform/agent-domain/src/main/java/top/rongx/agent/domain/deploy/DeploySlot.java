package top.rongx.agent.domain.deploy;

import java.util.Locale;

/**
 * 部署版本槽：同一环境里并存部署的身份。
 *
 * <p>规则（与页面「打 tag = 补丁版本」一致）：
 * <ul>
 *   <li>打 tag 且有版本号 → 槽 = 版本号，不同补丁版本、不同版本线互不覆盖；</li>
 *   <li>不打 tag 或没有版本号 → 默认槽（{@code null}），覆盖该环境的默认部署。</li>
 * </ul>
 *
 * <p>此前 DockerDeployer / ServerDeployer / 流水线引擎各自实现过一遍，规则漂移导致
 * 远端打 tag 仍按环境名覆盖；统一到这里后三处行为一致，也便于单测锁定。
 */
public final class DeploySlot {

    private DeploySlot() {
    }

    /** 槽名；默认槽返回 {@code null}。 */
    public static String of(boolean tagged, String version) {
        return tagged ? normalize(version) : null;
    }

    /** 槽名；默认槽返回 {@code null}。 */
    public static String of(DeployRequest request) {
        return request == null ? null : of(request.tagged(), request.version());
    }

    /**
     * 归一化版本槽：小写 + 仅保留容器名/标签允许的字符。
     *
     * <p>空白版本返回 {@code null} 而不是伪造一个共享槽（避免所有"空版本"互相覆盖）。
     */
    public static String normalize(String version) {
        if (version == null) {
            return null;
        }
        String value = version.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        return value.isEmpty() ? null : value;
    }
}
