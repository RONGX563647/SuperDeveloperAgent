package top.rongx.agent.domain.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 宿主端口探测的唯一实现。
 *
 * <p>守住的回归（G8）：同一件事曾被 4 处各写一遍，绑定地址还不一致
 * （`0.0.0.0` / `127.0.0.1`），同一时刻对同一端口可能得出相反结论。
 *
 * <p>同时明确边界：<b>DOCKER 目标不该用它</b>——docker 自己会分配
 * （`-p 127.0.0.1::8080`），事后 `docker port` 回读，"试绑→释放→再绑"之间没有窗口。
 * 必须用它的只有 HOST 目标、本机服务租约与中间件（端口要写进应用的环境变量）。
 */
class PortProbeTest {

    @Test
    @DisplayName("非法端口一律判为不可用（不让 0/负数/超界悄悄通过）")
    void invalidPortsAreRejected() {
        assertFalse(PortProbe.isFree(0));
        assertFalse(PortProbe.isFree(-1));
        assertFalse(PortProbe.isFree(65536));
        assertFalse(PortProbe.isFree(999999));
    }

    @Test
    @DisplayName("真的被占用时判为不可用（用自己占住一个端口来验证）")
    void occupiedPortIsDetected() throws IOException {
        try (ServerSocket holder = new ServerSocket()) {
            holder.setReuseAddress(false);
            holder.bind(new java.net.InetSocketAddress("0.0.0.0", 0));
            int occupied = holder.getLocalPort();

            assertFalse(PortProbe.isFree(occupied),
                    "自己正占着的端口必须被判为不可用：" + occupied);
        }
    }

    @Test
    @DisplayName("释放后立刻可用：setReuseAddress(false) 不会把刚关掉的端口卡在 TIME_WAIT")
    void releasedPortBecomesFree() throws IOException {
        int port;
        try (ServerSocket holder = new ServerSocket()) {
            holder.setReuseAddress(false);
            holder.bind(new java.net.InetSocketAddress("0.0.0.0", 0));
            port = holder.getLocalPort();
        }
        // SO_REUSEADDR 关掉时端口会进 TIME_WAIT；isFree 用同样的设置探测，
        // 因此结论必须与"能否真正 bind"一致（否则选出来的端口 docker 绑不上）
        assertTrue(PortProbe.isFree(port), "刚释放的端口应立即可用：" + port);
    }

    @Test
    @DisplayName("firstFree 从 base 起找到可用端口，且不会返回被占用的那个")
    void firstFreeSkipsOccupied() throws IOException {
        try (ServerSocket holder = new ServerSocket()) {
            holder.setReuseAddress(false);
            holder.bind(new java.net.InetSocketAddress("0.0.0.0", 0));
            int occupied = holder.getLocalPort();

            int found = PortProbe.firstFree(occupied, 50);

            assertTrue(found > occupied, "必须跳过被占用的端口：" + occupied + " → " + found);
            assertTrue(PortProbe.isFree(found), "选出来的端口必须真的可用：" + found);
        }
    }

    @Test
    @DisplayName("firstFree 找不到时返回 -1（调用方据此报可读错误，而不是静默用 base）")
    void firstFreeReturnsMinusOneWhenExhausted() {
        // 起始值已越过 65535 上界：循环体一次都不进，必须返回 -1 而不是 base
        assertEquals(-1, PortProbe.firstFree(70000, 5),
                "越界的 base 不能原样返回：调用方会拿它去 bind 一个不存在的端口");
    }

    @Test
    @DisplayName("base<=0 归一为 1：不会返回 0（0 交给内核才代表随机端口）")
    void baseIsNormalized() {
        int found = PortProbe.firstFree(0, 1);
        assertTrue(found == 1 || found == -1, "起始值归一到 1，绝不返回 0：" + found);
    }
}
