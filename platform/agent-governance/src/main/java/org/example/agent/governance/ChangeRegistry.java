package org.example.agent.governance;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.example.agent.domain.change.ChangePlan;
import org.example.agent.domain.change.PlanHasher;
import org.example.agent.domain.port.ChangeStore;
import org.springframework.stereotype.Service;

/**
 * 变更单存储（内存实现；生产落库）。
 *
 * <p>职责边界：<b>只负责登记与查询</b>。签发确认令牌必须由 {@link ConfirmTokenService}
 * 在"用户已登录的请求链路"中完成，工具无权调用。
 */
@Service
public class ChangeRegistry implements ChangeStore {

    private final Map<String, ChangePlan> changes = new ConcurrentHashMap<>();

    @Override
    public ChangePlan request(String projectId, String env, String kind, String summary,
                              String imageDigest, String frontendDistHash, boolean highRisk) {
        String changeId = "chg-" + UUID.randomUUID();
        String planHash = PlanHasher.hash(projectId, env, kind, imageDigest, frontendDistHash, summary);
        ChangePlan plan = new ChangePlan(changeId, projectId, env, kind, planHash, summary,
                imageDigest, frontendDistHash, highRisk, null);
        changes.put(changeId, plan);
        return plan;
    }

    @Override
    public Optional<ChangePlan> find(String changeId) {
        return Optional.ofNullable(changes.get(changeId));
    }
}
