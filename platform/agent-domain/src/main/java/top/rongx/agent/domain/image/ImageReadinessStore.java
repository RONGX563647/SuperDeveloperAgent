package top.rongx.agent.domain.image;

import java.util.List;
import java.util.Optional;

/**
 * 镜像就绪状态存储（跨运行复用）。
 *
 * <p>只回答"这台主机上这个镜像现在是什么状态、上次失败到什么时候再试"，不做任何
 * Docker 操作；拉取策略在 executor 模块，存储实现走 PlatformDb（SQLite/PostgreSQL 共用）。
 */
public interface ImageReadinessStore {

    Optional<ImageReadiness> find(String host, String image);

    void save(ImageReadiness readiness);

    /** 某主机上的全部状态（运行前就绪阶段一次性读出来，避免逐镜像查询）。 */
    List<ImageReadiness> list(String host);
}
