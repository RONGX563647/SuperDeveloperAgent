package top.rongx.agent.domain.bugfix;

/** 自动修复策略的持久化端口（平台级单条配置）。 */
public interface BugFixPolicyStore {

    BugFixPolicy load();

    BugFixPolicy save(BugFixPolicy policy);
}
