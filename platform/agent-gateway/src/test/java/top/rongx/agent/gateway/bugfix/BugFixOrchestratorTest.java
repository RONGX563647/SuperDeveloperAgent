package top.rongx.agent.gateway.bugfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixDeployPort;
import top.rongx.agent.domain.bugfix.BugFixEdit;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStatus;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.gateway.bugfix.stage.AnalyzeStage;
import top.rongx.agent.gateway.bugfix.stage.DeployReproStage;
import top.rongx.agent.gateway.bugfix.stage.FixStage;
import top.rongx.agent.gateway.bugfix.stage.PullRequestStage;
import top.rongx.agent.gateway.bugfix.stage.ReproduceStage;
import top.rongx.agent.gateway.bugfix.stage.VerifyDeployStage;
import top.rongx.agent.gateway.bugfix.stage.VerifyStage;
import top.rongx.agent.infra.bugfix.SqliteBugFixStore;

/**
 * 自动修复编排器的端到端测试：用脚本化 Agent / 假部署 / 假 Git 跑完整状态机，
 * 验证「分析 → 复现部署 → 复现 → 修复 → 验证部署 → 验证 → PR」全部推进且证据落库，
 * 并覆盖取消竞态、同项目串行与证据门槛。
 */
class BugFixOrchestratorTest {

    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String FIX_SHA = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String PR_URL = "https://example.com/o/r/pull/7";

    @TempDir
    Path temp;

    @Test
    void runsThroughAllStagesAndCreatesPullRequest() throws Exception {
        SqliteBugFixStore store = new SqliteBugFixStore(temp.toString());
        BugFixOrchestrator orchestrator = orchestrator(store, enabledPolicy());
        BugFixRun started = orchestrator.start(issue("task-1", "BUG"));

        BugFixRun done = await(orchestrator, started.id(), BugFixStatus.SUCCEEDED);
        assertEquals(BugFixStage.DONE, done.stage());
        assertEquals(FIX_SHA, done.fixSha());
        assertEquals(HEAD_SHA, done.baseSha());
        assertEquals(PR_URL, done.prUrl());
        assertEquals("test", done.env());
        assertTrue(done.analysis().contains("根因"), "分析结论应落库：" + done.analysis());
        assertTrue(done.evidence() != null && !done.evidence().isBlank(), "证据应落库");
        assertFalse(orchestrator.events(done.userId(), done.id()).isEmpty(), "时间线应有事件");
    }

    @Test
    void retryResumesFromBlockedStage() throws Exception {
        SqliteBugFixStore store = new SqliteBugFixStore(temp.toString());
        BugFixPolicy blocked = new BugFixPolicy(true, false, true, "test", "", 2, 0);
        store.save(blocked);
        BugFixOrchestrator orchestrator = orchestrator(store, blocked);
        BugFixRun started = orchestrator.start(issue("task-1", "BUG"));

        BugFixRun paused = await(orchestrator, started.id(), BugFixStatus.BLOCKED);
        assertEquals(BugFixStage.REPRO_DEPLOY, paused.stage(), "未开启自动部署应停在部署阶段");

        store.save(enabledPolicy());
        BugFixRun resumed = orchestrator.resume(paused.userId(), paused.id());
        assertEquals(BugFixStatus.QUEUED, resumed.status());
        assertEquals(paused.stage(), resumed.stage());
        assertEquals(1, resumed.attempt());

        BugFixRun done = await(orchestrator, started.id(), BugFixStatus.SUCCEEDED);
        assertEquals(PR_URL, done.prUrl());
    }

    @Test
    void triggerIgnoresFeatureAndDisabledPolicy() throws Exception {
        SqliteBugFixStore store = new SqliteBugFixStore(temp.toString());
        BugFixOrchestrator orchestrator = orchestrator(store, BugFixPolicy.defaults());
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("bugFixOrchestrator", orchestrator);
        BugFixTriggerService trigger = new BugFixTriggerService(store,
                beans.getBeanProvider(BugFixOrchestrator.class));

        trigger.onIssueCreated(issue("task-1", "FEAT"));
        trigger.onIssueCreated(issue("task-1", "BUG"));
        assertTrue(store.list("u1", null, 10).isEmpty(), "策略关闭时不应入队");

        store.save(enabledPolicy());
        trigger.onIssueCreated(issue("task-1", "FEAT"));
        assertTrue(store.list("u1", null, 10).isEmpty(), "Feat 任务不应自动修复");

        trigger.onIssueCreated(issue("task-1", "BUG"));
        List<BugFixRun> runs = store.list("u1", null, 10);
        assertEquals(1, runs.size());
        // 等异步执行结束，避免测试结束时临时目录被清理导致写入失败
        await(orchestrator, runs.get(0).id(), BugFixStatus.SUCCEEDED);
    }

