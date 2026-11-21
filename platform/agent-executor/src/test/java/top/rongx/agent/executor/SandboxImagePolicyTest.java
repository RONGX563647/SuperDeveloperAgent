package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.SandboxDeniedException;

/**
 * 镜像准入策略：在语法校验之上回答"允许谁"。
 *
 * <p>守住的回归：{@code stage.image} 来自仓库里的 {@code ci/pipeline.yml}，
 * {@code ImageRef} 只能挡住参数注入（以 {@code -} 开头），挡不住
 * {@code evil.example.com/backdoor:latest} —— 那是一句语法完全合法的引用。
 */
class SandboxImagePolicyTest {

    @Test
    @DisplayName("默认策略不改变现有行为：不查允许列表、允许浮动 tag")
    void permissiveIsBackwardCompatible() {
        SandboxImagePolicy policy = SandboxImagePolicy.permissive();

        for (String image : List.of("maven:3.9-eclipse-temurin-21", "node:latest", "nginx", "evil/x:latest")) {
            assertTrue(policy.check(image, SandboxImagePolicy.Source.STAGE).allowed(), image);
            assertTrue(policy.check(image, SandboxImagePolicy.Source.CONFIG).allowed(), image);
        }
    }

    @Test
    @DisplayName("禁浮动 tag 时：仓库来源必须钉版本，平台配置来源仍可浮动")
    void floatingTagRestrictedForRepositoryInputs() {
        SandboxImagePolicy policy = new SandboxImagePolicy(List.of(), false);

        // 仓库来源：无 tag / latest / 浮动 → 拒绝
        assertFalse(policy.check("nginx", SandboxImagePolicy.Source.STAGE).allowed());
        assertFalse(policy.check("nginx:latest", SandboxImagePolicy.Source.STAGE).allowed());
        assertFalse(policy.check("evil.example.com/x:latest", SandboxImagePolicy.Source.DEPLOY).allowed());
        // 钉住版本 → 通过
        assertTrue(policy.check("nginx:1.27.1", SandboxImagePolicy.Source.STAGE).allowed());
        assertTrue(policy.check("nginx@sha256:" + "a".repeat(64),
                SandboxImagePolicy.Source.STAGE).allowed());
        // 平台配置来源（运维自己写的）允许浮动
        assertTrue(policy.check("maven:latest", SandboxImagePolicy.Source.CONFIG).allowed());
        // registry 端口不算 tag
        assertTrue(SandboxImagePolicy.isFloating("localhost:5000/app"));
        assertFalse(SandboxImagePolicy.isFloating("localhost:5000/app:1.0"));
    }

    @Test
    @DisplayName("允许列表按 glob 匹配：不在列表内的仓库来源被拒，报错带来源与可执行建议")
    void allowlistBlocksUnknownRegistry() {
        SandboxImagePolicy policy = new SandboxImagePolicy(
                List.of("maven:*", "node:*", "registry.internal:5000/*"), true);

        assertTrue(policy.check("maven:3.9-eclipse-temurin-21", SandboxImagePolicy.Source.STAGE).allowed());
        assertTrue(policy.check("registry.internal:5000/team/app:1.0",
                SandboxImagePolicy.Source.DEPLOY).allowed());
        assertFalse(policy.check("evil.example.com/backdoor:1.0",
                SandboxImagePolicy.Source.STAGE).allowed());

        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> policy.require("evil.example.com/backdoor:1.0", SandboxImagePolicy.Source.STAGE));
        assertEquals(SandboxDeniedException.Rule.IMAGE, denied.rule());
        assertTrue(denied.structured().contains("stage.image"), denied.structured());
        assertTrue(denied.action().contains("image-allowlist"), "建议要指向具体配置项：" + denied.action());
        assertTrue(denied.effective().toString().contains("maven:*"), denied.effective().toString());
    }

    @Test
    @DisplayName("空镜像直接拒绝（不落到 docker run 才报）")
    void blankImageRejected() {
        SandboxImagePolicy policy = SandboxImagePolicy.permissive();
        assertFalse(policy.check("", SandboxImagePolicy.Source.STAGE).allowed());
        assertFalse(policy.check(null, SandboxImagePolicy.Source.CONFIG).allowed());
        assertThrows(SandboxDeniedException.class,
                () -> policy.require(null, SandboxImagePolicy.Source.DEPLOY));
    }

    @Test
    @DisplayName("策略可被 DockerSandbox 读取并应用到 stage.image")
    void dockerSandboxUsesPolicyForStageImage() {
        DockerSandbox sandbox = new DockerSandbox("docker", "maven:3.9-eclipse-temurin-21", "bridge",
                "2g", "2", 512, "1000:1000", "agent-cache")
                .setImagePolicy(new SandboxImagePolicy(List.of("maven:*", "node:*"), true));

        // 允许列表内
        assertEquals("node:22-bookworm", sandbox.withImage("node:22-bookworm").image());
        // 列表外：E-707 而不是默默起容器
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> sandbox.withImage("evil/x:1.0"));
        assertEquals(SandboxDeniedException.Rule.IMAGE, denied.rule());
        // 派生沙箱继承策略（拷贝构造），不会再漏一个旋钮
        DockerSandbox derived = sandbox.withImage("node:20-bookworm");
        assertThrows(SandboxDeniedException.class, () -> derived.withImage("evil/y:1.0"));
    }
}
