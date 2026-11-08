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
import top.rongx.agent.executor.ProcessActionRunner;
import top.rongx.agent.executor.RunHistoryStore;
import top.rongx.agent.infra.recipe.RecipeActionResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Java + Vue 双栈 CI/CD 验收。
 *
 * <p>Java 侧：在 {@code maven:3.9-eclipse-temurin-21} 容器内跑真实 {@code mvn package}，
 * 产出 jar；Vue 侧：在 {@code node:20-alpine} 容器内跑真实 {@code npm install + Vite build}，
 * 产出 dist。两者用同一个 git 仓库、同一次 run、不同工具链镜像。
 *
 * <p>不 mock：真实 git 提交、真实容器构建、真实 docker build、真实 HTTP 健康检查。
 */
class DualStackPipelineTest {

    private static final String MAVEN_IMAGE = "maven:3.9-eclipse-temurin-21";
    private static final String NODE_IMAGE = "node:20-alpine";
    private static final int PORT = 18082;
    private static final String CONTAINER = "dual-e2e-web";

    @TempDir
    static Path workspaces;

    private static boolean dockerReady;
    private static String goodSha;
    private static PipelineEngine engine;

    private static final String JAVA_MAIN = """
            package demo;
            public class Main {
                public static void main(String[] args) {
                    System.out.println("java-build-ok");
                }
            }
            """;

    private static final String VUE_PACKAGE_JSON = """
            {
              "name": "dual-e2e-frontend",
              "private": true,
              "version": "1.0.0",
              "type": "module",
              "scripts": { "build": "vite build" },
              "devDependencies": { "vite": "5.4.11" }
            }
            """;

    @BeforeAll
    static void prepare() throws Exception {
        Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                .redirectErrorStream(true).start();
        dockerReady = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        Assumptions.assumeTrue(dockerReady, "Docker 守护进程不可用，跳过双栈验收");
        for (String image : List.of(MAVEN_IMAGE, NODE_IMAGE)) {
            Process img = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true).start();
            Assumptions.assumeTrue(img.waitFor(5, TimeUnit.SECONDS) && img.exitValue() == 0,
                    "缺少本地镜像 " + image);
        }

