package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.auth.UserStore;
import top.rongx.agent.tools.ConfigResolver;

/**
 * BUG-27 入口5收敛：配置解析接口的环境校验必须走 {@link ProjectContextResolver}
 *（环境必须属于项目），不再自行 {@code new ProjectContext} 绕过校验。
 */
class ConfigControllerTest {

    @Test
    void unknownEnvIsRejectedByResolver() {
        ProjectRepository projects = new FakeProjects();
        UserStore users = new UserStore(tempDir(), "admin=admin-pass-1");
        ProjectContextResolver resolver = new ProjectContextResolver(projects, users);
        CurrentUser currentUser = new CurrentUser() {
            @Override
            public String requireUserId() {
                return "admin";
            }

            @Override
            public void checkPermission(String permission) {
            }
        };
        ConfigResolver configResolver = mock(ConfigResolver.class);
        when(configResolver.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("test"),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new ConfigResolver.Resolution("RY", "test", Map.of(), Map.of(),
                        List.of(), Map.of(), List.of(), 0, false));
        ConfigController controller = new ConfigController(configResolver,
                resolver, currentUser);

        // prod 不属于该项目（项目只登记 dev/test）：必须被 resolver 拦下，
        // 而不是自行 new ProjectContext 绕过校验
        PlatformException error = assertThrows(PlatformException.class,
                () -> controller.resolve("RY", "prod", null));
        assertEquals("环境不属于该项目: prod", error.getMessage());

        // 合法环境正常构造上下文（ConfigResolver 被 mock，返回体不重要）
        Map<String, Object> out = controller.resolve("RY", "test", null);
        assertEquals("RY", out.get("projectId"));
        assertEquals("test", out.get("env"));
    }

    private static String tempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("cfg-ctrl").toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeProjects implements ProjectRepository {
        @Override
        public Optional<Project> findById(String projectId) {
            return "RY".equals(projectId)
                    ? Optional.of(new Project("RY", "RY", "https://example.com/RY.git",
                    "local", "generic-java", 8100, 8199))
                    : Optional.empty();
        }

        @Override
        public List<Project> findAll() {
            return List.of();
        }

        @Override
        public List<ProjectEnv> findEnvs(String projectId) {
            return "RY".equals(projectId)
                    ? List.of(new ProjectEnv("RY", "dev", null, null),
                    new ProjectEnv("RY", "test", null, null))
                    : List.of();
        }
    }
}
