package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
import top.rongx.agent.domain.runtime.RuntimeInspector;
import top.rongx.agent.domain.runtime.RuntimeInspector.ContainerStatus;
import top.rongx.agent.gateway.web.DeploymentControlService.Cell;
import top.rongx.agent.gateway.web.DeploymentControlService.Matrix;

/** 环境矩阵只做「读部署事实 + 校验」：不启动真实进程/容器。 */
class DeploymentControlServiceTest {

    @TempDir
    Path root;

    private DeploymentControlService service(RuntimeInspector inspector) {
        return new DeploymentControlService(inspector, mock(EnvironmentStore.class), root.toString());
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
        assertThrows(IllegalArgumentException.class,
                () -> service.start("proj", "dev", "../../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> service.stop("proj", "uat", null));
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