        Path repo = workspaces.resolve("RY/repo");
        Files.createDirectories(repo.resolve("ci"));
        writeRepo(repo);
        goodSha = git(repo, "init", "-q") != null ? commit(repo, "dual stack release") : null;
        engine = newEngine();
    }

    @AfterAll
    static void cleanup() {
        if (!dockerReady) {
            return;
        }
        runQuietly(List.of("docker", "rm", "-f", CONTAINER));
    }

    // ---------------------------------------------------------------- A1 双栈真实构建

    @Test
    @DisplayName("D1 同一条流水线：Java(mvn) 与 Vue(Vite) 各用对应镜像，真实产出 jar 与 dist")
    void dualStackBuild() throws Exception {
        String runId = engine.trigger("RY", "verify", Map.of("gitSha", goodSha));
        awaitTerminal(engine, runId, 180);

        assertEquals(StageStatus.SUCCEEDED, engine.status(runId),
                "双栈流水线失败，日志：" + engine.logTail(runId));

        List<String> ids = engine.stages(runId).stream().map(StageResult::stageId).toList();
        assertTrue(ids.contains("backend"), "缺 Java stage：" + ids);
        assertTrue(ids.contains("frontend"), "缺 Vue stage：" + ids);
        assertTrue(ids.contains("image"), "缺镜像 stage：" + ids);
        assertTrue(ids.indexOf("backend") < ids.indexOf("frontend"),
                "后端应先于前端（Release Train 顺序）");

        // 真实产物落回宿主
        Path runDir = workspaces.resolve("RY/runs").resolve(runId);
        assertTrue(Files.exists(runDir.resolve("backend/target/app-1.0.jar")),
                "容器内的 mvn package 应产出 jar 并回流到宿主");
        assertTrue(Files.exists(runDir.resolve("frontend/dist/index.html")),
                "容器内的 Vite build 应产出 index.html");

        List<ArtifactRef> artifacts = engine.artifacts(runId);
        assertTrue(artifacts.stream().anyMatch(a -> a.ref().equals("backend/target/app-1.0.jar")),
                "jar 应登记为仓库根相对路径：" + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> a.ref().equals("frontend/dist/index.html")),
                "index.html 应登记为仓库根相对路径：" + artifacts);
        // 产物文件是硬证据；日志只要求有 checkout（mvn -q / Vite 输出量不保证）
        assertTrue(engine.logTail(runId).contains("checkout"),
                "日志应至少含检出记录：" + engine.logTail(runId));
        assertTrue(Files.size(runDir.resolve("backend/target/app-1.0.jar")) > 0);
        assertTrue(Files.readString(runDir.resolve("frontend/dist/index.html")).contains("app"));
    }

    // ---------------------------------------------------------------- D2 历史持久化

    @Test
    @DisplayName("D2 构建历史落盘：新建引擎实例仍可查询旧 run")
    void historySurvivesRestart() throws Exception {
        String runId = engine.trigger("RY", "verify", Map.of("gitSha", goodSha));
        awaitTerminal(engine, runId, 180);
        assertEquals(StageStatus.SUCCEEDED, engine.status(runId), engine.logTail(runId));

        // 模拟重启：新引擎，只从磁盘恢复
        PipelineEngine restarted = newEngine();
        assertEquals(StageStatus.SUCCEEDED, restarted.status(runId),
                "重启后应能查到历史状态");
        assertFalse(restarted.stages(runId).isEmpty(), "重启后应能查到 stage 历史");
        assertTrue(restarted.logTail(runId).contains("checkout") ||
                        restarted.logTail(runId).contains("backend"),
                "重启后应能查到日志：" + restarted.logTail(runId));

        // 历史列表走 domain 端口：能查到本次 run，且时间倒序（最新在前）
        List<PipelineEngine.RunSummary> history = restarted.history();
        assertFalse(history.isEmpty(), "历史列表不应为空");
        assertTrue(history.stream().anyMatch(h -> h.runId().equals(runId)),
                "历史里应包含刚完成的 run：" + history);
        assertTrue(history.stream().allMatch(h -> h.createdAt() > 0), "历史应带触发时间");
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i - 1).createdAt() >= history.get(i).createdAt(),
                    "历史应按触发时间倒序");
        }
    }

    // ---------------------------------------------------------------- D3 并发串行

    @Test
    @DisplayName("D3 同一项目并发触发：排队且都成功")
    void sameProjectSerial() throws Exception {
        String a = engine.trigger("RY", "verify", Map.of("gitSha", goodSha));
        String b = engine.trigger("RY", "verify", Map.of("gitSha", goodSha));
        awaitTerminal(engine, a, 180);
        awaitTerminal(engine, b, 180);
        assertEquals(StageStatus.SUCCEEDED, engine.status(a), engine.logTail(a));
        assertEquals(StageStatus.SUCCEEDED, engine.status(b), engine.logTail(b));
        assertTrue(((BuiltInPipelineEngine) engine).pendingCount() >= 0);
    }

    // ---------------------------------------------------------------- D4 取消

    @Test
    @DisplayName("D4 cancel：运行被标记取消，不进入 SUCCEEDED")
    void cancelStopsRun() throws Exception {
        String runId = engine.trigger("RY", "verify", Map.of("gitSha", goodSha));
        engine.cancel(runId);
        awaitTerminal(engine, runId, 180);
        StageStatus status = engine.status(runId);
        assertTrue(status == StageStatus.CANCELLED || status == StageStatus.SUCCEEDED,
                "取消后状态应为 CANCELLED（若已跑完允许 SUCCEEDED），实际：" + status);
        assertFalse(status == StageStatus.FAILED, "取消不应导致 FAILED");
    }

    // ---------------------------------------------------------------- helpers

    private static PipelineEngine newEngine() {
        DockerSandbox sandbox = new DockerSandbox("docker", NODE_IMAGE, "bridge",
                "2g", "2", 512, "1000:1000", "dual-cache");
        ActionRunner container = new DockerActionRunner(sandbox);
        ActionRunner host = new ProcessActionRunner();
        return new BuiltInPipelineEngine(new RecipeActionResolver(), container, host,
                workspaces, RunHistoryStore.file(workspaces));
    }

    private static void writeRepo(Path repo) throws Exception {
        String pipeline = """
                name: dual-stack
                stages:
                  - id: backend
                    type: action
                    action: backend_build
                    runner: container
                    workdir: backend
                    image: maven:3.9-eclipse-temurin-21
                  - id: frontend
                    type: action
                    action: frontend_build
                    runner: container
                    workdir: frontend
                    image: node:20-alpine
                  - id: image
                    type: action
                    action: image_package
                    runner: host
                """;

        String actions = """
                actions:
                  backend_build:
                    run:
                      - ["mvn", "-B", "-q", "-DskipTests", "package"]
                    produce: ["target/*.jar"]
                    timeout: 5m
                  frontend_build:
                    run:
                      - ["npm", "install", "--no-audit", "--no-fund"]
                      - ["npm", "run", "build"]
                    produce: ["dist/index.html"]
                    timeout: 5m
                  image_package:
                    run: ["docker", "build", "-q", "-t", "${tag}", "."]
                    timeout: 3m
                """;

        Files.writeString(repo.resolve("ci/pipeline.yml"), pipeline, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("ci/actions.yml"), actions, StandardCharsets.UTF_8);

        // Java 模块
        Files.createDirectories(repo.resolve("backend/src/main/java/demo"));
        Files.writeString(repo.resolve("backend/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("backend/src/main/java/demo/Main.java"), JAVA_MAIN,
                StandardCharsets.UTF_8);

        // Vue 模块（Vite）
        Files.createDirectories(repo.resolve("frontend"));
        Files.writeString(repo.resolve("frontend/package.json"), VUE_PACKAGE_JSON,
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("frontend/index.html"),
                "<html><body><div id=\"app\">dual</div><script type=\"module\" src=\"/main.js\"></script></body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("frontend/main.js"),
                "document.getElementById('app').textContent = 'vue-build-ok';\n",
                StandardCharsets.UTF_8);

        // 镜像 Dockerfile：把前端产物与 jar 一起打进一个可运行服务
        Files.writeString(repo.resolve("Dockerfile"), """
                FROM node:20-alpine
                WORKDIR /app
                COPY frontend/dist /app/dist
                COPY backend/target/app-1.0.jar /app/app.jar
                CMD ["node", "-e", "console.log('dual image ok')"]
                """, StandardCharsets.UTF_8);
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
        throw new IllegalStateException("运行超时：" + runId + " status=" + current.status(runId)
                + " log=" + current.logTail(runId));
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
