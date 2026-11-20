package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.runtime.RuntimeInspector;
import top.rongx.agent.domain.runtime.RuntimeInspector.ContainerStatus;
import top.rongx.agent.gateway.web.DeploymentControlService.Cell;
import top.rongx.agent.gateway.web.DeploymentControlService.DeploymentLogs;
import top.rongx.agent.gateway.web.DeploymentControlService.Matrix;
import top.rongx.agent.domain.error.PlatformException;

/** 环境矩阵只做「读部署事实 + 校验」：不启动真实进程/容器。 */
class DeploymentControlServiceTest {

    @TempDir
    Path root;

    private DeploymentControlService service(RuntimeInspector inspector) {
        return new DeploymentControlService(inspector, mock(EnvironmentStore.class),
                mock(top.rongx.agent.domain.settings.DeployServerRepository.class),
                mock(top.rongx.agent.domain.settings.SshAccess.class), root.toString());
    }

    private DeploymentControlService service(RuntimeInspector inspector, EnvironmentStore store) {
        return new DeploymentControlService(inspector, store,
                mock(top.rongx.agent.domain.settings.DeployServerRepository.class),
                mock(top.rongx.agent.domain.settings.SshAccess.class), root.toString());
    }

    @Test
    @DisplayName("HOST 部署目录 → 版本出现在对应环境，当前版本标记正确")
    void hostReleasesAppearInMatrix() throws IOException {
        Path state = root.resolve("_platform").resolve("deploy").resolve("proj-dev");
        Path release = state.resolve("releases").resolve("abc1234");
        Files.createDirectories(release);
        Files.writeString(release.resolve("app.jar"), "jar", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("current"), "abc1234", StandardCharsets.UTF_8);

        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());

        Matrix matrix = service(inspector).matrix("proj");

