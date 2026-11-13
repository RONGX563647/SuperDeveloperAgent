package top.rongx.agent.gateway.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.MiddlewareImages;
import top.rongx.agent.domain.env.ScenarioCatalog;

/**
 * 环境模板文档视图：把「模板参数 + 中间件镜像 + 模板变量 + 场景词条」渲染成一份可编辑 Markdown，
 * 再把编辑后的 Markdown 解析回同一份数据。
 *
 * <p>规则：
 * <ul>
 *   <li>敏感值渲染为 {@code ****}；同步时仍是 {@code ****} 表示保留原值，填新值才会覆盖；</li>
 *   <li>表格里删除某行 = 删除对应中间件 / 变量；新增行 = 新增；</li>
 *   <li>场景词条区用来解释每个键的含义，顺手填的值也生效，但同名键以「模板变量」表为准。</li>
 * </ul>
 */
@Service
public class EnvDocumentService {

    private final EnvironmentStore store;

    public EnvDocumentService(EnvironmentStore store) {
        this.store = store;
    }

    // ---------------------------------------------------------------- 渲染

    public Map<String, Object> document(String env) {
        EnvTemplate t = store.template(env).orElseThrow(() ->
                new IllegalArgumentException("环境不存在：" + env));
        List<EnvResource> resources = store.resources(env);
        List<EnvVariable> vars = store.envVars(env).stream()
                .filter(v -> !"RESOURCE".equals(v.source()))
                .toList();
        Map<String, String> varValues = new LinkedHashMap<>();
        for (EnvVariable v : vars) {
            varValues.put(v.key(), v.value());
        }

        StringBuilder md = new StringBuilder();
        md.append("# 环境模板 · ").append(envLabel(env)).append("（").append(env).append("）\n\n");
        md.append("> 编辑后点「同步到平台」即可写回。`****` 表示保留原密码 / 原值；")
                .append("删除表格行会删除对应中间件或变量，新增行会新建。\n")
                .append("> 场景词条区用于说明与便捷填值；同名变量以「模板变量」表为准。\n\n");

        md.append("## 概览\n\n");
        md.append("- 运行形态: ").append(t.runtimeMode()).append('\n');
        md.append("- 自治级别: ").append(t.level().name()).append('\n');
        md.append("- 命名空间: ").append(t.namespace()).append('\n');
        md.append("- 部署网络: ").append(nullToEmpty(t.network())).append('\n');
        md.append("- 默认部署目标: ").append(t.defaultTarget()).append('\n');
        md.append("- 默认端口: ").append(t.defaultPort()).append('\n');
        md.append("- 健康路径: ").append(nullToEmpty(t.healthPath())).append('\n');
        md.append("- 绑定服务器: ").append(nullToEmpty(t.serverId())).append("\n\n");

        md.append("## 中间件镜像\n\n");
        md.append("| 名称 | 镜像 | 主机 | 端口 | 库名 | 用户名 | 密码 | 网络 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (EnvResource r : resources) {
            md.append("| ").append(cell(r.name()))
                    .append(" | ").append(cell(r.displayImage()))
                    .append(" | ").append(cell(r.host()))
                    .append(" | ").append(r.port())
                    .append(" | ").append(cell(r.databaseName()))
                    .append(" | ").append(cell(r.username()))
                    .append(" | ").append(cell(r.passwordMasked()))
                    .append(" | ").append(cell(r.network()))
                    .append(" |\n");
        }
        md.append('\n');

        md.append("## 模板变量\n\n");
        md.append("| 变量名 | 值 | 敏感 | 来源 |\n");
        md.append("| --- | --- | --- | --- |\n");
        for (EnvVariable v : vars) {
            md.append("| ").append(cell(v.key()))
                    .append(" | ").append(cell(v.value()))
                    .append(" | ").append(v.secret() ? "是" : "否")
                    .append(" | ").append(cell(sourceLabel(v.source())))
                    .append(" |\n");
        }
        md.append('\n');

        md.append("## 场景配置词条\n\n");
        for (ScenarioCatalog.Scenario s : ScenarioCatalog.all()) {
            md.append("### ").append(s.name()).append("\n\n");
            md.append(s.summary()).append("\n\n");
            md.append("| 变量名 | 说明 | 必填 | 敏感 | 当前值 |\n");
            md.append("| --- | --- | --- | --- | --- |\n");
            for (ScenarioCatalog.Key k : s.keys()) {
                String current = varValues.get(k.key());
                md.append("| ").append(cell(k.key()))
                        .append(" | ").append(cell(k.description()))
                        .append(" | ").append(k.required() ? "是" : "否")
                        .append(" | ").append(k.secret() ? "是" : "否")
                        .append(" | ").append(cell(current == null ? "未配置" : current))
                        .append(" |\n");
            }
            md.append('\n');
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", env);
        out.put("markdown", md.toString());
        out.put("updatedAt", System.currentTimeMillis());
        return out;
    }

    // ---------------------------------------------------------------- 同步

    /** 把编辑后的 Markdown 写回模板 / 中间件 / 变量；返回各段变更计数。 */
    public Map<String, Object> apply(String env, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("文档内容为空");
        }
        Parsed parsed = parse(markdown);

        int templateChanged = applyTemplate(env, parsed.overview);
        Map<String, Integer> resources = applyResources(env, parsed.resources, parsed.sawResourcesTable);
        // 文档只维护人工变量；中间件连接信息由资源实时派生，不落模板变量。
        Map<String, Integer> vars = applyVars(env, parsed.vars, parsed.sawVarsTable);
        // 场景词条区填的值只补「还没有」的键，不覆盖模板变量表里更明确的值。
        int scenarioFilled = fillScenarioValues(env, parsed.scenarioValues);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("env", env);
        out.put("templateChanged", templateChanged > 0);
        out.put("resources", resources);
        out.put("vars", vars);
        out.put("scenarioFilled", scenarioFilled);
        return out;
    }

