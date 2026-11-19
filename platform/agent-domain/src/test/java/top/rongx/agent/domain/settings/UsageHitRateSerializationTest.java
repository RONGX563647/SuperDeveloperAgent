package top.rongx.agent.domain.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.TokenUsageRepository.DailyUsage;
import top.rongx.agent.domain.settings.TokenUsageRepository.ModelUsageSummary;
import top.rongx.agent.domain.settings.TokenUsageRepository.ProjectUsageSummary;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageSummary;

/**
 * 命中率必须是 record 的<b>组件</b>，不能只是普通方法。
 *
 * <p>踩过的坑：一开始把 cacheHitRate 写成 record 的派生方法。Java 里能调用、
 * 单测也过，但 springdoc / Jackson 只序列化 record 组件，派生方法不出现在
 * JSON 与 OpenAPI 里——前端那一列永远是空白，而接口看起来"一切正常"。
 *
 * <p>domain 层不允许依赖 Jackson（架构约束 A2：domain 与框架无关），
 * 所以这里用反射检查「是不是组件」这个本质特征，而不是真去序列化：
 * 是组件 → 一定被序列化；不是组件 → 一定不会。
 */
class UsageHitRateSerializationTest {

    private static boolean hasComponent(Class<?> type, String name) {
        RecordComponent[] components = type.getRecordComponents();
        return components != null && Arrays.stream(components)
                .anyMatch(component -> component.getName().equals(name));
    }

    @Test
    @DisplayName("三个维度都是 record 组件，因此会进 OpenAPI 与前端类型")
    void everyDimensionDeclaresRateComponent() {
        assertTrue(hasComponent(DailyUsage.class, "cacheHitRate"),
                "DailyUsage 的 cacheHitRate 必须是 record 组件");
        assertTrue(hasComponent(ModelUsageSummary.class, "cacheHitRate"),
                "ModelUsageSummary 的 cacheHitRate 必须是 record 组件");
        assertTrue(hasComponent(ProjectUsageSummary.class, "cacheHitRate"),
                "ProjectUsageSummary 的 cacheHitRate 必须是 record 组件");
        assertTrue(hasComponent(UsageSummary.class, "cacheHitRate"),
                "UsageSummary 的 cacheHitRate 必须是 record 组件");
    }

    @Test
    @DisplayName("组件能取到值：50 / 40 / 75")
    void componentValuesAreComputed() {
        assertEquals(50, new DailyUsage("2026-11-19", 3, 1000, 500, 100, 1100).cacheHitRate());
        assertEquals(40, new ModelUsageSummary("deepseek-chat", 3, 1000, 400, 100, 1100).cacheHitRate());
        assertEquals(75, new ProjectUsageSummary("RY", 3, 2000, 1500, 100, 2100).cacheHitRate());
    }

    @Test
    @DisplayName("无输入时是 0，不是 null")
    void zeroInputIsZeroNotNull() {
        assertEquals(0, new DailyUsage("d", 0, 0, 0, 0, 0).cacheHitRate());
        assertEquals(0, new ModelUsageSummary("m", 0, 0, 30, 0, 0).cacheHitRate());
        assertEquals(0, new ProjectUsageSummary("p", 0, 0, 0, 0, 0).cacheHitRate());
    }

    @Test
    @DisplayName("显式传入的命中率优先于推导值")
    void explicitRateWins() {
        assertEquals(80, new DailyUsage("d", 1, 100, 10, 5, 105, 80).cacheHitRate());
        assertEquals(90, new ModelUsageSummary("m", 1, 100, 10, 5, 105, 90).cacheHitRate());
        assertEquals(70, new ProjectUsageSummary("p", 1, 100, 10, 5, 105, 70).cacheHitRate());
    }

    @Test
    @DisplayName("旧的四参/六参构造器仍然可用（兼容既有调用点）")
    void legacyConstructorsStillWork() {
        List<Object> rows = List.of(
                new DailyUsage("d", 1, 10, 5, 1, 11),
                new ModelUsageSummary("m", 1, 10, 5, 1, 11),
                new ProjectUsageSummary("p", 1, 10, 5, 1, 11));
        assertEquals(3, rows.size(), "旧构造器不应被移除");
    }
}
