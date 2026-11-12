package top.rongx.agent.domain.policy;

/** 策略裁决：放行 / 需人工确认 / 拒绝。 */
public enum PolicyDecision {
    ALLOW,
    CONFIRM,
    DENY
}
