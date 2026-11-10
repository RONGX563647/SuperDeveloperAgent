package top.rongx.agent.executor;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.run.StageStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 构建历史：序号从 1 开始、旧文件补号、滑动删除与产物 meta 往返。 */
class RunHistoryStoreTest {

    private static final String TAG = "ci-test-v1-abcdef1";

    private static RunHistoryStore.RunSnapshot snap(String runId, String env, long createdAt, int seq) {
        return new RunHistoryStore.RunSnapshot(runId, "RY", env, "release", "abcdef1234567",
                StageStatus.SUCCEEDED, createdAt, seq, List.of(),
                List.of(new ArtifactRef("image", TAG, "sha256:deadbeef", 12L,
                        Map.of("version", "test-v1", "localImage", TAG))),
                "log", "repo", null);
    }

    @Test
    @DisplayName("同项目+同环境的序号从 1 开始，nextSeq 继续递增")
    void numbersFromOne() throws Exception {
        RunHistoryStore store = RunHistoryStore.file(Files.createTempDirectory("hist-seq"));
        store.save(snap("r1", "test", 1000, 1));
        store.save(snap("r2", "test", 2000, 2));
        store.save(snap("d1", "dev", 1500, 1));

        var list = store.list();
        assertEquals(3, list.size());
        assertEquals("r2", list.get(0).runId(), "最新在前");
        assertEquals(1, seqOf(list, "r1"));
        assertEquals(1, seqOf(list, "d1"), "环境和项目一起决定编号");
        assertEquals(3, store.nextSeq("RY", "test"));
        assertEquals(2, store.nextSeq("RY", "dev"));
    }

    @Test
    @DisplayName("旧文件没有 seq 时按时间补号并落盘；删除中间一条后其它条目的号不变")
    void backfillsLegacyFilesAndDelete() throws Exception {
        RunHistoryStore store = RunHistoryStore.file(Files.createTempDirectory("hist-legacy"));
        store.save(snap("old1", "test", 1000, 0));
        store.save(snap("old2", "test", 2000, 0));
        store.save(snap("old3", "test", 3000, 0));

        var list = store.list();
        assertEquals(1, seqOf(list, "old1"));
        assertEquals(2, seqOf(list, "old2"));
        assertEquals(3, seqOf(list, "old3"));

        assertTrue(store.delete("old2"));
        var after = store.list();
        assertTrue(after.stream().noneMatch(s -> s.runId().equals("old2")));
        // 旧文件补的号已落盘，删掉中间的不会让后面的号整体前移
        assertEquals(1, seqOf(after, "old1"));
        assertEquals(3, seqOf(after, "old3"));
        assertEquals(4, store.nextSeq("RY", "test"));
        assertFalse(store.delete("missing"));
    }

    @Test
    @DisplayName("镜像产物 meta 往返：版本 vN 与本地镜像地址可读回")
    void persistsArtifactMeta() throws Exception {
        RunHistoryStore store = RunHistoryStore.file(Files.createTempDirectory("hist-meta"));
        store.save(snap("r1", "test", 1000, 1));

        ArtifactRef image = store.list().get(0).artifacts().get(0);
        assertEquals("test-v1", image.meta().get("version"));
        assertEquals(TAG, image.meta().get("localImage"));
    }

    private static int seqOf(List<RunHistoryStore.RunSnapshot> list, String runId) {
        return list.stream().filter(s -> s.runId().equals(runId)).findFirst().orElseThrow().seq();
    }
}
