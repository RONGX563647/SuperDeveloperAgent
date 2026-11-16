package top.rongx.agent.domain.settings;

/**
 * 模型目录条目：一个模型连接（网关）下可选的模型。
 *
 * <p>来源有二：{@code discover} = 通过 {@code GET {baseUrl}/models} 自动发现；
 * {@code manual} = 设置页手工填写（本地网关没有 /models 时的兜底）。
 */
public record ModelCatalogEntry(
        String connectionId,
        String modelId,
        String displayName,
        String source,
        long updatedAt) {

    public ModelCatalogEntry {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        modelId = modelId.trim();
        displayName = displayName == null || displayName.isBlank() ? modelId : displayName.trim();
        source = source == null || source.isBlank() ? "discover" : source;
    }
}
