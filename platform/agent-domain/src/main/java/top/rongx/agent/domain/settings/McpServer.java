package top.rongx.agent.domain.settings;

import java.util.List;

/**
 * MCP 服务器配置（设置页「MCP 接入」）。
 *
 * <p>配置形状对齐 Claude Desktop / Claude Code 的 {@code mcpServers}：
 * stdio 用 {@code command + args}，HTTP 用 {@code url + headers}。
 * headers 的值只写不读（返回 key 列表与掩码）。
 */
public record McpServer(
        String id,
        String name,
        String transport,
        String command,
        List<String> args,
        String url,
        List<String> headerNames,
        boolean enabled,
        long updatedAt) {
}
