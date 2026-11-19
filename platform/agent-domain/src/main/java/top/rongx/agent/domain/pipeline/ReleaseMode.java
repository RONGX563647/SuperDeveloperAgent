package top.rongx.agent.domain.pipeline;

/**
 * 一次发布的形态判定：首次发布 / 增量发布 / 版本线不匹配。
 *
 * <p><b>为什么需要它</b>：「首次发布这里不对」的本质是平台把「没有上一版」当成了错误：
 * 回滚预检找不到 previous image 就报 E-507，用户看到一次失败却不知道该改什么。
 * 首次发布不是失败，是一个必须显式标记的正常分支。
 *
 * @param firstRelease 本次是否首次发布（该环境没有成功的上一版）
 * @param previousVersion 上一版版本号；空串 = 没有
 * @param reason 面向用户的说明（首次发布时讲清楚为什么跳过回滚预检）
 */
public record ReleaseMode(boolean firstRelease, String previousVersion, String reason) {

    public ReleaseMode {
        previousVersion = previousVersion == null ? "" : previousVersion.trim();
        reason = reason == null ? "" : reason.trim();
    }

    /**
     * 判定发布形态。
     *
     * @param previousTag 该环境上一版镜像的版本标签（空 = 没有成功发布过）
     */
    public static ReleaseMode of(String previousTag) {
        String previous = previousTag == null ? "" : previousTag.trim();
        if (previous.isBlank()) {
            return new ReleaseMode(true, "",
                    "首次发布：该环境没有上一版镜像，跳过回滚预检（这不是错误）");
        }
        return new ReleaseMode(false, previous, "增量发布：上一版 " + previous);
    }

    /**
     * 版本线校验：版本号与目标分支不一致时前置拒绝，而不是打出一个错位的标签。
     *
     * <p>规则：版本线分支（{@code 0.1.0-snapshot}）只能发 {@code 0.1.x}；
     * 非版本线分支不限制（发行分支可能叫 release/0.1 或 main）。
     *
     * @param version 本次要打的版本号（已归一化，不带 v）
     * @param branch  目标分支名
     * @return 空串 = 通过；否则为可读的拒绝原因
     */
    public static String versionBranchConflict(String version, String branch) {
        String ver = version == null ? "" : version.trim();
        String br = branch == null ? "" : branch.trim();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.0-snapshot$").matcher(br);
        if (!matcher.matches() || ver.isBlank()) {
            return "";
        }
        String line = matcher.group(1) + "." + matcher.group(2) + ".";
        if (ver.startsWith(line)) {
            return "";
        }
        return "版本号 " + ver + " 与分支 " + br + " 的版本线（" + line + "x）不一致："
                + "请改用 " + line + "x，或切到对应版本线分支后再发布";
    }
}
