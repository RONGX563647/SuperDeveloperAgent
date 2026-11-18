package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.error.RecipeException;

/**
 * 镜像引用的入口校验：不需要 Docker，纯逻辑。
 *
 * <p>与 {@link DockerDeployerTest} 分开的原因：那个类用 {@code @BeforeAll} + {@code Assumptions}
 * 依赖真实镜像，环境不全时整个类被跳过——安全校验的回归断言不能跟着一起被跳过。
 */
class ImageRefTest {

    private static final String PROJECT = "IMAGE-REF";

    @Test
    @DisplayName("合法镜像引用原样通过（含 registry 端口与 digest）")
    void acceptsValidRefs() {
        assertEquals("maven:3.9-eclipse-temurin-21",
                ImageRef.require("maven:3.9-eclipse-temurin-21", "t"));
        assertEquals("nginx", ImageRef.require("nginx", "t"));
        assertEquals("ghcr.io/acme/app:1.2.3",
                ImageRef.require("ghcr.io/acme/app:1.2.3", "t"));
        assertEquals("registry.local:5000/team/app:latest",
                ImageRef.require("registry.local:5000/team/app:latest", "t"));
        assertEquals("alpine@sha256:" + "a".repeat(64),
                ImageRef.require("alpine@sha256:" + "a".repeat(64), "t"));
        assertEquals("maven:3.9", ImageRef.require("  maven:3.9  ", "t"), "两端空白应被裁掉");
    }

    @Test
    @DisplayName("以 - 开头的镜像名必被拒：docker 会把它当成选项")
    void rejectsLeadingDash() {
        String digest = "sha256:" + "b".repeat(64);
        for (String bad : new String[]{
                "--privileged",
                "-v", "-v=/:/host",
                "--volume=/:/host",
                "--network=host",
                "--security-opt=seccomp=unconfined",
                "--entrypoint=/bin/sh"}) {
            assertThrowsRecipe(bad);
        }
        assertThrowsRecipe("-" + digest);
    }

    @Test
    @DisplayName("空、含空白、超长的镜像名被拒")
    void rejectsMalformed() {
        assertThrowsRecipe(null);
        assertThrowsRecipe("");
        assertThrowsRecipe("   ");
        assertThrowsRecipe("maven:3.9 mvn -v");
        assertThrowsRecipe("Maven:3.9");
        assertThrowsRecipe("maven:" + "x".repeat(300));
        assertThrowsRecipe("maven:3.9;rm -rf /");
        assertThrowsRecipe("maven:3.9\n--privileged");
    }

    @Test
    @DisplayName("沙箱与部署器都必须拒绝注入型镜像（同一校验点）")
    void bothEntryPointsReject() {
        assertThrows(RecipeException.class,
                () -> new DockerSandbox("docker", "--privileged", "none", "1g", "1", 128, "1000:1000"));
        DockerSandbox ok = new DockerSandbox("docker", "alpine:3.20", "none", "1g", "1", 128, "1000:1000");
        assertThrows(RecipeException.class, () -> ok.withImage("--volume=/:/host"));

        DeployResult deployed = new DockerDeployer().deploy(new DeployRequest(
                PROJECT, "run-x", "abcdef1234567", "--privileged", null, null, ".",
                DeployTarget.DOCKER, 18095, 3000, "/healthz", null, false, null));
        assertFalse(deployed.success(), "注入型镜像不能让部署继续");
        assertEquals("E-201", deployed.errorCode(), deployed.summary());
    }

    private static void assertThrowsRecipe(String image) {
        assertThrows(RecipeException.class, () -> ImageRef.require(image, "test"),
                "应被拒绝：" + image);
    }
}
