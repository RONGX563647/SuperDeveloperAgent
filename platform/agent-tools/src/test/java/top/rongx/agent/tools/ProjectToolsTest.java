package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;

/**
 * BUG-45：composeDir 是幽灵字段——部署链路从不读取，list_envs 不应再把它
 * 当成冲突源报警，而要明确标注「仅登记未参与部署」。
 */
class ProjectToolsTest {

    private static final String PROJECT = "SHOP";
    private static final int EFFECTIVE_PORT = 18173;

    private static ProjectContext ctx() {
        return new ProjectContext(PROJECT, "test", "alice", null, null);
    }

    private static ProjectTools tools(List<ProjectEnv> envs, int effectivePort) {
        EffectiveEndpointResolver resolver = new EffectiveEndpointResolver(null, null, null) {
            @Override
            public Endpoint resolve(String projectId, String env) {
                return new Endpoint(effectivePort, "/healthz", "端口租约（已部署）");
            }
        };
        return new ProjectTools(new FakeProjects(envs), resolver);
    }

    @Test
    @DisplayName("composeDir 在输出中标注「仅登记未参与部署」")
    void composeDirMarkedReserved() {
        String out = tools(List.of(
                new ProjectEnv(PROJECT, "test", "http://127.0.0.1:18173", "/opt/apps/shop"),
                new ProjectEnv(PROJECT, "prod", "http://127.0.0.1:18180", "/opt/apps/shop")
        ), EFFECTIVE_PORT).listEnvs(ctx());
        assertTrue(out.contains("composeDir=/opt/apps/shop"), out);
        assertTrue(out.contains("仅登记，未参与部署"), "必须标注 reserved：" + out);
        assertTrue(out.contains("修改它不会改变部署落点"), "要有不生效的解释：" + out);
    }

    @Test
    @DisplayName("同项目共用 composeDir 不再触发「互相覆盖」告警（部署从不读它）")
    void sharedComposeDirNoLongerWarns() {
        String out = tools(List.of(
                new ProjectEnv(PROJECT, "test", "", "/opt/apps/superdeveloperag"),
                new ProjectEnv(PROJECT, "prod", "", "/opt/apps/superdeveloperag")
        ), EFFECTIVE_PORT).listEnvs(ctx());
        assertFalse(out.contains("共用 composeDir"), out);
        assertFalse(out.contains("互相覆盖"), out);
    }

    @Test
    @DisplayName("登记端口 ≠ 生效端口：提示改为「真实端口以租约为准」，不再让人去改登记地址")
    void mismatchWarningPointsAtLease() {
        String out = tools(List.of(
                new ProjectEnv(PROJECT, "test", "http://127.0.0.1:8180", null)
        ), EFFECTIVE_PORT).listEnvs(ctx());
        assertTrue(out.contains("真实端口以租约为准"), out);
        assertFalse(out.contains("以 effective 为准"), out);
    }

    private record FakeProjects(List<ProjectEnv> envs) implements ProjectRepository {
        @Override
        public java.util.Optional<Project> findById(String projectId) {
            return java.util.Optional.of(new Project(PROJECT, "Shop",
                    "https://example.com/shop.git", "local", "generic-java", 8100, 8199));
        }

        @Override
        public List<Project> findAll() {
            return List.of();
        }

        @Override
        public List<ProjectEnv> findEnvs(String projectId) {
            return envs;
        }
    }
}
