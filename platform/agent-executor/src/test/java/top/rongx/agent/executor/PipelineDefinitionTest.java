package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    @DisplayName("forVerify：去掉部署/健康/回滚/deploy 动作与镜像，只留构建测试")
    void forVerifyKeepsBuildOnly() {
        PipelineDefinition def = new PipelineDefinition("release",
                List.of(
                        new PipelineDefinition.StageDef("build", "action", "backend_build", "container", null, null, null, 0),
                        new PipelineDefinition.StageDef("image", "action", "image_package", "container", null, null, null, 0),
                        new PipelineDefinition.StageDef("deploy", "deploy", null, "container", null, null, null, 0),
                        new PipelineDefinition.StageDef("health", "health", null, "container", null, null, null, 0),
                        new PipelineDefinition.StageDef("rollback", "rollback", null, "container", null, null, null, 0)),
                List.of(new PipelineDefinition.StageDef(
                        "rollback", "rollback", null, "container", null, null, null, 0)),
                null);

        PipelineDefinition verify = def.forVerify(false);

        assertEquals(1, verify.stages().size(), "只应保留 build");
        assertEquals("build", verify.stages().get(0).id());
        assertTrue(verify.onFailure().isEmpty(), "verify 不需要 on-failure 回滚");

        // 显式要求保留镜像时（API 参数 verifyImage=true）才留下 image stage
        assertEquals(2, def.forVerify(true).stages().size());
    }

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
        assertTrue(deploy.envRequired().isEmpty());
    }

    @Test
    @DisplayName("deploy.env-required：字符串与 name 映射两种写法都解析、去重、保序；非法名拒绝")
    void parsesEnvRequired(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  target: docker
                  env-required:
                    - DB_HOST
                    - name: REDIS_HOST
                    - DB_HOST
                stages:
                  - id: deploy
                    type: deploy
                """);

        PipelineDefinition.DeploySpec deploy = PipelineDefinition.load(repo).deploy();
        assertNotNull(deploy);
        assertEquals(java.util.List.of("DB_HOST", "REDIS_HOST"), deploy.envRequired());

        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  env-required:
                    - "bad-name"
                stages:
                  - id: deploy
                    type: deploy
                """);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PipelineDefinition.load(repo));
    }

    @Test
    @DisplayName("health.expected：2xx 解析为任意 2xx（0），非法写法 fail-fast")
    void parsesHealthExpected(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                stages:
                  - id: health
                    type: health
                    url: http://127.0.0.1:8080/actuator/health
                    expected: 2xx
                """);
        assertEquals(0, PipelineDefinition.load(repo).stages().get(0).expected());

        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                stages:
                  - id: health
                    type: health
                    expected: ok
                """);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PipelineDefinition.load(repo));
    }
}
