package top.rongx.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 环境<b>写</b>工具：生成部署绑定草案、按需补齐中间件。
 *
 * <p>与 {@link EnvQueryTools} 拆开后，生产环境（只读 Toolkit）不会看到这两个工具；
 * 非生产环境仍同时具备读与写。
 *
 * <p>注意：{@code env_mapping_draft} 只产生 DRAFT 记录，<b>不能自己确认</b>，
 * 必须由人在设置页确认后才能用于 {@code deploy_run}。
 */
@Component
public class EnvCommandTools {

    private final EnvBindingPort bindings;
    private final top.rongx.agent.domain.env.EnvProvisioner provisioner;
    private final ObjectMapper json = new ObjectMapper();

    public EnvCommandTools(EnvBindingPort bindings,
                           top.rongx.agent.domain.env.EnvProvisioner provisioner) {
        this.bindings = bindings;
        this.provisioner = provisioner;
    }

    @Tool(name = "env_mapping_draft",
            description = """
                    生成该环境的变量映射草案（写一条 DRAFT 记录，不含任何变量值）。
                    生成后必须让用户在「设置 → 项目环境 → Agent 部署绑定」里人工确认；
                    Agent 自己不能确认，也不能绕过 binding_id 直接 deploy_run。
                    返回：binding_id、缺失变量、中间件告警。""",
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
        StringBuilder sb = new StringBuilder();
        sb.append("bindingId: ").append(binding.id()).append('\n')
                .append("status: ").append(binding.status()).append("（需人工确认后才可用于 deploy_run）\n")
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
        sb.append("next: 请让用户到「设置 → 项目环境」确认该草案，确认后再用 binding_id 触发 deploy_run");
        return sb.toString();
    }

    /**
     * 环境自愈：项目在该环境缺中间件容器时按需创建，并把连接信息写入项目环境覆盖。
     *
     * <p>只作用于本机 Docker 环境；远程服务器环境不会自动创建（返回说明）。
     * 返回值只有容器名 / 变量名，不含任何密码。
     */
    @Tool(name = "env_ensure",
            description = """
                    确保项目在当前环境的中间件容器存在并运行；缺失时按需创建（项目隔离命名），
                    并把地址/端口/账号/密码写入项目环境覆盖；必填的密钥类变量（TOKEN_SECRET 等）
                    缺失时自动生成。用于解决"缺 DB_HOST / REDIS_HOST"这类部署前置失败。
                    可自动放行：dev/test 下无需额外确认单；生产环境拒绝执行。""",
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
