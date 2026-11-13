package top.rongx.agent.infra.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.audit.ToolAudit;
import top.rongx.agent.domain.audit.ToolAuditStore;

/**
 * 工具调用审计仓储（SQLite，与项目/环境共用 projects.db）。
 *
 * <p>只追加，不更新；参数只存哈希，避免密钥/令牌进入审计表。
 */
@Repository
public class SqliteToolAuditStore implements ToolAuditStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteToolAuditStore.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS tool_audit (
                id          TEXT PRIMARY KEY,
                session_id  TEXT,
                user_id     TEXT,
                project_id  TEXT,
                env         TEXT,
                tool        TEXT NOT NULL,
                args_hash   TEXT,
                result_code TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_tool_audit_project
                ON tool_audit(project_id, created_at);
            """;

    private final Path dbFile;

    public SqliteToolAuditStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        createParent();
        initSchema();
    }

    private void createParent() {
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建审计目录：" + e.getMessage(), e);
        }
    }

    private void initSchema() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.executeUpdate(ddl);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工具审计表初始化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void record(ToolAudit audit) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO tool_audit
                         (id, session_id, user_id, project_id, env, tool, args_hash,
                          result_code, duration_ms, created_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, audit.id());
            st.setString(2, audit.sessionId());
            st.setString(3, audit.userId());
            st.setString(4, audit.projectId());
            st.setString(5, audit.env());
            st.setString(6, audit.tool());
            st.setString(7, audit.argsHash());
            st.setString(8, audit.resultCode());
            st.setLong(9, audit.durationMs());
            st.setLong(10, audit.createdAt());
            st.executeUpdate();
        } catch (SQLException e) {
            // 审计失败不能影响主流程，只记服务端日志。
            log.warn("工具审计写入失败：{}", e.getMessage());
        }
    }

    @Override
    public List<ToolAudit> recent(String projectId, int limit) {
        String sql = projectId == null || projectId.isBlank()
                ? "SELECT * FROM tool_audit ORDER BY created_at DESC LIMIT ?"
                : "SELECT * FROM tool_audit WHERE project_id=? ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            if (projectId == null || projectId.isBlank()) {
                st.setInt(1, Math.max(1, limit));
            } else {
                st.setString(1, projectId);
                st.setInt(2, Math.max(1, limit));
            }
            try (ResultSet rs = st.executeQuery()) {
                List<ToolAudit> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ToolAudit(rs.getString("id"), rs.getString("session_id"),
                            rs.getString("user_id"), rs.getString("project_id"),
                            rs.getString("env"), rs.getString("tool"),
                            rs.getString("args_hash"), rs.getString("result_code"),
                            rs.getLong("duration_ms"), rs.getLong("created_at")));
                }
                return out;
            }
        } catch (SQLException e) {
            log.warn("查询工具审计失败：{}", e.getMessage());
            return List.of();
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }
}
