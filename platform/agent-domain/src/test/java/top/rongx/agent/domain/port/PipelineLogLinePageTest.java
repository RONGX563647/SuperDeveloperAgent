package top.rongx.agent.domain.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.PipelineEngine.LogLinePage;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 日志行分页的契约。
 *
 * <p>「右侧没有滚动条、日志不能下滚」的根因是日志只能整包给：前端拿不到总行数，
 * 就没法算滚动高度、也没法做虚拟滚动。这里把 {@code {lines, total, truncated}}
 * 的口径钉死（含行尾换行、越界、上限夹紧三个容易错的地方）。
 */
class PipelineLogLinePageTest {

    @Test
    @DisplayName("行尾换行不多算一行，total 是真实行数")
    void trailingNewlineIsNotAnExtraLine() {
        LogLinePage page = LogLinePage.of("a\nb\nc\n", 0, 10);

        assertEquals(3, page.total(), "行尾换行不应多算一行");
        assertEquals(List.of("a", "b", "c"), page.lines());
        assertFalse(page.truncated());
    }

    @Test
    @DisplayName("窗口越界退化：offset 超出 total 返回空页，truncated=false")
    void offsetBeyondEndIsEmpty() {
        LogLinePage page = LogLinePage.of("a\nb\n", 99, 10);

        assertEquals(2, page.total());
        assertEquals(2, page.offset(), "offset 要夹紧到 total，前端据此停止翻页");
        assertTrue(page.lines().isEmpty());
        assertFalse(page.truncated());
    }

    @Test
    @DisplayName("limit 生效并如实报告 truncated")
    void limitTruncates() {
        String text = String.join("\n", "1", "2", "3", "4", "5");
        LogLinePage first = LogLinePage.of(text, 0, 2);

        assertEquals(List.of("1", "2"), first.lines());
        assertEquals(5, first.total());
        assertTrue(first.truncated(), "还有更多行时必须置 truncated");

        LogLinePage last = LogLinePage.of(text, 4, 2);
        assertEquals(List.of("5"), last.lines());
        assertFalse(last.truncated(), "读到末尾就不该再报截断");
    }

    @Test
    @DisplayName("limit 被上限夹紧，空日志返回空页而不是出错")
    void limitIsBoundedAndEmptyLogIsSafe() {
        LogLinePage big = LogLinePage.of("a\nb\n", 0, 999_999);
        assertEquals(LogLinePage.MAX_LINES, big.limit(), "上限必须生效，否则分页失去意义");

        LogLinePage empty = LogLinePage.of("", 0, 10);
        assertEquals(0, empty.total());
        assertTrue(empty.lines().isEmpty());
        assertFalse(empty.truncated());

        LogLinePage nil = LogLinePage.of(null, 0, 10);
        assertEquals(0, nil.total());
    }

    @Test
    @DisplayName("默认实现走 logTail，引擎不覆盖时分页仍可用")
    void defaultImplementationUsesLogTail() {
        PipelineEngine engine = new StubEngine("l1\nl2\nl3\n");
        LogLinePage page = engine.logLines("run-1", 1, 1);

        assertEquals(3, page.total());
        assertEquals(List.of("l2"), page.lines());
        assertTrue(page.truncated());
    }

    /** 只实现必需方法的最小引擎；logTail 给测试文本。 */
    private static final class StubEngine implements PipelineEngine {
        private final String log;

        private StubEngine(String log) {
            this.log = log;
        }

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public List<String> pipelines(String projectId) {
            return List.of();
        }

        @Override
        public String trigger(String projectId, String pipelineId,
                              java.util.Map<String, String> params) {
            return "run-1";
        }

        @Override
        public StageStatus status(String runId) {
            return StageStatus.SUCCEEDED;
        }

        @Override
        public List<StageResult> stages(String runId) {
            return List.of();
        }

        @Override
        public void cancel(String runId) {
        }

        @Override
        public List<ArtifactRef> artifacts(String runId) {
            return List.of();
        }

        @Override
        public String logTail(String runId) {
            return log;
        }
    }
}
