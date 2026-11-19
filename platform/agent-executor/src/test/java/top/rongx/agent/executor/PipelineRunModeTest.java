package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * 运行模式裁剪：触发与自动续跑必须得到同一份阶段计划。
 *
 * <p>回归场景：verify 运行失败后自动续跑，曾丢掉 verify 裁剪，
 * 变成完整 release（部署 + 固化配方），只读的检查构建污染了环境。
 */
class PipelineRunModeTest {

    private static PipelineDefinition definition(String target) {
        List<PipelineDefinition.StageDef> stages = List.of(
                new PipelineDefinition.StageDef("build", "action", "backend_build",
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("frontend", "action", "frontend_build",
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("image", "action", "image_package",
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("deploy", "deploy", null,
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("health", "health", null,
                        null, null, null, "http://127.0.0.1:8182/actuator/health", 200));
        return new PipelineDefinition("release", stages, List.of(),
                new PipelineDefinition.DeploySpec(target, 8182, 8090,
                        "/actuator/health", null, null, List.of()));
    }

    private static Set<String> actions(PipelineDefinition definition) {
        return definition.stages().stream()
                .map(PipelineDefinition.StageDef::action)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("verify：不部署、不打镜像")
    void verifyKeepsOnlyBuildStages() {
        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "verify", null, false, "host", definition("host"));

        assertEquals(Set.of("backend_build", "frontend_build"), actions(planned));
        assertNull(planned.deploy(), "verify 不应留下 deploy 声明");
        assertTrue(planned.stages().stream().noneMatch(s -> "deploy".equals(s.type())));
        assertTrue(planned.stages().stream().noneMatch(s -> "health".equals(s.type())));
    }

    @Test
    @DisplayName("verify + verifyImage：显式保留镜像打包")
    void verifyKeepsImageWhenRequested() {
        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "verify", null, true, "host", definition("host"));

        assertTrue(actions(planned).contains("image_package"));
        assertTrue(planned.stages().stream().noneMatch(s -> "deploy".equals(s.type())));
    }

    @Test
    @DisplayName("按版本部署：只跑 deploy + health，不构建")
    void deployImageKeepsDeployOnly() {
        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "release", "ci-test-4ac8ba6", false, "host", definition("host"));

        assertEquals(List.of("deploy", "health"),
                planned.stages().stream().map(PipelineDefinition.StageDef::type).toList());
    }

    @Test
    @DisplayName("docker 目标：保留完整阶段")
    void dockerTargetKeepsImage() {
        PipelineDefinition original = definition("docker");

        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "release", null, false, "docker", original);

        assertSame(original, planned, "无需裁剪时必须返回原定义，避免无谓复制");
        assertTrue(actions(planned).contains("image_package"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("stage.workdir 越出检出目录 → 拒绝（不能把宿主任意目录挂进容器）")
    void workdirMustStayInsideCheckout() {
        Path runDir = Path.of("E:/data/runs/run-1").toAbsolutePath().normalize();

        assertEquals(runDir, BuiltInPipelineEngine.resolveWorkdir(runDir, null));
        assertEquals(runDir, BuiltInPipelineEngine.resolveWorkdir(runDir, "  "));
        assertEquals(runDir.resolve("backend"),
                BuiltInPipelineEngine.resolveWorkdir(runDir, "backend"));
        // a/../b 规范化后仍在内部，允许
        assertEquals(runDir.resolve("b"),
                BuiltInPipelineEngine.resolveWorkdir(runDir, "a/../b"));

        // 上跳到检出目录之外：会把宿主任意目录挂成容器的 /ws
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, ".."));
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, "../../../../Windows"));
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, "backend/../../etc"));
    }

    @Test
    @DisplayName("绝对路径一律拒绝：Windows 盘符与 POSIX 绝对路径都要挡住")
    void absoluteWorkdirRejected() {
        Path runDir = Path.of("E:/data/runs/run-1").toAbsolutePath().normalize();

        // 关键：这条断言在 Linux CI 上曾经失手过。
        // "E:/Windows" 在 Windows 上是绝对路径，在 Linux 上却是一个普通相对路径，
        // 会被拼成 <runDir>/E:/Windows 而"通过"越界检查——于是同一份测试在两个平台结论不同。
        // 因此实现必须显式识别盘符形态，而不是只依赖 Path.isAbsolute()。
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, "E:/Windows"),
                "Windows 盘符路径必须在任何平台都被判为越界");
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, "C:\\Windows\\System32"),
                "Windows 反斜杠盘符路径同样要拒绝");
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, "/etc/passwd"),
                "POSIX 绝对路径必须拒绝");
        assertNull(BuiltInPipelineEngine.resolveWorkdir(runDir, "\\\\server\\share"),
                "UNC 路径必须拒绝");
    }

    @Test
    @DisplayName("actuator 返回 200 但 body=DOWN 不能算健康")
    void healthBodyRejectsDownStatus() {
        String url = "http://127.0.0.1:8183/actuator/health";
        assertTrue(BuiltInPipelineEngine.healthBodyOk(url,
                "{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}"));
        assertFalse(BuiltInPipelineEngine.healthBodyOk(url,
                "{ \"status\" : \"DOWN\" }"));
        assertFalse(BuiltInPipelineEngine.healthBodyOk(url,
                "{\"status\":\"OUT_OF_SERVICE\"}"));
    }

    @Test
    @DisplayName("非 actuator 地址不做 body 约束（可能是任意首页）")
    void healthBodyIgnoresNonActuator() {
        assertTrue(BuiltInPipelineEngine.healthBodyOk("http://127.0.0.1:8183/", "DOWN"));
    }

    @Test
    @DisplayName("首页 5xx：健康接口通过也不能算发布成功")
    void homepageServerErrorFailsHealth() throws Exception {
        int[] hits = {0};
        HttpServer server = homepageServer(hits, 500);
        try {
            String issue = BuiltInPipelineEngine.homepageIssue(HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/healthz");
            assertTrue(issue != null && issue.contains("500"), String.valueOf(issue));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("首页 404 视为没有前端（纯 API 工程），不判失败")
    void homepageMissingIsFine() throws Exception {
        int[] hits = {0};
        HttpServer server = homepageServer(hits, 404);
        try {
            assertNull(BuiltInPipelineEngine.homepageIssue(HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/healthz"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("探活地址本身就是首页时不再重复请求")
    void homepageSkippedWhenHealthUrlIsRoot() throws Exception {
        int[] hits = {0};
        HttpServer server = homepageServer(hits, 500);
        try {
            assertNull(BuiltInPipelineEngine.homepageIssue(HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertEquals(0, hits[0], "根路径就是探活地址，不应再发一次请求");
        } finally {
            server.stop(0);
        }
    }

    /** 探活地址固定返回 200；根路径按参数返回，并统计被访问次数。 */
    private static HttpServer homepageServer(int[] hits, int rootStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> respond(exchange, 200));
        server.createContext("/", exchange -> {
            hits[0]++;
            respond(exchange, rootStatus);
        });
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status)
            throws java.io.IOException {
        byte[] body = "OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
