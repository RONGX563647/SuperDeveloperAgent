package top.rongx.agent.domain.change;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 计划哈希（规约 §4.10 第三道守卫）。
 *
 * <p>把"这次到底要做什么"规范化后取哈希；确认令牌绑定该哈希，执行前比对，
 * 防止"确认 A、执行 B"。
 */
public final class PlanHasher {

    private PlanHasher() {
    }

    /**
     * 计算规范化哈希。
     *
     * @param projectId 项目
     * @param env       环境
     * @param kind      变更类型
     * @param parts     参与哈希的关键字段（镜像 digest、前端 dist、SQL 文件清单等）
     * @return 64 位小写十六进制
     */
    public static String hash(String projectId, String env, String kind, String... parts) {
        // 1. 规范化拼接待哈希字段（null 归一为空串，避免歧义）
        StringBuilder sb = new StringBuilder();
        sb.append(nullToEmpty(projectId)).append('|')
                .append(nullToEmpty(env)).append('|')
                .append(nullToEmpty(kind));
        if (parts != null) {
            for (String part : parts) {
                sb.append('|').append(nullToEmpty(part));
            }
        }
        // 2. 取 SHA-256 作为计划指纹（确认令牌绑定此值）
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
