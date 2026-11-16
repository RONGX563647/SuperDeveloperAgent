package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Optional;

/** 模型目录仓储：按连接保存「这个网关下有哪些模型」。 */
public interface ModelCatalogRepository {

    List<ModelCatalogEntry> findByConnection(String connectionId);

    Optional<ModelCatalogEntry> find(String connectionId, String modelId);

    /** 全量替换某个连接的目录（发现结果覆盖旧的自动条目，手工条目保留）。 */
    void replaceDiscovered(String connectionId, List<ModelCatalogEntry> entries);

    /** 新增/更新一条（手工模型名保存时调用）。 */
    void upsert(ModelCatalogEntry entry);

    void deleteByConnection(String connectionId);
}
