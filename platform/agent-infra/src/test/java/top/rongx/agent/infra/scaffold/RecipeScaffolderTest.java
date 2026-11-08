package top.rongx.agent.infra.scaffold;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚手架生成规则测试（方案 C）：导入项目即可产出可跑通的 ci/ 脚本。
 */
class RecipeScaffolderTest {

    private final RecipeScaffolder scaffolder = new RecipeScaffolder();

    @Test
    @DisplayName("Java 单模块 + Dockerfile → 生成 build/test/image/deploy/health + rollback")
    void javaProject(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre");

        ScaffoldPlan plan = scaffolder.plan("RY", repo, DeployTarget.DOCKER);

        assertEquals("JAVA", plan.stack());
        String pipeline = content(plan, "ci/pipeline.yml");
        assertTrue(pipeline.contains("name: release"), pipeline);
        assertTrue(pipeline.contains("target: docker"), pipeline);
        assertTrue(pipeline.contains("type: action"), pipeline);
        assertTrue(pipeline.contains("action: backend_build"), pipeline);
        assertTrue(pipeline.contains("action: image_package"), pipeline);
        assertTrue(pipeline.contains("type: deploy"), pipeline);
        assertTrue(pipeline.contains("type: health"), pipeline);
        assertTrue(pipeline.contains("type: rollback"), pipeline);

        String actions = content(plan, "ci/actions.yml");
        assertTrue(actions.contains("backend_build:"), actions);
        assertTrue(actions.contains("mvn"), actions);
    }

    @Test
    @DisplayName("monorepo（backend + frontend）→ 两个 stage 带 workdir，包管理器按锁文件识别")
    void monorepo(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("backend"));
        Files.createDirectories(repo.resolve("frontend"));
        Files.writeString(repo.resolve("backend/pom.xml"), "<project/>");
        Files.writeString(repo.resolve("frontend/package.json"), "{}");
        Files.writeString(repo.resolve("frontend/pnpm-lock.yaml"), "lockfileVersion: 9");

        ScaffoldPlan plan = scaffolder.plan("RY", repo, DeployTarget.HOST);

        assertEquals("JAVA_NODE", plan.stack());
        String pipeline = content(plan, "ci/pipeline.yml");
        assertTrue(pipeline.contains("workdir: backend"), pipeline);
        assertTrue(pipeline.contains("workdir: frontend"), pipeline);
        assertTrue(pipeline.contains("target: host"), pipeline);
        // HOST 目标无 Dockerfile 时不生成 image stage
        assertFalse(pipeline.contains("action: image_package"), pipeline);

        String actions = content(plan, "ci/actions.yml");
        assertTrue(actions.contains("pnpm"), actions);
    }

    @Test
    @DisplayName("未识别项目 → 生成骨架 + UNKNOWN 告警，不抛异常")
    void unknownStack(@TempDir Path repo) {
        ScaffoldPlan plan = scaffolder.plan("X", repo, DeployTarget.DOCKER);
        assertEquals("UNKNOWN", plan.stack());
        assertFalse(plan.notes().isEmpty());
        assertTrue(content(plan, "ci/pipeline.yml").contains("type: deploy"));
    }

    @Test
    @DisplayName("落盘：默认不覆盖已有文件；overwrite=true 才覆盖")
    void applyRespectsOverwrite(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        ScaffoldPlan plan = scaffolder.plan("RY", repo, DeployTarget.DOCKER);

        List<String> first = scaffolder.apply("RY", repo, plan, false);
        assertTrue(first.contains("ci/pipeline.yml"), String.valueOf(first));

        Files.writeString(repo.resolve("ci/pipeline.yml"), "# 人工修改过\n");
        List<String> second = scaffolder.apply("RY", repo, plan, false);
        assertFalse(second.contains("ci/pipeline.yml"), "不应覆盖已存在文件");
        assertEquals("# 人工修改过\n",
                Files.readString(repo.resolve("ci/pipeline.yml"), StandardCharsets.UTF_8));

        List<String> third = scaffolder.apply("RY", repo, plan, true);
        assertTrue(third.contains("ci/pipeline.yml"), "overwrite=true 应覆盖");
        assertTrue(Files.readString(repo.resolve("ci/pipeline.yml")).contains("name: release"));
    }

    private String content(ScaffoldPlan plan, String path) {
        return plan.files().stream()
                .filter(f -> f.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少文件：" + path))
                .content();
    }
}