    private int applyTemplate(String env, Map<String, String> overview) {
        if (overview.isEmpty()) {
            return 0;
        }
        EnvTemplate current = store.template(env).orElseThrow(() ->
                new IllegalArgumentException("环境不存在：" + env));
        String runtimeMode = value(overview, "运行形态", current.runtimeMode());
        String autonomy = value(overview, "自治级别", current.autonomy());
        String namespace = value(overview, "命名空间", current.namespace());
        String network = value(overview, "部署网络", nullToEmpty(current.network()));
        String defaultTarget = value(overview, "默认部署目标", current.defaultTarget());
        String healthPath = value(overview, "健康路径", nullToEmpty(current.healthPath()));
        int defaultPort = parseInt(value(overview, "默认端口", String.valueOf(current.defaultPort())),
                current.defaultPort());
        String serverId = value(overview, "绑定服务器", nullToEmpty(current.serverId()));
        EnvTemplate next = new EnvTemplate(env,
                network.isBlank() ? null : network.trim(),
                defaultTarget.trim(),
                defaultPort,
                healthPath.isBlank() ? null : healthPath.trim(),
                runtimeMode.trim(),
                namespace.trim(),
                serverId.isBlank() ? null : serverId.trim(),
                autonomy.trim(),
                current.updatedAt());
        if (next.equals(current)) {
            return 0;
        }
        store.saveTemplate(new EnvTemplate(env, next.network(), next.defaultTarget(),
                next.defaultPort(), next.healthPath(), next.runtimeMode(), next.namespace(),
                next.serverId(), next.autonomy(), System.currentTimeMillis()));
        return 1;
    }

