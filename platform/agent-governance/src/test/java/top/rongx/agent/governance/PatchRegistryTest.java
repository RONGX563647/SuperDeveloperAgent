package top.rongx.agent.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.patch.CodePatch;

/** 补丁必须能跨进程存活，否则确认卡片在重启后失效。 */
class PatchRegistryTest {

    @TempDir
    Path dir;

    private CodePatch patch(String id, long createdAt) {
        return new CodePatch(id, "DEMO", "dev", "abc123", "patch/DEMO",
                List.of(new CodePatch.FileEdit("src/Main.java", "class Main {}\n"),
                        new CodePatch.FileEdit("ci/build.sh", "mvn test\n")),
                "diff --git ...", "hash-" + id, 3, 1, createdAt);
    }

    @Test
    void survivesProcessRestart() {
        new PatchRegistry(dir.toString()).save(patch("p1", System.currentTimeMillis()));

        PatchRegistry restarted = new PatchRegistry(dir.toString());
        CodePatch found = restarted.find("p1").orElseThrow();

        assertEquals("DEMO", found.projectId());
        assertEquals("abc123", found.baseSha());
        assertEquals("hash-p1", found.diffHash());
        assertEquals(2, found.edits().size());
        assertEquals("src/Main.java", found.edits().get(0).path());
        assertEquals("ci/build.sh", found.edits().get(1).path());
    }

    @Test
    void saveIsIdempotentAndReplacesEdits() {
        PatchRegistry registry = new PatchRegistry(dir.toString());
        registry.save(patch("p1", System.currentTimeMillis()));
        registry.save(new CodePatch("p1", "DEMO", "dev", "def456", "patch/DEMO",
                List.of(new CodePatch.FileEdit("src/Only.java", "x")),
                "diff", "hash2", 1, 0, System.currentTimeMillis()));

        CodePatch found = registry.find("p1").orElseThrow();

        assertEquals("def456", found.baseSha());
        assertEquals(1, found.edits().size());
        assertEquals("src/Only.java", found.edits().get(0).path());
    }

    @Test
    void removeDeletesPatchAndEdits() {
        PatchRegistry registry = new PatchRegistry(dir.toString());
        registry.save(patch("p1", System.currentTimeMillis()));

        registry.remove("p1");

        assertTrue(registry.find("p1").isEmpty());
    }

    @Test
    void purgeBeforeRemovesOnlyStale() {
        PatchRegistry registry = new PatchRegistry(dir.toString());
        registry.save(patch("fresh", System.currentTimeMillis()));
        registry.save(patch("stale", System.currentTimeMillis() - 2 * 24 * 3600_000L));

        assertEquals(1, registry.purgeBefore(Instant.now().minusSeconds(3600)));
        assertTrue(registry.find("fresh").isPresent());
        assertFalse(registry.find("stale").isPresent());

        assertEquals(1, registry.purgeBefore(Instant.now().plusSeconds(60)));
        assertTrue(registry.find("fresh").isEmpty());
    }
}
