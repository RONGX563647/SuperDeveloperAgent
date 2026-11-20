package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import top.rongx.agent.domain.error.PlatformException;

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
            } catch (PlatformException | IllegalArgumentException e) {
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

    /**
     * 市场来源：一个可独立接入的开源目录（官方 Registry / GitHub 仓库 / 精选清单）。
     *
     * <p>来源不再写死成「单一仓库」：设置页据此渲染来源筛选与「如何接入」说明，
     * 运维加一个开源市场只需改配置（见 {@code agent.market.*}）。
     *
     * @param id          稳定标识，等于条目的 source 字段，用于筛选
     * @param kind        MCP / SKILL
     * @param name        展示名
     * @param url         人类可访问的主页 / 仓库地址
     * @param description 一句话说明这个来源提供什么
     * @param builtin     是否平台预置（false = 运维自定义接入）
     */
    record Source(String id, Kind kind, String name, String url,
                  String description, boolean builtin) {
    }

    /** 当前接入的市场来源清单（默认空，由实现方覆盖）。 */
    default List<Source> sources(Kind kind) {
        return List.of();
    }

    /**
     * 带来源筛选的分页：{@code source} 为空/空白时等价于普通分页（兼容旧调用）。
     */
    default Page page(Kind kind, String keyword, String source,
                      int offset, int limit, boolean refresh) {
        return page(kind, keyword, offset, limit, refresh);
    }

    /** 按 id 取回安装模板；不存在或市场已下架时返回 empty。 */
    Optional<Entry> find(String id);
}
