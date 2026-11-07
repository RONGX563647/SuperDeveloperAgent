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
import java.util.function.BooleanSupplier;
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
 *
 * <p>错误码（规约 §1.4/§4）：E-1xx 用户代码、E-2xx 平台、E-3xx 中断、E-4xx 产物。
 * 内核仅产出 E-101/E-209/E-302/E-303，平台级校验（E-201/202/211/401）在此处前置。
 */
public abstract class AbstractActionRunner implements ActionRunner {

    /** 产物数量上限，避免扫描出几万个文件。 */
    private static final int MAX_ARTIFACTS = 50;

    protected final ProcessExecutor executor = new ProcessExecutor();

    /** 把一条业务命令翻译成真正要执行的命令。 */
    protected abstract List<String> effectiveArgv(Path workspace, List<String> argv);

    @Override
    public ActionResult run(Path workspace, ActionSpec spec) {
        return run(workspace, spec, () -> false);
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled) {
        long started = System.nanoTime();
        StringBuilder sink = new StringBuilder();
        long budgetMillis = spec.timeout().toMillis();
        for (List<String> argv : spec.steps()) {
            // 1. 引擎侧校验 argv（E-201 白名单 / E-202 禁 shell / E-211 禁 mvn deploy）
            String err = validateArgv(argv);
            if (err != null) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                return new ActionResult(StageResult.failed(spec.action().wireName(), 1, err,
                        "命令校验未通过：" + err, costMs), ProcessExecutor.tail(sink));
            }
            // 2. 翻译为真正执行的命令（宿主或容器）
            List<String> effective = effectiveArgv(workspace, argv);
            // 3. 执行并扣减预算
            long stepStart = System.nanoTime();
            ProcessExecutor.StepOutcome outcome = executor.exec(workspace, effective, budgetMillis, sink, cancelled);
            budgetMillis -= Math.max(0, (System.nanoTime() - stepStart) / 1_000_000L);
            // 4. 失败直接返回（E-101/E-209/E-302/E-303 来自内核）
            if (outcome.errorCode() != null) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                return new ActionResult(StageResult.failed(spec.action().wireName(), outcome.exitCode(),
                        outcome.errorCode(), outcome.summary(), costMs), ProcessExecutor.tail(sink));
            }
            // 5. 预算耗尽返回 E-302
            if (budgetMillis <= 0) {
                long costMs = (System.nanoTime() - started) / 1_000_000L;
                return new ActionResult(StageResult.failed(spec.action().wireName(), 124, "E-302",
                        "动作总预算耗尽，已终止", costMs), ProcessExecutor.tail(sink));
            }
        }
        // 6. 展开产物并计算 sha256
        List<ArtifactRef> artifacts = expandArtifacts(workspace, spec.produce());
        // 7. 声明了产物却未生成 → E-401（防止配方过期静默成功）
        if (!spec.produce().isEmpty() && artifacts.isEmpty()) {
            long costMs = (System.nanoTime() - started) / 1_000_000L;
            return new ActionResult(StageResult.failed(spec.action().wireName(), 404, "E-401",
                    "声明的产物未生成，可能配方过期或构建未产出：" + spec.produce(), costMs),
                    ProcessExecutor.tail(sink));
        }
        long costMs = (System.nanoTime() - started) / 1_000_000L;
        return new ActionResult(StageResult.success(spec.action().wireName(), 0, artifacts, costMs),
                ProcessExecutor.tail(sink));
    }

    /**
     * 执行前的 argv 校验。
     *
     * <p><b>刻意只查两件事</b>：空命令、以及"把 shell 本身当命令启动"。
     * 不检查参数内容里的 {@code ; & | `} 等字符——因为执行器用 {@code ProcessBuilder(List)}
     * 直接传 argv，从不把字符串交给 shell，这些字符只是普通数据（如 {@code node -e "a;b"}）。
     * 早期版本按"内容含 shell 元字符即拒绝"实现，会误杀合法构建脚本，已移除。
     *
     * <p>命令内容由**人**写在仓库并 review（方案 D），模型只能选动作、不能提供 argv。
     */
    private String validateArgv(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return "E-201";
        }
        String head = argv.get(0);
        if (head == null || head.isBlank()) {
            return "E-201";
        }
        String lower = head.toLowerCase().replace('\\', '/');
        String base = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (base.equals("sh") || base.equals("bash") || base.equals("zsh")
                || base.equals("cmd") || base.equals("cmd.exe") || base.equals("powershell")
                || base.equals("pwsh")) {
            return "E-202";
        }
        if ("mvn".equals(base) && argv.contains("deploy")) {
            return "E-211";
        }
        return null;
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
