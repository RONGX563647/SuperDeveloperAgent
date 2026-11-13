package top.rongx.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 环境<b>只读</b>工具：变量数量/来源、中间件健康、变量指向校验。
 *
 * <p>与 {@link EnvCommandTools} 拆开的原因：生产环境的会话只装配只读 Toolkit，
 * 原先整类（含写工具）被排除，导致生产连 {@code env_summary} 都看不到。
 * 拆分后"只读能力"可以安全地暴露到生产，写工具仍然只出现在非生产工具面。
 *
 * <p>永远不返回变量明文值，只返回变量名 / 来源 / 健康状态。
 */
@Component
public class EnvQueryTools {

    private final EnvironmentStore envStore;

    public EnvQueryTools(EnvironmentStore envStore) {
        this.envStore = envStore;
    }

    @Tool(name = "env_summary",
            description = """
                    查看某环境部署时会注入的变量数量、网络、端口与中间件健康（只读）。
                    中间件按「本项目专属 / 环境共享」拆分：healthy 只统计本项目专属中间件，
                    环境共享的实例（其它项目或公共中间件）单独列出、不计入本项目健康。
                    不返回变量值；用于判断"缺变量/中间件没起"这类前置问题。""",
            readOnly = true, concurrencySafe = true)
    public String summary(@ToolParam(name = "env", description = "环境：dev / test / prod") String env,
                          ProjectContext ctx) {
        String target = normalize(env);
        EffectiveEnvironment effective = envStore.effective(ctx.projectId(), target);
        long secret = effective.vars().stream().filter(v -> v.secret()).count();
        String slug = slug(ctx.projectId());
        List<EnvResource> own = new ArrayList<>();
        List<EnvResource> shared = new ArrayList<>();
        // 健康只看"被项目变量引用"的资源：环境里其它项目的容器不该拉低本项目健康。
        for (EnvResource r : effective.resources()) {
            if (!top.rongx.agent.domain.env.StackAdapter.isReferenced(r, effective.values())) {
                continue;
            }
            (ownedBy(r, slug) ? own : shared).add(r);
        }
        long healthy = own.stream().filter(EnvResource::running).count();
        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(ctx.projectId()).append('\n')
                .append("env: ").append(target).append('\n')
                .append("variables: ").append(effective.vars().size())
                .append("（模板 ").append(effective.templateCount())
                .append(" / 覆盖 ").append(effective.overrideCount())
                .append(" / 敏感 ").append(secret).append("）\n")
                .append("network: ").append(effective.network() == null || effective.network().isBlank()
                        ? "默认" : effective.network()).append('\n')
                .append("port: ").append(effective.defaultPort()).append('\n')
                .append("healthPath: ").append(effective.healthPath()).append('\n')
                .append("middleware: ").append(effective.resources().size())
                .append("（本项目 ").append(own.size())
                .append(" / 环境共享 ").append(shared.size()).append("）\n");
        if (own.isEmpty()) {
            sb.append("own: （本项目在该环境没有专属中间件；若应用仍需数据库/缓存，")
                    .append("请用 env_var_probe 确认变量指向哪个实例）\n");
        } else {
            sb.append("own:\n");
            own.forEach(r -> sb.append("  ").append(middlewareLine(r)));
        }
        if (!shared.isEmpty()) {
            sb.append("shared: ").append(shared.size())
                    .append(" 个属于环境共享/其它项目，不计入本项目健康\n");
            shared.stream().limit(5).forEach(r -> sb.append("  ").append(middlewareLine(r)));
            if (shared.size() > 5) {
                sb.append("  …（其余 ").append(shared.size() - 5).append(" 个省略）\n");
            }
        }
        sb.append("healthy: ").append(healthy).append('/').append(own.size()).append("（本项目）\n")
                .append("evidence: project:").append(ctx.projectId()).append("/env:").append(target);
        return sb.toString();
    }

