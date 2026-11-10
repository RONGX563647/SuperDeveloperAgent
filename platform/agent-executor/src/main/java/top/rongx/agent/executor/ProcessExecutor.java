package top.rongx.agent.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    StepOutcome exec(Path dir, List<String> argv, long budgetMillis, StringBuilder sink,
                     BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + Math.max(1, budgetMillis) * 1_000_000L;
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

        Thread reader = Thread.ofVirtual().start(() -> pump(process.getInputStream(), sink));

        boolean finished;
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    joinQuietly(reader);
                    return StepOutcome.failed(143, "E-303", "执行被取消：" + String.join(" ", argv));
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
                if (System.nanoTime() - deadline > 0) {
                    finished = false;
                    break;
                }
            }
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
        text = text.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._-]{16,}", "$1***");
        text = text.replaceAll("(?i)((?:" + "token|secret|password|passwd|api[_-]?key|access[_-]?key"
                + "|authorization" + ")\\s*[=:]\\s*)[^\\s\"']{8,}", "$1***");
        // 2. 长十六进制串（jwt 第二段等）按行内孤立 token 处理；
        //    但 `sha256:<hex>` 是镜像/产物的公开指纹，属于构建证据，必须保留（否则页面上只剩 ***）。
        text = text.replaceAll("(?<!sha256:)(?<!sha512:)\\b[0-9a-fA-F]{64}\\b", "***");
        // 3. 形如 -----BEGIN ... PRIVATE KEY----- 的 PEM 块
        text = text.replaceAll("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", "***PEM***");
        return text;
    }

    static void warn(String message) {
        log.warn(message);
    }
}
