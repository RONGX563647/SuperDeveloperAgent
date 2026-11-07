package org.example.agent.governance;

import java.time.Instant;

/**
 * 一次性确认令牌。
 *
 * <p>绑定 {@code userId + changeId + planHash}，5 分钟过期，用后即失效。
 * 只由用户会话签发，Agent 无法构造（架构上不提供该能力）。
 *
 * @param token    令牌值
 * @param userId   签发人
 * @param changeId 变更单
 * @param planHash 计划哈希（执行前需比对）
 * @param expiresAt 过期时间
 * @param used     是否已使用
 */
public record ConfirmToken(
        String token,
        String userId,
        String changeId,
        String planHash,
        Instant expiresAt,
        boolean used) {

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public ConfirmToken markUsed() {
        return new ConfirmToken(token, userId, changeId, planHash, expiresAt, true);
    }
}
