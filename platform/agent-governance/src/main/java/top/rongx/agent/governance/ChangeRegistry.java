package top.rongx.agent.governance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.PlanHasher;
import top.rongx.agent.domain.port.ChangeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 变更单存储（SQLite 持久化）。
 *
 * <p>职责边界：<b>只负责登记与查询</b>。签发确认令牌必须由 {@link ConfirmTokenService}
 * 在"用户已登录的请求链路"中完成，工具无权调用。
 *
 * <p>planHash 保证：{@link #request} 把 imageDigest / frontendDistHash 一并纳入哈希
 * （确认后换 digest 会使令牌失效）；{@link #registerWithHash} 由调用方（动作路径）提供
 * "基于实际配方"的哈希。
 *
 * <p>持久化原因：变更单是"人工确认—执行"的凭证，进程重启后仍必须能查到并核验，
 * 否则确认卡片会凭空消失。落盘位置与其它平台数据一致：{@code <workspace>/_platform/governance.db}。
 */
@Service
public class ChangeRegistry implements ChangeStore {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS change_plans (
                change_id          TEXT PRIMARY KEY,
                project_id         TEXT NOT NULL,
                env                TEXT NOT NULL,
                kind               TEXT NOT NULL,
                plan_hash          TEXT NOT NULL,
                summary            TEXT,
                image_digest       TEXT,
                frontend_dist_hash TEXT,
                high_risk          INTEGER NOT NULL DEFAULT 0,
                created_at         INTEGER NOT NULL
            )
            """;

    private final Path dbFile;

    public ChangeRegistry(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("governance.db");
        initSchema();
    }

    private void initSchema() {
        try {
            Files.createDirectories(dbFile.getParent());
            try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(SCHEMA)) {
                st.executeUpdate();
            }
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("change_plans 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public ChangePlan request(String projectId, String env, String kind, String summary,
                              String imageDigest, String frontendDistHash, boolean highRisk) {
        String planHash = PlanHasher.hash(projectId, env, kind, imageDigest, frontendDistHash, summary);
        return save(new ChangePlan("chg-" + UUID.randomUUID(), projectId, env, kind, planHash,
                summary, imageDigest, frontendDistHash, highRisk, Instant.now()));
    }

    @Override
    public ChangePlan registerWithHash(String projectId, String env, String kind, String summary,
                                       String planHash, boolean highRisk) {
        return save(new ChangePlan("chg-" + UUID.randomUUID(), projectId, env, kind, planHash,
                summary, null, null, highRisk, Instant.now()));
    }

    private ChangePlan save(ChangePlan plan) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO change_plans
                         (change_id, project_id, env, kind, plan_hash, summary,
                          image_digest, frontend_dist_hash, high_risk, created_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, plan.changeId());
            st.setString(2, plan.projectId());
            st.setString(3, plan.env());
            st.setString(4, plan.kind());
            st.setString(5, plan.planHash());
            st.setString(6, plan.summary());
            st.setString(7, plan.imageDigest());
            st.setString(8, plan.frontendDistHash());
            st.setInt(9, plan.highRisk() ? 1 : 0);
            st.setLong(10, plan.createdAt().toEpochMilli());
            st.executeUpdate();
            return plan;
        } catch (SQLException e) {
            throw new IllegalStateException("变更单登记失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ChangePlan> find(String changeId) {
        if (changeId == null || changeId.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT change_id, project_id, env, kind, plan_hash, summary,
                            image_digest, frontend_dist_hash, high_risk, created_at
                     FROM change_plans WHERE change_id = ?
                     """)) {
            st.setString(1, changeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ChangePlan(
                        rs.getString("change_id"),
                        rs.getString("project_id"),
                        rs.getString("env"),
                        rs.getString("kind"),
                        rs.getString("plan_hash"),
                        rs.getString("summary"),
                        rs.getString("image_digest"),
                        rs.getString("frontend_dist_hash"),
                        rs.getInt("high_risk") == 1,
                        Instant.ofEpochMilli(rs.getLong("created_at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("变更单查询失败：" + e.getMessage(), e);
        }
    }
}
