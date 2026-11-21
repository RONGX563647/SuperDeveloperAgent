package top.rongx.agent.domain.docker;

import java.util.Locale;

/**
 * 容器运行状态判定（唯一实现）。
 *
 * <p>{@code docker ps} 有两种来源：新版 {@code --format} 能直接取 {@code .State}，
 * 旧路径只有 {@code .Status} 文本。两边曾各自实现「是否在运行」：
 * 一处只看 {@code State=running}，一处只看 {@code Status} 以 {@code Up} 开头，
 * 于是暂停中的容器在一处算运行、另一处不算。
 */
public final class ContainerState {

    private ContainerState() {
    }

    /**
     * 是否在运行。
     *
     * <p>{@code state} 优先（running/exited/created…）；缺失时回退 {@code status}
     * 文本（{@code Up 3 minutes} / {@code Exited (0)…}），暂停（{@code (Paused)}）不算运行。
     */
    public static boolean running(String state, String status) {
        if (state != null && !state.isBlank()) {
            return "running".equalsIgnoreCase(state.trim());
        }
        String text = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("up") && !text.contains("(paused)");
    }
}
