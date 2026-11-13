package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MCP 服务器配置仓储（设置页）。 */
public interface McpServerRepository {

    List<McpServer> findAll();

    Optional<McpServer> find(String id);

    /** 新建或更新；headers 为空表示沿用旧值。 */
    McpServer save(McpServerView view, Map<String, String> headers, String updatedBy);

    void delete(String id);

    /** 解密取 headers（仅供后端发起 MCP 调用；不返回给前端）。 */
    Map<String, String> headersOf(String id);

    /** 写入模型（id 为空 = 新建）。 */
    record McpServerView(
            String id,
            String name,
            String transport,
            String command,
            List<String> args,
            String url,
            boolean enabled) {
    }
}
