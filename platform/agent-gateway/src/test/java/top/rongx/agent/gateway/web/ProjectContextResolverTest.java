package top.rongx.agent.gateway.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.gateway.auth.UserStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 项目上下文解析：users.projects 项目级校验、只读角色标记、工作树逃逸拒绝。
 *
 * <p>用真实的 SQLite UserStore（临时目录）+ 内存项目仓库，验证的是接线而不是 mock 行为。
 */
class ProjectContextResolverTest {

    @TempDir
    Path workspaceRoot;

    private ProjectContextResolver resolver;

    @BeforeEach
    void setUp() {
        UserStore users = new UserStore(workspaceRoot.toString(),
                "admin=admin-pass-1,devops=devops-pass-1,viewer=viewer-pass-1");
        resolver = new ProjectContextResolver(new FakeProjects(), users);
    }

    @Test
    @DisplayName("ADMIN 可访问任意已存在项目")
    void adminCanAccessAllProjects() {
        ProjectContext ctx = resolver.resolve("RY", "test", "admin");
        assertEquals("RY", ctx.projectId());
        assertFalse(ctx.readOnly());
        ProjectContext other = resolver.resolve("NEW", "dev", "admin");
        assertEquals("NEW", other.projectId());
    }

    @Test
    @DisplayName("projects=[RY] 的用户可访问成员项目 RY")
    void memberCanAccessOwnProject() {
        ProjectContext ctx = resolver.resolve("RY", "test", "devops");
        assertEquals("RY", ctx.projectId());
        assertFalse(ctx.readOnly());
    }

    @Test
    @DisplayName("projects=[RY] 的用户访问其它项目被拒绝")
    void nonMemberProjectRejected() {
        PlatformException ex = assertThrows(PlatformException.class,
                () -> resolver.resolve("NEW", "dev", "devops"));
        assertTrue(ex.getMessage().contains("无权访问该项目"), ex.getMessage());
        assertEquals("E-390", ex.code());
    }

    @Test
    @DisplayName("VIEWER 访问非成员项目同样被拒绝（只读不豁免 RBAC）")
    void viewerNonMemberProjectRejected() {
        PlatformException ex = assertThrows(PlatformException.class,
                () -> resolver.resolve("NEW", "dev", "viewer"));
        assertTrue(ex.getMessage().contains("无权访问该项目"), ex.getMessage());
    }

    @Test
    @DisplayName("projects=[*] 的用户可访问任意已接入项目")
    void allProjectsWildcardGrantsAccess() {
        assertEquals("RY", resolver.resolve("RY", "test", "admin").projectId());
        assertEquals("NEW", resolver.resolve("NEW", "dev", "admin").projectId());
    }

    @Test
    @DisplayName("已登录用户可访问项目；VIEWER 上下文标记只读")
    void memberAccessAndViewerReadOnly() {
        assertFalse(resolver.resolve("RY", "test", "devops").readOnly());

        ProjectContext viewer = resolver.resolve("RY", "test", "viewer");
        assertTrue(viewer.readOnly(), "VIEWER 必须是只读上下文");
    }

    @Test
    @DisplayName("未知用户不能构造上下文")
    void unknownUserRejected() {
        assertThrows(PlatformException.class,
                () -> resolver.resolve("RY", "test", "ghost"));
    }

    @Test
    @DisplayName("环境必须属于项目")
    void unknownEnvRejected() {
        assertThrows(PlatformException.class,
                () -> resolver.resolve("RY", "staging", "devops"));
    }

    @Test
    @DisplayName("工作树路径逃逸在进入工具层前被拒绝")
    void worktreeEscapeRejected() {
        PlatformException ex = assertThrows(PlatformException.class,
                () -> resolver.resolve("RY", "test", "devops", "../../../etc"));
        assertTrue(ex.getMessage().contains("工作树名不合法"), ex.getMessage());
    }

    /** 两个项目：RY 是 seed 用户的成员项目，NEW 只有 ADMIN 可访问。 */
    private static final class FakeProjects implements ProjectRepository {

        @Override
        public Optional<Project> findById(String projectId) {
            return switch (projectId) {
                case "RY" -> Optional.of(project("RY"));
                case "NEW" -> Optional.of(project("NEW"));
                default -> Optional.empty();
            };
        }

        @Override
        public List<Project> findAll() {
            return List.of(project("RY"), project("NEW"));
        }

        @Override
        public List<ProjectEnv> findEnvs(String projectId) {
            return "RY".equals(projectId)
                    ? List.of(new ProjectEnv("RY", "dev", null, null),
                            new ProjectEnv("RY", "test", null, null),
                            new ProjectEnv("RY", "prod", null, null))
                    : List.of(new ProjectEnv("NEW", "dev", null, null));
        }

        private static Project project(String id) {
            return new Project(id, id, "https://example.com/" + id + ".git", "local",
                    "generic-java", 8100, 8199);
        }
    }
}
