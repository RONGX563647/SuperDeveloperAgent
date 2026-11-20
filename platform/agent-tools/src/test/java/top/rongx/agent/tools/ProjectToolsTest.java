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
                new ProjectEnv(PROJECT, "test", "/opt/apps/shop"),
                new ProjectEnv(PROJECT, "prod", "/opt/apps/shop")
        ), EFFECTIVE_PORT).listEnvs(ctx());
        assertTrue(out.contains("composeDir=/opt/apps/shop"), out);
        assertTrue(out.contains("仅登记，未参与部署"), "必须标注 reserved：" + out);
        assertTrue(out.contains("修改它不会改变部署落点"), "要有不生效的解释：" + out);
    }

    @Test
    @DisplayName("同项目共用 composeDir 不再触发「互相覆盖」告警（部署从不读它）")
    void sharedComposeDirNoLongerWarns() {
        String out = tools(List.of(
                new ProjectEnv(PROJECT, "test", "/opt/apps/superdeveloperag"),
                new ProjectEnv(PROJECT, "prod", "/opt/apps/superdeveloperag")
        ), EFFECTIVE_PORT).listEnvs(ctx());
        assertFalse(out.contains("共用 composeDir"), out);
        assertFalse(out.contains("互相覆盖"), out);
    }

    @Test
    @DisplayName("list_envs 只报告生效地址与来源，不再输出已删除的登记地址/端口告警")
    void noRegisteredAddressInOutput() {
        String out = tools(List.of(
                new ProjectEnv(PROJECT, "test", null)
        ), EFFECTIVE_PORT).listEnvs(ctx());
        assertTrue(out.contains("effective=http://127.0.0.1:" + EFFECTIVE_PORT), out);
        assertFalse(out.contains("appUrl"), out);
        assertFalse(out.contains("登记地址端口"), out);
        assertTrue(out.contains("生效地址以端口租约/流水线为准"), out);
    }

    @Test
    @DisplayName("project_info：repo 未登记时不输出裸 null，明确标注本地仓库")
    void projectInfoNeverPrintsNullRepo() {
        ProjectRepository nullRepo = new ProjectRepository() {
            @Override
            public java.util.Optional<Project> findById(String projectId) {
                return java.util.Optional.of(new Project(PROJECT, "Shop", null,
                        "local", "generic-java", 8100, 8199));
            }

            @Override
            public List<Project> findAll() {
                return List.of();
            }

            @Override
            public List<ProjectEnv> findEnvs(String projectId) {
                return List.of();
            }
        };
        ProjectTools t = new ProjectTools(nullRepo,
                new EffectiveEndpointResolver(null, null, null));
        String out = t.projectInfo(ctx());
        assertFalse(out.contains("repo: null"), out);
        assertTrue(out.contains("未登记"), out);
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