    /**
     * 配置指向校验：只回答"变量指向的 host:port 属于谁"，<b>永不返回变量值</b>。
     *
     * <p>典型误配是"DB_HOST 指向了别的项目/公共实例"——中间件全绿但连错库。
     * 这里把指向解析出来，与本环境登记的中间件比对，给出 project / shared / external 归属。
     */
    @Tool(name = "env_var_probe",
            description = """
                    校验环境变量指向（只读，不返回值）：给定变量名，回答它指向的 host:port
                    是否属于「本项目中间件」/「环境共享实例」/「外部地址」。
                    用于排查"数据库连错实例""redis 指向别的项目"这类配置问题。
                    支持 jdbc: / redis:// / amqp:// / http(s):// 以及 host:port、纯主机名写法。""",
            readOnly = true, concurrencySafe = true)
    public String varProbe(@ToolParam(name = "env", description = "环境：dev / test / prod") String env,
                           @ToolParam(name = "keys",
                                   description = "要校验的变量名，逗号分隔，例如 DB_HOST,REDIS_HOST")
                           String keys,
                           ProjectContext ctx) {
        String target = normalize(env);
        if (keys == null || keys.isBlank()) {
            return "err: keys 不能为空（示例：DB_HOST,REDIS_HOST）";
        }
        EffectiveEnvironment effective = envStore.effective(ctx.projectId(), target);
        String slug = slug(ctx.projectId());
        Map<String, String> values = effective.values();
        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(ctx.projectId()).append('\n')
                .append("env: ").append(target).append('\n');
        int checked = 0;
        int projectOwned = 0;
        sb.append("---\n");
        for (String raw : keys.split(",")) {
            String key = raw.trim();
            if (key.isEmpty()) {
                continue;
            }
            checked++;
            sb.append("key: ").append(key).append('\n');
            if (!values.containsKey(key)) {
                sb.append("  missing: 该环境没有这个变量（用 env_summary 看变量数量，")
                        .append("或在设置页补模板/项目覆盖）\n");
                continue;
            }
            Address addr = addressOf(values.get(key));
            if (addr == null) {
                sb.append("  addr: 无法解析为 host:port（值不回显；可能是开关/路径/密钥）\n");
                continue;
            }
            if (addr.port() <= 0) {
                // 只给了主机名（常见：DB_HOST=agent-dev-xxx-mysql）：用同前缀的 _PORT 补端口
                int sibling = siblingPort(key, values);
                if (sibling > 0) {
                    addr = new Address(addr.host(), sibling);
                }
            }
            sb.append("  addr: ").append(addr.host());
            if (addr.port() > 0) {
                sb.append(':').append(addr.port());
            }
            sb.append('\n');
            List<EnvResource> matched = matches(effective.resources(), addr);
            if (matched.size() > 1) {
                // 仍多义时按变量名的中间件类型收窄（DB_→mysql、REDIS_→redis…）
                List<EnvResource> byKind = filterByKindHint(matched, key);
                if (!byKind.isEmpty()) {
                    matched = byKind;
                }
            }
            if (matched.isEmpty()) {
                sb.append("  owner: external（本环境没有登记该地址对应的中间件）\n");
                continue;
            }
            if (matched.size() > 1) {
                boolean allOwn = matched.stream().allMatch(r -> ownedBy(r, slug));
                boolean anyOwn = matched.stream().anyMatch(r -> ownedBy(r, slug));
                if (!allOwn && anyOwn) {
                    sb.append("  owner: ambiguous（同时匹配本项目与共享实例：")
                            .append(names(matched)).append("）\n");
                    continue;
                }
                if (allOwn) {
                    projectOwned++;
                }
                sb.append("  owner: ").append(allOwn
                                ? "project（本项目中间件：" + names(matched) + "）"
                                : "shared（环境共享/其它项目实例：" + names(matched) + "）")
                        .append('\n');
                continue;
            }
            EnvResource r = matched.get(0);
            boolean own = ownedBy(r, slug);
            if (own) {
                projectOwned++;
            }
            sb.append("  owner: ").append(own ? "project（本项目中间件）" : "shared（环境共享/其它项目实例）")
                    .append('\n')
                    .append("  resource: ").append(r.name()).append("  ").append(r.kind())
                    .append("  ").append(r.host()).append(':').append(r.port())
                    .append("  ").append(r.status()).append('\n');
            String container = r.internalHost() == null || r.internalHost().isBlank()
                    ? r.name() : r.internalHost();
            sb.append("  container: ").append(container).append('\n');
        }
        sb.append("---\n")
                .append("ok: ").append(projectOwned).append('/').append(checked)
                .append(" 指向本项目中间件\n")
                .append("note: 只回指向关系，不返回任何变量值\n")
                .append("evidence: project:").append(ctx.projectId()).append("/env:").append(target);
        return sb.toString();
    }

    private static String normalize(String env) {
        String value = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        return List.of("dev", "test", "prod").contains(value) ? value : "test";
    }

    // ---------------------------------------------------------------- 指向校验

