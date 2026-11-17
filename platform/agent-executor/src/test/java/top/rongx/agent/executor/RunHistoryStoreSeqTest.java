package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.run.StageStatus;

/** 历史序号归一化：修掉旧数据的跳号与残留"进行中"。 */
class RunHistoryStoreSeqTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("启动归一化后按时间连续编号，并将残留运行标为已取消")
    void renumbersByCreatedAt() {
        RunHistoryStore store = RunHistoryStore.file(dir);
        store.save(snapshot("r1", 10, 1_000, StageStatus.SUCCEEDED));
        store.save(snapshot("r2", 200, 2_000, StageStatus.SUCCEEDED));
        store.save(snapshot("r3", 5, 3_000, StageStatus.RUNNING));

        store.normalizeSeq();

        List<RunHistoryStore.Summary> list = store.summaries(null, 0, 10);
        assertEquals(List.of(3, 2, 1),
                list.stream().map(RunHistoryStore.Summary::seq).toList(),
                "按创建时间倒序，序号连续");
        assertEquals(StageStatus.CANCELLED,
                list.stream().filter(h -> h.runId().equals("r3")).findFirst().orElseThrow().status(),
                "重启后残留的运行不能一直挂在进行中");
    }

    private static RunHistoryStore.RunSnapshot snapshot(String runId, int seq, long createdAt,
                                                        StageStatus status) {
        return new RunHistoryStore.RunSnapshot(runId, "P", "test", "release", "abcdef1234",
                status, createdAt, seq, List.of(), List.of(), "", "PLATFORM", null, null, null);
    }
}
