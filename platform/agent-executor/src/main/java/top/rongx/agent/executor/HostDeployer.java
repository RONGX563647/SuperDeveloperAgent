package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.Deployer;

/**
 * 本机直接运行部署器（HOST）。
 *
 * <p>版本目录（方案 C 的可回滚要求）：
 * <pre>
 * &lt;workspace&gt;/.agent/deploy/&lt;project&gt;/
 *   ├── releases/&lt;sha&gt;/app.jar     ← 每次部署一份只读副本
 *   ├── current                     ← 当前版本 sha（纯文本）
 *   ├── previous                    ← 上一版 sha（回滚锚点）
 *   ├── app.pid                    ← 当前进程 pid
 *   └── app.log                    ← 进程 stdout/stderr
 * </pre>
 *
 * <p>部署顺序：落盘新版本 → 停旧进程 → 起新进程 → 成功后才轮转 current/previous。
 * 因此<b>启动失败不会污染回滚锚点</b>。
 *
 * <p>边界：只支持 Java jar（{@code java -jar}）。前端静态产物请选 DOCKER 目标；
 * 否则返回 E-504 明确指引，不静默成功。
 */
public class HostDeployer implements Deployer {

    private static final long STARTUP_PROBE_MS = 800;

    @Override
    public boolean supports(DeployTarget target) {
        return target == DeployTarget.HOST;
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        Path workdir = Path.of(request.workspace());
        Path stateDir = workdir.resolve(".agent/deploy").resolve(request.projectId());
        StringBuilder log = new StringBuilder();

        try {
            Files.createDirectories(stateDir.resolve("releases"));
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.HOST, "E-501",
                    "无法创建部署状态目录：" + e.getMessage(), "");
        }

        String releaseSha;
        Path jar;
        if (request.rollback()) {
            releaseSha = readPointer(stateDir.resolve("previous"));
            if (releaseSha == null) {
                return DeployResult.failed(DeployTarget.HOST, "E-507",
                        "没有可回滚的本机版本（尚无 previous 指针）", "");
            }
            jar = stateDir.resolve("releases").resolve(releaseSha).resolve("app.jar");
            if (!Files.isRegularFile(jar)) {
                return DeployResult.failed(DeployTarget.HOST, "E-507",
                        "上一版 jar 不存在：" + jar, "");
            }
            log.append("[host] rollback to ").append(releaseSha).append('\n');
        } else {
            releaseSha = request.gitSha();
            Path sourceJar = resolveJar(request, workdir);
            if (sourceJar == null) {
                return DeployResult.failed(DeployTarget.HOST, "E-504",
                        "HOST 目标需要可运行的 jar（target/*.jar）；前端静态产物请选择 DOCKER 目标",
                        "");
            }
            jar = stateDir.resolve("releases").resolve(releaseSha).resolve("app.jar");
            try {
                Files.createDirectories(jar.getParent());
                Files.copy(sourceJar, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return DeployResult.failed(DeployTarget.HOST, "E-501",
                        "复制产物失败：" + e.getMessage(), "");
            }
        }

        Path pidFile = stateDir.resolve("app.pid");
        Path logFile = stateDir.resolve("app.log");
        stopPrevious(pidFile, log);

        List<String> argv = List.of("java", "-jar", jar.toString(),
                "--server.port=" + request.hostPort());
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.environment().put("CI", "true");
        // 平台「设置 → 项目环境」里的变量（与 DOCKER 目标同一份数据）
        request.env().forEach((key, value) -> pb.environment().put(key, value == null ? "" : value));

        try {
            Process process = pb.start();
            Files.writeString(pidFile, String.valueOf(process.pid()), StandardCharsets.UTF_8);
            boolean exited = process.waitFor(STARTUP_PROBE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (exited && process.exitValue() != 0) {
                Files.deleteIfExists(pidFile);
                return DeployResult.failed(DeployTarget.HOST, "E-502",
                        "进程启动即退出（exit " + process.exitValue() + "）", tailOf(logFile));
            }
            rotatePointers(stateDir, releaseSha, request.rollback());
            log.append("[host] started pid=").append(process.pid())
                    .append(" release=").append(releaseSha)
                    .append(" port=").append(request.hostPort()).append('\n');
            return DeployResult.ok(DeployTarget.HOST,
                    "http://127.0.0.1:" + request.hostPort() + safePath(request.healthPath()),
                    releaseSha, ProcessExecutor.redact(log.toString()));
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.HOST, "E-502",
                    "无法启动进程：" + e.getMessage(), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeployResult.failed(DeployTarget.HOST, "E-502", "启动被中断", "");
        }
    }

    /** 成功后轮转指针：普通部署 current→previous 变成新 current；回滚把 current 退回 previous。 */
    private void rotatePointers(Path stateDir, String releaseSha, boolean rollback) {
        Path current = stateDir.resolve("current");
        Path previous = stateDir.resolve("previous");
        try {
            if (rollback) {
                String old = readPointer(current);
                writePointer(current, releaseSha);
                if (old != null) {
                    writePointer(previous, old);
                }
                return;
            }
            String existing = readPointer(current);
            if (existing != null && !existing.equals(releaseSha)) {
                writePointer(previous, existing);
            }
            writePointer(current, releaseSha);
        } catch (IOException e) {
            ProcessExecutor.warn("部署指针写入失败：" + e.getMessage());
        }
    }

    private String readPointer(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }

    private void writePointer(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    /** 停掉上一次由平台拉起的进程（按 pid 文件；已死则清理文件）。 */
    private void stopPrevious(Path pidFile, StringBuilder log) {
        if (!Files.isRegularFile(pidFile)) {
            return;
        }
        try {
            String raw = Files.readString(pidFile).trim();
            long pid = Long.parseLong(raw);
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                handle.get().destroy();
                try {
                    handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    handle.get().destroyForcibly();
                }
                log.append("[host] stopped previous pid=").append(pid).append('\n');
            }
        } catch (Exception e) {
            log.append("[host] 旧进程停止失败（继续）：").append(e.getMessage()).append('\n');
        }
        try {
            Files.deleteIfExists(pidFile);
        } catch (IOException ignored) {
            // 删不掉不影响后续写入
        }
    }

    /** 找可运行 jar：显式 artifact 优先，否则 target/*.jar（排除 sources/javadoc）。 */
    private Path resolveJar(DeployRequest request, Path workdir) {
        if (request.artifactPath() != null && !request.artifactPath().isBlank()) {
            Path explicit = workdir.resolve(request.artifactPath()).normalize();
            return Files.isRegularFile(explicit) ? explicit : null;
        }
        Path base = request.workdir() == null || request.workdir().isBlank()
                ? workdir : workdir.resolve(request.workdir()).normalize();
        Path target = base.resolve("target");
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                Path found = stream
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                        .sorted()
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException ignored) {
                // 根 target 读不了时继续按子模块找
            }
        }
        // Maven 聚合工程：可运行 jar 在子模块的 target 下（如 ruoyi-admin/target/*.jar）
        return BuiltInPipelineEngine.findRunnableJar(base);
    }

    private String tailOf(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            int from = Math.max(0, lines.length - 20);
            return ProcessExecutor.redact(String.join("\n",
                    java.util.Arrays.copyOfRange(lines, from, lines.length)));
        } catch (IOException e) {
            return "";
        }
    }

    private static String safePath(String healthPath) {
        if (healthPath == null || healthPath.isBlank()) {
            return "/";
        }
        return healthPath.startsWith("/") ? healthPath : "/" + healthPath;
    }
}
