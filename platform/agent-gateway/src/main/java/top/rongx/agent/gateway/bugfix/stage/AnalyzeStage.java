package top.rongx.agent.gateway.bugfix.stage;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * 分析阶段：锁定基线提交，让只读 Agent 输出「版本范围 / 根因 / 复现步骤 / 目标文件」。
 *
 * <p>基线 sha 由平台从仓库读取，不相信模型给出的提交号；模型只负责分析内容。
 */
@Component
public class AnalyzeStage implements BugFixStageHandler {

    private final BugFixAgentPort agent;
    private final BugFixGitPort git;

    public AnalyzeStage(BugFixAgentPort agent, BugFixGitPort git) {
        this.agent = agent;
        this.git = git;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.ANALYZE;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        String baseSha;
        try {
            baseSha = git.headSha(run.projectId());
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("无法读取仓库基线提交：" + message(e));
        }
        if (baseSha == null || baseSha.isBlank()) {
            return BugFixStageResult.blocked("仓库没有可用提交，无法分析版本");
        }
        String prompt = """
                你是资深缺陷分析工程师。请分析下面这个 Bug，只做只读排查，不要修改任何文件。

                项目：%s
                任务编号：%s
                标题：%s
                描述：
                %s

                基线提交：%s

                请完成：
                1. 判断 Bug 影响哪些版本/提交范围；
                2. 给出最可能的根因（结合代码路径）；
                3. 给出可执行的复现步骤；
                4. 列出最可能需要修改的文件（相对路径）。

                最后必须输出一个 JSON 代码块，格式：
                ```json
                {"version":"影响版本","commitRange":"提交范围","rootCause":"根因",
                 "reproSteps":["步骤1","步骤2"],"targetFiles":["src/..."],"confidence":0.8}
                ```
                """.formatted(run.projectId(), safe(run.issueNumber()), safe(run.issueTitle()),
                clip(run.issueBody(), 8000), baseSha);
        try {
            String text = agent.run(run, BugFixAgentPort.Role.ANALYZE, prompt);
            if (text == null || text.isBlank()) {
                return BugFixStageResult.blocked("分析 Agent 未返回内容");
            }
            return BugFixStageResult.ok("分析完成，基线 " + baseSha.substring(0, Math.min(8, baseSha.length())), text)
                    .withAnalysis(text).withBaseSha(baseSha);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("分析 Agent 不可用：" + message(e));
        }
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n...(已截断)";
    }

    static String message(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
