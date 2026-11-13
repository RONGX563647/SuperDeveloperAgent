package top.rongx.agent.gateway.bugfix.stage;

import java.util.List;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.gateway.bugfix.BugFixJson;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * 修复阶段：修复 Agent 输出文件级补丁，平台把它提交到独立分支。
 *
 * <p>只能写 {@code agent/bugfix-*} 分支，绝不触碰默认分支；补丁路径由 infra 层的
 * 修复补丁器做白名单与逃逸校验，Agent 无法借提示词越界。
 */
@Component
public class FixStage implements BugFixStageHandler {

    private final BugFixAgentPort agent;
    private final BugFixGitPort git;
    private final BugFixJson json;

    public FixStage(BugFixAgentPort agent, BugFixGitPort git, BugFixJson json) {
        this.agent = agent;
        this.git = git;
        this.json = json;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.FIX;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        String prompt = """
                你是修复工程师。请基于下面的分析，在修复分支上完成 Bug 修复。

                任务：%s
                描述：
                %s
                基线提交：%s
                修复分支：%s（平台已从基线创建工作树，你只需给出文件内容）
                分析结论：
                %s

                要求：
                1. 只修改与根因相关的业务源码/前端源码；
                2. 每个文件给出完整的新内容（不是 diff 片段）；
                3. 不要修改测试、构建配置或凭证文件，除非根因就在其中；
                4. 最多修改 15 个文件。

                最后必须输出 JSON 代码块：
                ```json
                {"summary":"修复说明","files":[{"path":"src/main/java/...","content":"完整文件内容"}]}
                ```
                """.formatted(AnalyzeStage.safe(run.issueTitle()), AnalyzeStage.clip(run.issueBody(), 6000),
                AnalyzeStage.safe(run.baseSha()), AnalyzeStage.safe(run.branch()),
                AnalyzeStage.clip(run.analysis(), 8000));
        String text;
        try {
            text = agent.run(run, BugFixAgentPort.Role.FIX, prompt);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("修复 Agent 不可用：" + AnalyzeStage.message(e));
        }
        var node = json.extract(text);
        if (node.isEmpty()) {
            return BugFixStageResult.blocked("修复 Agent 未输出可解析的补丁");
        }
        List<CodePatch.FileEdit> edits = json.files(node.get());
        if (edits.isEmpty()) {
            return BugFixStageResult.blocked("修复 Agent 未产出文件改动");
        }
        try {
            String sha = git.commitFix(run, edits, "fix(bug): " + AnalyzeStage.safe(run.issueTitle()));
            return BugFixStageResult.ok("修复已提交到 " + run.branch(), text).withFixSha(sha);
        } catch (IllegalArgumentException e) {
            return BugFixStageResult.blocked("补丁未通过校验：" + e.getMessage());
        } catch (RuntimeException e) {
            return BugFixStageResult.failed("提交修复失败：" + AnalyzeStage.message(e));
        }
    }
}
