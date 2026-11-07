package org.example.agent.governance;

/**
 * 动作门禁（PRD §2.1：不区分角色，只按动作设门禁）。
 *
 * <p>注意：不存在"由 Agent 自行确认"的门禁——{@link #CONFIRM_TOKEN} 只能由人的会话签发。
 */
public enum ActionGate {

    /** 无门禁：只读、沙箱执行、建分支、提 PR。 */
    NO_GATE,

    /** 需二次身份确认（重输密码）：改流水线定义、改凭证、接入项目。 */
    SECOND_PASSWORD,

    /** 需确认令牌：生产发布/回滚、生产 Runbook。 */
    CONFIRM_TOKEN;

    public boolean needsHuman() {
        return this != NO_GATE;
    }
}
