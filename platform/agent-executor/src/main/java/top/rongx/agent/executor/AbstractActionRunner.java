package top.rongx.agent.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.run.StageResult;

/**
 * 动作执行骨架：预算、成功/失败、产物、日志尾部。
 *
 * <p>子类只决定"这一步实际怎么跑"——宿主进程（{@link ProcessActionRunner}）
 * 还是容器（{@link DockerActionRunner}）。其余语义完全相同，避免两套实现漂移。
 */
public abstract class AbstractActionRunner implements ActionRunner {

    /** 产物数量上限，避免扫描出几万个文件。 */
    private static final int MAX_ARTIFACTS = 50;

    protected final ProcessExecutor executor = new ProcessExecutor();

    /** 把一条业务命令翻译成真正要执行的命令。 */
    protected abstract List<String> effectiveArgv(Path workspace, List<String> argv);

    @Override
    public ActionResult run(Path workspace, ActionSpec spec) {
        long started = System.nanoTime();
        StringBuilder sink = new StringBuilder();
        long budgetMillis = spec.timeout().toMillis();

        for (List<String> argv : spec.steps()) {
            List<String> effective = effectiveArgv(workspace, argv);
            long stepStart = System.nanoTime();
            ProcessExecutor.StepOutcome outcome = executor.exec(workspace, effective, budgetMillis, sink);
            budgetMillis -= Math.max(0, (System.nanoTime() - stepStart) / 1_000_000L);

            if (outcome.errorCode() != null) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                return new ActionResult(StageResult.failed(spec.action().wireName(), outcome.exitCode(),
                        outcome.errorCode(), outcome.summary(), costMs), ProcessExecutor.tail(sink));
            }
            if (budgetMillis <= 0) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                return new ActionResult(StageResult.failed(spec.action().wireName(), 124, "E-302",
                        "动作总预算耗尽，已终止", costMs), ProcessExecutor.tail(sink));
            }
        }

        List<ArtifactRef> artifacts = expandArtifacts(workspace, spec.produce());
        long costMs = (System.nanoTime() - started) / 1_000_000L;
        return new ActionResult(StageResult.success(spec.action().wireName(), 0, artifacts, costMs),
                ProcessExecutor.tail(sink));
    }

    // ---------------------------------------------------------------- 产物

    private List<ArtifactRef> expandArtifacts(Path workspace, List<String> patterns) {
        if (patterns.isEmpty()) {
            return List.of();
        }
        FileSystem fs = FileSystems.getDefault();
        List<ArtifactRef> found = new ArrayList<>();
        for (String pattern : patterns) {
            PathMatcher matcher = fs.getPathMatcher("glob:" + pattern);
            try (Stream<Path> stream = Files.walk(workspace)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> matcher.matches(workspace.relativize(file)))
                        .limit(MAX_ARTIFACTS - found.size())
                        .forEach(file -> found.add(toArtifact(workspace, file)));
            } catch (IOException e) {
                ProcessExecutor.warn("扫描产物失败 " + pattern + ": " + e.getMessage());
            }
            if (found.size() >= MAX_ARTIFACTS) {
                break;
            }
        }
        return found;
    }

    private ArtifactRef toArtifact(Path workspace, Path file) {
        String relative = workspace.relativize(file).toString().replace('\\', '/');
        long size = 0;
        String digest = null;
        try {
            size = Files.size(file);
            digest = sha256(file);
        } catch (IOException e) {
            ProcessExecutor.warn("读取产物失败 " + relative + ": " + e.getMessage());
        }
        return new ArtifactRef("file", relative, digest, size, Map.of("source", "action"));
    }

    private String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                md.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
