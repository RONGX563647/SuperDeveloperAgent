package top.rongx.agent.gateway.web;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import top.rongx.agent.domain.docker.ContainerState;
import top.rongx.agent.domain.hash.Hashes;

/**
 * 远端 {@code docker ps} 输出解析（纯函数，可单测）。
 *
 * <p>兼容两类容器：
 * <ul>
 *   <li>新版本：带 {@code agent.env / agent.version / agent.sha} 标签，优先用标签；</li>
 *   <li>旧版本：没有标签，从容器名 {@code agent-deploy-<project>-<env>[-<version>]} 推断。</li>
 * </ul>
 *
 * <p>输出格式固定为 {@link #FORMAT}，解析时按 {@code |} 分隔；格式不完整的行直接忽略，
 * 避免 ssh 登录横幅/警告污染矩阵。
 */
final class RemoteDockerPs {

    /** 与 ServerDeployer / DockerDeployer 的容器命名保持一致。 */
    static final String CONTAINER_PREFIX = "agent-deploy-";
    static final String FORMAT = "{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}"
            + "|{{.Label \"agent.env\"}}|{{.Label \"agent.version\"}}|{{.Label \"agent.sha\"}}"
            + "|{{.CreatedAt}}";

    private static final List<String> ENVS = List.of("dev", "test", "prod");

    private RemoteDockerPs() {
    }

    /** 解析一行行 docker ps 输出；只保留容器名属于该项目前缀的容器。 */
    static List<Row> parse(String projectId, String output) {
        String prefix = CONTAINER_PREFIX + projectId;
        List<Row> rows = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return rows;
        }
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            if (f.length < 8) {
                continue;
            }
            String name = f[0].trim();
            // 边界校验：agent-deploy-proj 不能误匹配 agent-deploy-proj2
            if (!name.equals(prefix) && !name.startsWith(prefix + "-")) {
                continue;
            }
            String rest = name.substring(prefix.length());
            String env = f[4].trim();
            if (env.isEmpty()) {
                env = envFromName(rest);
            }
            if (env.isEmpty()) {
                continue;
            }
            String sha = f[6].trim();
            String version = f[5].trim();
            if (version.isEmpty()) {
                version = versionFromName(rest, env, sha);
            }
            if (version.isEmpty()) {
                version = "latest";
            }
            String status = f[2].trim();
            boolean running = ContainerState.running(null, status);
            rows.add(new Row(name, f[1].trim(), env, version, sha, status, f[3].trim(),
                    running, parseCreatedAt(f[7].trim())));
        }
        return rows;
    }

    static String envFromName(String rest) {
        for (String env : ENVS) {
            if (rest.equals("-" + env) || rest.startsWith("-" + env + "-")) {
                return env;
            }
        }
        return "";
    }

    static String versionFromName(String rest, String env, String sha) {
        String marker = "-" + env + "-";
        int index = rest.indexOf(marker);
        if (index >= 0 && index + marker.length() < rest.length()) {
            return rest.substring(index + marker.length());
        }
        if (sha == null || sha.isBlank()) {
            return "";
        }
        return Hashes.shortSha(sha, 12);
    }

    /** docker ps 的 CreatedAt：{@code 2026-11-20 10:00:00 +0800 CST}（带时区名）。 */
    static long parseCreatedAt(String value) {
        if (value == null || value.length() < 19) {
            return 0;
        }
        try {
            // 先按远端偏移解析（服务器时区可能与本机不同），失败再退回本机时区
            if (value.length() >= 25) {
                return OffsetDateTime.parse(value.substring(0, 25),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"))
                        .toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(value.substring(0, 19),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 一行容器信息（与服务器无关，便于单测）。 */
    record Row(String name, String image, String env, String version, String sha,
               String status, String ports, boolean running, long createdAt) {
    }
}
