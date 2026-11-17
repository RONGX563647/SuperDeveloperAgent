package top.rongx.agent.domain.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseVersionTest {

    @Test
    @DisplayName("版本号只接受 x.y.z，不带 v 前缀")
    void parsesStrictSemver() {
        assertEquals(new ReleaseVersion(0, 1, 2), ReleaseVersion.parse("0.1.2"));
        assertEquals(new ReleaseVersion(0, 1, 2), ReleaseVersion.parse(" 0.1.2 "));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse("v0.1.2"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse("0.1"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse("0.1.2-snapshot"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse("1.0.0.0"));
    }

    @Test
    @DisplayName("版本线映射到 0.x.0-snapshot 分支，补丁共用同一分支")
    void mapsLineToSnapshotBranch() {
        ReleaseVersion base = ReleaseVersion.parse("0.1.0");
        ReleaseVersion patch = ReleaseVersion.parse("0.1.7");
        assertEquals("0.1", base.line());
        assertEquals("0.1.0-snapshot", base.branchName());
        assertEquals("0.1.0-snapshot", patch.branchName(), "补丁版本不新起分支");
        assertTrue(patch.onBranch("0.1.0-snapshot"));
        assertFalse(patch.onBranch("0.2.0-snapshot"));
        assertEquals("0.1.7", patch.tagName(), "标签不带 v 前缀");
        assertEquals("0.1.7-snapshot", patch.snapshotLabel());
    }

    @Test
    @DisplayName("分支解析：版本分支返回版本线，普通分支返回 null")
    void parsesVersionBranch() {
        assertEquals("0.1", ReleaseVersion.lineOfBranch("0.1.0-snapshot"));
        assertEquals("0.2", ReleaseVersion.lineOfBranch("0.2.0-snapshot"));
        assertNull(ReleaseVersion.lineOfBranch("main"));
        assertNull(ReleaseVersion.lineOfBranch("release/0.1"));
        assertNull(ReleaseVersion.lineOfBranch("0.1.1-snapshot"));
        assertEquals(ReleaseVersion.parse("0.1.0"),
                ReleaseVersion.baseOfBranch("0.1.0-snapshot"));
        assertTrue(ReleaseVersion.isVersionBranch("0.1.0-snapshot"));
        assertFalse(ReleaseVersion.isVersionBranch("main"));
    }

    @Test
    @DisplayName("补丁只允许 +1，比较按 major/minor/patch 顺序")
    void ordersAndAdvances() {
        assertEquals("0.1.3", ReleaseVersion.parse("0.1.2").nextPatch().toString());
        assertTrue(ReleaseVersion.parse("0.2.0").compareTo(ReleaseVersion.parse("0.1.9")) > 0);
        assertTrue(ReleaseVersion.parse("0.1.10").compareTo(ReleaseVersion.parse("0.1.9")) > 0);
        assertTrue(ReleaseVersion.parse("1.0.0").compareTo(ReleaseVersion.parse("0.9.9")) > 0);
    }
}
