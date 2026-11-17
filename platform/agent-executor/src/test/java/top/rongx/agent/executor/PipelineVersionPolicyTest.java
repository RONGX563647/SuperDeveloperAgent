package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.release.ReleaseVersion;

class PipelineVersionPolicyTest {

    @Test
    @DisplayName("同版本线补丁号必须连续，不能重复、倒退或跳号")
    void requiresSequentialPatch() {
        List<String> tags = List.of("0.1.0", "0.1.1");
        assertNull(BuiltInPipelineEngine.versionSequenceProblem(
                ReleaseVersion.parse("0.1.2"), tags));
        assertEquals("补丁号不能跳号：0.1 已发布到 0.1.1，下一个版本必须是 0.1.2",
                BuiltInPipelineEngine.versionSequenceProblem(
                        ReleaseVersion.parse("0.1.3"), tags));
        assertEquals("标签 0.1.1 已存在，请递增补丁号",
                BuiltInPipelineEngine.versionSequenceProblem(
                        ReleaseVersion.parse("0.1.1"), tags),
                "重复版本先按标签重复拒绝");
        assertEquals("版本 0.1.0 必须大于已发布的最大版本 0.1.1",
                BuiltInPipelineEngine.versionSequenceProblem(
                        ReleaseVersion.parse("0.1.0"), List.of("0.1.1")));
    }

    @Test
    @DisplayName("新版本线首个版本必须是 x.y.0，其它线标签不影响判断")
    void firstVersionOfNewLine() {
        List<String> tags = List.of("0.1.0", "0.1.1", "0.1.2");
        assertNull(BuiltInPipelineEngine.versionSequenceProblem(
                ReleaseVersion.parse("0.2.0"), tags));
        assertEquals("版本线 0.2 还没有基础版本，第一个版本必须是 0.2.0",
                BuiltInPipelineEngine.versionSequenceProblem(
                        ReleaseVersion.parse("0.2.1"), tags));
    }

    @Test
    @DisplayName("无关标签（v 前缀/自由文本）不会参与补丁序列")
    void ignoresUnrelatedTags() {
        List<String> tags = List.of("v0.1.0", "latest", "0.1.0");
        assertNull(BuiltInPipelineEngine.versionSequenceProblem(
                ReleaseVersion.parse("0.1.1"), tags));
    }
}
