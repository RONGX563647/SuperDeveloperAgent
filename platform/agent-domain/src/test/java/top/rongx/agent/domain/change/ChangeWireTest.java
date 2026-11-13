package top.rongx.agent.domain.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 工具输出与网关之间的变更单线格式：生产/消费必须使用同一实现。 */
class ChangeWireTest {

    @Test
    void lineThenExtractRoundTrips() {
        String changeId = "chg-3f2c9a1e-7b4d-4c2a-9f10-2a1b3c4d5e6f";
        String toolResult = """
                status: PENDING_USER_CONFIRMATION
                %s
                planHash: abc
                evidence: change:%s
                """.formatted(ChangeWire.line(changeId), changeId);

        assertEquals(changeId, ChangeWire.extract(toolResult));
    }

    @Test
    void onlyStandaloneLineIsAccepted() {
        // 正文里顺带提到的单号不能触发确认卡片
        assertNull(ChangeWire.extract("已完成，可参考 changeId: chg-11111111-2222 这条记录"));
        assertNull(ChangeWire.extract("changeId: not-a-change"));
        assertNull(ChangeWire.extract(""));
        assertNull(ChangeWire.extract(null));
    }

    @Test
    void trailingTextOnSameLineIsRejected() {
        assertNull(ChangeWire.extract("changeId: chg-3f2c9a1e-7b4d (仅供参考)"));
    }
}
