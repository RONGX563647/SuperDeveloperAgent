package top.rongx.agent.domain.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要与提交号展示的统一原语。
 *
 * <p>此前全平台有 5 份 {@code sha256} 与 7 份 {@code shortSha}：
 * 两处 sha256 在失败时静默退化成 {@code hashCode} / 随机 UUID（签名校验或补丁幂等
 * 会"看似正常但永远不匹配"）；shortSha 有 7/8 位两种长度、null 会渲染成字面量
 * {@code "null"}。这里只保留一种失败语义：SHA-256 不可用即抛错。
 */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 7 位短提交号；空值统一为 {@code "-"}（不再出现字面量 "null"）。 */
    public static String shortSha(String sha) {
        return shortSha(sha, 7);
    }

    public static String shortSha(String sha, int length) {
        if (sha == null || sha.isBlank()) {
            return "-";
        }
        return sha.length() <= length ? sha : sha.substring(0, length);
    }

    /** 常量时间比较，避免 webhook 签名校验被时序侧信道试探；null 视为不匹配。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
