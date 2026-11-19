package top.rongx.agent.executor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

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
// 慢测试：真实进程 / 真实 git / Spring 上下文 / BCrypt 迭代，提交时跳过（pre-commit 加 -DexcludedGroups=slow），推送与 CI 全跑。
@Tag("slow")
class HostDeployerTest {

    private static final String PROJECT = "host-e2e";
    private static final int PORT = 18096;

    private final HostDeployer deployer = new HostDeployer();

    /**
     * 不用 @TempDir：真实子进程持有 app.log / app.jar 句柄，Windows 上 JUnit 自动清理
     * 会因"文件被占用"把通过的测试判成失败。改为手动创建，清理时先杀进程再尽力删除。
     */
    static Path workspace;

    @BeforeAll
    static void buildJar() throws Exception {
        workspace = Files.createTempDirectory("host-e2e-");
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
                kill(Long.parseLong(Files.readString(pid).trim()));
            }
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
        // 兜底：重启/回滚可能留下命令行里带本临时目录的旧进程
        ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine()
                        .map(line -> line.contains(workspace.toString())).orElse(false))
                .forEach(handle -> kill(handle.pid()));
        deleteQuietly(workspace);
    }

    private static void kill(long id) {
        ProcessHandle.of(id).ifPresent(handle -> {
            handle.destroyForcibly();
            try {
                handle.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 进程可能已退出
            }
        });
    }

    /** 尽力删除临时目录：句柄释放有延迟时重试，删不掉也不影响测试结论。 */
    private static void deleteQuietly(Path root) {
        for (int i = 0; i < 10; i++) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 仍被占用，交给下一轮重试
                    }
                });
            } catch (IOException ignored) {
                // 目录可能已经被删
            }
            if (!Files.exists(root)) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
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

    @Test
    @DisplayName("pid 文件丢失时仍能按槽内登记回收遗留进程，且不误杀其它槽")
    void stopsRecordedOrphansOfSameSlotOnly() throws Exception {
        Path slot = Files.createTempDirectory("host-slot-");
        Files.createDirectories(slot.resolve("releases"));
        Path otherSlot = Files.createTempDirectory("host-slot-other-");
        Files.createDirectories(otherSlot.resolve("releases"));

        Process orphan = sleepy();
        Process other = sleepy();
        try {
            deployer.recordProcess(slot, orphan.toHandle());
            deployer.recordProcess(otherSlot, other.toHandle());
            StringBuilder log = new StringBuilder();
            deployer.stopRecorded(slot, log);

            assertTrue(orphan.waitFor(10, TimeUnit.SECONDS), "本槽遗留进程应被回收");
            assertTrue(log.toString().contains("stopped orphan pid=" + orphan.pid()), log.toString());
            assertTrue(other.isAlive(), "其它版本槽的进程不能被误杀");
        } finally {
            kill(orphan.pid());
            kill(other.pid());
            deleteQuietly(slot);
            deleteQuietly(otherSlot);
        }
    }

    /** 起一个长时间运行的进程，模拟平台此前拉起的 java -jar。 */
    private static Process sleepy() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 60")
                : new ProcessBuilder("sleep", "60");
        return builder.redirectErrorStream(true).start();
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