    @Test
    void cancelDuringStageIsNotOverwrittenByLateResult() throws Exception {
        SqliteBugFixStore store = new SqliteBugFixStore(temp.toString());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BugFixAgentPort slow = (run, role, prompt) -> {
            if (role == BugFixAgentPort.Role.ANALYZE) {
                entered.countDown();
                awaitLatch(release);
            }
            return defaultAnswer(role);
        };
        BugFixOrchestrator orchestrator = orchestrator(store, enabledPolicy(), slow,
                (run, sha) -> "部署成功：" + sha);
        BugFixRun started = orchestrator.start(issue("task-1", "BUG"));

        assertTrue(entered.await(5, TimeUnit.SECONDS), "分析阶段应已开始");
        BugFixRun cancelled = orchestrator.cancel(started.userId(), started.id());
        assertEquals(BugFixStatus.CANCELLED, cancelled.status());
        release.countDown();

        Thread.sleep(500);
        BugFixRun after = orchestrator.get(started.userId(), started.id());
        assertEquals(BugFixStatus.CANCELLED, after.status(), "阶段返回后不能覆盖取消状态");
        assertEquals(BugFixStage.ANALYZE, after.stage());
    }

    @Test
    void serializesRunsOfTheSameProject() throws Exception {
        SqliteBugFixStore store = new SqliteBugFixStore(temp.toString());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        BugFixDeployPort deploy = (run, sha) -> {
            int now = active.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return "部署成功：" + sha;
        };
        BugFixOrchestrator orchestrator = orchestrator(store, enabledPolicy(), new FakeAgent(), deploy);

        BugFixRun first = orchestrator.start(issue("task-1", "BUG"));
        BugFixRun second = orchestrator.start(issue("task-2", "BUG"));
        await(orchestrator, first.id(), BugFixStatus.SUCCEEDED);
        await(orchestrator, second.id(), BugFixStatus.SUCCEEDED);

        assertEquals(1, max.get(), "同一项目的运行必须串行，避免同时部署同一测试环境");
    }

    @Test
    void lowConfidenceReproductionBlocksBeforeFix() throws Exception {
        SqliteBugFixStore store = new SqliteBugFixStore(temp.toString());
        BugFixAgentPort weak = (run, role, prompt) -> role == BugFixAgentPort.Role.REPRODUCE
                ? "```json\n{\"reproduced\":true,\"confidence\":0.2}\n```"
                : defaultAnswer(role);
        BugFixOrchestrator orchestrator = orchestrator(store, enabledPolicy(), weak,
                (run, sha) -> "部署成功：" + sha);
        BugFixRun started = orchestrator.start(issue("task-1", "BUG"));

        BugFixRun paused = await(orchestrator, started.id(), BugFixStatus.BLOCKED);
        assertEquals(BugFixStage.REPRODUCE, paused.stage(), "证据/置信度不足应停在复现阶段");
        assertTrue(paused.message().contains("置信度") || paused.message().contains("证据"),
                "阻塞原因应可操作：" + paused.message());
    }

    // ---------------------------------------------------------------- 装配

    private BugFixOrchestrator orchestrator(SqliteBugFixStore store, BugFixPolicy policy) {
        return orchestrator(store, policy, new FakeAgent(), (run, sha) -> "部署成功：" + sha);
    }

