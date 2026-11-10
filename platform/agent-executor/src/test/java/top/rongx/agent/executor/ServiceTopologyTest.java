package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EnvAddressing;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.env.RuntimeMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行形态（dev/test/prod）与 test 多服务部署的拓扑约定。
 */
class ServiceTopologyTest {

    @Test
    @DisplayName("deploy.services：解析前后端两个容器及其端口/网络别名约定")
    void parsesServiceSet(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  target: docker
                  port: 8080
                  services:
                    - name: backend
                      image: demo-backend:1
                      containerPort: 8080
                      volume: /data
                    - name: frontend
                      image: demo-frontend:1
                      containerPort: 80
                stages:
                  - id: deploy
                    type: deploy
                """);

        PipelineDefinition.DeploySpec deploy = PipelineDefinition.load(repo).deploy();
        assertEquals(2, deploy.services().size());
        PipelineDefinition.ServiceSpec backend = deploy.services().get(0);
        assertEquals("backend", backend.name());
        assertEquals(8080, backend.containerPort());
        assertEquals("/data", backend.volumePath());
        assertEquals(0, backend.hostPort());
        assertEquals(0, PipelineDefinition.load(repo).deploy().services().get(1).hostPort());
        assertTrue(deploy.services().get(1).volumePath() == null);
    }

    @Test
    @DisplayName("deploy.services：服务名重复 / 缺镜像 / 非法端口都要在解析期拒绝")
    void rejectsInvalidServices(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  services:
                    - name: backend
                      image: a
                      containerPort: 8080
                    - name: backend
                      image: b
                      containerPort: 8080
                stages:
                  - id: deploy
                    type: deploy
                """);
        assertThrows(IllegalArgumentException.class, () -> PipelineDefinition.load(repo));

        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  services:
                    - name: frontend
                      containerPort: 80
                stages:
                  - id: deploy
                    type: deploy
                """);
        assertThrows(IllegalArgumentException.class, () -> PipelineDefinition.load(repo));

        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  services:
                    - name: frontend
                      image: a
                      containerPort: 0
                stages:
                  - id: deploy
                    type: deploy
                """);
        assertThrows(IllegalArgumentException.class, () -> PipelineDefinition.load(repo));
    }

    @Test
    @DisplayName("寻址：dev=宿主机发布端口，test=容器名+容器内端口，prod=服务器地址+发布端口")
    void addressesByRuntimeMode() {
        EnvResource raw = new EnvResource("id", "dev", "mysql", "agent-dev-mysql",
                "127.0.0.1", 13306, 3306, "agent-dev-mysql",
                "shop", "root", "secret", "agent-dev-net", null, "LOCAL_DOCKER",
                true, 0, null);

        EnvResource dev = EnvAddressing.resolve(raw, RuntimeMode.LOCAL_DEV);
        assertEquals("127.0.0.1", dev.host());
        assertEquals(13306, dev.port());

        EnvResource test = EnvAddressing.resolve(raw, RuntimeMode.LOCAL_TEST);
        assertEquals("agent-dev-mysql", test.host());
        assertEquals(3306, test.port());

        EnvResource prod = EnvAddressing.resolve(raw, RuntimeMode.SERVER);
        assertEquals("127.0.0.1", prod.host());
        assertEquals(13306, prod.port());
    }

    @Test
    @DisplayName("隔离命名：容器名 / 网络别名 / 数据卷按命名空间与服务区分")
    void namesAreNamespaced() {
        assertEquals("agent-test-demo-backend",
                DockerDeployer.serviceContainerName("agent-test", "Demo", "backend"));
        assertEquals("demo-backend", DockerDeployer.serviceAlias("Demo", "backend"));
        assertEquals("agent-test-demo-backend-data",
                DockerDeployer.volumeName("agent-test", "Demo", "backend"));
        assertEquals("agent-deploy-demo", DockerDeployer.containerName("Demo"));
    }

    @Test
    @DisplayName("模板形态：空值按环境推导，命名空间默认 agent-<env>")
    void templateDefaults() {
        assertEquals(RuntimeMode.LOCAL_DEV, new EnvTemplate("dev", null, "host", 8080,
                "/healthz", 0).mode());
        assertEquals(RuntimeMode.LOCAL_TEST, new EnvTemplate("test", null, "docker", 8080,
                "/healthz", 0).mode());
        assertEquals(RuntimeMode.SERVER, new EnvTemplate("prod", null, "server", 8080,
                "/healthz", 0).mode());
        assertEquals("agent-dev", new EnvTemplate("dev", null, "host", 8080,
                "/healthz", 0).namespace());
        assertTrue(new EnvTemplate("dev", null, "host", 8080, "/healthz", 0).mode().localProcess());
        assertTrue(new EnvTemplate("test", null, "docker", 8080, "/healthz", 0)
                .mode().localDocker());
    }
}
