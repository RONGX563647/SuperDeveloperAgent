package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.PipelineEngine.StagePreview;

/**
 * 预检：镜像阶段需要的 Dockerfile 缺席时必须挡住。
 *
 * <p>回归背景（run-a3bfe538）：平台定义是 host 目标却带 image_package 阶段，
 * 旧逻辑只在「docker 目标 + 无镜像阶段」时才检查 Dockerfile；预检放行后
 * image 阶段必然 {@code docker build} 失败（E-101），自动修复又因为"预检无阻塞"
 * 直接续跑同一提交，形成「修了也没用」的死循环。
 */
class ImageStageDryRunTest {

    @TempDir
    Path repo;

    private static StagePreview imageStage(String command) {
        return new StagePreview("image", "action", "image_package", "", "",
                "", "sh", command, "", "", false, "host");
    }

    @Test
    @DisplayName("有 Dockerfile 时不算阻塞")
    void dockerfilePresentIsFine() throws Exception {
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:21-jre\n");

        assertNull(BuiltInPipelineEngine.missingImageDockerfile(
                repo, List.of(imageStage("docker build -f Dockerfile -t ci-test-abc ."))));
    }

    @Test
    @DisplayName("host 目标自带镜像阶段也要求 Dockerfile")
    void missingDockerfileBlocksEvenForHostTarget() {
        assertEquals("Dockerfile", BuiltInPipelineEngine.missingImageDockerfile(
                repo, List.of(imageStage("docker build -f Dockerfile -t ci-test-abc ."))));
    }

    @Test
    @DisplayName("自定义 -f 路径按命令里的路径判断，不误判根目录")
    void customDockerfilePathIsHonored() throws Exception {
        Files.createDirectories(repo.resolve("deploy"));
        Files.writeString(repo.resolve("deploy/Dockerfile"), "FROM scratch\n");

        assertNull(BuiltInPipelineEngine.missingImageDockerfile(
                repo, List.of(imageStage("docker build --file deploy/Dockerfile -t x ."))));
        assertEquals("docker/Dockerfile", BuiltInPipelineEngine.missingImageDockerfile(
                repo, List.of(imageStage("docker build -f docker/Dockerfile -t x ."))));
    }

    @Test
    @DisplayName("没有镜像阶段时不做这项检查")
    void noImageStageIsNotChecked() {
        StagePreview build = new StagePreview("build", "action", "backend_build", "", "",
                "", "sh", "mvn -B package", "", "", false, "host");

        assertNull(BuiltInPipelineEngine.missingImageDockerfile(repo, List.of(build)));
    }
}
