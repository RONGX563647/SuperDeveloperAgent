package top.rongx.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.domain.env.EnvBindingWire;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 环境<b>写</b>工具：生成部署绑定草案、按需补齐中间件、维护项目环境变量。
 *
 * <p>与 {@link EnvQueryTools} 拆开后，生产环境（只读 Toolkit）不会看到这些工具；
 * 非生产环境仍同时具备读与写。
 *
 * <p>注意：{@code env_mapping_draft} 默认只产生 DRAFT 记录；但当变量齐全、没有缺失项时
 * 平台会直接自动确认（自愈/续跑不再被一张无事可做的确认卡挡住）。存在缺失项时仍需人工补值，
 * 并由人在对话内的绑定确认卡确认后才能用于 {@code deploy_run}。变量写操作会改变有效环境哈希，
 * 因此旧绑定自动失效，需要重新生成草案。
 */
@Component
public class EnvCommandTools {

    private final EnvBindingPort bindings;
    private final top.rongx.agent.domain.env.EnvProvisioner provisioner;
    private final EnvironmentStore envStore;
    private final ObjectMapper json = new ObjectMapper();

    public EnvCommandTools(EnvBindingPort bindings,
                           top.rongx.agent.domain.env.EnvProvisioner provisioner,
                           EnvironmentStore envStore) {
        this.bindings = bindings;
        this.provisioner = provisioner;
        this.envStore = envStore;
    }

