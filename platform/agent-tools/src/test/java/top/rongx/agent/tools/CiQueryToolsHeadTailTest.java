package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运行日志截断：必须同时保留开头（阶段/命令）与结尾（错误码/最后输出）。 */
class CiQueryToolsHeadTailTest {

    @Test
    @DisplayName("短日志原样返回")
    void shortLogUntouched() {
        assertEquals("a\nb", CiQueryTools.headTail("a\nb", 100));
        assertEquals(null, CiQueryTools.headTail(null, 100));
    }

    @Test
    @DisplayName("长日志保留头尾并标注省略字符数")
    void longLogKeepsBothEnds() {
        String head = "[build] 阶段开始 · 第 2/3 阶段\n$ mvn -B -q package\n";
        String middle = "x".repeat(20_000);
        String tail = "ERROR: 代码提交被拦截：没有进行中的会话归档。\nE-302 执行超时";

        String out = CiQueryTools.headTail(head + middle + tail, 6000);

        assertTrue(out.startsWith(head), "开头必须保留：否则看不到是哪个阶段/哪条命令");
        assertTrue(out.endsWith(tail), "结尾必须保留：否则看不到错误码");
        assertTrue(out.contains("中间省略"), out.substring(0, 80));
        assertTrue(out.length() <= 6000 + 100, "窗口大小仍需受控：" + out.length());
    }

    @Test
    @DisplayName("头部约占四分之一，尾部占大头")
    void headIsQuarterOfBudget() {
        String out = CiQueryTools.headTail("H".repeat(100) + "M".repeat(100) + "T".repeat(100), 40);

        assertTrue(out.startsWith("H".repeat(10)), "头部应为预算的 1/4");
        assertTrue(out.endsWith("T".repeat(30)), "尾部应为预算的 3/4");
    }
}
