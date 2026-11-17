package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 部署版本号：环境矩阵依赖它区分"最新构建"，不能再退化成 latest。 */
class DeployVersionLabelTest {

    @Test
    @DisplayName("未填版本号时用构建序号：test-v15")
    void fallsBackToBuildSequence() {
        assertEquals("test-v15", BuiltInPipelineEngine.versionLabel(null, "test", 15, "e075cd6abc"));
        assertEquals("dev-v3", BuiltInPipelineEngine.versionLabel("  ", "dev", 3, "abcdef1234"));
    }

    @Test
    @DisplayName("用户填写的版本号优先（打 tag 场景）")
    void prefersUserVersion() {
        assertEquals("release-2026.11", BuiltInPipelineEngine.versionLabel(
                "release-2026.11", "test", 15, "e075cd6abc"));
    }

    @Test
    @DisplayName("没有构建序号时回落到提交短 sha")
    void fallsBackToShortSha() {
        assertEquals("e075cd6", BuiltInPipelineEngine.versionLabel(null, "prod", 0, "e075cd6abc"));
    }
}
