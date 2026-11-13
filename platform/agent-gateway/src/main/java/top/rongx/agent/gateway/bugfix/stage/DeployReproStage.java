package top.rongx.agent.gateway.bugfix.stage;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixDeployPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * 复现部署阶段：把基线提交部署到测试环境。
 *
 * <p>自动修复永远不碰生产环境；未开启自动部署或缺少已确认的环境绑定时停在 BLOCKED，
 * 让用户补齐前置条件后重试，而不是偷偷跳过部署。
 */
@Component
public class DeployReproStage implements BugFixStageHandler {

    private final BugFixDeployPort deploy;
    private final EnvBindingPort bindings;

    public DeployReproStage(BugFixDeployPort deploy, EnvBindingPort bindings) {
        this.deploy = deploy;
        this.bindings = bindings;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.REPRO_DEPLOY;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        if (!policy.autoDeploy()) {
            return BugFixStageResult.blocked("未开启自动部署，已停在复现环境搭建阶段");
        }
        if (policy.bindingId() == null || policy.bindingId().isBlank()) {
            return BugFixStageResult.blocked("未配置已确认的测试环境绑定，无法自动部署");
        }
        if (run.baseSha() == null || run.baseSha().isBlank()) {
            return BugFixStageResult.blocked("缺少分析阶段锁定的基线提交");
        }
        try {
            bindings.requireForRun(policy.bindingId(), run.projectId(), policy.testEnv());
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("环境绑定不可用：" + AnalyzeStage.message(e));
        }
        try {
            String evidence = deploy.deploy(run, run.baseSha());
            return BugFixStageResult.ok("测试环境已部署（" + policy.testEnv() + "）", evidence)
                    .withEnv(policy.testEnv());
        } catch (RuntimeException e) {
            return BugFixStageResult.failed("部署复现环境失败：" + AnalyzeStage.message(e));
        }
    }
}
