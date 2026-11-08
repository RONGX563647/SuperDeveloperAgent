package top.rongx.agent.infra.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配方自检报告：动作覆盖、pipeline 模板、Dockerfile 检查。 */
class RecipeCheckTest {

    @TempDir
    Path repo;

    private Map<String, Object> runCheck() {
        ProjectIntakeService svc = new ProjectIntakeService(
                new GitWorktreeManager(repo.getParent()),
                repo.getParent().toString());
        return svc.recipeCheck(repo);
    }

    @Test
    @DisplayName("C1 空仓库：ready=false，warnings 指路，带 pipeline 模板")
    void emptyRepo() {
        Map<String, Object> report = runCheck();
        assertFalse((Boolean) report.get("ready"));
        assertTrue(report.containsKey("pipelineTemplate"), "缺 pipeline 时应给出模板");
        @SuppressWarnings("unchecked")
        java.util.List<String> warnings = (java.util.List<String>) report.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Dockerfile")));
    }

    @Test
    @DisplayName("C2 完整仓库：ready=true，6 个动作有解析来源")
    void fullRepo() throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/actions.yml"), """
                actions:
                  backend_build:
                    run: ["mvn", "package"]
                  backend_test:
                    run: ["mvn", "test"]
                """);
        Files.writeString(repo.resolve("ci/pipeline.yml"), "name: release\nstages: []\n");
        Files.writeString(repo.resolve("Dockerfile"), "FROM scratch\n");

        Map<String, Object> report = runCheck();
        assertTrue((Boolean) report.get("ready"), String.valueOf(report.get("warnings")));
        @SuppressWarnings("unchecked")
        Map<String, String> actions = (Map<String, String>) report.get("actions");
        assertEquals("ci/actions.yml#backend_build", actions.get("backend_build"));
        assertEquals("ci/actions.yml#backend_test", actions.get("backend_test"));
        assertEquals("内置默认（mvn / pnpm）", actions.get("frontend_build"));
    }

    @Test
    @DisplayName("C3 Makefile 回退：无 actions.yml 时按 target 命名匹配")
    void makefileFallback() throws Exception {
        Files.writeString(repo.resolve("Makefile"), """
                backend-build:
                \techo building
                """);
        Map<String, Object> report = runCheck();
        @SuppressWarnings("unchecked")
        Map<String, String> actions = (Map<String, String>) report.get("actions");
        assertEquals("Makefile#backend-build", actions.get("backend_build"));
    }
}
