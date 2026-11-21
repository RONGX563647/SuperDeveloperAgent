package top.rongx.agent.domain.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    /** 文件内容摘要（流式读取，不把大文件整个读进内存）。 */
    public static String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                md.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 多段文本的摘要（按迭代顺序依次 update，不拼接成一个大字符串）。 */
    public static String sha256Hex(Iterable<String> chunks) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String chunk : chunks) {
                md.update(String.valueOf(chunk).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** HMAC-SHA256 原始字节；调用方按协议自行编码为 hex / base64。 */
    public static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM 缺少 HMAC-SHA256 实现", e);
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
