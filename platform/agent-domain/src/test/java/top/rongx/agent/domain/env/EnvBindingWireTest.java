package top.rongx.agent.domain.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 环境绑定线格式：工具结果被 JSON 序列化后仍要能解析出绑定 id。 */
class EnvBindingWireTest {

    @Test
    void lineThenExtractRoundTrips() {
        String bindingId = "bind-cc0913c1";
        String toolResult = """
                status: DRAFT
                %s
                env: dev
                """.formatted(EnvBindingWire.line(bindingId));

        assertEquals(bindingId, EnvBindingWire.extract(toolResult));
    }

    @Test
    void jsonSerializedToolResultIsUnwrapped() {
        String bindingId = "bind-cc0913c1";
        String raw = "status: DRAFT\n" + EnvBindingWire.line(bindingId) + "\n";
        String serialized = "\"" + raw.replace("\n", "\\n") + "\"";

        assertEquals(bindingId, EnvBindingWire.extract(serialized));
    }

    @Test
    void inlineMentionIsRejected() {
        assertNull(EnvBindingWire.extract("可参考 bindingId: bind-cc0913c1 这条记录"));
        assertNull(EnvBindingWire.extract(null));
    }
}
