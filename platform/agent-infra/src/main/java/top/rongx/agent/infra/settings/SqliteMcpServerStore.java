package top.rongx.agent.infra.settings;

import top.rongx.agent.domain.id.Ids;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.McpServer;
import top.rongx.agent.domain.settings.McpServerRepository;
import top.rongx.agent.infra.security.SecretBox;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * MCP 服务器配置。
 *
 * <p>形状对齐 Claude Desktop 的 {@code mcpServers}：
 * <ul>
 *   <li>stdio：{@code command} + 每行一个 {@code args}；</li>
 *   <li>http：{@code url} + 请求头（一行 {@code Key: Value}，值加密落库）。</li>
 * </ul>
 * 读取只返回表头名，不回显 header 值。
 */
@Repository
public class SqliteMcpServerStore implements McpServerRepository {

    private static final String COLUMNS = "id, name, transport, command, args, url, "
            + "headers_enc, headers_iv, enabled, updated_at";

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public SqliteMcpServerStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteMcpServerStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.at(Path.of(workspaceRoot)), secretBox);
    }

    @Override
    public List<McpServer> findAll() {
        try {
            return db.read(em -> PlatformDb.rows(em,
                            "SELECT " + COLUMNS + " FROM mcp_servers ORDER BY updated_at DESC").stream()
                    .map(this::map).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<McpServer> find(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + COLUMNS + " FROM mcp_servers WHERE id = ?", id)
                    .map(this::map));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public McpServer save(McpServerView view, Map<String, String> headers, String updatedBy) {
        String transport = normalizeTransport(view.transport());
        String name = requireName(view.name());
        if ("stdio".equals(transport) && blank(view.command())) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "stdio 传输必须填写启动命令（如 npx）");
        }
        if ("http".equals(transport) && blank(view.url())) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "http 传输必须填写服务 URL");
        }
        String id = blank(view.id()) ? Ids.newId("mcp") : view.id();
        long now = System.currentTimeMillis();
        try {
            db.inTx(em -> {
                boolean exists = PlatformDb.first(em,
                        "SELECT 1 FROM mcp_servers WHERE id = ?", id).isPresent();
                SecretBox.Sealed sealed = headers == null || headers.isEmpty()
                        ? null : secretBox.encrypt(joinHeaders(headers));
                if (exists) {
                    update(em, view, id, transport, name, sealed, updatedBy, now);
                } else {
                    insert(em, view, id, transport, name, sealed, updatedBy, now);
                }
                return null;
            });
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("UNIQUE")) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "名称已存在：" + name);
            }
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 保存失败：" + message, e);
        }
        return find(id).orElseThrow();
    }

    private void insert(EntityManager em, McpServerView v, String id, String transport, String name,
                        SecretBox.Sealed sealed, String updatedBy, long now) {
        PlatformDb.update(em, """
                INSERT INTO mcp_servers (id, name, transport, command, args, url,
                    headers_enc, headers_iv, enabled, updated_by, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, name, transport, clean(v.command()), joinArgs(v.args()), clean(v.url()),
                sealed == null ? null : sealed.cipherB64(),
                sealed == null ? null : sealed.ivB64(),
                v.enabled() ? 1 : 0, updatedBy, now);
    }

    private void update(EntityManager em, McpServerView v, String id, String transport, String name,
                        SecretBox.Sealed sealed, String updatedBy, long now) {
        if (sealed == null) {
            PlatformDb.update(em, """
                    UPDATE mcp_servers SET name=?, transport=?, command=?, args=?, url=?, enabled=?,
                        updated_by=?, updated_at=? WHERE id=?
                    """,
                    name, transport, clean(v.command()), joinArgs(v.args()), clean(v.url()),
                    v.enabled() ? 1 : 0, updatedBy, now, id);
        } else {
            PlatformDb.update(em, """
                    UPDATE mcp_servers SET name=?, transport=?, command=?, args=?, url=?, enabled=?,
                        headers_enc=?, headers_iv=?, updated_by=?, updated_at=? WHERE id=?
                    """,
                    name, transport, clean(v.command()), joinArgs(v.args()), clean(v.url()),
                    v.enabled() ? 1 : 0, sealed.cipherB64(), sealed.ivB64(), updatedBy, now, id);
        }
    }

    @Override
    public void delete(String id) {
        try {
            db.run(em -> PlatformDb.update(em, "DELETE FROM mcp_servers WHERE id = ?", id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> headersOf(String id) {
        try {
            return db.read(em -> {
                Optional<Object[]> row = PlatformDb.row(em,
                        "SELECT headers_enc, headers_iv FROM mcp_servers WHERE id = ?", id);
                if (row.isEmpty()) {
                    return Map.of();
                }
                String enc = (String) row.get()[0];
                String iv = (String) row.get()[1];
                if (enc == null || iv == null) {
                    return Map.of();
                }
                return parseHeaders(secretBox.decrypt(enc, iv));
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 请求头读取失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 工具开关

    @Override
    public List<String> disabledToolsOf(String id) {
        if (id == null || id.isBlank()) {
            return List.of();
        }
        try {
            return db.read(em -> PlatformDb.rows(em,
                            "SELECT tool_name, server_id FROM mcp_disabled_tools "
                                    + "WHERE server_id = ? ORDER BY tool_name", id).stream()
                    .map(row -> (String) row[0]).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 工具开关读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void setDisabledTools(String id, List<String> tools) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            db.inTx(em -> {
                PlatformDb.update(em, "DELETE FROM mcp_disabled_tools WHERE server_id = ?", id);
                if (tools == null || tools.isEmpty()) {
                    return null;
                }
                for (String tool : tools) {
                    if (tool != null && !tool.isBlank()) {
                        PlatformDb.update(em, """
                                INSERT INTO mcp_disabled_tools (server_id, tool_name) VALUES (?,?)
                                ON CONFLICT(server_id, tool_name) DO NOTHING
                                """, id, tool.trim());
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "MCP 工具开关保存失败：" + e.getMessage(), e);
        }
    }

    private McpServer map(Object[] row) {
        String enc = (String) row[6];
        String iv = (String) row[7];
        List<String> headerNames = enc == null || iv == null
                ? List.of()
                : new ArrayList<>(parseHeaders(secretBox.decrypt(enc, iv)).keySet());
        return new McpServer(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                splitArgs((String) row[4]),
                (String) row[5],
                headerNames,
                ((Number) row[8]).intValue() == 1,
                ((Number) row[9]).longValue());
    }

    // ---------------------------------------------------------------- 文本编解码

    private static String joinArgs(List<String> args) {
        return args == null ? "" : String.join("\n", args.stream()
                .filter(a -> a != null && !a.isBlank()).toList());
    }

    private static List<String> splitArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\R")).filter(s -> !s.isBlank()).toList();
    }

    private static String joinHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> {
            if (k != null && !k.isBlank()) {
                sb.append(k.trim()).append('=').append(v == null ? "" : v.trim()).append('\n');
            }
        });
        return sb.toString();
    }

    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\\R")) {
            int idx = line.indexOf('=');
            if (idx > 0) {
                out.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return out;
    }

    private static String normalizeTransport(String transport) {
        String t = transport == null || transport.isBlank()
                ? "stdio" : transport.trim().toLowerCase(Locale.ROOT);
        if (!t.equals("stdio") && !t.equals("http")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "transport 只能是 stdio 或 http");
        }
        return t;
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isBlank() || n.length() > 64) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "名称必填且不超过 64 字符");
        }
        if (!n.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "名称只能包含字母、数字、下划线、点与短横线");
        }
        return n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

}
