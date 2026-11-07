package top.rongx.agent.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.run.StageResult;

/**
 * 动作执行器（执行面，确定性代码，不调用任何模型）。
 *
 * <p>规约对齐：
 * <ul>
 *   <li>只用 {@code ProcessBuilder(List)}，**不经 shell**（规约 §10.4）；</li>
 *   <li>超时 → E-302；取消/中断 → E-303；命令无法启动 → E-209；退出码非 0 → E-101（用户代码失败，不重试）；</li>
 *   <li>输出有上限，避免大日志撑爆内存；产物做 sha256 记账。</li>
 * </ul>
 *
 * <p><b>当前为 P0 验证版</b>：直接在宿主进程执行，尚未套容器沙箱。
 * 正式版把 {@link #runOne} 换成"容器内执行"，其余逻辑不变。
 */
@Component
public class ProcessActionRunner implements ActionRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessActionRunner.class);

    /** 单次动作日志上限（字符）。 */
    private static final int MAX_LOG_CHARS = 200_000;
    /** 产物数量上限，避免扫描出几万个文件。 */
    private static final int MAX_ARTIFACTS = 50;

    @Override
    public ActionResult run(Path workspace, ActionSpec spec) {
        long started = System.nanoTime();
        StringBuilder sink = new StringBuilder();
        long budgetMillis = spec.timeout().toMillis();

        for (List<String> argv : spec.steps()) {
            long stepStart = System.nanoTime();
            StepOutcome outcome = runOne(workspace, argv, budgetMillis, sink);
            budgetMillis -= Math.max(0, (System.nanoTime() - stepStart) / 1_000_000L);

            if (outcome.errorCode() != null) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                log.info("动作 {} 失败 {}（{}ms）：{}", spec.action().wireName(),
                        outcome.errorCode(), costMs, outcome.summary());
                return new ActionResult(StageResult.failed(spec.action().wireName(), outcome.exitCode(),
                        outcome.errorCode(), outcome.summary(), costMs), tail(sink));
            }
            if (budgetMillis <= 0) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                return new ActionResult(StageResult.failed(spec.action().wireName(), 124, "E-302",
                        "动作总预算耗尽，已终止", costMs), tail(sink));
            }
        }

        List<ArtifactRef> artifacts = expandArtifacts(workspace, spec.produce());
        long costMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("动作 {} 成功（{}ms，产物 {} 个）", spec.action().wireName(), costMs, artifacts.size());
        return new ActionResult(StageResult.success(spec.action().wireName(), 0, artifacts, costMs),
                tail(sink));
    }

    /** 日志尾部：最后 30 行且不超过 4000 字符（交给模型的是摘要，不是全文）。 */
    private String tail(StringBuilder sink) {
        String text;
        synchronized (sink) {
            text = sink.toString();
        }
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - 30);
        String joined = String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
        if (joined.length() > 4000) {
            joined = "…" + joined.substring(joined.length() - 4000);
        }
        return joined;
    }

    // ---------------------------------------------------------------- 单步执行

    private record StepOutcome(int exitCode, String errorCode, String summary) {
        static StepOutcome ok() {
            return new StepOutcome(0, null, null);
        }

        static StepOutcome failed(int exitCode, String errorCode, String summary) {
            return new StepOutcome(exitCode, errorCode, summary);
        }
    }

    private StepOutcome runOne(Path dir, List<String> argv, long budgetMillis, StringBuilder sink) {
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("CI", "true");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return StepOutcome.failed(127, "E-209",
                    "无法启动命令 " + argv.get(0) + "：" + e.getMessage());
        }

        // 必须并发读，否则管道写满会死锁
        Thread reader = Thread.ofVirtual().start(() -> pump(process.getInputStream(), sink));

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, budgetMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(reader);
            return StepOutcome.failed(143, "E-303", "执行被中断");
        }

        if (!finished) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            joinQuietly(reader);
            return StepOutcome.failed(124, "E-302",
                    "执行超时（" + (budgetMillis / 1000) + "s），已终止：" + String.join(" ", argv));
        }

        joinQuietly(reader);
        int exit = process.exitValue();
        if (exit != 0) {
            return StepOutcome.failed(exit, "E-101",
                    "命令失败（exit " + exit + "）：" + String.join(" ", argv) + "；见日志尾部");
        }
        return StepOutcome.ok();
    }

    private void pump(InputStream in, StringBuilder sink) {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                append(sink, new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 进程被终止时读会抛，属正常
        }
    }

    /** 追加并保留尾部（避免 OOM）。 */
    private void append(StringBuilder sink, String chunk) {
        synchronized (sink) {
            sink.append(chunk);
            int overflow = sink.length() - MAX_LOG_CHARS;
            if (overflow > 0) {
                sink.delete(0, overflow);
            }
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
                log.warn("扫描产物失败 {}: {}", pattern, e.getMessage());
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
            log.warn("读取产物失败 {}: {}", relative, e.getMessage());
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
