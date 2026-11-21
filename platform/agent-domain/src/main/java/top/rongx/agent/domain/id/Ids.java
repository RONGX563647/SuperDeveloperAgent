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

    private Ids() {
    }

    /**
     * 生成 {@code prefix-xxxxxxxx} 形式的 ID。
     *
     * @param prefix 前缀（空/null 时只返回随机段）
     */
    public static String newId(String prefix) {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        String tail = HexFormat.of().formatHex(raw);
        return prefix == null || prefix.isBlank() ? tail : prefix + "-" + tail;
    }
}
