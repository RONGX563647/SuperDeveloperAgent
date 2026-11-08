package top.rongx.agent.infra.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.IntakeProjectStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配方自检报告（方案 C 之后）：
 * <ul>
 *   <li>缺 pipeline.yml 不再是"警告 + PORT 占位模板"，而是**按仓库结构生成真实脚本**；</li>
 *   <li>deploy/rollback 由平台部署器承担，不再要求 stack_deploy/stack_rollback 配方；</li>
 *   <li>缺 Dockerfile 只影响镜像 stage，不阻塞接入。</li>
 * </ul>
 */
class RecipeCheckTest {

    @TempDir
    Path repo;

    private Map<String, Object> runCheck() {
        ProjectIntakeService svc = new ProjectIntakeService(
                new GitWorktreeManager(repo.getParent()),
                new IntakeProjectStore(repo.getParent().resolve("_platform/projects.db")),
                repo.getParent().toString());
        return svc.recipeCheck(repo);
    }

    @Test
    @DisplayName("C1 空仓库：可接入（ready），自动生成脚本，模板不再含 PORT 占位")
    void emptyRepoGeneratesScript() {
        Map<String, Object> report = runCheck();

        assertTrue((Boolean) report.get("ready"), "能自动生成脚本就应视为可接入");

        String template = (String) report.get("pipelineTemplate");
        assertNotNull(template, "缺 pipeline 时应给出生成结果");
        assertTrue(template.contains("deploy:"), template);
        assertTrue(template.contains("target: docker"), template);
        assertTrue(template.contains("type: deploy"), template);
        assertFalse(template.contains("PORT"), "不应再有 PORT 占位：" + template);
        assertFalse(template.contains("stack_deploy"), "不应再引用旧的部署动作：" + template);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> files = (List<Map<String, String>>) report.get("scaffoldFiles");
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> f.get("path").equals("ci/actions.yml")));

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) report.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Dockerfile")), String.valueOf(warnings));
    }

    @Test
    @DisplayName("C2 完整仓库：ready=true；只检查构建类动作（部署由平台承担）")
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
        assertFalse(actions.containsKey("stack_deploy"), "部署动作不再属于配方检查范围");
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

    @Test
    @DisplayName("C4 Java 工程：生成的 pipeline 含 build/test/deploy/health + workdir 规则")
    void javaRepoGeneratedPipeline() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre");

        Map<String, Object> report = runCheck();
        String template = (String) report.get("pipelineTemplate");
        assertTrue(template.contains("action: backend_build"), template);
        assertTrue(template.contains("action: image_package"), template);
        assertTrue(template.contains("type: health"), template);
        assertTrue(template.contains("type: rollback"), template);
        assertEquals("JAVA", report.get("stack"));
    }
}