    /** 从变量值里解析出的地址（绝不回显原值）。 */
    private record Address(String host, int port) {
    }

    /** 带 scheme 的地址：jdbc:mysql://host:3306/db、redis://host:6379/0、amqp://user:pass@host/。 */
    private static final Pattern URL_ADDR = Pattern.compile(
            "[a-z][a-z0-9+.-]*://(?:[^@/\\s]*@)?([^/:?;#\\s]+)(?::(\\d+))?",
            Pattern.CASE_INSENSITIVE);
    /** 裸地址：host:port。 */
    private static final Pattern HOST_PORT_ADDR = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+):(\\d+)\\s*$");
    /** 纯主机名（应用侧会补默认端口）。 */
    private static final Pattern HOST_ONLY_ADDR = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]{2,})\\s*$");

    private static Address addressOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher url = URL_ADDR.matcher(value);
        if (url.find()) {
            return new Address(url.group(1), url.group(2) == null ? 0 : parseInt(url.group(2)));
        }
        String trimmed = value.trim();
        Matcher hp = HOST_PORT_ADDR.matcher(trimmed);
        if (hp.matches()) {
            return new Address(hp.group(1), parseInt(hp.group(2)));
        }
        Matcher hostOnly = HOST_ONLY_ADDR.matcher(trimmed);
        if (hostOnly.matches()) {
            return new Address(hostOnly.group(1), 0);
        }
        return null;
    }

    /** DB_HOST → 同前缀 DB_PORT 的值（只给主机名时补端口）。 */
    private static int siblingPort(String key, Map<String, String> values) {
        String prefix = key.endsWith("_HOST") ? key.substring(0, key.length() - "_HOST".length()) : null;
        if (prefix == null) {
            return 0;
        }
        return parseInt(values.get(prefix + "_PORT"));
    }

    /** 变量名 → 期望的中间件类型（用于把"127.0.0.1 + 多实例"收窄到正确类型）。 */
    private static String kindHint(String key) {
        String k = key.toUpperCase(Locale.ROOT);
        if (k.contains("REDIS")) {
            return "redis";
        }
        if (k.contains("RABBIT") || k.contains("AMQP")) {
            return "rabbitmq";
        }
        if (k.contains("MINIO") || k.contains("S3_")) {
            return "minio";
        }
        if (k.contains("MONGO")) {
            return "mongo";
        }
        if (k.contains("PG") || k.contains("POSTGRES")) {
            return "postgres";
        }
        if (k.contains("DB_") || k.contains("MYSQL") || k.contains("MARIADB")) {
            return "mysql";
        }
        return null;
    }

    private static List<EnvResource> filterByKindHint(List<EnvResource> resources, String key) {
        String hint = kindHint(key);
        if (hint == null) {
            return List.of();
        }
        return resources.stream()
                .filter(r -> r.kind() != null && r.kind().equalsIgnoreCase(hint))
                .toList();
    }

    private static String names(List<EnvResource> resources) {
        return resources.stream().map(EnvResource::name).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static List<EnvResource> matches(List<EnvResource> resources, Address addr) {
        List<EnvResource> hit = new ArrayList<>();
        for (EnvResource r : resources) {
            if (!sameHost(r, addr.host())) {
                continue;
            }
            if (addr.port() <= 0 || addr.port() == r.port() || addr.port() == r.containerPort()) {
                hit.add(r);
            }
        }
        return hit;
    }

    private static boolean sameHost(EnvResource r, String host) {
        return eq(r.host(), host) || eq(r.internalHost(), host) || eq(r.name(), host);
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 资源是否属于本项目：名字/内部主机名里含项目 slug 段（中间件容器名 <ns>-<slug>-<kind>）。 */
    private static boolean ownedBy(EnvResource r, String slug) {
        return hasSegment(r.name(), slug) || hasSegment(r.internalHost(), slug);
    }

    private static boolean hasSegment(String raw, String slug) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String part : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part.equals(slug)) {
                return true;
            }
        }
        return false;
    }

    private static String slug(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        while (value.startsWith("-") || value.startsWith(".")) {
            value = value.substring(1);
        }
        return value;
    }

    private static String middlewareLine(EnvResource r) {
        return "- " + r.name() + " " + r.kind() + " " + r.host() + ':' + r.port()
                + " → " + (r.lastCheckAt() == 0 ? "未检查" : r.running() ? "正常" : "异常")
                + "\n";
    }
}
