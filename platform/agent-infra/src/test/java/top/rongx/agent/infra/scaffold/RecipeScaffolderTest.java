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
    @DisplayName("Java 单模块 + Dockerfile → 生成 build/image/deploy/health + rollback（不再拆 test stage）")
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
        // build 与 test 合成一个 stage：两步会各编译一遍源码
        assertFalse(pipeline.contains("action: backend_test"), pipeline);
        assertTrue(pipeline.contains("action: image_package"), pipeline);
        assertTrue(pipeline.contains("type: deploy"), pipeline);
        assertTrue(pipeline.contains("type: health"), pipeline);
        assertTrue(pipeline.contains("type: rollback"), pipeline);

        String actions = content(plan, "ci/actions.yml");
        assertTrue(actions.contains("backend_build:"), actions);
        assertTrue(actions.contains("\"verify\""), actions);
        // 动作级超时：构建 10m、镜像 20m
        assertTrue(actions.contains("timeout: 10m"), actions);
        assertTrue(actions.contains("timeout: 20m"), actions);
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
        // package.json 是空对象：没有 test 脚本，不生成前端测试 stage
        assertFalse(pipeline.contains("action: frontend_test"), pipeline);
    }

    @Test
    @DisplayName("前端 package.json 有 test 脚本 → 生成 frontend_test stage 与 20m 超时")
    void frontendTestStage(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("web"));
        Files.writeString(repo.resolve("web/package.json"), """
                { "scripts": { "test": "vitest run" } }
                """);

        ScaffoldPlan plan = scaffolder.plan("WEB", repo, DeployTarget.DOCKER);

        String pipeline = content(plan, "ci/pipeline.yml");
        assertTrue(pipeline.contains("action: frontend_test"), pipeline);
        String actions = content(plan, "ci/actions.yml");
        assertTrue(actions.contains("frontend_test:"), actions);
        assertTrue(actions.contains("\"test\""), actions);
    }

    @Test
    @DisplayName("npm init 占位 test 脚本不算测试 → 不生成 frontend_test")
    void placeholderTestScriptSkipped(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("web"));
        Files.writeString(repo.resolve("web/package.json"), """
                { "scripts": { "test": "echo \\"Error: no test specified\\" && exit 1" } }
                """);

        ScaffoldPlan plan = scaffolder.plan("WEB", repo, DeployTarget.DOCKER);

        assertFalse(content(plan, "ci/pipeline.yml").contains("action: frontend_test"));
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
    @DisplayName("仓库根下一个工程目录（RuoYi-Vue 布局）→ 识别嵌套 Java + UI，不误判 UNKNOWN")
    void nestedProject(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("RuoYi-Vue/ruoyi-ui"));
        Files.writeString(repo.resolve("RuoYi-Vue/pom.xml"), "<project/>");
        Files.writeString(repo.resolve("RuoYi-Vue/ruoyi-ui/package.json"), "{}");
        // Dockerfile 自己会 mvn 编译：不再重复生成 build/test
        Files.writeString(repo.resolve("Dockerfile"), """
                FROM maven:3.9 AS build
                RUN mvn -B -q -DskipTests package
                FROM eclipse-temurin:17-jre
                RUN curl -sf http://localhost:8687/ > /dev/null
                EXPOSE 8687 8688
                HEALTHCHECK CMD curl -sf http://localhost:8687/ && curl -s http://localhost:8688/login
                """);

        ScaffoldPlan plan = scaffolder.plan("RUOYIWNZ", repo, DeployTarget.DOCKER);

        assertEquals("JAVA_NODE", plan.stack());
        String pipeline = content(plan, "ci/pipeline.yml");
        assertTrue(pipeline.contains("action: image_package"), pipeline);
        assertFalse(pipeline.contains("action: backend_build"), "镜像内已编译，不应重复 build");
        // 端口/健康路径取 Dockerfile（旧实现写死 8080/healthz，RuoYi 部署必挂）
        assertTrue(pipeline.contains("port: 8687"), pipeline);
        assertTrue(pipeline.contains("healthPath: /"), pipeline);
        assertTrue(pipeline.contains("url: http://127.0.0.1:8687/"), pipeline);
    }

    @Test
    @DisplayName("docker/.env.example → deploy.env-required 自动生成（只取名字，不取值）")
    void detectsRequiredEnvFromEnvExample(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("RuoYi-Vue/docker"));
        Files.writeString(repo.resolve("RuoYi-Vue/pom.xml"), "<project/>");
        Files.writeString(repo.resolve("RuoYi-Vue/docker/.env.example"), """
                # 外部数据库地址（必填）
                DB_HOST=
                DB_PORT=3306
                REDIS_HOST=
                TOKEN_SECRET=change-me
                BAD NAME=skip-me
                """);

        ScaffoldPlan plan = scaffolder.plan("ENVD", repo, DeployTarget.DOCKER);

        String pipeline = content(plan, "ci/pipeline.yml");
        assertTrue(pipeline.contains("env-required:"), pipeline);
        assertTrue(pipeline.contains("- DB_HOST"), pipeline);
        assertTrue(pipeline.contains("- REDIS_HOST"), pipeline);
        assertTrue(pipeline.contains("- TOKEN_SECRET"), pipeline);
        assertFalse(pipeline.contains("change-me"), "示例值绝不进流水线定义");
        assertFalse(pipeline.contains("BAD"), pipeline);
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("env-required")),
                String.valueOf(plan.notes()));
    }

    @Test
    @DisplayName("嵌套 Java 工程 + 纯运行时 Dockerfile → build/test 带 workdir")
    void nestedJavaWithoutBuildInDockerfile(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("server"));
        Files.writeString(repo.resolve("server/pom.xml"), "<project/>");
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:17-jre\nEXPOSE 9090\n");

        ScaffoldPlan plan = scaffolder.plan("X", repo, DeployTarget.DOCKER);

        assertEquals("JAVA", plan.stack());
        String pipeline = content(plan, "ci/pipeline.yml");
        assertTrue(pipeline.contains("workdir: server"), pipeline);
        assertTrue(pipeline.contains("action: backend_build"), pipeline);
        assertTrue(pipeline.contains("port: 9090"), pipeline);
        // 没有 HEALTHCHECK：默认健康路径 /
        assertTrue(pipeline.contains("healthPath: /"), pipeline);
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
