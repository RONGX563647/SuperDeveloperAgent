package top.rongx.agent.gateway.bugfix.stage;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixDeployPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/** 验证部署阶段：把修复提交重新部署到测试环境，供下一阶段验证。 */
@Component
public class VerifyDeployStage implements BugFixStageHandler {

    private final BugFixDeployPort deploy;
    private final EnvBindingPort bindings;

    public VerifyDeployStage(BugFixDeployPort deploy, EnvBindingPort bindings) {
        this.deploy = deploy;
        this.bindings = bindings;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.VERIFY_DEPLOY;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        if (!policy.autoDeploy()) {
            return BugFixStageResult.blocked("未开启自动部署，无法部署修复版本");
        }
        if (policy.bindingId() == null || policy.bindingId().isBlank()) {
            return BugFixStageResult.blocked("未配置已确认的测试环境绑定，无法自动部署");
        }
        if (run.fixSha() == null || run.fixSha().isBlank()) {
            return BugFixStageResult.blocked("缺少修复提交，无法部署验证");
        }
        try {
            bindings.requireForRun(policy.bindingId(), run.projectId(), policy.testEnv());
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("环境绑定不可用：" + AnalyzeStage.message(e));
        }
        try {
            String evidence = deploy.deploy(run, run.fixSha());
            return BugFixStageResult.ok("修复版本已部署（" + policy.testEnv() + "）", evidence)
                    .withEnv(policy.testEnv());
        } catch (RuntimeException e) {
            return BugFixStageResult.failed("部署修复版本失败：" + AnalyzeStage.message(e));
        }
    }
}
