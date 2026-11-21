package top.rongx.agent.domain.id;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 短随机 ID 的统一生成（{@code prefix-8位十六进制}）。
 *
 * <p>模型连接 / 部署服务器 / MCP 服务 / Git 账号 / 环境资源 / Issue 曾各自实现一份，
 * 随机源与长度还不一致（4/5 字节随机、UUID 前 8 位）。这里只保留一种，
 * 避免同库里的 ID 存在多种形态、排查时无法判断来源。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /**
     * 生成 {@code prefix-xxxxxxxx} 形式的 ID。
     *
     * @param prefix 前缀（空/null 时只返回随机段）
     */
    public static String newId(String prefix) {
        return newId(prefix, 8);
    }

    /**
     * 生成指定十六进制位数的随机 ID。
     *
     * <p>位数只用于兼容既有外部契约（如缺陷运行号要求 12 位），新代码优先用 8 位。
     *
     * @param hexChars 随机段长度（2–64，奇数位会截断）
     */
    public static String newId(String prefix, int hexChars) {
        int length = Math.max(2, Math.min(64, hexChars));
        byte[] raw = new byte[(length + 1) / 2];
        RANDOM.nextBytes(raw);
        String tail = HexFormat.of().formatHex(raw).substring(0, length);
        return prefix == null || prefix.isBlank() ? tail : prefix + "-" + tail;
    }
}
