package top.rongx.agent.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.run.StageStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 缺 / 坏 {@code ci/pipeline.yml} 时的行为（回归：曾经只抛一句"无法读取某路径"）。
 *
 * <p>三条契约：
 * <ul>
 *   <li><b>白名单诚实</b>：仓库里没有定义就不列出流水线，避免"点了必然失败"；</li>
 *   <li><b>触发即失败</b>：提交里没有定义时在触发处返回可读原因（400），不落一条垃圾构建历史；</li>
 *   <li><b>报错可照做</b>：消息必须区分"根本没生成 ci/"和"生成了但没提交"。</li>
 * </ul>
 *
 * <p>执行期内还有一层 E-220 兜底（检出后仍读不到定义，例如触发与检出之间仓库被改写）——
 * 那是竞态防御，正常路径由触发期校验拦住。
 */
class PipelineDefinitionGuardTest {

    private static final String PROJECT = "GUARD";

    @Test
    @DisplayName("工作区已生成 ci/pipeline.yml 但没提交 → 拒绝并提示先提交")
    void rejectsUncommittedDefinition(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");
        // 只写到工作区（很多用户的真实状态：点了「生成流水线脚本」但忘了 commit）
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), validPipeline(), StandardCharsets.UTF_8);

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo));
        try {
            // 工作区有 → 白名单能列出（否则用户连"为什么不能点"都看不到）
            assertEquals(List.of("release", "verify"), engine.pipelines(PROJECT));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> engine.trigger(PROJECT, "release", Map.of("gitSha", sha)));
            String message = e.getMessage();
            assertTrue(message.contains(sha.substring(0, 7)), message);
            assertTrue(message.contains("ci/pipeline.yml"), message);
            assertTrue(message.contains("未提交"), "应指出工作区有、提交里没有：" + message);
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("仓库根本没有 ci/ → 白名单为空，触发给「生成流水线脚本」指引")
    void emptyWhitelistWhenNoDefinition(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        String sha = commit(repo, "init");

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo));
        try {
            assertEquals(List.of(), engine.pipelines(PROJECT), "没有定义时不应列出假流水线");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> engine.trigger(PROJECT, "release", Map.of("gitSha", sha)));
            assertTrue(e.getMessage().contains("生成流水线脚本"), e.getMessage());
            assertTrue(e.getMessage().contains("ci/pipeline.yml"), e.getMessage());
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("定义已提交 → 正常触发并跑通（不误伤正常仓库）")
    void triggersCommittedDefinition(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), validPipeline(), StandardCharsets.UTF_8);
        String sha = commit(repo, "ci: pipeline");

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo));
        try {
            assertEquals(List.of("release", "verify"), engine.pipelines(PROJECT));
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            StageStatus status = awaitTerminal(engine, runId);
            assertEquals(StageStatus.SUCCEEDED, status,
                    "build stage 应成功，日志：" + engine.logTail(runId));
            assertTrue(engine.stages(runId).stream().anyMatch(s -> s.stageId().equals("build")),
                    String.valueOf(engine.stages(runId)));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("ci/pipeline.yml 存在但 YAML 非法 → 触发期给出解析错误，不进入执行")
    void rejectsBrokenDefinition(@TempDir Path temp) throws Exception {
        Path repo = newRepo(temp);
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), "name: release\nstages: [", StandardCharsets.UTF_8);
        String sha = commit(repo, "ci: broken");

        BuiltInPipelineEngine engine = newEngine(temp, locator(repo));
        try {
            // 白名单不因解析失败而消失（否则用户连触发都点不到，看不到具体原因）
            assertEquals(List.of("release", "verify"), engine.pipelines(PROJECT));
            String runId = engine.trigger(PROJECT, "verify", Map.of("gitSha", sha));
            assertEquals(StageStatus.FAILED, awaitTerminal(engine, runId));
            assertTrue(engine.stages(runId).stream().anyMatch(s -> "E-221".equals(s.errorCode())),
                    "应给出 E-221（定义非法），实际：" + engine.stages(runId));
        } finally {
            engine.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 单 stage 的最小流水线（host 执行 node，不依赖宿主 JDK 版本）。 */
    private static String validPipeline() {
        return """
                name: release
                stages:
                  - id: build
                    type: action
                    action: backend_build
                    runner: host
                """;
    }

    private static BuiltInPipelineEngine newEngine(Path workspace, ProjectRepoLocator locator) {
        return new BuiltInPipelineEngine(RESOLVER, HOST, HOST, workspace,
                RunHistoryStore.disabled(), "ci", locator);
    }

    private static final ActionRunner HOST = new ProcessActionRunner();

    /** 只认 backend_build / backend_test 的配方解析器（避免测试依赖真实 mvn）。 */
    private static final ActionResolver RESOLVER = new ActionResolver() {
        @Override
        public Optional<ActionSpec> resolve(Path repoRoot, ActionId action) {
            if (action == ActionId.BACKEND_BUILD || action == ActionId.BACKEND_TEST) {
                return Optional.of(new ActionSpec(action,
                        List.of(List.of("node", "-e", "console.log('ok')")),
                        List.of(), Duration.ofMinutes(1), ActionSource.builtin("test")));
            }
            return Optional.empty();
        }

        @Override
        public List<ActionSpec> resolveAll(Path repoRoot) {
            return List.of();
        }

        @Override
        public boolean hasRecipe(Path repoRoot) {
            return true;
        }
    };

    /** DIRECT 模式定位器：仓库就在给定目录，worktree 建在 {@code <dir>/.agent/worktrees}。 */
    private static ProjectRepoLocator locator(Path repo) {
        return new ProjectRepoLocator() {
            @Override
            public Optional<Path> repoDir(String projectId) {
                return Optional.of(repo);
            }

            @Override
            public Optional<Path> worktreeRoot(String projectId) {
                return Optional.of(repo.resolve(".agent/worktrees"));
            }

            @Override
            public void sync(String projectId) {
                // 本地目录：无远端，无需同步
            }

            @Override
            public StorageMode modeOf(String projectId) {
                return StorageMode.DIRECT;
            }
        };
    }

    private static Path newRepo(Path temp) throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "ci@example.com");
        git(repo, "config", "user.name", "CI");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        return repo;
    }

    private static StageStatus awaitTerminal(BuiltInPipelineEngine engine, String runId)
            throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            StageStatus status = engine.status(runId);
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(100);
        }
        fail("运行未在 30s 内结束：" + runId);
        return null;
    }

    private static String commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", message);
        return git(repo, "rev-parse", "HEAD").trim();
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败：" + out);
        }
        return out;
    }
}
