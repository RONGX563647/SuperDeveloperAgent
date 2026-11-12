package top.rongx.agent.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.executor.BuiltInPipelineEngine;
import top.rongx.agent.executor.DockerActionRunner;
import top.rongx.agent.executor.DockerSandbox;
import top.rongx.agent.executor.PipelineEngineConfig;
import top.rongx.agent.executor.ProcessActionRunner;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.infra.recipe.RecipeActionResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「自研替代 Jenkins」的真实端到端验收：**不 mock 任何一步**。
 *
 * <p>链路：真实 git 仓库三次提交 → 按指定 sha 检出 → 容器内构建 → 容器内测试 →
 * 真实 {@code docker build} → 真实容器部署 → 真实 HTTP 健康检查 → 构建历史与产物。
 *
 * <p>失败场景：坏提交 → 失败即停 → 不部署 → 执行 on-failure 回滚到上一个好版本。
 */
class PipelineAcceptanceTest {

    private static final String TOOLCHAIN = "node:20-alpine";
    private static final String CONTAINER = "ci-e2e-web";
    private static final int PORT = 18081;

    @TempDir
    static Path workspaces;

    private static boolean dockerReady;
    /** 同一次验收共用一个引擎，lastGood（回滚锚点）才是跨运行可见的。 */
    private static PipelineEngine engine;
    private static String goodSha;
    private static String v2Sha;
    private static String badSha;

    private static final String DOCKERFILE = """
            FROM node:20-alpine
            WORKDIR /app
            COPY app.js /app/app.js
            EXPOSE 3000
            CMD ["node", "/app/app.js"]
            """;

    private static final String APP_JS = """
            const http = require('http');
            http.createServer((req, res) => {
              if (req.url === '/healthz') { res.writeHead(200); res.end('OK'); }
              else { res.writeHead(404); res.end('not found'); }
            }).listen(3000, '0.0.0.0', () => console.log('listening on 3000'));
            """;

    private static final String GOOD_TEST = "console.log('test passed');\n";
    private static final String BAD_TEST = """
            console.error('FAIL: deliberate test failure');
            process.exit(1);
            """;

    /** 部署脚本（人写在仓库里、review 过；平台只执行 argv）。 */
    private static final String DEPLOY_JS = """
            const cp = require('child_process');
            const tag = process.argv[2];
            cp.spawnSync('docker', ['rm', '-f', 'ci-e2e-web']);   // 不存在时也继续
            const r = cp.spawnSync('docker', ['run', '-d', '--rm', '--name', 'ci-e2e-web',
                                              '-p', '18081:3000', tag], {encoding: 'utf8'});
            if (r.status !== 0) { console.error('deploy failed: ' + r.stderr); process.exit(1); }
            console.log('deployed ' + tag);
            """;

    @BeforeAll
    static void prepare() throws Exception {
        dockerReady = TestEnv.dockerAvailable();
        Assumptions.assumeTrue(dockerReady, "Docker 守护进程不可用，跳过 CI/CD 验收");
        Process img = new ProcessBuilder("docker", "image", "inspect", TOOLCHAIN)
                .redirectErrorStream(true).start();
        Assumptions.assumeTrue(img.waitFor(5, TimeUnit.SECONDS) && img.exitValue() == 0,
                "缺少本地镜像 " + TOOLCHAIN);

        // 项目仓库：先用旧布局造一个 git 仓库，再通过 v2 接入路径收编为平台 bare（端到端走真实接入）
        Path repo = workspaces.resolve("RY-repo");
        Files.createDirectories(repo.resolve("ci"));
        writeFiles(repo, APP_JS, GOOD_TEST);
        goodSha = git(repo, "init", "-q") != null ? commit(repo, "good release") : null;

        // 第二次提交：应用内容变化（验证按 sha 检出，不是取 HEAD）
        Files.writeString(repo.resolve("app.js"), APP_JS.replace("'OK'", "'OK-v2'"),
                StandardCharsets.UTF_8);
        v2Sha = commit(repo, "v2 release");

        // 第三次提交：故意让测试失败
        Files.writeString(repo.resolve("test.js"), BAD_TEST, StandardCharsets.UTF_8);
        badSha = commit(repo, "deliberately broken");

        // v2 收编：repo → bare（引擎从 bare 建 worktree 检出）
        new GitWorktreeManager(workspaces).linkLocal("RY", repo, false);
        engine = newEngine();
    }

