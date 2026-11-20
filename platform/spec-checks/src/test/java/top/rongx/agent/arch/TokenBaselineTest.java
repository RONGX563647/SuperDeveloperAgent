package top.rongx.agent.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.tool.ToolPluginIndex;
import top.rongx.agent.domain.tool.ToolPlugins;

/**
 * 真实请求 token 回归基线（token 爆炸专项 6.4 第 3 条 + 第 1 条量化的确定性代理）。
 *
 * <p>验收要求「同一任务 inputTokens 下降 ≥ 40%」且「新增真实请求 token 回归基线」。
 * 本测试用<b>真实工具面 + 真实插件目录 + 真实评测指令</b>构造可复现基线：
 * <ol>
 *   <li>从 {@code agent-tools} 源码解析每个 {@code @Tool} 的真实 name / description；</li>
 *   <li>用 {@link ToolPlugins#standard()} 与 {@link ToolPlugins#CORE} 构造真实
 *       {@link ToolPluginIndex}（与运行时同一份目录、同一套 route/visible 逻辑）；</li>
 *   <li>对固定的「用户指令 → 首轮激活插件」集合，复现首轮路由（最多 2 个插件），
 *       计算下发 schema 的字符数；</li>
 *   <li>断言相对「全量注入」下降 ≥ 40%，且按需总量不超硬上限（防回涨）。</li>
 * </ol>
 *
 * <p><b>口径说明</b>：schema 的 name+description 文本是每轮常驻 prompt 的确定性组成，
 * 英文约 4 字符/token，字符比与 token 比同构；真实 {@code inputTokens} 绝对值读数由
 * 段 2 的 {@code TokenUsageRepository.summary()} 从真实会话采集。这里锁定的是
 * <b>结构性下降</b>——只要按需披露的路径不被改坏，比例就稳定可复现，适合 CI 兜底。
 * 全量/按需都含相同的工具，差异完全来自「不发哪些 schema」，因此结论对代理口径不敏感。
 */
class TokenBaselineTest {

    /** 单条指令相对全量注入至少要下降的比例（验收 6.4 第 1 条）。 */
    private static final double MIN_DROP_RATIO = 0.40;
    /** 首轮按需下发的 schema 字符硬上限：当前最坏（双插件）1706，留出余量但能拦回涨。 */
    private static final int MAX_ON_DEMAND_CHARS = 2_200;
    /** 全量工具 schema 字符上限：与 ToolSchemaBudgetTest 的 description 预算同向，防整体膨胀。 */
    private static final int MAX_FULL_CHARS = 4_500;

    private static final Pattern TOOL_DESCRIPTION = Pattern.compile(
            "@Tool\\(\\s*name\\s*=\\s*\"([A-Za-z0-9_]+)\",\\s*description\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    @DisplayName("真实请求 token 基线：首轮按需披露相对全量下降 ≥40%，且不超总量上限")
    void onDemandSchemaIsAtLeast40PercentSmaller() throws IOException {
        var descriptions = toolDescriptions();
        assertFalse(descriptions.isEmpty(), "未能从 agent-tools 源码解析到任何 @Tool description");

        ToolPluginIndex index = ToolPluginIndex.of(ToolPlugins.standard(), descriptions.keySet(),
                ToolPlugins.CORE);
        List<String> allTools = new ArrayList<>(descriptions.keySet());
        int fullChars = schemaChars(allTools, descriptions);
        assertTrue(fullChars <= MAX_FULL_CHARS,
                "全量 schema 字符 " + fullChars + " 超过上限 " + MAX_FULL_CHARS + "，先收紧 description");

        // 真实评测指令集：取自 ToolEvalBaselineTest 的典型任务，并补一条同时命中双插件的最坏场景。
        List<String> instructions = List.of(
                "当前项目用的什么仓库和端口？",
                "有哪些环境可以部署？",
                "看看可以跑哪些流水线",
                "上次构建成没成功？",
                "帮我查一下 run-abc123 这次构建的状态",
                "这个项目能执行哪些动作？",
                "backend_build 具体会跑什么命令？",
                "触发一次 release 流水线构建 sha abc1234",
                "单独跑一下 backend_build 这个动作验证",
                "把当前版本发布到生产",
                "查一下工单和任务板上的任务",
                "看下这个 git 分支的代码能不能合");

        for (String instruction : instructions) {
            Set<String> active = new LinkedHashSet<>(index.alwaysVisible());
            active.addAll(firstTwo(index.route(instruction), index));
            List<String> visibleTools = new ArrayList<>();
            for (String tool : descriptions.keySet()) {
                if (index.visible(tool, active)) {
                    visibleTools.add(tool);
                }
            }
            int onDemandChars = schemaChars(visibleTools, descriptions);
            double drop = fullChars == 0 ? 0 : 1.0 - onDemandChars * 1.0 / fullChars;
            assertTrue(drop >= MIN_DROP_RATIO,
                    String.format(Locale.ROOT,
                            "指令「%s」首轮下发 %d 工具 / %d 字符，相对全量 %d 字符仅下降 %.1f%%，"
                                    + "低于门槛 %.0f%%（激活插件=%s）",
                            instruction, visibleTools.size(), onDemandChars, fullChars,
                            drop * 100, MIN_DROP_RATIO * 100, active));
            assertTrue(onDemandChars <= MAX_ON_DEMAND_CHARS,
                    String.format(Locale.ROOT,
                            "指令「%s」首轮下发 %d 字符，超过硬上限 %d（防 token 回涨）",
                            instruction, onDemandChars, MAX_ON_DEMAND_CHARS));
        }
    }

    /** 复现 ProgressiveToolMiddleware.limit：按插件目录顺序保留命中的前 2 个。 */
    private static Set<String> firstTwo(Set<String> routed, ToolPluginIndex index) {
        Set<String> kept = new LinkedHashSet<>();
        for (ToolPluginIndex.Plugin plugin : index.plugins()) {
            if (kept.size() >= 2) {
                break;
            }
            if (routed.contains(plugin.name())) {
                kept.add(plugin.name());
            }
        }
        return kept;
    }

    /** name + description 字符和；中文关键词只用于路由，不进 prompt，故不计入。 */
    private static int schemaChars(List<String> tools, java.util.Map<String, String> descriptions) {
        int total = 0;
        for (String tool : tools) {
            total += tool.length() + descriptions.get(tool).length();
        }
        return total;
    }

    private static java.util.Map<String, String> toolDescriptions() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", ".."), "agent-tools", "src", "main", "java");
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = TOOL_DESCRIPTION.matcher(Files.readString(file));
                while (m.find()) {
                    result.putIfAbsent(m.group(1), m.group(2));
                }
            }
        }
        return result;
    }
}
