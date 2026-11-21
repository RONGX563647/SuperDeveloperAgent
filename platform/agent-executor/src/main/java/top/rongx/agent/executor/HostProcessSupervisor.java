package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import top.rongx.agent.domain.process.LogTail;

/**
 * HOST 目标进程的托管原语（唯一实现）。
 *
 * <p>流水线部署（{@link HostDeployer}）与控制台启停（网关 {@code DeploymentControlService}）
 * 曾各写一套「写 pid → 起进程 → 探早退 → 停旧进程」：pid 文件名一致，但一边按 pid 停、
 * 一边校验命令行；一边有 {@code app.pids} 追加登记，一边没有。结果是控制台停不掉的遗留
 * 进程，流水线下一次部署才清掉。
 *
 * <p>本类只统一 HOST 进程的托管动作：启动、停止（含遗留登记回收）、pid/port/日志文件读写。
 * Docker 的 {@code run}/{@code start} 语义不在此列。
 */
public final class HostProcessSupervisor {

    /** 当前进程 pid 文件。 */
    public static final String PID_FILE = "app.pid";
    /** 槽内追加式进程登记：每行 {@code pid 启动时刻毫秒}。 */
    public static final String PIDS_FILE = "app.pids";
    /** 端口文件。 */
    public static final String PORT_FILE = "port";
    /** 进程输出日志。 */
    public static final String LOG_FILE = "app.log";

    private static final long STARTUP_PROBE_MS = 800L;
    private static final Pattern PROCESS_PORT = Pattern.compile("--server\\.port=(\\d+)");

    private HostProcessSupervisor() {
    }

    /**
     * 启动 HOST 进程：工作目录固定为状态目录，输出追加到 {@code app.log}。
     *
     * @param stateDir 状态目录
     * @param argv     完整命令
     * @param env      追加环境变量（应用环境变量；{@code CI=true} 由本方法补齐）
     */
    public static Process start(Path stateDir, List<String> argv, Map<String, String> env)
            throws IOException {
        Files.createDirectories(stateDir);
        ProcessBuilder pb = new ProcessBuilder(List.copyOf(argv))
                .directory(stateDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        stateDir.resolve(LOG_FILE).toFile()));
        pb.environment().put("CI", "true");
        if (env != null) {
            env.forEach((key, value) -> pb.environment().put(key, value == null ? "" : value));
        }
        Process process = pb.start();
        Files.writeString(stateDir.resolve(PID_FILE), String.valueOf(process.pid()),
                StandardCharsets.UTF_8);
        recordProcess(stateDir, process.toHandle());
        return process;
    }

    /** 启动探针：{@code true} 表示进程在探针窗口内已经退出。 */
    public static boolean exitedDuringProbe(Process process) throws InterruptedException {
        return process.waitFor(STARTUP_PROBE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止本槽进程：先停 pid 文件指向的进程，再回收 {@code app.pids} 里仍存活的遗留进程。
     *
     * @param verifyAppJar 为 {@code true} 时校验命令行含 {@code app.jar}，防止 pid 复用误杀
     * @throws IllegalStateException 校验失败（pid 已被无关进程复用）
     */
    public static void stop(Path stateDir, boolean verifyAppJar, StringBuilder log) {
        Path pidFile = stateDir.resolve(PID_FILE);
        long pid = readPid(pidFile);
        if (pid > 0) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            if (handle.isPresent()) {
                if (verifyAppJar) {
                    String commandLine = handle.get().info().commandLine().orElse("");
                    if (!commandLine.isEmpty() && !commandLine.contains("app.jar")) {
                        throw new IllegalStateException("pid=" + pid
                                + " 不是平台部署的 app.jar，拒绝停止（可能是 pid 被复用）");
                    }
                }
                terminate(handle.get());
                log.append("[host] stopped pid=").append(pid).append('\n');
            }
            deleteQuietly(pidFile);
        }
        stopRecorded(stateDir, log);
    }

    /** 回收槽内登记（{@code app.pids}）里仍存活的遗留进程，并把未能回收的登记写回。 */
    public static void stopRecorded(Path stateDir, StringBuilder log) {
        Path file = stateDir.resolve(PIDS_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<String> survivors = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) {
                    continue;
                }
                long pid;
                long startedAt;
                try {
                    pid = Long.parseLong(parts[0]);
                    startedAt = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
                } catch (NumberFormatException invalid) {
                    continue;
                }
                if (pid == ProcessHandle.current().pid()) {
                    continue;
                }
                Optional<ProcessHandle> handle = ProcessHandle.of(pid);
                if (handle.isEmpty() || !handle.get().isAlive()) {
                    continue;
                }
                long actual = handle.get().info().startInstant()
                        .map(java.time.Instant::toEpochMilli).orElse(-1L);
                if (startedAt > 0 && actual > 0 && Math.abs(actual - startedAt) > 2000L) {
                    survivors.add(line);
                    continue;
                }
                terminate(handle.get());
                log.append("[host] stopped orphan pid=").append(pid).append('\n');
            }
            // 回写未能回收的登记，避免下次失去线索
            Files.write(file, survivors, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.append("[host] 遗留进程登记读取失败（继续）：").append(e.getMessage()).append('\n');
        }
    }

    /** 追加登记进程：每行 {@code pid 启动时刻毫秒}。 */
    public static void recordProcess(Path stateDir, ProcessHandle handle) {
        long startedAt = handle.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
        try {
            Files.writeString(stateDir.resolve(PIDS_FILE),
                    handle.pid() + " " + startedAt + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            ProcessExecutor.warn("进程登记写入失败：" + e.getMessage());
        }
    }

    /** 读文本；文件缺失或为空返回 {@code null}。 */
    public static String readText(Path file) {
        return top.rongx.agent.domain.fs.TextFiles.readTrimmedOrNull(file);
    }

    /** 读 pid；文件缺失或非法返回 0。 */
    public static long readPid(Path file) {
        String value = readText(file);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 读端口；文件缺失或非法返回 0。 */
    public static int readPort(Path file) {
        String value = readText(file);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 写端口；失败不影响启动（下次回落到 app.log）。 */
    public static void writePort(Path stateDir, int port) {
        try {
            Files.writeString(stateDir.resolve(PORT_FILE), String.valueOf(port),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 端口记录失败不影响启动，下次会回落到 app.log
        }
    }

    /** 从运行中进程的命令行取 {@code --server.port}，给没有 port 文件的旧部署兜底。 */
    public static int portFromCommandLine(long pid) {
        if (pid <= 0) {
            return 0;
        }
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .map(handle -> handle.info().commandLine().orElse(""))
                .map(commandLine -> {
                    var matcher = PROCESS_PORT.matcher(commandLine);
                    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
                })
                .orElse(0);
    }

    /** 日志尾部（已脱敏）。 */
    public static String tail(Path logFile, int lines) {
        return ProcessExecutor.redact(LogTail.lastLines(logFile, lines));
    }

    private static void terminate(ProcessHandle handle) {
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        if (!waitForExit(handle, 5)) {
            // 强杀后也要等句柄释放（Windows 上 app.log 被占用会导致目录清理失败）
            handle.destroyForcibly();
            waitForExit(handle, 3);
        }
    }

    private static boolean waitForExit(ProcessHandle handle, int seconds) {
        try {
            handle.onExit().get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception notYet) {
            return false;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删不掉不影响后续写入
        }
    }
}
