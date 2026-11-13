package top.rongx.agent.gateway.bugfix;

import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;

/**
 * 单个阶段的执行器。实现只做「本阶段的事」，阶段推进与落库由
 * {@link BugFixOrchestrator} 统一负责。
 */
public interface BugFixStageHandler {

    BugFixStage stage();

    BugFixStageResult execute(BugFixRun run, BugFixPolicy policy);
}
