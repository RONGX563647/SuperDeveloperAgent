package top.rongx.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PlatformException;

/** 平台侧流水线部署段：端口 / 健康检查的读改写不能破坏其它阶段。 */
class DeployToolsConfigTest {

    private static final String YAML = """
            name: release
            deploy:
              target: host
              port: 8090
              containerPort: 8090
              healthPath: /actuator/health
            stages:
              - id: build
                type: action
                action: backend_build
                runner: container
                workdir: platform
              - id: deploy
                type: deploy
              - id: health
                type: health
                url: http://127.0.0.1:8090/actuator/health
                expected: 2xx
            """;

    @Test
    @DisplayName("改端口与健康路径：deploy 与 health.url 同步更新，阶段顺序不变")
    void rewritesPortAndHealthUrl() {
        String updated = DeployTools.applyDeployConfig(YAML, null, 8180, null,
                "/actuator/health", null);
        Map<?, ?> root = (Map<?, ?>) new Yaml().load(updated);
        Map<?, ?> deploy = (Map<?, ?>) root.get("deploy");
        assertEquals(8180, deploy.get("port"));
        assertEquals(8180, deploy.get("containerPort"));
        assertEquals("/actuator/health", deploy.get("healthPath"));

        List<?> stages = (List<?>) root.get("stages");
        assertEquals(List.of("build", "deploy", "health"),
                stages.stream().map(s -> ((Map<?, ?>) s).get("id")).toList());
        Map<?, ?> health = (Map<?, ?>) stages.get(2);
        assertEquals("http://127.0.0.1:8180/actuator/health", health.get("url"));
    }

    @Test
    @DisplayName("只改 target：保留原有 health.url（可能指向远程服务器）")
    void targetOnlyKeepsUrl() {
        String updated = DeployTools.applyDeployConfig(YAML, "server", null, null, null, null);
        Map<?, ?> root = (Map<?, ?>) new Yaml().load(updated);
        assertEquals("server", ((Map<?, ?>) root.get("deploy")).get("target"));
        Map<?, ?> health = (Map<?, ?>) ((List<?>) root.get("stages")).get(2);
        assertEquals("http://127.0.0.1:8090/actuator/health", health.get("url"));
    }

    @Test
    @DisplayName("单容器 volume：可写入，也可用空字符串移除")
    void setsAndRemovesVolume() {
        String withVolume = DeployTools.applyDeployConfig(YAML, null, null, null, null, null,
                "/app/data");
        Map<?, ?> deploy = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(withVolume)).get("deploy");
        assertEquals("/app/data", deploy.get("volume"));

        String removed = DeployTools.applyDeployConfig(withVolume, null, null, null, null, null, "");
        Map<?, ?> deployAfter = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(removed)).get("deploy");
        assertTrue(deployAfter == null || !deployAfter.containsKey("volume"));
    }

    @Test
    @DisplayName("HOST 部署状态目录：<deployRoot>/<project>-<env>，与 HostDeployer 的命名一致")
    void hostStateDirFollowsHostDeployerLayout() {
        java.nio.file.Path root = java.nio.file.Path.of("data", "workspaces", "_platform", "deploy");
        assertEquals(root.resolve("SUPERDEVELOPERAG-test"),
                DeployTools.hostStateDir(root, "SUPERDEVELOPERAG", "test"));
        assertEquals(root.resolve("RY-dev"), DeployTools.hostStateDir(root, "RY", "dev"));
    }

    @Test
    @DisplayName("非法 YAML：明确报错而不是写坏定义")
    void invalidYamlFails() {
        PlatformException error = assertThrows(PlatformException.class,
                () -> DeployTools.applyDeployConfig("name: [unclosed", null, 8180, null, null, null));
        assertTrue(error.getMessage().contains("合法 YAML"), error.getMessage());
    }
}
