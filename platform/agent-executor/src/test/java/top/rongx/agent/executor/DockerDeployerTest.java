package top.rongx.agent.executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DockerDeployer 真实验收（不 mock）：真实镜像、真实容器、真实 HTTP。
 *
 * <p>验证三件方案 C 承诺的事：
 * <ol>
 *   <li>部署后服务可达（engine 的 health stage 才能成立）；</li>
 *   <li>重复部署是替换（同项目一个槽位，不端口冲突）；</li>
 *   <li>失败时返回错误码而不是抛异常。</li>
 * </ol>
 */
class DockerDeployerTest {

    private static final String TOOLCHAIN = "node:20-alpine";
    private static final String PROJECT = "DEPLOY-E2E";
    private static final String CONTAINER = "agent-deploy-deploy-e2e";
    private static final int HOST_PORT = 18095;

    private static boolean dockerReady;
    private static String image;

    @TempDir
    static Path buildDir;

    private final DockerDeployer deployer = new DockerDeployer();

    @BeforeAll
    static void prepare() throws Exception {
        Process info = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                .redirectErrorStream(true).start();
        dockerReady = info.waitFor(5, TimeUnit.SECONDS) && info.exitValue() == 0;
        Assumptions.assumeTrue(dockerReady, "Docker 不可用，跳过真实部署验收");

        Process img = new ProcessBuilder("docker", "image", "inspect", TOOLCHAIN)
                .redirectErrorStream(true).start();
        Assumptions.assumeTrue(img.waitFor(5, TimeUnit.SECONDS) && img.exitValue() == 0,
                "缺少本地镜像 " + TOOLCHAIN);

        Files.writeString(buildDir.resolve("app.js"), """
                const http = require('http');
                http.createServer((req, res) => {
                  if (req.url === '/healthz') { res.writeHead(200); res.end('OK'); }
                  else { res.writeHead(404); res.end('not found'); }
                }).listen(3000, '0.0.0.0');
                """, StandardCharsets.UTF_8);
        Files.writeString(buildDir.resolve("Dockerfile"), """
                FROM node:20-alpine
                WORKDIR /app
                COPY app.js /app/app.js
                EXPOSE 3000
                CMD ["node", "/app/app.js"]
                """, StandardCharsets.UTF_8);

        image = "deploy-e2e-web:test";
        run("docker", "build", "-q", "-t", image, buildDir.toString());
    }

    @AfterAll
    static void cleanup() {
        if (!dockerReady) {
            return;
        }
        runQuietly("docker", "rm", "-f", CONTAINER);
        if (image != null) {
            runQuietly("docker", "image", "rm", "-f", image);
        }
    }

    @Test
    @DisplayName("部署 → 容器可达 → 重复部署替换（幂等）→ 取消掉旧进程不报错")
    void deploysAndReplacesContainer() throws Exception {
        DockerDeployer local = deployer;
        DeployResult first = local.deploy(request("run-1"));
        assertTrue(first.success(), "首次部署应成功：" + first.summary() + "\n" + first.log());
        assertTrue(first.endpoint().contains(":" + HOST_PORT), first.endpoint());
        assertEquals(CONTAINER, first.resource());

        assertEquals("OK", httpGet("http://127.0.0.1:" + HOST_PORT + "/healthz"),
                "部署后 /healthz 应返回 200 OK");

        // 第二次：同槽位替换，不应因端口/名称冲突失败
        DeployResult second = local.deploy(request("run-2"));
        assertTrue(second.success(), "重复部署应成功（替换语义）：" + second.summary() + "\n" + second.log());
        assertEquals("OK", httpGet("http://127.0.0.1:" + HOST_PORT + "/healthz"),
                "替换后服务仍应可用");

        // 标签应跟着新 run（证明容器确实被替换而不是复用旧容器）
        String labels = run("docker", "inspect", "--format", "{{.Config.Labels}}", CONTAINER);
        assertTrue(labels.contains("run-2"), "容器应带新 run 标签：" + labels);
    }

    @Test
    @DisplayName("无镜像 → E-501（不启动容器，直接可读失败）")
    void failsFastWithoutImage() {
        DeployResult result = deployer.deploy(new DeployRequest(PROJECT, "run-x", "abcdef1234567",
                null, null, null, buildDir.toString(), DeployTarget.DOCKER,
                HOST_PORT, 3000, "/healthz", null, false, null));
        assertEquals(false, result.success());
        assertEquals("E-501", result.errorCode());
    }

    private DeployRequest request(String runId) {
        return new DeployRequest(PROJECT, runId, "abcdef1234567", image, null, null,
                buildDir.toString(), DeployTarget.DOCKER, HOST_PORT, 3000, "/healthz", null, false, null);
    }

    private static String httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
            } catch (Exception ignored) {
                // 容器尚未就绪，继续等
            }
            TimeUnit.MILLISECONDS.sleep(300);
        }
        return "";
    }

    private static String run(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "命令超时：" + String.join(" ", argv));
        assertEquals(0, p.exitValue(), "命令失败：" + String.join(" ", argv) + "\n" + out);
        return out;
    }

    private static void runQuietly(String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
    }
}
