package top.rongx.agent.domain.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 日志取尾部/截断的唯一实现。 */
class LogTailTest {

    @Test
    @DisplayName("lastLines 只保留最后 N 行，且去掉行尾空行")
    void lastLinesKeepsTail() {
        assertEquals("c\nd", LogTail.lastLines("a\nb\nc\nd\n", 2));
        assertEquals("", LogTail.lastLines((String) null, 5));
        assertEquals("", LogTail.lastLines("a\nb", 0));
    }

    @Test
    @DisplayName("lastLines(Path) 读取失败时返回空串")
    void lastLinesFromMissingFileIsEmpty(@TempDir Path dir) {
        assertEquals("", LogTail.lastLines(dir.resolve("nope.log"), 5));
    }

    @Test
    @DisplayName("lastLines 支持自定义连接符与兜底")
    void lastLinesJoinerAndFallback(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "1\n2\n3\n");
        assertEquals("2 | 3", LogTail.lastLines(log, 2, " | ", "none"));
        assertEquals("none", LogTail.lastLines(dir.resolve("missing.log"), 2, " | ", "none"));
    }

    @Test
    @DisplayName("lastLineList 返回最后 N 行，缺失文件返回空列表")
    void lastLineList(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "1\n2\n3\n");
        assertEquals(List.of("2", "3"), LogTail.lastLineList(log, 2));
        assertEquals(List.of(), LogTail.lastLineList(dir.resolve("missing.log"), 2));
    }

    @Test
    @DisplayName("lastChars 取尾部，截断时按需加前缀")
    void lastCharsTail() {
        assertEquals("cdef", LogTail.lastChars("abcdef", 4));
        assertEquals("abcdef", LogTail.lastChars("abcdef", 10));
        assertEquals("…cdef", LogTail.lastChars("abcdef", 4, "…"));
        assertEquals("", LogTail.lastChars(null, 4));
    }

    @Test
    @DisplayName("prefix 取开头，截断时追加省略号")
    void prefixHead() {
        assertEquals("abc…", LogTail.prefix("abcdef", 3, "…"));
        assertEquals("ab", LogTail.prefix("ab", 3, "…"));
        assertEquals("", LogTail.prefix(null, 3, "…"));
    }

    @Test
    @DisplayName("oneLine 折叠空白后截断")
    void oneLineCollapsesWhitespace() {
        assertEquals("a b c…", LogTail.oneLine("a\n  b\tc  extra", 5));
        assertTrue(LogTail.oneLine(null, 5).isEmpty());
    }
}
