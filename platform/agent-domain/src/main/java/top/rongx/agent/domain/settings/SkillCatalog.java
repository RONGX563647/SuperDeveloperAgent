package top.rongx.agent.domain.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 技能目录（领域端口）。
 *
 * <p>运行层只需要"技能根目录 + 默认启用的技能名"，不关心它落在 SQLite、文件还是远端。
 *
 * <p><b>渐进披露</b>：对话默认只把技能名与 description 放进提示词（{@link #entries()}），
 * 正文（{@link #content(String)}）由模型按需通过 {@code skill_load} 读取——
 * 避免每个会话都把全部 SKILL.md 全文塞进上下文。
 */
public interface SkillCatalog {

    /** 技能索引条目：目录名 + 展示名 + 描述 + 是否启用。 */
    record Entry(String name, String title, String description, boolean enabled) {
    }

    /** 技能根目录（历史实现会用到；渐进披露后对话侧不再依赖它）。 */
    Path root();

    /** 默认启用的技能目录名。 */
    Set<String> enabledNames();

    /** 技能索引（不含正文）；目录不存在时返回空列表。 */
    List<Entry> entries();

    /** 读取 SKILL.md 正文；不存在返回 empty。 */
    Optional<String> content(String name);
}
