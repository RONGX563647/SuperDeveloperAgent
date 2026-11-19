package top.rongx.agent.infra.persistence.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

/**
 * 数据库后端的运行时切换配置（设置页写入，启动时生效）。
 *
 * <p>为什么单独落一个文件而不是直接改 {@code agent.db.*}：{@link AgentDbProperties} 是
 * 启动期装配连接池的输入，进程内改值没有任何意义——Hikari 已经建好连接。真正能切换
 * 后端的只有"下次启动"，因此配置必须落在进程外、且在 Spring 绑定 properties 之前读入。
 *
 * <p>文件位置 {@code <workspace>/_platform/db-backend.yml}，与主密钥同目录但内容不含
 * 密码明文——密码走 {@code SecretBox} 加密后存 {@code password-cipher}，
 * 明文只在内存中短暂存在（规约 §8：明文凭证不入配置库）。
 *
 * <p>优先级：真实环境变量 &gt; 本文件 &gt; application.yml 默认值。启动时由
 * {@code AgentApplication} 把本文件内容注入系统属性（已存在的键不覆盖），
 * 从而保证"页面上填的 PG 连接"能覆盖打包时的默认配置。
 */
public final class DbBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(DbBackendConfig.class);

    /** 文件内使用的键：与 Spring 的 relaxed binding 对应（url → agent.db.url 等）。 */
    public static final String KEY_URL = "db.url";
    public static final String KEY_USERNAME = "db.username";
    public static final String KEY_PASSWORD_CIPHER = "db.password-cipher";
    public static final String KEY_PASSWORD_IV = "db.password-iv";

    private DbBackendConfig() {
    }

    /** 配置文件路径：跟随 SQLite 文件所在目录（默认 {@code ./data/workspaces/_platform}）。 */
    public static Path file(Path sqliteFile) {
        Path dir = sqliteFile.toAbsolutePath().normalize().getParent();
        return dir == null ? Path.of("db-backend.yml") : dir.resolve("db-backend.yml");
    }

    /** 读取配置；文件不存在或损坏时返回空表（回落到 application.yml / 环境变量）。 */
    public static Map<String, String> read(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim();
                String value = unquote(trimmed.substring(colon + 1).trim());
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            log.warn("读取数据库后端配置失败（将回落到环境变量/默认值）{}：{}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
        return values;
    }

    /** 覆盖写配置文件（只含 url / username / 加密后的密码）。 */
    public static void write(Path file, String url, String username, String cipher, String iv) {
        StringBuilder out = new StringBuilder("# 由设置页写入：数据库后端。启动时生效，手工编辑亦可。\n");
        out.append("# 密码为 AES-256-GCM 密文（主密钥见 platform.key），不放明文。\n");
        out.append(KEY_URL).append(": ").append(quote(url)).append('\n');
        out.append(KEY_USERNAME).append(": ").append(quote(username)).append('\n');
        out.append(KEY_PASSWORD_CIPHER).append(": ").append(quote(cipher)).append('\n');
        out.append(KEY_PASSWORD_IV).append(": ").append(quote(iv)).append('\n');
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INTERNAL,
                    "写入数据库后端配置失败：" + file, e);
        }
    }

    /** 清空配置：回到 SQLite（删除文件即可，不保留空壳）。 */
    public static void clear(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INTERNAL,
                    "删除数据库后端配置失败：" + file, e);
        }
    }

    /**
     * 把配置注入系统属性（Spring 绑定前调用）。
     *
     * <p>只注入<b>尚未存在</b>的键：真实环境变量/命令行参数优先，符合 12-factor；
     * 这也让"临时用环境变量覆盖页面配置"成为可行的排障手段。
     *
     * <p>密码在这里被解密成明文后只放进系统属性（进程内存），不落盘、不入日志；
     * 解密失败不阻塞启动——会带着空密码去连库，由 PG 明确报出认证失败。
     *
     * @param decrypt 密文 → 明文；传 {@code null} 表示不解密（无可用主密钥）
     */
    public static void applyToSystemProperties(Path file, BiFunction<String, String, String> decrypt) {
        Map<String, String> values = read(file);
        String url = values.get(KEY_URL);
        if (url == null || url.isBlank()) {
            return;
        }
        putIfAbsent("AGENT_DB_URL", url);
        putIfAbsent("AGENT_DB_USERNAME", values.getOrDefault(KEY_USERNAME, ""));
        String cipher = values.get(KEY_PASSWORD_CIPHER);
        String iv = values.get(KEY_PASSWORD_IV);
        if (decrypt != null && cipher != null && iv != null && !cipher.isBlank() && !iv.isBlank()) {
            try {
                putIfAbsent("AGENT_DB_PASSWORD", decrypt.apply(cipher, iv));
            } catch (RuntimeException e) {
                log.warn("数据库密码解密失败（主密钥不匹配？），将按空密码尝试连接：{}",
                        e.getMessage());
            }
        }
        log.info("已加载设置页数据库配置：{}（{}）", url, file);
    }

    private static void putIfAbsent(String key, String value) {
        if (System.getenv(key) == null && System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static String quote(String value) {
        return '"' + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + '"';
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }
}