        assertEquals(List.of("abc1234"), matrix.versions());
        assertEquals(1, matrix.cells().size());
        Cell cell = matrix.cells().get(0);
        assertEquals("dev", cell.env());
        assertEquals("HOST", cell.target());
        assertTrue(cell.current());
        assertFalse(cell.running());
        assertTrue(matrix.dockerAvailable());
    }

    @Test
    @DisplayName("Docker 容器按 agent.sha 聚合成版本；任一容器运行即视为运行中")
    void dockerContainersGroupedByVersion() throws IOException {
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());
        when(inspector.containers("proj", "test")).thenReturn(List.of(
                container("proj-test-backend", "test", "backend", true, "sha9", "app-backend:1"),
                container("proj-test-frontend", "test", "frontend", false, "sha9", "app-web:1")));

        Matrix matrix = service(inspector).matrix("proj");

        assertEquals(List.of("sha9"), matrix.versions());
        Cell cell = matrix.cells().get(0);
        assertEquals("test", cell.env());
        assertEquals("DOCKER", cell.target());
        assertTrue(cell.running());
        assertEquals(2, cell.containers().size());
    }

    @Test
    @DisplayName("打 tag 的 HOST 版本槽独立展示：默认部署与 versions/<版本号> 并存")
    void taggedHostSlotsAppearSideBySide() throws IOException {
        Path base = root.resolve("_platform").resolve("deploy").resolve("proj-test");
        Files.createDirectories(base.resolve("releases").resolve("aaa1111"));
        Files.writeString(base.resolve("current"), "aaa1111", StandardCharsets.UTF_8);
        Files.writeString(base.resolve("version"), "latest", StandardCharsets.UTF_8);

        Path tagged = base.resolve("versions").resolve("v2.0.0");
        Files.createDirectories(tagged.resolve("releases").resolve("bbb2222"));
        Files.writeString(tagged.resolve("current"), "bbb2222", StandardCharsets.UTF_8);
        Files.writeString(tagged.resolve("version"), "v2.0.0", StandardCharsets.UTF_8);

        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());

        Matrix matrix = service(inspector).matrix("proj");

        assertTrue(matrix.versions().contains("latest"), matrix.versions().toString());
        assertTrue(matrix.versions().contains("v2.0.0"), matrix.versions().toString());
        assertEquals(2, matrix.cells().stream().filter(c -> "test".equals(c.env())).count());
    }

    @Test
    @DisplayName("Docker 容器按 agent.version 标签分版本：同一环境可同时运行多个版本")
    void dockerContainersGroupedByVersionLabel() {
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());
        when(inspector.containers("proj", "test")).thenReturn(List.of(
                container("agent-deploy-proj-test", "test", "app", true, "sha1", "v1.0.0", "img:1"),
                container("agent-deploy-proj-test-v2.0.0", "test", "app", true, "sha2", "v2.0.0", "img:2")));

        Matrix matrix = service(inspector).matrix("proj");

        assertEquals(2, matrix.versions().size(), matrix.versions().toString());
        assertTrue(matrix.versions().contains("v1.0.0"));
        assertTrue(matrix.versions().contains("v2.0.0"));
        assertEquals(2, matrix.cells().stream().filter(c -> "test".equals(c.env())).count());
    }

    @Test
    @DisplayName("Docker 不可用：仍返回 HOST 数据，并给出提示")
    void dockerUnavailableStillReturnsHost() throws IOException {
        Path state = root.resolve("_platform").resolve("deploy").resolve("proj-prod");
        Files.createDirectories(state.resolve("releases").resolve("v1"));
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString()))
                .thenThrow(new IllegalStateException("docker 未启动"));

        Matrix matrix = service(inspector).matrix("proj");

        assertFalse(matrix.dockerAvailable());
        assertFalse(matrix.note().isBlank());
        assertEquals(List.of("v1"), matrix.versions());
    }

    @Test
    @DisplayName("非法版本标识直接拒绝，避免路径穿越")
    void rejectsUnsafeVersion() {
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        DeploymentControlService service = service(inspector);
        assertThrows(PlatformException.class,
                () -> service.start("proj", "dev", "../../etc/passwd"));
        assertThrows(PlatformException.class,
                () -> service.stop("proj", "uat", null));
    }

    @Test
    @DisplayName("端口优先读 port 文件（流水线分配），并回填访问地址与发布提交")
    void portFileDrivesEndpoint() throws IOException {
        Path state = root.resolve("_platform").resolve("deploy").resolve("proj-dev");
        Path release = state.resolve("releases").resolve("abc1234");
        Files.createDirectories(release);
        Files.writeString(release.resolve("app.jar"), "jar", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("current"), "abc1234", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("port"), "18081", StandardCharsets.UTF_8);

        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());

        Cell cell = service(inspector).matrix("proj").cells().get(0);

        assertEquals("127.0.0.1:18081", cell.ports());
        assertEquals("http://127.0.0.1:18081", cell.url());
        assertEquals("abc1234", cell.release());
    }

    @Test
    @DisplayName("HOST 日志：读 app.log 尾部并脱敏")
    void hostLogsAreTailAndRedacted() throws IOException {
        Path state = root.resolve("_platform").resolve("deploy").resolve("proj-dev");
        Files.createDirectories(state.resolve("releases").resolve("abc1234"));
        Files.writeString(state.resolve("current"), "abc1234", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("app.log"),
                "boot line\npassword=supersecret123\nready\n", StandardCharsets.UTF_8);

        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());

        DeploymentLogs logs = service(inspector).logs("proj", "dev", null, null, 2, 60);

        assertEquals("HOST", logs.source());
        assertEquals(2, logs.lines().size(), logs.lines().toString());
        assertTrue(logs.lines().get(1).contains("ready"));
        assertTrue(logs.lines().stream().noneMatch(line -> line.contains("supersecret123")),
                logs.lines().toString());
    }

    @Test
    @DisplayName("旧部署没有 port 文件时用端口租约回填访问地址")
    void leasePortFillsEndpoint() throws IOException {
        Path state = root.resolve("_platform").resolve("deploy").resolve("proj-dev");
        Path release = state.resolve("releases").resolve("abc1234");
        Files.createDirectories(release);
        Files.writeString(release.resolve("app.jar"), "jar", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("current"), "abc1234", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("version"), "latest", StandardCharsets.UTF_8);

        EnvironmentStore store = mock(EnvironmentStore.class);
        when(store.leases("proj", "dev")).thenReturn(List.of(
                new PortLease("proj", "dev", 8080, 18090, "run-1", 1_000L)));
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());

        Cell cell = service(inspector, store).matrix("proj").cells().get(0);

        assertEquals("127.0.0.1:18090", cell.ports());
        assertEquals("http://127.0.0.1:18090", cell.url());
    }

    @Test
    @DisplayName("日志里的应用监听端口优先于端口租约（租约可能是旧的）")
    void appLogPortBeatsLease() throws IOException {
        Path state = root.resolve("_platform").resolve("deploy").resolve("proj-prod");
        Files.createDirectories(state.resolve("releases").resolve("abc1234"));
        Files.writeString(state.resolve("current"), "abc1234", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("app.log"),
                "Tomcat started on port 18095 (http) with context path '/'\n",
                StandardCharsets.UTF_8);

        EnvironmentStore store = mock(EnvironmentStore.class);
        when(store.leases("proj", "prod")).thenReturn(List.of(
                new PortLease("proj", "prod", 8080, 18090, "run-old", 1_000L)));
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());

        Cell cell = service(inspector, store).matrix("proj").cells().get(0);

        assertEquals("127.0.0.1:18095", cell.ports());
    }

    @Test
    @DisplayName("Docker 日志：按版本挑选运行中容器，拒绝不属于该环境的容器名")
    void dockerLogsPicksRunningContainer() {
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        when(inspector.containers(anyString(), anyString())).thenReturn(List.of());
        when(inspector.containers("proj", "test")).thenReturn(List.of(
                container("proj-test-backend", "test", "backend", false, "sha9", "app-backend:1"),
                container("proj-test-frontend", "test", "frontend", true, "sha9", "app-web:1")));
        when(inspector.logs(eq("proj-test-frontend"), anyInt())).thenReturn(List.of("fe ready"));

        DeploymentControlService service = service(inspector);
        DeploymentLogs logs = service.logs("proj", "test", "sha9", null, 50, 60);

        assertEquals("DOCKER", logs.source());
        assertEquals("proj-test-frontend", logs.container());
        assertEquals(List.of("proj-test-backend", "proj-test-frontend"), logs.containers());
        assertEquals(List.of("fe ready"), logs.lines());
        assertThrows(PlatformException.class,
                () -> service.logs("proj", "test", "sha9", "another-container", 50, 60));
    }

    @Test
    @DisplayName("远程 SERVER 容器 → 出现在 prod 对应版本，并带服务器名与访问地址")
    void remoteServerContainersAppearInMatrix() {
        RuntimeInspector inspector = mock(RuntimeInspector.class);
        top.rongx.agent.domain.settings.DeployServerRepository servers =
                mock(top.rongx.agent.domain.settings.DeployServerRepository.class);
        top.rongx.agent.domain.settings.DeployServer server =
                new top.rongx.agent.domain.settings.DeployServer("s1", "生产一号", "10.0.0.9", 22,
                        "deploy", "KEY", "/k", "/opt/agent/deploy", true, true,
                        false, null, null, null, null, "t", 1L);
        when(servers.findAll()).thenReturn(List.of(server));
        when(servers.secretOf("s1")).thenReturn(java.util.Optional.empty());
        top.rongx.agent.domain.settings.SshAccess ssh =
                mock(top.rongx.agent.domain.settings.SshAccess.class);
        when(ssh.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(top.rongx.agent.domain.settings.SshAccess.Result.ok(
                        "agent-deploy-proj-prod-1|ci-prod-abc1234|Up 2 hours|"
                                + "0.0.0.0:18080->8080/tcp||0.1.0|abc1234def||"
                                + "2026-11-20 10:00:00 +0800 CST"));

        DeploymentControlService service = new DeploymentControlService(inspector,
                mock(EnvironmentStore.class), servers, ssh, root.toString());
        Matrix matrix = service.matrix("proj");

        Cell cell = matrix.cells().stream()
                .filter(c -> "prod".equals(c.env()) && "0.1.0".equals(c.version()))
                .findFirst().orElseThrow();
        assertEquals("生产一号", cell.server());
        assertTrue(cell.running());
        assertTrue(cell.containers().contains("agent-deploy-proj-prod-1"));
        assertEquals("http://10.0.0.9:18080", cell.url());
    }

    private static ContainerStatus container(String name, String env, String service,
                                             boolean running, String sha, String image) {
        return container(name, env, service, running, sha, sha, image);
    }

    private static ContainerStatus container(String name, String env, String service,
                                             boolean running, String sha, String version,
                                             String image) {
        return new ContainerStatus(name, "proj", env, service,
                running ? "running" : "exited", running, 0, "2026-11-16T00:00:00Z",
                image, image + "-id", "healthy", running ? 0 : 1,
                "8080/tcp → 0.0.0.0:18080", "run-1", sha, version, "agent-" + env);
    }
}
