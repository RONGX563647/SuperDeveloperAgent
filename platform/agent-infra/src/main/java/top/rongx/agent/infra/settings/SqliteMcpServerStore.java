package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.McpServer;
import top.rongx.agent.domain.settings.McpServerRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * MCP 服务器配置（SQLite）。
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

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS mcp_servers (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL UNIQUE,
                transport       TEXT NOT NULL,
                command         TEXT,
                args            TEXT,
                url             TEXT,
                headers_enc     TEXT,
                headers_iv      TEXT,
                enabled         INTEGER NOT NULL DEFAULT 1,
                updated_by      TEXT,
                updated_at      INTEGER NOT NULL
            )
            """;

    private static final String COLUMNS = "id, name, transport, command, args, url, "
            + "headers_enc, headers_iv, enabled, updated_at";

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteMcpServerStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        this.secretBox = secretBox;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(SCHEMA)) {
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("mcp_servers 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public List<McpServer> findAll() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM mcp_servers ORDER BY updated_at DESC")) {
            try (ResultSet rs = st.executeQuery()) {
                List<McpServer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("MCP 列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<McpServer> find(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM mcp_servers WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("MCP 查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public McpServer save(McpServerView view, Map<String, String> headers, String updatedBy) {
        String transport = normalizeTransport(view.transport());
        String name = requireName(view.name());
        if ("stdio".equals(transport) && blank(view.command())) {
            throw new IllegalArgumentException("stdio 传输必须填写启动命令（如 npx）");
        }
        if ("http".equals(transport) && blank(view.url())) {
            throw new IllegalArgumentException("http 传输必须填写服务 URL");
        }
        String id = blank(view.id()) ? newId() : view.id();
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            boolean exists = exists(conn, id);
            SecretBox.Sealed sealed = headers == null || headers.isEmpty()
                    ? null : secretBox.encrypt(joinHeaders(headers));
            if (exists) {
                update(conn, view, id, transport, name, sealed, updatedBy, now);
            } else {
                insert(conn, view, id, transport, name, sealed, updatedBy, now);
            }
            return find(id).orElseThrow();
        } catch (SQLException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("UNIQUE")) {
                throw new IllegalArgumentException("名称已存在：" + name);
            }
            throw new IllegalStateException("MCP 保存失败：" + message, e);
        }
    }

    private void insert(Connection conn, McpServerView v, String id, String transport, String name,
                        SecretBox.Sealed sealed, String updatedBy, long now) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                INSERT INTO mcp_servers (id, name, transport, command, args, url,
                    headers_enc, headers_iv, enabled, updated_by, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            st.setString(1, id);
            st.setString(2, name);
            st.setString(3, transport);
            st.setString(4, clean(v.command()));
            st.setString(5, joinArgs(v.args()));
            st.setString(6, clean(v.url()));
            st.setString(7, sealed == null ? null : sealed.cipherB64());
            st.setString(8, sealed == null ? null : sealed.ivB64());
            st.setInt(9, v.enabled() ? 1 : 0);
            st.setString(10, updatedBy);
            st.setLong(11, now);
            st.executeUpdate();
        }
    }

    private void update(Connection conn, McpServerView v, String id, String transport, String name,
                        SecretBox.Sealed sealed, String updatedBy, long now) throws SQLException {
        String sql = sealed == null
                ? """
                UPDATE mcp_servers SET name=?, transport=?, command=?, args=?, url=?, enabled=?,
                    updated_by=?, updated_at=? WHERE id=?
                """
                : """
                UPDATE mcp_servers SET name=?, transport=?, command=?, args=?, url=?, enabled=?,
                    headers_enc=?, headers_iv=?, updated_by=?, updated_at=? WHERE id=?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            int i = 1;
            st.setString(i++, name);
            st.setString(i++, transport);
            st.setString(i++, clean(v.command()));
            st.setString(i++, joinArgs(v.args()));
            st.setString(i++, clean(v.url()));
            st.setInt(i++, v.enabled() ? 1 : 0);
            if (sealed != null) {
                st.setString(i++, sealed.cipherB64());
                st.setString(i++, sealed.ivB64());
            }
            st.setString(i++, updatedBy);
            st.setLong(i++, now);
            st.setString(i, id);
            st.executeUpdate();
        }
    }

    private boolean exists(Connection conn, String id) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT 1 FROM mcp_servers WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void delete(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("DELETE FROM mcp_servers WHERE id = ?")) {
            st.setString(1, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("MCP 删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> headersOf(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT headers_enc, headers_iv FROM mcp_servers WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                String enc = rs.getString("headers_enc");
                String iv = rs.getString("headers_iv");
                if (enc == null || iv == null) {
                    return Map.of();
                }
                return parseHeaders(secretBox.decrypt(enc, iv));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("MCP 请求头读取失败：" + e.getMessage(), e);
        }
    }

    private McpServer map(ResultSet rs) throws SQLException {
        String enc = rs.getString("headers_enc");
        String iv = rs.getString("headers_iv");
        List<String> headerNames = enc == null || iv == null
                ? List.of()
                : new ArrayList<>(parseHeaders(secretBox.decrypt(enc, iv)).keySet());
        return new McpServer(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("transport"),
                rs.getString("command"),
                splitArgs(rs.getString("args")),
                rs.getString("url"),
                headerNames,
                rs.getInt("enabled") == 1,
                rs.getLong("updated_at"));
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
            throw new IllegalArgumentException("transport 只能是 stdio 或 http");
        }
        return t;
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isBlank() || n.length() > 64) {
            throw new IllegalArgumentException("名称必填且不超过 64 字符");
        }
        if (!n.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("名称只能包含字母、数字、下划线、点与短横线");
        }
        return n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String newId() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        return "mcp-" + HexFormat.of().formatHex(raw);
    }
}
