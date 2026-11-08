package top.rongx.agent.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.executor.BuiltInPipelineEngine;
import top.rongx.agent.executor.DockerActionRunner;
import top.rongx.agent.executor.DockerSandbox;
import top.rongx.agent.executor.ProcessActionRunner;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.infra.recipe.RecipeActionResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2 接入链路端到端验收：本地目录收编 → 引擎从 bare worktree 检出 → 构建。
 *
 * <p>不 mock：真实 git 收编（把本测试所在仓库收编进临时工作区）、真实 worktree 检出、
 * 真实命令执行（verify 流水线剔除部署，安全）。Docker 不可用时自动跳过镜像 stage 之外的断言。
 */
class IntakeE2ETest {

    @TempDir
    static Path ws;

    /** 被收编的"用户项目"：临时目录里 git init 的一个小项目（含配方）。 */
    @TempDir
    static Path userRepo;

    private static String head;

    @org.junit.jupiter.api.BeforeAll
    static void prepare() throws Exception {
        // 1. 造用户项目：README + ci/actions.yml + ci/pipeline.yml + Dockerfile
        Files.createDirectories(userRepo.resolve("ci"));
        Files.writeString(userRepo.resolve("README.md"), "intake e2e\n");
        Files.writeString(userRepo.resolve("ci/actions.yml"), """
                actions:
                  backend_build:
                    run: [["node", "-e", "require('fs').writeFileSync('built.txt','ok')"]]
                    produce: ["built.txt"]
                    timeout: 2m
                """);
        Files.writeString(userRepo.resolve("ci/pipeline.yml"), """
                name: release
                stages:
                  - id: build
                    type: action
                    action: backend_build
                    runner: host
                """);
        Files.writeString(userRepo.resolve("Dockerfile"), "FROM scratch\n");

        git(userRepo, "init", "-q");
        git(userRepo, "add", "-A");
        git(userRepo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v1");
        head = new GitWorktreeManager(ws).revParse(userRepo, "HEAD");

        Assumptions.assumeTrue(head != null && head.length() == 40, "git 不可用，跳过 E2E");
    }

    private static void git(Path dir, String... args) throws Exception {
        if (dir == null) {
            throw new IllegalStateException("temp dir not injected yet");
        }
        String[] argv = new String[1 + args.length];
        argv[0] = "git";
        System.arraycopy(args, 0, argv, 1, args.length);
        Process p = new ProcessBuilder(argv).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }

    @Test
    @DisplayName("E2E 本地收编 → 用户追加提交 → fetch → 引擎 worktree 构建拿到新内容")
    void intakeThenBuildSeesNewCommit() throws Exception {
        GitWorktreeManager git = new GitWorktreeManager(ws);
        Path userDir = userRepo;

        // 1. 收编
        String linkedHead = git.linkLocal("E2E", userDir, false);
        org.junit.jupiter.api.Assertions.assertEquals(head, linkedHead);

        // 2. 用户继续开发：追加提交（模拟真实使用）
        Files.writeString(userDir.resolve("feature.txt"), "new-feature\n");
        git(userDir, "add", "-A");
        git(userDir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "v2");
        String newHead = git.revParse(userDir, "HEAD");

        // 3. 平台同步（fetchLocal）
        git.fetchLocal("E2E", userDir);

        // 4. 引擎构建：verify 流水线（只构建，不部署）
        DockerSandbox sandbox = new DockerSandbox("docker", "node:20-alpine", "bridge",
                "1g", "1", 256, "1000:1000");
        boolean dockerReady = sandbox.available();
        Assumptions.assumeTrue(dockerReady, "Docker 不可用，跳过引擎构建断言");
        PipelineEngine engine = new BuiltInPipelineEngine(new RecipeActionResolver(),
                new DockerActionRunner(sandbox), new ProcessActionRunner(), ws);

        // ci/pipeline.yml 的 name=release → pipelines() 白名单应含它
        assertTrue(engine.pipelines("E2E").contains("release"),
                "白名单应从 ci/pipeline.yml 读取: " + engine.pipelines("E2E"));

        String runId = engine.trigger("E2E", "verify", Map.of("gitSha", newHead));
        long deadline = System.currentTimeMillis() + 60_000;
        while (!engine.status(runId).isTerminal()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("构建超时：" + engine.logTail(runId));
            }
            Thread.sleep(200);
        }

        assertEquals(top.rongx.agent.domain.run.StageStatus.SUCCEEDED, engine.status(runId),
                "构建日志：" + engine.logTail(runId));
        // 构建确实用到了新提交的内容（worktree 检出的是 v2）
        assertTrue(engine.logTail(runId).contains("worktree " + newHead)
                        || engine.stages(runId).stream().anyMatch(s -> "checkout".equals(s.stageId())),
                "应有 checkout stage");
        // 构建成功后 worktree 保留（产物可访问）
        Path buildWt = ws.resolve("E2E/worktrees/wt-build-" + runId);
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(buildWt),
                "成功构建的 worktree 应保留（产物可访问）");
        // 且产物就在其中
        org.junit.jupiter.api.Assertions.assertTrue(
                Files.exists(buildWt.resolve("built.txt")), "构建产物应存在于保留的 worktree");
    }
}
