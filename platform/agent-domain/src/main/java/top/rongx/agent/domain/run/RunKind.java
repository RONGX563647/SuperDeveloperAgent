package top.rongx.agent.domain.run;

/** 统一运行账本里的运行类型（四类编排共用一张表，便于全局查看与恢复）。 */
public enum RunKind {
    CHAT,
    DEPLOY,
    WORKSPACE,
    BUGFIX,
    PIPELINE
}