    @AfterAll
    static void cleanup() {
        if (!dockerReady) {
            return;
        }
        runQuietly(List.of("docker", "rm", "-f", CONTAINER));
        // 清掉测试产生的镜像
        try {
            Process p = new ProcessBuilder("docker", "images", "-q", "--filter", "reference=ci-*")
                    .redirectErrorStream(true).start();
            String ids = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(10, TimeUnit.SECONDS);
            if (!ids.isEmpty()) {
                for (String id : ids.split("\\R")) {
                    runQuietly(List.of("docker", "image", "rm", "-f", id.trim()));
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
    }

    // ---------------------------------------------------------------- 验收 1：完整发布链路

    @Test
    @DisplayName("A1 真实 CI/CD：检出→构建→测试→镜像→部署→健康→历史")
    void fullReleasePipeline() throws Exception {
        PipelineEngine current = engine;
        String runId = current.trigger("RY", "release", Map.of("gitSha", goodSha));
        awaitTerminal(current, runId, 90);

        assertEquals(StageStatus.SUCCEEDED, current.status(runId),
                "运行失败，日志：" + current.logTail(runId));

        List<StageResult> stages = current.stages(runId);
        List<String> ids = stages.stream().map(StageResult::stageId).toList();
        for (String expected : List.of("checkout", "build", "test", "image", "deploy", "health")) {
            assertTrue(ids.contains(expected), "缺少 stage " + expected + "：" + ids);
        }
        assertEquals("health", ids.get(ids.size() - 1), "健康检查应是最后一个 stage");
        assertTrue(stages.stream().allMatch(s -> s.status() == StageStatus.SUCCEEDED));
        assertTrue(stages.stream().allMatch(s -> s.durationMs() >= 0));

        // 部署后真实 HTTP 可访问
        assertEquals("OK", httpGet("http://127.0.0.1:" + PORT + "/healthz"));

        // 产物：源码文件 + 镜像（含 digest）
        List<ArtifactRef> artifacts = current.artifacts(runId);
        assertTrue(artifacts.stream().anyMatch(a -> a.kind().equals("file") && a.ref().equals("app.js")),
                "应收集到构建产物 app.js：" + artifacts);
        ArtifactRef image = artifacts.stream().filter(a -> a.kind().equals("image"))
                .findFirst().orElseThrow(() -> new AssertionError("缺少镜像产物：" + artifacts));
        assertNotNull(image.digest(), "镜像应有 digest 记账，便于回滚");
        assertEquals(runId, image.meta().get("runId"));

        // 日志里能看到测试的真实输出
        assertTrue(current.logTail(runId).contains("test passed"),
                "日志应包含测试输出：" + current.logTail(runId));
    }

    // ---------------------------------------------------------------- 验收 2：失败即停 + 回滚

    @Test
    @DisplayName("A2 失败即停：测试失败 → 后续 stage 不执行 → on-failure 回滚")
    void failureStopsAndRollsBack() throws Exception {
        PipelineEngine current = engine;
        String goodRun = current.trigger("RY", "release", Map.of("gitSha", goodSha));
        awaitTerminal(current, goodRun, 90);
        assertEquals(StageStatus.SUCCEEDED, current.status(goodRun),
                "基线发布应先成功：" + current.logTail(goodRun));

        String badRun = current.trigger("RY", "release", Map.of("gitSha", badSha));
        awaitTerminal(current, badRun, 90);

        assertEquals(StageStatus.FAILED, current.status(badRun), "坏提交必须失败");
        List<String> ids = current.stages(badRun).stream().map(StageResult::stageId).toList();
        assertFalse(ids.contains("deploy"), "失败后不得部署：" + ids);
        assertFalse(ids.contains("health"), "失败后不得执行健康检查：" + ids);
        assertTrue(ids.stream().anyMatch(s -> s.startsWith("on-failure:")),
                "应执行 on-failure 回滚：" + ids + "\n日志：" + current.logTail(badRun));
        assertTrue(current.logTail(badRun).contains("deployed ci-" + goodSha.substring(0, 7)),
                "回滚应把上一个好版本重新部署，日志：" + current.logTail(badRun));

        StageResult failedTest = current.stages(badRun).stream()
                .filter(s -> s.stageId().equals("test")).findFirst().orElseThrow();
        assertEquals("E-101", failedTest.errorCode(), "测试失败应分类为用户代码失败");
    }

    // ---------------------------------------------------------------- 验收 3：指定提交构建

    @Test
    @DisplayName("A3 指定 git sha 构建：检出的是该提交的内容，不是 HEAD")
    void buildsPinnedSha() throws Exception {
        PipelineEngine current = engine;
        String runId = current.trigger("RY", "verify", Map.of("gitSha", v2Sha));
        awaitTerminal(current, runId, 60);
        assertEquals(StageStatus.SUCCEEDED, current.status(runId), current.logTail(runId));

        // v2 后 worktree 构建完即清理：用日志验证检出的正是该提交
        assertTrue(current.logTail(runId).contains("worktree " + v2Sha),
                "日志应记录检出的 sha：log=" + current.logTail(runId));
        // 构建产物 app.js 内容来自 v2 提交（构建时打入产物区）
        assertTrue(current.artifacts(runId).stream().anyMatch(a -> a.ref().contains("app.js")),
                "应收集到 app.js 产物");
        assertFalse(goodSha.equals(v2Sha));
    }

    // ---------------------------------------------------------------- 验收 4：白名单/参数校验

    @Test
    @DisplayName("A4 未登记流水线与非法 sha 被拒")
    void inputValidation() {
        PipelineEngine current = engine;
        assertThrows(IllegalArgumentException.class,
                () -> current.trigger("RY", "deploy-everything", Map.of("gitSha", "abcdef1")));
        assertThrows(IllegalArgumentException.class,
                () -> current.trigger("RY", "release", Map.of("gitSha", "not-a-sha")));
        assertEquals(List.of("release", "verify"), current.pipelines("RY"));
        assertEquals("builtin-pipeline", current.id());
    }

    // ---------------------------------------------------------------- helpers

    private static PipelineEngine newEngine() {
        DockerSandbox sandbox = new DockerSandbox("docker", TOOLCHAIN, "bridge",
                "1g", "1", 256, "1000:1000");
        ActionRunner container = new DockerActionRunner(sandbox);
        ActionRunner host = new ProcessActionRunner();
        return new BuiltInPipelineEngine(PipelineEngineConfig
                .builder(new RecipeActionResolver(), container, host, workspaces)
                .build());
    }

    /** 写入随版本控制的流水线定义与源码。 */
    private static void writeFiles(Path repo, String appJs, String testBody) throws Exception {
        String pipeline = """
                name: release
                stages:
                  - id: build
                    type: action
                    action: backend_build
                    runner: container
                  - id: test
                    type: action
                    action: backend_test
                    runner: container
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                  - id: deploy
                    type: action
                    action: stack_deploy
                    runner: host
                  - id: health
                    type: health
                    url: http://127.0.0.1:%d/healthz
                    expected: 200
                on-failure:
                  - id: rollback
                    type: action
                    action: stack_rollback
                    runner: host
                """.formatted(PORT);

        String actions = """
                actions:
                  backend_build:
                    run:
                      - ["node", "-e", "const fs=require('fs');fs.mkdirSync('dist',{recursive:true});fs.writeFileSync('dist/build.txt','built');console.log('build ok')"]
                    produce: ["app.js"]
                    timeout: 2m
                  backend_test:
                    run: ["node", "test.js"]
                    timeout: 2m
                  image_package:
                    run: ["docker", "build", "-q", "-t", "${tag}", "."]
                    timeout: 3m
                  stack_deploy:
                    run: ["node", "deploy.js", "${tag}"]
                    timeout: 2m
                  stack_rollback:
                    run: ["node", "deploy.js", "${tag}"]
                    timeout: 2m
                """;

        Files.writeString(repo.resolve("ci/pipeline.yml"), pipeline, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("ci/actions.yml"), actions, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("Dockerfile"), DOCKERFILE, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("app.js"), appJs, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("test.js"), testBody, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("deploy.js"), DEPLOY_JS, StandardCharsets.UTF_8);
    }

    private static String commit(Path repo, String message) throws Exception {
        git(repo, "config", "user.email", "ci@example.com");
        git(repo, "config", "user.name", "CI");
        git(repo, "add", ".");
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

    private static void awaitTerminal(PipelineEngine current, String runId, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (current.status(runId).isTerminal()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("运行超时未完成：" + runId + " status=" + current.status(runId)
                + " log=" + current.logTail(runId));
    }

    private static String httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "健康检查端点应返回 200");
        return response.body();
    }

    private static void runQuietly(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            // 清理失败不影响断言
        }
    }
}