    private Map<String, Integer> applyResources(String env, List<Map<String, String>> rows,
                                                boolean sawTable) {
        Map<String, EnvResource> existing = new LinkedHashMap<>();
        for (EnvResource r : store.resources(env)) {
            existing.put(r.name(), r);
        }
        int created = 0;
        int updated = 0;
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (Map<String, String> row : rows) {
            String name = row.getOrDefault("名称", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            kept.add(name);
            EnvResource old = existing.get(name);
            String image = row.getOrDefault("镜像", "").trim();
            String kind = inferKind(image, old == null ? null : old.kind());
            int port = parseInt(row.getOrDefault("端口", ""), old == null ? 0 : old.port());
            String password = row.getOrDefault("密码", "").trim();
            String nextPassword = old == null
                    ? (isMask(password) || password.isEmpty() ? null : password)
                    : (isMask(password) || password.isEmpty() ? old.password() : password);
            String network = row.getOrDefault("网络", "").trim();
            if (network.isEmpty()) {
                network = old == null ? null : old.network();
            }
            EnvResource saved = store.saveResource(new EnvResource(
                    old == null ? null : old.id(), env, kind, name,
                    blankTo(row.get("主机"), old == null ? "127.0.0.1" : old.host()),
                    port,
                    old == null ? 0 : old.containerPort(),
                    old == null ? null : old.internalHost(),
                    blankToNull(row.get("库名")),
                    blankToNull(row.get("用户名")),
                    nextPassword,
                    network,
                    old == null ? null : old.serverId(),
                    old == null ? "MANUAL" : old.source(),
                    old != null && old.running(),
                    old == null ? 0 : old.lastCheckAt(),
                    old == null ? null : old.lastError(),
                    image.isEmpty() ? null : image));
            if (old == null) {
                created += 1;
            } else if (!saved.equals(old)) {
                updated += 1;
            }
        }
        int deleted = 0;
        if (sawTable) {
            for (EnvResource r : existing.values()) {
                if (!kept.contains(r.name())) {
                    store.deleteResource(r.id());
                    deleted += 1;
                }
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("updated", updated);
        out.put("deleted", deleted);
        return out;
    }

    private Map<String, Integer> applyVars(String env, List<Map<String, String>> rows, boolean sawTable) {
        Map<String, EnvVariable> existing = new LinkedHashMap<>();
        for (EnvVariable v : store.envVars(env)) {
            if (!"RESOURCE".equals(v.source())) {
                existing.put(v.key(), v);
            }
        }
        int created = 0;
        int updated = 0;
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (Map<String, String> row : rows) {
            String key = row.getOrDefault("变量名", "").trim();
            if (key.isEmpty()) {
                continue;
            }
            kept.add(key);
            EnvVariable old = existing.get(key);
            String value = row.getOrDefault("值", "").trim();
            boolean mask = value.isEmpty() || isMask(value);
            if (mask && old == null) {
                continue;
            }
            boolean secret = parseBool(row.get("敏感"), old != null && old.secret());
            if (mask && old != null && secret == old.secret()) {
                continue;
            }
            if (!mask && old != null && !old.secret() && value.equals(old.value())) {
                continue;
            }
            store.saveEnvVar(env, key, mask ? null : value, secret,
                    old == null ? "TEMPLATE" : old.source());
            if (old == null) {
                created += 1;
            } else {
                updated += 1;
            }
        }
        int deleted = 0;
        if (sawTable) {
            for (EnvVariable v : existing.values()) {
                if (!kept.contains(v.key())) {
                    store.deleteEnvVar(env, v.key());
                    deleted += 1;
                }
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("updated", updated);
        out.put("deleted", deleted);
        return out;
    }

    /** 场景词条区填的值：只补缺失键，避免和模板变量表冲突。 */
    private int fillScenarioValues(String env, Map<String, String> values) {
        Map<String, EnvVariable> existing = new LinkedHashMap<>();
        for (EnvVariable v : store.envVars(env)) {
            if (!"RESOURCE".equals(v.source())) {
                existing.put(v.key(), v);
            }
        }
        int filled = 0;
        for (ScenarioCatalog.Scenario s : ScenarioCatalog.all()) {
            for (ScenarioCatalog.Key k : s.keys()) {
                String value = values.get(k.key());
                if (value == null || value.isBlank() || "未配置".equals(value.trim())
                        || isMask(value.trim()) || existing.containsKey(k.key())) {
                    continue;
                }
                store.saveEnvVar(env, k.key(), value.trim(), k.secret(), "TEMPLATE");
                filled += 1;
            }
        }
        return filled;
    }

    // ---------------------------------------------------------------- 解析

    private record Parsed(Map<String, String> overview,
                          List<Map<String, String>> resources,
                          boolean sawResourcesTable,
                          List<Map<String, String>> vars,
                          boolean sawVarsTable,
                          Map<String, String> scenarioValues) {
    }

    private Parsed parse(String markdown) {
        Map<String, String> overview = new LinkedHashMap<>();
        List<Map<String, String>> resources = new ArrayList<>();
        List<Map<String, String>> vars = new ArrayList<>();
        Map<String, String> scenarioValues = new LinkedHashMap<>();
        boolean sawResources = false;
        boolean sawVars = false;
        String section = "";
        List<String> header = null;
        for (String raw : markdown.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                section = line.substring(3).trim();
                header = null;
                continue;
            }
            if (line.startsWith("### ")) {
                header = null;
                continue;
            }
            if (line.startsWith("- ") && section.startsWith("概览")) {
                String body = line.substring(2);
                int idx = body.indexOf(':');
                int cn = body.indexOf('：');
                if (idx < 0 || (cn >= 0 && cn < idx)) {
                    idx = cn;
                }
                if (idx > 0) {
                    overview.put(body.substring(0, idx).trim(), body.substring(idx + 1).trim());
                }
                continue;
            }
            if (!line.startsWith("|")) {
                header = null;
                continue;
            }
            List<String> cells = cells(line);
            if (cells.stream().allMatch(EnvDocumentService::isSeparator)) {
                continue;
            }
            if (header == null) {
                header = cells;
                if (section.startsWith("中间件")) {
                    sawResources = true;
                } else if (section.startsWith("模板变量")) {
                    sawVars = true;
                }
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < cells.size(); i++) {
                row.put(header.get(i), cells.get(i));
            }
            if (section.startsWith("中间件")) {
                resources.add(row);
            } else if (section.startsWith("模板变量")) {
                vars.add(row);
            } else if (section.startsWith("场景配置词条")) {
                String key = row.getOrDefault("变量名", "").trim();
                String value = row.getOrDefault("当前值", "").trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    scenarioValues.put(key, value);
                }
            }
        }
        return new Parsed(overview, resources, sawResources, vars, sawVars, scenarioValues);
    }

    private static List<String> cells(String line) {
        String body = line.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String c : body.split("(?<!\\\\)\\|", -1)) {
            out.add(c.trim().replace("\\|", "|"));
        }
        return out;
    }

    private static boolean isSeparator(String cell) {
        return cell.matches(":?-{3,}:?");
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ").trim();
    }

    private static boolean isMask(String value) {
        return value != null && value.startsWith("****");
    }

    private static boolean parseBool(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim();
        if ("是".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)) {
            return true;
        }
        if ("否".equals(v) || "false".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)) {
            return false;
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String value(Map<String, String> map, String key, String fallback) {
        String v = map.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 新增行的类型从镜像名推断（mysql:8.0 → mysql），推断不出保留原类型/other。 */
    private static String inferKind(String image, String fallback) {
        if (image == null || image.isBlank()) {
            return fallback == null ? "other" : fallback;
        }
        String base = image.toLowerCase(Locale.ROOT);
        for (String kind : MiddlewareImages.defaultsByKind().keySet()) {
            if (base.startsWith(kind + ":") || base.startsWith(kind + "/")
                    || base.contains("/" + kind + ":")) {
                return kind;
            }
        }
        if (base.contains("postgres")) {
            return "postgres";
        }
        if (base.contains("maria")) {
            return "mariadb";
        }
        return fallback == null ? "other" : fallback;
    }

    private static String sourceLabel(String source) {
        if ("RESOURCE".equals(source)) {
            return "中间件自动";
        }
        if ("OVERRIDE".equals(source)) {
            return "项目覆盖";
        }
        return "模板";
    }

    private static String envLabel(String env) {
        return switch (env) {
            case "dev" -> "开发";
            case "prod" -> "生产";
            default -> "测试";
        };
    }
}
