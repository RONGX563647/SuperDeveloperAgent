package top.rongx.agent.domain.settings;

import java.nio.file.Path;
import java.util.Set;

/**
 * 技能目录（领域端口）。
 *
 * <p>运行层只需要"技能根目录 + 默认启用的技能名"，不关心它落在 SQLite、文件还是远端。
 */
public interface SkillCatalog {

    /** 技能根目录（交给 AgentScope 的 FileSystemSkillRepository）。 */
    Path root();

    /** 默认启用的技能目录名。 */
    Set<String> enabledNames();
}
