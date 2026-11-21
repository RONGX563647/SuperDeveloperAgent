package top.rongx.agent.domain.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 小文本文件读取（唯一实现）。
 *
 * <p>「读 version / current / pid 这类单值文件，缺失或为空就当没有」曾经
 * 在本地服务运行时、主机进程监督与部署控制面各写一遍。
 */
public final class TextFiles {

    private TextFiles() {
    }

    /**
     * 读 UTF-8 文本并 {@code trim}。
     *
     * @return 文件不存在、内容为空或读取失败时返回 {@code null}
     */
    public static String readTrimmedOrNull(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