    private BugFixOrchestrator orchestrator(SqliteBugFixStore store, BugFixPolicy policy,
                                            BugFixAgentPort agent, BugFixDeployPort deploy) {
        store.save(policy);
        ObjectMapper mapper = new ObjectMapper();
        BugFixJson json = new BugFixJson(mapper);
        BugFixGitPort git = new FakeGit();
        EnvBindingPort bindings = new EnvBindingPort() {
            @Override
            public EnvBinding draft(String projectId, String env, String actor) {
                return new EnvBinding("b1", projectId, env, "{}", "h", actor, "", 0, 0, "DRAFT");
            }

            @Override
            public EnvBinding requireForRun(String bindingId, String projectId, String env) {
                if (!"b1".equals(bindingId)) {
                    throw new IllegalArgumentException("绑定不存在：" + bindingId);
                }
                return new EnvBinding(bindingId, projectId, env, "{}", "h", "user",
                        "user", System.currentTimeMillis(), 0, "CONFIRMED");
            }
        };
        List<BugFixStageHandler> handlers = List.of(
                new AnalyzeStage(agent, git, json),
                new DeployReproStage(deploy, bindings),
                new ReproduceStage(agent, json),
                new FixStage(agent, git, json),
                new VerifyDeployStage(deploy, bindings),
                new VerifyStage(agent, json),
                new PullRequestStage(git));
        return new BugFixOrchestrator(store, store, handlers, (run, message) -> {
        });
    }

    private static BugFixPolicy enabledPolicy() {
        return new BugFixPolicy(true, true, true, "test", "b1", 2, 0);
    }

    private static GitIssue issue(String id, String kind) {
        long now = System.currentTimeMillis();
        return new GitIssue(id, "u1", "p1", "GITHUB", "o/r", "1",
                "登录接口 500", "打开登录页点击提交后返回 500", "OPEN", kind, "bug", "",
                "PLATFORM", "LOCAL", "", "", now, now);
    }

    private static BugFixRun await(BugFixOrchestrator orchestrator, String id, BugFixStatus expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        BugFixRun last = null;
        while (System.currentTimeMillis() < deadline) {
            last = orchestrator.get(null, id);
            if (last.status() == expected) {
                // 状态先于事件/通知落库可见；留一点收尾时间，避免 @TempDir 清理与写库竞态
                Thread.sleep(150);
                return orchestrator.get(null, id);
            }
            if (last.status() == BugFixStatus.FAILED && expected != BugFixStatus.FAILED) {
                throw new AssertionError("运行意外失败：" + last.message() + "（阶段 "
                        + last.stage() + "）");
            }
            Thread.sleep(50);
        }
        assertNotNull(last);
        throw new AssertionError("等待状态 " + expected + " 超时，当前 " + last.status()
                + "（阶段 " + last.stage() + "）");
    }

    private static String defaultAnswer(BugFixAgentPort.Role role) {
        return switch (role) {
            case ANALYZE -> "疑似空指针。\n```json\n{\"version\":\"1.4.0\","
                    + "\"rootCause\":\"根因为空值未判空\",\"targetFiles\":[\"src/main/App.java\"],"
                    + "\"confidence\":0.9}\n```";
            case REPRODUCE -> "```json\n{\"reproduced\":true,\"evidence\":\"500 日志\","
                    + "\"confidence\":0.9}\n```";
            case FIX -> "```json\n{\"summary\":\"补空值判断\",\"edits\":[{\"path\":"
                    + "\"src/main/App.java\",\"old\":\"return 0;\",\"new\":\"return 1;\"}]}\n```";
            case VERIFY -> "```json\n{\"fixed\":true,\"evidence\":\"登录成功\","
                    + "\"confidence\":0.9}\n```";
            case DEPLOY -> "部署完成";
        };
    }

    private static final class FakeAgent implements BugFixAgentPort {
        @Override
        public String run(BugFixRun run, Role role, String prompt) {
            return defaultAnswer(role);
        }
    }

    private static final class FakeGit implements BugFixGitPort {
        @Override
        public void sync(String projectId) {
        }

        @Override
        public String headSha(String projectId) {
            return HEAD_SHA;
        }

        @Override
        public String commitFix(BugFixRun run, List<BugFixEdit> edits, String message) {
            assertFalse(edits.isEmpty());
            return FIX_SHA;
        }

        @Override
        public String openPullRequest(BugFixRun run, String title, String body) {
            return PR_URL;
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
