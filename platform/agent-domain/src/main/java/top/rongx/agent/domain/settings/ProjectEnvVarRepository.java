package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Map;

/**
 * 项目环境变量仓储（设置页维护 + 部署器读取）。
 *
 * <p>安全边界：
 * <ul>
 *   <li>所有值 AES-GCM 加密落库（即使标记为非敏感）；</li>
 *   <li>{@link #findAll} 只返回掩码/非敏感明文，供设置页展示；</li>
 *   <li>{@link #resolve} 返回明文，只允许执行面（部署器）调用。</li>
 * </ul>
 */
public interface ProjectEnvVarRepository {

    /** 某项目某环境的变量列表（敏感项已掩码）。 */
    List<ProjectEnvVar> findAll(String projectId, String env);

    /** 部署时使用：解密出全部 KEY=VALUE（按 key 排序，保证注入顺序稳定）。 */
    Map<String, String> resolve(String projectId, String env);

    /**
     * 新建或更新一个变量。
     *
     * @param value 明文；为 {@code null} 或空串时表示"保持原值"（仅更新 secret 标记）
     * @return 保存后的视图（敏感项已掩码）
     */
    ProjectEnvVar save(String projectId, String env, String key, String value,
                       boolean secret, String updatedBy);

    /** 删除一个变量；不存在时不报错。 */
    void delete(String projectId, String env, String key);
}
