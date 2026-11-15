package top.rongx.agent.executor;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 中间件镜像不再写死：可覆盖、非法值明确报错、docker run 真的用覆盖后的镜像。 */
class DockerEnvProvisionerImageTest {

    @Test
    @DisplayName("覆盖解析支持逗号/分号、大小写 kind、私有 registry 与 tag")
    void parsesOverrides() {
        Map<String, String> images = DockerEnvProvisioner.parseImageOverrides(
                "mysql=registry.local/mysql:8.4; REDIS=redis:7.2-alpine");
        assertEquals("registry.local/mysql:8.4", images.get("mysql"));
        assertEquals("redis:7.2-alpine", images.get("redis"));
    }

    @Test
    @DisplayName("留空配置返回空覆盖，其余 kind 仍走默认标签")
    void blankOverridesAreEmpty() {
        assertTrue(DockerEnvProvisioner.parseImageOverrides(null).isEmpty());
        assertTrue(DockerEnvProvisioner.parseImageOverrides("   ").isEmpty());
    }

    @Test
    @DisplayName("未知 kind / 缺 = / 混入命令片段都明确报错，不静默忽略")
    void invalidOverridesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DockerEnvProvisioner.parseImageOverrides("oracle=oracle:19c"));
        assertThrows(IllegalArgumentException.class,
                () -> DockerEnvProvisioner.parseImageOverrides("mysql"));
        assertThrows(IllegalArgumentException.class,
                () -> DockerEnvProvisioner.parseImageOverrides("redis=redis:7 ; rm -rf /"));
    }

    @Test
    @DisplayName("imageOf：覆盖优先，未覆盖回退默认，未知 kind 返回空")
    void imageOfUsesOverridesFirst() {
        DockerEnvProvisioner provisioner = new DockerEnvProvisioner(null, null,
                Map.of("mysql", "registry.local/mysql:8.4"), true);
        assertEquals("registry.local/mysql:8.4", provisioner.imageOf("mysql"));
        assertEquals("redis:7-alpine", provisioner.imageOf("redis"));
        assertEquals("", provisioner.imageOf("unknown"));
    }

    @Test
    @DisplayName("docker run 使用覆盖后的镜像，argv 里不再出现写死的标签")
    void runArgvUsesConfiguredImage() {
        List<String> argv = DockerEnvProvisioner.runArgv("mysql", "env-mysql", 3306,
                "app", "root", "secret", "net",
                Map.of("mysql", "registry.local/mysql:8.4"));
        assertTrue(argv.contains("docker"));
        assertEquals("registry.local/mysql:8.4", argv.getLast());
        assertFalse(argv.contains("mysql:8.0"));
    }

    @Test
    @DisplayName("镜像非法时抛错，绝不静默去拉一个未知镜像")
    void runArgvRejectsInvalidImage() {
        assertThrows(IllegalArgumentException.class,
                () -> DockerEnvProvisioner.runArgv("mysql", "env-mysql", 3306,
                        "app", "root", "secret", "net", Map.of("mysql", "")));
    }
}
