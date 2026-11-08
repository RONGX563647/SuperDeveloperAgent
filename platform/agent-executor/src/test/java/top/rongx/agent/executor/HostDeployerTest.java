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
 * HostDeployer 真实验收：真实 jar、真实进程、真实 HTTP。
 *
 * <p>验证方案 C 对 HOST 的三个承诺：
 * <ol>
 *   <li>发布后服务可达；</li>
 *   <li>重复部署替换进程（不残留、不端口冲突）；</li>
 *   <li>回滚真正回到 previous 指针指向的版本。</li>
 * </ol>
 */
class HostDeployerTest {

    private static final String PROJECT = "host-e2e";
    private static final int PORT = 18096;

    private final HostDeployer deployer = new HostDeployer();

    @TempDir
    static Path workspace;

    @BeforeAll
    static void buildJar() throws Exception {
        Path src = workspace.resolve("src/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                import com.sun.net.httpserver.HttpServer;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        int port = 8080;
                        for (String a : args) {
                            if (a.startsWith("--server.port=")) {
                                port = Integer.parseInt(a.substring("--server.port=".length()));
                            }
                        }
                        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", port), 0);
                        server.createContext("/healthz", exchange -> {
                            byte[] body = "OK".getBytes();
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                            exchange.close();
                        });
                        server.start();
                    }
                }
                """, StandardCharsets.UTF_8);
        run(workspace, "javac", "-d", workspace.resolve("classes").toString(), src.toString());
        Path target = workspace.resolve("target");
        Files.createDirectories(target);
        run(workspace, "jar", "cfe", target.resolve("app.jar").toString(), "Main",
                "-C", workspace.resolve("classes").toString(), ".");
    }

    @AfterAll
    static void cleanup() {
        // 依据部署器写的 pid 文件收尾，避免残留进程
        Path pid = workspace.resolve(".agent/deploy").resolve(PROJECT).resolve("app.pid");
        try {
            if (Files.isRegularFile(pid)) {
                long id = Long.parseLong(Files.readString(pid).trim());
                ProcessHandle.of(id).ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
    }

    @Test
    @DisplayName("部署 → 健康 → 换版本 → 回滚到上一版")
    void deploysRedploysAndRollsBack() throws Exception {
        DeployResult v1 = deployer.deploy(request("sha-one-1111"));
        assertTrue(v1.success(), "首次部署应成功：" + v1.summary() + "\n" + v1.log());
        assertEquals("sha-one-1111", v1.resource(), "资源标识应为版本目录");
        assertEquals("OK", httpGet("http://127.0.0.1:" + PORT + "/healthz"));

        // 第二次部署：pid 必须变化（替换而非复用）
        String pid1 = Files.readString(ptr("app.pid")).trim();
        DeployResult v2 = deployer.deploy(request("sha-two-2222"));
        assertTrue(v2.success(), "第二次部署应成功：" + v2.summary() + "\n" + v2.log());
        String pid2 = Files.readString(ptr("app.pid")).trim();
        assertTrue(!pid1.equals(pid2), "应替换为新进程：old=" + pid1 + " new=" + pid2);
        assertEquals("OK", httpGet("http://127.0.0.1:" + PORT + "/healthz"));

        // current / previous 指针应轮转
        assertEquals("sha-two-2222", Files.readString(ptr("current")).trim());
        assertEquals("sha-one-1111", Files.readString(ptr("previous")).trim());

        // 回滚：回到 sha-one-1111
        DeployResult rollback = deployer.deploy(new DeployRequest(PROJECT, "run-rb", "sha-three-3333",
                null, null, null, workspace.toString(), DeployTarget.HOST,
                PORT, PORT, "/healthz", null, true, null));
        assertTrue(rollback.success(), "回滚应成功：" + rollback.summary() + "\n" + rollback.log());
        assertEquals("sha-one-1111", rollback.resource(), "回滚应使用 previous 版本");
        assertEquals("OK", httpGet("http://127.0.0.1:" + PORT + "/healthz"));
        assertEquals("sha-one-1111", Files.readString(ptr("current")).trim());
    }

    @Test
    @DisplayName("无 jar → E-504（明确提示改用 DOCKER），不启动进程")
    void failsFastWithoutJar() throws Exception {
        Path empty = Files.createTempDirectory("host-empty");
        DeployResult result = deployer.deploy(new DeployRequest("host-empty", "run-x", "sha-x",
                null, null, null, empty.toString(), DeployTarget.HOST,
                PORT, PORT, "/healthz", null, false, null));
        assertEquals(false, result.success());
        assertEquals("E-504", result.errorCode());
        assertTrue(result.summary().contains("DOCKER"), result.summary());
    }

    private Path ptr(String name) {
        return workspace.resolve(".agent/deploy").resolve(PROJECT).resolve(name);
    }

    private DeployRequest request(String sha) {
        return new DeployRequest(PROJECT, "run-" + sha, sha, null, null, null,
                workspace.toString(), DeployTarget.HOST, PORT, PORT, "/healthz", null, false, null);
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
                // 进程尚未就绪，继续等
            }
            TimeUnit.MILLISECONDS.sleep(300);
        }
        return "";
    }

    private static void run(Path dir, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "命令超时：" + String.join(" ", argv));
        assertEquals(0, p.exitValue(), "命令失败：" + String.join(" ", argv) + "\n" + out);
    }
}
