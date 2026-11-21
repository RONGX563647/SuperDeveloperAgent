package top.rongx.agent.domain.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 容器资源限制的默认值与校验。 */
class DockerLimitsTest {

    @Test
    @DisplayName("合法配置原样保留，空白项回落到各自默认值")
    void keepsValidAndAppliesDefaults() {
        List<String> problems = new ArrayList<>();
        DockerLimits limits = DockerLimits.normalize("alpine", "", "1g", "1.5", 256, "",
                "bridge", "1000:1000", "agent.sandbox.", problems);
        assertEquals("alpine", limits.image());
        assertEquals("bridge", limits.network());
        assertEquals("1g", limits.memory());
        assertEquals("1.5", limits.cpus());
        assertEquals(256, limits.pidsLimit());
        assertEquals("1000:1000", limits.user());
        assertTrue(problems.isEmpty());
    }

    @Test
    @DisplayName("memory 漏单位 / cpus 非法 / pids<=0 均告警并回落")
    void fallsBackOnInvalidValues() {
        List<String> problems = new ArrayList<>();
        DockerLimits limits = DockerLimits.normalize(null, null, "2", "abc", 0, null,
                "none", "", "agent.agent-sandbox.", problems);
        assertEquals(DockerLimits.DEFAULT_IMAGE, limits.image());
        assertEquals("none", limits.network());
        assertEquals(DockerLimits.DEFAULT_MEMORY, limits.memory());
        assertEquals(DockerLimits.DEFAULT_CPUS, limits.cpus());
        assertEquals(DockerLimits.DEFAULT_PIDS_LIMIT, limits.pidsLimit());
        assertEquals("", limits.user());
        assertEquals(3, problems.size());
    }
}
