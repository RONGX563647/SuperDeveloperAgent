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
}
