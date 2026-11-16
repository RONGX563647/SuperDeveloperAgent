package top.rongx.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Harness Handbook 漂移检查（docs/handbook/index.md）。
 *
 * <p>L3 的"行为 → 源码位置"表由代码锚点生成；这里只调用生成器的 check 子命令，
 * 与 pre-push 走同一个入口：锚点删除、符号改名、行号移动都会在这里失败。
 */
class HandbookDriftTest {

    @Test
    @DisplayName("Handbook 锚点与生成区未漂移")
    void handbookIsUpToDate() {
        PythonScripts.run("tools/handbook/build.py", "check", "Harness Handbook");
    }
}
