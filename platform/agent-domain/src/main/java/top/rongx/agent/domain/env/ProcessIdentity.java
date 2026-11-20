package top.rongx.agent.domain.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dev 本机进程的身份记录与校验（BUG-40/42/43 共用 helper）。
 *
 * <p>背景：平台有两条启动链路（流水线 {@code LocalDevHostDeployer}、设置页
 * {@code LocalProcessServiceRuntime}），共用同一张租约表。停进程若只按裸 PID
 * {@code isAlive()} 就 {@code destroyForcibly()}，PID 被系统复用时会杀掉无关进程
 * （最坏情况杀掉平台自己）。因此：
 *
 * <ul>
 *   <li><b>写入端</b>：启动进程后把身份三元组 {@code (pid, startInstant, token)} +
 *       命令行摘要写成 {@code <service>.pid.json}（旧纯数字 {@code .pid} 只读兼容）；</li>
 *   <li><b>终止端</b>：kill 前必须通过 {@link #matches}：记录含 startInstant 时，
 *       与实际 {@link ProcessHandle.Info#startInstant()} 误差 ≤2s 才认定同一进程
 *       （PID 复用必然改变 startInstant，这是抗复用的充分判据）；记录无 startInstant
 *       （legacy）时退化为命令行包含本项目路径片段（jar / stateDir / repo 路径），
 *       读不到命令行一律返回 false（不杀）。</li>
 * </ul>
 *
 * <p>放在 domain：executor 与 infra 都只依赖 domain，两条链路共用同一份判定规则。
 */
public final class ProcessIdentity {

    /** 启动命令注入的身份标记系统属性：{@code -Dagent.dev.token=<uuid>}。 */
    public static final String TOKEN_PROPERTY = "agent.dev.token";

    /** startInstant 比对容差（毫秒）：文件写入与 kill 读取之间允许的时钟抖动。 */
    public static final long START_TOLERANCE_MS = 2_000;

    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");
    private static final Pattern START = Pattern.compile("\"startInstantEpochMs\"\\s*:\\s*(-?\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern COMMAND = Pattern.compile("\"command\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * pid 文件里的身份记录。
     *
     * @param pid                进程 id
     * @param startInstantEpochMs 进程启动时刻（epoch ms；0 = 未知，legacy 纯数字 pid 文件）
     * @param token              启动时注入的 {@code -Dagent.dev.token=} 唯一标识（可空）
     * @param command            命令行摘要（可空）
     */
    public record PidRecord(long pid, long startInstantEpochMs, String token, String command) {

        /** legacy 纯数字 pid 文件的记录：无 startInstant、无 token。 */
        public static PidRecord legacy(long pid) {
            return new PidRecord(pid, 0, null, null);
        }

        public boolean hasStartInstant() {
            return startInstantEpochMs > 0;
        }
    }

    private ProcessIdentity() {
    }

    // ------------------------------------------------------------ 路径

    /** 与 LocalDevHostDeployer.stateDir 一致的部署目录名：小写规范化 + -env 后缀。 */
    public static String deployDirName(String projectId, String env) {
        String suffix = env == null || env.isBlank() ? "" : "-" + env.trim();
        return normalize(projectId) + suffix;
    }

    /** {@code <repo>/.agent/deploy/<project>[-<env>]}：两条链路共用的账本目录。 */
    public static Path deployDir(Path repo, String projectId, String env) {
        return repo.resolve(".agent/deploy").resolve(deployDirName(projectId, env));
    }

    public static Path pidFile(Path deployDir, String service) {
        return deployDir.resolve(service + ".pid.json");
    }

    public static Path legacyPidFile(Path deployDir, String service) {
        return deployDir.resolve(service + ".pid");
    }

    // ------------------------------------------------------------ 读写

    /** 写入 JSON 身份记录（写入端用；目录不存在会自动创建）。 */
    public static void writePidRecord(Path deployDir, String service, PidRecord record) throws IOException {
        Files.createDirectories(deployDir);
        String json = "{\"pid\":" + record.pid()
                + ",\"startInstantEpochMs\":" + record.startInstantEpochMs()
                + ",\"token\":\"" + escape(record.token() == null ? "" : record.token())
                + "\",\"command\":\"" + escape(record.command() == null ? "" : record.command())
                + "\"}";
        Files.writeString(pidFile(deployDir, service), json, StandardCharsets.UTF_8);
    }

    /** 读取身份记录：优先 {@code .pid.json}，回退 legacy 纯数字 {@code .pid}。 */
    public static Optional<PidRecord> readPidRecord(Path deployDir, String service) {
        Optional<PidRecord> json = readExact(pidFile(deployDir, service));
        if (json.isPresent()) {
            return json;
        }
        return readLegacy(legacyPidFile(deployDir, service));
    }

    /** 解析 JSON 记录；文件缺失或内容损坏返回 empty（调用方按"无记录"处理）。 */
    public static Optional<PidRecord> readExact(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            Matcher pid = PID.matcher(text);
            if (!pid.find()) {
                return Optional.empty();
            }
            long pidValue = Long.parseLong(pid.group(1));
            long start = 0;
            Matcher s = START.matcher(text);
            if (s.find()) {
                start = Long.parseLong(s.group(1));
            }
            String token = null;
            Matcher t = TOKEN.matcher(text);
            if (t.find()) {
                token = unescape(t.group(1));
            }
            String command = null;
            Matcher c = COMMAND.matcher(text);
            if (c.find()) {
                command = unescape(c.group(1));
            }
            return Optional.of(new PidRecord(pidValue, start, blankToNull(token), blankToNull(command)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** legacy 纯数字 pid 文件：只读出 pid，无 startInstant（校验退化为命令行探针）。 */
    public static Optional<PidRecord> readLegacy(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            long pid = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
            return pid > 0 ? Optional.of(PidRecord.legacy(pid)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static void deletePidFiles(Path deployDir, String service) {
        try {
            Files.deleteIfExists(pidFile(deployDir, service));
        } catch (IOException ignored) {
            // 删不掉不影响后续
        }
        try {
            Files.deleteIfExists(legacyPidFile(deployDir, service));
        } catch (IOException ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------ 身份判定

    /**
     * 终止前的身份判定（BUG-40/42 共用，宁可漏杀不可误杀）。
     *
     * <ul>
     *   <li>记录含 startInstant：必须与实际 startInstant 误差 ≤ {@value #START_TOLERANCE_MS}ms，
     *       否则判定为 PID 已被复用 → false（不杀）；读不到实际 startInstant 同样 false。</li>
     *   <li>记录为 legacy（无 startInstant）：命令行包含 pathFragments 之一（本项目
     *       repo / stateDir / jar 路径）或含注入 token 才为 true；读不到命令行 → false。</li>
     * </ul>
     */
    public static boolean matches(Optional<String> commandLine, Optional<Instant> startInstant,
                                  PidRecord record, List<String> pathFragments) {
        if (record == null || record.pid() <= 0) {
            return false;
        }
        if (record.hasStartInstant()) {
            if (startInstant.isEmpty()) {
                return false;
            }
            return Math.abs(startInstant.get().toEpochMilli() - record.startInstantEpochMs())
                    <= START_TOLERANCE_MS;
        }
        return matchesCommandLine(commandLine, pathFragments) || tokenMatches(commandLine, record);
    }

    /**
     * legacy / 租约 pid 的命令行探针：命令行必须包含 pathFragments 之一。
     * 读不到命令行返回 false（默认不杀，BUG-42）。
     */
    public static boolean matchesCommandLine(Optional<String> commandLine, List<String> pathFragments) {
        if (commandLine.isEmpty() || commandLine.get().isBlank()) {
            return false;
        }
        String cmd = commandLine.get();
        for (String fragment : pathFragments) {
            if (fragment != null && !fragment.isBlank() && cmd.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 注入 token 匹配：命令行包含 {@code -Dagent.dev.token=<token>}。 */
    public static boolean tokenMatches(Optional<String> commandLine, PidRecord record) {
        return record != null && record.token() != null && commandLine.isPresent()
                && commandLine.get().contains("-D" + TOKEN_PROPERTY + "=" + record.token());
    }

    // ------------------------------------------------------------ 工具

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "project";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
