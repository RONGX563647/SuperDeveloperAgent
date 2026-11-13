package top.rongx.agent.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import top.rongx.agent.runtime.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台入口。
 *
 * <p>启动前加载同目录 {@code .env} 文件（若存在）：K=V 每行一条，
 * 只在<b>环境变量尚未设置</b>时注入——真实环境变量优先于 .env（12-factor 语义）。
 * 该文件已被 .gitignore 忽略，凭证不入配置库（规约 §8）。
 */
@SpringBootApplication(scanBasePackages = "top.rongx.agent")
@EnableConfigurationProperties(AgentProperties.class)
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        // 向上查找 .env（当前目录 → 父目录，最多 3 层）：兼容 mvn spring-boot:run
        // 的工作目录在模块子目录（agent-gateway/）而 .env 放在仓库根（platform/）的情况
        Path dir = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 3 && dir != null; i++) {
            loadDotEnv(dir.resolve(".env"));
            dir = dir.getParent();
        }
        SpringApplication.run(AgentApplication.class, args);
    }

    /** 解析 .env 并注入系统环境变量；已存在的变量不覆盖。文件不存在时静默跳过。 */
    static void loadDotEnv(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                // 去掉可选的引号包裹
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (key.matches("[A-Za-z_][A-Za-z0-9_]*")
                        && System.getenv(key) == null
                        && System.getProperty(key) == null) {
                    // 可移植写法：注入为系统属性，Spring 的 relaxed binding 能读到 ${KEY} 占位符
                    System.setProperty(key, value);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // .env 解析失败不阻塞启动：真实环境变量仍可兜底
        }
    }
}
