package top.rongx.agent.domain.docker;

import java.util.List;

/**
 * 容器资源限制的默认值与校验（唯一实现）。
 *
 * <p>流水线沙箱（{@code agent.sandbox.*}）与会话沙箱（{@code agent.agent-sandbox.*}）
 * 各维护一套 memory/cpus/pids/user 的兜底：一边校验、一边不校验；一边默认
 * {@code 1000:1000}、一边留空用镜像用户。配置写错时，只有一个入口会告警，
 * 另一个要到 {@code docker run} 才报 CLI 原始错误。
 *
 * <p>本类只做纯字符串/整数规整，不绑定 Spring，配置类各自保留自己的 Bean 与默认网络/用户。
 *
 * @param image     镜像
 * @param network   网络
 * @param memory    内存（必须带单位，如 {@code 2g}）
 * @param cpus      CPU 核数（可为小数，如 {@code 1.5}）
 * @param pidsLimit 进程数上限（必须 &gt; 0）
 * @param user      运行用户（{@code uid:gid}；空串表示沿用镜像默认用户）
 */
public record DockerLimits(String image, String network, String memory, String cpus,
                           int pidsLimit, String user) {

    public static final String DEFAULT_IMAGE = "maven:3.9-eclipse-temurin-21";
    public static final String DEFAULT_MEMORY = "2g";
    public static final String DEFAULT_CPUS = "2";
    public static final int DEFAULT_PIDS_LIMIT = 512;

    /**
     * 规整并校验资源限制。
     *
     * @param defaultNetwork 网络为空时的默认值（流水线 {@code bridge}，会话 {@code none}）
     * @param defaultUser    用户为空时的默认值（会话传空串表示沿用镜像用户）
     * @param prefix         配置前缀（用于告警文案，如 {@code agent.sandbox.}）
     * @param problems       收集配置问题（不抛异常：错误配置应可降级启动）
     */
    public static DockerLimits normalize(String image, String network, String memory, String cpus,
                                         int pidsLimit, String user, String defaultNetwork,
                                         String defaultUser, String prefix,
                                         List<String> problems) {
        String normalizedMemory = normalizeMemory(memory, prefix, problems);
        String normalizedCpus = normalizeCpus(cpus, prefix, problems);
        int normalizedPids = pidsLimit;
        if (normalizedPids <= 0) {
            // 0 在 docker 里表示"无限制"，正好抵消这条隔离
            problems.add(prefix + "pids-limit 必须 > 0（0 表示无限制，会抵消隔离）："
                    + pidsLimit + "，已回落到 " + DEFAULT_PIDS_LIMIT);
            normalizedPids = DEFAULT_PIDS_LIMIT;
        }
        String normalizedUser = user == null ? "" : user.trim();
        if (normalizedUser.isEmpty() && defaultUser != null && !defaultUser.isBlank()) {
            normalizedUser = defaultUser;
        }
        return new DockerLimits(
                blankTo(image, DEFAULT_IMAGE),
                blankTo(network, defaultNetwork),
                normalizedMemory,
                normalizedCpus,
                normalizedPids,
                normalizedUser);
    }

    /** 校验 {@code --memory}：单位必填（纯数字在 docker 里是字节，几乎一定是漏写单位）。 */
    public static String normalizeMemory(String memory, String prefix, List<String> problems) {
        if (memory == null || !memory.trim().matches("[1-9][0-9]*[bkmgBKMG]")) {
            problems.add(prefix + "memory 非法：" + memory
                    + "（必须带单位，如 2g / 512m；纯数字会被当成字节数），已回落到 "
                    + DEFAULT_MEMORY);
            return DEFAULT_MEMORY;
        }
        return memory.trim();
    }

    /** 校验 {@code --cpus}：只接受正数（小数表示核数的分数）。 */
    public static String normalizeCpus(String cpus, String prefix, List<String> problems) {
        if (cpus == null || !cpus.trim().matches("[0-9]+(\\.[0-9]+)?")) {
            problems.add(prefix + "cpus 非法：" + cpus + "（应为 2 / 1.5），已回落到 "
                    + DEFAULT_CPUS);
            return DEFAULT_CPUS;
        }
        return cpus.trim();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
