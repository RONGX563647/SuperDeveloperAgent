package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
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

    public ConfigTools(ConfigResolver resolver) {
        this.resolver = resolver;
    }

    @Tool(name = "config_resolve", description = "Resolve env config; secrets are masked.",
            readOnly = true, concurrencySafe = true)
    public String resolve(
            @ToolParam(name = "env", description = "环境：dev / test / prod", required = false) String env,
            @ToolParam(name = "keys", description = "可选，逗号分隔的变量名；不传则返回全部声明/已配置项",
                    required = false) String keys,
            ProjectContext ctx) {
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
                            .append(" @ ").append(resolution.resolvedSource().get(key)).append('\n'));
        }
        return sb.toString();
    }
}
