package top.rongx.agent.domain.process;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * 宿主端口探测（唯一实现）。
 *
 * <p>为什么需要它：同一件事（"这个端口现在能不能绑"）曾被 4 处各写一遍，
 * 绑定地址还不一致（`0.0.0.0` / `127.0.0.1`），于是同一时刻对同一端口可能得出相反结论。
 *
 * <p><b>这不是端口分配的正确做法，而是"必须自己起进程时"的折中</b>：
 * <ul>
 *   <li>{@code DOCKER} 目标不该用它——docker 自己会分配（`-p 127.0.0.1::8080`），
 *       事后用 {@code docker port} 回读。让 docker 分配没有 TOCTOU；
 *       本类"试绑 → 关闭 → 交给 docker 绑定"中间永远有一个窗口。</li>
 *   <li>必须用它的场景：HOST 目标（`java -jar --server.port=N` 必须先把 N 告诉 JVM）、
 *       本机服务租约、中间件容器（端口要写进应用的环境变量，必须事先知道）。</li>
 * </ul>
 *
 * <p>统一绑 `0.0.0.0`：这是<b>更严格</b>的判据——只绑 {@code 127.0.0.1} 会漏掉
 * "别的进程已经占了 0.0.0.0:N"（在 Linux 上两者不能共存，在 Windows 上行为更宽松）。
 * 判"空闲"时宁可误判为占用，也不要事后被 EADDRINUSE 打断。
 */
public final class PortProbe {

    private PortProbe() {
    }

    /**
     * 端口当前是否可绑定。
     *
     * <p>{@code setReuseAddress(false)} 是刻意的：开着 SO_REUSEADDR 时，
     * TIME_WAIT 状态的端口会被判成"可用"，随后真正 bind 却失败。
     */
    public static boolean isFree(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 目标地址当前是否可建立 TCP 连接（只连不读写）。
     *
     * @param timeoutMs 连接超时；{@code <= 0} 时回退 1500ms
     */
    public static boolean isOpen(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank() || port <= 0 || port > 65535) {
            return false;
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(host, port),
                    timeoutMs > 0 ? timeoutMs : 1500);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 从 {@code base} 起找一个空闲端口。
     *
     * @param base  起始端口（含）
     * @param limit 最多尝试多少个（从 base 向上递增）
     * @return 空闲端口；找不到返回 {@code -1}（调用方负责报可读错误，不要静默取 base）
     */
    public static int firstFree(int base, int limit) {
        int start = Math.max(1, base);
        int attempts = Math.max(1, limit);
        for (int port = start; port < start + attempts && port <= 65535; port++) {
            if (isFree(port)) {
                return port;
            }
        }
        return -1;
    }
}
