package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 外部能力市场（MCP 官方 Registry / 公开 Skill 仓库）。
 *
 * <p>市场内容是远端目录的只读投影，平台不再内置虚构条目；安装时再按 id 取回
 * 安装模板，避免信任前端回传的命令。端口放在 domain，网关与 Agent 工具共用。
 */
public interface CapabilityMarket {

    enum Kind {
        MCP, SKILL;

        public static Optional<Kind> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * 市场条目：只含公开元数据与安装模板，不含任何密钥。
     * MCP 用 transport / command / args / url；Skill 用 content（SKILL.md 原文），
     * 安装时 {@code files} 带上技能目录下的脚本/参考资料（相对路径 → 文本内容）。
     */
    record Entry(String id, Kind kind, String name, String title, String description,
                 List<String> tags, String source, String transport, String command,
                 List<String> args, String url, String content, List<String> requiredEnv,
                 Map<String, String> files) {

        /** 兼容只带 SKILL.md 的条目（MCP 与旧调用）。 */
        public Entry(String id, Kind kind, String name, String title, String description,
                     List<String> tags, String source, String transport, String command,
                     List<String> args, String url, String content, List<String> requiredEnv) {
            this(id, kind, name, title, description, tags, source, transport, command,
                    args, url, content, requiredEnv, Map.of());
        }
    }

    /** 按类型与关键词检索；远端不可达时抛 IllegalStateException（由调用方呈现给用户）。 */
    List<Entry> search(Kind kind, String keyword);

    /**
     * 分页 + 强制同步（市场懒加载）。
     *
     * @param offset  从第几条开始
     * @param limit   本页条数
     * @param refresh true = 忽略缓存，重新同步远端仓库 / Registry
     */
    default Page page(Kind kind, String keyword, int offset, int limit, boolean refresh) {
        List<Entry> all = search(kind, keyword);
        int from = Math.max(0, Math.min(offset, all.size()));
        int to = (int) Math.max(from,
                Math.min((long) from + Math.max(1, limit), all.size()));
        return new Page(List.copyOf(all.subList(from, to)), all.size(), to < all.size());
    }

    /** 市场分页结果。 */
    record Page(List<Entry> items, int total, boolean hasMore) {
    }

    /** 按 id 取回安装模板；不存在或市场已下架时返回 empty。 */
    Optional<Entry> find(String id);
}
