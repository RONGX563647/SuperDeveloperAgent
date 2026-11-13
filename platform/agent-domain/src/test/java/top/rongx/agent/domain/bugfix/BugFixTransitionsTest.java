package top.rongx.agent.domain.bugfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 状态迁移表的纯单测：不依赖数据库、线程与阶段实现。 */
class BugFixTransitionsTest {

    @Test
    void pullRequestSuccessWaitsForHumanMergeInsteadOfCompleting() {
        BugFixTransitions.Decision decision = BugFixTransitions.decide(
                BugFixStage.PULL_REQUEST, BugFixStageResult.Outcome.CONTINUE, 0, 2);

        assertEquals(BugFixStage.PULL_REQUEST, decision.nextStage());
        assertEquals(BugFixStatus.WAITING_MERGE, decision.status());
        assertTrue(decision.finished());
        assertFalse(decision.autoRetry());
    }

    @Test
    void verifyFailureReturnsToFixWhenAttemptsRemain() {
        BugFixTransitions.Decision decision = BugFixTransitions.decide(
                BugFixStage.VERIFY, BugFixStageResult.Outcome.FAILED, 0, 2);

        assertEquals(BugFixStage.FIX, decision.nextStage());
        assertEquals(BugFixStatus.RUNNING, decision.status());
        assertEquals(1, decision.attempt());
        assertFalse(decision.finished());
    }

    @Test
    void verifyFailureFailsAfterAttemptsExhausted() {
        BugFixTransitions.Decision decision = BugFixTransitions.decide(
                BugFixStage.VERIFY, BugFixStageResult.Outcome.FAILED, 1, 2);

        assertEquals(BugFixStage.VERIFY, decision.nextStage());
        assertEquals(BugFixStatus.FAILED, decision.status());
        assertEquals(2, decision.attempt());
        assertTrue(decision.finished());
    }

    @Test
    void deployFailureRetriesWithBackoff() {
        BugFixTransitions.Decision decision = BugFixTransitions.decide(
                BugFixStage.VERIFY_DEPLOY, BugFixStageResult.Outcome.FAILED, 0, 3);

        assertEquals(BugFixStage.VERIFY_DEPLOY, decision.nextStage());
        assertEquals(BugFixStatus.QUEUED, decision.status());
        assertTrue(decision.autoRetry());
        assertEquals(10_000L, decision.retryDelayMs());
        assertFalse(decision.finished());
        assertEquals(60_000L, BugFixTransitions.retryDelay(9), "退避上限 60 秒");
    }

    @Test
    void blockedIsTerminalForThisExecution() {
        BugFixTransitions.Decision decision = BugFixTransitions.decide(
                BugFixStage.ANALYZE, BugFixStageResult.Outcome.BLOCKED, 0, 2);

        assertEquals(BugFixStage.ANALYZE, decision.nextStage());
        assertEquals(BugFixStatus.BLOCKED, decision.status());
        assertTrue(decision.finished());
    }
}
