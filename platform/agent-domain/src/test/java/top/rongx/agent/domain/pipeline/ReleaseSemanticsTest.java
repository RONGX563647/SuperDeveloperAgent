package top.rongx.agent.domain.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发布形态与标签状态的语义。
 *
 * <p>两个真实误判在这里被钉死：
 * <ol>
 *   <li>「首次发布这里不对」——没有上一版被当成故障（E-507），用户不知道要改什么；</li>
 *   <li>「打 tag 未生效」——本地建了但推送失败被当成成功，远端查不到标签。</li>
 * </ol>
 */
class ReleaseSemanticsTest {

    @Test
    @DisplayName("首次发布被显式标记，并说明为什么跳过回滚预检")
    void firstReleaseIsExplicit() {
        ReleaseMode mode = ReleaseMode.of("");

        assertTrue(mode.firstRelease());
        assertEquals("", mode.previousVersion());
        assertTrue(mode.reason().contains("首次发布"), mode.reason());
        assertTrue(mode.reason().contains("不是错误"), "要说清这不是故障：" + mode.reason());
    }

    @Test
    @DisplayName("有上一版时不是首次发布，并带上上一版版本号")
    void incrementalReleaseKeepsPrevious() {
        ReleaseMode mode = ReleaseMode.of("0.1.4");

        assertFalse(mode.firstRelease());
        assertEquals("0.1.4", mode.previousVersion());
        assertTrue(mode.reason().contains("0.1.4"), mode.reason());
    }

    @Test
    @DisplayName("版本线与目标分支不一致时前置拒绝，给出可读原因")
    void versionBranchConflictIsReadable() {
        String conflict = ReleaseMode.versionBranchConflict("0.2.0", "0.1.0-snapshot");

        assertTrue(conflict.contains("0.2.0"), conflict);
        assertTrue(conflict.contains("0.1.0-snapshot"), conflict);
        assertTrue(conflict.contains("0.1.x"), "要给出可用的版本线：" + conflict);
    }

    @Test
    @DisplayName("版本线匹配或非版本线分支不拦")
    void compatibleVersionLinePasses() {
        assertEquals("", ReleaseMode.versionBranchConflict("0.1.7", "0.1.0-snapshot"));
        assertEquals("", ReleaseMode.versionBranchConflict("0.2.0", "release/0.2"));
        assertEquals("", ReleaseMode.versionBranchConflict("0.2.0", "main"));
        assertEquals("", ReleaseMode.versionBranchConflict("", "0.1.0-snapshot"));
        assertEquals("", ReleaseMode.versionBranchConflict(null, null));
    }

    @Test
    @DisplayName("只有 PUSHED 算远端可见；LOCAL 带凭据原因码与补推建议")
    void tagStateDistinguishesLocalOnly() {
        assertTrue(TagState.PUSHED.remoteVisible());
        assertFalse(TagState.LOCAL_ONLY.remoteVisible());

        assertEquals("TAG_PUSH_CREDENTIAL_MISSING", TagState.LOCAL_ONLY.reasonCode());
        assertTrue(TagState.LOCAL_ONLY.needsAction());
        assertTrue(TagState.LOCAL_ONLY.hint().contains("重推标签"), TagState.LOCAL_ONLY.hint());
        assertTrue(TagState.LOCAL_ONLY.hint().contains("凭据"), TagState.LOCAL_ONLY.hint());

        assertEquals("PUSHED", TagState.PUSHED.wireName());
        assertEquals("LOCAL", TagState.LOCAL_ONLY.wireName(), "线值保持 LOCAL 以兼容历史数据");
        assertFalse(TagState.SNAPSHOT.needsAction());
        assertTrue(TagState.REJECTED.needsAction());
    }

    @Test
    @DisplayName("线值解析兼容旧数据：LOCAL_ONLY / 未知值不抛异常")
    void wireParsingIsForgiving() {
        assertEquals(TagState.LOCAL_ONLY, TagState.fromWire("LOCAL"));
        assertEquals(TagState.LOCAL_ONLY, TagState.fromWire("local_only"));
        assertEquals(TagState.PUSHED, TagState.fromWire("pushed"));
        assertEquals(TagState.FAILED, TagState.fromWire("FAILED"));
        assertEquals(TagState.SNAPSHOT, TagState.fromWire("SOMETHING_ELSE"));
        assertEquals(TagState.SNAPSHOT, TagState.fromWire(null));
        assertEquals(TagState.SNAPSHOT, TagState.fromWire("  "));
    }
}
