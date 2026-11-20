package top.rongx.agent.gateway.gitissue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 任务默认版本号：按当前分支版本线推导，非版本分支退回最新标签。 */
class GitIssueVersionSuggestionTest {

    @Test
    @DisplayName("版本线分支：取该线最大补丁 +1，无标签时用 x.y.0")
    void suggestsNextPatchOnVersionBranch() {
        assertEquals("0.1.3", GitIssueServiceImpl.suggestVersion(
                "0.1.0-snapshot", List.of("0.1.2", "0.1.1", "0.0.9")));
        assertEquals("0.2.0", GitIssueServiceImpl.suggestVersion("0.2.0-snapshot", List.of()));
        assertEquals("0.2.0", GitIssueServiceImpl.suggestVersion(
                "0.2.0-snapshot", List.of("0.1.9")), "其他版本线的标签不参与");
    }

    @Test
    @DisplayName("非版本分支或仓库不可用：退回最新标签，没有标签则为空")
    void fallsBackToLatestTag() {
        assertEquals("0.3.1", GitIssueServiceImpl.suggestVersion(
                "feat/board-version", List.of("0.3.1", "0.3.0")));
        assertEquals("", GitIssueServiceImpl.suggestVersion("master", List.of()));
        assertEquals("", GitIssueServiceImpl.suggestVersion("", null));
    }

    @Test
    @DisplayName("Feat 取下一个次版本：版本线已发过就往后推，不给已存在的版本")
    void featTakesNextMinorOnVersionBranch() {
        // 分支 0.1.0、只发过 0.1.0 → 下一个次版本是 0.2.0（Bug 同条件给的是 0.1.1）
        assertEquals("0.2.0",
                GitIssueServiceImpl.suggestVersion("0.1.0-snapshot", List.of("0.1.0"), "FEAT"));
        // 0.2.0 已经发过 → 不能再建议它，只能到 0.3.0
        assertEquals("0.3.0",
                GitIssueServiceImpl.suggestVersion("0.1.0-snapshot",
                        List.of("0.2.0", "0.1.0"), "FEAT"));
    }

    @Test
    @DisplayName("Feat 在还没发布过的版本线上：用该版本线的 x.y.0")
    void featOnUnreleasedBranchLine() {
        assertEquals("0.2.0",
                GitIssueServiceImpl.suggestVersion("0.2.0-snapshot", List.of(), "FEAT"));
        assertEquals("0.1.0",
                GitIssueServiceImpl.suggestVersion("0.1.0-snapshot", List.of(), "FEAT"));
    }

    @Test
    @DisplayName("Feat 在非版本分支上：从最新标签往后取一个次版本")
    void featOnNonVersionBranch() {
        assertEquals("0.4.0", GitIssueServiceImpl.suggestVersion(
                "feat/board-version", List.of("0.3.1", "0.3.0"), "FEAT"));
        // 没有标签就没有可推的基线，仍然为空
        assertEquals("", GitIssueServiceImpl.suggestVersion("master", List.of(), "FEAT"));
    }

    @Test
    @DisplayName("kind 缺省或为空按 Bug：仍取当前版本线的最大补丁 +1")
    void missingKindFallsBackToPatch() {
        assertEquals("0.1.1",
                GitIssueServiceImpl.suggestVersion("0.1.0-snapshot", List.of("0.1.0"), null));
        assertEquals("0.1.1",
                GitIssueServiceImpl.suggestVersion("0.1.0-snapshot", List.of("0.1.0"), ""));
        // 小写 kind 同样识别，避免前后端大小写口径不一致
        assertEquals("0.2.0",
                GitIssueServiceImpl.suggestVersion("0.1.0-snapshot", List.of("0.1.0"), "feat"));
    }
}
