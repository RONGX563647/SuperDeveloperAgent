package top.rongx.agent.domain.release;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布版本号：三段式 {@code MAJOR.MINOR.PATCH}，不带 {@code v} 前缀。
 *
 * <p>版本与分支强绑定，但绑定的是「版本线」而不是单个版本：
 * <ul>
 *   <li>版本线 = {@code MAJOR.MINOR}，例如 {@code 0.1}；</li>
 *   <li>版本线对应一个长期分支 {@code MAJOR.MINOR.0-snapshot}，例如 {@code 0.1.0-snapshot}；</li>
 *   <li>补丁版本 {@code 0.1.1 / 0.1.2} 继续提交在同一分支上，不新开分支；</li>
 *   <li>只有新的基础版本 {@code 0.2.0} 才需要新建 {@code 0.2.0-snapshot} 分支。</li>
 * </ul>
 *
 * <p>标签名就是版本号本身（{@code 0.1.0 / 0.1.1}），快照构建不产生标签，
 * 统一用 {@link #snapshotLabel()} 展示。
 */
public record ReleaseVersion(int major, int minor, int patch)
        implements Comparable<ReleaseVersion> {

    private static final Pattern RELEASE = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");
    private static final Pattern VERSION_BRANCH =
            Pattern.compile("(\\d{1,4})\\.(\\d{1,4})\\.0-snapshot");

    public ReleaseVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("版本号不能为负数");
        }
    }

    /**
     * 解析正式版本号：只接受 {@code x.y.z}。
     *
     * <p>明确拒绝 {@code v} 前缀与 {@code -snapshot} 后缀：标签不用 v 前缀，
     * 快照是发布动作的属性，不是版本号的一部分。
     */
    public static ReleaseVersion parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        Matcher matcher = RELEASE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "版本号必须是 MAJOR.MINOR.PATCH（例如 0.1.0，不带 v 前缀）：" + raw);
        }
        return new ReleaseVersion(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    }

    /** 宽松解析：格式不合法返回空。 */
    public static Optional<ReleaseVersion> tryParse(String raw) {
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 版本线（MAJOR.MINOR）：分支与版本归属都按它比较。 */
    public String line() {
        return major + "." + minor;
    }

    /** 版本线对应的长期分支：0.1 → 0.1.0-snapshot。 */
    public String branchName() {
        return line() + ".0-snapshot";
    }

    /** git 标签名：与版本号一致，不带 v。 */
    public String tagName() {
        return toString();
    }

    /** 快照展示名：0.1.1-snapshot（不打 tag 的构建）。 */
    public String snapshotLabel() {
        return toString() + "-snapshot";
    }

    /** 下一个补丁版本：补丁号只允许 +1，不允许跳号。 */
    public ReleaseVersion nextPatch() {
        return new ReleaseVersion(major, minor, patch + 1);
    }

    /** 分支名对应的版本线（0.1）；不是版本分支时返回 null。 */
    public static String lineOfBranch(String branch) {
        String value = branch == null ? "" : branch.trim();
        Matcher matcher = VERSION_BRANCH.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1)) + "." + Integer.parseInt(matcher.group(2));
    }

    /** 分支名对应的基础版本（0.1.0）；不是版本分支时返回 null。 */
    public static ReleaseVersion baseOfBranch(String branch) {
        String line = lineOfBranch(branch);
        if (line == null) {
            return null;
        }
        return parse(line + ".0");
    }

    /** 分支是否属于当前版本所在的版本线。 */
    public boolean onBranch(String branch) {
        return line().equals(lineOfBranch(branch));
    }

    /** 是否是版本线分支（<=> 由平台创建的发布分支）。 */
    public static boolean isVersionBranch(String branch) {
        return lineOfBranch(branch) != null;
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /** 兼容大小写与空白：分支名比较统一按小写。 */
    public static String normalizeBranch(String branch) {
        return branch == null ? "" : branch.trim().toLowerCase(Locale.ROOT);
    }
}