    @Tool(name = "env_mapping_draft", description = "Draft an env binding for confirmation.",
            readOnly = false, concurrencySafe = false)
    public String mappingDraft(@ToolParam(name = "env", description = "环境：dev / test") String env,
                               ProjectContext ctx) {
        String target = normalize(env);
        if ("prod".equals(target)) {
            return "err: 生产环境不生成 Agent 草案；请走测试环境 + 部署面板确认发布";
        }
        EnvBinding binding = bindings.draft(ctx.projectId(), target, "agent");
        Map<?, ?> mapping = parse(binding.mappingJson());
        List<?> keys = mapping.get("keys") instanceof List<?> list ? list : List.of();
        List<?> missing = mapping.get("missing") instanceof List<?> list ? list : List.of();
        List<?> warnings = mapping.get("warnings") instanceof List<?> list ? list : List.of();
        // 变量齐全 = 人没有任何可补的：直接平台确认，避免"无事可做还要点一下"的确认卡。
        // 自愈续跑依赖这个绑定，卡在人工确认上等于修复链断了。
        EnvBinding confirmed = missing.isEmpty() ? confirm(binding) : null;
        boolean autoConfirmed = confirmed != null;
        if (autoConfirmed) {
            binding = confirmed;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(EnvBindingWire.line(binding.id())).append('\n')
                .append("status: ").append(binding.status())
                .append(autoConfirmed ? "（平台自动确认：变量齐全，无需人工补值）"
                        : "（需人工确认后才可用于 deploy_run）").append('\n')
                .append("keys: ").append(keys.size()).append('\n')
                .append("missing: ").append(missing.isEmpty() ? "无" : String.join(", ", missing.stream()
                        .map(String::valueOf).toList())).append('\n');
        if (!warnings.isEmpty()) {
            sb.append("warnings:\n");
            for (Object w : warnings) {
                Map<?, ?> warning = w instanceof Map<?, ?> m ? m : Map.of();
                sb.append("  - ").append(warning.get("code")).append(' ')
                        .append(warning.get("message")).append('\n');
            }
        }
        sb.append("next: ").append(autoConfirmed
                ? "已自动确认，直接用该 binding_id 触发 deploy_run"
                : "环境绑定确认卡已在对话中展开，请用户本人点击确认；确认后再用该 binding_id 触发 deploy_run");
        return sb.toString();
    }

    /** 自动确认；实现不支持或确认后仍是 DRAFT 时返回 null，由人点确认卡。 */
    private EnvBinding confirm(EnvBinding binding) {
        try {
            EnvBinding confirmed = bindings.confirm(binding.id(), "platform-autofix");
            return "CONFIRMED".equals(confirmed.status()) ? confirmed : null;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /**
     * 环境自愈：项目在该环境缺中间件容器时按需创建，并把连接信息写入项目环境覆盖。
     *
     * <p>只作用于本机 Docker 环境；远程服务器环境不会自动创建（返回说明）。
     * 返回值只有容器名 / 变量名，不含任何密码。
     */
    @Tool(name = "env_ensure", description = "Ensure project middleware is running.",
            readOnly = false, concurrencySafe = false)
    public String ensure(@ToolParam(name = "env", description = "环境：dev / test") String env,
                         ProjectContext ctx) {
        String target = normalize(env);
        if ("prod".equals(target)) {
            return "err: 生产环境不自动创建容器；请由运维在服务器上确认连接信息";
        }
        String kindHint = "按项目必填变量推断需要的中间件";
        var result = provisioner.ensure(ctx.projectId(), target, bindings.requiredKeys(ctx.projectId()));
        StringBuilder sb = new StringBuilder();
        sb.append("ok: ").append(result.ok()).append('\n')
                .append("message: ").append(result.message()).append('\n');
        if (!result.injected().isEmpty()) {
            sb.append("injected: ").append(String.join(", ", result.injected()))
                    .append("（来自项目 .env，值不回显）\n");
        }
        if (!result.created().isEmpty()) {
            sb.append("created: ").append(String.join(", ", result.created())).append('\n');
        }
        if (!result.started().isEmpty()) {
            sb.append("started: ").append(String.join(", ", result.started())).append('\n');
        }
        if (!result.generated().isEmpty()) {
            sb.append("generated: ").append(String.join(", ", result.generated()))
                    .append("（值已写入项目环境覆盖，不回显）\n");
        }
        if (!result.failed().isEmpty()) {
            sb.append("failed:\n");
            result.failed().forEach(f -> sb.append("  - ").append(f).append('\n'));
        }
        sb.append("note: ").append(kindHint).append("；连接信息只写入项目覆盖，不覆盖人工模板变量");
        return sb.toString();
    }

    /**
     * 新增/修改项目环境变量（写入项目覆盖层，环境模板不变）。
     *
     * <p>这是部署前最常见的配置补齐：端口覆盖（SERVER_PORT）、工作区目录、
     * 连接串等。值落库加密，工具结果不回显明文。
     */
    @Tool(name = "env_var_set", description = "Set a project env variable override.",
            readOnly = false, concurrencySafe = false)
    public String setVar(@ToolParam(name = "env", description = "环境：dev / test") String env,
                         @ToolParam(name = "key", description = "变量名，例如 SERVER_PORT") String key,
                         @ToolParam(name = "value", description = "变量值（不会回显）") String value,
                         @ToolParam(name = "secret", required = false,
                                 description = "是否敏感：true/false；缺省按变量名推断")
                         String secret,
                         ProjectContext ctx) {
        String target = normalize(env);
        if ("prod".equals(target)) {
            return "err: 生产环境变量不允许由 Agent 写入；请在「设置 → 项目环境」由运维维护";
        }
        String name = key == null ? "" : key.trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "err: 变量名非法（需字母/下划线开头，仅含字母、数字、下划线）：" + key;
        }
        if (value == null || value.isEmpty()) {
            return "err: value 不能为空；如需删除变量请调用 env_var_unset";
        }
        boolean sensitive = secret == null || secret.isBlank()
                ? looksSecret(name) : Boolean.parseBoolean(secret.trim());
        EnvVariable saved = envStore.saveOverride(ctx.projectId(), target, name, value, sensitive);
        return """
                saved: %s
                env: %s
                secret: %s
                source: 项目覆盖（环境模板未改动）
                next: 有效环境已变化 → 重新调用 env_mapping_draft，并请人在对话确认卡中确认新绑定后再 deploy_run
                evidence: project:%s/env:%s""".formatted(saved.key(), target, sensitive,
                ctx.projectId(), target);
    }

    /** 删除项目环境变量的覆盖项（回到环境模板/默认值）。 */
    @Tool(name = "env_var_unset", description = "Remove a project env variable override.",
            readOnly = false, concurrencySafe = false)
    public String unsetVar(@ToolParam(name = "env", description = "环境：dev / test") String env,
                           @ToolParam(name = "key", description = "变量名") String key,
                           ProjectContext ctx) {
        String target = normalize(env);
        if ("prod".equals(target)) {
            return "err: 生产环境变量不允许由 Agent 删除；请在「设置 → 项目环境」由运维维护";
        }
        String name = key == null ? "" : key.trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "err: 变量名非法：" + key;
        }
        envStore.deleteOverride(ctx.projectId(), target, name);
        return """
                deleted: %s
                env: %s
                next: 重新调用 env_mapping_draft 并请人确认新绑定
                evidence: project:%s/env:%s""".formatted(name, target, ctx.projectId(), target);
    }

    /** 变量名是否像敏感信息（与设置页的掩码规则保持一致）。 */
    private static boolean looksSecret(String key) {
        String k = key.toUpperCase(java.util.Locale.ROOT);
        return k.contains("PASSWORD") || k.contains("SECRET") || k.contains("TOKEN")
                || k.endsWith("_KEY");
    }

    private Map<?, ?> parse(String mappingJson) {
        try {
            return json.readValue(mappingJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String normalize(String env) {
        String value = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        return List.of("dev", "test", "prod").contains(value) ? value : "test";
    }
}
