package top.rongx.agent.domain.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 全平台共享的只读 JSON 映射器。
 *
 * <p>此前 tools / runtime / gateway 里散落着十余处 {@code new ObjectMapper()}：
 * 有的注册了 {@code JavaTimeModule}，有的没有；有的关闭「未知字段即失败」，有的没关。
 * 于是同一份 JSON 从一个入口能读、从另一个入口报 500，排查成本极高。
 *
 * <p><b>只读约定</b>：调用方只能拿它做 {@code readValue / writeValueAsString / readTree}
 * 等无状态操作，<b>不得</b>再 {@code configure} 或 {@code registerModule}——那会污染全平台。
 * 需要不同配置时请自行 new 一个局部 mapper。
 */
public final class JsonMappers {

    private static final ObjectMapper READ_ONLY = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonMappers() {
    }

    /** 共享只读 mapper（线程安全）。 */
    public static ObjectMapper readOnly() {
        return READ_ONLY;
    }
}
