package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pipeline.yml v3 解析测试：deploy 段（方案 C）必须被正确读出。
 */
class PipelineDefinitionTest {

    @Test
    @DisplayName("deploy 段：target/port/containerPort/healthPath/artifact 全部解析")
    void parsesDeploySection(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  target: host
                  port: 9091
                  containerPort: 9090
                  healthPath: /ready
                  artifact: backend/target/app.jar
                stages:
                  - id: build
                    type: action
                    action: backend_build
                  - id: deploy
                    type: deploy
                on-failure:
                  - id: rollback
                    type: rollback
                """);

        PipelineDefinition def = PipelineDefinition.load(repo);

        assertEquals("release", def.name());
        assertEquals(2, def.stages().size());
        assertEquals("deploy", def.stages().get(1).type());
        assertEquals(1, def.onFailure().size());
        assertEquals("rollback", def.onFailure().get(0).type());

        PipelineDefinition.DeploySpec deploy = def.deploy();
        assertNotNull(deploy);
        assertEquals("host", deploy.target());
        assertEquals(9091, deploy.port());
        assertEquals(9090, deploy.containerPort());
        assertEquals("/ready", deploy.healthPath());
        assertEquals("backend/target/app.jar", deploy.artifact());
    }

    @Test
    @DisplayName("无 deploy 段 → null（纯构建流水线；引擎会以 E-506 拒绝 deploy stage）")
    void deployIsOptional(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: verify-only
                stages:
                  - id: build
                    type: action
                    action: backend_build
                """);

        PipelineDefinition def = PipelineDefinition.load(repo);
        assertNull(def.deploy());
    }

    @Test
    @DisplayName("deploy 段缺字段时用默认值（target=docker / port=8080）")
    void deployDefaults(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy: {}
                stages:
                  - id: build
                    type: action
                    action: backend_build
                """);

        PipelineDefinition.DeploySpec deploy = PipelineDefinition.load(repo).deploy();
        assertNotNull(deploy);
        assertEquals("docker", deploy.target());
        assertEquals(8080, deploy.port());
        assertEquals(8080, deploy.containerPort());
        assertTrue(deploy.healthPath().startsWith("/"));
    }
}
