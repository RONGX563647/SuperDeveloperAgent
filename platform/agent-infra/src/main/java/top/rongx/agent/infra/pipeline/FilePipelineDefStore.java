package top.rongx.agent.infra.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.PipelineDefStore;

/**
 * 平台侧流水线定义的落盘实现：{@code <workspaceRoot>/<projectId>/ci/{pipeline.yml,actions.yml}}。
 *
 * <p>目录形状刻意与仓库根一致（其下有 {@code ci/}），这样解析与配方查找复用同一套代码，
 * 不需要为"平台定义"写第二套读取逻辑。
 */
@Component
public class FilePipelineDefStore implements PipelineDefStore {

    private static final Logger log = LoggerFactory.getLogger(FilePipelineDefStore.class);

    private final Path workspaceRoot;

    public FilePipelineDefStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    @Override
    public Path rootOf(String projectId) {
        // projectId 已由接入侧校验（大写字母开头），这里再挡一次路径穿越
        if (projectId == null || !projectId.matches("[A-Za-z0-9_\\-]{1,32}")) {
            throw new IllegalArgumentException("非法 projectId：" + projectId);
        }
        return workspaceRoot.resolve(projectId);
    }

    @Override
    public boolean exists(String projectId) {
        return Files.isRegularFile(pipelineFile(projectId));
    }

    @Override
    public Optional<String> read(String projectId) {
        return readIfPresent(pipelineFile(projectId));
    }

    @Override
    public boolean write(String projectId, String pipelineYaml, String actionsYaml) {
        try {
            Path pipeline = pipelineFile(projectId);
            Files.createDirectories(pipeline.getParent());
            Files.writeString(pipeline, pipelineYaml, StandardCharsets.UTF_8);
            if (actionsYaml != null && !actionsYaml.isBlank()) {
                Files.writeString(actionsFile(projectId), actionsYaml, StandardCharsets.UTF_8);
            }
            log.info("平台侧流水线定义已保存：{}", pipeline);
            return true;
        } catch (IOException e) {
            // 存不下来不能吞掉：调用方要能退回"仅本次使用"，并在日志里留痕
            log.warn("平台侧流水线定义保存失败 projectId={}：{}", projectId, e.getMessage());
            return false;
        }
    }

    private Path pipelineFile(String projectId) {
        return rootOf(projectId).resolve("ci").resolve("pipeline.yml");
    }

    private Path actionsFile(String projectId) {
        return rootOf(projectId).resolve("ci").resolve("actions.yml");
    }

    private Optional<String> readIfPresent(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取流水线定义失败 {}：{}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
