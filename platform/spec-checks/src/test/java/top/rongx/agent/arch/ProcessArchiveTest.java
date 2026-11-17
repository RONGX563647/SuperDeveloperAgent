package top.rongx.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话归档规约（docs/session-archive-spec-v1.md）。
 *
 * <p>校验逻辑只有一份：{@code tools/session-archive/gate.py}。这里只调用它，
 * 避免 Java / Python 双份规则漂移；hook 与 CI 走的是同一个入口。
 */
class ProcessArchiveTest {

    @Test
    @DisplayName("会话归档完整且哈希链未断")
    void archiveIsValid() {
        PythonScripts.run("tools/session-archive/gate.py", "validate", "会话归档");
    }

    @Test
    @DisplayName("归档校验器自检通过")
    void gateSelfTestPasses() {
        PythonScripts.run("tools/session-archive/gate.py", "self-test", "会话归档自检");
    }

    @Test
    @DisplayName("会话对账脚本可运行（dry-run）")
    void sweepDryRunSucceeds() {
        PythonScripts.run("tools/session-archive/sweep.py", "--dry-run", "会话对账");
    }
}
