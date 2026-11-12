package top.rongx.agent.domain.env;

/**
 * 密钥条目（只读视图）：不包含密文或明文。
 *
 * @param projectId 项目
 * @param env       环境
 * @param name      引用名（{@code secret://name} 中的 name）
 * @param version   版本，轮换时 +1
 * @param updatedAt 最近更新时间
 */
public record SecretEntry(String projectId, String env, String name, int version, long updatedAt) {
}
