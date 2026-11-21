package top.rongx.agent.domain.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 子进程 I/O 原语（唯一实现）。
 *
 * <p>执行面（{@code ProcessExecutor}）与只读探测面（{@link BoundedCommand}）曾各自
 * 维护一份「边读边收 / 强杀进程树 / 收敛读线程 / 200k 输出上限」，细节逐渐漂移：
 * 一处限制 200k，另一处不限制；一处杀后代，另一处只杀主进程。子进程不退出时，
 * 差异会表现为「某条命令卡死、另一条正常」。
 *
 * <p>本类把共性收敛为四个原语，仅依赖 JDK：
 * <ul>
 *   <li>{@link #pump}：按字符解码并限制总长度，可选实时回调；</li>
 *   <li>{@link #killTree}：强杀主进程及后代并关闭管道，避免读线程等不到 EOF；</li>
 *   <li>{@link #joinQuietly}：有界等待读线程收敛；</li>
 *   <li>{@link #append}：线程安全地追加并裁剪到上限。</li>
 * </ul>
 * 取消、实时日志与分段步骤等语义仍留在调用方。
 */
public final class ProcessIo {

    /** 单个进程保留的输出上限（字符）：只留尾部，避免大构建撑爆内存。 */
    public static final int MAX_OUTPUT_CHARS = 200_000;

    /** 读线程收敛的有界等待。 */
    public static final long READER_JOIN_MS = 3_000L;

    private ProcessIo() {
    }

    /**
     * 排空输入流。
     *
     * @param live 实时回调；{@code null} 表示只往 {@code sink} 攒尾部
     */
    public static void pump(InputStream in, StringBuilder sink, Consumer<String> live) {
        // 按字符解码：日志里有中文时，按字节切块会把多字节字符切成两半（表现为乱码）
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
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
            // 进程被强杀时读会抛，属预期；输出已收集的部分仍然可用
        }
    }

    /** 线程安全地追加片段，并把总长度裁剪到 {@link #MAX_OUTPUT_CHARS}。 */
    public static void append(StringBuilder sink, String chunk) {
        synchronized (sink) {
            sink.append(chunk);
            int overflow = sink.length() - MAX_OUTPUT_CHARS;
            if (overflow > 0) {
                sink.delete(0, overflow);
            }
        }
    }

    /**
     * 终止进程树并关闭输出管道。
     *
     * <p>CLI 常拉起子进程（Windows 上 docker 尤其明显），只杀主进程会留下继续持有
     * 管道写端的后代，读线程永远等不到 EOF；关掉输入流让 pump 立即结束，
     * {@link #joinQuietly} 才不用白等。
     */
    public static void killTree(Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // 已退出的子进程
                }
            });
        } catch (RuntimeException ignored) {
            // 平台不支持 descendants 时退化为只杀主进程
        }
        process.destroyForcibly();
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 流已关闭
        }
    }

    /** 有界等待读线程收敛（默认 3s）。 */
    public static void joinQuietly(Thread thread) {
        joinQuietly(thread, READER_JOIN_MS);
    }

    /** 有界等待读线程收敛。 */
    public static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
