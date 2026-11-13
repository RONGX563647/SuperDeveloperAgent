package top.rongx.agent.gateway.bugfix;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixDeployPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;

/**
 * 自动修复的部署适配器：复用平台运维（部署）Agent 把指定提交发布到测试环境。
 *
 * <p>只做「测试环境部署」：环境名与已确认绑定来自策略配置，阶段层已做校验；
 * 这里把绑定 id 直接写进提示词，禁止 Agent 重新生成草案或向用户追问。
 */
@Component
public class ChatBugFixDeploy implements BugFixDeployPort {

    private static final String PIPELINE = "release";

    private final BugFixAgentPort agents;
    private final BugFixPolicyStore policies;

    public ChatBugFixDeploy(BugFixAgentPort agents, BugFixPolicyStore policies) {
        this.agents = agents;
        this.policies = policies;
    }

    @Override
    public String deploy(BugFixRun run, String sha) {
        BugFixPolicy policy = policies.load();
        String purpose = switch (run.stage()) {
            case REPRO_DEPLOY -> "复现 Bug";
            case VERIFY_DEPLOY -> "验证修复";
            default -> "自动修复流水线";
        };
        String prompt = """
                自动修复流水线需要把下面这个提交部署到测试环境（%s），用于%s，请自动完成部署，不要向用户追问。
                项目：%s
                环境：%s
                提交：%s
                流水线：%s
                环境绑定：binding_id=%s（已由人工确认，直接复用，不要再生成草案）

                执行要求：
                1. 先调 env_summary 确认变量数量与中间件健康；缺变量或中间件异常先说明并停止。
                2. 调 deploy_env_status：已有可用固化脚本且证据成功 → 直接 deploy_run 重放。
                3. 没有可用脚本 → deploy_run，必须带上上面的 binding_id。
                4. 触发后立刻用 wait_run 等待终态（一次最多 90 秒，未结束就再次 wait_run 续等，
                   不要高频轮询 ci_status）；失败用 read_run_log 定位原因，最多再重试两次。
                5. 跑通后必须 deploy_save_recipe 固化脚本，再用 deploy_env_status 确认版本与证据。
                6. 结束时用中文汇报：运行 id、各阶段结果、脚本版本、失败原因（如有）。
                """.formatted(policy.testEnv(), purpose, run.projectId(), policy.testEnv(), sha,
                PIPELINE, policy.bindingId());
        String text = agents.run(run, BugFixAgentPort.Role.DEPLOY, prompt);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("运维 Agent 未返回部署结果");
        }
        return text;
    }
}
