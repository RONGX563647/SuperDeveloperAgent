package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Locale;
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
     * MCP 用 transport / command / args / url；Skill 用 content（SKILL.md 原文）。
     */
    record Entry(String id, Kind kind, String name, String title, String description,
                 List<String> tags, String source, String transport, String command,
                 List<String> args, String url, String content, List<String> requiredEnv) {
    }

    /** 按类型与关键词检索；远端不可达时抛 IllegalStateException（由调用方呈现给用户）。 */
    List<Entry> search(Kind kind, String keyword);

    /** 按 id 取回安装模板；不存在或市场已下架时返回 empty。 */
    Optional<Entry> find(String id);
}
