package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.ScenarioCatalog;
import top.rongx.agent.domain.env.ValueState;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 配置解析工具：只读取，不写环境。
 *
 * <p>优先级：项目环境配置（模板 + 项目覆盖）→ 仓库声明（.env/.env.example/compose）
 * → 全量扫描文档。扫描结果只作为候选，不自动生效；缺失或冲突时返回
 * {@code NEED_USER_INPUT}，Agent 必须停下来问用户并等待回答。
 */
@Component
public class ConfigTools {

    private final ConfigResolver resolver;
    private final EnvironmentStore envStore;
    private final ToolAuditRecorder audit;

    public ConfigTools(ConfigResolver resolver, EnvironmentStore envStore,
                       ToolAuditRecorder audit) {
        this.resolver = resolver;
        this.envStore = envStore;
        this.audit = audit;
    }

    @Tool(name = "config_resolve",
            description = """
                    解析某个环境的配置，返回可直接使用的值、缺失项与扫描候选（密钥只显示 ****）。
                    优先级：项目环境配置（设置→项目环境）> 仓库 .env/.env.example/compose > 文档扫描。
                    如果返回 status: NEED_USER_INPUT，说明配置缺失或存在多个冲突候选：
                    必须停止执行，把 missing/candidates/conflicts 摘要原样告诉用户，并等待用户回答要使用哪组值；
                    不要自行猜测，也不要把明文密钥贴进对话。用户确认后再继续部署或生成绑定。""",
            readOnly = true, concurrencySafe = true)
    public String resolve(
            @ToolParam(name = "env", description = "环境：dev / test / prod", required = false) String env,
            @ToolParam(name = "keys", description = "可选，逗号分隔的变量名；不传则返回全部声明/已配置项",
                    required = false) String keys,
            ProjectContext ctx) {
        return audit.call(ctx, "config_resolve",
                java.util.Map.of("env", env == null ? "" : env, "keys", keys == null ? "" : keys),
                () -> resolveChecked(env, keys, ctx));
    }

    private String resolveChecked(String env, String keys, ProjectContext ctx) {
        List<String> requested = keys == null || keys.isBlank()
                ? List.of()
                : Arrays.stream(keys.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        String target = ConfigResolver.normalize(env == null ? ctx.env() : env);
        ConfigResolver.Resolution resolution = resolver.resolve(ctx, target, requested);
        StringBuilder sb = new StringBuilder(resolution.summary()).append('\n');
        if (!resolution.resolved().isEmpty()) {
            sb.append("values:\n");
            resolution.resolved().forEach((key, value) ->
                    sb.append("  ").append(key).append(" = ").append(value)
                            .append(" @ ").append(resolution.resolvedSource().get(key))
                            .append(" [").append(resolution.states()
                                    .getOrDefault(key, ValueState.MISSING)).append("]\n"));
        }
        return sb.toString();
    }

    /**
     * 场景配置词条目录：DeepSeek / 阿里云 OSS / SMTP 邮箱 / 百炼等。
     *
     * <p>只给键名、含义、默认值与"当前环境是否已配置"，不含任何值。
     * 用于把"项目缺哪些第三方服务配置"讲清楚，避免 Agent 自己发明变量名。
     */
    @Tool(name = "config_catalog",
            description = """
                    列出平台内置的场景配置词条（DeepSeek、阿里云 OSS、SMTP 邮箱、通义千问/百炼）。
                    每条包含变量名、含义、是否敏感、是否必填、默认值，以及该环境是否已配置。
                    典型用法：config_resolve 返回缺失项后，先调本工具确认该键属于哪个场景、该填什么，
                    再把"缺哪些键、去哪申请、填什么格式"告诉用户等待补值；不要自己编造变量名。
                    本工具不返回任何配置值。""",
            readOnly = true, concurrencySafe = true)
    public String catalog(
            @ToolParam(name = "scenario", description = "可选：deepseek / aliyun-oss / smtp / dashscope；不传返回全部",
                    required = false) String scenario,
            @ToolParam(name = "env", description = "环境：dev / test / prod；用于标注是否已配置",
                    required = false) String env,
            ProjectContext ctx) {
        return audit.call(ctx, "config_catalog",
                java.util.Map.of("scenario", scenario == null ? "" : scenario,
                        "env", env == null ? "" : env),
                () -> catalogChecked(scenario, env, ctx));
    }

    private String catalogChecked(String scenario, String env, ProjectContext ctx) {
        String target = ConfigResolver.normalize(env == null ? ctx.env() : env);
        Set<String> configured = new LinkedHashSet<>();
        Set<String> placeholders = new LinkedHashSet<>();
        for (EnvVariable v : envStore.envVars(target)) {
            if (v.value() == null || v.value().isBlank()) {
                continue;
            }
            if (ValueState.of(v.key(), v.value()) == ValueState.PLACEHOLDER
                    && !v.secret() && !EnvVariable.KIND_SECRET_REF.equals(v.valueKind())) {
                placeholders.add(v.key());
            } else {
                configured.add(v.key());
            }
        }
        String wanted = scenario == null ? "" : scenario.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder()
                .append("环境: ").append(target).append('\n')
                .append("说明: 以下是平台内置场景词条，值需在「设置 → 环境模板 → 场景配置词条」填写。\n");
        boolean any = false;
        for (ScenarioCatalog.Scenario s : ScenarioCatalog.all()) {
            if (!wanted.isBlank() && !s.id().equals(wanted)) {
                continue;
            }
            any = true;
            sb.append('\n').append(ScenarioCatalog.describe(s));
            for (ScenarioCatalog.Key k : s.keys()) {
                String state = configured.contains(k.key()) ? "已配置"
                        : placeholders.contains(k.key()) ? "疑似占位值（需替换）" : "未配置";
                sb.append("    ").append(k.key()).append(": ").append(state).append('\n');
            }
        }
        if (!any) {
            sb.append("没有匹配的场景：").append(scenario)
                    .append("（可选 deepseek / aliyun-oss / smtp / dashscope）");
        }
        return sb.toString();
    }
}
