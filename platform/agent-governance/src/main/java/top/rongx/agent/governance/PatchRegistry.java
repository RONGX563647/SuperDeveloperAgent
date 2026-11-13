package top.rongx.agent.governance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.PatchStore;

/**
 * 待确认补丁存储（SQLite 持久化）。
 *
 * <p>为什么必须落库：补丁是「人工确认 → 应用」的凭证，进程重启后确认卡片仍要能取回
 * 文件全文与 diff，否则用户点击确认只会得到 patch 不存在。落盘位置与变更单一致：
 * {@code <workspace>/_platform/governance.db}。
 *
 * <p>文件改动拆到子表而不是塞 JSON：补丁内容可能含引号/换行/超大文件，关系表更稳，
 * 也方便将来按 path 审计。保存采用「先删后插」的整单替换，保证重复 save 幂等。
 */
@Service
public class PatchRegistry implements PatchStore {

    private static final String SCHEMA_PATCHES = """
            CREATE TABLE IF NOT EXISTS code_patches (
                patch_id   TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                env        TEXT,
                base_sha   TEXT,
                branch     TEXT,
                diff       TEXT,
                diff_hash  TEXT,
                added      INTEGER NOT NULL DEFAULT 0,
                removed    INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """;

    private static final String SCHEMA_EDITS = """
            CREATE TABLE IF NOT EXISTS code_patch_edits (
                patch_id TEXT NOT NULL,
                seq      INTEGER NOT NULL,
                path     TEXT NOT NULL,
                content  TEXT NOT NULL,
                PRIMARY KEY (patch_id, seq)
            )
            """;

    private static final String COLUMNS = """
            patch_id, project_id, env, base_sha, branch, diff, diff_hash,
            added, removed, created_at
            """;

    private final Path dbFile;

    public PatchRegistry(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("governance.db");
        initSchema();
    }

    private void initSchema() {
        try {
            Files.createDirectories(dbFile.getParent());
            try (Connection conn = open()) {
                exec(conn, SCHEMA_PATCHES);
                exec(conn, SCHEMA_EDITS);
            }
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("code_patches 表初始化失败：" + e.getMessage(), e);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.executeUpdate();
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public void save(CodePatch patch) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM code_patch_edits WHERE patch_id = ?")) {
                    del.setString(1, patch.patchId());
                    del.executeUpdate();
                }
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT OR REPLACE INTO code_patches
                            (patch_id, project_id, env, base_sha, branch, diff, diff_hash,
                             added, removed, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    st.setString(1, patch.patchId());
                    st.setString(2, patch.projectId());
                    st.setString(3, patch.env());
                    st.setString(4, patch.baseSha());
                    st.setString(5, patch.branch());
                    st.setString(6, patch.diff());
                    st.setString(7, patch.diffHash());
                    st.setInt(8, patch.added());
                    st.setInt(9, patch.removed());
                    st.setLong(10, patch.createdAt());
                    st.executeUpdate();
                }
                try (PreparedStatement edit = conn.prepareStatement("""
                        INSERT INTO code_patch_edits (patch_id, seq, path, content)
                        VALUES (?,?,?,?)
                        """)) {
                    int seq = 0;
                    for (CodePatch.FileEdit file : patch.edits()) {
                        edit.setString(1, patch.patchId());
                        edit.setInt(2, seq++);
                        edit.setString(3, file.path());
                        edit.setString(4, file.content());
                        edit.addBatch();
                    }
                    edit.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("补丁保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<CodePatch> find(String patchId) {
        if (patchId == null || patchId.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM code_patches WHERE patch_id = ?")) {
            st.setString(1, patchId);
            String projectId;
            String env;
            String baseSha;
            String branch;
            String diff;
            String diffHash;
            int added;
            int removed;
            long createdAt;
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                projectId = rs.getString("project_id");
                env = rs.getString("env");
                baseSha = rs.getString("base_sha");
                branch = rs.getString("branch");
                diff = rs.getString("diff");
                diffHash = rs.getString("diff_hash");
                added = rs.getInt("added");
                removed = rs.getInt("removed");
                createdAt = rs.getLong("created_at");
            }
            return Optional.of(new CodePatch(patchId, projectId, env, baseSha, branch,
                    edits(conn, patchId), diff, diffHash, added, removed, createdAt));
        } catch (SQLException e) {
            throw new IllegalStateException("补丁查询失败：" + e.getMessage(), e);
        }
    }

    private List<CodePatch.FileEdit> edits(Connection conn, String patchId) throws SQLException {
        List<CodePatch.FileEdit> edits = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement("""
                SELECT path, content FROM code_patch_edits
                WHERE patch_id = ? ORDER BY seq
                """)) {
            st.setString(1, patchId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    edits.add(new CodePatch.FileEdit(rs.getString("path"), rs.getString("content")));
                }
            }
        }
        return edits;
    }

    @Override
    public void remove(String patchId) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM code_patch_edits WHERE patch_id = ?")) {
                    st.setString(1, patchId);
                    st.executeUpdate();
                }
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM code_patches WHERE patch_id = ?")) {
                    st.setString(1, patchId);
                    st.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("补丁删除失败：" + e.getMessage(), e);
        }
    }

    /** 清理早于 cutoff 的补丁（确认卡片只关心近期数据）。返回删除行数。 */
    public int purgeBefore(Instant cutoff) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                long millis = cutoff.toEpochMilli();
                try (PreparedStatement st = conn.prepareStatement("""
                        DELETE FROM code_patch_edits WHERE patch_id IN
                            (SELECT patch_id FROM code_patches WHERE created_at < ?)
                        """)) {
                    st.setLong(1, millis);
                    st.executeUpdate();
                }
                int removed;
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM code_patches WHERE created_at < ?")) {
                    st.setLong(1, millis);
                    removed = st.executeUpdate();
                }
                conn.commit();
                return removed;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("补丁清理失败：" + e.getMessage(), e);
        }
    }
}
