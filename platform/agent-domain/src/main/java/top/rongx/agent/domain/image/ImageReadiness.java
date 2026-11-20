package top.rongx.agent.domain.image;

/**
 * 某台主机上某个工具链镜像的就绪状态。
 *
 * <p>为什么要有这个状态：镜像拉取失败以前只体现为某次运行的一条 E-302，下一次运行
 * 又从零开始"预检 → 拉取 → 等 60s 超时"。参考 kubelet 的 ImagePullBackOff——失败要
 * **按镜像维度记住并退避**，成功也要记住，这样同一台机器上的重复构建不必反复探测。
 *
 * <p>{@code host} 是 Docker 守护所在主机：状态描述的是"这台机器的镜像层缓存"，
 * 多实例共用同一守护时共享，不同主机之间不可共享。
 */
public record ImageReadiness(
        String host,
        String image,
        Status status,
        String source,
        String digest,
        int attempts,
        String lastError,
        long nextRetryAt,
        long updatedAt) {

    public enum Status {
        /** 已在本机并复核通过（digest 记下来供可复现性排查）。 */
        READY,
        /** 上次失败：{@code nextRetryAt} 之前不再重试，直接复用失败结论。 */
        FAILED
    }

    /** 是否仍在退避窗口内（应当快返上次失败原因，而不是再等一轮超时）。 */
    public boolean inBackoff(long now) {
        return status == Status.FAILED && nextRetryAt > now;
    }
}
