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
 * 配方自检报告：
 * <ul>
 *   <li>缺 pipeline.yml 时按仓库结构生成真实脚本（不写 PORT 占位）；</li>
 *   <li>缺 Dockerfile 且目标为 docker 是**阻塞项**（ready=false），不再自相矛盾；</li>
 *   <li>只检查最终定义真正引用的动作；诊断按 blocker/suggestion/info 分级且去重。</li>
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
    @DisplayName("C1 空仓库：能生成脚本，但无 Dockerfile → 有阻塞项、ready=false")
    void emptyRepoGeneratesScript() {
        Map<String, Object> report = runCheck();

        assertFalse((Boolean) report.get("ready"), "无 Dockerfile 的 docker 目标不可部署，不应显示就绪");
        assertFalse((Boolean) report.get("deployable"));

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
        List<Map<String, Object>> blockers = (List<Map<String, Object>>) report.get("blockers");
        assertTrue(blockers.stream().anyMatch(b -> "DOCKERFILE_MISSING".equals(b.get("code"))),
                String.valueOf(blockers));

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) report.get("warnings");
        assertEquals(1, warnings.stream().filter(w -> w.contains("Dockerfile")).count(),
                "同一根因只应出现一次：" + warnings);
    }

    @Test
    @DisplayName("C2 完整仓库：ready=true；只检查定义真正引用的动作，无关动作不刷屏")
    void fullRepo() throws Exception {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/actions.yml"), """
                actions:
                  backend_build:
                    run: ["mvn", "package"]
                  backend_test:
                    run: ["mvn", "test"]
                """);
        Files.writeString(repo.resolve("ci/pipeline.yml"), """
                name: release
                deploy:
                  target: docker
                stages:
                  - id: build
                    type: action
                    action: backend_build
                """);
        Files.writeString(repo.resolve("Dockerfile"), "FROM scratch\n");

        Map<String, Object> report = runCheck();
        assertTrue((Boolean) report.get("ready"), String.valueOf(report.get("warnings")));
        @SuppressWarnings("unchecked")
        Map<String, String> actions = (Map<String, String>) report.get("actions");
        assertEquals("ci/actions.yml#backend_build", actions.get("backend_build"));
        assertFalse(actions.containsKey("backend_test"), "未被定义引用的动作不应出现在报告里");
        assertFalse(actions.containsKey("frontend_build"), "未被定义引用的动作不应出现在报告里");
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
    @DisplayName("C4 Java 工程：生成的 pipeline 含 build/deploy/health + workdir 规则")
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
        assertTrue((Boolean) report.get("ready"), String.valueOf(report.get("warnings")));
    }

    @Test
    @DisplayName("C5 识别结果结构化：端口/健康路径/目标/工作目录都能从报告里读到")
    void summaryIsStructured() throws Exception {
        Files.createDirectories(repo.resolve("app/src/main/resources"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Files.writeString(repo.resolve("app/src/main/resources/application.yml"), """
                server:
                  port: 8090
                management:
                  endpoints:
                    web:
                      exposure:
                        include: health
                """);

        Map<String, Object> report = runCheck();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals("host", summary.get("deployTarget"), "无 Dockerfile 的纯 Java 应降级 HOST");
        assertEquals(8090, summary.get("port"));
        assertEquals("/actuator/health", summary.get("healthPath"));
        assertTrue(String.valueOf(summary.get("portSource")).contains("application.yml"));
        assertEquals(Boolean.TRUE, summary.get("deployable"));
    }
}
