package top.rongx.agent.gateway.bugfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 片段匹配：精确 → 去行尾空白 → 忽略缩进；多处命中必须失败。 */
class PatchApplierTest {

    @Test
    void exactMatchWins() {
        String current = "class A {\n    int x = 1;\n}\n";
        String result = PatchApplier.replace("A.java", current, "int x = 1;", "int x = 2;");
        assertEquals("class A {\n    int x = 2;\n}\n", result);
    }

    @Test
    void toleratesCrlfAndTrailingSpaces() {
        String current = "line1  \r\nline2\r\nline3\r\n";
        String result = PatchApplier.replace("a.txt", current, "line1\nline2\n", "changed\n");
        assertTrue(result.contains("changed"));
        assertTrue(result.contains("line3"));
    }

    @Test
    void toleratesIndentDifference() {
        String current = "class A {\n        int x = 1;\n}\n";
        String result = PatchApplier.replace("A.java", current, "    int x = 1;", "    int x = 2;");
        // 精确子串命中时保留文件原有缩进，只替换片段本身
        assertEquals("class A {\n        int x = 2;\n}\n", result);
    }

    @Test
    void multipleMatchesReportCandidates() {
        String current = "a();\nb();\na();\n";
        PatchApplier.ApplyException error = assertThrows(PatchApplier.ApplyException.class,
                () -> PatchApplier.replace("a.js", current, "a();", "c();"));
        assertEquals("a.js", error.file());
        assertEquals(java.util.List.of(1, 3), error.candidateLines());
    }

    @Test
    void notFoundReportsClosestLines() {
        String current = "one\ntwo\nthree\n";
        PatchApplier.ApplyException error = assertThrows(PatchApplier.ApplyException.class,
                () -> PatchApplier.replace("a.txt", current, "two\nmore", "x"));
        assertTrue(error.getMessage().contains("第 2 行"), error.getMessage());
    }
}
