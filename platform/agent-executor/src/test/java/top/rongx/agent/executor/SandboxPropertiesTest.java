package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 沙箱配置的声明与启动期校验。
 *
 * <p>守住的回归（G11）：23 个 {@code @Value} 散在 bean 方法签名里时，
 * {@code memory=2}（少了单位）、{@code pids-limit=0}（docker 里 0 = 无限制，
 * 正好抵消这条隔离）都要等到 {@code docker run} 才失败，报的还是 CLI 原始错误。
 * 现在统一在 {@link SandboxProperties#normalize()} 收敛成"明确告警 + 安全默认"。
 */
class SandboxPropertiesTest {

    @Test
    @DisplayName("缺省值就是安全默认：非 root、有 pids 上限、能力全丢由 wrap 保证")
    void defaultsAreSafe() {
        SandboxProperties props = new SandboxProperties();
        List<String> problems = props.normalize();

        assertTrue(problems.isEmpty(), "缺省配置不该有问题：" + problems);
        assertEquals("docker", props.getMode());
        assertEquals("1000:1000", props.getUser(), "默认非 root");
        assertEquals(512, props.getPidsLimit(), "默认必须有 pids 上限");
        assertEquals("bridge", props.getNetwork(), "默认能拉依赖");
        assertFalse(props.isAllowOffline(), "离线逃生门默认关闭");
    }

    @Test
    @DisplayName("memory 少了单位要告警并回落：不让它在 docker run 时才失败")
    void invalidMemoryIsCaught() {
        for (String bad : List.of("2", "abc", "", "-1g", "0g")) {
            SandboxProperties props = new SandboxProperties();
            props.setMemory(bad);

            List<String> problems = props.normalize();

            assertEquals("2g", props.getMemory(), "非法 memory 必须回落：" + bad);
            assertTrue(problems.stream().anyMatch(p -> p.contains("memory")),
                    "必须告警而不是静默修正：" + bad + " → " + problems);
        }
    }

    @Test
    @DisplayName("合法 memory/cpus 原样保留（不做无谓改写）")
    void validValuesAreKept() {
        SandboxProperties props = new SandboxProperties();
        props.setMemory("512m");
        props.setCpus("1.5");

        assertTrue(props.normalize().isEmpty(), "合法值不该产生告警");
        assertEquals("512m", props.getMemory());
        assertEquals("1.5", props.getCpus());
    }

    @Test
    @DisplayName("pids-limit <= 0 必须回落：0 在 docker 里表示无限制，会抵消隔离")
    void nonPositivePidsLimitIsDangerous() {
        SandboxProperties props = new SandboxProperties();
        props.setPidsLimit(0);

        List<String> problems = props.normalize();

        assertEquals(512, props.getPidsLimit(), "0（无限制）必须被收敛成有限值");
        assertTrue(problems.stream().anyMatch(p -> p.contains("pids-limit")), problems.toString());
    }

    @Test
    @DisplayName("超时与重试必须为正：0 会把“等待”变成“立即失败”")
    void nonPositiveTimeoutsAreNormalized() {
        SandboxProperties props = new SandboxProperties();
        props.setPullTimeoutSeconds(0);
        props.setPullIdleTimeoutSeconds(-1);
        props.setPullAttempts(0);
        props.setPullBackoffSeconds(0);
        props.setPullBackoffMaxSeconds(0);

        props.normalize();

        assertTrue(props.getPullTimeoutSeconds() > 0, "总超时必须为正");
        assertTrue(props.getPullIdleTimeoutSeconds() > 0, "静默阈值必须为正");
        assertTrue(props.getPullAttempts() >= 1, "至少尝试一次");
        assertTrue(props.getPullBackoffMaxSeconds() >= props.getPullBackoffSeconds(),
                "退避上限不能小于基数");
    }

    @Test
    @DisplayName("空白字符串归一：docker-binary / image / init-image 不能被空串污染")
    void blankStringsFallBack() {
        SandboxProperties props = new SandboxProperties();
        props.setDockerBinary("  ");
        props.setImage("");
        props.setInitImage(null);
        props.setUser(" ");
        props.setMode("  ");

        props.normalize();

        assertEquals("docker", props.getDockerBinary());
        assertEquals("maven:3.9-eclipse-temurin-21", props.getImage());
        assertEquals("busybox:1.36", props.getInitImage());
        assertEquals("1000:1000", props.getUser());
        assertEquals("docker", props.getMode());
    }

    @Test
    @DisplayName("describe() 是可审计快照：含隔离关键项且不含任何凭据")
    void describeIsAuditable() {
        SandboxProperties props = new SandboxProperties();
        props.normalize();
        String text = props.describe();

        assertTrue(text.contains("mode=docker"), text);
        assertTrue(text.contains("user=1000:1000"), text);
        assertTrue(text.contains("pids=512"), text);
        assertTrue(text.contains("runtime=runc"), "空 runtime 要显示成 runc：" + text);
        assertFalse(text.contains("password"), text);
    }
}
