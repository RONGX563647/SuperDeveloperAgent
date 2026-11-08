package top.rongx.agent.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.PipelineDefAuthor;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

/**
 * 流水线定义的 agent 兜底：规则识别不出技术栈时，请模型按同一契约给一份候选定义。
 *
 * <p>安全边界：
 * <ul>
 *   <li>模型只能<b>编排</b>——stage 只能引用白名单动作，命令来自 {@code ci/actions.yml} 或平台内置；
 *       输出会经 {@code PipelineDefinitionResolver} 校验，不合契约直接丢弃；</li>
 *   <li>fail-soft：没配模型 / 调用失败 / 超时 → 返回 empty，调用方退回规则骨架；</li>
 *   <li>结果会被标记为"agent 生成，请人工确认"，持久化后同样可以人工编辑。</li>
 * </ul>
 */
@Component
public class AgentPipelineDefAuthor implements PipelineDefAuthor {

    private static final Logger log = LoggerFactory.getLogger(AgentPipelineDefAuthor.class);

    /** 兜底生成的整体时限：宁可退回骨架，也不让接入卡在模型调用上。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final DynamicModelProvider models;

    public AgentPipelineDefAuthor(DynamicModelProvider models) {
        this.models = models;
    }

    @Override
    public Optional<String> author(String projectId, Path repoRoot, ScaffoldPlan rulePlan, String reason) {
        String apiKey;
        try {
            apiKey = models.activeConfig().apiKey();
        } catch (RuntimeException e) {
            log.info("agent 兜底不可用（没有模型配置）：{}", e.getMessage());
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.info("agent 兜底不可用：模型 API Key 未配置");
            return Optional.empty();
        }
        try {
            Model model = models.current();
            Msg prompt = Msg.builder()
                    .role(MsgRole.USER)
                    .name("user")
                    .textContent(buildPrompt(projectId, repoRoot, rulePlan, reason))
                    .build();
            List<String> chunks = model.stream(List.of(prompt), List.of(),
                            GenerateOptions.builder().temperature(0.0).build())
                    .map(AgentPipelineDefAuthor::textOf)
                    .collectList()
                    .block(TIMEOUT);
            String text = chunks == null ? "" : String.join("", chunks);
            Optional<String> yaml = extractYaml(text);
            if (yaml.isEmpty()) {
                log.warn("agent 兜底没有输出可用 YAML（项目 {}）", projectId);
            }
            return yaml;
        } catch (RuntimeException e) {
            log.warn("agent 兜底调用失败（项目 {}）：{}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 提示词刻意只给"契约 + 事实"，不给自由发挥空间。 */
    private String buildPrompt(String projectId, Path repoRoot, ScaffoldPlan rulePlan, String reason) {
        return """
                你是 CI 流水线定义生成器。请只输出一个 YAML 文档，不要任何解释、不要 Markdown 代码块。

                硬约束（违反即作废）：
                1. 顶层字段只能是 name / stages / on-failure / deploy。
                2. 每个 stage 的 type 只能是 action / deploy / health / rollback。
                3. type=action 时 action 只能取：backend_build、backend_test、frontend_build、
                   image_package、stack_deploy、stack_rollback。不得出现其它动作名，不得写 shell 命令。
                4. stage 可选字段：id、runner（container|host）、workdir、image、url、expected。
                5. 构建/测试用 container；docker build、部署类用 host。
                6. 顺序：构建 → 测试 → 镜像（可选）→ 部署（可选）→ 健康检查（可选）；
                   on-failure 里放 rollback。

                参考骨架（可以改 stages，但不要改格式）：
                %s

                事实：
                - 项目 id：%s
                - 规则识别结论：stack=%s（%s）
                - 脚手架提示：%s
                - 仓库顶层结构：%s
                """.formatted(skeleton(rulePlan), projectId, rulePlan.stack(), reason,
                String.join("；", rulePlan.notes()), listing(repoRoot));
    }

    private String skeleton(ScaffoldPlan plan) {
        return plan.files().stream()
                .filter(f -> f.path().equals("ci/pipeline.yml"))
                .map(ScaffoldPlan.ScaffoldFile::content)
                .findFirst()
                .orElse("name: release\nstages:\n  - id: build\n    type: action\n    action: backend_build\n");
    }

    /** 仓库顶层条目（最多 30 个），让模型有事实可依而不是凭空猜。 */
    private String listing(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return "（无）";
        }
        try (Stream<Path> stream = Files.list(repoRoot)) {
            return stream.limit(30)
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("（空目录）");
        } catch (Exception e) {
            return "（读取失败：" + e.getMessage() + "）";
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : response.getContent()) {
            if (block instanceof TextBlock text) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 从模型输出里取出 YAML：容忍 ``` 围栏与前后解释文字。
     *
     * <p>不做语义校验（那是解析器的事），这里只负责"拿到一段看起来像 YAML 的文本"。
     */
    static Optional<String> extractYaml(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end);
            }
        }
        // 从第一行 "name:" 或 "stages:" 起截到末尾，丢掉前置解释
        int first = text.length();
        for (String marker : List.of("\nname:", "name:", "\nstages:", "stages:")) {
            int idx = text.indexOf(marker);
            if (idx >= 0) {
                first = Math.min(first, idx == 0 ? 0 : idx + 1);
            }
        }
        if (first < text.length()) {
            text = text.substring(first);
        }
        text = text.trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
