package top.rongx.agent.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.error.ErrorCode;

/**
 * 单条命令的执行内核（宿主进程与容器共用）。
 *
 * <p>只用 {@code ProcessBuilder(List)}，不经 shell；输出边读边收集（否则管道写满会死锁）。
 */
final class ProcessExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutor.class);

    /** 单次动作日志上限（字符）：只保留尾部，避免大构建撑爆内存。 */
    private static final int MAX_LOG_CHARS = 200_000;

    /** 一步的结果。 */
    record StepOutcome(int exitCode, String errorCode, String summary) {
        static StepOutcome ok() {
            return new StepOutcome(0, null, null);
        }

        static StepOutcome failed(int exitCode, String errorCode, String summary) {
            return new StepOutcome(exitCode, errorCode, summary);
        }
    }

    /**
     * 失败动作类型：退出码非 0 时决定错误码与摘要模板。
     *
     * <p>过去所有非 0 退出码一律标 E-101"编译失败"——测试失败、镜像构建失败、脚本失败
     * 在 UI 与自动修复里都被当成编译错误处理，自愈动作与人的判断同时被误导。
     * 现在调用方按动作语义传入类型；未传入时按命令首参数特征推断，兜底 SCRIPT_FAILED。
     */
    enum FailureKind {
        /** 真正的编译动作（backend_build 等编译段）：保持 E-101。 */
        COMPILE,
        /** 测试动作（backend_test / frontend_test）：E-102。 */
        TEST,
        /** 镜像构建（docker build）：E-103。 */
        IMAGE_BUILD,
        /** 其它任意命令 / 脚本：E-104。 */
        SCRIPT;

        /** 按动作 wireName 映射；未知动作返回 null（由命令特征推断）。 */
        static FailureKind ofAction(String actionWireName) {
            if (actionWireName == null) {
                return null;
            }
            return switch (actionWireName) {
                case "backend_test", "frontend_test" -> TEST;
                case "image_package" -> IMAGE_BUILD;
                case "backend_build", "frontend_build" -> COMPILE;
                default -> null;
            };
        }

        /** 按命令首参数特征推断（mvn/pnpm 的 test 目标、docker build、其余脚本）。 */
        static FailureKind fromArgv(List<String> argv) {
            if (argv == null || argv.isEmpty()) {
                return SCRIPT;
            }
            String head = argv.get(0);
            String base = head == null ? "" : Path.of(head).getFileName().toString()
                    .toLowerCase(java.util.Locale.ROOT);
            if (base.contains(".")) {
                base = base.substring(0, base.indexOf('.'));
            }
            boolean isMaven = base.equals("mvn") || base.equals("mvnw");
            boolean isPnpmLike = base.equals("pnpm") || base.equals("npm") || base.equals("yarn")
                    || base.equals("node") || base.equals("vitest") || base.equals("jest");
            if (isMaven || isPnpmLike) {
                boolean isTest = argv.stream().skip(1).limit(4).anyMatch(
                        a -> "test".equalsIgnoreCase(a) || "verify".equalsIgnoreCase(a)
                                || "-t".equalsIgnoreCase(a));
                return isTest ? TEST : COMPILE;
            }
            if (base.equals("docker") && argv.stream().anyMatch(a -> "build".equals(a))) {
                return IMAGE_BUILD;
            }
            return SCRIPT;
        }

        String errorCode() {
            return switch (this) {
                case COMPILE -> ErrorCode.COMPILE_FAILED.code();
                case TEST -> ErrorCode.TEST_FAILED.code();
                case IMAGE_BUILD -> ErrorCode.IMAGE_BUILD_FAILED.code();
                case SCRIPT -> ErrorCode.SCRIPT_FAILED.code();
            };
        }

        String summary(int exit, List<String> argv) {
            String cmd = String.join(" ", argv);
            String base = switch (this) {
                case TEST -> "测试未通过（exit " + exit + "）：" + cmd + "；见 surefire/vitest 测试报告";
                case IMAGE_BUILD -> "镜像/产物打包失败（exit " + exit + "）：" + cmd + "；检查 Dockerfile 与网络";
                case COMPILE -> "编译失败（exit " + exit + "）：" + cmd + "；见编译诊断";
                case SCRIPT -> "命令失败（exit " + exit + "）：" + cmd + "；见日志尾部";
            };
            // exit >= 128 = 被信号终止（137 = OOM/kill -9，143 = SIGTERM）：高频排障线索，单独点出
            return exit >= 128
                    ? base + "（被信号终止，exit-128=信号号；137 通常是内存不足被 OOM kill）"
                    : base;
        }
    }

    StepOutcome exec(Path dir, List<String> argv, long budgetMillis, StringBuilder sink,
                     BooleanSupplier cancelled) {
        return exec(dir, argv, budgetMillis, sink, cancelled, null, null);
    }

    /**
     * @param live 实时输出回调：每读到一段就回传一次（用于运行日志边跑边看）；
     *             为 {@code null} 时只在 {@code sink} 里攒尾部。
     */
    StepOutcome exec(Path dir, List<String> argv, long budgetMillis, StringBuilder sink,
                     BooleanSupplier cancelled, Consumer<String> live) {
        return exec(dir, argv, budgetMillis, sink, cancelled, live, null);
    }

    /**
     * 带动作语义的执行：失败时按 {@code failureKind}（缺省按 argv 推断）选错误码。
     */
    StepOutcome exec(Path dir, List<String> argv, long budgetMillis, StringBuilder sink,
                     BooleanSupplier cancelled, Consumer<String> live, FailureKind failureKind) {
        return run(dir, argv, 0L, Math.max(1, budgetMillis), sink, cancelled, live, failureKind);
    }

    /**
     * 进度感知执行：只要还有新输出就继续跑，连续 {@code idleMillis} 无输出才判定卡死。
     *
     * <p>用于 {@code docker pull} 这类「慢但一直有进度」的长任务：固定总预算会把
     * 「层已下载完成、正在解压」误判成网络故障（实测 node:22-bookworm 在忙时超过 180s，
     * 报 E-302 而全部层其实已 Download complete）。{@code idleMillis <= 0} 时退回固定预算语义。
     * {@code maxTotalMillis > 0} 时另有总时长兜底，防止「一直有零星输出」的任务无限跑。
     */
    StepOutcome execUntilIdle(Path dir, List<String> argv, long idleMillis, long maxTotalMillis,
                              StringBuilder sink, BooleanSupplier cancelled, Consumer<String> live) {
        if (idleMillis <= 0) {
            return run(dir, argv, 0L, Math.max(1, maxTotalMillis), sink, cancelled, live, null);
        }
        return run(dir, argv, idleMillis, maxTotalMillis, sink, cancelled, live, null);
    }

    private StepOutcome run(Path dir, List<String> argv, long idleMillis, long maxTotalMillis,
                            StringBuilder sink, BooleanSupplier cancelled, Consumer<String> live,
                            FailureKind failureKind) {
        long startedAt = System.nanoTime();
        long deadline = maxTotalMillis > 0
                ? startedAt + maxTotalMillis * 1_000_000L : Long.MAX_VALUE;
        java.util.concurrent.atomic.AtomicLong lastOutputAt =
                new java.util.concurrent.atomic.AtomicLong(startedAt);
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("CI", "true");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return StepOutcome.failed(127, ErrorCode.EXECUTABLE_NOT_FOUND.code(),
                    "无法启动命令 " + argv.get(0) + "：" + e.getMessage());
        }

        Thread reader = Thread.ofVirtual().start(() -> pump(process.getInputStream(), sink, chunk -> {
            lastOutputAt.set(System.nanoTime());
            if (live != null) {
                live.accept(chunk);
            }
        }));

        boolean finished;
        // 终止原因要能区分「一直有进度但太久」和「彻底没动静」，否则读日志的人无法判断
        // 是网络卡死还是单纯慢——这正是 E-302 那次被误读成 registry 故障的原因。
        String stopReason = null;
        long stopBudgetMillis = maxTotalMillis;
        boolean stalled = false;
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    killTree(process);
                    joinQuietly(reader);
                    return StepOutcome.failed(143, ErrorCode.CANCELLED.code(), "执行被取消：" + String.join(" ", argv));
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
                long now = System.nanoTime();
                if (now - deadline > 0) {
                    finished = false;
                    stopReason = "总时长已达上限";
                    break;
                }
                if (idleMillis > 0
                        && now - lastOutputAt.get() > idleMillis * 1_000_000L) {
                    finished = false;
                    stopReason = "无任何输出";
                    stopBudgetMillis = idleMillis;
                    stalled = true;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            joinQuietly(reader);
            return StepOutcome.failed(143, ErrorCode.CANCELLED.code(), "执行被中断");
        }

        if (!finished) {
            killTree(process);
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    killTree(process);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree(process);
            }
            joinQuietly(reader);
            // 预算可能是秒级碎片（多步配方里前一步吃掉大半预算），按整秒显示会变成
            // "执行超时（0s）"，读日志的人无法判断到底是超时还是命令本身有问题。
            String verdict = stalled
                    ? "执行停滞（" + human(stopBudgetMillis) + " 无输出），已终止："
                    : "执行超时（" + human(stopBudgetMillis) + "，" + stopReason + "），已终止：";
            return StepOutcome.failed(124, ErrorCode.TIMEOUT.code(),
                    verdict + String.join(" ", argv));
        }

        joinQuietly(reader);
        int exit = process.exitValue();
        if (exit != 0) {
            FailureKind kind = failureKind != null ? failureKind : FailureKind.fromArgv(argv);
            return StepOutcome.failed(exit, kind.errorCode(), kind.summary(exit, argv));
        }
        return StepOutcome.ok();
    }

    private void pump(InputStream in, StringBuilder sink, Consumer<String> live) {
        // 按字符解码：日志里有中文时，按字节切块会把多字节字符切成两半（表现为乱码）。
        try (var reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, read);
                append(sink, chunk);
                if (live != null) {
                    live.accept(chunk);
                }
            }
        } catch (IOException ignored) {
            // 进程被终止时读会抛，属正常
        }
    }

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

    /** 预算的人类可读形式：不足 1s 时显示毫秒，避免"超时（0s）"这种没有信息量的摘要。 */
    static String human(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        long seconds = millis / 1000;
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    /**
     * 终止进程树并关闭输出管道。
     *
     * <p>Windows 上 docker CLI 会拉起子进程，只 destroy CLI 会留下继续持有管道写端的
     * 后代进程，读线程永远等不到 EOF；关掉输入流让 pump 立即结束，joinQuietly 才不会
     * 白等 3 秒，超时/取消也能真正收敛。
     */
    private static void killTree(Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // 已退出的子进程
                }
            });
        } catch (RuntimeException ignored) {
            // 平台不支持 descendants 时忽略
        }
        process.destroyForcibly();
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 流已关闭
        }
    }

    /** 日志尾部：最后 30 行且不超过 4000 字符。输出前经 {@link #redact} 脱敏。 */
    static String tail(StringBuilder sink) {
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
        return redact(joined);
    }

    /**
     * 日志脱敏（规约 §1.6 redactor）：过滤常见的密钥/凭证形状，避免写入证据链。
     * 仅做形态匹配，不做内容解析；命中即替换为 {@code ***}。
     */
    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 1. Authorization / Bearer / token 赋值
        //    Bearer 阈值 8：与 agent-tools SecretRedactor 对齐（7 位以下不构成有效凭证，
        //    统一阈值才能保证同一段密钥在所有出口的行为一致）。
        text = text.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._-]{8,}", "$1***");
        text = text.replaceAll("(?i)((?:" + "token|secret|password|passwd|api[_-]?key|access[_-]?key"
                + "|authorization" + ")\\s*[=:]\\s*)[^\\s\"']{8,}", "$1***");
        // 1a. TOKEN_PREFIX：OpenAI/Anthropic、GitHub、GitLab、Slack、AWS 等前缀形凭证。
        //     与 SecretRedactor 的 TOKEN_PREFIX 规则对齐；sk-proj- / sk-ant- 由 sk- 覆盖。
        text = text.replaceAll("(?i)(sk-[A-Za-z0-9_-]{4,}"
                + "|gh[pousr]_[A-Za-z0-9]{4,}"
                + "|glpat-[A-Za-z0-9_-]{4,}"
                + "|xox[baprs]-[A-Za-z0-9_-]{4,}"
                + "|AKIA[0-9A-Z]{8,})", "***");
        // 2. 长十六进制串（jwt 第二段等）按行内孤立 token 处理；
        //    但 `sha256:<hex>` 是镜像/产物的公开指纹，属于构建证据，必须保留（否则页面上只剩 ***）。
        text = text.replaceAll("(?<!sha256:)(?<!sha512:)\\b[0-9a-fA-F]{64}\\b", "***");
        // 3. 形如 -----BEGIN ... PRIVATE KEY----- 的 PEM 块
        text = text.replaceAll("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", "***PEM***");
        // 4. Cookie / Set-Cookie 整行（与 SecretRedactor 对齐：整行都是会话凭证）
        text = text.replaceAll("(?im)^\\s*(?:set-)?cookie\\s*:.*$", "***COOKIE***");
        return text;
    }

    /**
     * 值级脱敏：在形态匹配之外，把本次注入过的密钥明文整段替换掉。
     *
     * <p>形态匹配挡不住 {@code echo $TOKEN} 这类没有关键字前缀的输出；
     * 运行日志写盘前用环境里的真实密钥值兜底，避免明文进入证据链。
     */
    static String redact(String text, Collection<String> sensitiveValues) {
        String out = redact(text);
        if (out == null || out.isEmpty() || sensitiveValues == null || sensitiveValues.isEmpty()) {
            return out;
        }
        List<String> values = sensitiveValues.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                // 太短的值（如 "1"、"true"）替换会把正常日志打碎，且不构成有效密钥。
                .filter(v -> v.length() >= 4)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String value : values) {
            out = out.replace(value, "***");
        }
        return out;
    }

    static void warn(String message) {
        log.warn(message);
    }
}