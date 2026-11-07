package org.example.agent.domain.change;

import java.time.Instant;

/**
 * 一次变更的计划（审批/确认的对象）。
 *
 * <p>确认令牌与 {@code planHash} 绑定：执行前校验哈希未变，防止"确认 A、执行 B"。
 *
 * @param changeId        变更标识
 * @param projectId       所属项目（服务端绑定，不来自模型）
 * @param env             目标环境
 * @param kind            RELEASE / ROLLBACK / MIGRATION / RUNBOOK
 * @param planHash        计划内容的规范化哈希
 * @param summary         面向人的一句话
 * @param imageDigest     后端镜像 digest（可空）
 * @param frontendDistHash 前端 dist 哈希（可空）
 * @param highRisk        是否高危（含 SQL / 依赖升级 / 安全配置）
 * @param createdAt       创建时间
 */
public record ChangePlan(
        String changeId,
        String projectId,
        String env,
        String kind,
        String planHash,
        String summary,
        String imageDigest,
        String frontendDistHash,
        boolean highRisk,
        Instant createdAt) {

    public ChangePlan {
        if (planHash == null || planHash.isBlank()) {
            throw new IllegalArgumentException("planHash 不能为空");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
