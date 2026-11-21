package top.rongx.agent.domain.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 仓库/工作树内路径边界（唯一实现）。
 *
 * <p>「相对路径不能跑出根目录」曾在仓库只读工具、代码补丁、缺陷修复、会话工作树与
 * 本地目录浏览里各写一遍，强度还不一致：多数只做 {@code normalize + startsWith}，
 * 挡得住 {@code ../}，挡不住符号链接——仓库里一个 {@code link -> /etc} 会让
 * {@code read_file("link/passwd")} 读到仓库外。
 *
 * <p>本类采用更严格的算法：
 * <ol>
 *   <li>拒绝绝对路径（绝对路径会直接丢弃 root）；</li>
 *   <li>{@code normalize} 后必须仍在根内（挡住 {@code ../}）；</li>
 *   <li>取「最近的已存在祖先」的 {@code toRealPath()} 再校验一次（挡住符号链接逃逸，
 *       目标不存在时校验其父目录，防止先写后读式绕过）。</li>
 * </ol>
 *
 * <p>只依赖 JDK 与 domain 自身错误类型，可被 tools / infra / gateway 共用。
 */
public final class PathGuard {

    private PathGuard() {
    }

    /**
     * 解析相对路径并保证结果落在 {@code root} 内。
     *
     * @param root     边界根（建议传真实路径；若根本身是符号链接，会额外用其真实路径兜底）
     * @param relative 相对路径；空串表示根自身
     * @return 规范化后的路径（可能尚不存在）
     * @throws RunException 路径非法或经 {@code ../}、符号链接逃逸出根
     */
    public static Path resolveInside(Path root, String relative) {
        if (root == null) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "根目录不能为空");
        }
        Path base = root.toAbsolutePath().normalize();
        String rel = relative == null ? "" : relative.trim();
        if (rel.isEmpty()) {
            return base;
        }
        Path candidate;
        try {
            candidate = Path.of(rel);
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "路径非法：" + relative);
        }
        if (candidate.isAbsolute()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "路径超出仓库范围：" + relative);
        }
        Path resolved = base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "路径超出仓库范围：" + relative);
        }
        Path realBase = realOr(base);
        // 找到最近一个存在的祖先（目标可能尚不存在），用真实路径做范围校验
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "路径超出仓库范围：" + relative);
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "路径不可读：" + relative);
        }
        if (!real.startsWith(realBase) && !real.equals(realBase)) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT,
                    "路径经符号链接超出仓库范围：" + relative);
        }
        return resolved;
    }

    /**
     * 判断 {@code target} 是否在 {@code root} 之内（含相等）。
     *
     * <p>用于调用方已经拿到真实路径、只需判定边界的场景。
     */
    public static boolean isInside(Path root, Path target) {
        if (root == null || target == null) {
            return false;
        }
        Path base = realOr(root.toAbsolutePath().normalize());
        Path normalized = target.toAbsolutePath().normalize();
        return normalized.equals(base) || normalized.startsWith(base);
    }

    /**
     * 统一仓库内相对路径写法：去空白、反斜杠转正斜杠、去掉前导 {@code ./}。
     *
     * <p>同一段逻辑曾在 code patch / bugfix 修复 / bugfix git 三处各写一遍，
     * 任何一处漏掉反斜杠都会让同一份补丁在不同环节匹配失败。
     */
    public static String normalizeRelative(String path) {
        if (path == null) {
            return "";
        }
        String value = path.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }

    /**
     * 取 {@code file} 相对 {@code root} 的路径（{@code /} 分隔）。
     *
     * @return 不在根内返回 {@code null}
     */
    public static String relative(Path root, Path file) {
        if (root == null || file == null) {
            return null;
        }
        Path base = root.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            return null;
        }
        return base.relativize(normalized).toString().replace('\\', '/');
    }

    /** 能解析真实路径就解析，否则退回规范化路径（根可能尚不存在）。 */
    private static Path realOr(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
