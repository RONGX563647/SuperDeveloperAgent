package top.rongx.agent.gateway.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.governance.EnvPolicyService;

/** 分环境策略的只读视图（当前矩阵由代码默认值提供）。 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final EnvPolicyService policy;
    private final CurrentUser currentUser;

    public PolicyController(EnvPolicyService policy, CurrentUser currentUser) {
        this.policy = policy;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> view() {
        currentUser.checkPermission("settings:read");
        return Map.of("envs", policy.envs(), "policies", policy.view());
    }
}
