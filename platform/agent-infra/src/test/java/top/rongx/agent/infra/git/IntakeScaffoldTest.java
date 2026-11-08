package top.rongx.agent.infra.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.IntakeProjectStore;
import top.rongx.agent.infra.scaffold.RecipeScaffolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接入流程验收：导入一个**没有 ci/ 目录**的 Java 项目，接入完成后
 * 平台应自动写出 `ci/pipeline.yml` + `ci/actions.yml`（用户反馈的核心诉求）。
 */
class IntakeScaffoldTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("接入后自动生成流水线脚本（无需手工复制模板）")
    void intakeWritesPipelineScripts() throws Exception {
        Path repo = workspace.resolve("demo-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre\n");
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");

        IntakeProjectStore store = new IntakeProjectStore(workspace.resolve("_platform/projects.db"));
        ProjectIntakeService service = new ProjectIntakeService(
                new GitWorktreeManager(workspace), store, workspace.toString(), new RecipeScaffolder());

        service.submit(new ProjectIntakeService.IntakeRequest(
                "DEMO", repo.toString(), null, "java", false, "DIRECT", null));

        ProjectIntakeService.IntakeState state = awaitRegistered(service, "DEMO");
        assertEquals("REGISTERED", state.phase(), state.message());

        // 脚本已落盘
        assertTrue(Files.isRegularFile(repo.resolve("ci/pipeline.yml")),
                "接入后应自动生成 ci/pipeline.yml");
        assertTrue(Files.isRegularFile(repo.resolve("ci/actions.yml")),
                "接入后应自动生成 ci/actions.yml");

        String pipeline = Files.readString(repo.resolve("ci/pipeline.yml"), StandardCharsets.UTF_8);
        assertTrue(pipeline.contains("deploy:"), pipeline);
        assertTrue(pipeline.contains("type: deploy"), pipeline);
        assertTrue(pipeline.contains("type: rollback"), pipeline);

        // 报告里要能看出"已生成"，而不是让用户去复制模板
        Map<String, Object> report = state.report();
        @SuppressWarnings("unchecked")
        List<String> written = (List<String>) report.get("scaffoldWritten");
        assertTrue(written != null && written.contains("ci/pipeline.yml"), String.valueOf(report));
        assertTrue((Boolean) report.get("ready"), String.valueOf(report.get("warnings")));
        assertTrue(state.message().contains("已自动生成"), state.message());
    }

    @Test
    @DisplayName("已有 ci/pipeline.yml 时不被覆盖")
    void existingPipelineIsKept() throws Exception {
        Path repo = workspace.resolve("demo-repo2");
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("ci/pipeline.yml"), "# 手工维护\n", StandardCharsets.UTF_8);
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");

        ProjectIntakeService service = new ProjectIntakeService(
                new GitWorktreeManager(workspace),
                new IntakeProjectStore(workspace.resolve("_platform2/projects.db")),
                workspace.toString(), new RecipeScaffolder());
        service.submit(new ProjectIntakeService.IntakeRequest(
                "DEMO2", repo.toString(), null, "java", false, "DIRECT", null));
        awaitRegistered(service, "DEMO2");

        assertEquals("# 手工维护\n",
                Files.readString(repo.resolve("ci/pipeline.yml"), StandardCharsets.UTF_8),
                "已有文件不得被覆盖");
    }

    private ProjectIntakeService.IntakeState awaitRegistered(ProjectIntakeService service, String id)
            throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            ProjectIntakeService.IntakeState state = service.status(id);
            if ("REGISTERED".equals(state.phase()) || "INTAKE_FAILED".equals(state.phase())) {
                return state;
            }
            TimeUnit.MILLISECONDS.sleep(150);
        }
        throw new AssertionError("接入未在 30s 内完成：" + service.status(id));
    }

    private static String sh(Path dir, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "命令超时：" + String.join(" ", argv));
        assertEquals(0, p.exitValue(), "命令失败：" + String.join(" ", argv) + "\n" + out);
        return out;
    }
}
