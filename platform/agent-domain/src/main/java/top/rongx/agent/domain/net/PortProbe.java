package top.rongx.agent.domain.net;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP 端口探测的唯一实现：连通性探测（连接远端）与空闲探测（本地绑定）。
 *
 * <p>只做「连/绑」判定，不读写数据。各调用点按需传入超时与绑定地址：
 * 本机 Docker / 本地服务用 {@code 127.0.0.1}，容器发布端口用 {@code 0.0.0.0}。
 */
public final class PortProbe {

    /** 默认连接超时：本地端口轮询场景足够，避免阻塞主流程。 */
    public static final int DEFAULT_TIMEOUT_MS = 800;

    private PortProbe() {
    }

    /** 端口是否可建立 TCP 连接（默认超时）。 */
    public static boolean open(String host, int port) {
        return open(host, port, DEFAULT_TIMEOUT_MS);
    }

    /** 端口是否可建立 TCP 连接。 */
    public static boolean open(String host, int port, int timeoutMillis) {
        return failure(host, port, timeoutMillis) == null;
    }

    /** @return 连接成功返回 {@code null}，失败返回原因（供健康检查展示）。 */
    public static String failure(String host, int port, int timeoutMillis) {
        if (host == null || host.isBlank() || port <= 0 || port > 65535) {
            return "host/port 不合法：" + host + ":" + port;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** 端口是否空闲（绑定全部网卡，适合判断能否对外发布）。 */
    public static boolean free(int port) {
        return free("0.0.0.0", port);
    }

    /** 端口是否空闲（绑定指定网卡；本机服务用 {@code 127.0.0.1}）。 */
    public static boolean free(String bindHost, int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(bindHost, port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
