package top.rongx.agent.infra.git;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 GitHub/GitLab token 注入路径：
 * 私有仓库走 HTTPS Basic 认证，凭据应通过 askpass 送到 git（不进命令行）。
 */
class GitAuthInjectionTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("ls-remote 遇到 401 时通过 askpass 注入用户名与 token")
    void askpassInjectsBasicAuth() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) {
                authorization.set(header);
            }
            // 第一次无凭据 → 401（带 WWW-Authenticate 才会触发 git 的凭据回调）
            if (header == null) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
                exchange.sendResponseHeaders(401, -1);
            } else {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/acme/demo.git";
            GitWorktreeManager git = new GitWorktreeManager(workspace);
            assertThrows(GitWorktreeManager.GitIntakeException.class, () -> git.lsRemote(
                    url, new GitWorktreeManager.GitAuth("x-access-token", "ghp_test_token")));

            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    "x-access-token:ghp_test_token".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authorization.get(), "askpass 应送出 Basic 凭据");
        } finally {
            server.stop(0);
        }
    }
}
