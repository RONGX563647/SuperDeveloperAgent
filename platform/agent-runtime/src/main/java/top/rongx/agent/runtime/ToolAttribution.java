package top.rongx.agent.runtime;

import java.util.List;
import java.util.Map;

/**
 * 本轮工具面归因：模型这次看到的 schema 由哪些插件贡献、各占多少字符。
 *
 * <p>对应验收补充点「本轮哪些插件/技能被加载、各占多少 token」。工具 schema 是每轮常驻
 * prompt 的 token 大头，{@code schemaChars} 用 name+description 字符数作确定性代理
 * （英文约 4 字符/token，与 {@code TokenBaselineTest} 同口径）；技能正文由 {@code skill_load}
 * 按需返回、不进常驻 schema，故不在此列，其加载在工具链日志中体现。
 *
 * <p>纯数据 record（无框架依赖），由 {@link ProgressiveToolMiddleware} 在裁剪后写入
 * {@code RuntimeContext}，由 {@link TokenUsageRecorder} 在模型调用结束时读出落库。
 *
 * @param activatedPlugins 本轮处于激活态的插件名（已剔除路由标记）
 * @param schemaChars      贡献者 → schema 字符数；核心工具归入 {@code core} 桶
 */
public record ToolAttribution(List<String> activatedPlugins, Map<String, Integer> schemaChars) {

    public ToolAttribution {
        activatedPlugins = activatedPlugins == null ? List.of() : List.copyOf(activatedPlugins);
        schemaChars = schemaChars == null ? Map.of() : Map.copyOf(schemaChars);
    }

    /** 落库用的稳定字符串：插件名按传入顺序，逗号分隔；无归因时为空串。 */
    public String pluginsCsv() {
        return String.join(",", activatedPlugins);
    }

    /** 落库用的稳定字符串：{@code 贡献者:字符数} 逗号分隔，顺序随 schemaChars 迭代顺序。 */
    public String schemaCharsCsv() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : schemaChars.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }
}
